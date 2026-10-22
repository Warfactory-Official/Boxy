package com.golem.boxy.seedlod.column;

import com.golem.boxy.seedlod.world.SeedWorld;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * Biomes as generated chunks see them. Noise biomes (one per 4x4x4 quart) are memoised because the climate
 * sampler is costly and every block-level lookup reads eight of them; block-level lookups use vanilla's zoomed
 * {@link BiomeManager}, which is what surface rules and feature placement read. One instance per thread.
 */
public final class BiomeLookup implements BiomeManager.NoiseBiomeSource {
   private static final int MEMO_LIMIT = 1 << 16;
   private final BiomeSource source;
   private final Climate.Sampler sampler;
   private final BiomeManager zoomed;
   private final Long2ObjectOpenHashMap<Holder<Biome>> memo = new Long2ObjectOpenHashMap<>();
   private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

   public BiomeLookup(SeedWorld world) {
      this.source = world.biomeSource();
      this.sampler = world.randomState().sampler();
      this.zoomed = new BiomeManager(this, BiomeManager.obfuscateSeed(world.seed()));
   }

   @Override
   public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ) {
      long key = BlockPos.asLong(quartX, quartY, quartZ);
      Holder<Biome> known = memo.get(key);
      if (known == null) {
         if (memo.size() >= MEMO_LIMIT) {
            memo.clear();
         }
         known = source.getNoiseBiome(quartX, quartY, quartZ, sampler);
         memo.put(key, known);
      }
      return known;
   }

   /** The biome at a block, with vanilla's zoom jitter. */
   public Holder<Biome> at(int x, int y, int z) {
      return zoomed.getBiome(cursor.set(x, y, z));
   }

   public Holder<Biome> at(BlockPos pos) {
      return zoomed.getBiome(pos);
   }

   /** The biome of the quart containing a block, without jitter; cheap enough for coarse samples. */
   public Holder<Biome> quartAt(int x, int y, int z) {
      return getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
   }

   public BiomeManager manager() {
      return zoomed;
   }
}
