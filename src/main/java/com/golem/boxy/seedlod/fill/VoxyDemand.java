package com.golem.boxy.seedlod.fill;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.world.level.ChunkPos;

/**
 * Places where Voxy wanted more detail than the seed fill has written: it tried to split a coarse seed node
 * that has no children ({@code MixinVoxySeedLeafRequest}). Recorded per tile as the level Voxy needs there,
 * drained by the planner ahead of distance-based work.
 */
public final class VoxyDemand {
   private static final int MOST = 4096;
   private static final Long2ByteOpenHashMap WANTED = new Long2ByteOpenHashMap();

   private VoxyDemand() {
   }

   /** Whether seed LODs want to hear about childless nodes (and Voxy should stop warning about them). */
   public static boolean listening() {
      return SeedFiller.running();
   }

   /** Voxy wanted the children of the level-L node at {@code position}, i.e. level L - 1 there. */
   public static void wanted(long position) {
      int level = WorldEngine.getLevel(position);
      if (level <= 0) {
         return;
      }
      int blocks = 32 << level;
      int tileX = Math.floorDiv(WorldEngine.getX(position) * blocks, TileBuilder.TILE_BLOCKS);
      int tileZ = Math.floorDiv(WorldEngine.getZ(position) * blocks, TileBuilder.TILE_BLOCKS);
      int tilesAcross = Math.max(1, blocks / TileBuilder.TILE_BLOCKS);
      synchronized (WANTED) {
         if (WANTED.size() >= MOST) {
            return;
         }
         for (int dz = 0; dz < tilesAcross; dz++) {
            for (int dx = 0; dx < tilesAcross; dx++) {
               long tile = ChunkPos.asLong(tileX + dx, tileZ + dz);
               byte had = WANTED.getOrDefault(tile, (byte)5);
               WANTED.put(tile, (byte)Math.min(had, level - 1));
            }
         }
      }
   }

   /** Takes everything recorded since the last call: tile to level. */
   static Long2ByteOpenHashMap drain() {
      synchronized (WANTED) {
         Long2ByteOpenHashMap taken = new Long2ByteOpenHashMap(WANTED);
         WANTED.clear();
         return taken;
      }
   }
}
