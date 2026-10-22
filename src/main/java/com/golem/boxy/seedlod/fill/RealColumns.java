package com.golem.boxy.seedlod.fill;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Columns Boxy has ingested from a real world this session, per dimension. Real terrain always wins: the seed
 * fill never writes a marked column, and if one becomes real while the fill is writing it the fill reports it
 * so Boxy can fetch it again ({@link #onOverwritten}). Columns stored in earlier sessions are recognised from
 * Voxy itself ({@link VoxyPresence}).
 */
public final class RealColumns {
   private static final Map<ResourceKey<Level>, LongOpenHashSet> BY_DIMENSION = new ConcurrentHashMap<>();
   /** Receives (dimension, {@link ChunkPos#asLong}) for a real column the fill may have overwritten. */
   public static volatile BiConsumer<ResourceKey<Level>, Long> onOverwritten;

   private RealColumns() {
   }

   public static void mark(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
      LongOpenHashSet columns = BY_DIMENSION.computeIfAbsent(dimension, ignored -> new LongOpenHashSet());
      synchronized (columns) {
         columns.add(ChunkPos.asLong(chunkX, chunkZ));
      }
   }

   public static boolean isReal(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
      LongOpenHashSet columns = BY_DIMENSION.get(dimension);
      if (columns == null) {
         return false;
      }
      synchronized (columns) {
         return columns.contains(ChunkPos.asLong(chunkX, chunkZ));
      }
   }

   /** After writing: hands back any of the given chunks that turned real meanwhile. */
   static void recheck(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
      BiConsumer<ResourceKey<Level>, Long> listener = onOverwritten;
      if (listener != null && isReal(dimension, chunkX, chunkZ)) {
         listener.accept(dimension, ChunkPos.asLong(chunkX, chunkZ));
      }
   }

   public static void clear() {
      BY_DIMENSION.clear();
   }
}
