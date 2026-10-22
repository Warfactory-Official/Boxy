package com.golem.boxy.seedlod.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/** The three ways a {@link SeedWorld} comes about. Each returns null when the dimension cannot be drawn. */
public final class SeedWorlds {
   private SeedWorlds() {
   }

   /** Singleplayer: the integrated server's own generator and random state, which are exact. */
   public static SeedWorld fromServer(ServerLevel level) {
      if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
         return null;
      }
      return new SeedWorld(level.dimension(), level.getSeed(), level.getServer().registryAccess(), generator,
         level.getChunkSource().randomState(), level.dimensionType(), level.getServer()::getStructureManager);
   }

   /** A server shared its generator (encoded with {@link #encodeGenerator}); rebuild it from local worldgen. */
   public static SeedWorld fromShared(ResourceKey<Level> dimension, long seed, String generatorJson, DimensionType type) {
      LocalWorldgen local = LocalWorldgen.get();
      if (local == null) {
         return null;
      }
      JsonElement json = JsonParser.parseString(generatorJson);
      ChunkGenerator generator = ChunkGenerator.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, local.registries()), json)
         .getOrThrow(message -> new IllegalArgumentException("the server's generator does not decode here: " + message));
      return fromGenerator(dimension, seed, generator, type, local);
   }

   /** A bare seed for a server without Boxy: the default world preset's generator for the dimension. */
   public static SeedWorld fromSeedOnly(ResourceKey<Level> dimension, long seed, DimensionType type) {
      LocalWorldgen local = LocalWorldgen.get();
      if (local == null) {
         return null;
      }
      ResourceKey<LevelStem> stem = ResourceKey.create(Registries.LEVEL_STEM, dimension.location());
      WorldPreset preset = local.registries().registryOrThrow(Registries.WORLD_PRESET)
         .get(ResourceLocation.withDefaultNamespace("normal"));
      if (preset == null) {
         return null;
      }
      LevelStem levelStem = preset.createWorldDimensions().dimensions().get(stem);
      return levelStem == null ? null : fromGenerator(dimension, seed, levelStem.generator(), type, local);
   }

   private static SeedWorld fromGenerator(ResourceKey<Level> dimension, long seed, ChunkGenerator generator, DimensionType type, LocalWorldgen local) {
      if (!(generator instanceof NoiseBasedChunkGenerator noise)) {
         return null;
      }
      RegistryAccess registries = local.registries();
      RandomState random = RandomState.create(noise.generatorSettings().value(), registries.lookupOrThrow(Registries.NOISE), seed);
      return new SeedWorld(dimension, seed, registries, noise, random, type, local::templates);
   }

   /** Server side: a dimension's generator as JSON that references worldgen entries by name. */
   public static String encodeGenerator(ServerLevel level) {
      return ChunkGenerator.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, level.getServer().registryAccess()),
            level.getChunkSource().getGenerator())
         .getOrThrow(message -> new IllegalStateException("cannot encode the generator of " + level.dimension().location() + ": " + message))
         .toString();
   }
}
