package com.golem.boxy.seedlod.fill;

import com.golem.boxy.seedlod.column.DensityPlan;
import com.golem.boxy.seedlod.decor.DecorationPlan;
import com.golem.boxy.seedlod.decor.DecorationStore;
import com.golem.boxy.seedlod.decor.StructureLayer;
import com.golem.boxy.seedlod.world.SeedWorld;

/** The thread-safe, per-dimension part of a fill: the world and the plans every worker reads. */
record FillWorld(SeedWorld world, DensityPlan density, DecorationPlan decoration, StructureLayer structures, DecorationStore decorated) {
   static FillWorld of(SeedWorld world) {
      DecorationPlan decoration = new DecorationPlan(world);
      return new FillWorld(world, new DensityPlan(world), decoration, new StructureLayer(world, decoration), new DecorationStore());
   }
}
