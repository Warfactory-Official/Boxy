package com.golem.boxy.vss.client;

import com.golem.boxy.vss.common.PositionUtil;
import com.golem.boxy.vss.common.VSSConstants;
import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.payloads.BatchChunkRequestC2SPayload;
import com.golem.boxy.vss.payloads.CancelRequestC2SPayload;
import com.golem.boxy.vss.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;

final class LodRequestPlanner {
   private static final int BACKPRESSURE_NUMERATOR = 3;
   private static final int BACKPRESSURE_DENOMINATOR = 4;
   private static final int MIN_SEND_PER_TICK = 16;
   private static final long TIMEOUT_NANOS = 10000000000L;
   /** Prune only after moving this far from the last prune center. The prune distance carries a 32-chunk
    *  margin over the effective LOD distance (SpiralScanner.getPruneDistance), so deferring by up to 8
    *  chunks never drops an in-range entry — it just stops re-walking every collection (the timestamp map
    *  alone can hold hundreds of thousands of entries) on every single chunk-boundary cross. */
   private static final int PRUNE_HYSTERESIS_CHUNKS = 8;
   private static final int LIVE_MARGIN_CHUNKS = 2;
   /** Scan centered where the player is this far ahead (ETA order); 300 km/h => 41 chunks. */
   private static final double LEAD_SECONDS = 8.0;
   /** Per-tick displacement above this = teleport, not motion. */
   private static final double TELEPORT_BLOCKS = 64.0;
   private double lastX;
   private double lastZ;
   private long lastGameTime = Long.MIN_VALUE;
   /** Blocks/tick, EMA. */
   private double velX;
   private double velZ;
   private int lastLeadDx;
   private int lastLeadDz;
   private SessionConfigS2CPayload sessionConfig;
   private String serverAddress;
   private int lastChunkX;
   private int lastChunkZ;
   private int lastPruneChunkX;
   private int lastPruneChunkZ;
   private ResourceKey<Level> lastDimension;
   private final Long2LongOpenHashMap columnTimestamps = new Long2LongOpenHashMap();
   private final InFlightTracker tracker;
   private final RequestQueue queue;
   private boolean cacheLoaded;
   private volatile CompletableFuture<Long2LongOpenHashMap> pendingCacheLoad;
   private final LongOpenHashSet dirtyColumns;
   private final LongOpenHashSet refreshColumns = new LongOpenHashSet();
   private final LongOpenHashSet rateLimitRetryPositions;
   private final LongOpenHashSet validatedThisSession;
   /** Validated beyond the server's live distance (later changes fogged) => revalidate on entry. */
   private final LongOpenHashSet farValidated = new LongOpenHashSet();
   private final RequestMetrics metrics;
   private int maxGenConcurrency;
   private int maxSyncConcurrency;
   private boolean skipNextScan;
   private int maxSendPerTick;
   private int[] sendRequestIdBuffer;
   private long[] sendPositionBuffer;
   private long[] sendTimestampBuffer;
   private final SpiralScanner scanner;

   public LodRequestPlanner() {
      this.columnTimestamps.defaultReturnValue(-1L);
      this.tracker = new InFlightTracker();
      this.queue = new RequestQueue();
      this.cacheLoaded = false;
      this.pendingCacheLoad = null;
      this.dirtyColumns = new LongOpenHashSet();
      this.rateLimitRetryPositions = new LongOpenHashSet();
      this.validatedThisSession = new LongOpenHashSet();
      this.metrics = new RequestMetrics();
      this.maxSendPerTick = 16;
      this.sendRequestIdBuffer = new int[16];
      this.sendPositionBuffer = new long[16];
      this.sendTimestampBuffer = new long[16];
      this.scanner = new SpiralScanner();
   }

   private void putTimestamp(long packed, long timestamp) {
      long old = this.columnTimestamps.put(packed, timestamp);
      this.metrics.adjustCounters(old, timestamp);
   }

   private void clearTimestamps() {
      this.columnTimestamps.clear();
      this.metrics.reset();
   }

   private void loadTimestamps(Long2LongOpenHashMap loaded) {
      this.columnTimestamps.putAll(loaded);
      this.metrics.bulkRecount(this.columnTimestamps);
   }

   public void onSessionConfig(SessionConfigS2CPayload config, String serverAddress) {
      this.sessionConfig = config;
      this.serverAddress = serverAddress;
      this.resetRequestState();
      this.lastDimension = null;
      this.cacheLoaded = false;
      this.scanner.reset();
      this.maxGenConcurrency = config.generationConcurrencyLimitPerPlayer();
      this.maxSyncConcurrency = config.syncOnLoadConcurrencyLimitPerPlayer();
      this.skipNextScan = false;
   }

   record TickInput(ResourceKey<Level> dimension, int playerCx, int playerCz, double playerX, double playerZ, long gameTime,
                    int viewDistance,
                    int missingVanilla, int queuedColumns, int clientDistance, int voxyDistance,
                    boolean incremental, long worldVersion) {}
   record Outbound(com.golem.boxy.vss.payloads.VssPayload payload, boolean[] cached) {}
   private final java.util.ArrayList<Outbound> outbound = new java.util.ArrayList<>();
   private boolean incremental;
   private long worldVersion = -1;

   java.util.List<Outbound> takeOutbound() {
      var result = java.util.List.copyOf(outbound);
      outbound.clear();
      return result;
   }

   public void tick(TickInput input) {
      incremental = input.incremental();
      scanner.setDistances(input.clientDistance(), input.voxyDistance());
      if (this.sessionConfig != null && this.sessionConfig.enabled()) {
               int playerCx = input.playerCx();
               int playerCz = input.playerCz();
               ResourceKey<Level> currentDim = input.dimension();
               if (this.lastDimension != null && (!currentDim.equals(this.lastDimension) || worldVersion != input.worldVersion())) {
                  this.onDimensionChange(currentDim);
               } else if (!this.cacheLoaded) {
                  this.cacheLoaded = true;
                  this.startAsyncCacheLoad(currentDim);
               }

               this.lastDimension = currentDim;
               worldVersion = input.worldVersion();
               if (playerCx != this.lastChunkX || playerCz != this.lastChunkZ) {
                  // Re-open the spiral for the new center. Incremental mode (opt-in) re-centers by the
                  // distance moved, keeping confirmed inner rings closed; the default reproduces the original
                  // full restart from ring 0. Pruning runs only after PRUNE_HYSTERESIS_CHUNKS of movement
                  // either way (safe under getPruneDistance's 32-chunk margin) — the old per-cross full prune
                  // re-walked hundreds of thousands of entries on the client thread while travelling.
                  int moved = Math.max(Math.abs(playerCx - this.lastChunkX), Math.abs(playerCz - this.lastChunkZ));
                  this.lastChunkX = playerCx;
                  this.lastChunkZ = playerCz;
                  if (incremental) {
                     this.scanner.recenter(moved);
                  } else {
                     this.scanner.restartSpiral();
                  }
                  if (this.releaseEnteredLive(playerCx, playerCz) && incremental) {
                     this.scanner.lowerConfirmedRing(0);
                  }
                  if (Math.max(Math.abs(playerCx - this.lastPruneChunkX), Math.abs(playerCz - this.lastPruneChunkZ))
                        >= PRUNE_HYSTERESIS_CHUNKS) {
                     int pruneDistance = this.scanner.getPruneDistance(this.sessionConfig);
                     this.scanner.pruneOutOfRangeTimestamps(this.columnTimestamps, this.metrics, playerCx, playerCz, pruneDistance);
                      this.scanner.pruneOutOfRangePositions(this.dirtyColumns, playerCx, playerCz, pruneDistance);
                      this.scanner.pruneOutOfRangePositions(this.refreshColumns, playerCx, playerCz, pruneDistance);
                     this.scanner.pruneOutOfRangePositions(this.rateLimitRetryPositions, playerCx, playerCz, pruneDistance);
                     this.scanner.pruneOutOfRangePositions(this.validatedThisSession, playerCx, playerCz, pruneDistance);
                     this.scanner.pruneOutOfRangePositions(this.farValidated, playerCx, playerCz, pruneDistance);
                     this.pruneAndCancelOutOfRangePending(playerCx, playerCz, pruneDistance);
                     this.lastPruneChunkX = playerCx;
                     this.lastPruneChunkZ = playerCz;
                  }
               }

               this.trackVelocity(input);
               this.metrics.updateRollingRates();
               int columnQueueSize = input.queuedColumns();
               int columnQueueCapacity = 8000;
               if (columnQueueSize < columnQueueCapacity * 3 / 4) {
                  if (this.pendingCacheLoad != null) {
                     if (!this.pendingCacheLoad.isDone()) {
                        return;
                     }

                     try {
                        Long2LongOpenHashMap loaded = this.pendingCacheLoad.getNow(null);
                        if (loaded != null && this.lastDimension != null) {
                           this.loadTimestamps(loaded);
                        }
                     } catch (Exception ignored) {
                     }

                     this.pendingCacheLoad = null;
                  }

                  if (this.scanner.advanceScanTick()) {
                     if (this.skipNextScan) {
                        this.skipNextScan = false;
                     } else {
                        int viewDistance = input.viewDistance();
                        int missingVanilla = input.missingVanilla();
                        this.scanner.updateMissingVanillaChunks(missingVanilla);
                        int budget = SpiralScanner.baseBudget(this.sessionConfig);
                        int haltThreshold = columnQueueCapacity * 3 / 4;
                        if (columnQueueSize > 0) {
                           budget = Math.max(1, Math.round(budget * Math.max(0.0F, 1.0F - (float)columnQueueSize / haltThreshold)));
                        }

                         int dirtyBudget = 0;
                         for (long pos : this.dirtyColumns) {
                            if (!this.tracker.isInFlight(pos) && !PositionUtil.isOutOfRange(pos, playerCx, playerCz,
                                  this.scanner.getEffectiveLodDistance(this.sessionConfig))) {
                               if (++dirtyBudget >= budget) break;
                            }
                         }
                         if (missingVanilla > 0) {
                           int exclusionArea = (2 * viewDistance + 1) * (2 * viewDistance + 1);
                           float vanillaScale = Math.max(0.0F, 1.0F - (float)missingVanilla / exclusionArea);
                           if (vanillaScale <= 0.0F) {
                              budget = 0;
                           } else {
                              budget = Math.max(1, Math.round(budget * vanillaScale));
                            }
                         }
                         budget = Math.max(budget, dirtyBudget);

                        if (budget > 0) {
                           int lodDistance = this.scanner.getEffectiveLodDistance(this.sessionConfig);
                           double lead = LEAD_SECONDS * 20.0 / 16.0;
                           double leadX = this.velX * lead;
                           double leadZ = this.velZ * lead;
                           double leadMax = Math.max(Math.abs(leadX), Math.abs(leadZ));
                           if (leadMax > lodDistance / 2.0) {
                              leadX *= lodDistance / 2.0 / leadMax;
                              leadZ *= lodDistance / 2.0 / leadMax;
                           }
                           int leadCx = playerCx + (int) Math.round(leadX);
                           int leadCz = playerCz + (int) Math.round(leadZ);
                           // Confirmed rings are relative to the scan center.
                           if (leadCx - playerCx != this.lastLeadDx || leadCz - playerCz != this.lastLeadDz) {
                              this.scanner.lowerConfirmedRing(0);
                              this.lastLeadDx = leadCx - playerCx;
                              this.lastLeadDz = leadCz - playerCz;
                           }
                           SpiralScanner.ScanResult scanResult = this.scanner
                              .scan(
                                 playerCx,
                                 playerCz,
                                 leadCx,
                                 leadCz,
                                 viewDistance,
                                 this.columnTimestamps,
                                 this.dirtyColumns,
                                 this.rateLimitRetryPositions,
                                 this.validatedThisSession,
                                 this.tracker::isInFlight,
                                 this.sessionConfig,
                                 budget
                              );
                           if (scanResult.count() > 0) {
                              this.queue.populate(scanResult);
                              this.updateSendPerTick(scanResult.count());
                           }
                        }
                     }

                     this.tracker.timeoutSweep(TIMEOUT_NANOS, this::onRequestTimedOut, this::sendCancelPacket);
                  }

                  if (this.queue.hasNext()) {
                     int sent = this.drainQueue(this.maxSendPerTick);
                     if (sent > 0) {
                        this.sendRequests(this.sendPositionBuffer, this.sendTimestampBuffer, sent);
                     }
                  }
               }
      }
   }

   private void updateSendPerTick(int lastScanQueued) {
      int perTick = Math.min(1024, Math.max(16, (lastScanQueued + 20 - 1) / 20));
      if (perTick != this.maxSendPerTick) {
         this.maxSendPerTick = perTick;
         if (perTick > this.sendPositionBuffer.length) {
            this.sendRequestIdBuffer = new int[perTick];
            this.sendPositionBuffer = new long[perTick];
            this.sendTimestampBuffer = new long[perTick];
         }
      }
   }

   private int drainQueue(int maxToSend) {
      long now = System.nanoTime();
      long[] positionBuffer = this.sendPositionBuffer;
      long[] timestampBuffer = this.sendTimestampBuffer;
      int count = 0;

      while (count < maxToSend && this.queue.hasNext()) {
         long pos = this.queue.peekPosition();
          long ts = this.refreshColumns.contains(pos) ? VSSConstants.DIRTY_REFRESH_TIMESTAMP : this.queue.peekTimestamp();
         if (this.tracker.isInFlight(pos)) {
            this.queue.skip();
         } else {
            long stored = this.columnTimestamps.get(pos);
            if (stored > 0L && !this.dirtyColumns.contains(pos) && !this.rateLimitRetryPositions.contains(pos) && this.validatedThisSession.contains(pos)) {
               this.queue.skip();
            } else {
               boolean isGen = ts == 0L;
               if (isGen && this.tracker.generationCount() >= this.maxGenConcurrency) {
                  this.queue.skip();
               } else if (!isGen && this.tracker.size() - this.tracker.generationCount() >= this.maxSyncConcurrency) {
                  this.queue.skip();
               } else {
                  this.queue.skip();
                  positionBuffer[count] = pos;
                   timestampBuffer[count] = ts;
                  this.tracker.markPending(pos, now, isGen);
                  this.rateLimitRetryPositions.remove(pos);
                  this.dirtyColumns.remove(pos);
                  count++;
               }
            }
         }
      }

      return count;
   }

   private void sendRequests(long[] positionBuffer, long[] timestampBuffer, int count) {
      int[] requestIds = this.sendRequestIdBuffer;

      for (int i = 0; i < count; i++) {
         requestIds[i] = this.tracker.send(positionBuffer[i]);
      }

      boolean[] cached = new boolean[count];
      for (int i = 0; i < count; i++) {
         cached[i] = columnTimestamps.get(positionBuffer[i]) > 0L || refreshColumns.contains(positionBuffer[i]);
      }
      // Scratch arrays are reused next tick; the main-thread handoff owns independent arrays.
      outbound.add(new Outbound(new BatchChunkRequestC2SPayload(
            java.util.Arrays.copyOf(requestIds, count), java.util.Arrays.copyOf(positionBuffer, count),
            java.util.Arrays.copyOf(timestampBuffer, count), count), cached));

      this.metrics.recordSendCycle(count);
   }

   /** @return true if the client already had a cached version of this column (i.e. this is an update/re-sync,
    *  not a fresh ingest) — the ingest then clears any sub-chunks that emptied since (e.g. a broken pillar). */
   public boolean onColumnReceived(int requestId, long columnTimestamp) {
      InFlightTracker.RemovedRequest completion = this.tracker.removeByRequestId(requestId);
      boolean wasCached = false;
      if (completion != null) {
          wasCached = this.columnTimestamps.get(completion.position()) > 0L || this.refreshColumns.contains(completion.position());
          // A newer dirty notification may have arrived while this response was in flight.
          if (!this.dirtyColumns.contains(completion.position())) this.refreshColumns.remove(completion.position());
         this.putTimestamp(completion.position(), columnTimestamp);
         this.markValidated(completion.position());
      }

      this.metrics.recordColumnReceived();
      return wasCached;
   }

   boolean acceptsColumn(com.golem.boxy.vss.payloads.VoxelColumnS2CPayload payload) {
      return payload.dimension().equals(this.lastDimension)
            && this.tracker.matches(payload.requestId(), PositionUtil.packPosition(payload.chunkX(), payload.chunkZ()));
   }

   boolean hasCachedColumn(int x, int z) {
       long packed = PositionUtil.packPosition(x, z);
       return this.columnTimestamps.get(packed) > 0L || this.refreshColumns.contains(packed);
   }

   public void onDirtyColumns(long[] dirtyPositions) {
      // Re-open the spiral to cover the dirtied columns. Incremental mode (opt-in) drops the confirmed ring
      // only to the innermost dirty column's ring; the default reproduces the original full restart from
      // ring 0 (which made every ~2s dirty broadcast re-walk the entire scan area next scan tick).
      boolean incrementalScan = this.incremental;
      int minRing = Integer.MAX_VALUE;

       for (long packed : dirtyPositions) {
             this.dirtyColumns.add(packed);
             this.refreshColumns.add(packed);
            if (incremental) {
               int ring = PositionUtil.chebyshevDistance(
                     PositionUtil.unpackX(packed), PositionUtil.unpackZ(packed), this.lastChunkX, this.lastChunkZ);
               minRing = Math.min(minRing, ring);
            } else {
               minRing = 0; // mark "something was added" so the full reset below fires
       }
      }

      if (minRing != Integer.MAX_VALUE) {
         if (incrementalScan) {
            this.scanner.lowerConfirmedRing(minRing);
         } else {
            this.scanner.restartSpiral();
         }
      }
   }

   public void onColumnNotGenerated(int requestId) {
      InFlightTracker.RemovedRequest removal = this.tracker.removeByRequestId(requestId);
       if (removal != null) {
          this.putTimestamp(removal.position(), 0L);
          if (!this.dirtyColumns.contains(removal.position())) this.refreshColumns.remove(removal.position());
      }

      this.metrics.recordNotGenerated();
   }

   public void onColumnUpToDate(int requestId) {
      InFlightTracker.RemovedRequest completion = this.tracker.removeByRequestId(requestId);
      if (completion != null) {
         this.markValidated(completion.position());
         if (this.columnTimestamps.get(completion.position()) == -1L) {
            this.putTimestamp(completion.position(), VSSConstants.epochSeconds());
         }
      }

      this.metrics.recordUpToDate();
   }

   public void onRateLimited(int requestId) {
      InFlightTracker.RemovedRequest removal = this.tracker.removeByRequestId(requestId);
       if (removal != null) {
          this.rateLimitRetryPositions.add(removal.position());
          if (this.refreshColumns.contains(removal.position())) this.dirtyColumns.add(removal.position());
      }

      this.metrics.recordRateLimited();
      this.skipNextScan = true;
   }

   private void onDimensionChange(ResourceKey<Level> newDimension) {
      this.saveCache();
      this.cancelAllPending();
      this.resetRequestState();
      this.queue.clear();
      this.scanner.restartSpiral();
      this.cacheLoaded = true;
      this.startAsyncCacheLoad(newDimension);
   }

   private void resetRequestState() {
      this.clearTimestamps();
       this.dirtyColumns.clear();
       this.refreshColumns.clear();
      this.rateLimitRetryPositions.clear();
      this.validatedThisSession.clear();
      this.farValidated.clear();
      this.tracker.clear();
      this.skipNextScan = false;
   }

   private void trackVelocity(TickInput input) {
      long dt = input.gameTime() - this.lastGameTime;
      double dx = input.playerX() - this.lastX;
      double dz = input.playerZ() - this.lastZ;
      this.lastX = input.playerX();
      this.lastZ = input.playerZ();
      this.lastGameTime = input.gameTime();
      if (dt <= 0 || dt > 20 || Math.max(Math.abs(dx), Math.abs(dz)) > TELEPORT_BLOCKS * dt) {
         this.velX = 0.0;
         this.velZ = 0.0;
         return;
      }
      this.velX += (dx / dt - this.velX) * 0.2;
      this.velZ += (dz / dt - this.velZ) * 0.2;
   }

   /** Validated beyond the live distance => later changes fogged => revalidate on entry. */
   private void markValidated(long position) {
      this.validatedThisSession.add(position);
      if (this.sessionConfig != null && PositionUtil.isOutOfRange(position, this.lastChunkX, this.lastChunkZ, this.liveRelease())) {
         this.farValidated.add(position);
      }
   }

   /** Server judges live distance by its own, lagging player position: margin LIVE_MARGIN_CHUNKS. */
   private int liveRelease() {
      return Math.max(0, this.sessionConfig.liveDistanceChunks() - LIVE_MARGIN_CHUNKS);
   }

   /** @return whether any column re-entered the live distance. */
   private boolean releaseEnteredLive(int playerCx, int playerCz) {
      if (this.farValidated.isEmpty()) return false;
      int live = this.liveRelease();
      boolean released = false;
      for (int x = playerCx - live; x <= playerCx + live; x++) {
         for (int z = playerCz - live; z <= playerCz + live; z++) {
            long packed = PositionUtil.packPosition(x, z);
            if (this.farValidated.remove(packed)) {
               this.validatedThisSession.remove(packed);
               released = true;
            }
         }
      }
      return released;
   }

   private void startAsyncCacheLoad(ResourceKey<Level> dimension) {
      this.pendingCacheLoad = ColumnCacheStore.loadAsync(this.serverAddress, dimension);
   }

   private void sendCancelPacket(int reqId) {
      outbound.add(new Outbound(new CancelRequestC2SPayload(reqId), null));
   }

   private void pruneAndCancelOutOfRangePending(int playerCx, int playerCz, int pruneDistance) {
      this.tracker.pruneOutOfRange(playerCx, playerCz, pruneDistance, this::sendCancelPacket);
   }

   /** A timed-out request's column is no longer in flight; re-open the spiral at its ring so it gets
    *  re-requested even if the player is standing still. Runs in <b>both</b> rescan modes: it's a
    *  strict safety net (cheaper than a full reset, only fires after a 10s timeout) that also closes a
    *  latent gap the original had — an in-flight column advances the confirmed ring past itself, so on
    *  timeout a stationary player would otherwise never re-request it. */
   private void onRequestTimedOut(long pos) {
       if (this.refreshColumns.contains(pos)) this.dirtyColumns.add(pos);
      this.scanner.lowerConfirmedRing(PositionUtil.chebyshevDistance(
            PositionUtil.unpackX(pos), PositionUtil.unpackZ(pos), this.lastChunkX, this.lastChunkZ));
   }

   private void cancelAllPending() {
      this.tracker.forEachRequestId(this::sendCancelPacket);
   }

   public void disconnect() {
      this.tracker.clear();
   }

   public void saveCache() {
      if (this.serverAddress != null && this.lastDimension != null && !this.columnTimestamps.isEmpty()) {
         ColumnCacheStore.saveAsync(this.serverAddress, this.lastDimension, this.columnTimestamps);
      }
   }

   public void flushCache() {
      this.pendingCacheLoad = null;
      if (this.serverAddress != null) {
         ColumnCacheStore.clearForServerAsync(this.serverAddress);
      }

      this.cancelAllPending();
      this.resetRequestState();
      this.queue.clear();
      this.scanner.reset();
   }

   void retryAll() {
      cancelAllPending();
      tracker.clear();
      queue.clear();
      validatedThisSession.clear();
      dirtyColumns.addAll(columnTimestamps.keySet());
      refreshColumns.addAll(columnTimestamps.keySet());
      scanner.reset();
   }

   Diagnostics diagnostics() {
      return new Diagnostics(getReceivedColumnCount(), getEmptyColumnCount(), getEffectiveLodDistanceChunks(), getTotalSendCycles(), getTotalPositionsRequested(), getDirtyColumnCount(), getConfirmedRing(), getScanRing(), getMissingVanillaChunks(), getTotalColumnsReceived(), getTotalUpToDate(), getTotalNotGenerated(), getTotalRateLimited(), getReceiveRate(), getRequestRate(), getPendingCount(), getQueueRemaining(), getLastBudget(), getLastSyncQueued(), getLastGenQueued());
   }

   record Diagnostics(int getReceivedColumnCount, int getEmptyColumnCount, int getEffectiveLodDistanceChunks, long getTotalSendCycles, long getTotalPositionsRequested, int getDirtyColumnCount, int getConfirmedRing, int getScanRing, int getMissingVanillaChunks, long getTotalColumnsReceived, long getTotalUpToDate, long getTotalNotGenerated, long getTotalRateLimited, double getReceiveRate, double getRequestRate, int getPendingCount, int getQueueRemaining, int getLastBudget, int getLastSyncQueued, int getLastGenQueued) {
      static Diagnostics empty() { return new Diagnostics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0); }
   }

   public int getReceivedColumnCount() {
      return this.metrics.getReceivedCount();
   }

   public int getEmptyColumnCount() {
      return this.metrics.getEmptyCount();
   }

   public int getEffectiveLodDistanceChunks() {
      return this.sessionConfig != null ? this.scanner.getEffectiveLodDistance(this.sessionConfig) : 0;
   }

   public long getTotalSendCycles() {
      return this.metrics.getTotalSendCycles();
   }

   public long getTotalPositionsRequested() {
      return this.metrics.getTotalPositionsRequested();
   }

   public int getDirtyColumnCount() {
      return this.dirtyColumns.size();
   }

   public int getConfirmedRing() {
      return this.scanner.getConfirmedRing();
   }

   public int getScanRing() {
      return this.scanner.getScanRing();
   }

   public int getMissingVanillaChunks() {
      return this.scanner.getMissingVanillaChunks();
   }

   public long getTotalColumnsReceived() {
      return this.metrics.getTotalColumnsReceived();
   }

   public long getTotalUpToDate() {
      return this.metrics.getTotalUpToDate();
   }

   public long getTotalNotGenerated() {
      return this.metrics.getTotalNotGenerated();
   }

   public long getTotalRateLimited() {
      return this.metrics.getTotalRateLimited();
   }

   public double getReceiveRate() {
      return this.metrics.getReceiveRate();
   }

   public double getRequestRate() {
      return this.metrics.getRequestRate();
   }

   public int getPendingCount() {
      return this.tracker.size();
   }

   public int getQueueRemaining() {
      return this.queue.remaining();
   }

   public int getLastBudget() {
      return this.scanner.getLastBudget();
   }

   public int getLastSyncQueued() {
      return this.scanner.getLastSyncQueued();
   }

   public int getLastGenQueued() {
      return this.scanner.getLastGenQueued();
   }
}


