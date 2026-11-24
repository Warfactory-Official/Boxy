package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.common.processing.OffThreadProcessor.LiveSerializeRequest;
import com.golem.boxy.vss.config.VSSServerConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded live reload of existing dirty terrain after an editor releases its own chunk tickets. */
final class DirtyChunkReloads {
    private static final TicketType<Long> TICKET = TicketType.create("boxy_dirty_refresh", Comparator.<Long>naturalOrder());
    private static final class Pending {
        final ServerLevel level;
        final int started;
        LiveSnapshots.Handle handle;

        Pending(ServerLevel level, int started) {
            this.level = level;
            this.started = started;
        }
    }
    private final LinkedHashMap<LiveSerializeRequest, Pending> pending = new LinkedHashMap<>();
    private final MinecraftServer server;
    private final ChunkDiskReader results;
    private final LiveColumnSerializer serializer;
    private final LiveSnapshots snapshots;
    private final ConcurrentLinkedQueue<LiveSerializeRequest> deferredDone = new ConcurrentLinkedQueue<>();
    private boolean closed;
    private final AtomicLong completed = new AtomicLong();

    DirtyChunkReloads(MinecraftServer server, ChunkDiskReader results, LiveColumnSerializer serializer, LiveSnapshots snapshots) {
        this.server = server;
        this.results = results;
        this.serializer = serializer;
        this.snapshots = snapshots;
    }

    // Called on the server thread only, after the disk reader confirms an existing FULL column.
    void submit(ServerLevel level, LiveSerializeRequest request) {
        var player = server.getPlayerList().getPlayer(request.playerUuid());
        if (closed || player == null || player.serverLevel() != level) return;
        if (!VSSServerConfig.CONFIG.enabled) {
            retry(request);
            return;
        }
        if (pending.containsKey(request)) return;
        long playerCount = pending.keySet().stream().filter(req -> req.playerUuid().equals(request.playerUuid())).count();
        if (pending.size() >= 32 || playerCount >= 8) {
            retry(request);
            return;
        }
        level.getChunkSource().addRegionTicket(TICKET, new ChunkPos(request.cx(), request.cz()), 0, request.submissionOrder());
        pending.put(request, new Pending(level, server.getTickCount()));
    }

    void tick(long deadline) {
        LiveSerializeRequest finished;
        while ((finished = deferredDone.poll()) != null) {
            var state = pending.get(finished);
            if (state != null && state.handle != null) {
                pending.remove(finished);
                release(finished, state);
            }
        }
        boolean copied = false;
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var request = entry.getKey();
            var state = entry.getValue();
            var player = server.getPlayerList().getPlayer(request.playerUuid());
            boolean invalid = player == null || player.serverLevel() != state.level;
            boolean timedOut = server.getTickCount() - state.started >= 160;
            if (state.handle != null && !invalid && !timedOut) continue;
            var chunk = state.level.getChunkSource().getChunkNow(request.cx(), request.cz());
            if (!invalid && !timedOut && chunk == null) continue;
            if (!invalid && !timedOut && copied && System.nanoTime() >= deadline) continue;
            try {
                if (state.handle != null && !state.handle.cancel()) {
                    // capture already ran on the owner; its sink published the result
                } else if (invalid) {
                    // Session/dimension cleanup owns its request accounting.
                } else if (timedOut) {
                    retry(request);
                } else {
                    var handle = snapshots.capture(server.getTickCount(), state.level, request.cx(), request.cz(),
                            () -> ColumnSnapshotter.snapshot(state.level, chunk, request.cx(), request.cz()), sink(request));
                    if (handle != null) {
                        state.handle = handle;
                        continue;
                    }
                    copied = !snapshots.deferred();
                }
            } catch (Throwable error) {
                VSSLogger.error("Dirty chunk reload failed", error);
                retry(request);
            }
            release(request, state);
            iterator.remove();
        }
    }

    private LiveSnapshots.Sink<ColumnSnapshot> sink(LiveSerializeRequest request) {
        return new LiveSnapshots.Sink<>() {
            @Override
            public void accept(ColumnSnapshot snapshot) {
                serializer.submit(request, snapshot);
                completed.incrementAndGet();
            }

            @Override
            public void fail(Throwable cause) {
                VSSLogger.error("Dirty chunk reload failed", cause);
                retry(request);
            }

            @Override
            public void deferredDone() {
                deferredDone.add(request);
            }
        };
    }

    void cancel(UUID player, int requestId) {
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().playerUuid().equals(player) && (requestId == -1 || entry.getKey().requestId() == requestId)) {
                var handle = entry.getValue().handle;
                boolean owned = handle == null || handle.cancel();
                if (requestId != -1 && owned) retry(entry.getKey());
                release(entry.getKey(), entry.getValue());
                iterator.remove();
            }
        }
    }

    void close() {
        closed = true;
        clear();
    }

    void clear() {
        if (!closed) pending.forEach((request, state) -> {
            if (state.handle == null || state.handle.cancel()) retry(request);
        });
        pending.forEach(DirtyChunkReloads::release);
        pending.clear();
    }

    long completed() { return completed.get(); }

    private void retry(LiveSerializeRequest request) {
        results.publishResult(request.playerUuid(), ChunkDiskReader.saturatedResult(request.playerUuid(),
                request.requestId(), request.cx(), request.cz(), request.submissionOrder()));
    }

    private static void release(LiveSerializeRequest request, Pending state) {
        state.level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(request.cx(), request.cz()), 0, request.submissionOrder());
    }
}
