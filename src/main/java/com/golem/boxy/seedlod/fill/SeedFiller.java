package com.golem.boxy.seedlod.fill;

import com.golem.boxy.seedlod.SeedLods;
import com.golem.boxy.seedlod.world.SeedWorld;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyInstance;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Keeps Voxy filled from the seed around the player in one dimension. A coordinator thread re-plans a few times
 * a second: every tile within reach gets the detail level its distance needs ({@link DetailLevels}), nearest
 * first, and is rebuilt only when it needs finer detail than it has. Worker threads build tiles; Voxy's save
 * queue throttles them. At most one filler runs at a time.
 */
public final class SeedFiller {
   private static final long PLAN_INTERVAL_MILLIS = 250L;
   private static final byte NOTHING_WRITTEN = 5;
   /** Smoothed integrated-server tick time above which the fill pauses; a tick is due every 50 ms. */
   private static final float HOST_BUSY_MILLIS = 35.0F;
   private static volatile SeedFiller current;
   private final FillWorld kit;
   private final VoxyInstance voxy;
   private final WorldEngine engine;
   private final int threads;
   private final int radiusChunks;
   private final boolean decorate;
   private final ExecutorService workers;
   private final ThreadLocal<TileBuilder> builders;
   private final Long2ByteOpenHashMap written = new Long2ByteOpenHashMap();
   private final LongOpenHashSet inFlight = new LongOpenHashSet();
   private final FillMemory memory;
   private static final long SAVE_INTERVAL_NANOS = 60_000_000_000L;
   private final Thread coordinator;
   private final AtomicLong tilesBuilt = new AtomicLong();
   private final AtomicLong buildNanos = new AtomicLong();
   private final AtomicInteger failures = new AtomicInteger();
   private final long startedAt = System.nanoTime();
   private volatile boolean stopping;
   private volatile double playerX;
   private volatile double playerZ;
   private volatile int viewChunks;
   private volatile double focal;
   private volatile boolean placed;
   private volatile int remembered;

   private SeedFiller(SeedWorld world, VoxyInstance voxy, WorldEngine engine, String voxyWorld, int threads, int radiusChunks, boolean decorate) {
      this.kit = FillWorld.of(world);
      this.memory = new FillMemory(voxyWorld, world.dimension(), world.seed());
      this.voxy = voxy;
      this.engine = engine;
      this.threads = threads;
      this.radiusChunks = radiusChunks;
      this.decorate = decorate;
      AtomicInteger names = new AtomicInteger();
      this.workers = Executors.newFixedThreadPool(threads, task -> {
         Thread thread = new Thread(task, "Boxy-SeedLods-" + names.incrementAndGet());
         thread.setDaemon(true);
         thread.setPriority(Thread.MIN_PRIORITY + 1);
         return thread;
      });
      this.builders = ThreadLocal.withInitial(() -> new TileBuilder(kit, engine));
      this.coordinator = new Thread(this::coordinate, "Boxy-SeedLods-plan");
      this.coordinator.setDaemon(true);
   }

   /** Starts filling {@code world} into {@code engine} (whose reference the filler now owns), replacing any running filler. */
   public static synchronized void start(SeedWorld world, VoxyInstance voxy, WorldEngine engine, String voxyWorld, int threads, int radiusChunks,
                                         boolean decorate) {
      stop();
      SeedFiller filler = new SeedFiller(world, voxy, engine, voxyWorld, threads, radiusChunks, decorate);
      current = filler;
      filler.coordinator.start();
      SeedLods.LOGGER.info("Seed LODs filling {} within {} chunks on {} threads", world, radiusChunks, threads);
   }

   /** Stops the running filler, waiting briefly for in-flight tiles so Voxy is not written to after it closes. */
   public static synchronized void stop() {
      SeedFiller filler = current;
      current = null;
      if (filler != null) {
         filler.shutdown();
      }
   }

   public static boolean runningIn(ResourceKey<Level> dimension) {
      SeedFiller filler = current;
      return filler != null && filler.kit.world().dimension().equals(dimension);
   }

   public static boolean running() {
      return current != null;
   }

   /** Client thread: where the player is and what the view looks like. */
   public static void observe(double x, double z, int renderDistanceChunks, double focalPixels) {
      SeedFiller filler = current;
      if (filler != null) {
         filler.playerX = x;
         filler.playerZ = z;
         filler.viewChunks = renderDistanceChunks;
         filler.focal = focalPixels;
         filler.placed = true;
      }
   }

   /** Tiles built and failed by the running filler, or null when none runs. */
   public record Progress(long tiles, int failures, int remembered) {
   }

   public static Progress progress() {
      SeedFiller filler = current;
      return filler == null ? null : new Progress(filler.tilesBuilt.get(), filler.failures.get(), filler.remembered);
   }

   public static String status() {
      SeedFiller filler = current;
      return filler == null ? "Seed LODs are not running." : filler.describe();
   }

   private String describe() {
      SeedFiller filler = this;
      long tiles = filler.tilesBuilt.get();
      double seconds = (System.nanoTime() - filler.startedAt) / 1.0e9;
      int pending;
      synchronized (filler.inFlight) {
         pending = filler.inFlight.size();
      }
      return String.format(Locale.ROOT, "Seed LODs in %s: %,d tiles built in %.0f s (%.1f ms each), %d in progress, %d failed.",
         filler.kit.world(), tiles, seconds, tiles == 0 ? 0.0 : filler.buildNanos.get() / 1.0e6 / tiles, pending, filler.failures.get());
   }

   private void shutdown() {
      SeedLods.LOGGER.info(describe());
      stopping = true;
      coordinator.interrupt();
      workers.shutdownNow();
      try {
         if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
            SeedLods.LOGGER.warn("Seed LOD workers still busy after 10 s of stopping");
         }
         coordinator.join(2000L);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      } finally {
         saveProgress();
         engine.releaseRef();
      }
   }

   private void coordinate() {
      try {
         Long2ByteOpenHashMap remembered = memory.load(engine, kit.world().minY(), kit.world().maxY());
         synchronized (inFlight) {
            written.putAll(remembered);
         }
         this.remembered = remembered.size();
         if (!remembered.isEmpty()) {
            SeedLods.LOGGER.info("Seed LODs remember {} tiles from earlier sessions", remembered.size());
         }
         long savedAt = System.nanoTime();
         while (!stopping) {
            if (placed) {
               plan();
            }
            if (System.nanoTime() - savedAt > SAVE_INTERVAL_NANOS) {
               saveProgress();
               savedAt = System.nanoTime();
            }
            Thread.sleep(PLAN_INTERVAL_MILLIS);
         }
      } catch (InterruptedException ignored) {
         // Stopping.
      } catch (RuntimeException | LinkageError failure) {
         SeedLods.LOGGER.error("Seed LOD planning failed; the fill stops", failure);
      }
   }

   private void saveProgress() {
      Long2ByteOpenHashMap snapshot;
      synchronized (inFlight) {
         snapshot = new Long2ByteOpenHashMap(written);
      }
      if (!snapshot.isEmpty()) {
         memory.save(snapshot);
      }
   }

   private record Wanted(long tile, int level, double distance, boolean demanded) {
   }

   private void plan() {
      double centerX = playerX;
      double centerZ = playerZ;
      double voxyReach = DetailLevels.voxyReachBlocks();
      double reach = voxyReach > 0.0 ? Math.min(radiusChunks * 16.0, voxyReach) : radiusChunks * 16.0;
      double fullDetail = DetailLevels.fullDetailReach(focal, DetailLevels.subdivisionPixels());
      int minTileX = (int)Math.floor((centerX - reach) / TileBuilder.TILE_BLOCKS);
      int maxTileX = (int)Math.floor((centerX + reach) / TileBuilder.TILE_BLOCKS);
      int minTileZ = (int)Math.floor((centerZ - reach) / TileBuilder.TILE_BLOCKS);
      int maxTileZ = (int)Math.floor((centerZ + reach) / TileBuilder.TILE_BLOCKS);
      List<Wanted> wanted = new ArrayList<>();
      Long2ByteOpenHashMap demanded = VoxyDemand.drain();
      synchronized (inFlight) {
         int room = threads * 2 - inFlight.size();
         if (room <= 0) {
            return;
         }
         for (int tz = minTileZ; tz <= maxTileZ; tz++) {
            for (int tx = minTileX; tx <= maxTileX; tx++) {
               double distance = distanceToTile(centerX, centerZ, tx, tz);
               if (distance > reach) {
                  continue;
               }
               long tile = ChunkPos.asLong(tx, tz);
               int byDistance = DetailLevels.levelAt(distance, fullDetail);
               int level = Math.min(byDistance, demanded.getOrDefault(tile, NOTHING_WRITTEN));
               if (level < written.getOrDefault(tile, NOTHING_WRITTEN) && !inFlight.contains(tile)) {
                  wanted.add(new Wanted(tile, level, distance, level < byDistance));
               }
            }
         }
         // What Voxy is already asking for first, then nearest first.
         wanted.sort((a, b) -> a.demanded() != b.demanded() ? (a.demanded() ? -1 : 1) : Double.compare(a.distance(), b.distance()));
         int view = viewChunks;
         int playerChunkX = (int)Math.floor(centerX) >> 4;
         int playerChunkZ = (int)Math.floor(centerZ) >> 4;
         for (int i = 0; i < Math.min(room, wanted.size()); i++) {
            Wanted next = wanted.get(i);
            inFlight.add(next.tile());
            workers.execute(() -> buildTile(next, playerChunkX, playerChunkZ, view));
         }
      }
   }

   private static double distanceToTile(double x, double z, int tileX, int tileZ) {
      double minX = tileX * (double)TileBuilder.TILE_BLOCKS;
      double minZ = tileZ * (double)TileBuilder.TILE_BLOCKS;
      double dx = Math.max(0.0, Math.max(minX - x, x - (minX + TileBuilder.TILE_BLOCKS)));
      double dz = Math.max(0.0, Math.max(minZ - z, z - (minZ + TileBuilder.TILE_BLOCKS)));
      return Math.sqrt(dx * dx + dz * dz);
   }

   private void buildTile(Wanted job, int playerChunkX, int playerChunkZ, int viewChunks) {
      int tileX = ChunkPos.getX(job.tile());
      int tileZ = ChunkPos.getZ(job.tile());
      try {
         if (!engine.isLive()) {
            return;
         }
         waitForVoxy();
         SeedWorld world = kit.world();
         long skip = VoxyPresence.chunksWithDetail(engine, tileX, tileZ, world.minY(), world.maxY()) | inView(tileX, tileZ, playerChunkX, playerChunkZ, viewChunks);
         if (skip != -1L && !stopping) {
            long began = System.nanoTime();
            builders.get().build(tileX, tileZ, job.level(), skip, decorate);
            buildNanos.addAndGet(System.nanoTime() - began);
            tilesBuilt.incrementAndGet();
         }
         synchronized (inFlight) {
            byte had = written.getOrDefault(job.tile(), NOTHING_WRITTEN);
            written.put(job.tile(), (byte)Math.min(had, job.level()));
         }
      } catch (RuntimeException | LinkageError failure) {
         if (failures.getAndIncrement() == 0) {
            SeedLods.LOGGER.error("A seed LOD tile failed to build; further failures are counted in /boxyseed status", failure);
         }
      } finally {
         synchronized (inFlight) {
            inFlight.remove(job.tile());
         }
      }
   }

   private static boolean hostBehind() {
      net.minecraft.client.server.IntegratedServer server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
      return server != null && server.isRunning() && server.getCurrentSmoothedTickTime() > HOST_BUSY_MILLIS;
   }

   /** Chunks the client will load itself (Voxy ingests those), which the fill leaves to it. */
   private static long inView(int tileX, int tileZ, int playerChunkX, int playerChunkZ, int viewChunks) {
      long mask = 0L;
      for (int dz = 0; dz < TileBuilder.TILE_CHUNKS; dz++) {
         for (int dx = 0; dx < TileBuilder.TILE_CHUNKS; dx++) {
            int chunkX = tileX * TileBuilder.TILE_CHUNKS + dx;
            int chunkZ = tileZ * TileBuilder.TILE_CHUNKS + dz;
            if (Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ)) <= viewChunks) {
               mask |= 1L << (dz * TileBuilder.TILE_CHUNKS + dx);
            }
         }
      }
      return mask;
   }

   /**
    * Waits while Voxy's save queue is full, and in singleplayer while the integrated server is behind: seed
    * terrain is a stand-in and must never slow the real thing (the server is what streams and generates it).
    */
   private void waitForVoxy() {
      try {
         while (!stopping && (!voxy.savingServiceRateLimiter.getAsBoolean() || hostBehind())) {
            Thread.sleep(hostBehind() ? 50L : 5L);
         }
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      }
   }
}
