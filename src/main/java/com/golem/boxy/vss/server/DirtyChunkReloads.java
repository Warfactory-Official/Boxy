package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.processing.OffThreadProcessor.LiveSerializeRequest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Bounded live reload of existing dirty terrain after an editor releases its own chunk tickets. */
final class DirtyChunkReloads {
    private static final TicketType<Long> TICKET = TicketType.create("boxy_dirty_refresh", Comparator.<Long>naturalOrder());
    private record Pending(ServerLevel level, int started) {}
    private final LinkedHashMap<LiveSerializeRequest, Pending> pending = new LinkedHashMap<>();
    private final MinecraftServer server;
    private final ChunkDiskReader results;
    private final LiveColumnSerializer serializer;
    private boolean closed;
    private volatile long completed;

    DirtyChunkReloads(MinecraftServer server, ChunkDiskReader results, LiveColumnSerializer serializer) {
        this.server = server;
        this.results = results;
        this.serializer = serializer;
    }

    // Called on the server thread only, after the disk reader confirms an existing FULL column.
    void submit(ServerLevel level, LiveSerializeRequest request) {
        var player = server.getPlayerList().getPlayer(request.playerUuid());
        if (closed || player == null || player.serverLevel() != level) return;
        if (!com.golem.boxy.vss.config.VSSServerConfig.CONFIG.enabled) {
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
        boolean copied = false;
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var request = entry.getKey();
            var state = entry.getValue();
            var player = server.getPlayerList().getPlayer(request.playerUuid());
            boolean invalid = player == null || player.serverLevel() != state.level();
            boolean timedOut = server.getTickCount() - state.started() >= 160;
            var chunk = state.level().getChunkSource().getChunkNow(request.cx(), request.cz());
            if (!invalid && !timedOut && chunk == null) continue;
            if (!invalid && !timedOut && copied && System.nanoTime() >= deadline) continue;
            try {
                if (invalid) {
                    // Session/dimension cleanup owns its request accounting.
                } else if (timedOut) {
                    retry(request);
                } else {
                    serializer.submit(request, ColumnSnapshotter.snapshot(server, state.level(), chunk, request.cx(), request.cz()));
                    completed++;
                    copied = true;
                }
            } catch (Throwable error) {
                com.golem.boxy.vss.common.VSSLogger.error("Dirty chunk reload failed", error);
                retry(request);
            } finally {
                release(request, state);
                iterator.remove();
            }
        }
    }

    void cancel(UUID player, int requestId) {
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().playerUuid().equals(player) && (requestId == -1 || entry.getKey().requestId() == requestId)) {
                if (requestId != -1) retry(entry.getKey());
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
        if (!closed) pending.keySet().forEach(this::retry);
        pending.forEach(DirtyChunkReloads::release);
        pending.clear();
    }

    long completed() { return completed; }

    private void retry(LiveSerializeRequest request) {
        results.publishResult(request.playerUuid(), ChunkDiskReader.saturatedResult(request.playerUuid(),
                request.requestId(), request.cx(), request.cz(), request.submissionOrder()));
    }

    private static void release(LiveSerializeRequest request, Pending state) {
        state.level().getChunkSource().removeRegionTicket(TICKET, new ChunkPos(request.cx(), request.cz()), 0, request.submissionOrder());
    }
}
