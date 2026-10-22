package com.golem.boxy.seedlod.decor;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The blocks one chunk's decoration pass placed (structure pieces and features, including what spilled into
 * neighbouring chunks), packed as {@link BlockPos#asLong} positions with their states.
 */
public record ChunkDecoration(long[] positions, BlockState[] states) {
   static final ChunkDecoration NONE = new ChunkDecoration(new long[0], new BlockState[0]);

   static ChunkDecoration of(Long2ObjectLinkedOpenHashMap<BlockState> written) {
      if (written == null || written.isEmpty()) {
         return NONE;
      }
      long[] positions = new long[written.size()];
      BlockState[] states = new BlockState[written.size()];
      int i = 0;
      for (Long2ObjectMap.Entry<BlockState> entry : written.long2ObjectEntrySet()) {
         positions[i] = entry.getLongKey();
         states[i] = entry.getValue();
         i++;
      }
      return new ChunkDecoration(positions, states);
   }

   public int size() {
      return positions.length;
   }

   /** Adds the blocks inside the block rectangle [minX, maxX) x [minZ, maxZ) to {@code level}. */
   public void addTo(CaptureLevel level, int minX, int minZ, int maxX, int maxZ) {
      for (int i = 0; i < positions.length; i++) {
         long position = positions[i];
         int x = BlockPos.getX(position);
         int z = BlockPos.getZ(position);
         if (x >= minX && x < maxX && z >= minZ && z < maxZ) {
            level.addPlaced(x, BlockPos.getY(position), z, states[i]);
         }
      }
   }
}
