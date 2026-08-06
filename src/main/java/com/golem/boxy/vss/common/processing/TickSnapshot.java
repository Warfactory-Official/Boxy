package com.golem.boxy.vss.common.processing;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TickSnapshot(
   Map<UUID, TickSnapshot.PlayerTickData> players,
   Map<UUID, LongSet> loadedPositions,
   List<TickSnapshot.GenerationReadyData> generationReady,
   List<UUID> removedPlayers,
   int maxSendQueueSize,
   boolean shutdown
) {
   public static TickSnapshot shutdownSentinel() {
      return new TickSnapshot(Map.of(), Map.of(), List.of(), List.of(), 0, true);
   }

   public record GenerationReadyData(UUID playerUuid, int requestId, LoadedColumnData columnData, long columnTimestamp, long submissionOrder) {
   }

   /**
    * @param probedCount how many entries of this player's {@code incomingRequests} queue the server thread
    *        walked while building the snapshot. The router must not poll past that prefix: beyond it there is
    *        no loaded/not-loaded information, and routing a loaded-and-recently-edited chunk to the disk
    *        reader would serve autosave-stale bytes. Entries left behind stay queued and are probed next tick.
    *
    *        <p>This relies on {@code incomingRequests} being a ConcurrentLinkedQueue whose only consumer is
    *        the processing thread: the netty handler appends to the tail, the server thread walks a prefix
    *        non-destructively, so the prefix walked in tick N is exactly the prefix polled in cycle N.
    */
   public record PlayerTickData(String dimension, boolean dimensionChanged, int probedCount) {
   }
}
