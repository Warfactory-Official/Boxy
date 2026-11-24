package com.golem.boxy.seedlod.fill;

import com.golem.boxy.seedlod.column.BiomeLookup;
import com.golem.boxy.seedlod.column.ColumnRuns;
import com.golem.boxy.seedlod.column.SurfacePainter;
import com.golem.boxy.seedlod.column.TerrainCache;
import com.golem.boxy.seedlod.column.TerrainSampler;
import com.golem.boxy.seedlod.decor.CaptureLevel;
import com.golem.boxy.seedlod.decor.ChunkDecoration;
import com.golem.boxy.seedlod.decor.DecorationStore;
import com.golem.boxy.seedlod.decor.Decorator;
import com.golem.boxy.seedlod.world.SeedWorld;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Builds one tile (8 x 8 chunks, exactly one level-2 Voxy section wide) at one detail level and writes it. A
 * worker's whole toolkit lives here so nothing is shared between threads but the world and its plans.
 *
 * <ul>
 *   <li>Level 0: every column, painted, then decorated (structures and features, including those spilling in
 *   from the ring of chunks around the tile), written chunk by chunk through Voxy's own update path.</li>
 *   <li>Levels 1-2: the same full-resolution terrain and decoration, reduced to one column per voxel; the
 *   tallest placed object in a voxel's footprint is kept so forests keep their canopy.</li>
 *   <li>Levels 3-4: one sparse sample per voxel, without decoration, painted against its grid neighbours.</li>
 * </ul>
 */
final class TileBuilder {
   static final int TILE_CHUNKS = 8;
   static final int TILE_BLOCKS = TILE_CHUNKS * 16;
   private static final int DECORATED_UP_TO = 2;
   private final SeedWorld world;
   private final TerrainSampler sampler;
   private final SurfacePainter painter;
   private final TerrainCache terrain;
   private final CaptureLevel capture;
   private final Decorator decorator;
   private final LodWriter writer;
   private final DecorationStore decorations;
   private final int minY;
   private final int maxY;
   private final ColumnRuns[] chunkColumns = new ColumnRuns[256];
   /** Without a writer (benchmarks), a digest of everything that would have been written. */
   private long digest;

   /** {@code engine} may be null for a builder that only probes ({@link #probeChunk}). */
   TileBuilder(FillWorld kit, WorldEngine engine) {
      this.world = kit.world();
      this.sampler = new TerrainSampler(world, kit.density());
      BiomeLookup biomes = new BiomeLookup(world);
      this.painter = new SurfacePainter(world, sampler, biomes);
      this.terrain = new TerrainCache(world, sampler, painter);
      this.capture = new CaptureLevel(world, terrain, biomes);
      this.decorator = new Decorator(world, kit.decoration(), kit.structures(), capture, terrain, biomes);
      this.writer = engine == null ? null : new LodWriter(engine, sampler.airLight());
      this.decorations = kit.decorated();
      this.terrain.timed = engine == null;
      this.minY = world.minY();
      this.maxY = world.maxY();
   }

   /** Writes the tile at {@code level}; chunks whose bit is set in {@code skip} ({@code dz * 8 + dx}) are left alone. */
   void build(int tileX, int tileZ, int level, long skip, boolean decorate) {
      int firstChunkX = tileX * TILE_CHUNKS;
      int firstChunkZ = tileZ * TILE_CHUNKS;
      int margin = 48;
      terrain.focus(firstChunkX * 16 - margin, firstChunkZ * 16 - margin, (firstChunkX + TILE_CHUNKS) * 16 + margin,
         (firstChunkZ + TILE_CHUNKS) * 16 + margin);
      capture.clearPlaced();
      if (decorate && level <= DECORATED_UP_TO) {
         long began = System.nanoTime();
         decorateAround(firstChunkX, firstChunkZ, TILE_CHUNKS);
         if (writer == null) PHASES[0] += System.nanoTime() - began;
      }
      if (level == 0) {
         long began = System.nanoTime();
         buildFull(firstChunkX, firstChunkZ, skip);
         if (writer == null) PHASES[4] += System.nanoTime() - began;
      } else if (level <= DECORATED_UP_TO) {
         buildReduced(tileX, tileZ, level, skip);
      } else {
         long began = System.nanoTime();
         buildSparse(tileX, tileZ, level, skip);
         if (writer == null) PHASES[3] += System.nanoTime() - began;
      }
   }

   /**
    * The finished columns of one chunk, decorated as in a level-0 tile, without writing anything. For checking
    * seed terrain against generated chunks. Indexed {@code z << 4 | x}.
    */
   ColumnRuns[] probeChunk(int chunkX, int chunkZ, boolean decorate) {
      terrain.reset();
      capture.clearPlaced();
      if (decorate) {
         decorateAround(chunkX, chunkZ, 1);
      }
      ColumnRuns[] columns = new ColumnRuns[256];
      for (int index = 0; index < 256; index++) {
         columns[index] = finished((chunkX << 4) + (index & 15), (chunkZ << 4) + (index >> 4));
      }
      return columns;
   }

   /**
    * Decoration for a square of chunks: every chunk of it and of the ring around it (whose trees can spill in)
    * is decorated once into the shared store, then everything landing inside the square is laid into the capture.
    */
   private void decorateAround(int firstChunkX, int firstChunkZ, int chunks) {
      int lastX = firstChunkX + chunks;
      int lastZ = firstChunkZ + chunks;
      decorator.prepareArea(firstChunkX - 1, firstChunkZ - 1, lastX, lastZ);
      ChunkDecoration[] ring = new ChunkDecoration[(chunks + 2) * (chunks + 2)];
      int i = 0;
      for (int cz = firstChunkZ - 1; cz <= lastZ; cz++) {
         for (int cx = firstChunkX - 1; cx <= lastX; cx++) {
            int x = cx;
            int z = cz;
            ring[i++] = decorations.get(x, z, () -> decorator.decorateAlone(x, z, decorations));
         }
      }
      capture.clearPlaced();
      int minX = firstChunkX << 4;
      int minZ = firstChunkZ << 4;
      for (ChunkDecoration decoration : ring) {
         decoration.addTo(capture, minX, minZ, minX + chunks * 16, minZ + chunks * 16);
      }
   }

   private void buildFull(int firstChunkX, int firstChunkZ, long skip) {
      for (int dz = 0; dz < TILE_CHUNKS; dz++) {
         for (int dx = 0; dx < TILE_CHUNKS; dx++) {
            if ((skip >>> (dz * TILE_CHUNKS + dx) & 1L) != 0L) {
               continue;
            }
            int chunkX = firstChunkX + dx;
            int chunkZ = firstChunkZ + dz;
            if (RealColumns.isReal(world.dimension(), chunkX, chunkZ)) {
               continue;
            }
            for (int index = 0; index < 256; index++) {
               int x = (chunkX << 4) + (index & 15);
               int z = (chunkZ << 4) + (index >> 4);
               chunkColumns[index] = finished(x, z);
            }
            if (writer == null) {
               digestColumns(chunkColumns);
            } else {
               writer.writeChunk(chunkX, chunkZ, chunkColumns, minY, maxY);
            }
            RealColumns.recheck(world.dimension(), chunkX, chunkZ);
         }
      }
   }

   /** A painted column with every block placed in it. */
   private ColumnRuns finished(int x, int z) {
      ColumnRuns column = terrain.painted(x, z).copy();
      addPlaced(column, x, z, Integer.MIN_VALUE);
      return column;
   }

   /** Writes blocks placed in column (x, z) into {@code column}, only those at or above {@code from}. */
   private void addPlaced(ColumnRuns column, int x, int z, int from) {
      Int2ObjectOpenHashMap<BlockState> placed = capture.placed().get(ChunkPos.asLong(x, z));
      if (placed == null) {
         return;
      }
      int[] ys = placed.keySet().toIntArray();
      java.util.Arrays.sort(ys);
      Holder<Biome> biome = column.count() > 0 ? column.biome(column.count() - 1) : null;
      for (int y : ys) {
         if (y < from) {
            continue;
         }
         BlockState state = com.golem.boxy.lod.dt.DtLod.connect(placed.get(y), capture, x, y, z);
         column.set(y, state, biome, state.isAir() ? sampler.airLight() : ColumnRuns.UNLIT);
      }
   }

   private void buildReduced(int tileX, int tileZ, int level, long skip) {
      int scale = 1 << level;
      int across = TILE_BLOCKS >> level;
      ColumnRuns[] grid = new ColumnRuns[across * across];
      int originX = tileX * TILE_BLOCKS;
      int originZ = tileZ * TILE_BLOCKS;
      for (int gz = 0; gz < across; gz++) {
         for (int gx = 0; gx < across; gx++) {
            int x = originX + gx * scale;
            int z = originZ + gz * scale;
            long began = System.nanoTime();
            ColumnRuns column = finished(x, z);
            long mid = System.nanoTime();
            keepTallest(column, x, z, scale);
            if (writer == null) {
               PHASES[1] += mid - began;
               PHASES[2] += System.nanoTime() - mid;
            }
            grid[gz * across + gx] = column;
         }
      }
      writeCoarse(tileX, tileZ, level, across, grid, skip);
   }

   /** Adds the tallest object placed anywhere in a voxel's footprint, above this column's own top. */
   private void keepTallest(ColumnRuns column, int x, int z, int scale) {
      int bestTop = column.height();
      int bestX = 0;
      int bestZ = 0;
      boolean found = false;
      for (int dz = 0; dz < scale; dz++) {
         for (int dx = 0; dx < scale; dx++) {
            if (dx == 0 && dz == 0) {
               continue;
            }
            Int2ObjectOpenHashMap<BlockState> placed = capture.placed().get(ChunkPos.asLong(x + dx, z + dz));
            if (placed == null) {
               continue;
            }
            for (Int2ObjectMap.Entry<BlockState> entry : placed.int2ObjectEntrySet()) {
               if (!entry.getValue().isAir() && entry.getIntKey() + 1 > bestTop) {
                  bestTop = entry.getIntKey() + 1;
                  bestX = x + dx;
                  bestZ = z + dz;
                  found = true;
               }
            }
         }
      }
      if (found) {
         addPlaced(column, bestX, bestZ, column.height());
      }
   }

   private void buildSparse(int tileX, int tileZ, int level, long skip) {
      int scale = 1 << level;
      int across = TILE_BLOCKS >> level;
      int ring = across + 2;
      int originX = tileX * TILE_BLOCKS;
      int originZ = tileZ * TILE_BLOCKS;
      ColumnRuns[] bare = new ColumnRuns[ring * ring];
      int[] solidTop = new int[ring * ring];
      for (int rz = 0; rz < ring; rz++) {
         for (int rx = 0; rx < ring; rx++) {
            ColumnRuns column = new ColumnRuns(minY);
            sampler.sample(originX + (rx - 1) * scale, originZ + (rz - 1) * scale, false, column);
            bare[rz * ring + rx] = column;
            solidTop[rz * ring + rx] = solidHeight(column);
         }
      }
      SurfacePainter.Heights gridHeights = (bx, bz) -> {
         int rx = Math.clamp(Math.floorDiv(bx - originX, scale) + 1, 0, ring - 1);
         int rz = Math.clamp(Math.floorDiv(bz - originZ, scale) + 1, 0, ring - 1);
         return bare[rz * ring + rx].height() - 1;
      };
      ColumnRuns[] grid = new ColumnRuns[across * across];
      for (int gz = 0; gz < across; gz++) {
         for (int gx = 0; gx < across; gx++) {
            int at = (gz + 1) * ring + gx + 1;
            int lowest = Math.min(Math.min(solidTop[at - 1], solidTop[at + 1]), Math.min(solidTop[at - ring], solidTop[at + ring]));
            ColumnRuns painted = new ColumnRuns(minY);
            painter.paint(originX + gx * scale, originZ + gz * scale, bare[at], Math.max(0, solidTop[at] - lowest), gridHeights,
               world.needsFullColumns(), painted);
            grid[gz * across + gx] = painted;
         }
      }
      writeCoarse(tileX, tileZ, level, across, grid, skip);
   }

   private void writeCoarse(int tileX, int tileZ, int level, int across, ColumnRuns[] grid, long skip) {
      int firstChunkX = tileX * TILE_CHUNKS;
      int firstChunkZ = tileZ * TILE_CHUNKS;
      LodWriter.Mask mask = (chunkX, chunkZ) -> {
         int bit = (chunkZ - firstChunkZ) * TILE_CHUNKS + (chunkX - firstChunkX);
         return (skip >>> bit & 1L) != 0L || RealColumns.isReal(world.dimension(), chunkX, chunkZ);
      };
      if (writer == null) {
         digestColumns(grid);
         return;
      }
      writer.writeCoarse(level, tileX * TILE_BLOCKS, tileZ * TILE_BLOCKS, across, grid, minY, maxY, mask);
      for (int dz = 0; dz < TILE_CHUNKS; dz++) {
         for (int dx = 0; dx < TILE_CHUNKS; dx++) {
            if ((skip >>> (dz * TILE_CHUNKS + dx) & 1L) == 0L) {
               RealColumns.recheck(world.dimension(), firstChunkX + dx, firstChunkZ + dz);
            }
         }
      }
   }

   /** Benchmark only (single thread): decorate, reduced columns, canopy, sparse, full. */
   static final long[] PHASES = new long[5];

   long digest() {
      return digest;
   }

   private void digestColumns(ColumnRuns[] columns) {
      long hash = digest;
      for (ColumnRuns column : columns) {
         for (int run = 0; run < column.count(); run++) {
            hash = hash * 31 + column.top(run);
            hash = hash * 31 + net.minecraft.world.level.block.Block.getId(column.state(run));
            hash = hash * 31 + column.light(run);
         }
         hash = hash * 31 + column.count();
      }
      digest = hash;
   }

   private int solidHeight(ColumnRuns column) {
      for (int run = column.count() - 1; run >= 0; run--) {
         if (!column.state(run).isAir() && column.state(run).getFluidState().isEmpty()) {
            return column.top(run);
         }
      }
      return minY;
   }
}
