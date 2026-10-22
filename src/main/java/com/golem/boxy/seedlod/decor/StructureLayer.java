package com.golem.boxy.seedlod.decor;

import com.golem.boxy.seedlod.SeedLods;
import com.golem.boxy.seedlod.world.SeedWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Structure starts of one dimension, generated with vanilla code from the seed: a start chunk is chosen by each
 * set's placement, a structure by the set's weights, and its pieces by {@link Structure#generate}, which uses
 * vanilla's own templates. Results per chunk are cached and shared by all fill workers.
 */
public final class StructureLayer {
   /** Starts this many chunks away can still reach a chunk; vanilla references structures within 8. */
   private static final int REACH_CHUNKS = 8;
   private static final int CACHE_LIMIT = 1 << 16;
   private final SeedWorld world;
   private final DecorationPlan plan;
   private final Map<Long, List<Placed>> startsAt = new ConcurrentHashMap<>();
   private final AtomicBoolean warned = new AtomicBoolean();
   private volatile ChunkGeneratorStructureState state;

   /** A generated start and the structure it belongs to. */
   public record Placed(Structure structure, StructureStart start) {
   }

   public StructureLayer(SeedWorld world, DecorationPlan plan) {
      this.world = world;
      this.plan = plan;
   }

   /** Every start whose bounds reach into the given chunk. */
   public List<Placed> touching(int chunkX, int chunkZ) {
      int minX = chunkX << 4;
      int minZ = chunkZ << 4;
      List<Placed> found = new ArrayList<>();
      for (int dz = -REACH_CHUNKS; dz <= REACH_CHUNKS; dz++) {
         for (int dx = -REACH_CHUNKS; dx <= REACH_CHUNKS; dx++) {
            for (Placed placed : startsAt(chunkX + dx, chunkZ + dz)) {
               BoundingBox box = placed.start().getBoundingBox();
               if (box.maxX() >= minX && box.minX() <= minX + 15 && box.maxZ() >= minZ && box.minZ() <= minZ + 15) {
                  found.add(placed);
               }
            }
         }
      }
      return found;
   }

   /**
    * Every start whose bounds reach into the chunk rectangle, in the same order {@link #touching} lists them
    * (start chunk z, then x, then set order), so filtering this per chunk gives exactly {@link #touching}.
    */
   public List<Placed> touchingArea(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
      int minX = minChunkX << 4;
      int minZ = minChunkZ << 4;
      int maxX = (maxChunkX << 4) + 15;
      int maxZ = (maxChunkZ << 4) + 15;
      List<Placed> found = new ArrayList<>();
      for (int z = minChunkZ - REACH_CHUNKS; z <= maxChunkZ + REACH_CHUNKS; z++) {
         for (int x = minChunkX - REACH_CHUNKS; x <= maxChunkX + REACH_CHUNKS; x++) {
            for (Placed placed : startsAt(x, z)) {
               BoundingBox box = placed.start().getBoundingBox();
               if (box.maxX() >= minX && box.minX() <= maxX && box.maxZ() >= minZ && box.minZ() <= maxZ) {
                  found.add(placed);
               }
            }
         }
      }
      return found;
   }

   private List<Placed> startsAt(int chunkX, int chunkZ) {
      long key = ChunkPos.asLong(chunkX, chunkZ);
      List<Placed> known = startsAt.get(key);
      if (known != null) {
         return known;
      }
      List<Placed> made = generateAt(chunkX, chunkZ);
      if (startsAt.size() >= CACHE_LIMIT) {
         startsAt.clear();
      }
      startsAt.putIfAbsent(key, made);
      return made;
   }

   private List<Placed> generateAt(int chunkX, int chunkZ) {
      ChunkGeneratorStructureState state = placementState();
      ChunkPos pos = new ChunkPos(chunkX, chunkZ);
      List<Placed> made = List.of();
      for (Holder<StructureSet> set : state.possibleStructureSets()) {
         if (!set.value().placement().isStructureChunk(state, chunkX, chunkZ)) {
            continue;
         }
         Placed placed = pick(set.value(), state, pos);
         if (placed != null) {
            if (made.isEmpty()) {
               made = new ArrayList<>(1);
            }
            made.add(placed);
         }
      }
      return made;
   }

   /** Vanilla's placement state for this world; concentric-ring sets compute their rings when first asked. */
   private ChunkGeneratorStructureState placementState() {
      ChunkGeneratorStructureState known = state;
      if (known == null) {
         synchronized (this) {
            if (state == null) {
               ChunkGeneratorStructureState made = world.generator().createState(
                  world.registries().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE_SET), world.randomState(), world.seed());
               // Its ring positions are built lazily and not thread-safe; build them before anyone else sees it,
               // as a server does once at start.
               made.ensureStructuresGenerated();
               state = made;
            }
            known = state;
         }
      }
      return known;
   }

   /** One set's start at a chunk: a single entry is tried alone, several are drawn by weight until one fits. */
   private Placed pick(StructureSet set, ChunkGeneratorStructureState state, ChunkPos pos) {
      List<StructureSet.StructureSelectionEntry> entries = set.structures();
      if (entries.size() == 1) {
         Structure only = entries.get(0).structure().value();
         return plan.runs(only.step().ordinal()) ? attempt(only, state, pos) : null;
      }
      List<StructureSet.StructureSelectionEntry> remaining = new ArrayList<>(entries);
      WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
      random.setLargeFeatureSeed(state.getLevelSeed(), pos.x, pos.z);
      int totalWeight = 0;
      for (StructureSet.StructureSelectionEntry entry : remaining) {
         totalWeight += entry.weight();
      }
      while (!remaining.isEmpty()) {
         int roll = random.nextInt(totalWeight);
         int chosen = 0;
         for (StructureSet.StructureSelectionEntry entry : remaining) {
            roll -= entry.weight();
            if (roll < 0) {
               break;
            }
            chosen++;
         }
         StructureSet.StructureSelectionEntry entry = remaining.get(chosen);
         Placed placed = attempt(entry.structure().value(), state, pos);
         if (placed != null) {
            // A valid pick ends the draw even when it is not drawn, as in vanilla.
            return plan.runs(placed.structure().step().ordinal()) ? placed : null;
         }
         remaining.remove(chosen);
         totalWeight -= entry.weight();
      }
      return null;
   }

   private Placed attempt(Structure structure, ChunkGeneratorStructureState state, ChunkPos pos) {
      try {
         StructureStart start = structure.generate(world.registries(), world.generator(), world.biomeSource(), state.randomState(),
            world.templates(), state.getLevelSeed(), pos, 0, world.heightAccessor(), structure.biomes()::contains);
         return start.isValid() ? new Placed(structure, start) : null;
      } catch (RuntimeException | LinkageError failure) {
         if (warned.compareAndSet(false, true)) {
            SeedLods.LOGGER.warn("A structure in {} could not be laid out from the seed; such structures are left out: {}", world, failure.toString());
         }
         return null;
      }
   }
}
