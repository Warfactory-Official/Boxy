package com.golem.boxy.vss.client;

import com.golem.boxy.vss.common.PositionUtil;
import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.config.VSSClientConfig;
import com.golem.boxy.vss.net.VssChannels;
import com.golem.boxy.vss.payloads.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Main-thread world/packet boundary. All scanning and cache collections belong to the planner worker. */
public final class LodRequestManager {
    private static final int MAX_PENDING_EVENTS = 65536;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Boxy-LOD-Planner");
        thread.setDaemon(true);
        return thread;
    });
    private final LodRequestPlanner planner = new LodRequestPlanner();
    private ArrayList<Consumer<LodRequestPlanner>> events = new ArrayList<>();
    private int eventWeight;
    private boolean recover;
    private boolean closed;
    private boolean forceClearMissing;
    private long epoch;
    private long worldVersion;
    private ClientLevel level;
    private int probeTicks;
    private int missingVanilla;
    private int probeX;
    private int probeZ;
    private int probeRadius = -1;
    private final Map<Integer, Pending> pending = new HashMap<>();
    private final Map<Long, Integer> byPosition = new HashMap<>();
    private CompletableFuture<Completion> work;
    private LodRequestPlanner.Diagnostics diagnostics = LodRequestPlanner.Diagnostics.empty();

    private record Pending(long position, boolean cached) {}
    private record Completion(long epoch, List<LodRequestPlanner.Outbound> outbound,
                              LodRequestPlanner.Diagnostics diagnostics) {}

    public void onSessionConfig(SessionConfigS2CPayload config, String address) {
        enqueue(p -> p.onSessionConfig(config, address), 1);
    }

    private void enqueue(Consumer<LodRequestPlanner> event, int weight) {
        if (closed) return;
        if (eventWeight + weight > MAX_PENDING_EVENTS) {
            // Network input cannot create an unbounded executor backlog. Re-open all cached terrain
            // after overload, including dirty refreshes whose notifications were coalesced away.
            events.clear();
            eventWeight = 0;
            recover = true;
            forceClearMissing = true;
            epoch++;
            pending.clear();
            byPosition.clear();
        }
        events.add(event);
        eventWeight += weight;
    }

    public void tick() {
        if (closed) return;
        Minecraft mc = Minecraft.getInstance();
        if (level != mc.level) {
            level = mc.level;
            epoch++;
            worldVersion++;
            pending.clear();
            byPosition.clear();
            probeRadius = -1;
        }
        if (work != null) {
            if (!work.isDone()) return; // At most one submitted tick; never wait on the game thread.
            try {
                Completion completed = work.join();
                if (completed.epoch() == epoch && level != null) {
                    diagnostics = completed.diagnostics();
                    dispatch(completed.outbound());
                }
            } catch (Exception e) {
                VSSLogger.error("LOD planning failed; retrying outstanding terrain", e);
                recover = true;
            }
            work = null;
        }
        if (level == null || mc.player == null || mc.player.isDeadOrDying()) return;
        int cx = mc.player.getBlockX() >> 4;
        int cz = mc.player.getBlockZ() >> 4;
        int radius = mc.options.getEffectiveRenderDistance();
        if (++probeTicks >= 20 || cx != probeX || cz != probeZ || radius != probeRadius) {
            probeTicks = 0;
            probeX = cx;
            probeZ = cz;
            probeRadius = radius;
            missingVanilla = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (!level.getChunkSource().hasChunk(cx + dx, cz + dz)) missingVanilla++;
                }
            }
        }
        var input = new LodRequestPlanner.TickInput(level.dimension(), cx, cz, radius, missingVanilla,
                ClientNetworking.getQueuedColumnCount(), VSSClientConfig.CONFIG.lodDistanceChunks,
                VoxyClientBridge.getViewDistanceChunks().orElse(-1),
                VSSClientConfig.CONFIG.incrementalSpiralRescan, worldVersion);
        var batch = events;
        events = new ArrayList<>();
        eventWeight = 0;
        boolean retry = recover;
        recover = false;
        long submittedEpoch = epoch;
        work = CompletableFuture.supplyAsync(() -> {
            for (var event : batch) event.accept(planner);
            if (retry) planner.retryAll();
            planner.tick(input);
            return new Completion(submittedEpoch, planner.takeOutbound(), planner.diagnostics());
        }, executor);
    }

    private void dispatch(List<LodRequestPlanner.Outbound> actions) {
        for (var action : actions) {
            if (action.payload() instanceof BatchChunkRequestC2SPayload request) {
                for (int i = 0; i < request.count(); i++) {
                    int id = request.requestIds()[i];
                    long position = request.packedPositions()[i];
                    pending.put(id, new Pending(position, action.cached()[i]));
                    byPosition.put(position, id);
                }
                try {
                    VssChannels.sendToServer(request);
                } catch (Exception e) {
                    for (int id : request.requestIds()) onRateLimited(id);
                    VSSLogger.error("Failed to send LOD requests", e);
                }
            } else if (action.payload() instanceof CancelRequestC2SPayload cancel) {
                remove(cancel.requestId());
                try { VssChannels.sendToServer(cancel); } catch (Exception ignored) {}
            }
        }
        // Recovery marks the next planned batch as a full cached refresh; subsequent batches use
        // their per-request cache bit and avoid paying the missing-section clear cost unnecessarily.
        if (!actions.isEmpty()) forceClearMissing = false;
    }

    private Pending remove(int id) {
        Pending value = pending.remove(id);
        if (value != null) byPosition.remove(value.position(), id);
        return value;
    }

    boolean acceptsColumn(VoxelColumnS2CPayload payload) {
        Pending request = pending.get(payload.requestId());
        return !closed && level != null && Minecraft.getInstance().level == level
                && level.dimension().equals(payload.dimension()) && request != null
                && request.position() == PositionUtil.packPosition(payload.chunkX(), payload.chunkZ());
    }

    boolean hasCachedColumn(int x, int z) {
        Integer id = byPosition.get(PositionUtil.packPosition(x, z));
        return forceClearMissing || (id != null && pending.get(id).cached());
    }

    public boolean onColumnReceived(int id, long timestamp) {
        Pending request = remove(id);
        if (request == null) return false;
        enqueue(p -> p.onColumnReceived(id, timestamp), 1);
        return forceClearMissing || request.cached();
    }

    public void onColumnNotGenerated(int id) {
        if (remove(id) != null) enqueue(p -> p.onColumnNotGenerated(id), 1);
    }

    public void onColumnUpToDate(int id) {
        if (remove(id) != null) enqueue(p -> p.onColumnUpToDate(id), 1);
    }

    public void onRateLimited(int id) {
        if (remove(id) != null) enqueue(p -> p.onRateLimited(id), 1);
    }

    public void onDirtyColumns(long[] positions) {
        long[] copy = positions.clone();
        for (long position : copy) {
            Integer id = byPosition.get(position);
            if (id != null) pending.put(id, new Pending(position, true));
        }
        enqueue(p -> p.onDirtyColumns(copy), Math.max(1, copy.length));
    }

    public void saveCache() { enqueue(LodRequestPlanner::saveCache, 1); }

    public void flushCache() {
        epoch++;
        pending.clear();
        byPosition.clear();
        enqueue(LodRequestPlanner::flushCache, 1);
    }

    public void disconnect() {
        if (closed) return;
        closed = true;
        pending.clear();
        byPosition.clear();
        var batch = events;
        events = new ArrayList<>();
        // Runs after the current tick, never waits for disk I/O or a scan during logout.
        executor.execute(() -> {
            for (var event : batch) event.accept(planner);
            planner.disconnect();
            planner.saveCache();
        });
        executor.shutdown();
    }
    public int getReceivedColumnCount() { return diagnostics.getReceivedColumnCount(); }
    public int getEmptyColumnCount() { return diagnostics.getEmptyColumnCount(); }
    public int getEffectiveLodDistanceChunks() { return diagnostics.getEffectiveLodDistanceChunks(); }
    public long getTotalSendCycles() { return diagnostics.getTotalSendCycles(); }
    public long getTotalPositionsRequested() { return diagnostics.getTotalPositionsRequested(); }
    public int getDirtyColumnCount() { return diagnostics.getDirtyColumnCount(); }
    public int getConfirmedRing() { return diagnostics.getConfirmedRing(); }
    public int getScanRing() { return diagnostics.getScanRing(); }
    public int getMissingVanillaChunks() { return diagnostics.getMissingVanillaChunks(); }
    public long getTotalColumnsReceived() { return diagnostics.getTotalColumnsReceived(); }
    public long getTotalUpToDate() { return diagnostics.getTotalUpToDate(); }
    public long getTotalNotGenerated() { return diagnostics.getTotalNotGenerated(); }
    public long getTotalRateLimited() { return diagnostics.getTotalRateLimited(); }
    public double getReceiveRate() { return diagnostics.getReceiveRate(); }
    public double getRequestRate() { return diagnostics.getRequestRate(); }
    public int getPendingCount() { return diagnostics.getPendingCount(); }
    public int getQueueRemaining() { return diagnostics.getQueueRemaining(); }
    public int getLastBudget() { return diagnostics.getLastBudget(); }
    public int getLastSyncQueued() { return diagnostics.getLastSyncQueued(); }
    public int getLastGenQueued() { return diagnostics.getLastGenQueued(); }
}
