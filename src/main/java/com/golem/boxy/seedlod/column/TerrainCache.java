package com.golem.boxy.seedlod.column;

import com.golem.boxy.seedlod.world.SeedWorld;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * Full-resolution terrain around one tile, built on demand: bare columns from the {@link TerrainSampler}, then
 * painted columns from the {@link SurfacePainter} once a column's neighbours are known. Feature placement reads
 * arbitrary columns near the tile through this, so columns are kept until {@link #reset()}. One per thread.
 */
public final class TerrainCache implements SurfacePainter.Heights {
   private static final int LIMIT = 1 << 17;
   private final TerrainSampler sampler;
   private final SurfacePainter painter;
   private final boolean everyBiome;
   private final Long2ObjectOpenHashMap<ColumnRuns> bare = new Long2ObjectOpenHashMap<>();
   private final Long2ObjectOpenHashMap<ColumnRuns> painted = new Long2ObjectOpenHashMap<>();
   private final int floor;
   /** Benchmark only: time spent sampling and painting, when {@link #timed}. */
   public static final long[] SAMPLE_NANOS = new long[2];
   public boolean timed;

   public TerrainCache(SeedWorld world, TerrainSampler sampler, SurfacePainter painter) {
      this.sampler = sampler;
      this.painter = painter;
      this.everyBiome = world.needsFullColumns();
      this.floor = world.minY();
   }

   public void reset() {
      bare.clear();
      painted.clear();
      sampler.probe().forget();
   }

   /**
    * Keeps only columns inside the block rectangle (they are pure functions of position, so keeping them for the
    * next tile changes nothing but the work) and the density memos; everything else is dropped.
    */
   public void focus(int minX, int minZ, int maxX, int maxZ) {
      if (bare.size() + painted.size() < LIMIT / 4) {
         return;
      }
      java.util.function.LongPredicate outside = key -> {
         int x = ChunkPos.getX(key);
         int z = ChunkPos.getZ(key);
         return x < minX || x > maxX || z < minZ || z > maxZ;
      };
      bare.keySet().removeIf(outside::test);
      painted.keySet().removeIf(outside::test);
   }

   public ColumnRuns bare(int x, int z) {
      long key = ChunkPos.asLong(x, z);
      ColumnRuns column = bare.get(key);
      if (column == null) {
         if (bare.size() >= LIMIT) {
            reset();
         }
         column = new ColumnRuns(floor);
         long began = timed ? System.nanoTime() : 0L;
         sampler.sample(x, z, true, column);
         if (timed) {
            SAMPLE_NANOS[0] += System.nanoTime() - began;
         }
         bare.put(key, column);
      }
      return column;
   }

   /** Highest non-air block of the bare column, fluids included, as a heightmap's "top block". */
   @Override
   public int topBlock(int x, int z) {
      return bare(x, z).height() - 1;
   }

   /** One above the highest block that is neither air nor fluid. */
   public int solidHeight(int x, int z) {
      ColumnRuns column = bare(x, z);
      for (int run = column.count() - 1; run >= 0; run--) {
         if (!column.state(run).isAir() && column.state(run).getFluidState().isEmpty()) {
            return column.top(run);
         }
      }
      return floor;
   }

   public ColumnRuns painted(int x, int z) {
      long key = ChunkPos.asLong(x, z);
      ColumnRuns column = painted.get(key);
      if (column == null) {
         int own = solidHeight(x, z);
         int lowest = Math.min(Math.min(solidHeight(x - 1, z), solidHeight(x + 1, z)), Math.min(solidHeight(x, z - 1), solidHeight(x, z + 1)));
         column = new ColumnRuns(floor);
         long began = timed ? System.nanoTime() : 0L;
         painter.paint(x, z, bare(x, z), Math.max(0, own - lowest), this, everyBiome, column);
         if (timed) {
            SAMPLE_NANOS[1] += System.nanoTime() - began;
         }
         painted.put(key, column);
      }
      return column;
   }
}
