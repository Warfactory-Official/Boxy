package com.golem.boxy.seedlod.world;

import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Everything needed to generate one dimension from its seed, independent of where it came from: the integrated
 * server's own objects in singleplayer, or a generator a server shared and this client rebuilt from its own data
 * packs ({@link SeedWorlds}). Immutable and shared by all fill workers; per-thread state lives elsewhere.
 */
public record SeedWorld(ResourceKey<Level> dimension, long seed, RegistryAccess registries, NoiseBasedChunkGenerator generator,
                        RandomState randomState, DimensionType dimensionType, Supplier<StructureTemplateManager> templateSource) {

   public BiomeSource biomeSource() {
      return generator.getBiomeSource();
   }

   public Holder<NoiseGeneratorSettings> settingsHolder() {
      return generator.generatorSettings();
   }

   public NoiseGeneratorSettings settings() {
      return settingsHolder().value();
   }

   public StructureTemplateManager templates() {
      return templateSource.get();
   }

   /** Lowest generated block: the higher of the dimension's floor and the noise floor. */
   public int minY() {
      return Math.max(dimensionType.minY(), settings().noiseSettings().minY());
   }

   /** One above the highest generated block. */
   public int maxY() {
      int dimensionTop = dimensionType.minY() + dimensionType.height();
      int noiseTop = settings().noiseSettings().minY() + settings().noiseSettings().height();
      return Math.min(dimensionTop, noiseTop);
   }

   public int seaLevel() {
      return settings().seaLevel();
   }

   /** True where players see terrain from inside: under a ceiling, between floating islands, without sky. */
   public boolean needsFullColumns() {
      return dimensionType.hasCeiling() || !dimensionType.hasSkyLight() || dimension.equals(Level.END);
   }

   public LevelHeightAccessor heightAccessor() {
      return LevelHeightAccessor.create(dimensionType.minY(), dimensionType.height());
   }

   @Override
   public String toString() {
      return dimension.location() + " (seed " + seed + ")";
   }
}
