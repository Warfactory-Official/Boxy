package com.golem.boxy.seedlod.column;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * One thread's movable sampling point for a {@link DensityPlan}. It is the {@link DensityFunction.FunctionContext}
 * handed to vanilla functions, and it owns the cell-corner memo the plan's interpolating nodes read from.
 * Not thread safe; every fill worker holds its own.
 */
public final class Probe implements DensityFunction.FunctionContext {
   private static final int CORNER_LIMIT = 1 << 15;
   private final DensityPlan plan;
   private final Long2DoubleOpenHashMap[] corners;
   private final int[] stack = new int[24];
   private int depth;
   private int x;
   private int y;
   private int z;

   Probe(DensityPlan plan) {
      this.plan = plan;
      this.corners = new Long2DoubleOpenHashMap[plan.markerSlots];
      for (int i = 0; i < corners.length; i++) {
         corners[i] = new Long2DoubleOpenHashMap();
      }
   }

   static Probe of(DensityFunction.FunctionContext context) {
      return context instanceof Probe probe ? probe : null;
   }

   public Probe at(int x, int y, int z) {
      this.x = x;
      this.y = y;
      this.z = z;
      return this;
   }

   /** Density at a block as a generated chunk would compute it. */
   public double exact(int x, int y, int z) {
      return plan.exact.compute(at(x, y, z));
   }

   /** Density at a block without cell interpolation; for sparse samples. */
   public double coarse(int x, int y, int z) {
      return plan.coarse.compute(at(x, y, z));
   }

   /** The jaggedness-free initial density vanilla uses for its preliminary surface estimate. */
   public double preliminary(int x, int y, int z) {
      return plan.preliminary.compute(at(x, y, z));
   }

   /** Drop memoised corners, e.g. between tiles far apart. */
   public void forget() {
      for (Long2DoubleOpenHashMap map : corners) {
         map.clear();
      }
   }

   double corner(int slot, DensityFunction input, int cx, int cy, int cz) {
      Long2DoubleOpenHashMap memo = corners[slot];
      long key = BlockPos.asLong(cx, cy, cz);
      if (memo.containsKey(key)) {
         return memo.get(key);
      }
      push(cx, cy, cz);
      double value;
      try {
         value = input.compute(this);
      } finally {
         pop();
      }
      if (memo.size() >= CORNER_LIMIT) {
         memo.clear();
      }
      memo.put(key, value);
      return value;
   }

   void push(int nx, int ny, int nz) {
      stack[depth++] = x;
      stack[depth++] = y;
      stack[depth++] = z;
      x = nx;
      y = ny;
      z = nz;
   }

   void pop() {
      z = stack[--depth];
      y = stack[--depth];
      x = stack[--depth];
   }

   @Override
   public int blockX() {
      return x;
   }

   @Override
   public int blockY() {
      return y;
   }

   @Override
   public int blockZ() {
      return z;
   }
}
