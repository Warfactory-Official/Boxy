package com.golem.boxy.vss.client;

import com.golem.boxy.vss.common.VSSLogger;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;

public class ColumnCacheStore {
   private static final Pattern SANITIZE_PATTERN = Pattern.compile("[^a-zA-Z0-9._-]");
   private static final int FORMAT_VERSION = 4;
   private static final int MAX_CACHE_ENTRIES = 2000000;
   private static final Path CACHE_DIR = FMLPaths.CONFIGDIR.get().resolve("boxy").resolve("cache-1.21.1-v20");
   private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "VSS-CacheIO");
      t.setDaemon(true);
      return t;
   });

   public ColumnCacheStore() {
   }

   public static Long2LongOpenHashMap load(String serverAddress, ResourceKey<Level> dimension) {
      Long2LongOpenHashMap map = new Long2LongOpenHashMap();
      map.defaultReturnValue(-1L);
      Path file = getCacheFile(serverAddress, dimension);
      if (!Files.exists(file)) {
         return map;
      } else {
         try {
            Long2LongOpenHashMap result;
            try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
               int version = in.readInt();
                if (version != FORMAT_VERSION) {
                  VSSLogger.warn("Column cache " + file + " has unsupported version " + version + ", discarding");
                  return map;
               }

               int count = in.readInt();
               if (count >= 0 && count <= 2000000) {

                  for (int i = 0; i < count; i++) {
                     long pos = in.readLong();
                     long value = in.readLong();
                      map.put(pos, value);
                  }

                   VSSLogger.info("Loaded " + count + " cached column entries for " + dimensionKey(dimension));
                  return map;
               }

               VSSLogger.warn("Column cache " + file + " has invalid entry count " + count + ", discarding");
               result = map;
            }

            return result;
         } catch (IOException e) {
            VSSLogger.warn("Failed to load column cache from " + file, e);
            return map;
         }
      }
   }

   public static CompletableFuture<Long2LongOpenHashMap> loadAsync(String serverAddress, ResourceKey<Level> dimension) {
      return CompletableFuture.supplyAsync(() -> load(serverAddress, dimension), IO_EXECUTOR);
   }

   public static void saveAsync(String serverAddress, ResourceKey<Level> dimension, Long2LongOpenHashMap columns) {
      if (!columns.isEmpty()) {
         Long2LongOpenHashMap copy = new Long2LongOpenHashMap(columns);
         copy.defaultReturnValue(-1L);
         IO_EXECUTOR.execute(() -> save(serverAddress, dimension, copy));
      }
   }

   public static void save(String serverAddress, ResourceKey<Level> dimension, Long2LongOpenHashMap columns) {
      if (!columns.isEmpty()) {
         Path file = getCacheFile(serverAddress, dimension);
         Path tmpFile = file.resolveSibling(file.getFileName() + ".tmp");

         try {
            Files.createDirectories(file.getParent());

            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmpFile))) {
                out.writeInt(FORMAT_VERSION);
               out.writeInt(columns.size());
               ObjectIterator iter = columns.long2LongEntrySet().iterator();

               while (iter.hasNext()) {
                  Entry entry = (Entry)iter.next();
                  out.writeLong(entry.getLongKey());
                  out.writeLong(entry.getLongValue());
               }
            }

            Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            VSSLogger.info("Saved " + columns.size() + " cached column entries for " + dimensionKey(dimension));
         } catch (IOException e) {
            VSSLogger.warn("Failed to save column cache to " + file, e);

            try {
               Files.deleteIfExists(tmpFile);
            } catch (IOException cleanupError) {
               VSSLogger.warn("Failed to clean up temporary cache file " + tmpFile, cleanupError);
            }
         }
      }
   }

   public static void clearForServer(String serverAddress) {
      Path dir = getServerDir(serverAddress);
      if (Files.exists(dir)) {
         try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
               Files.deleteIfExists(file);
            }

            Files.deleteIfExists(dir);
            VSSLogger.info("Cleared column cache for server " + serverAddress);
         } catch (IOException e) {
            VSSLogger.warn("Failed to clear column cache for " + serverAddress, e);
         }
      }
   }

   public static void clearAll() {
      if (Files.exists(CACHE_DIR)) {
         try (DirectoryStream<Path> servers = Files.newDirectoryStream(CACHE_DIR)) {
            for (Path serverDir : servers) {
               if (Files.isDirectory(serverDir)) {
                  try (DirectoryStream<Path> files = Files.newDirectoryStream(serverDir)) {
                     for (Path file : files) {
                        Files.deleteIfExists(file);
                     }
                  }

                  Files.deleteIfExists(serverDir);
               }
            }

            VSSLogger.info("Cleared all column caches");
         } catch (IOException e) {
            VSSLogger.warn("Failed to clear all column caches", e);
         }
      }
   }

   private static Path getServerDir(String serverAddress) {
      return CACHE_DIR.resolve(sanitizeForFilePath(serverAddress));
   }

   private static Path getCacheFile(String serverAddress, ResourceKey<Level> dimension) {
      return getServerDir(serverAddress).resolve(dimensionKey(dimension) + ".bin");
   }

   private static String dimensionKey(ResourceKey<Level> dimension) {
      return sanitizeForFilePath(dimension.location().toString());
   }

   static String sanitizeForFilePath(String name) {
      return SANITIZE_PATTERN.matcher(name).replaceAll("_");
   }
}
