package com.golem.boxy.seedlod.column;

import com.golem.boxy.seedlod.world.SeedWorld;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shapes bare terrain for single columns: default block where density is positive, the default fluid below sea
 * level where it is not, air above. Surface blocks come later ({@link SurfacePainter}).
 *
 * <p>Open dimensions keep only what is seen from above: the column is solid from the floor to its highest solid
 * block, found by stepping down from vanilla's preliminary surface estimate one cell at a time and refining inside
 * the first solid cell. Dimensions with a ceiling or floating islands keep every run, sampled per block (exact)
 * or per cell (coarse). One instance per thread.
 */
public final class TerrainSampler {
   /** How far above vanilla's preliminary estimate jagged peaks may rise; vanilla's own estimate ignores them. */
   private static final int PEAK_ALLOWANCE = 96;
   /** Vanilla's threshold on the jaggedness-free density for its preliminary surface. */
   private static final double PRELIMINARY_SOLID = 0.390625;
   private final SeedWorld world;
   private final DensityPlan plan;
   private final Probe probe;
   private final BlockState stone;
   private final BlockState fluid;
   private final int minY;
   private final int maxY;
   private final int seaLevel;
   private final int airLight;
   /** Preliminary surface per quart column; vanilla's noise chunk memoises it the same way. */
   private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap preliminaryMemo = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

   public TerrainSampler(SeedWorld world, DensityPlan plan) {
      this.world = world;
      this.plan = plan;
      this.probe = plan.newProbe();
      this.stone = world.settings().defaultBlock();
      this.fluid = world.settings().defaultFluid();
      this.minY = world.minY();
      this.maxY = world.maxY();
      this.seaLevel = world.seaLevel();
      this.airLight = world.dimensionType().hasSkyLight() ? ColumnRuns.OPEN_SKY : ColumnRuns.UNLIT;
   }

   public Probe probe() {
      return probe;
   }

   public int airLight() {
      return airLight;
   }

   /**
    * Vanilla's preliminary surface: the highest cell-aligned y where the jaggedness-free density clears its
    * threshold, sampled at the quart corner. {@link Integer#MAX_VALUE} when no cell does, as in vanilla.
    */
   public int preliminarySurface(int x, int z) {
      int qx = QuartPos.toBlock(QuartPos.fromBlock(x));
      int qz = QuartPos.toBlock(QuartPos.fromBlock(z));
      long key = net.minecraft.world.level.ChunkPos.asLong(qx, qz);
      if (preliminaryMemo.containsKey(key)) {
         return preliminaryMemo.get(key);
      }
      int found = Integer.MAX_VALUE;
      int top = plan.noiseMinY + world.settings().noiseSettings().height();
      for (int y = top; y >= plan.noiseMinY; y -= plan.cellHeight) {
         if (probe.preliminary(qx, y, qz) > PRELIMINARY_SOLID) {
            found = y;
            break;
         }
      }
      if (preliminaryMemo.size() >= 1 << 16) {
         preliminaryMemo.clear();
      }
      preliminaryMemo.put(key, found);
      return found;
   }

   /**
    * Fills {@code out} with the bare column at (x, z). {@code exact} interpolates densities like a generated
    * chunk; otherwise samples are taken directly, which suits columns spaced wider than a cell.
    */
   public void sample(int x, int z, boolean exact, ColumnRuns out) {
      out.clear();
      if (world.needsFullColumns()) {
         sampleAllRuns(x, z, exact, out);
      } else {
         sampleSurface(x, z, exact, out);
      }
   }

   private double density(int x, int y, int z, boolean exact) {
      return exact ? probe.exact(x, y, z) : probe.coarse(x, y, z);
   }

   private void sampleSurface(int x, int z, boolean exact, ColumnRuns out) {
      int estimate = preliminarySurface(x, z);
      int ceiling = maxY - 1;
      int y = estimate == Integer.MAX_VALUE ? ceiling : Math.min(ceiling, estimate + PEAK_ALLOWANCE);
      y = alignToCell(y);
      if (density(x, y, z, exact) > 0.0) {
         // A peak above the allowance: climb until open air.
         while (y < ceiling && density(x, Math.min(ceiling, y + plan.cellHeight), z, exact) > 0.0) {
            y = Math.min(ceiling, y + plan.cellHeight);
         }
         int solidTop = refineDown(x, Math.min(ceiling, y + plan.cellHeight), y, z, exact);
         finishSurface(solidTop + 1, out);
         return;
      }
      int above = y;
      while (y > minY) {
         int below = Math.max(minY, y - plan.cellHeight);
         if (density(x, below, z, exact) > 0.0) {
            finishSurface(refineDown(x, above, below, z, exact) + 1, out);
            return;
         }
         above = below;
         y = below;
      }
      finishSurface(minY, out);
   }

   /** Highest solid y in (solidAt, airAt]; {@code solidAt} is known solid, {@code airAt} known open. */
   private int refineDown(int x, int airAt, int solidAt, int z, boolean exact) {
      if (exact) {
         for (int y = airAt - 1; y > solidAt; y--) {
            if (density(x, y, z, exact) > 0.0) {
               return y;
            }
         }
         return solidAt;
      }
      int low = solidAt;
      int high = airAt;
      while (high - low > 1) {
         int mid = (low + high) >>> 1;
         if (density(x, mid, z, exact) > 0.0) {
            low = mid;
         } else {
            high = mid;
         }
      }
      return low;
   }

   private void finishSurface(int solidTop, ColumnRuns out) {
      out.append(solidTop, stone, null, ColumnRuns.UNLIT);
      if (solidTop < seaLevel && !fluid.isAir()) {
         out.append(seaLevel, fluid, null, airLight);
      }
   }

   private void sampleAllRuns(int x, int z, boolean exact, ColumnRuns out) {
      int step = exact ? 1 : Math.max(1, plan.cellHeight / 2);
      for (int bottom = minY; bottom < maxY; bottom += step) {
         int top = Math.min(maxY, bottom + step);
         int probeY = bottom + (top - bottom) / 2;
         BlockState state;
         int light;
         if (density(x, probeY, z, exact) > 0.0) {
            state = stone;
            light = ColumnRuns.UNLIT;
         } else if (probeY < seaLevel && !fluid.isAir()) {
            state = fluid;
            light = airLight;
         } else {
            state = AIR_MARKER;
            light = airLight;
         }
         out.append(top, state, null, light);
      }
      trimOpenTop(out);
   }

   private static final BlockState AIR_MARKER = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();

   /** Air at the top of a column is implicit; drop explicit trailing air runs. */
   private static void trimOpenTop(ColumnRuns out) {
      int keep = out.count();
      while (keep > 0 && out.state(keep - 1).isAir()) {
         keep--;
      }
      if (keep < out.count()) {
         ColumnRuns copy = out.copy();
         out.clear();
         for (int run = 0; run < keep; run++) {
            out.append(copy.top(run), copy.state(run), copy.biome(run), copy.light(run));
         }
      }
   }

   private int alignToCell(int y) {
      return Math.floorDiv(y - plan.noiseMinY, plan.cellHeight) * plan.cellHeight + plan.noiseMinY;
   }
}
