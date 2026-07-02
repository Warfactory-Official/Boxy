package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.PositionUtil;
import com.golem.boxy.vss.common.SharedBandwidthLimiter;
import com.golem.boxy.vss.common.VSSConstants;
import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.common.processing.IncomingRequest;
import com.golem.boxy.vss.common.processing.LoadedColumnData;
import com.golem.boxy.vss.common.processing.OffThreadProcessor;
import com.golem.boxy.vss.common.processing.OffThreadProcessor.GenerationTicketRequest;
import com.golem.boxy.vss.common.processing.RateLimiterSet;
import com.golem.boxy.vss.common.processing.SendAction;
import com.golem.boxy.vss.common.processing.SendActionBatcher;
import com.golem.boxy.vss.common.processing.TickDiagnostics;
import com.golem.boxy.vss.common.processing.TickSnapshot;
import com.golem.boxy.vss.common.processing.TickSnapshot.GenerationReadyData;
import com.golem.boxy.vss.common.processing.TickSnapshot.PlayerTickData;
import com.golem.boxy.vss.common.tracking.DirtyColumnTracker;
import com.golem.boxy.vss.config.VSSServerConfig;
import com.golem.boxy.vss.net.VssChannels;
import com.golem.boxy.vss.payloads.BandwidthUpdateC2SPayload;
import com.golem.boxy.vss.payloads.BatchChunkRequestC2SPayload;
import com.golem.boxy.vss.payloads.BatchResponseS2CPayload;
import com.golem.boxy.vss.payloads.CancelRequestC2SPayload;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server-side orchestrator (port of VSS's {@code RequestProcessingService}). Runs each server tick:
 * polls finished generations, processes player lifecycle (incl. probing already-loaded chunks), posts a
 * snapshot to the off-thread processor, drains/sends responses and queued column payloads under the
 * bandwidth budget, and broadcasts dirty columns.
 */
public class RequestProcessingService {
    private static final int MAX_PROBES_PER_TICK_PER_PLAYER = 512;
    private static final int DIAG_LOG_INTERVAL_TICKS = 100;

    private final Map<UUID, PlayerRequestState> players = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final ChunkDiskReader diskReader;
    private final ChunkGenerationService generationService;
    private final SharedBandwidthLimiter bandwidthLimiter;
    private final ForgeOffThreadProcessor offThreadProcessor;
    private final DirtyColumnTracker dirtyTracker;
    private final long startTimeNanos = System.nanoTime();
    private final DirtyColumnBroadcaster dirtyBroadcaster;
    private final Map<ServerLevel, String> dimensionStringCache = new HashMap<>();
    private int diagLogCounter = 0;
    private final TickDiagnostics diag = new TickDiagnostics();
    private final Map<UUID, PlayerTickData> reusablePlayerTickData = new HashMap<>();
    private final Map<UUID, Long2ObjectMap<LoadedColumnData>> reusableLoadedChunkProbes = new HashMap<>();
    private final SendActionBatcher sendActionBatcher = new SendActionBatcher();

    public RequestProcessingService(MinecraftServer server) {
        this.server = server;
        VSSServerConfig config = VSSServerConfig.CONFIG;
        this.dirtyTracker = new DirtyColumnTracker();
        this.diskReader = new ChunkDiskReader(config.diskReaderThreads);
        this.generationService = config.enableChunkGeneration ? new ChunkGenerationService(config) : null;
        this.bandwidthLimiter = new SharedBandwidthLimiter(config.bytesPerSecondLimitGlobal);
        Path dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
        this.offThreadProcessor = new ForgeOffThreadProcessor(this.players, this.diskReader, this.generationService, dataDir, config.perDimensionTimestampCacheSizeMB);
        this.offThreadProcessor.start();
        this.dirtyBroadcaster = new DirtyColumnBroadcaster(server, this.players, this.offThreadProcessor, this.dirtyTracker);
    }

    public PlayerRequestState registerPlayer(ServerPlayer player, int capabilities) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        PlayerRequestState state = this.players.computeIfAbsent(
                player.getUUID(),
                uuid -> new PlayerRequestState(player, config.syncOnLoadRateLimitPerPlayer, config.syncOnLoadConcurrencyLimitPerPlayer,
                        config.generationRateLimitPerPlayer, config.generationConcurrencyLimitPerPlayer));
        this.diskReader.registerPlayer(player.getUUID());
        if (this.generationService != null) {
            this.generationService.registerPlayer(player.getUUID());
        }
        state.setCapabilities(capabilities);
        state.markHandshakeComplete();
        return state;
    }

    public void removePlayer(UUID uuid) {
        this.players.remove(uuid);
        this.cleanupPlayerServices(uuid);
    }

    private void cleanupPlayerServices(UUID uuid) {
        this.diskReader.removePlayerResults(uuid);
        if (this.generationService != null) {
            this.generationService.removePlayer(uuid);
        }
    }

    public void handleBatchRequest(ServerPlayer player, BatchChunkRequestC2SPayload payload) {
        PlayerRequestState state = this.players.get(player.getUUID());
        if (state != null && state.hasCompletedHandshake()) {
            int playerCx = player.getBlockX() >> 4;
            int playerCz = player.getBlockZ() >> 4;
            int maxDist = VSSServerConfig.CONFIG.lodDistanceChunks + VSSConstants.LOD_DISTANCE_BUFFER;
            for (int i = 0; i < payload.count(); i++) {
                long packedPosition = payload.packedPositions()[i];
                int cx = PositionUtil.unpackX(packedPosition);
                int cz = PositionUtil.unpackZ(packedPosition);
                if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) <= maxDist) {
                    state.addRequest(payload.requestIds()[i], packedPosition, payload.clientTimestamps()[i]);
                }
            }
        }
    }

    public void handleCancel(ServerPlayer player, CancelRequestC2SPayload payload) {
        PlayerRequestState state = this.players.get(player.getUUID());
        if (state != null && state.hasCompletedHandshake()) {
            state.addCancel(payload.requestId());
        }
    }

    public void handleBandwidthUpdate(ServerPlayer player, BandwidthUpdateC2SPayload payload) {
        PlayerRequestState state = this.players.get(player.getUUID());
        if (state != null && state.hasCompletedHandshake()) {
            state.setDesiredBandwidth(payload.desiredRate());
        }
    }

    public void tick() {
        if (!VSSServerConfig.CONFIG.enabled) {
            return;
        }
        this.diag.reset(this.offThreadProcessor.getDiagnostics());
        VSSServerConfig config = VSSServerConfig.CONFIG;
        List<GenerationReadyData> generationReady = this.tickGenerationService();
        LifecycleResult lifecycle = this.processPlayerLifecycle(config, generationReady);
        if (lifecycle.toRemove != null) {
            for (UUID uuid : lifecycle.toRemove) {
                this.removePlayer(uuid);
            }
        }
        this.postSnapshot(lifecycle, generationReady, config);
        this.drainSendActions();
        this.drainGenerationTicketRequests();
        this.flushSendQueues(lifecycle.activeCount, config);
        this.tickDirtyBroadcast(config);
        this.tickDiagnosticsLog(config);
    }

    private List<GenerationReadyData> tickGenerationService() {
        return this.generationService == null ? List.of() : this.generationService.tick();
    }

    private LifecycleResult processPlayerLifecycle(VSSServerConfig config, List<GenerationReadyData> generationReady) {
        this.reusablePlayerTickData.clear();
        this.reusableLoadedChunkProbes.clear();
        Map<UUID, PlayerTickData> playerTickData = this.reusablePlayerTickData;
        Map<UUID, Long2ObjectMap<LoadedColumnData>> loadedChunkProbes = this.reusableLoadedChunkProbes;
        Map<UUID, LongOpenHashSet> genReadyPositions = null;
        if (!generationReady.isEmpty()) {
            genReadyPositions = new HashMap<>();
            for (GenerationReadyData genData : generationReady) {
                genReadyPositions.computeIfAbsent(genData.playerUuid(), k -> new LongOpenHashSet())
                        .add(PositionUtil.packPosition(genData.columnData().cx(), genData.columnData().cz()));
            }
        }

        int activeCount = 0;
        List<UUID> toRemove = null;

        for (PlayerRequestState state : this.players.values()) {
            if (!state.hasCompletedHandshake()) {
                continue;
            }
            activeCount++;
            this.diag.updateQueuePeak(state.getSendQueueSize());
            boolean removed = false;
            boolean dimensionChanged = false;
            if (state.getPlayer().isRemoved()) {
                ServerPlayer current = this.server.getPlayerList().getPlayer(state.getPlayer().getUUID());
                if (current == null) {
                    if (toRemove == null) {
                        toRemove = new ArrayList<>();
                    }
                    toRemove.add(state.getPlayer().getUUID());
                    removed = true;
                } else {
                    state.updatePlayer(current);
                }
            }

            if (!removed) {
                if (state.checkDimensionChange()) {
                    state.onDimensionChange();
                    this.cleanupPlayerServices(state.getPlayer().getUUID());
                    this.diskReader.registerPlayer(state.getPlayer().getUUID());
                    if (this.generationService != null) {
                        this.generationService.registerPlayer(state.getPlayer().getUUID());
                    }
                    dimensionChanged = true;
                }

                ServerPlayer player = state.getPlayer();
                ServerLevel level = player.serverLevel();
                String dimension = this.dimensionStringCache.computeIfAbsent(level, l -> l.dimension().location().toString());
                this.offThreadProcessor.updateDimensionContext(dimension, level);
                playerTickData.put(player.getUUID(), new PlayerTickData(dimension, dimensionChanged));
                if (!dimensionChanged) {
                    LongOpenHashSet skipPositions = genReadyPositions != null ? genReadyPositions.get(player.getUUID()) : null;
                    Long2ObjectMap<LoadedColumnData> probes = this.probeLoadedChunks(state, level, skipPositions);
                    if (!probes.isEmpty()) {
                        loadedChunkProbes.put(player.getUUID(), probes);
                    }
                }
            }
        }

        return new LifecycleResult(playerTickData, loadedChunkProbes, activeCount, toRemove);
    }

    private void postSnapshot(LifecycleResult lifecycle, List<GenerationReadyData> generationReady, VSSServerConfig config) {
        List<UUID> removed = lifecycle.toRemove != null ? lifecycle.toRemove : List.of();
        TickSnapshot snapshot = new TickSnapshot(lifecycle.playerTickData, lifecycle.loadedChunkProbes, generationReady, removed, config.sendQueueLimitPerPlayer, false);
        this.offThreadProcessor.postSnapshot(snapshot);
    }

    private void flushSendQueues(int activeCount, VSSServerConfig config) {
        long perPlayerAllocation = this.bandwidthLimiter.getPerPlayerAllocation(activeCount);
        long perPlayerCap = Math.min(perPlayerAllocation, (long) config.bytesPerSecondLimitPerPlayer);
        for (PlayerRequestState state : this.players.values()) {
            if (state.hasCompletedHandshake()) {
                long effective = Math.min(perPlayerCap, Math.max(1L, state.getDesiredBandwidth()));
                this.flushSendQueue(state, effective);
            }
        }
    }

    private void tickDirtyBroadcast(VSSServerConfig config) {
        this.dirtyBroadcaster.tick(config);
    }

    private void tickDiagnosticsLog(VSSServerConfig config) {
        if (++this.diagLogCounter >= DIAG_LOG_INTERVAL_TICKS) {
            this.diagLogCounter = 0;
            if (VSSLogger.isDebugEnabled()) {
                long uptimeSec = this.getUptimeSeconds();
                long bwRate = uptimeSec > 0L ? this.bandwidthLimiter.getTotalBytesSent() / uptimeSec : 0L;
                VSSLogger.debug(this.diag.formatSummary(bwRate, config.bytesPerSecondLimitGlobal));
                for (PlayerRequestState state : this.players.values()) {
                    if (state.hasCompletedHandshake()) {
                        RateLimiterSet rl = state.getRateLimiters();
                        VSSLogger.debug(String.format("  %s: sq=%d, psync=%d, pgen=%d, syncCC=%d/%d, genCC=%d/%d, wq=%d",
                                state.getPlayer().getName().getString(), state.getSendQueueSize(), state.getPendingSyncCount(),
                                state.getPendingGenerationCount(), rl.syncOnLoad().getCurrentConcurrency(), rl.syncOnLoad().getMaxConcurrency(),
                                rl.generation().getCurrentConcurrency(), rl.generation().getMaxConcurrency(), state.getWaitingQueueSize()));
                    }
                }
            }
        }
    }

    private Long2ObjectMap<LoadedColumnData> probeLoadedChunks(PlayerRequestState state, ServerLevel level, LongOpenHashSet skipPositions) {
        // Lazily allocated: most requested LOD columns are distant and not loaded server-side, so the common
        // case finds nothing and returns the shared empty map — no per-player-per-tick allocation.
        Long2ObjectOpenHashMap<LoadedColumnData> probes = null;
        int probed = 0;
        for (IncomingRequest req : state.getIncomingRequests()) {
            if (probed >= MAX_PROBES_PER_TICK_PER_PLAYER) {
                break;
            }
            long packed = PositionUtil.packPosition(req.cx(), req.cz());
            if ((probes == null || !probes.containsKey(packed)) && (skipPositions == null || !skipPositions.contains(packed))) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(req.cx(), req.cz());
                if (chunk != null) {
                    if (probes == null) {
                        probes = new Long2ObjectOpenHashMap<>();
                    }
                    probes.put(packed, SectionSerializer.serializeColumn(level, chunk, req.cx(), req.cz()));
                }
                probed++;
            }
        }
        return probes == null ? Long2ObjectMaps.emptyMap() : probes;
    }

    private void drainGenerationTicketRequests() {
        if (this.generationService == null) {
            return;
        }
        GenerationTicketRequest req;
        while ((req = this.offThreadProcessor.pollGenerationTicketRequest()) != null) {
            PlayerRequestState state = this.players.get(req.playerUuid());
            if (state != null && state.hasCompletedHandshake()) {
                ServerPlayer player = state.getPlayer();
                if (!player.isRemoved()) {
                    ServerLevel level = player.serverLevel();
                    boolean accepted = this.generationService.submitGeneration(req.playerUuid(), req.requestId(), level, req.cx(), req.cz(), req.submissionOrder());
                    if (!accepted) {
                        this.generationService.addResult(req.playerUuid(), ChunkDiskReader.emptyResult(req.playerUuid(), req.requestId(), req.cx(), req.cz(), req.submissionOrder()));
                    }
                }
            }
        }
    }

    private void drainSendActions() {
        this.sendActionBatcher.clear();
        SendAction action;
        while ((action = this.offThreadProcessor.pollSendAction()) != null) {
            PlayerRequestState state = this.players.get(action.playerUuid());
            if (state != null && state.hasCompletedHandshake()) {
                this.sendActionBatcher.add(action.playerUuid(), action.responseType(), action.requestId());
            }
        }
        if (!this.sendActionBatcher.isEmpty()) {
            this.sendActionBatcher.forEach((uuid, types, ids, count) -> {
                PlayerRequestState state = this.players.get(uuid);
                if (state == null || !state.hasCompletedHandshake()) {
                    return;
                }
                try {
                    // Split into packets of <= MAX_BATCH_RESPONSES: the client's decoder rejects a larger
                    // count and disconnects. A backlog drained after the (singleplayer) server unpauses can
                    // easily exceed it — e.g. thousands of rate-limited responses for stale requests.
                    int max = VSSConstants.MAX_BATCH_RESPONSES;
                    for (int off = 0; off < count; off += max) {
                        int n = Math.min(max, count - off);
                        byte[] t = (off == 0 && n == count) ? types : Arrays.copyOfRange(types, off, off + n);
                        int[] idv = (off == 0 && n == count) ? ids : Arrays.copyOfRange(ids, off, off + n);
                        VssChannels.sendToClient(state.getPlayer(), new BatchResponseS2CPayload(t, idv, n));
                    }
                } catch (Exception e) {
                    VSSLogger.error("Failed to send batch response to " + state.getPlayer().getName().getString(), e);
                }
            });
        }
    }

    private void flushSendQueue(PlayerRequestState state, long allocationBytes) {
        state.drainReadyPayloads();
        PriorityQueue<PlayerRequestState.QueuedPayload> queue = state.getSendQueue();
        while (!queue.isEmpty()) {
            if (!state.canSend(allocationBytes)) {
                return;
            }
            PlayerRequestState.QueuedPayload queued = queue.peek();
            try {
                VssChannels.sendToClient(state.getPlayer(), queued.payload());
                queue.poll();
                state.recordSend(queued.estimatedBytes());
                this.bandwidthLimiter.recordSend(queued.estimatedBytes());
                this.diag.recordSectionSent(queued.estimatedBytes());
            } catch (Exception e) {
                VSSLogger.error("Failed to send queued payload to " + state.getPlayer().getName().getString()
                        + ", dropping remaining queue (" + queue.size() + " entries)", e);
                queue.clear();
                return;
            }
        }
    }

    public Map<UUID, PlayerRequestState> getPlayers() {
        return Collections.unmodifiableMap(this.players);
    }

    public ChunkDiskReader getDiskReader() {
        return this.diskReader;
    }

    public ChunkGenerationService getGenerationService() {
        return this.generationService;
    }

    public SharedBandwidthLimiter getBandwidthLimiter() {
        return this.bandwidthLimiter;
    }

    public long getUptimeSeconds() {
        return (System.nanoTime() - this.startTimeNanos) / 1_000_000_000L;
    }

    public OffThreadProcessor<?, ?> getOffThreadProcessor() {
        return this.offThreadProcessor;
    }

    public DirtyColumnTracker getDirtyTracker() {
        return this.dirtyTracker;
    }

    public String getTickDiagnostics() {
        return this.diag.format(VSSServerConfig.CONFIG.sendQueueLimitPerPlayer);
    }

    public long getWindowBandwidthRate() {
        return this.diag.getWindowBytesPerSecond();
    }

    public void shutdown() {
        try {
            this.offThreadProcessor.shutdown();
        } catch (Exception e) {
            VSSLogger.error("Error shutting down off-thread processor", e);
        }
        this.players.clear();
        try {
            this.diskReader.shutdown();
        } catch (Exception e) {
            VSSLogger.error("Error shutting down disk reader", e);
        }
        try {
            if (this.generationService != null) {
                this.generationService.shutdown();
            }
        } catch (Exception e) {
            VSSLogger.error("Error shutting down generation service", e);
        }
    }

    private record LifecycleResult(
            Map<UUID, PlayerTickData> playerTickData,
            Map<UUID, Long2ObjectMap<LoadedColumnData>> loadedChunkProbes,
            int activeCount,
            List<UUID> toRemove) {
    }
}
