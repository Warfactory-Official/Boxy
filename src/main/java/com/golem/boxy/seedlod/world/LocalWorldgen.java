package com.golem.boxy.seedlod.world;

import com.golem.boxy.seedlod.SeedLods;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.DirectoryValidator;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.registries.DataPackRegistriesHooks;

/**
 * Worldgen data on a client that is not hosting the world: vanilla's data, every mod's data, and any packs the
 * player drops into {@code boxy-seedlod-datapacks}, loaded the way a server loads them (minus optional feature
 * packs). A server's shared generator is decoded against these registries, so they must match the server's;
 * {@link WorldgenFingerprint} checks that. Loaded once, on first use, off the client thread.
 */
public final class LocalWorldgen {
   public static final String EXTRA_PACKS = "boxy-seedlod-datapacks";
   private static volatile LocalWorldgen loaded;
   private final RegistryAccess.Frozen registries;
   private final CloseableResourceManager resources;
   private final List<String> extraPacks;
   private StructureTemplateManager templates;
   private LevelStorageSource.LevelStorageAccess templateStorage;

   private LocalWorldgen(RegistryAccess.Frozen registries, CloseableResourceManager resources, List<String> extraPacks) {
      this.registries = registries;
      this.resources = resources;
      this.extraPacks = extraPacks;
   }

   /** The client's worldgen, loading it on first call; null when it cannot be loaded. */
   public static LocalWorldgen get() {
      LocalWorldgen local = loaded;
      if (local == null) {
         synchronized (LocalWorldgen.class) {
            local = loaded;
            if (local == null) {
               local = load();
               loaded = local;
            }
         }
      }
      return local;
   }

   public RegistryAccess.Frozen registries() {
      return registries;
   }

   /** Names of packs from {@value #EXTRA_PACKS}; a server running other packs will not match. */
   public List<String> extraPacks() {
      return extraPacks;
   }

   /** Vanilla's template manager over these packs, for laying out structures. */
   public synchronized StructureTemplateManager templates() {
      if (templates == null) {
         try {
            Path home = FMLPaths.GAMEDIR.get().resolve("boxy").resolve("seedlod-templates");
            Files.createDirectories(home);
            templateStorage = LevelStorageSource.createDefault(home).createAccess("templates");
            templates = new StructureTemplateManager(resources, templateStorage, net.minecraft.util.datafix.DataFixers.getDataFixer(),
               BuiltInRegistries.BLOCK.asLookup());
         } catch (IOException e) {
            throw new IllegalStateException("cannot open a template workspace for seed LODs", e);
         }
      }
      return templates;
   }

   private static LocalWorldgen load() {
      long began = System.nanoTime();
      try {
         Path extra = FMLPaths.GAMEDIR.get().resolve(EXTRA_PACKS);
         Files.createDirectories(extra);
         PackRepository repository = ServerPacksSource.createPackRepository(extra, new DirectoryValidator(path -> true));
         repository.reload();
         List<String> selected = new ArrayList<>();
         List<String> extraNames = new ArrayList<>();
         for (Pack pack : repository.getAvailablePacks()) {
            if (pack.getPackSource() == PackSource.FEATURE) {
               continue;
            }
            selected.add(pack.getId());
            if (pack.getPackSource() == PackSource.WORLD) {
               extraNames.add(pack.getId());
            }
         }
         repository.setSelected(selected);
         CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, repository.openAllSelected());
         RegistryAccess.Frozen builtIn = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
         RegistryAccess.Frozen worldgen = RegistryDataLoader.load(resources, builtIn, DataPackRegistriesHooks.getDataPackRegistries());
         worldgen.registries().forEach(entry -> bindTags(entry.value(), resources));
         RegistryAccess.Frozen beforeDimensions = join(builtIn, worldgen);
         RegistryAccess.Frozen dimensions = RegistryDataLoader.load(resources, beforeDimensions, RegistryDataLoader.DIMENSION_REGISTRIES);
         RegistryAccess.Frozen all = join(beforeDimensions, dimensions);
         SeedLods.LOGGER.info("Seed LODs read this game's worldgen ({} packs) in {} ms", selected.size(), (System.nanoTime() - began) / 1_000_000L);
         return new LocalWorldgen(all, resources, List.copyOf(extraNames));
      } catch (IOException | RuntimeException failure) {
         SeedLods.LOGGER.warn("Seed LODs could not read this game's worldgen, so servers' seeds cannot be used: {}", failure.toString());
         return null;
      }
   }

   private static RegistryAccess.Frozen join(RegistryAccess first, RegistryAccess second) {
      return new RegistryAccess.ImmutableRegistryAccess(Stream.concat(first.registries(), second.registries())).freeze();
   }

   /** Data-pack registries load without tags; bind them the way a server's reload does. */
   private static <T> void bindTags(Registry<T> registry, CloseableResourceManager resources) {
      ResourceKey<? extends Registry<T>> key = registry.key();
      TagLoader<Holder<T>> loader = new TagLoader<>(
         (ResourceLocation id) -> registry.getHolder(ResourceKey.create(key, id)).map(holder -> (Holder<T>)holder),
         net.minecraft.core.registries.Registries.tagsDirPath(key));
      Map<ResourceLocation, Collection<Holder<T>>> loadedTags = loader.loadAndBuild(resources);
      Map<TagKey<T>, List<Holder<T>>> bound = new HashMap<>();
      loadedTags.forEach((id, holders) -> bound.put(TagKey.create(key, id), List.copyOf(holders)));
      registry.bindTags(bound);
   }

   static Optional<LocalWorldgen> ifLoaded() {
      return Optional.ofNullable(loaded);
   }
}
