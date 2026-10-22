package com.golem.boxy.seedlod.fill;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

/**
 * Which chunks of a tile already hold full-detail terrain in Voxy (from Boxy, Voxy's own ingest, an import, or
 * an earlier session's seed fill). Walks Voxy's hierarchy top down so empty areas cost a few lookups: a tile is
 * exactly one level-2 section wide, and only level-1 sections that report non-empty children are opened down to
 * level 0. Coarse seed writes never set those children, so they are not mistaken for full detail.
 */
final class VoxyPresence {
   private VoxyPresence() {
   }

   /**
    * Bit {@code dz * 8 + dx} set for each chunk (tile-relative) with any non-air level-0 voxel.
    * {@code tileX}/{@code tileZ} are in 128-block units.
    */
   static long chunksWithDetail(WorldEngine engine, int tileX, int tileZ, int minY, int maxY) {
      long found = 0L;
      for (int sy2 = minY >> 7; sy2 <= (maxY - 1) >> 7; sy2++) {
         WorldSection top = engine.acquireIfExists(2, tileX, sy2, tileZ);
         if (top == null) {
            continue;
         }
         byte level1Children;
         try {
            level1Children = top.getNonEmptyChildren();
         } finally {
            top.release();
         }
         for (int child = 0; child < 8; child++) {
            if ((level1Children & 1 << child) == 0) {
               continue;
            }
            int x1 = tileX << 1 | child & 1;
            int z1 = tileZ << 1 | child >> 1 & 1;
            int y1 = sy2 << 1 | child >> 2 & 1;
            found |= fromLevel1(engine, x1, y1, z1, tileX, tileZ);
         }
      }
      return found;
   }

   private static long fromLevel1(WorldEngine engine, int x1, int y1, int z1, int tileX, int tileZ) {
      WorldSection middle = engine.acquireIfExists(1, x1, y1, z1);
      if (middle == null) {
         return 0L;
      }
      byte level0Children;
      try {
         level0Children = middle.getNonEmptyChildren();
      } finally {
         middle.release();
      }
      long found = 0L;
      for (int child = 0; child < 8; child++) {
         if ((level0Children & 1 << child) == 0) {
            continue;
         }
         WorldSection base = engine.acquireIfExists(0, x1 << 1 | child & 1, y1 << 1 | child >> 2 & 1, z1 << 1 | child >> 1 & 1);
         if (base == null) {
            continue;
         }
         try {
            found |= chunksIn(base, tileX, tileZ);
         } finally {
            base.release();
         }
      }
      return found;
   }

   /** A level-0 section spans 2x2 chunks; report the ones with any non-air voxel. */
   private static long chunksIn(WorldSection section, int tileX, int tileZ) {
      long[] voxels = section._unsafeGetRawDataArray();
      long found = 0L;
      for (int quarter = 0; quarter < 4; quarter++) {
         int qx = quarter & 1;
         int qz = quarter >> 1;
         int chunkX = (section.x << 1 | qx) - (tileX << 3);
         int chunkZ = (section.z << 1 | qz) - (tileZ << 3);
         long bit = 1L << (chunkZ * 8 + chunkX);
         if (hasBlock(voxels, qx << 4, qz << 4)) {
            found |= bit;
         }
      }
      return found;
   }

   private static boolean hasBlock(long[] voxels, int fromX, int fromZ) {
      for (int y = 0; y < 32; y++) {
         for (int z = fromZ; z < fromZ + 16; z++) {
            int row = WorldSection.getIndex(0, y, z);
            for (int x = fromX; x < fromX + 16; x++) {
               if (!Mapper.isAir(voxels[row + x])) {
                  return true;
               }
            }
         }
      }
      return false;
   }
}
