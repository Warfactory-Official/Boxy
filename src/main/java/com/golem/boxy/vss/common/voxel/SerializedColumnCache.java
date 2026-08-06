package com.golem.boxy.vss.common.voxel;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An LRU cache of serialized column bytes, keyed by dimension and packed chunk position.
 *
 * <p>Its biggest win is the disk path: without it, every request for an already-generated column re-reads the
 * region file, re-parses the NBT and re-runs the full PalettedContainer codec, even when a different player
 * asked for the identical column a moment ago. {@code DedupTracker} already collapses <em>concurrent</em>
 * duplicates; this adds temporal dedup, which is what a server with a clustered spawn or base region actually
 * needs. On the live path a hit also removes the ~2-tick deferral, since the bytes are already in hand.
 *
 * <p><b>Values are immutable by convention.</b> The same array is handed to every player who wants that
 * column — the dedup dispatch already relies on this, and payload encoding only ever reads it. Never mutate a
 * {@code byte[]} that has been through {@link #put}.
 *
 * <p>Staleness is bounded by the dirty-broadcast interval. See {@code DirtyColumnTracker}'s invalidation sink
 * for the per-edit path and {@code DirtyColumnBroadcaster} for the periodic sweep that closes the race where
 * an in-flight serialization stores bytes just after an edit invalidated them.
 */
public class SerializedColumnCache {
   private final Map<String, SerializedColumnCache.DimensionSlice> slices = new ConcurrentHashMap<>();
   private final long maxBytesPerDimension;

   public SerializedColumnCache(long maxBytesPerDimension) {
      this.maxBytesPerDimension = maxBytesPerDimension;
   }

   public static long mbToBytes(int mb) {
      return (long)mb * 1024L * 1024L;
   }

   public boolean isEnabled() {
      return this.maxBytesPerDimension > 0L;
   }

   public byte[] get(String dimension, long packed) {
      if (!this.isEnabled()) {
         return null;
      }

      SerializedColumnCache.DimensionSlice slice = this.slices.get(dimension);
      if (slice == null) {
         return null;
      }

      synchronized (slice) {
         return slice.entries.getAndMoveToFirst(packed);
      }
   }

   /** {@code bytes} must never be mutated afterwards; a null value means "nothing to send" and is cacheable. */
   public void put(String dimension, long packed, byte[] bytes) {
      if (!this.isEnabled() || bytes == null) {
         return;
      }

      // One pathological column must not be able to flush everything else out.
      if ((long)bytes.length > this.maxBytesPerDimension / 8L) {
         return;
      }

      SerializedColumnCache.DimensionSlice slice = this.slices.computeIfAbsent(dimension, k -> new SerializedColumnCache.DimensionSlice());
      synchronized (slice) {
         byte[] replaced = slice.entries.putAndMoveToFirst(packed, bytes);
         if (replaced != null) {
            slice.totalBytes -= replaced.length;
         }

         slice.totalBytes += bytes.length;
         this.evictSlice(slice);
      }
   }

   public void invalidate(String dimension, long packed) {
      SerializedColumnCache.DimensionSlice slice = this.slices.get(dimension);
      if (slice != null) {
         synchronized (slice) {
            byte[] removed = slice.entries.remove(packed);
            if (removed != null) {
               slice.totalBytes -= removed.length;
            }
         }
      }
   }

   public void invalidate(String dimension, long[] positions) {
      SerializedColumnCache.DimensionSlice slice = this.slices.get(dimension);
      if (slice != null) {
         synchronized (slice) {
            for (long packed : positions) {
               byte[] removed = slice.entries.remove(packed);
               if (removed != null) {
                  slice.totalBytes -= removed.length;
               }
            }
         }
      }
   }

   /** Re-applies the byte budget, so lowering it in the config takes effect without a restart. */
   public int evictIfOversized() {
      int evicted = 0;

      for (SerializedColumnCache.DimensionSlice slice : this.slices.values()) {
         synchronized (slice) {
            evicted += this.evictSlice(slice);
         }
      }

      return evicted;
   }

   /** Caller must hold the slice monitor. */
   private int evictSlice(SerializedColumnCache.DimensionSlice slice) {
      int evicted = 0;

      while (slice.totalBytes > this.maxBytesPerDimension && !slice.entries.isEmpty()) {
         byte[] removed = slice.entries.removeLast();
         if (removed != null) {
            slice.totalBytes -= removed.length;
         }

         evicted++;
      }

      return evicted;
   }

   /**
    * Access-ordered so LRU eviction is O(1) via getAndMoveToFirst/removeLast, with no per-entry node object —
    * matching the fastutil style used by the sibling caches.
    */
   private static final class DimensionSlice {
      final Long2ObjectLinkedOpenHashMap<byte[]> entries = new Long2ObjectLinkedOpenHashMap<>();
      long totalBytes;
   }
}
