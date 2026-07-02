package com.golem.boxy.vss.common.tracking;

import com.golem.boxy.vss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;

public class DirtyColumnTracker {
   private final Map<String, LongOpenHashSet> dirtyColumns = new HashMap<>();

   // Last column marked since the last drain. markDirty sits on ServerLevel.sendBlockUpdated — every block
   // change server-wide (redstone, pistons, fluids, player edits) — and consecutive changes overwhelmingly
   // hit the same column, so this memo lets repeat marks skip the monitor entirely. All callers (the two
   // dirty mixins and the broadcaster's drain) run on the server thread, so plain fields suffice; if an
   // off-thread caller ever appears, a stale read here only costs one redundant synchronized add.
   private String lastDimension;
   private long lastPacked = Long.MIN_VALUE; // sentinel: unreachable for real chunk coords

   public DirtyColumnTracker() {
   }

   public void markDirty(String dimension, int cx, int cz) {
      long packed = PositionUtil.packPosition(cx, cz);
      if (packed == this.lastPacked && dimension.equals(this.lastDimension)) {
         return; // same column as the last mark since the last drain — already in the set
      }
      synchronized (this) {
         this.dirtyColumns.computeIfAbsent(dimension, k -> new LongOpenHashSet()).add(packed);
         this.lastPacked = packed;
         this.lastDimension = dimension;
      }
   }

   public synchronized long[] drainDirty(String dimension) {
      // Invalidate the memo so a column edited again after this drain gets re-marked.
      this.lastPacked = Long.MIN_VALUE;
      this.lastDimension = null;
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
