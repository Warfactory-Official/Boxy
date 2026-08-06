package com.golem.boxy.vss.common.processing;

import com.golem.boxy.vss.common.PositionUtil;
import com.golem.boxy.vss.common.VSSConstants;
import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.common.voxel.ColumnTimestampCache;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;

class IncomingRequestRouter<PS extends PlayerStateAccess> {
   private final ColumnTimestampCache timestampCache;
   private final DedupTracker dedupTracker;
   private final boolean diskReadingAvailable;
   private final boolean generationAvailable;
   private final ProcessingContext ctx;

   IncomingRequestRouter(
      ColumnTimestampCache timestampCache, DedupTracker dedupTracker, boolean diskReadingAvailable, boolean generationAvailable, ProcessingContext ctx
   ) {
      this.timestampCache = timestampCache;
      this.dedupTracker = dedupTracker;
      this.diskReadingAvailable = diskReadingAvailable;
      this.generationAvailable = generationAvailable;
      this.ctx = ctx;
   }

   void routeAll(
      TickSnapshot snapshot,
      Map<UUID, PS> players,
      IncomingRequestRouter.DiskReadSubmitter diskReadSubmitter,
      IncomingRequestRouter.LiveColumnRouter liveColumnRouter,
      IncomingRequestRouter.ColumnPayloadEnqueuer<PS> payloadEnqueuer,
      long cycleNow
   ) {
      for (Entry<UUID, TickSnapshot.PlayerTickData> entry : snapshot.players().entrySet()) {
         if (!entry.getValue().dimensionChanged()) {
            PS state = players.get(entry.getKey());
            if (state != null && state.supportsVoxelColumns()) {
               this.processIncomingRequests(state, entry.getKey(), entry.getValue(), snapshot, diskReadSubmitter, liveColumnRouter, payloadEnqueuer, cycleNow);
            }
         }
      }
   }

   private void processIncomingRequests(
      PS state,
      UUID playerUuid,
      TickSnapshot.PlayerTickData playerData,
      TickSnapshot snapshot,
      IncomingRequestRouter.DiskReadSubmitter diskReadSubmitter,
      IncomingRequestRouter.LiveColumnRouter liveColumnRouter,
      IncomingRequestRouter.ColumnPayloadEnqueuer<PS> payloadEnqueuer,
      long cycleNow
   ) {
      String dimension = playerData.dimension();
      LongSet loadedPositions = snapshot.loadedPositions().getOrDefault(playerUuid, LongSets.EMPTY_SET);
      this.drainWaitingQueue(state, playerUuid, dimension, loadedPositions, snapshot, diskReadSubmitter, liveColumnRouter, payloadEnqueuer, cycleNow);

      // Bounded by the prefix the server thread probed this tick — see TickSnapshot.PlayerTickData.probedCount.
      int remaining = playerData.probedCount();
      IncomingRequest req;
      while (remaining > 0 && (req = state.pollIncomingRequest()) != null) {
         remaining--;
         if (!this.resolvedAsDuplicate(state, playerUuid, req)) {
            if (this.sendQueueFull(state, snapshot)) {
               break;
            }

            long packed = PositionUtil.packPosition(req.cx(), req.cz());
            if (!this.resolvedFromTimestamp(state, playerUuid, req, packed, dimension)
               && !this.resolvedFromLiveColumn(state, playerUuid, req, packed, loadedPositions, dimension, liveColumnRouter, payloadEnqueuer, cycleNow)) {
               RequestType type = req.clientTimestamp() == 0L ? RequestType.GENERATION : RequestType.SYNC;
               ConcurrencyLimiter limiter = this.tryConcurrencyOrEnqueue(state, playerUuid, req, type, dimension);
               if (limiter != null) {
                  this.submitToDiskOrGeneration(state, playerUuid, req, packed, dimension, type, limiter, diskReadSubmitter);
               }
            }
         }
      }
   }

   private void drainWaitingQueue(
      PS state,
      UUID playerUuid,
      String dimension,
      LongSet loadedPositions,
      TickSnapshot snapshot,
      IncomingRequestRouter.DiskReadSubmitter diskReadSubmitter,
      IncomingRequestRouter.LiveColumnRouter liveColumnRouter,
      IncomingRequestRouter.ColumnPayloadEnqueuer<PS> payloadEnqueuer,
      long cycleNow
   ) {
      ArrayDeque<AbstractPlayerRequestState.QueuedRequest> queue = state.getWaitingQueue();

      while (!queue.isEmpty()) {
         AbstractPlayerRequestState.QueuedRequest queued = queue.peek();
         IncomingRequest req = queued.request();
         long packed = PositionUtil.packPosition(req.cx(), req.cz());
         if (state.hasDiskReadDone(req.cx(), req.cz())) {
            queue.poll();
            this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, req.requestId()));
         } else if (this.resolvedFromTimestamp(state, playerUuid, req, packed, dimension)) {
            queue.poll();
         } else if (this.resolvedFromLiveColumn(state, playerUuid, req, packed, loadedPositions, dimension, liveColumnRouter, payloadEnqueuer, cycleNow)) {
            queue.poll();
         } else {
            ConcurrencyLimiter limiter = state.getRateLimiters().forRequest(queued.type(), this.diskReadingAvailable);
            if (!limiter.tryAcquire()) {
               break;
            }

            queue.poll();
            this.submitToDiskOrGeneration(state, playerUuid, req, packed, dimension, queued.type(), limiter, diskReadSubmitter);
         }
      }
   }

   private boolean resolvedAsDuplicate(PS state, UUID playerUuid, IncomingRequest req) {
      if (state.hasDiskReadDone(req.cx(), req.cz())) {
         this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, req.requestId()));
         return true;
      } else if (state.hasPendingRequest(req.cx(), req.cz())) {
         this.ctx.diagnostics().incrementSkippedDuplicate();
         return true;
      } else {
         return false;
      }
   }

   private boolean sendQueueFull(PS state, TickSnapshot snapshot) {
      if (snapshot.maxSendQueueSize() > 0 && state.getSendQueueSize() >= snapshot.maxSendQueueSize()) {
         this.ctx.diagnostics().incrementQueueFull();
         return true;
      } else {
         return false;
      }
   }

   private ConcurrencyLimiter tryConcurrencyOrEnqueue(PS state, UUID playerUuid, IncomingRequest req, RequestType type, String dimension) {
      RateLimiterSet limiters = state.getRateLimiters();
      ConcurrencyLimiter limiter = limiters.forRequest(type, this.diskReadingAvailable);
      if (limiter.tryAcquire()) {
         return limiter;
      } else {
         int queueCap = limiters.syncRateLimit() + limiters.genRateLimit();
         if (state.getWaitingQueueSize() < queueCap) {
            state.getWaitingQueue().add(new AbstractPlayerRequestState.QueuedRequest(req, type, dimension));
            this.ctx.diagnostics().incrementQueued();
         } else {
            this.ctx.sendActions().add(new SendAction.RateLimited(playerUuid, req.requestId()));
            this.ctx.diagnostics().incrementRateLimited(type);
            if (VSSLogger.isDebugEnabled()) {
               VSSLogger.debug(
                  "Rate-limited "
                     + playerUuid
                     + " ("
                     + type
                     + "): queue full at "
                     + queueCap
                     + " for chunk ["
                     + req.cx()
                     + ", "
                     + req.cz()
                     + "] in "
                     + dimension
               );
            }
         }

         return null;
      }
   }

   /**
    * Routes a request whose chunk the server thread found loaded this tick.
    *
    * <p>Unless the cache already has them, the bytes do not exist yet: the server thread only probed for
    * presence, and serializing a column is the single most expensive thing this mod does on the main thread,
    * so it is deferred until here — after duplicate, send-queue and timestamp checks have had their say. Most
    * probed columns never reach this point, which is the entire win.
    *
    * <p>The deferred path deliberately acquires {@code syncOnLoad()} and registers the same pending/dedup
    * bookkeeping the disk path uses, because the serialized result is delivered into the same per-player
    * result queue. That queue's drain releases {@code syncOnLoad()} unconditionally for every result, so
    * acquiring anything else here would leak a permit per column and eventually starve the player to zero
    * throughput. The cache-hit path takes no permit at all, because no result is coming to release one.
    *
    * <p>Returns false when the permit is unavailable, letting the caller fall through to the normal
    * concurrency/waiting-queue back-pressure exactly as if the chunk had not been loaded.
    */
   private boolean resolvedFromLiveColumn(
      PS state,
      UUID playerUuid,
      IncomingRequest req,
      long packed,
      LongSet loadedPositions,
      String dimension,
      IncomingRequestRouter.LiveColumnRouter liveColumnRouter,
      IncomingRequestRouter.ColumnPayloadEnqueuer<PS> payloadEnqueuer,
      long cycleNow
   ) {
      if (!loadedPositions.contains(packed)) {
         return false;
      }

      // Cache hit: the bytes already exist, so serve synchronously and skip the round trip entirely — no
      // permit, no pending entry, nothing to release, and none of the ~2-tick deferral.
      byte[] cached = this.ctx.bytesCache().get(dimension, packed);
      if (cached != null) {
         payloadEnqueuer.enqueue(state, req.cx(), req.cz(), dimension, req.requestId(), cycleNow, this.ctx.sequence().next(),
            cached, cached.length + VSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES);
         this.timestampCache.put(dimension, packed, cycleNow, cycleNow);
         state.markDiskReadDone(req.cx(), req.cz());
         this.ctx.diagnostics().incrementInMemory();
         return true;
      }

      if (!state.getRateLimiters().syncOnLoad().tryAcquire()) {
         return false;
      } else {
         long order = this.ctx.sequence().next();
         state.addPendingRequest(new PendingRequest(req.requestId(), req.cx(), req.cz(), RequestType.SYNC));
         // Attached: another player already asked for this column and will get the bytes dispatched to both.
         if (!this.dedupTracker.tryAttachOrCreate(packed, dimension, playerUuid, req.requestId(), order)) {
            liveColumnRouter.requestLiveSerialize(playerUuid, req.requestId(), dimension, req.cx(), req.cz(), order);
         }

         this.ctx.diagnostics().incrementInMemory();
         return true;
      }
   }

   private boolean resolvedFromTimestamp(PS state, UUID playerUuid, IncomingRequest req, long packed, String dimension) {
      if (req.clientTimestamp() <= 0L) {
         return false;
      } else {
         long cachedTs = this.timestampCache.get(dimension, packed);
         if (cachedTs > 0L && cachedTs <= req.clientTimestamp()) {
            state.markDiskReadDone(req.cx(), req.cz());
            this.ctx.sendActions().add(new SendAction.ColumnUpToDate(playerUuid, req.requestId()));
            this.ctx.diagnostics().incrementUpToDate();
            return true;
         } else {
            return false;
         }
      }
   }

   private void submitToDiskOrGeneration(
      PS state,
      UUID playerUuid,
      IncomingRequest req,
      long packed,
      String dimension,
      RequestType type,
      ConcurrencyLimiter limiter,
      IncomingRequestRouter.DiskReadSubmitter diskReadSubmitter
   ) {
      long order = this.ctx.sequence().next();
      if (type == RequestType.SYNC || this.diskReadingAvailable) {
         state.addPendingRequest(new PendingRequest(req.requestId(), req.cx(), req.cz(), type));
         boolean attached = this.dedupTracker.tryAttachOrCreate(packed, dimension, playerUuid, req.requestId(), order);
         if (!attached) {
            diskReadSubmitter.submit(playerUuid, req.requestId(), dimension, req.cx(), req.cz(), order);
         }

         this.ctx.diagnostics().incrementDiskQueued();
      } else if (type == RequestType.GENERATION && this.generationAvailable) {
         state.addPendingRequest(new PendingRequest(req.requestId(), req.cx(), req.cz(), type));
         this.ctx.generationTicketRequests().add(new OffThreadProcessor.GenerationTicketRequest(playerUuid, req.requestId(), req.cx(), req.cz(), order));
      } else {
         this.ctx.sendActions().add(new SendAction.ColumnNotGenerated(playerUuid, req.requestId()));
         limiter.release();
      }
   }

   @FunctionalInterface
   interface DiskReadSubmitter {
      void submit(UUID playerUuid, int requestId, String dimension, int cx, int cz, long submissionOrder);
   }

   /**
    * Asks the server thread to serialize a live chunk and publish the bytes into the player's disk-result
    * queue. Called from the processing thread; the work happens on the next tick.
    */
   @FunctionalInterface
   interface LiveColumnRouter {
      void requestLiveSerialize(UUID playerUuid, int requestId, String dimension, int cx, int cz, long submissionOrder);
   }

   /** Queues an already-serialized column straight onto a player's send queue (the cache-hit path). */
   @FunctionalInterface
   interface ColumnPayloadEnqueuer<PS> {
      void enqueue(PS state, int cx, int cz, String dimension, int requestId, long columnTimestamp, long submissionOrder,
         byte[] sectionBytes, int estimatedBytes);
   }
}
