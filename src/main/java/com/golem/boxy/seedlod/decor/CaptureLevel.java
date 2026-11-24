package com.golem.boxy.seedlod.decor;

import com.golem.boxy.seedlod.column.BiomeLookup;
import com.golem.boxy.seedlod.column.ColumnRuns;
import com.golem.boxy.seedlod.column.TerrainCache;
import com.golem.boxy.seedlod.world.SeedWorld;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;

/**
 * The world vanilla features and structure pieces write into while a tile is decorated. Reads come from what has
 * been placed so far, else from painted terrain ({@link TerrainCache}); writes are only recorded. Anything a
 * feature asks for that a seed cannot answer (entities, block entities, the server, other chunks) is absent, and
 * features that insist on it fail and are skipped by the caller. One per thread.
 */
public final class CaptureLevel implements WorldGenLevel {
   private static final BlockState AIR = Blocks.AIR.defaultBlockState();
   private final SeedWorld world;
   private final TerrainCache terrain;
   private final BiomeLookup biomes;
   private final RandomSource random = RandomSource.create();
   private final WorldBorder border = new WorldBorder();
   private final DifficultyInstance difficulty = new DifficultyInstance(Difficulty.NORMAL, 0L, 0L, 0.0F);
   private final Long2ObjectOpenHashMap<Int2ObjectOpenHashMap<BlockState>> placed = new Long2ObjectOpenHashMap<>();
   private final ChunkAccess sink;
   private it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<BlockState> written;

   public CaptureLevel(SeedWorld world, TerrainCache terrain, BiomeLookup biomes) {
      this.world = world;
      this.terrain = terrain;
      this.biomes = biomes;
      this.sink = new SinkChunk(world);
   }

   /** Forget every placed block. */
   public void clearPlaced() {
      placed.clear();
      written = null;
   }

   /** From now until {@link #takeWritten}, also record every block features place. */
   public void recordWrites() {
      written = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
   }

   /** Blocks placed since {@link #recordWrites}, last write per position. */
   public ChunkDecoration takeWritten() {
      it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<BlockState> taken = written;
      written = null;
      return ChunkDecoration.of(taken);
   }

   /** Adds a block without recording it, e.g. a neighbour's earlier decoration. */
   public void addPlaced(int x, int y, int z, BlockState state) {
      placed.computeIfAbsent(ChunkPos.asLong(x, z), ignored -> new Int2ObjectOpenHashMap<>()).put(y, state);
   }

   /** Placed blocks by column ({@link ChunkPos#asLong} of x, z) and y. */
   public Long2ObjectMap<Int2ObjectOpenHashMap<BlockState>> placed() {
      return placed;
   }

   @Nullable
   private BlockState placedAt(int x, int y, int z) {
      Int2ObjectOpenHashMap<BlockState> column = placed.get(ChunkPos.asLong(x, z));
      return column == null ? null : column.get(y);
   }

   @Override
   public BlockState getBlockState(BlockPos pos) {
      BlockState mine = placedAt(pos.getX(), pos.getY(), pos.getZ());
      if (mine != null) {
         return mine;
      }
      if (isOutsideBuildHeight(pos)) {
         return AIR;
      }
      return terrain.painted(pos.getX(), pos.getZ()).stateAt(pos.getY());
   }

   @Override
   public FluidState getFluidState(BlockPos pos) {
      return getBlockState(pos).getFluidState();
   }

   @Override
   public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> test) {
      return test.test(getBlockState(pos));
   }

   @Override
   public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> test) {
      return test.test(getFluidState(pos));
   }

   @Override
   public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
      if (isOutsideBuildHeight(pos)) {
         return false;
      }
      placed.computeIfAbsent(ChunkPos.asLong(pos.getX(), pos.getZ()), ignored -> new Int2ObjectOpenHashMap<>()).put(pos.getY(), state);
      if (written != null) {
         written.put(pos.asLong(), state);
      }
      return true;
   }

   @Override
   public boolean removeBlock(BlockPos pos, boolean movedByPiston) {
      return setBlock(pos, getFluidState(pos).createLegacyBlock(), 3, 512);
   }

   @Override
   public boolean destroyBlock(BlockPos pos, boolean dropItems, @Nullable Entity breaker, int recursionLeft) {
      return setBlock(pos, getFluidState(pos).createLegacyBlock(), 3, recursionLeft);
   }

   @Override
   public int getHeight(Heightmap.Types type, int x, int z) {
      Predicate<BlockState> counts = type.isOpaque();
      ColumnRuns column = terrain.painted(x, z);
      Int2ObjectOpenHashMap<BlockState> mine = placed.get(ChunkPos.asLong(x, z));
      int y = column.height() - 1;
      if (mine != null && !mine.isEmpty()) {
         int lowest = Integer.MAX_VALUE;
         for (int key : mine.keySet()) {
            y = Math.max(y, key);
            lowest = Math.min(lowest, key);
         }
         // Where placed blocks are, look block by block; they can replace terrain as well as sit on it.
         for (; y >= lowest; y--) {
            BlockState state = mine.get(y);
            if (counts.test(state != null ? state : column.stateAt(y))) {
               return y + 1;
            }
         }
      }
      for (int run = column.count() - 1; run >= 0; run--) {
         if (column.bottom(run) <= y && counts.test(column.state(run))) {
            return Math.min(column.top(run), y + 1);
         }
      }
      return getMinBuildHeight();
   }

   @Override
   public BiomeManager getBiomeManager() {
      return biomes.manager();
   }

   @Override
   public Holder<Biome> getUncachedNoiseBiome(int quartX, int quartY, int quartZ) {
      return biomes.getNoiseBiome(quartX, quartY, quartZ);
   }

   @Override
   public long getSeed() {
      return world.seed();
   }

   public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension() {
      return world.dimension();
   }

   @Override
   public int getSeaLevel() {
      return world.seaLevel();
   }

   @Override
   public DimensionType dimensionType() {
      return world.dimensionType();
   }

   @Override
   public int getMinBuildHeight() {
      return world.dimensionType().minY();
   }

   @Override
   public int getHeight() {
      return world.dimensionType().height();
   }

   @Override
   public RegistryAccess registryAccess() {
      return world.registries();
   }

   @Override
   public FeatureFlagSet enabledFeatures() {
      return FeatureFlags.REGISTRY.allFlags();
   }

   @Override
   public RandomSource getRandom() {
      return random;
   }

   @Override
   public boolean ensureCanWrite(BlockPos pos) {
      return true;
   }

   @Override
   public void setCurrentlyGenerating(@Nullable Supplier<String> description) {
   }

   // What follows has no meaning for a seed-only world.

   @Override
   public ServerLevel getLevel() {
      throw new UnsupportedOperationException("seed LOD decoration has no server level");
   }

   @Nullable
   @Override
   public MinecraftServer getServer() {
      return null;
   }

   @Nullable
   @Override
   public BlockEntity getBlockEntity(BlockPos pos) {
      return null;
   }

   /**
    * A chunk that absorbs writes. Each section request gets a fresh empty section: features that lock sections
    * (ores) and fail before unlocking would otherwise leave a shared section locked for the next caller.
    */
   private static final class SinkChunk extends net.minecraft.world.level.chunk.ProtoChunk {
      private final net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomeRegistry;

      SinkChunk(SeedWorld world) {
         super(ChunkPos.ZERO, net.minecraft.world.level.chunk.UpgradeData.EMPTY, world.heightAccessor(),
            world.registries().registryOrThrow(net.minecraft.core.registries.Registries.BIOME), null);
         this.biomeRegistry = world.registries().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
      }

      @Override
      public net.minecraft.world.level.chunk.LevelChunkSection getSection(int index) {
         return new net.minecraft.world.level.chunk.LevelChunkSection(biomeRegistry);
      }
   }

   /**
    * Pieces and features ask for a chunk mostly to flag blocks for later shape updates (fences, walls) or to
    * read a carving mask. One blank chunk absorbs those; anything written into it is not part of the result.
    */
   @Override
   public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
      return sink;
   }

   @Override
   public boolean hasChunk(int x, int z) {
      return false;
   }

   @Override
   public ChunkSource getChunkSource() {
      throw new UnsupportedOperationException("seed LOD decoration has no chunk source");
   }

   @Override
   public LevelTickAccess<Block> getBlockTicks() {
      return BlackholeTickAccess.emptyLevelList();
   }

   @Override
   public LevelTickAccess<Fluid> getFluidTicks() {
      return BlackholeTickAccess.emptyLevelList();
   }

   @Override
   public long nextSubTickCount() {
      return 0L;
   }

   @Override
   public LevelData getLevelData() {
      return FRESH_WORLD;
   }

   /** A world at its first tick, in clear weather on normal difficulty; some features consult it. */
   private static final LevelData FRESH_WORLD = new LevelData() {
      private final net.minecraft.world.level.GameRules rules = new net.minecraft.world.level.GameRules();

      @Override
      public BlockPos getSpawnPos() {
         return BlockPos.ZERO;
      }

      @Override
      public float getSpawnAngle() {
         return 0.0F;
      }

      @Override
      public long getGameTime() {
         return 0L;
      }

      @Override
      public long getDayTime() {
         return 0L;
      }

      @Override
      public boolean isThundering() {
         return false;
      }

      @Override
      public boolean isRaining() {
         return false;
      }

      @Override
      public void setRaining(boolean raining) {
      }

      @Override
      public boolean isHardcore() {
         return false;
      }

      @Override
      public net.minecraft.world.level.GameRules getGameRules() {
         return rules;
      }

      @Override
      public Difficulty getDifficulty() {
         return Difficulty.NORMAL;
      }

      @Override
      public boolean isDifficultyLocked() {
         return false;
      }
   };

   @Override
   public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
      return difficulty;
   }

   @Override
   public LevelLightEngine getLightEngine() {
      throw new UnsupportedOperationException("seed LOD decoration has no light engine");
   }

   /** Fresh terrain: no block light anywhere, open sky everywhere (what freezing and snow ask about). */
   @Override
   public int getBrightness(net.minecraft.world.level.LightLayer layer, BlockPos pos) {
      return layer == net.minecraft.world.level.LightLayer.SKY && world.dimensionType().hasSkyLight() ? 15 : 0;
   }

   @Override
   public int getRawBrightness(BlockPos pos, int darkening) {
      return world.dimensionType().hasSkyLight() ? Math.max(0, 15 - darkening) : 0;
   }

   @Override
   public int getSkyDarken() {
      return 0;
   }

   @Override
   public float getShade(Direction direction, boolean shade) {
      return 1.0F;
   }

   @Override
   public WorldBorder getWorldBorder() {
      return border;
   }

   @Override
   public boolean isClientSide() {
      return false;
   }

   @Override
   public boolean addFreshEntity(Entity entity) {
      return false;
   }

   @Override
   public void playSound(@Nullable Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
   }

   @Override
   public void addParticle(ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
   }

   @Override
   public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
   }

   @Override
   public void gameEvent(Holder<GameEvent> event, Vec3 position, GameEvent.Context context) {
   }

   @Override
   public List<Entity> getEntities(@Nullable Entity except, AABB area, @Nullable Predicate<? super Entity> filter) {
      return List.of();
   }

   @Override
   public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> type, AABB area, Predicate<? super T> filter) {
      return List.of();
   }

   @Override
   public List<? extends Player> players() {
      return List.of();
   }
}
