package com.golem.boxy.seedlod.column;

import com.golem.boxy.seedlod.world.SeedWorld;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.QuartPos;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;

/**
 * A dimension's density functions rewired for sampling single columns outside a chunk.
 *
 * <p>Inside a chunk, vanilla's {@code NoiseChunk} gives two cache markers a meaning that changes values:
 * {@code flat_cache} reads its input at the quart grid point (y = 0) and {@code interpolated} blends its input
 * trilinearly between cell corners. Both are reproduced here so heights match generated chunks; the remaining
 * markers only cache and are dropped. Corner values are memoised per thread in {@link Probe}, so neighbouring
 * columns of one cell share them.
 *
 * <p>Two variants are built: {@code exact} interpolates like a chunk does, {@code coarse} evaluates
 * interpolated inputs at the point itself, which is cheaper when sampled columns are too far apart to share
 * corners and the voxels are larger than a cell anyway.
 */
public final class DensityPlan {
   final int cellWidth;
   final int cellHeight;
   final int noiseMinY;
   final DensityFunction exact;
   final DensityFunction coarse;
   final DensityFunction preliminary;
   final int markerSlots;

   public DensityPlan(SeedWorld world) {
      NoiseSettings noise = world.settings().noiseSettings();
      this.cellWidth = noise.getCellWidth();
      this.cellHeight = noise.getCellHeight();
      this.noiseMinY = noise.minY();
      NoiseRouter router = world.randomState().router();
      AtomicInteger slots = new AtomicInteger();
      this.exact = router.finalDensity().mapAll(c2meAware(new Rewire(true, slots)));
      this.coarse = router.finalDensity().mapAll(c2meAware(new Rewire(false, slots)));
      this.preliminary = router.initialDensityWithoutJaggedness().mapAll(c2meAware(new Rewire(false, slots)));
      this.markerSlots = slots.get();
   }

   public Probe newProbe() {
      return new Probe(this);
   }

   private static final Class<?> C2ME_BLENDING_AWARE = optionalClass("com.ishland.c2me.opts.dfc.common.ducks.IBlendingAwareVisitor");

   /**
    * C2ME's density function compiler replaces the router with compiled nodes whose {@code mapAll} only accepts a
    * visitor that keeps its cache nodes, so unwrapping markers fails with a ClassCastException. A visitor that
    * reports blending makes those nodes map their original, uncompiled tree instead.
    */
   private static DensityFunction.Visitor c2meAware(DensityFunction.Visitor visitor) {
      if (C2ME_BLENDING_AWARE == null) {
         return visitor;
      }
      return (DensityFunction.Visitor)Proxy.newProxyInstance(DensityPlan.class.getClassLoader(),
         new Class<?>[]{DensityFunction.Visitor.class, C2ME_BLENDING_AWARE},
         (proxy, method, args) -> method.getDeclaringClass() == C2ME_BLENDING_AWARE ? Boolean.TRUE : method.invoke(visitor, args));
   }

   private static Class<?> optionalClass(String name) {
      try {
         return Class.forName(name, false, DensityPlan.class.getClassLoader());
      } catch (ClassNotFoundException | LinkageError e) {
         return null;
      }
   }

   private final class Rewire implements DensityFunction.Visitor {
      private final boolean interpolate;
      private final AtomicInteger slots;

      Rewire(boolean interpolate, AtomicInteger slots) {
         this.interpolate = interpolate;
         this.slots = slots;
      }

      @Override
      public DensityFunction apply(DensityFunction function) {
         if (function instanceof DensityFunctions.HolderHolder holder) {
            return holder.function().value();
         }
         if (!(function instanceof DensityFunctions.MarkerOrMarked marker)) {
            return function;
         }
         // The marker type enum is not visible here; its constant names are stable data-pack names.
         return switch (String.valueOf(marker.type())) {
            case "FlatCache" -> new QuartPinned(marker.wrapped(), slots.getAndIncrement());
            case "Interpolated" -> interpolate ? new CellBlend(marker.wrapped(), slots.getAndIncrement()) : marker.wrapped();
            default -> marker.wrapped();
         };
      }
   }

   /**
    * {@code flat_cache} as a chunk sees it: the input sampled at the quart corner at y = 0. Memoised per quart
    * column in the probe, as a chunk memoises it per quart; these inputs are the costly 2D climate noises.
    */
   private record QuartPinned(DensityFunction input, int slot) implements Evaluated {
      @Override
      public double compute(FunctionContext at) {
         int x = QuartPos.toBlock(QuartPos.fromBlock(at.blockX()));
         int z = QuartPos.toBlock(QuartPos.fromBlock(at.blockZ()));
         Probe probe = Probe.of(at);
         return probe == null ? input.compute(new SinglePointContext(x, 0, z)) : probe.corner(slot, input, x, 0, z);
      }
   }

   /** {@code interpolated} as a chunk sees it: trilinear between the eight corners of the enclosing cell. */
   private final class CellBlend implements Evaluated {
      private final DensityFunction input;
      private final int slot;

      CellBlend(DensityFunction input, int slot) {
         this.input = input;
         this.slot = slot;
      }

      @Override
      public DensityFunction input() {
         return input;
      }

      @Override
      public double compute(FunctionContext at) {
         Probe probe = Probe.of(at);
         if (probe == null) {
            return input.compute(at);
         }
         int x = at.blockX();
         int y = at.blockY();
         int z = at.blockZ();
         int x0 = Math.floorDiv(x, cellWidth) * cellWidth;
         int z0 = Math.floorDiv(z, cellWidth) * cellWidth;
         int y0 = Math.floorDiv(y - noiseMinY, cellHeight) * cellHeight + noiseMinY;
         double fx = (double)(x - x0) / cellWidth;
         double fy = (double)(y - y0) / cellHeight;
         double fz = (double)(z - z0) / cellWidth;
         int x1 = x0 + cellWidth;
         int y1 = y0 + cellHeight;
         int z1 = z0 + cellWidth;
         double c000 = probe.corner(slot, input, x0, y0, z0);
         double c100 = probe.corner(slot, input, x1, y0, z0);
         double c010 = probe.corner(slot, input, x0, y1, z0);
         double c110 = probe.corner(slot, input, x1, y1, z0);
         double c001 = probe.corner(slot, input, x0, y0, z1);
         double c101 = probe.corner(slot, input, x1, y0, z1);
         double c011 = probe.corner(slot, input, x0, y1, z1);
         double c111 = probe.corner(slot, input, x1, y1, z1);
         double bottom = lerp(fz, lerp(fx, c000, c100), lerp(fx, c001, c101));
         double top = lerp(fz, lerp(fx, c010, c110), lerp(fx, c011, c111));
         return lerp(fy, bottom, top);
      }

      private static double lerp(double t, double a, double b) {
         return a + t * (b - a);
      }
   }

   /** Rewired nodes are evaluated, never serialised or re-mapped, so the plumbing a codec needs is inert. */
   private interface Evaluated extends DensityFunction {
      @Override
      default void fillArray(double[] values, ContextProvider provider) {
         provider.fillAllDirectly(values, this);
      }

      @Override
      default DensityFunction mapAll(Visitor visitor) {
         return visitor.apply(this);
      }

      DensityFunction input();

      @Override
      default double minValue() {
         return input().minValue();
      }

      @Override
      default double maxValue() {
         return input().maxValue();
      }

      @Override
      default KeyDispatchDataCodec<? extends DensityFunction> codec() {
         throw new UnsupportedOperationException("seed LOD density nodes are not serialisable");
      }
   }
}
