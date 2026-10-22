package com.golem.boxy.seedlod.fill;

import com.golem.boxy.seedlod.column.ColumnRuns;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Writes seed columns into a Voxy world. Full detail goes through Voxy's own section update, exactly like
 * ingested chunks; distant detail is written straight into Voxy's coarser levels (1 to 4), then mipped up to
 * level 4 with Voxy's mipper, and announced to parent sections so Voxy's traversal finds it.
 *
 * <p>Voxy section geometry: a level-L section holds 32^3 voxels of 2^L blocks each, indexed
 * {@code y << 10 | z << 5 | x}. A level-L section's parent bit {@code x&1 | (z&1)<<1 | (y&1)<<2} marks it
 * non-empty. One instance per worker thread and engine.
 */
public final class LodWriter {
   private static final VarHandle CHILDREN = childrenHandle();
   private static final int CHANGE_BLOCKS = 1;
   private static final int CHANGE_CHILDREN = 2;
   private static final int ALL_NEIGHBOURS = 63;
   private final WorldEngine engine;
   private final Mapper mapper;
   private final long openAir;
   /** Light given to every non-air voxel; see {@link #voxelOf}. */
   private final byte blockLight;
   private final Reference2IntOpenHashMap<BlockState> blockIds = new Reference2IntOpenHashMap<>();
   private final Reference2IntOpenHashMap<Holder<Biome>> biomeIds = new Reference2IntOpenHashMap<>();
   private final VoxelizedSection staging = VoxelizedSection.createEmpty();
   private final Long2ObjectOpenHashMap<WorldSection> held = new Long2ObjectOpenHashMap<>();

   public LodWriter(WorldEngine engine, int airLight) {
      this.engine = engine;
      this.mapper = engine.getMapper();
      this.openAir = Mapper.airWithLight(airLight);
      this.blockLight = (byte)airLight;
      blockIds.defaultReturnValue(-1);
      biomeIds.defaultReturnValue(-1);
   }

   private static VarHandle childrenHandle() {
      try {
         return MethodHandles.privateLookupIn(WorldSection.class, MethodHandles.lookup())
            .findVarHandle(WorldSection.class, "nonEmptyChildren", byte.class);
      } catch (ReflectiveOperationException e) {
         throw new ExceptionInInitializerError(e);
      }
   }

   // ---- level 0 ----

   /**
    * Writes one chunk at full detail. {@code columns} is indexed {@code z << 4 | x}; {@code minY}/{@code maxY}
    * bound the dimension. Open sky above the terrain is written too, as Voxy's own ingest writes it: faces take
    * their light from the voxel in front of them, and Voxy averages air light when it mips, so sky left unwritten
    * reads as pitch dark at every coarser level. Only sections identical to "never written" are skipped.
    */
   public void writeChunk(int chunkX, int chunkZ, ColumnRuns[] columns, int minY, int maxY) {
      long[] voxels = staging.section;
      int highest = minY;
      for (ColumnRuns column : columns) {
         if (column.count() > 0) {
            highest = Math.max(highest, column.top(column.count() - 1));
         }
      }
      for (int sectionY = minY >> 4; sectionY <= (maxY - 1) >> 4; sectionY++) {
         int baseY = sectionY << 4;
         if (baseY >= highest) {
            // Wholly above every column: open sky, the same voxels the general path would produce.
            if (openAir == 0L) {
               break;
            }
            Arrays.fill(voxels, openAir);
            staging.lvl0NonAirCount = 0;
            WorldUpdater.insertUpdate(engine, staging.setPosition(chunkX, sectionY, chunkZ));
            continue;
         }
         int nonAir = 0;
         boolean uniform = true;
         long first = 0L;
         for (int index = 0; index < 256; index++) {
            ColumnRuns column = columns[index];
            int x = index & 15;
            int z = index >> 4;
            int run = 0;
            for (int dy = 0; dy < 16; dy++) {
               int y = baseY + dy;
               while (run < column.count() && column.top(run) <= y) {
                  run++;
               }
               long voxel = run < column.count() && y >= column.floor() ? voxelOf(column, run) : openAir;
               voxels[dy << 8 | z << 4 | x] = voxel;
               if (!Mapper.isAir(voxel)) {
                  nonAir++;
               }
               if (index == 0 && dy == 0) {
                  first = voxel;
               } else if (voxel != first) {
                  uniform = false;
               }
            }
         }
         if (nonAir == 0 && uniform && first == 0L) {
            continue;
         }
         if (uniform) {
            Arrays.fill(voxels, 4096, voxels.length, first);
         } else {
            mipStaging();
         }
         staging.lvl0NonAirCount = nonAir;
         WorldUpdater.insertUpdate(engine, staging.setPosition(chunkX, sectionY, chunkZ));
      }
   }

   /** Fills levels 1..4 of the staging section from level 0 with Voxy's mipper. */
   private void mipStaging() {
      long[] v = staging.section;
      int from = 0;
      int to = 4096;
      for (int size = 8; size >= 1; size >>= 1) {
         int bits = Integer.numberOfTrailingZeros(size);
         int childBits = bits + 1;
         int i = to;
         for (int y = 0; y < size; y++) {
            for (int z = 0; z < size; z++) {
               for (int x = 0; x < size; x++) {
                  int cx = x << 1;
                  int cy = y << 1;
                  int cz = z << 1;
                  v[i++] = Mipper.mip(
                     v[from + index(cx, cy, cz, childBits)], v[from + index(cx + 1, cy, cz, childBits)],
                     v[from + index(cx, cy, cz + 1, childBits)], v[from + index(cx + 1, cy, cz + 1, childBits)],
                     v[from + index(cx, cy + 1, cz, childBits)], v[from + index(cx + 1, cy + 1, cz, childBits)],
                     v[from + index(cx, cy + 1, cz + 1, childBits)], v[from + index(cx + 1, cy + 1, cz + 1, childBits)],
                     mapper);
               }
            }
         }
         from = to;
         to = i;
      }
   }

   private static int index(int x, int y, int z, int bits) {
      return y << (bits << 1) | z << bits | x;
   }

   // ---- coarse levels ----

   /** Which chunks of a tile to leave alone (real terrain, or terrain the client is about to load). */
   public interface Mask {
      boolean skipsChunk(int chunkX, int chunkZ);
   }

   /**
    * Writes a square of {@code across x across} grid columns at {@code level} (each 2^level blocks wide),
    * starting at block ({@code originX}, {@code originZ}), then mips it up to level 4. Columns are indexed
    * {@code gz * across + gx}.
    */
   public void writeCoarse(int level, int originX, int originZ, int across, ColumnRuns[] grid, int minY, int maxY, Mask mask) {
      try {
         int voxelX0 = originX >> level;
         int voxelZ0 = originZ >> level;
         int voxelY0 = Math.floorDiv(minY, 1 << level);
         int voxelY1 = Math.floorDiv(maxY - 1, 1 << level);
         writeLevel(level, voxelX0, voxelZ0, across, voxelY0, voxelY1, grid, mask);
         for (int up = level + 1; up <= WorldEngine.MAX_LOD_LAYER; up++) {
            int shift = up - level;
            mipLevel(up, voxelX0 >> shift, voxelZ0 >> shift, Math.max(1, across >> shift), voxelY0 >> shift, voxelY1 >> shift, mask);
         }
      } finally {
         releaseAll();
      }
   }

   private void writeLevel(int level, int vx0, int vz0, int across, int vy0, int vy1, ColumnRuns[] grid, Mask mask) {
      int scale = 1 << level;
      for (int sy = vy0 >> 5; sy <= vy1 >> 5; sy++) {
         for (int sz = vz0 >> 5; sz <= (vz0 + across - 1) >> 5; sz++) {
            for (int sx = vx0 >> 5; sx <= (vx0 + across - 1) >> 5; sx++) {
               WorldSection section = hold(level, sx, sy, sz);
               long[] data = section._unsafeGetRawDataArray();
               boolean changed = false;
               boolean solid = false;
               for (int vz = Math.max(vz0, sz << 5); vz < Math.min(vz0 + across, (sz + 1) << 5); vz++) {
                  for (int vx = Math.max(vx0, sx << 5); vx < Math.min(vx0 + across, (sx + 1) << 5); vx++) {
                     if (mask.skipsChunk((vx * scale) >> 4, (vz * scale) >> 4)) {
                        continue;
                     }
                     ColumnRuns column = grid[(vz - vz0) * across + (vx - vx0)];
                     for (int vy = Math.max(vy0, sy << 5); vy <= Math.min(vy1, (sy << 5) + 31); vy++) {
                        long voxel = voxelOver(column, vy * scale, (vy + 1) * scale);
                        int at = WorldSection.getIndex(vx & 31, vy & 31, vz & 31);
                        if (data[at] != voxel) {
                           data[at] = voxel;
                           changed = true;
                        }
                        solid |= !Mapper.isAir(voxel);
                     }
                  }
               }
               finishSection(section, changed, solid);
            }
         }
      }
   }

   private void mipLevel(int level, int vx0, int vz0, int across, int vy0, int vy1, Mask mask) {
      int scale = 1 << level;
      for (int sy = vy0 >> 5; sy <= vy1 >> 5; sy++) {
         for (int sz = vz0 >> 5; sz <= (vz0 + across - 1) >> 5; sz++) {
            for (int sx = vx0 >> 5; sx <= (vx0 + across - 1) >> 5; sx++) {
               WorldSection section = hold(level, sx, sy, sz);
               long[] data = section._unsafeGetRawDataArray();
               boolean changed = false;
               boolean solid = false;
               for (int vz = Math.max(vz0, sz << 5); vz < Math.min(vz0 + across, (sz + 1) << 5); vz++) {
                  for (int vx = Math.max(vx0, sx << 5); vx < Math.min(vx0 + across, (sx + 1) << 5); vx++) {
                     if (mask.skipsChunk((vx * scale) >> 4, (vz * scale) >> 4)) {
                        continue;
                     }
                     for (int vy = Math.max(vy0, sy << 5); vy <= Math.min(vy1, (sy << 5) + 31); vy++) {
                        long voxel = mipFromChildren(level - 1, vx << 1, vy << 1, vz << 1);
                        int at = WorldSection.getIndex(vx & 31, vy & 31, vz & 31);
                        if (data[at] != voxel) {
                           data[at] = voxel;
                           changed = true;
                        }
                        solid |= !Mapper.isAir(voxel);
                     }
                  }
               }
               finishSection(section, changed, solid);
            }
         }
      }
   }

   private long mipFromChildren(int level, int x, int y, int z) {
      return Mipper.mip(
         child(level, x, y, z), child(level, x + 1, y, z), child(level, x, y, z + 1), child(level, x + 1, y, z + 1),
         child(level, x, y + 1, z), child(level, x + 1, y + 1, z), child(level, x, y + 1, z + 1), child(level, x + 1, y + 1, z + 1),
         mapper);
   }

   private long child(int level, int x, int y, int z) {
      return hold(level, x >> 5, y >> 5, z >> 5)._unsafeGetRawDataArray()[WorldSection.getIndex(x & 31, y & 31, z & 31)];
   }

   private void finishSection(WorldSection section, boolean changed, boolean solid) {
      if (changed) {
         engine.markDirty(section, CHANGE_BLOCKS, ALL_NEIGHBOURS);
      }
      if (solid) {
         announce(section);
      }
   }

   /** Marks the section non-empty in each ancestor, stopping at the first ancestor that already knew. */
   private void announce(WorldSection section) {
      int level = section.lvl;
      int x = section.x;
      int y = section.y;
      int z = section.z;
      while (level < WorldEngine.MAX_LOD_LAYER) {
         WorldSection parent = hold(level + 1, x >> 1, y >> 1, z >> 1);
         byte bit = (byte)(1 << WorldSection.getChildIndex(x, y, z));
         byte before = (byte)CHILDREN.getAndBitwiseOr(parent, bit);
         if ((before & bit) != 0) {
            return;
         }
         engine.markDirty(parent, CHANGE_CHILDREN, 0);
         if (before != 0) {
            return;
         }
         level++;
         x >>= 1;
         y >>= 1;
         z >>= 1;
      }
   }

   private WorldSection hold(int level, int x, int y, int z) {
      long key = WorldEngine.getWorldSectionId(level, x, y, z);
      WorldSection section = held.get(key);
      if (section == null) {
         section = engine.acquire(level, x, y, z);
         held.put(key, section);
      }
      return section;
   }

   private void releaseAll() {
      for (WorldSection section : held.values()) {
         section.release();
      }
      held.clear();
   }

   // ---- voxels ----

   /** The voxel covering blocks [bottom, top) of a column: its highest non-air block, else the air there. */
   private long voxelOver(ColumnRuns column, int bottom, int top) {
      long air = openAir;
      for (int run = column.count() - 1; run >= 0; run--) {
         int runBottom = column.bottom(run);
         if (runBottom >= top) {
            continue;
         }
         if (column.top(run) <= bottom) {
            break;
         }
         if (!column.state(run).isAir()) {
            return voxelOf(column, run);
         }
         air = Mapper.airWithLight(column.light(run));
      }
      return column.count() == 0 || bottom >= column.top(column.count() - 1) ? openAir : air;
   }

   private long voxelOf(ColumnRuns column, int run) {
      BlockState state = column.state(run);
      if (state.isAir()) {
         return Mapper.airWithLight(column.light(run));
      }
      int block = blockIds.getInt(state);
      if (block < 0) {
         block = mapper.getIdForBlockState(state);
         blockIds.put(state, block);
      }
      Holder<Biome> biomeHolder = column.biome(run);
      int biome = 0;
      if (biomeHolder != null) {
         biome = biomeIds.getInt(biomeHolder);
         if (biome < 0) {
            biome = mapper.getIdForBiome(biomeHolder);
            biomeIds.put(biomeHolder, biome);
         }
      }
      // Voxy ignores an opaque block's own light, but lights leaves, plants and snow layers from it; give them
      // the open light they would have (what Voxy's ingest reads for them), never the stored 0 of solid ground.
      return Mapper.composeMappingId(blockLight, block, biome);
   }
}
