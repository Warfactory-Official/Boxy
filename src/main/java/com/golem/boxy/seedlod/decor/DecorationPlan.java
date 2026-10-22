package com.golem.boxy.seedlod.decor;

import com.golem.boxy.seedlod.world.SeedWorld;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Which features and structures a dimension decorates with, in vanilla's order. Feature indices per step come
 * from vanilla's own sorter over every possible biome, exactly as a chunk generator builds them, so the random
 * seed each feature receives matches real generation. Steps nobody sees from a distance are left out.
 */
public final class DecorationPlan {
   private final List<FeatureSorter.StepFeatureData> steps;
   private final List<List<Structure>> structuresByStep;
   private final Set<GenerationStep.Decoration> visible;
   private final int stepCount;

   public DecorationPlan(SeedWorld world) {
      this.steps = FeatureSorter.buildFeaturesPerStep(
         List.copyOf(world.biomeSource().possibleBiomes()), biome -> biome.value().getGenerationSettings().features(), true);
      this.visible = visibleSteps(world.needsFullColumns());
      Registry<Structure> structures = world.registries().registryOrThrow(Registries.STRUCTURE);
      int count = GenerationStep.Decoration.values().length;
      List<List<Structure>> byStep = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
         byStep.add(new ArrayList<>());
      }
      // Registry order is vanilla's order, which fixes each structure's index within its step.
      for (Structure structure : structures) {
         byStep.get(structure.step().ordinal()).add(structure);
      }
      this.structuresByStep = byStep;
      this.stepCount = Math.max(count, steps.size());
   }

   private static Set<GenerationStep.Decoration> visibleSteps(boolean interiorsVisible) {
      Set<GenerationStep.Decoration> seen = EnumSet.allOf(GenerationStep.Decoration.class);
      seen.remove(GenerationStep.Decoration.STRONGHOLDS);
      seen.remove(GenerationStep.Decoration.UNDERGROUND_ORES);
      seen.remove(GenerationStep.Decoration.FLUID_SPRINGS);
      if (!interiorsVisible) {
         seen.remove(GenerationStep.Decoration.UNDERGROUND_STRUCTURES);
         seen.remove(GenerationStep.Decoration.UNDERGROUND_DECORATION);
      }
      return seen;
   }

   public int stepCount() {
      return stepCount;
   }

   public boolean runs(int step) {
      return step < GenerationStep.Decoration.values().length && visible.contains(GenerationStep.Decoration.values()[step]);
   }

   public List<Structure> structures(int step) {
      return step < structuresByStep.size() ? structuresByStep.get(step) : List.of();
   }

   public boolean hasFeatures(int step) {
      return step < steps.size();
   }

   /** The step's feature at a sorted index, or null for one that is never worth placing. */
   public PlacedFeature feature(int step, int index) {
      PlacedFeature feature = steps.get(step).features().get(index);
      return feature.feature().value().feature() == Feature.GEODE ? null : feature;
   }

   /** Sorted indices of this step's features that any of {@code biomes} lists. */
   public int[] featuresFor(int step, Iterable<Holder<Biome>> biomes) {
      FeatureSorter.StepFeatureData data = steps.get(step);
      it.unimi.dsi.fastutil.ints.IntArraySet indices = new it.unimi.dsi.fastutil.ints.IntArraySet();
      for (Holder<Biome> biome : biomes) {
         List<HolderSet<PlacedFeature>> perStep = biome.value().getGenerationSettings().features();
         if (step < perStep.size()) {
            for (Holder<PlacedFeature> feature : perStep.get(step)) {
               indices.add(data.indexMapping().applyAsInt(feature.value()));
            }
         }
      }
      int[] sorted = indices.toIntArray();
      java.util.Arrays.sort(sorted);
      return sorted;
   }
}
