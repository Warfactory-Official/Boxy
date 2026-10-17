package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.VSSConstants;
import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.common.processing.SequenceCounter;
import com.golem.boxy.vss.config.VSSServerConfig;
import com.golem.boxy.vss.net.VssChannels;
import com.golem.boxy.vss.payloads.BatchResponseS2CPayload;
import com.golem.boxy.vss.payloads.VoxelColumnS2CPayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Fog-of-war baseline: beyond {@code liveDistanceChunks} a column is served from a region-file copy taken every
 * {@code baselineRefreshMinutes}, stamped with the copy's per-chunk save time; never live content.
 * <ul>
 * <li>Copy: unchanged region (size + mtime) => hardlink to the previous epoch; else copy. No save flush: unsaved
 * edits are simply absent (older = no leak). Copy concurrent with saves => torn chunk => position check, skipped.</li>
 * <li>Late: first FULL save of a chunk the copy lacks (or holds below FULL) => frozen into {@code late/}; served after
 * the copy. Leak window = generation -> first FULL save (vanilla eager save 10 s).</li>
 * <li>Serve: neither => client version kept ({@code ts > 0}) or normal route ({@code ts <= 0}).</li>
 * </ul>
 */
public final class BaselineSnapshots {
    private static final String CURRENT = "CURRENT";
    private static final String LATE = "late";
    private static final String FULL = "minecraft:full";

    private record Epoch(long seconds, Path root) {}

    private record Answer(UUID player, int requestId, byte type) {}

    private final MinecraftServer server;
    private final Path base;
    private final SequenceCounter sequence;
    private final ThreadPoolExecutor readers;
    private final Thread.Builder copier = Thread.ofPlatform().daemon().name("Boxy baseline copy").priority(Thread.MIN_PRIORITY);
    private final ThreadPoolExecutor lateWriter = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
            Thread.ofPlatform().daemon().name("Boxy baseline late").priority(Thread.MIN_PRIORITY).factory());
    private volatile Epoch epoch;
    private volatile Thread copying;
    /** Region files of the current epoch: path -> (file, header timestamps). */
    private final Map<Path, Region> regions = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Answer> answers = new ConcurrentLinkedQueue<>();
    /** Diagnostics: chunks frozen into late/, columns served from it. */
    private final AtomicInteger lateCaptured = new AtomicInteger(), lateServed = new AtomicInteger();

    /** {@code full}: copy chunks read and found FULL (late capture skips them). Guarded by {@code file}. */
    private record Region(RegionFile file, int[] stamps, BitSet full) {}

    BaselineSnapshots(MinecraftServer server, SequenceCounter sequence) {
        this.server = server;
        this.sequence = sequence;
        this.base = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("boxy_baseline");
        int threads = VSSServerConfig.CONFIG.diskReaderThreads;
        this.readers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(threads * 256),
                Thread.ofPlatform().daemon().name("Boxy baseline read #", 0).priority(Thread.MIN_PRIORITY).factory());
        try {
            Path current = this.base.resolve(CURRENT);
            if (Files.exists(current)) {
                long seconds = Long.parseLong(Files.readString(current).trim());
                this.epoch = new Epoch(seconds, this.base.resolve(Long.toString(seconds)));
            }
        } catch (IOException | NumberFormatException e) {
            VSSLogger.error("Unreadable baseline pointer; next copy replaces it", e);
        }
    }

    /** Epoch seconds of the active copy; 0 = none (fog falls back to "client keeps its version"). */
    long epochSeconds() {
        Epoch e = this.epoch;
        return e == null ? 0L : e.seconds;
    }

    void tick() {
        int minutes = VSSServerConfig.CONFIG.baselineRefreshMinutes;
        if (minutes <= 0 || this.copying != null) {
            return;
        }
        if (VSSConstants.epochSeconds() - epochSeconds() >= minutes * 60L) {
            List<ServerLevel> levels = new ArrayList<>();
            this.server.getAllLevels().forEach(levels::add);
            this.copying = this.copier.start(() -> copy(levels));
        }
        Answer a;
        Map<UUID, List<Answer>> byPlayer = null;
        while ((a = this.answers.poll()) != null) {
            if (byPlayer == null) byPlayer = new java.util.HashMap<>();
            byPlayer.computeIfAbsent(a.player, k -> new ArrayList<>()).add(a);
        }
        if (byPlayer != null) {
            byPlayer.forEach((uuid, list) -> {
                var player = this.server.getPlayerList().getPlayer(uuid);
                if (player == null) return;
                for (int off = 0; off < list.size(); off += VSSConstants.MAX_BATCH_RESPONSES) {
                    int n = Math.min(VSSConstants.MAX_BATCH_RESPONSES, list.size() - off);
                    byte[] types = new byte[n];
                    int[] ids = new int[n];
                    for (int i = 0; i < n; i++) {
                        types[i] = list.get(off + i).type;
                        ids[i] = list.get(off + i).requestId;
                    }
                    VssChannels.sendToClient(player, new BatchResponseS2CPayload(types, ids, n));
                }
            });
        }
    }

    private void copy(List<ServerLevel> levels) {
        try {
            Epoch prev = this.epoch;
            long started = VSSConstants.epochSeconds();
            Path root = this.base.resolve(Long.toString(started));
            Path worldRoot = this.server.getWorldPath(LevelResource.ROOT);
            int copied = 0, linked = 0;
            for (ServerLevel level : levels) {
                Path src = DimensionType.getStorageFolder(level.dimension(), worldRoot).resolve("region");
                if (!Files.isDirectory(src)) continue;
                Path dst = root.resolve(dimensionFolder(level));
                Files.createDirectories(dst);
                Path old = prev == null ? null : prev.root.resolve(dimensionFolder(level));
                List<Path> files;
                try (Stream<Path> s = Files.list(src)) {
                    files = s.filter(p -> p.getFileName().toString().endsWith(".mca")).toList();
                }
                for (Path f : files) {
                    String name = f.getFileName().toString();
                    FileTime mtime = Files.getLastModifiedTime(f);
                    long size = Files.size(f);
                    Path was = old == null ? null : old.resolve(name);
                    if (was != null && Files.exists(was) && Files.size(was) == size && Files.getLastModifiedTime(was).equals(mtime)
                            && link(dst.resolve(name), was)) {
                        linked++;
                    } else {
                        Files.copy(f, dst.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                        // Pre-copy mtime: a save during the copy leaves the source newer => recopied next epoch.
                        Files.setLastModifiedTime(dst.resolve(name), mtime);
                        copied++;
                    }
                }
            }
            // Content may include saves made during the copy => stamp the epoch at its end.
            long seconds = VSSConstants.epochSeconds();
            Path finalRoot = this.base.resolve(Long.toString(seconds));
            if (!finalRoot.equals(root)) Files.move(root, finalRoot);
            Path tmp = this.base.resolve(CURRENT + ".tmp");
            Files.writeString(tmp, Long.toString(seconds));
            Files.move(tmp, this.base.resolve(CURRENT), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            this.epoch = new Epoch(seconds, finalRoot);
            closeRegions();
            try (Stream<Path> s = Files.list(this.base)) {
                for (Path p : s.filter(p -> Files.isDirectory(p) && !p.equals(finalRoot)).toList()) delete(p);
            }
            VSSLogger.info("Baseline " + seconds + ": " + copied + " region files copied, " + linked + " linked, "
                    + (seconds - started) + " s");
        } catch (IOException e) {
            VSSLogger.error("Baseline copy failed; keeping the previous one", e);
        } finally {
            this.copying = null;
        }
    }

    /** Filesystem without hardlinks => false, caller copies. */
    private static boolean link(Path link, Path target) {
        try {
            Files.createLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    private static String dimensionFolder(ServerLevel level) {
        var id = level.dimension().location();
        return id.getNamespace() + "_" + id.getPath().replace('/', '_');
    }

    private static void delete(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    /**
     * Beyond-live request, answered off the server thread. {@code fallback} (any thread): copy lacks the chunk, client
     * holds nothing.
     */
    void submit(PlayerRequestState state, ServerLevel level, int requestId, int cx, int cz, long clientTimestamp,
                Runnable fallback) {
        Epoch e = this.epoch;
        UUID player = state.getPlayer().getUUID();
        try {
            this.readers.execute(() -> serve(e, state, player, level, requestId, cx, cz, clientTimestamp, fallback));
        } catch (RejectedExecutionException saturated) {
            // Never the live route: client re-requests.
            this.answers.add(new Answer(player, requestId, VSSConstants.RESPONSE_RATE_LIMITED));
        }
    }

    private void serve(Epoch e, PlayerRequestState state, UUID player, ServerLevel level, int requestId, int cx, int cz,
                       long clientTimestamp, Runnable fallback) {
        try {
            String dim = dimensionFolder(level), name = regionName(cx, cz);
            int i = (cx & 31) + (cz & 31) * 32;
            byte[] bytes = null;
            int stamp = 0;
            for (Path dir : new Path[] {e.root.resolve(dim), e.root.resolve(LATE).resolve(dim)}) {
                Region region = region(dir.resolve(name), level);
                stamp = region == null ? 0 : region.stamps[i];
                if (stamp == 0) {
                    continue;
                }
                if (clientTimestamp > 0L && stamp <= clientTimestamp) {
                    this.answers.add(new Answer(player, requestId, VSSConstants.RESPONSE_UP_TO_DATE));
                    return;
                }
                CompoundTag nbt = read(region, cx, cz);
                bytes = nbt == null ? null : NbtSectionSerializer.serialize(nbt, level.registryAccess(), level);
                if (bytes != null) {
                    if (dir.endsWith(Path.of(LATE, dim))) {
                        this.lateServed.incrementAndGet();
                    }
                    break;
                }
            }
            if (bytes == null) {
                miss(player, requestId, clientTimestamp, fallback);
                return;
            }
            VoxelColumnS2CPayload payload = new VoxelColumnS2CPayload(requestId, cx, cz, level.dimension(), stamp, bytes);
            state.addReadyPayload(new PlayerRequestState.QueuedPayload(payload, requestId,
                    bytes.length + VSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES, this.sequence.next()));
        } catch (Exception ex) {
            VSSLogger.error("Baseline read failed at " + cx + ", " + cz, ex);
            miss(player, requestId, clientTimestamp, fallback);
        }
    }

    /** Torn (copy concurrent with a save) => null. */
    private static CompoundTag read(Region region, int cx, int cz) throws IOException {
        CompoundTag nbt;
        synchronized (region.file) {
            try (DataInputStream in = region.file.getChunkDataInputStream(new ChunkPos(cx, cz))) {
                nbt = in == null ? null : NbtIo.read(in);
            }
        }
        return nbt == null || nbt.getInt("xPos") != cx || nbt.getInt("zPos") != cz ? null : nbt;
    }

    /** Any thread (chunk storage write). */
    void capture(ServerLevel level, ChunkPos pos, CompoundTag tag) {
        Epoch e = this.epoch;
        if (e != null && VSSServerConfig.CONFIG.baselineRefreshMinutes > 0 && FULL.equals(tag.getString("Status"))) {
            this.lateWriter.execute(() -> late(e, level, pos, tag));
        }
    }

    private void late(Epoch e, ServerLevel level, ChunkPos pos, CompoundTag tag) {
        try {
            String dim = dimensionFolder(level), name = regionName(pos.x, pos.z);
            int i = (pos.x & 31) + (pos.z & 31) * 32;
            Path file = e.root.resolve(LATE).resolve(dim).resolve(name);
            Region late = region(file, level);
            if (e != this.epoch || late != null && late.stamps[i] != 0) {
                return;
            }
            Region copy = region(e.root.resolve(dim).resolve(name), level);
            if (copy != null && copy.stamps[i] != 0) {
                synchronized (copy.file) {
                    if (copy.full.get(i)) {
                        return;
                    }
                    CompoundTag held = read(copy, pos.x, pos.z);
                    if (held != null && FULL.equals(held.getString("Status"))) {
                        copy.full.set(i);
                        return;
                    }
                }
            }
            if (late == null) {
                Files.createDirectories(file.getParent());
                late = this.regions.computeIfAbsent(file, f -> open(f, level));
            }
            synchronized (late.file) {
                if (late.stamps[i] != 0) {
                    return;
                }
                try (DataOutputStream out = late.file.getChunkDataOutputStream(pos)) {
                    NbtIo.write(tag, out);
                }
                late.stamps[i] = (int) VSSConstants.epochSeconds();
            }
            this.lateCaptured.incrementAndGet();
        } catch (Exception ex) {
            VSSLogger.error("Baseline late capture failed at " + pos, ex);
        }
    }

    int lateCaptured() {
        return this.lateCaptured.get();
    }

    int lateServed() {
        return this.lateServed.get();
    }

    private Region region(Path file, ServerLevel level) {
        return Files.exists(file) ? this.regions.computeIfAbsent(file, f -> open(f, level)) : null;
    }

    private static String regionName(int cx, int cz) {
        return "r." + (cx >> 5) + "." + (cz >> 5) + ".mca";
    }

    private void miss(UUID player, int requestId, long clientTimestamp, Runnable fallback) {
        if (clientTimestamp > 0L) this.answers.add(new Answer(player, requestId, VSSConstants.RESPONSE_UP_TO_DATE));
        else fallback.run();
    }

    private Region open(Path file, ServerLevel level) {
        try {
            RegionStorageInfo info = new RegionStorageInfo("boxy_baseline", level.dimension(), "chunk");
            RegionFile region = new RegionFile(info, file, file.getParent(), false);
            int[] stamps = new int[1024];
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
                ByteBuffer buf = ByteBuffer.allocate(4096);
                if (ch.read(buf, 4096) == 4096) {
                    buf.flip();
                    buf.asIntBuffer().get(stamps);
                }
            }
            return new Region(region, stamps, new BitSet(1024));
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private void closeRegions() {
        for (Region r : this.regions.values()) {
            try {
                synchronized (r.file) {
                    r.file.close();
                }
            } catch (IOException ignored) {
            }
        }
        this.regions.clear();
    }

    void shutdown() {
        this.readers.shutdownNow();
        this.lateWriter.shutdownNow();
        closeRegions();
    }
}
