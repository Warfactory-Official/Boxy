package com.golem.boxy.seedlod.fill;

import com.golem.boxy.seedlod.SeedLods;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Which tiles a fill already wrote, and at what level, kept between sessions so rejoining a world does not
 * rewrite all of it. One file per Voxy world, dimension and seed. Before trusting a file, a sample of its tiles
 * is checked against Voxy: if Voxy no longer has data there (its storage was cleared), the file is ignored.
 */
final class FillMemory {
   /** Bump whenever seed output changes, so terrain written by an older engine is redone. */
   private static final int ENGINE_VERSION = 2;
   private static final int MAGIC = 0x42534C44;
   private static final int SAMPLES = 12;
   private final Path file;
   private final long seed;

   FillMemory(String voxyWorld, ResourceKey<Level> dimension, long seed) {
      this.seed = seed;
      this.file = FMLPaths.GAMEDIR.get().resolve("boxy").resolve("seedlod-progress").resolve(safe(voxyWorld))
         .resolve(safe(dimension.location().toString()) + "-" + Long.toHexString(seed) + ".bin");
   }

   private static String safe(String name) {
      return name.replaceAll("[^a-zA-Z0-9._-]", "_");
   }

   /** The remembered tiles, or an empty map when there is no usable record. */
   Long2ByteOpenHashMap load(WorldEngine engine, int minY, int maxY) {
      Long2ByteOpenHashMap tiles = new Long2ByteOpenHashMap();
      if (!Files.isRegularFile(file)) {
         return tiles;
      }
      try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
         if (in.readInt() != MAGIC || in.readInt() != ENGINE_VERSION || in.readLong() != seed) {
            return tiles;
         }
         int count = in.readInt();
         for (int i = 0; i < count; i++) {
            tiles.put(in.readLong(), in.readByte());
         }
      } catch (IOException | RuntimeException failure) {
         SeedLods.LOGGER.warn("Seed LOD progress {} unreadable, starting over: {}", file, failure.toString());
         tiles.clear();
         return tiles;
      }
      if (!stillInVoxy(engine, tiles, minY, maxY)) {
         SeedLods.LOGGER.info("Seed LOD progress for {} no longer matches Voxy's data (cleared?); starting over", file.getFileName());
         tiles.clear();
      }
      return tiles;
   }

   /** Every write reaches level 4, so a remembered tile must have non-air data in its level-4 section. */
   private static boolean stillInVoxy(WorldEngine engine, Long2ByteOpenHashMap tiles, int minY, int maxY) {
      if (tiles.isEmpty()) {
         return true;
      }
      List<Long> keys = new ArrayList<>(tiles.keySet());
      int step = Math.max(1, keys.size() / SAMPLES);
      int checked = 0;
      int present = 0;
      for (int i = 0; i < keys.size() && checked < SAMPLES; i += step) {
         long tile = keys.get(i);
         checked++;
         if (hasLevel4Data(engine, ChunkPos.getX(tile) >> 2, ChunkPos.getZ(tile) >> 2, minY, maxY)) {
            present++;
         }
      }
      return present * 2 >= checked;
   }

   private static boolean hasLevel4Data(WorldEngine engine, int x, int z, int minY, int maxY) {
      for (int y = minY >> 9; y <= (maxY - 1) >> 9; y++) {
         WorldSection section = engine.acquireIfExists(4, x, y, z);
         if (section == null) {
            continue;
         }
         try {
            for (long voxel : section._unsafeGetRawDataArray()) {
               if (!Mapper.isAir(voxel)) {
                  return true;
               }
            }
         } finally {
            section.release();
         }
      }
      return false;
   }

   /** Writes the record atomically (a crash leaves the previous one). */
   void save(Long2ByteMap tiles) {
      try {
         Files.createDirectories(file.getParent());
         Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
         try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
            out.writeInt(MAGIC);
            out.writeInt(ENGINE_VERSION);
            out.writeLong(seed);
            out.writeInt(tiles.size());
            for (Long2ByteMap.Entry entry : tiles.long2ByteEntrySet()) {
               out.writeLong(entry.getLongKey());
               out.writeByte(entry.getByteValue());
            }
         }
         Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException | RuntimeException failure) {
         SeedLods.LOGGER.warn("Could not save seed LOD progress {}: {}", file, failure.toString());
      }
   }
}
