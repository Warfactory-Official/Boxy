package com.golem.boxy.seedlod.decor;

import com.golem.boxy.seedlod.SeedLods;
import com.golem.boxy.seedlod.column.BiomeLookup;
import com.golem.boxy.seedlod.column.TerrainCache;
import com.golem.boxy.seedlod.world.SeedWorld;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Decorates chunks into a {@link CaptureLevel} the way a chunk generator's decoration pass does: one
 * decoration seed per chunk, then for every step its structures' pieces and its features, each reseeded by
 * step and index. Features are offered by the biomes around the chunk and filtered by vanilla's own biome
 * check, so a tree lands where real generation puts it. One per thread.
 */
public final class Decorator {
   /** Features or structures that failed once are reported once per session. */
   private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
   private static final java.util.Map<String, String> FAILED = new ConcurrentHashMap<>();
   private final SeedWorld world;
   private final DecorationPlan plan;
   private final StructureLayer structures;
   private final CaptureLevel level;
   private final TerrainCache terrain;
   private final BiomeLookup biomes;
   private final StructureManager structureManager;
   private final WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
   private final Set<Holder<Biome>> nearby = new ReferenceOpenHashSet<>();
   private final int biomeLayerStep;

   public Decorator(SeedWorld world, DecorationPlan plan, StructureLayer structures, CaptureLevel level, TerrainCache terrain, BiomeLookup biomes) {
      this.world = world;
      this.plan = plan;
      this.structures = structures;
      this.level = level;
      this.terrain = terrain;
      this.biomes = biomes;
      this.structureManager = new StructureManager(level, new WorldOptions(world.seed(), true, false), null);
      this.biomeLayerStep = world.needsFullColumns() ? 16 : 0;
   }

   private List<StructureLayer.Placed> areaStarts = List.of();
   private int areaMinX = Integer.MAX_VALUE;
   private int areaMinZ;
   private int areaMaxX;
   private int areaMaxZ;

   /** Looks up structure starts once for a block of chunks about to be decorated, instead of once per chunk. */
   public void prepareArea(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
      areaStarts = structures.touchingArea(minChunkX, minChunkZ, maxChunkX, maxChunkZ);
      areaMinX = minChunkX;
      areaMinZ = minChunkZ;
      areaMaxX = maxChunkX;
      areaMaxZ = maxChunkZ;
   }

   private List<StructureLayer.Placed> startsTouching(int chunkX, int chunkZ) {
      if (chunkX < areaMinX || chunkX > areaMaxX || chunkZ < areaMinZ || chunkZ > areaMaxZ) {
         return structures.touching(chunkX, chunkZ);
      }
      int minX = chunkX << 4;
      int minZ = chunkZ << 4;
      List<StructureLayer.Placed> found = new java.util.ArrayList<>();
      for (StructureLayer.Placed placed : areaStarts) {
         BoundingBox box = placed.start().getBoundingBox();
         if (box.maxX() >= minX && box.minX() <= minX + 15 && box.maxZ() >= minZ && box.minZ() <= minZ + 15) {
            found.add(placed);
         }
      }
      return found;
   }

   /**
    * Decorates one chunk on its own and returns what it placed. Neighbours already in {@code store} are laid
    * down first (within this chunk and the ring its features can reach) so features see their trees and
    * structures, as they would in a world where those chunks were generated first.
    */
   public ChunkDecoration decorateAlone(int chunkX, int chunkZ, DecorationStore store) {
      level.clearPlaced();
      int minX = (chunkX - 1) << 4;
      int minZ = (chunkZ - 1) << 4;
      for (int dz = -2; dz <= 2; dz++) {
         for (int dx = -2; dx <= 2; dx++) {
            ChunkDecoration neighbour = dx == 0 && dz == 0 ? null : store.peek(chunkX + dx, chunkZ + dz);
            if (neighbour != null) {
               neighbour.addTo(level, minX, minZ, minX + 48, minZ + 48);
            }
         }
      }
      level.recordWrites();
      decorate(chunkX, chunkZ);
      return level.takeWritten();
   }

   public void decorate(int chunkX, int chunkZ) {
      int originX = chunkX << 4;
      int originZ = chunkZ << 4;
      ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
      BlockPos origin = new BlockPos(originX, level.getMinBuildHeight(), originZ);
      BoundingBox writable = new BoundingBox(originX, level.getMinBuildHeight() + 1, originZ, originX + 15, level.getMaxBuildHeight() - 1, originZ + 15);
      long decorationSeed = random.setDecorationSeed(world.seed(), originX, originZ);
      collectNearbyBiomes(chunkX, chunkZ);
      List<StructureLayer.Placed> starts = startsTouching(chunkX, chunkZ);
      for (int step = 0; step < plan.stepCount(); step++) {
         if (!plan.runs(step)) {
            continue;
         }
         if (!starts.isEmpty()) {
            List<Structure> inStep = plan.structures(step);
            for (int index = 0; index < inStep.size(); index++) {
               Structure structure = inStep.get(index);
               for (StructureLayer.Placed placed : starts) {
                  if (placed.structure() != structure) {
                     continue;
                  }
                  random.setFeatureSeed(decorationSeed, index, step);
                  placePieces(placed.start(), structure, writable, chunk);
               }
            }
         }
         if (!plan.hasFeatures(step)) {
            continue;
         }
         for (int index : plan.featuresFor(step, nearby)) {
            PlacedFeature feature = plan.feature(step, index);
            if (feature == null) {
               continue;
            }
            random.setFeatureSeed(decorationSeed, index, step);
            try {
               feature.placeWithBiomeCheck(level, world.generator(), random, origin);
            } catch (RuntimeException | LinkageError failure) {
               report("feature " + key(feature), failure);
            }
         }
      }
   }

   /**
    * What {@code StructureStart.placeInChunk} does, one piece at a time: a piece that needs something a seed
    * cannot give (typically spawning a guardian or a drowned) loses only itself, not the whole structure.
    */
   private void placePieces(net.minecraft.world.level.levelgen.structure.StructureStart start, Structure structure, BoundingBox writable,
                            ChunkPos chunk) {
      List<net.minecraft.world.level.levelgen.structure.StructurePiece> pieces = start.getPieces();
      if (pieces.isEmpty()) {
         return;
      }
      BoundingBox first = pieces.get(0).getBoundingBox();
      BlockPos center = first.getCenter();
      BlockPos pivot = new BlockPos(center.getX(), first.minY(), center.getZ());
      for (net.minecraft.world.level.levelgen.structure.StructurePiece piece : pieces) {
         if (!piece.getBoundingBox().intersects(writable)) {
            continue;
         }
         try {
            piece.postProcess(level, structureManager, world.generator(), random, writable, chunk, pivot);
         } catch (RuntimeException | LinkageError failure) {
            report("structure piece of " + key(structure), failure);
         }
      }
      try {
         structure.afterPlace(level, structureManager, world.generator(), random, writable, chunk, new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(pieces));
      } catch (RuntimeException | LinkageError failure) {
         report("structure " + key(structure), failure);
      }
   }

   /**
    * Biomes of the chunk and its eight neighbours, which vanilla offers features from. Sampled per quart column
    * at the terrain surface (and every {@code biomeLayerStep} blocks where interiors are visible) rather than
    * at every height: a feature from a biome found only underground would not be seen anyway.
    */
   private void collectNearbyBiomes(int chunkX, int chunkZ) {
      nearby.clear();
      int firstX = (chunkX - 1) << 4;
      int firstZ = (chunkZ - 1) << 4;
      for (int dz = 0; dz < 48; dz += 4) {
         for (int dx = 0; dx < 48; dx += 4) {
            int x = firstX + dx;
            int z = firstZ + dz;
            if (biomeLayerStep == 0) {
               int surface = terrain.solidHeight(x, z);
               nearby.add(biomes.quartAt(x, surface, z));
            } else {
               for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y += biomeLayerStep) {
                  nearby.add(biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z)));
               }
            }
         }
      }
      nearby.retainAll(world.biomeSource().possibleBiomes());
   }

   private String key(Structure structure) {
      return world.registries().registryOrThrow(Registries.STRUCTURE).getResourceKey(structure).map(k -> k.location().toString()).orElse("?");
   }

   private String key(PlacedFeature feature) {
      return world.registries().registryOrThrow(Registries.PLACED_FEATURE).getResourceKey(feature).map(k -> k.location().toString()).orElse("?");
   }

   /** What has failed to place this session, with the first reason, for the development check. */
   public static java.util.Map<String, String> failures() {
      return java.util.Map.copyOf(FAILED);
   }

   private static void report(String what, Throwable failure) {
      FAILED.putIfAbsent(what, failure.toString());
      if (REPORTED.add(what)) {
         SeedLods.LOGGER.debug("Seed LODs leave out {}: it needs more than a seed can provide ({})", what, failure.toString());
      }
   }
}
