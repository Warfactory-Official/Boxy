package com.golem.boxy.seedlod.column;

import com.golem.boxy.seedlod.world.SeedWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Turns bare terrain into surface blocks with the dimension's own surface rules, evaluated by vanilla code.
 *
 * <p>Vanilla runs the rules over a whole chunk it has already filled. Here one rule context is reused for
 * single columns: the two things it reads beyond the column are supplied by stand-ins, neighbour heights for
 * its "steep" test ({@link HeightsChunk}) and the preliminary surface ({@link EstimateOnlyNoiseChunk}), both
 * answered from the {@link TerrainSampler}. Only the part of a run that can be seen is painted: down to the
 * lowest neighbouring surface (so cliff faces show their layers), and for dimensions with interior caverns also
 * the underside of each run. One instance per thread.
 */
public final class SurfacePainter {
   /** Always paint at least this deep, enough for every vanilla surface layer on flat ground. */
   private static final int MIN_DEPTH = 10;
   /** Cliff faces deeper than this are left as the default block. */
   private static final int MAX_DEPTH = 96;
   /** Underside depth painted on runs with something below them (ceilings, floating islands). */
   private static final int UNDERSIDE = 6;

   /** Answers the WORLD_SURFACE_WG height of any column; values are "highest non-air y". */
   public interface Heights {
      int topBlock(int x, int z);
   }

   private final SeedWorld world;
   private final TerrainSampler terrain;
   private final BiomeLookup biomes;
   private final BlockState defaultBlock;
   private final HeightsChunk chunk;
   private final SurfaceRules.Context context;
   private final SurfaceRules.SurfaceRule rule;
   private final SurfaceSystem system;
   private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

   public SurfacePainter(SeedWorld world, TerrainSampler terrain, BiomeLookup biomes) {
      this.world = world;
      this.terrain = terrain;
      this.biomes = biomes;
      this.defaultBlock = world.settings().defaultBlock();
      this.chunk = new HeightsChunk(world);
      this.system = world.randomState().surfaceSystem();
      NoiseChunk estimates = new EstimateOnlyNoiseChunk(world, terrain);
      this.context = new SurfaceRules.Context(
         world.randomState().surfaceSystem(),
         world.randomState(),
         chunk,
         estimates,
         biomes::at,
         world.registries().registryOrThrow(Registries.BIOME),
         new WorldGenerationContext(world.generator(), world.heightAccessor())
      );
      this.rule = world.settings().surfaceRule().apply(context);
   }

   /**
    * Paints {@code bare} into {@code out}. {@code exposed} is how far below this column's top its lowest
    * neighbour's top lies; {@code heights} answers neighbour heights within this column's chunk.
    */
   public void paint(int x, int z, ColumnRuns bare, int exposed, Heights heights, boolean everyBiome, ColumnRuns out) {
      out.clear();
      chunk.aim(x, z, heights);
      int surface = bare.height();
      Holder<Biome> surfaceBiome = biomes.at(x, surface, z);
      if (surfaceBiome.is(Biomes.ERODED_BADLANDS)) {
         // Vanilla raises its spires in default block before the rules paint them.
         bare = bare.copy();
         system.erodedBadlandsExtension(new RunsColumn(bare, surfaceBiome), x, z, surface, world.heightAccessor());
      }
      context.updateXZ(x, z);
      int depth = Math.clamp(exposed + 1, MIN_DEPTH, MAX_DEPTH);
      int topRun = bare.count() - 1;
      Holder<Biome> columnBiome = null;
      for (int run = 0; run <= topRun; run++) {
         BlockState state = bare.state(run);
         int bottom = bare.bottom(run);
         int top = bare.top(run);
         Holder<Biome> biome = everyBiome || columnBiome == null ? biomes.at(x, top - 1, z) : columnBiome;
         if (state != defaultBlock) {
            out.append(top, state, biome, bare.light(run));
            continue;
         }
         columnBiome = biome;
         int waterHeight = Integer.MIN_VALUE;
         if (run < topRun && !bare.state(run + 1).getFluidState().isEmpty()) {
            waterHeight = bare.top(run + 1);
         }
         boolean underside = run > 0 && world.needsFullColumns();
         int paintedFrom = Math.max(bottom, top - depth);
         int undersideTo = underside ? Math.min(paintedFrom, bottom + UNDERSIDE) : bottom;
         // Unpainted interior, bottom first, then the painted bands.
         for (int y = bottom; y < undersideTo; y++) {
            out.append(y + 1, apply(x, y, z, top, bottom, waterHeight), biome, ColumnRuns.UNLIT);
         }
         out.append(paintedFrom, defaultBlock, biome, ColumnRuns.UNLIT);
         for (int y = paintedFrom; y < top; y++) {
            out.append(y + 1, apply(x, y, z, top, bottom, waterHeight), biome, ColumnRuns.UNLIT);
         }
      }
      if (surfaceBiome.is(Biomes.FROZEN_OCEAN) || surfaceBiome.is(Biomes.DEEP_FROZEN_OCEAN)) {
         system.frozenOceanExtension(context.getMinSurfaceLevel(), surfaceBiome.value(), new RunsColumn(out, surfaceBiome), cursor, x, z, surface);
      }
   }

   /** Vanilla's column view over our runs, for the two surface shapes it adds outside the rules. */
   private final class RunsColumn implements BlockColumn {
      private final ColumnRuns runs;
      private final Holder<Biome> biome;

      RunsColumn(ColumnRuns runs, Holder<Biome> biome) {
         this.runs = runs;
         this.biome = biome;
      }

      @Override
      public BlockState getBlock(int y) {
         return runs.stateAt(y);
      }

      @Override
      public void setBlock(int y, BlockState state) {
         runs.set(y, state, biome, state.isAir() || !state.getFluidState().isEmpty() ? terrain.airLight() : ColumnRuns.UNLIT);
      }
   }

   private BlockState apply(int x, int y, int z, int runTop, int runBottom, int waterHeight) {
      context.updateY(runTop - y, y - runBottom + 1, waterHeight, x, y, z);
      BlockState painted = rule.tryApply(x, y, z);
      return painted == null ? defaultBlock : painted;
   }

   /** A chunk that only knows the heights of its columns, which is all the rule context asks of it. */
   private static final class HeightsChunk extends ProtoChunk {
      private int originX;
      private int originZ;
      private Heights heights;

      HeightsChunk(SeedWorld world) {
         super(ChunkPos.ZERO, UpgradeData.EMPTY, world.heightAccessor(), world.registries().registryOrThrow(Registries.BIOME), null);
      }

      void aim(int x, int z, Heights heights) {
         this.originX = x & ~15;
         this.originZ = z & ~15;
         this.heights = heights;
      }

      @Override
      public int getHeight(Heightmap.Types type, int localX, int localZ) {
         return heights.topBlock(originX + (localX & 15), originZ + (localZ & 15));
      }
   }

   /** Structures do not reshape the terrain the rules see; the stand-in noise chunk needs some beardifier. */
   private enum NoStructureBlending implements DensityFunctions.BeardifierOrMarker {
      INSTANCE;

      @Override
      public double compute(FunctionContext context) {
         return 0.0;
      }

      @Override
      public void fillArray(double[] values, ContextProvider provider) {
         java.util.Arrays.fill(values, 0.0);
      }

      @Override
      public double minValue() {
         return 0.0;
      }

      @Override
      public double maxValue() {
         return 0.0;
      }

      @Override
      public net.minecraft.util.KeyDispatchDataCodec<? extends net.minecraft.world.level.levelgen.DensityFunction> codec() {
         throw new UnsupportedOperationException("not serialisable");
      }
   }

   /** The rule context reads only the preliminary surface from its noise chunk; answer it from our sampler. */
   private static final class EstimateOnlyNoiseChunk extends NoiseChunk {
      private final TerrainSampler terrain;

      EstimateOnlyNoiseChunk(SeedWorld world, TerrainSampler terrain) {
         super(1, world.randomState(), 0, 0, world.settings().noiseSettings(), NoStructureBlending.INSTANCE,
            world.settings(), (x, y, z) -> new Aquifer.FluidStatus(world.seaLevel(), world.settings().defaultFluid()), Blender.empty());
         this.terrain = terrain;
      }

      @Override
      public int preliminarySurfaceLevel(int x, int z) {
         return terrain.preliminarySurface(x, z);
      }
   }
}
