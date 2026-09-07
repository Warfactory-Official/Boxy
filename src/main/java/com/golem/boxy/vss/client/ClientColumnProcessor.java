package com.golem.boxy.vss.client;

import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.config.VSSClientConfig;
import com.golem.boxy.vss.payloads.VoxelColumnS2CPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Decode off-thread, but acquire Voxy engines and acknowledge cached terrain only on the client thread. */
class ClientColumnProcessor {
    static final int MAX_QUEUED_COLUMNS = 8000;
    private static final long MAX_QUEUED_BYTES = 64L * 1024 * 1024;
    private static final int COLUMNS_PER_TICK = 64;
    private ExecutorService executor;
    private State state;
    private long columnsDropped;
    private long lastDropWarnMs;

    // A world/session owns its queues and counters, so a late worker cannot mutate the next world's state.
    private static final class State {
        final ClientLevel level;
        final LodRequestManager manager;
        final ConcurrentLinkedQueue<VoxelColumnS2CPayload> input = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Decoded> output = new ConcurrentLinkedQueue<>();
        final AtomicInteger size = new AtomicInteger();
        long bytes; // main-thread queue accounting, includes decoded entries until ingested
        final AtomicBoolean processing = new AtomicBoolean();
        volatile boolean closed;

        State(ClientLevel level, LodRequestManager manager) {
            this.level = level;
            this.manager = manager;
        }
    }

    void offer(VoxelColumnS2CPayload payload, LodRequestManager manager) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().equals(payload.dimension())) return;
        if (state == null || state.level != level || state.manager != manager) {
            shutdown();
            state = new State(level, manager);
        }
        if (state.size.get() >= MAX_QUEUED_COLUMNS || state.bytes + payload.estimatedBytes() > MAX_QUEUED_BYTES) {
            columnsDropped++;
            manager.onRateLimited(payload.requestId());
            long now = System.currentTimeMillis();
            if (now - lastDropWarnMs > 5000) {
                lastDropWarnMs = now;
                VSSLogger.warn("Column processing queue full; " + columnsDropped + " columns dropped for retry");
            }
            return;
        }
        state.size.incrementAndGet();
        state.bytes += payload.estimatedBytes();
        state.input.add(payload);
    }

    void scheduleProcessing(boolean serverEnabled) {
        State current = state;
        if (current == null) return;
        if (!serverEnabled || !VSSClientConfig.CONFIG.receiveServerLods
                || current.level != Minecraft.getInstance().level
                || current.manager != ClientNetworking.getRequestManager()) {
            shutdown();
            return;
        }
        // Keep requests pending while Voxy starts or reloads, rather than claiming un-ingested terrain is cached.
        if (!VoxyClientBridge.isAvailable()) return;
        if (!current.input.isEmpty() && current.processing.compareAndSet(false, true)) {
            Runnable decode = () -> {
                try {
                    for (int i = 0; i < COLUMNS_PER_TICK && !current.closed; i++) {
                        VoxelColumnS2CPayload payload = current.input.poll();
                        if (payload == null) break;
                        VoxelColumnData data = null;
                        try {
                            data = decode(current.level, payload);
                        } catch (Exception e) {
                            VSSLogger.error("Invalid voxel column at " + payload.chunkX() + "," + payload.chunkZ(), e);
                        }
                        if (!current.closed) current.output.add(new Decoded(payload, data));
                    }
                } finally {
                    current.processing.set(false);
                }
            };
            if (VSSClientConfig.CONFIG.offThreadSectionProcessing) {
                if (executor == null) {
                    executor = Executors.newSingleThreadExecutor(r -> {
                        Thread thread = new Thread(r, "Boxy-VSS-ColumnProcessor");
                        thread.setDaemon(true);
                        return thread;
                    });
                }
                executor.execute(decode);
            } else {
                decode.run();
            }
        }
        for (int i = 0; i < COLUMNS_PER_TICK; i++) {
            Decoded decoded = current.output.poll();
            if (decoded == null) break;
            current.size.decrementAndGet();
            var payload = decoded.payload();
            current.bytes -= payload.estimatedBytes();
            if (!current.manager.acceptsColumn(payload)) continue;
            if (decoded.data() != null && VoxyClientBridge.ingest(current.level, payload.dimension(),
                    payload.chunkX(), payload.chunkZ(), decoded.data(),
                    current.manager.hasCachedColumn(payload.chunkX(), payload.chunkZ()))) {
                current.manager.onColumnReceived(payload.requestId(), payload.columnTimestamp());
            } else {
                current.manager.onRateLimited(payload.requestId());
            }
        }
    }

    private static VoxelColumnData decode(ClientLevel level, VoxelColumnS2CPayload payload) {
        byte[] bytes = payload.decompressedSections();
        if (bytes == null) throw new IllegalArgumentException("Invalid compressed sections");
        // An empty column is still a real update: its previously stored sections must be cleared.
        if (bytes.length == 0) return new VoxelColumnData(new VoxelColumnData.SectionData[0], payload.columnTimestamp());
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            int count = buf.readVarInt();
            if (count < 0 || count > level.getSectionsCount()) throw new IllegalArgumentException("Section count out of range");
            var sections = new VoxelColumnData.SectionData[count];
            var ys = new HashSet<Integer>();
            var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
            for (int i = 0; i < count; i++) {
                int y = buf.readByte();
                if (y < level.getMinSection() || y >= level.getMaxSection() || !ys.add(y)) {
                    throw new IllegalArgumentException("Invalid or duplicate section Y");
                }
                var section = new LevelChunkSection(biomes);
                section.read(buf);
                DataLayer block = null;
                if (buf.readBoolean()) {
                    byte[] light = new byte[2048];
                    buf.readBytes(light);
                    block = new DataLayer(light);
                }
                DataLayer sky = null;
                if (buf.readBoolean()) {
                    byte[] light = new byte[2048];
                    buf.readBytes(light);
                    sky = new DataLayer(light);
                }
                sections[i] = new VoxelColumnData.SectionData(y, section, block, sky);
            }
            if (buf.isReadable()) throw new IllegalArgumentException("Trailing column bytes");
            return new VoxelColumnData(sections, payload.columnTimestamp());
        } finally {
            buf.release();
        }
    }

    void shutdown() {
        if (state != null) {
            state.closed = true;
            state.input.clear();
            state.output.clear();
            state = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    int getQueuedCount() { return state == null ? 0 : state.size.get(); }
    long getColumnsDropped() { return columnsDropped; }
    void resetStats() { columnsDropped = 0; lastDropWarnMs = 0; }

    private record Decoded(VoxelColumnS2CPayload payload, VoxelColumnData data) {}
}
