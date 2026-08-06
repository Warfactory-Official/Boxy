package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.PositionUtil;
import com.golem.boxy.vss.common.SharedBandwidthLimiter;
import com.golem.boxy.vss.common.VSSConstants;
import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.common.processing.IncomingRequest;
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
import com.golem.boxy.vss.common.voxel.SerializedColumnCache;
import com.golem.boxy.vss.config.VSSServerConfig;
import com.golem.boxy.vss.net.VssChannels;
import com.golem.boxy.vss.payloads.BandwidthUpdateC2SPayload;
import com.golem.boxy.vss.payloads.BatchChunkRequestC2SPayload;
import com.golem.boxy.vss.payloads.BatchResponseS2CPayload;
import com.golem.boxy.vss.payloads.CancelRequestC2SPayload;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
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
    /**
     * Consult System.nanoTime() once every 64 iterations rather than every one. Now that the probe body is
     * just a getChunkNow, a ~20-25ns clock read per iteration would be a measurable share of the loop.
     */
    private static final int NANOTIME_CHECK_MASK = 63;

    private final Map<UUID, PlayerRequestState> players = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final ChunkDiskReader diskReader;
    private final LiveColumnSerializer liveColumnSerializer;
    private final ChunkGenerationService generationService;
    private final SharedBandwidthLimiter bandwidthLimiter;
    private final ForgeOffThreadProcessor offThreadProcessor;
    private final DirtyColumnTracker dirtyTracker;
    private final SerializedColumnCache bytesCache;
    private final long startTimeNanos = System.nanoTime();
    private final DirtyColumnBroadcaster dirtyBroadcaster;
    private final Map<ServerLevel, String> dimensionStringCache = new HashMap<>();
    private int diagLogCounter = 0;
    private final TickDiagnostics diag = new TickDiagnostics();
    private final SendActionBatcher sendActionBatcher = new SendActionBatcher();
    /**
     * Round-robin start offset for the per-tick player walk. The probe budget is global, so a stable
     * iteration order would let the first player consume it every tick and starve the last one indefinitely.
     * Main-thread only, and never handed to the processing thread, so reusing the scratch list is safe here.
     */
    private final ArrayList<PlayerRequestState> lifecycleScratch = new ArrayList<>();
    private int lifecycleRotation;

    public RequestProcessingService(MinecraftServer server) {
        this.server = server;
        VSSServerConfig config = VSSServerConfig.CONFIG;
        this.dirtyTracker = new DirtyColumnTracker();
        this.bytesCache = new SerializedColumnCache(SerializedColumnCache.mbToBytes(config.serializedColumnCacheSizeMB));
        // Precise invalidation: fires once per column per drain window, straight off sendBlockUpdated and
        // ChunkMap.save. The broadcaster's periodic sweep backs it up (see tickDirtyBroadcast).
        this.dirtyTracker.setInvalidationSink(this.bytesCache::invalidate);
        this.diskReader = new ChunkDiskReader(config.diskReaderThreads, this.bytesCache);
        this.liveColumnSerializer = new LiveColumnSerializer(this.diskReader, this.bytesCache);
        this.generationService = config.enableChunkGeneration ? new ChunkGenerationService(config) : null;
        this.bandwidthLimiter = new SharedBandwidthLimiter(config.bytesPerSecondLimitGlobal);
        Path dataDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
        this.offThreadProcessor = new ForgeOffThreadProcessor(this.players, this.diskReader, this.generationService, dataDir, config.perDimensionTimestampCacheSizeMB, this.bytesCache);
        this.offThreadProcessor.start();
        this.dirtyBroadcaster = new DirtyColumnBroadcaster(server, this.players, this.offThreadProcessor, this.dirtyTracker, this.bytesCache);
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
        // One deadline shared by both live-chunk phases, so the cost of this mod's main-thread work is
        // bounded in total rather than per-phase or per-player.
        long deadlineNanos = config.probeBudgetMicros <= 0
                ? Long.MAX_VALUE
                : System.nanoTime() + config.probeBudgetMicros * 1000L;
        // Serialize what last cycle's router actually asked for, before probing for new work. Ordering
        // matters under a tight budget: these requests already hold permits, so finishing them keeps the
        // pipeline draining, whereas new probes only create more work.
        this.drainLiveSerializeRequests(deadlineNanos);
        List<GenerationReadyData> generationReady = this.tickGenerationService();
        LifecycleResult lifecycle = this.processPlayerLifecycle(config, generationReady, deadlineNanos);
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

    private LifecycleResult processPlayerLifecycle(VSSServerConfig config, List<GenerationReadyData> generationReady, long deadlineNanos) {
        // Allocated fresh per tick, never reused. These maps go straight into the TickSnapshot handed to the
        // processing thread, which may still be iterating last tick's snapshot when the next tick begins —
        // clearing and refilling shared instances raced that read (CME, a skipped player, or an infinite loop
        // inside fastutil). Two small HashMaps per tick is the correct price for that.
        Map<UUID, PlayerTickData> playerTickData = new HashMap<>();
        Map<UUID, LongSet> loadedPositions = new HashMap<>();
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

        this.lifecycleScratch.clear();
        this.lifecycleScratch.addAll(this.players.values());
        int playerCount = this.lifecycleScratch.size();
        int rotation = playerCount == 0 ? 0 : Math.floorMod(this.lifecycleRotation++, playerCount);

        for (int i = 0; i < playerCount; i++) {
            PlayerRequestState state = this.lifecycleScratch.get((rotation + i) % playerCount);
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
                int probedCount = 0;
                if (!dimensionChanged) {
                    LongOpenHashSet skipPositions = genReadyPositions != null ? genReadyPositions.get(player.getUUID()) : null;
                    LongOpenHashSet loaded = new LongOpenHashSet();
                    probedCount = this.probeLoadedChunks(state, level, loaded, skipPositions, deadlineNanos);
                    if (!loaded.isEmpty()) {
                        loadedPositions.put(player.getUUID(), loaded);
                    }
                }

                playerTickData.put(player.getUUID(), new PlayerTickData(dimension, dimensionChanged, probedCount));
            }
        }

        return new LifecycleResult(playerTickData, loadedPositions, activeCount, toRemove);
    }

    private void postSnapshot(LifecycleResult lifecycle, List<GenerationReadyData> generationReady, VSSServerConfig config) {
        List<UUID> removed = lifecycle.toRemove != null ? lifecycle.toRemove : List.of();
        TickSnapshot snapshot = new TickSnapshot(lifecycle.playerTickData, lifecycle.loadedPositions, generationReady, removed, config.sendQueueLimitPerPlayer, false);
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

    /**
     * Records which of this player's pending requests name a chunk that is loaded right now, and nothing more.
     *
     * <p>This used to serialize each loaded column inline, which was ~92% of this mod's entire server-thread
     * cost — and most of it was discarded, because the router only decides afterwards whether a column is
     * wanted, and rejects the majority as duplicates, already-pending, or already-current on the client.
     * Serialization now happens in {@link #drainLiveSerializeRequests} for the survivors only.
     *
     * <p>Walks the incoming queue non-destructively; the processing thread is the only consumer.
     *
     * @return how many queue entries were walked — the prefix the router may poll this cycle. Entries beyond
     *         it have no loaded/not-loaded information yet and must wait for a later tick rather than be
     *         routed to disk, which would serve autosave-stale bytes for a loaded, recently-edited chunk.
     */
    private int probeLoadedChunks(PlayerRequestState state, ServerLevel level, LongOpenHashSet outLoaded,
                                  LongOpenHashSet skipPositions, long deadlineNanos) {
        int visited = 0;
        for (IncomingRequest req : state.getIncomingRequests()) {
            if (visited >= MAX_PROBES_PER_TICK_PER_PLAYER) {
                break;
            }
            // Checked at visited == 0 too, so a player reached after the budget is already spent probes
            // nothing rather than getting a free batch. That is what makes the budget global.
            if ((visited & NANOTIME_CHECK_MASK) == 0 && System.nanoTime() >= deadlineNanos) {
                break;
            }
            visited++;
            long packed = PositionUtil.packPosition(req.cx(), req.cz());
            // skipPositions: generated this tick, so processGenerationReady already marked them done and the
            // router will resolve them as duplicates before ever consulting the loaded set.
            if ((skipPositions == null || !skipPositions.contains(packed))
                    && !outLoaded.contains(packed)
                    && level.getChunkSource().getChunkNow(req.cx(), req.cz()) != null) {
                outLoaded.add(packed);
            }
        }
        return visited;
    }

    /**
     * Serializes the columns last cycle's router asked for and publishes them into the players' disk-result
     * queues, where they share the disk path's permit release, dedup dispatch and payload build.
     *
     * <p>Every queued request already owns a syncOnLoad permit and a pendingByPosition slot, both of which are
     * only released when a result arrives. So every path out of here must produce exactly one result, or fall
     * back to a disk read that will. Dropping one silently leaks a permit and eventually starves the player.
     */
    private void drainLiveSerializeRequests(long deadlineNanos) {
        // Checked every iteration, unlike the probe loop: a column costs ~100-300us to serialize, so the
        // probe's every-64 sampling would overshoot a 2ms budget by an order of magnitude. Always serializes
        // at least one so the pipeline cannot stall outright under a very small budget.
        boolean first = true;
        while (first || System.nanoTime() < deadlineNanos) {
            OffThreadProcessor.LiveSerializeRequest req = this.offThreadProcessor.pollLiveSerializeRequest();
            if (req == null) {
                return;
            }

            this.serializeLiveColumn(req);
            first = false;
        }

        // Fell out on the deadline: the remainder waits for the next tick, permits and pending entries
        // intact — nothing is lost, the pipeline just runs a tick slower.
    }

    private void serializeLiveColumn(OffThreadProcessor.LiveSerializeRequest req) {
        PlayerRequestState state = this.players.get(req.playerUuid());
        if (state == null) {
            // Player gone. removePlayer/preparePlayers tears down the dedup groups and pending state; there is
            // no result queue left to publish into.
            return;
        }

        ServerPlayer player = state.getPlayer();
        ServerLevel level = player.serverLevel();
        try {
            // A dimension change between the probe and now would make getChunkNow read the *new* dimension for
            // a request that named the old one. Drop instead: the change already triggers clearProcessingState
            // and dedup teardown on the processing thread.
            String currentDimension = this.dimensionStringCache.computeIfAbsent(level, l -> l.dimension().location().toString());
            if (player.isRemoved() || !currentDimension.equals(req.dimension())) {
                return;
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(req.cx(), req.cz());
            if (chunk == null) {
                // Unloaded since the probe. Not "not generated" — it exists on disk, so read it rather than
                // telling the client the column doesn't exist. The disk path releases the permit we hold.
                this.diskReader.submitReadDirect(req.playerUuid(), req.requestId(), level, req.cx(), req.cz(), req.submissionOrder());
                return;
            }

            // The server thread only copies (container clones + light memcpy); the buffer writes — the
            // expensive part — happen on the serializer pool, which publishes the result itself.
            ColumnSnapshot snapshot = ColumnSnapshotter.snapshot(this.server, level, chunk, req.cx(), req.cz());
            this.liveColumnSerializer.submit(req, snapshot);
        } catch (Throwable t) {
            VSSLogger.error("Failed to serialize live column [" + req.cx() + ", " + req.cz() + "] in " + req.dimension(), t);
            this.diskReader.publishResult(req.playerUuid(),
                    ChunkDiskReader.emptyResult(req.playerUuid(), req.requestId(), req.cx(), req.cz(), req.submissionOrder()));
        }
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
            // Before the disk reader: these workers publish into its result queues.
            this.liveColumnSerializer.shutdown();
        } catch (Exception e) {
            VSSLogger.error("Error shutting down column serializer", e);
        }
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
            Map<UUID, LongSet> loadedPositions,
            int activeCount,
            List<UUID> toRemove) {
    }
}
