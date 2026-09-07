package com.golem.boxy.vss.common.tracking;

import com.golem.boxy.vss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;

public class DirtyColumnTracker {
   private final Map<String, LongOpenHashSet> dirtyColumns = new HashMap<>();

   // Notified the first time a column is marked dirty in each drain window. This is the precise invalidation
   // edge for the serialized-bytes cache: the periodic broadcast alone would leave bytes up to
   // dirtyBroadcastIntervalSeconds stale for a client that re-requests for an unrelated reason (a fresh join,
   // or LOD re-entry after client-side eviction) rather than because it saw the dirty broadcast.
   private volatile DirtyColumnTracker.ColumnInvalidationSink invalidationSink;

   public DirtyColumnTracker() {
   }

   /** Set once during startup, before any player can be marking columns dirty. */
   public void setInvalidationSink(DirtyColumnTracker.ColumnInvalidationSink sink) {
      this.invalidationSink = sink;
   }

   public void markDirty(String dimension, int cx, int cz) {
      long packed = PositionUtil.packPosition(cx, cz);
      boolean newlyDirty;
      synchronized (this) {
         newlyDirty = this.dirtyColumns.computeIfAbsent(dimension, k -> new LongOpenHashSet()).add(packed);
      }

      // Coalesce interleaved bulk-edit and block-entity notifications, not only consecutive marks.
      DirtyColumnTracker.ColumnInvalidationSink sink = this.invalidationSink;
      if (newlyDirty && sink != null) {
         sink.onColumnDirty(dimension, packed);
      }
   }

   @FunctionalInterface
   public interface ColumnInvalidationSink {
      void onColumnDirty(String dimension, long packed);
   }

   public synchronized long[] drainDirty(String dimension) {
      LongOpenHashSet set = this.dirtyColumns.get(dimension);
      if (set != null && !set.isEmpty()) {
         long[] result = set.toLongArray();
         set.clear();
         return result;
      } else {
         return null;
      }
   }
}
