package com.golem.boxy.seedlod.decor;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.world.level.ChunkPos;

/**
 * Each chunk's decoration, made once and shared by every fill worker: neighbouring tiles need the same ring
 * chunks, and a tile rebuilt finer as the player approaches needs all of them again. Bounded by the number of
 * stored blocks, least recently used first out; an evicted chunk is simply decorated again when next needed.
 */
public final class DecorationStore {
   /** About 12 bytes a block: some 50 MB. */
   private static final long BLOCK_BUDGET = 4_000_000L;
   private final LinkedHashMap<Long, ChunkDecoration> done = new LinkedHashMap<>(1024, 0.75F, true);
   private final ConcurrentHashMap<Long, CompletableFuture<ChunkDecoration>> making = new ConcurrentHashMap<>();
   private long blocks;
   private long made;
   private long reused;

   /** A finished chunk, or null; does not count as a use. */
   public synchronized ChunkDecoration peek(int chunkX, int chunkZ) {
      return done.get(ChunkPos.asLong(chunkX, chunkZ));
   }

   /** The chunk's decoration, made by {@code maker} on this thread unless done or being made elsewhere. */
   public ChunkDecoration get(int chunkX, int chunkZ, Supplier<ChunkDecoration> maker) {
      long key = ChunkPos.asLong(chunkX, chunkZ);
      synchronized (this) {
         ChunkDecoration known = done.get(key);
         if (known != null) {
            reused++;
            return known;
         }
      }
      CompletableFuture<ChunkDecoration> mine = new CompletableFuture<>();
      CompletableFuture<ChunkDecoration> theirs = making.putIfAbsent(key, mine);
      if (theirs != null) {
         return theirs.join();
      }
      try {
         ChunkDecoration result = maker.get();
         store(key, result);
         mine.complete(result);
         return result;
      } catch (RuntimeException | Error failure) {
         mine.completeExceptionally(failure);
         throw failure;
      } finally {
         making.remove(key, mine);
      }
   }

   private synchronized void store(long key, ChunkDecoration decoration) {
      ChunkDecoration old = done.put(key, decoration);
      blocks += decoration.size() - (old == null ? 0 : old.size());
      made++;
      Iterator<Map.Entry<Long, ChunkDecoration>> oldest = done.entrySet().iterator();
      while (blocks > BLOCK_BUDGET && oldest.hasNext()) {
         Map.Entry<Long, ChunkDecoration> eldest = oldest.next();
         if (eldest.getKey() == key) {
            continue;
         }
         blocks -= eldest.getValue().size();
         oldest.remove();
      }
   }

   public synchronized String describe() {
      return String.format(Locale.ROOT, "%d chunks decorated, %d reused, %d held (%,d blocks)", made, reused, done.size(), blocks);
   }
}
