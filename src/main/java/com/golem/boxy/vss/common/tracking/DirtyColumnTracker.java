package com.golem.boxy.vss.common.tracking;

import com.golem.boxy.vss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Lock-free from any thread. Per dimension: insert-only region table (32x32 columns) of dirty bitsets.
 * Mark on an already-dirty column = reads only (no store, no cache-line bounce); drain = {@code getAndSet(0)} per word.
 */
public class DirtyColumnTracker {
   private final ConcurrentHashMap<String, Dimension> dimensions = new ConcurrentHashMap<>();

   // Fired on the first mark of a column per drain window; periodic broadcast alone leaves serialized-bytes cache stale.
   private volatile DirtyColumnTracker.ColumnInvalidationSink invalidationSink;

   public DirtyColumnTracker() {
   }

   /** Set once during startup, before any player can be marking columns dirty. */
   public void setInvalidationSink(DirtyColumnTracker.ColumnInvalidationSink sink) {
      this.invalidationSink = sink;
   }

   /** Hot callers cache the handle; it is valid for the life of this tracker. */
   public Dimension dimension(String dimension) {
      Dimension d = this.dimensions.get(dimension);
      return d != null ? d : this.dimensions.computeIfAbsent(dimension, k -> new Dimension(this, k));
   }

   public void markDirty(String dimension, int cx, int cz) {
      dimension(dimension).mark(cx, cz);
   }

   @FunctionalInterface
   public interface ColumnInvalidationSink {
      void onColumnDirty(String dimension, long packed);
   }

   /** Packed positions dirtied since the last drain, or null when none. Unordered. */
   public long[] drainDirty(String dimension) {
      Dimension d = this.dimensions.get(dimension);
      return d == null ? null : d.drain();
   }

   public static final class Dimension {
      private static final int WORDS = 16;
      private final DirtyColumnTracker tracker;
      private final String id;
      private final Object growLock = new Object();
      private volatile Table table = new Table(16);

      private Dimension(DirtyColumnTracker tracker, String id) {
         this.tracker = tracker;
         this.id = id;
      }

      public DirtyColumnTracker tracker() {
         return this.tracker;
      }

      public void mark(int cx, int cz) {
         Region region = region(cx >> 5, cz >> 5);
         int bit = (cz & 31) << 5 | (cx & 31);
         int word = bit >>> 6;
         long mask = 1L << bit;
         AtomicLongArray words = region.words;
         if ((words.get(word) & mask) != 0L) {
            return;
         }
         long previous = words.get(word);
         while ((previous & mask) == 0L && !words.compareAndSet(word, previous, previous | mask)) {
            previous = words.get(word);
         }
         if (!region.dirty) {
            region.dirty = true;
         }
         if ((previous & mask) == 0L) {
            ColumnInvalidationSink sink = this.tracker.invalidationSink;
            if (sink != null) {
               sink.onColumnDirty(this.id, PositionUtil.packPosition(cx, cz));
            }
         }
      }

      long[] drain() {
         Table t = this.table;
         LongArrayList out = null;
         for (Region region : t.values) {
            if (region == null || !region.dirty) {
               continue;
            }
            region.dirty = false;
            for (int w = 0; w < WORDS; w++) {
               long bits = region.words.getAndSet(w, 0L);
               while (bits != 0L) {
                  int bit = w << 6 | Long.numberOfTrailingZeros(bits);
                  bits &= bits - 1;
                  if (out == null) {
                     out = new LongArrayList();
                  }
                  out.add(PositionUtil.packPosition(region.rx << 5 | bit & 31, region.rz << 5 | bit >>> 5));
               }
            }
         }
         return out == null ? null : out.toLongArray();
      }

      private Region region(int rx, int rz) {
         Region r = this.table.get(rx, rz);
         return r != null ? r : insert(rx, rz);
      }

      private Region insert(int rx, int rz) {
         synchronized (this.growLock) {
            Table t = this.table;
            Region r = t.get(rx, rz);
            if (r != null) {
               return r;
            }
            r = new Region(rx, rz);
            if (t.size * 2 >= t.values.length) {
               Table next = t.grown();
               next.put(r);
               this.table = next;
            } else {
               t.put(r);
            }
            return r;
         }
      }
   }

   private static final class Region {
      final int rx;
      final int rz;
      final AtomicLongArray words = new AtomicLongArray(Dimension.WORDS);
      volatile boolean dirty;

      Region(int rx, int rz) {
         this.rx = rx;
         this.rz = rz;
      }
   }

   /** Insert-only under growLock; entries never move or vanish, so unlocked probes see a miss or a final-field-safe Region. */
   private static final class Table {
      final Region[] values;
      int size;

      Table(int capacity) {
         this.values = new Region[capacity];
      }

      static int slot(int rx, int rz, int length) {
         long h = PositionUtil.packPosition(rx, rz) * 0x9E3779B97F4A7C15L;
         return (int) (h >>> 32) & (length - 1);
      }

      Region get(int rx, int rz) {
         Region[] v = this.values;
         for (int i = slot(rx, rz, v.length); ; i = (i + 1) & (v.length - 1)) {
            Region r = v[i];
            if (r == null || r.rx == rx && r.rz == rz) {
               return r;
            }
         }
      }

      void put(Region r) {
         Region[] v = this.values;
         int i = slot(r.rx, r.rz, v.length);
         while (v[i] != null) {
            i = (i + 1) & (v.length - 1);
         }
         v[i] = r;
         this.size++;
      }

      Table grown() {
         Table t = new Table(this.values.length * 2);
         for (Region r : this.values) {
            if (r != null) {
               t.put(r);
            }
         }
         return t;
      }
   }
}
