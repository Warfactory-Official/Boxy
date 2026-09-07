package com.golem.boxy.vss.common.processing;

import com.golem.boxy.vss.common.PositionUtil;
import com.golem.boxy.vss.common.VSSConstants;
import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.common.voxel.ColumnTimestampCache;
import com.golem.boxy.vss.common.voxel.SerializedColumnCache;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class OffThreadProcessor<PlayerState extends PlayerStateAccess, ReadResult extends ReadResultAccess> {
   private static final int SNAPSHOT_POLL_MS = 50;
   private static final int SHUTDOWN_JOIN_MS = 5000;
   private static final int EVICTION_INTERVAL_CYCLES = 1200;
   private static final int SAVE_INTERVAL_CYCLES = 6000;
   /** Backlog cap for live serialization; past this, requests fall back to the disk path (never dropped). */
   private static final int MAX_QUEUED_LIVE_SERIALIZE = 4096;
   private final Object snapshotLock = new Object();
   private TickSnapshot pendingSnapshot;
   private final ConcurrentLinkedQueue<SendAction> sendActions = new ConcurrentLinkedQueue<>();
   private final ConcurrentLinkedQueue<OffThreadProcessor.GenerationTicketRequest> generationTicketRequests = new ConcurrentLinkedQueue<>();
   private final ConcurrentLinkedQueue<OffThreadProcessor.TimestampInvalidation> timestampInvalidations = new ConcurrentLinkedQueue<>();
   private final ConcurrentLinkedQueue<TickSnapshot.GenerationReadyData> droppedGenerationReady = new ConcurrentLinkedQueue<>();
   private final ConcurrentLinkedQueue<UUID> droppedRemovedPlayers = new ConcurrentLinkedQueue<>();
   private final ConcurrentLinkedQueue<UUID> droppedDimensionChanges = new ConcurrentLinkedQueue<>();
   private final ConcurrentLinkedQueue<OffThreadProcessor.LiveSerializeRequest> liveSerializeRequests = new ConcurrentLinkedQueue<>();
   private final Thread processingThread;
   private final ColumnTimestampCache timestampCache;
   private final SerializedColumnCache bytesCache;
   private final Map<UUID, PlayerState> players;
   private final boolean diskReadingAvailable;
   private final boolean generationAvailable;
   private final ProcessingContext ctx;
   private final IncomingRequestRouter<PlayerState> requestRouter;
   private final DedupTracker dedupTracker = new DedupTracker();
   private final Path dataDir;
   private int evictionCounter;
   private int saveCounter;
   private int consecutiveErrors;
   private long cycleNow;
   private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "VSS-TimestampSave");
      t.setDaemon(true);
      return t;
   });

   protected OffThreadProcessor(
      Map<UUID, PlayerState> players, boolean diskReadingAvailable, boolean generationAvailable, Path dataDir, int perDimensionTimestampCacheSizeMB,
      SerializedColumnCache bytesCache
   ) {
      this.bytesCache = bytesCache;
      this.players = players;
      this.diskReadingAvailable = diskReadingAvailable;
      this.generationAvailable = generationAvailable;
      this.dataDir = dataDir;
      this.timestampCache = new ColumnTimestampCache(ColumnTimestampCache.mbToEntries(perDimensionTimestampCacheSizeMB));
      if (dataDir != null) {
         this.timestampCache.load(dataDir);
      }

      this.ctx = new ProcessingContext(this.sendActions, this.generationTicketRequests, new ProcessingDiagnostics(), new SequenceCounter(), bytesCache);
      this.requestRouter = new IncomingRequestRouter<>(this.timestampCache, this.dedupTracker, diskReadingAvailable, generationAvailable, this.ctx);
      this.processingThread = new Thread(this::processingLoop, "VSS Processing Thread");
      this.processingThread.setDaemon(true);
      this.processingThread.setPriority(4);
   }

   public void start() {
      this.processingThread.start();
   }

   public void postSnapshot(TickSnapshot snapshot) {
      synchronized (this.snapshotLock) {
         if (this.pendingSnapshot != null) {
            // The unconsumed snapshot is about to be discarded, so anything in it that owns resources has to
            // be carried forward or it leaks. Generation slots hold a concurrency permit; removedPlayers holds
            // DedupTracker groups and every syncOnLoad permit that player's in-flight requests acquired.
            for (TickSnapshot.GenerationReadyData genReady : this.pendingSnapshot.generationReady()) {
               this.droppedGenerationReady.add(genReady);
            }

             this.droppedRemovedPlayers.addAll(this.pendingSnapshot.removedPlayers());
             this.pendingSnapshot.players().forEach((uuid, data) -> {
                if (data.dimensionChanged()) this.droppedDimensionChanges.add(uuid);
             });
         }

         this.pendingSnapshot = snapshot;
         this.snapshotLock.notifyAll();
      }
   }

   public SendAction pollSendAction() {
      return this.sendActions.poll();
   }

   public OffThreadProcessor.GenerationTicketRequest pollGenerationTicketRequest() {
      return this.generationTicketRequests.poll();
   }

   /**
    * Drained by the server thread, which serializes the live chunk and publishes the bytes into the player's
    * disk-result queue. Every entry already owns a syncOnLoad permit and a pendingByPosition slot, so the
    * drain must produce exactly one result per entry (falling back to a disk read if the chunk is gone) —
    * dropping one leaks the permit.
    */
   public OffThreadProcessor.LiveSerializeRequest pollLiveSerializeRequest() {
      return this.liveSerializeRequests.poll();
   }

   private void requestLiveSerialize(UUID playerUuid, int requestId, String dimension, int cx, int cz, long submissionOrder) {
      // Backlogged: the server thread is not keeping up with live serialization, so send this one down the
      // disk path instead. Falling back rather than dropping is mandatory — the caller already acquired a
      // syncOnLoad permit and registered a pending entry, and only a result releases them. Slightly staler
      // bytes (last autosave) is the acceptable cost; a leaked permit is not.
      if (this.liveSerializeRequests.size() >= MAX_QUEUED_LIVE_SERIALIZE) {
          this.submitDiskRead(playerUuid, requestId, dimension, cx, cz, submissionOrder, true);
         return;
      }

      this.liveSerializeRequests.add(new OffThreadProcessor.LiveSerializeRequest(playerUuid, requestId, dimension, cx, cz, submissionOrder));
   }

   public void invalidateTimestamps(String dimension, long[] positions) {
      this.timestampInvalidations.add(new OffThreadProcessor.TimestampInvalidation(dimension, positions));
   }

   protected abstract ReadResult pollDiskResult(PlayerState state);

   protected abstract ReadResult pollGenerationResult(PlayerState state);

   protected abstract void enqueueResultPayloads(PlayerState state, ReadResult result);

    protected abstract void submitDiskRead(UUID playerUuid, int requestId, String dimension, int cx, int cz, long submissionOrder, boolean forceFresh);

   protected boolean compressAndEnqueueLoaded(
      PlayerState state, LoadedColumnData column, int requestId, long columnTimestamp, long submissionOrder, String dimension
   ) {
       if (column.serializedSections() != null) {
         int estimatedBytes = column.serializedSections().length + 25;
         long packed = PositionUtil.packPosition(column.cx(), column.cz());
         this.timestampCache.put(dimension, packed, columnTimestamp, this.cycleNow);
         this.buildAndEnqueueColumnPayload(
            state, column.cx(), column.cz(), dimension, requestId, columnTimestamp, submissionOrder, column.serializedSections(), estimatedBytes
         );
         return true;
      } else {
         return false;
      }
   }

   protected abstract void buildAndEnqueueColumnPayload(
      PlayerState state, int cx, int cz, String dimension, int requestId, long columnTimestamp, long submissionOrder, byte[] sectionBytes, int estimatedBytes
   );

   private void processingLoop() {
      while (true) {
         TickSnapshot snapshot;
         synchronized (this.snapshotLock) {
            while (this.pendingSnapshot == null) {
               try {
                  this.snapshotLock.wait(SNAPSHOT_POLL_MS);
               } catch (InterruptedException ignored) {
                  return;
               }
            }

            snapshot = this.pendingSnapshot;
            this.pendingSnapshot = null;
         }

         if (snapshot.shutdown()) {
            return;
         }

         try {
            this.processCycle(snapshot);
            this.consecutiveErrors = 0;
         } catch (Exception e) {
            VSSLogger.error("Error in processing cycle", e);
            if (++this.consecutiveErrors >= 10) {
               VSSLogger.error("Processing thread hit " + this.consecutiveErrors + " consecutive errors, backing off");

               try {
                  Thread.sleep(1000L);
               } catch (InterruptedException ignored) {
                  return;
               }
            }
         }
      }
   }

   private void processCycle(TickSnapshot snapshot) {
      this.cycleNow = VSSConstants.epochSeconds();
      this.ctx.diagnostics().resetTickCounters();
      this.releaseDroppedGenerationSlots();
      this.preparePlayers(snapshot);
      this.drainDiskResultsForAllPlayers(snapshot);
      this.drainGenerationResultsForAllPlayers(snapshot);
      this.processGenerationReady(snapshot);
      this.routeIncomingRequests(snapshot);
      if (++this.evictionCounter >= EVICTION_INTERVAL_CYCLES) {
         this.evictionCounter = 0;
         int evicted = this.timestampCache.evictIfOversized();
         if (evicted > 0 && VSSLogger.isDebugEnabled()) {
            VSSLogger.debug("Evicted " + evicted + " oversized timestamp cache entries (" + this.timestampCache.size() + " remaining)");
         }

         // Re-applies the byte budget so a config reduction takes effect; puts already evict inline.
         this.bytesCache.evictIfOversized();
      }

      if (this.dataDir != null && ++this.saveCounter >= SAVE_INTERVAL_CYCLES) {
         this.saveCounter = 0;
         ColumnTimestampCache cacheSnapshot = this.timestampCache.snapshotForSave();
         SAVE_EXECUTOR.execute(() -> cacheSnapshot.save(this.dataDir));
      }
   }

   private void releaseDroppedGenerationSlots() {
      TickSnapshot.GenerationReadyData genReady;
      while ((genReady = this.droppedGenerationReady.poll()) != null) {
         PlayerState state = this.players.get(genReady.playerUuid());
          if (state != null) {
             if (state.removePendingByRequestId(genReady.requestId()) != null) state.getRateLimiters().generation().release();
         }
      }
   }

   private void preparePlayers(TickSnapshot snapshot) {
      OffThreadProcessor.TimestampInvalidation inv;
      while ((inv = this.timestampInvalidations.poll()) != null) {
         this.timestampCache.invalidate(inv.dimension(), inv.positions());
      }

      UUID droppedUuid;
      while ((droppedUuid = this.droppedRemovedPlayers.poll()) != null) {
         this.cleanupDedupGroups(this.dedupTracker.removePlayer(droppedUuid));
      }

      for (UUID removedUuid : snapshot.removedPlayers()) {
         this.cleanupDedupGroups(this.dedupTracker.removePlayer(removedUuid));
      }

      UUID changedUuid;
      while ((changedUuid = this.droppedDimensionChanges.poll()) != null) {
         PlayerState state = this.players.get(changedUuid);
         if (state != null) state.clearProcessingState();
         this.cleanupDedupGroups(this.dedupTracker.removePlayer(changedUuid));
      }

      for (Entry<UUID, TickSnapshot.PlayerTickData> entry : snapshot.players().entrySet()) {
         PlayerState state = this.players.get(entry.getKey());
         if (state != null) {
            if (entry.getValue().dimensionChanged()) {
               state.clearProcessingState();
               this.cleanupDedupGroups(this.dedupTracker.removePlayer(entry.getKey()));
            }

            state.drainDirtyClearRequests();

            Integer cancelId;
            while ((cancelId = state.pollCancel()) != null) {
               PendingRequest pending = state.removePendingByRequestId(cancelId);
               if (pending != null) {
                  state.getRateLimiters().forRequest(pending.type(), this.diskReadingAvailable).release();
               }
            }
         }
      }
   }

   private void cleanupDedupGroups(List<DedupTracker.RemovedGroup> removedGroups) {
      for (DedupTracker.RemovedGroup rg : removedGroups) {
         for (DedupTracker.Attachment attachment : rg.group().attached()) {
            PlayerState attachedState = this.players.get(attachment.playerUuid());
            if (attachedState != null) {
               if (attachedState.removePendingByRequestId(attachment.requestId()) != null) {
                  attachedState.getRateLimiters().syncOnLoad().release();
               }
            }
         }
      }
   }

   private void drainDiskResultsForAllPlayers(TickSnapshot snapshot) {
      for (Entry<UUID, TickSnapshot.PlayerTickData> entry : snapshot.players().entrySet()) {
         PlayerState state = this.players.get(entry.getKey());
         if (state != null) {
            UUID playerUuid = entry.getKey();

            ReadResult result;
            while ((result = this.pollDiskResult(state)) != null) {
               int cx = result.chunkX();
               int cz = result.chunkZ();
               String dimension = result.dimension() != null ? result.dimension() : entry.getValue().dimension();
               if (!dimension.equals(entry.getValue().dimension())) continue;
               PendingRequest pending = state.removePendingByRequestId(result.requestId());
               int requestId = result.requestId();
               long packed = PositionUtil.packPosition(cx, cz);
               DedupTracker.Group group = this.dedupTracker.removeGroup(packed, dimension, playerUuid, requestId);
               if (pending == null) {
                  if (group != null) this.dispatchDedupGroup(group, result, cx, cz);
                  continue;
               }
               state.getRateLimiters().syncOnLoad().release();
               if (result.saturated()) {
                  this.ctx.sendActions().add(new SendAction.RateLimited(playerUuid, requestId));
                  if (VSSLogger.isDebugEnabled()) {
                     VSSLogger.debug("Rate-limited " + playerUuid + " (disk saturated): chunk [" + cx + ", " + cz + "]");
                  }
               } else if (result.notFound()) {
                  this.handleDiskNotFound(playerUuid, state, requestId, cx, cz, pending, dimension);
               } else {
                  state.markDiskReadDone(cx, cz);
                  if (result.sectionBytes() != null) {
                     this.enqueueResultPayloads(state, result);
                  } else {
                     this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, requestId));
                  }

                  this.timestampCache.put(entry.getValue().dimension(), packed, result.columnTimestamp(), this.cycleNow);
               }

               this.ctx.diagnostics().incrementDiskDrained();
               if (group != null) {
                  this.dispatchDedupGroup(group, result, cx, cz);
               }
            }
         }
      }
   }

   private void dispatchDedupGroup(DedupTracker.Group group, ReadResult result, int cx, int cz) {
      byte[] sectionBytes = result.sectionBytes();

      for (DedupTracker.Attachment attachment : group.attached()) {
         PlayerState attachedState = this.players.get(attachment.playerUuid());
         if (attachedState != null) {
            PendingRequest attachedPending = attachedState.removePendingByRequestId(attachment.requestId());
            if (attachedPending == null) continue;
            attachedState.getRateLimiters().syncOnLoad().release();
            int attachedRequestId = attachment.requestId();
            if (result.saturated()) {
               this.ctx.sendActions().add(new SendAction.RateLimited(attachment.playerUuid(), attachedRequestId));
            } else if (result.notFound()) {
               this.handleDiskNotFound(attachment.playerUuid(), attachedState, attachedRequestId, cx, cz, attachedPending, group.dimension());
            } else if (sectionBytes != null) {
               attachedState.markDiskReadDone(cx, cz);
               this.buildAndEnqueueColumnPayload(
                  attachedState,
                  cx,
                  cz,
                  group.dimension(),
                  attachedRequestId,
                  result.columnTimestamp(),
                  attachment.submissionOrder(),
                  sectionBytes,
                  result.estimatedBytes()
               );
            } else {
               attachedState.markDiskReadDone(cx, cz);
               this.ctx.sendActions().add(new SendAction.ColumnUpToDate(attachment.playerUuid(), attachedRequestId));
            }

            this.ctx.diagnostics().incrementDiskDrained();
         }
      }
   }

   private void handleDiskNotFound(UUID playerUuid, PlayerState state, int requestId, int cx, int cz, PendingRequest pending, String dimension) {
      if (pending == null || pending.type() != RequestType.GENERATION || !this.generationAvailable) {
         this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, requestId));
      } else if (state.getRateLimiters().generation().tryAcquire()) {
         this.ctx.generationTicketRequests().add(new OffThreadProcessor.GenerationTicketRequest(playerUuid, requestId, cx, cz, this.ctx.sequence().next(), dimension));
         state.addPendingRequest(new PendingRequest(requestId, cx, cz, RequestType.GENERATION));
      } else {
         this.ctx.sendActions().add(new SendAction.RateLimited(playerUuid, requestId));
      }
   }

   private void drainGenerationResultsForAllPlayers(TickSnapshot snapshot) {
      for (Entry<UUID, TickSnapshot.PlayerTickData> entry : snapshot.players().entrySet()) {
         PlayerState state = this.players.get(entry.getKey());
         ReadResult result;
         if (state != null) {
            for (UUID playerUuid = entry.getKey(); (result = this.pollGenerationResult(state)) != null; this.ctx.diagnostics().incrementGenDrained()) {
               int cx = result.chunkX();
               int cz = result.chunkZ();
               PendingRequest pending = state.removePendingByRequestId(result.requestId());
               if (pending == null) continue;
               int requestId = result.requestId();
               state.getRateLimiters().generation().release();
                if (result.saturated()) {
                   this.ctx.sendActions().add(new SendAction.RateLimited(playerUuid, requestId));
                } else if (result.notFound()) {
                  this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, requestId));
               } else {
                  state.markDiskReadDone(cx, cz);
                  this.enqueueResultPayloads(state, result);
                  long packed = PositionUtil.packPosition(cx, cz);
                  this.timestampCache.put(entry.getValue().dimension(), packed, result.columnTimestamp(), this.cycleNow);
               }
            }
         }
      }
   }

   private void processGenerationReady(TickSnapshot snapshot) {
      for (TickSnapshot.GenerationReadyData genReady : snapshot.generationReady()) {
          PlayerState state = this.players.get(genReady.playerUuid());
          if (state != null) {
             TickSnapshot.PlayerTickData playerData = snapshot.players().get(genReady.playerUuid());
             if (playerData == null || playerData.dimensionChanged() || !playerData.dimension().equals(genReady.dimension())) continue;
             int cx = genReady.columnData().cx();
             int cz = genReady.columnData().cz();
             if (state.removePendingByRequestId(genReady.requestId()) == null) continue;
            state.markDiskReadDone(cx, cz);
            state.getRateLimiters().generation().release();
            if (playerData != null) {
               String dimension = playerData.dimension();
               boolean sent = this.compressAndEnqueueLoaded(
                  state, genReady.columnData(), genReady.requestId(), genReady.columnTimestamp(), genReady.submissionOrder(), dimension
               );
               if (!sent) {
                  this.sendActions.add(new SendAction.ColumnUpToDate(genReady.playerUuid(), genReady.requestId()));
               }

               this.ctx.diagnostics().incrementGenDrained();
            }
         }
      }
   }

   private void routeIncomingRequests(TickSnapshot snapshot) {
      this.requestRouter.routeAll(snapshot, this.players, this::submitDiskRead, this::requestLiveSerialize, this::buildAndEnqueueColumnPayload, this.cycleNow);
   }

   public ProcessingDiagnostics getDiagnostics() {
      return this.ctx.diagnostics();
   }

   public void shutdown() {
      this.postSnapshot(TickSnapshot.shutdownSentinel());

      try {
         this.processingThread.interrupt();
         this.processingThread.join(SHUTDOWN_JOIN_MS);
         if (this.processingThread.isAlive()) {
            VSSLogger.warn("Processing thread did not terminate within " + SHUTDOWN_JOIN_MS + "ms");
         }
      } catch (InterruptedException ignored) {
         Thread.currentThread().interrupt();
      }

      if (this.dataDir != null) {
         this.timestampCache.save(this.dataDir);
      }
   }

   public record GenerationTicketRequest(UUID playerUuid, int requestId, int cx, int cz, long submissionOrder, String dimension) {
   }

   public record LiveSerializeRequest(UUID playerUuid, int requestId, String dimension, int cx, int cz, long submissionOrder) {
   }

   private record TimestampInvalidation(String dimension, long[] positions) {
   }
}
