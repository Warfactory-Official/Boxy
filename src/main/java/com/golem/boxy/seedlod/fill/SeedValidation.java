package com.golem.boxy.seedlod.fill;

import com.golem.boxy.seedlod.SeedLods;
import com.golem.boxy.seedlod.column.ColumnRuns;
import com.golem.boxy.seedlod.world.SeedWorld;
import com.golem.boxy.seedlod.world.SeedWorlds;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Development check, off unless {@code -Dboxy.seedlod.validate=<chunks>} and started by the client smoke test
 * once its streaming checks are done (it holds the server thread while generating): generates real chunks with the integrated
 * server and compares them column by column with what the seed engine produces for the same positions. Reports
 * how often the ground height and ground block match, and how often the top including trees and plants does.
 * Chunks are spread over rings 24 to 88 chunks out, away from anything the player loaded.
 */
public final class SeedValidation {
   private static final Heightmap.Types GROUND = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
   private static final Heightmap.Types EVERYTHING = Heightmap.Types.WORLD_SURFACE;

   /** Set once a requested validation has logged its result (or failed). */
   public static volatile boolean finished;

   private SeedValidation() {
   }

   public static boolean requested() {
      return Integer.getInteger("boxy.seedlod.validate", 0) > 0;
   }

   private static volatile boolean started;

   public static synchronized void runIfAsked(MinecraftServer server) {
      int chunks = Integer.getInteger("boxy.seedlod.validate", 0);
      if (chunks <= 0 || started) {
         return;
      }
      started = true;
      Thread check = new Thread(() -> run(server, chunks), "Boxy-SeedLods-validate");
      check.setDaemon(true);
      check.start();
   }

   private record Real(int[] ground, int[] everything, BlockState[] groundBlock) {
   }

   private static void run(MinecraftServer server, int chunks) {
      try {
         ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
            net.minecraft.resources.ResourceLocation.withDefaultNamespace(System.getProperty("boxy.seedlod.validateDimension", "overworld"))));
         if (level == null) {
            SeedLods.LOGGER.warn("BOXY_SEEDLOD_VALIDATION skipped: no such dimension");
            return;
         }
         SeedWorld world = SeedWorlds.fromServer(level);
         if (world == null) {
            SeedLods.LOGGER.warn("BOXY_SEEDLOD_VALIDATION skipped: the overworld is not noise-generated");
            return;
         }
         TileBuilder probe = new TileBuilder(FillWorld.of(world), null);
         if (world.needsFullColumns()) {
            runInteriors(server, level, world, probe, chunks);
            return;
         }
         int columns = 0;
         int groundExact = 0;
         int groundNear = 0;
         long groundError = 0;
         int blockMatch = 0;
         int topExact = 0;
         int topNear = 0;
         long seedNanos = 0;
         long terrainNanos = 0;
         List<String> examples = new ArrayList<>();
         for (int i = 0; i < chunks; i++) {
            double angle = i * 2.399963229728653;
            int distance = 24 + (i * 64) / Math.max(1, chunks);
            int chunkX = (int)Math.round(Math.cos(angle) * distance);
            int chunkZ = (int)Math.round(Math.sin(angle) * distance);
            Real real = server.submit(() -> read(level.getChunk(chunkX, chunkZ))).join();
            long began = System.nanoTime();
            probe.probeChunk(chunkX, chunkZ, false);
            terrainNanos += System.nanoTime() - began;
            began = System.nanoTime();
            ColumnRuns[] seed = probe.probeChunk(chunkX, chunkZ, true);
            seedNanos += System.nanoTime() - began;
            for (int index = 0; index < 256; index++) {
               int ground = top(seed[index], GROUND.isOpaque());
               int everything = top(seed[index], EVERYTHING.isOpaque());
               int error = Math.abs(ground - real.ground()[index]);
               columns++;
               groundExact += error == 0 ? 1 : 0;
               groundNear += error <= 1 ? 1 : 0;
               groundError += error;
               boolean sameBlock = seed[index].stateAt(ground).getBlock() == real.groundBlock()[index].getBlock();
               blockMatch += sameBlock ? 1 : 0;
               int topError = Math.abs(everything - real.everything()[index]);
               topExact += topError == 0 ? 1 : 0;
               topNear += topError <= 2 ? 1 : 0;
               if ((error > 2 || !sameBlock) && examples.size() < 12) {
                  int x = (chunkX << 4) + (index & 15);
                  int z = (chunkZ << 4) + (index >> 4);
                  examples.add(String.format(Locale.ROOT, "(%d, %d) ground real %d %s, seed %d %s", x, z, real.ground()[index],
                     real.groundBlock()[index].getBlock(), ground, seed[index].stateAt(ground).getBlock()));
               }
            }
         }
         SeedLods.LOGGER.info(String.format(Locale.ROOT,
            "BOXY_SEEDLOD_VALIDATION %d chunks: ground height exact %.1f%%, within 1 %.1f%%, mean error %.2f; ground block %.1f%%;"
               + " top incl. trees exact %.1f%%, within 2 %.1f%%; seed %.1f ms per chunk terrain only, %.1f ms decorated",
            chunks, 100.0 * groundExact / columns, 100.0 * groundNear / columns, (double)groundError / columns,
            100.0 * blockMatch / columns, 100.0 * topExact / columns, 100.0 * topNear / columns, terrainNanos / 1.0e6 / chunks,
            seedNanos / 1.0e6 / chunks));
         for (String example : examples) {
            SeedLods.LOGGER.info("BOXY_SEEDLOD_VALIDATION mismatch {}", example);
         }
         checkSharedPath(server, level, world, probe);
         for (int round = 0; round < Integer.getInteger("boxy.seedlod.benchRounds", 3); round++) {
            benchmark(world);
         }
         com.golem.boxy.seedlod.decor.Decorator.failures().forEach((what, why) -> SeedLods.LOGGER.info("BOXY_SEEDLOD_VALIDATION left out {}: {}", what, why));
      } catch (RuntimeException | LinkageError failure) {
         SeedLods.LOGGER.error("BOXY_SEEDLOD_VALIDATION failed", failure);
      } finally {
         finished = true;
      }
   }

   /** Dimensions seen from inside: compare every block of every column. */
   private static void runInteriors(MinecraftServer server, ServerLevel level, SeedWorld world, TileBuilder probe, int chunks) {
      long blocks = 0;
      long solidAgree = 0;
      long exact = 0;
      long realSolid = 0;
      long seedNanos = 0;
      int minY = world.minY();
      int maxY = world.maxY();
      for (int i = 0; i < chunks; i++) {
         double angle = i * 2.399963229728653;
         int distance = 12 + (i * 48) / Math.max(1, chunks);
         int chunkX = (int)Math.round(Math.cos(angle) * distance);
         int chunkZ = (int)Math.round(Math.sin(angle) * distance);
         BlockState[] real = server.submit(() -> {
            net.minecraft.world.level.chunk.ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
            BlockState[] states = new BlockState[256 * (maxY - minY)];
            net.minecraft.core.BlockPos.MutableBlockPos at = new net.minecraft.core.BlockPos.MutableBlockPos();
            for (int index = 0; index < 256; index++) {
               for (int y = minY; y < maxY; y++) {
                  states[index * (maxY - minY) + y - minY] = chunk.getBlockState(at.set((chunkX << 4) + (index & 15), y, (chunkZ << 4) + (index >> 4)));
               }
            }
            return states;
         }).join();
         long began = System.nanoTime();
         ColumnRuns[] seed = probe.probeChunk(chunkX, chunkZ, true);
         seedNanos += System.nanoTime() - began;
         for (int index = 0; index < 256; index++) {
            for (int y = minY; y < maxY; y++) {
               BlockState r = real[index * (maxY - minY) + y - minY];
               BlockState g = seed[index].stateAt(y);
               blocks++;
               boolean rs = !r.isAir() && r.getFluidState().isEmpty();
               boolean gs = !g.isAir() && g.getFluidState().isEmpty();
               realSolid += rs ? 1 : 0;
               solidAgree += rs == gs ? 1 : 0;
               exact += r.getBlock() == g.getBlock() ? 1 : 0;
            }
         }
      }
      SeedLods.LOGGER.info(String.format(Locale.ROOT,
         "BOXY_SEEDLOD_VALIDATION %s %d chunks: solid/open agrees %.1f%% of blocks, same block %.1f%% (%.0f%% of real blocks solid); seed %.1f ms per chunk decorated",
         world.dimension().location(), chunks, 100.0 * solidAgree / blocks, 100.0 * exact / blocks, 100.0 * realSolid / blocks, seedNanos / 1.0e6 / chunks));
      com.golem.boxy.seedlod.decor.Decorator.failures().forEach((what, why) -> SeedLods.LOGGER.info("BOXY_SEEDLOD_VALIDATION left out {}: {}", what, why));
   }

   /**
    * Fixed workload for measuring speed: a 5 x 5 block of tiles, detail falling off from the centre (levels 0, 1,
    * 2, 3, 4 by ring), built twice in a row the way a moving player would refine them, without writing to Voxy.
    * The digest identifies the exact output, so a change meant to be exact must leave it unchanged.
    */
   private static void benchmark(SeedWorld world) {
      FillWorld kit = FillWorld.of(world);
      java.util.Arrays.fill(TileBuilder.PHASES, 0L);
      java.util.Arrays.fill(com.golem.boxy.seedlod.column.TerrainCache.SAMPLE_NANOS, 0L);
      TileBuilder builder = new TileBuilder(kit, null);
      long[] nanos = new long[5];
      int[] tiles = new int[5];
      for (int pass = 0; pass < 2; pass++) {
         for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
               int ring = Math.max(Math.abs(dx), Math.abs(dz));
               int level = Math.min(4, ring * 2 - pass);
               level = Math.max(0, level);
               long began = System.nanoTime();
               builder.build(20 + dx, -14 + dz, level, 0L, true);
               nanos[level] += System.nanoTime() - began;
               tiles[level]++;
            }
         }
      }
      StringBuilder line = new StringBuilder();
      long total = 0;
      for (int level = 0; level < 5; level++) {
         total += nanos[level];
         line.append(String.format(Locale.ROOT, " L%d %d tiles %.0f ms/tile;", level, tiles[level], tiles[level] == 0 ? 0.0 : nanos[level] / 1.0e6 / tiles[level]));
      }
      SeedLods.LOGGER.info(String.format(Locale.ROOT, "BOXY_SEEDLOD_BENCH total %.0f ms;%s digest %016x; %s", total / 1.0e6, line, builder.digest(),
         kit.decorated().describe()));
      long[] p = TileBuilder.PHASES;
      SeedLods.LOGGER.info(String.format(Locale.ROOT, "BOXY_SEEDLOD_BENCH phases: decorate %.0f ms, reduced columns %.0f ms, canopy %.0f ms, sparse %.0f ms, full %.0f ms;"
            + " of all that, exact terrain sampling %.0f ms and painting %.0f ms",
         p[0] / 1e6, p[1] / 1e6, p[2] / 1e6, p[3] / 1e6, p[4] / 1e6,
         com.golem.boxy.seedlod.column.TerrainCache.SAMPLE_NANOS[0] / 1e6, com.golem.boxy.seedlod.column.TerrainCache.SAMPLE_NANOS[1] / 1e6));
   }

   /**
    * The multiplayer path, checked in singleplayer: the server's generator encoded as a server shares it, rebuilt
    * from this client's own data packs, must fingerprint the same and produce the same columns.
    */
   private static void checkSharedPath(MinecraftServer server, ServerLevel level, SeedWorld world, TileBuilder direct) {
      java.util.Map<String, String> serverPrint = com.golem.boxy.seedlod.world.WorldgenFingerprint.of(server.registryAccess());
      com.golem.boxy.seedlod.world.LocalWorldgen local = com.golem.boxy.seedlod.world.LocalWorldgen.get();
      java.util.Map<String, String> localPrint = local == null ? java.util.Map.of() : com.golem.boxy.seedlod.world.WorldgenFingerprint.of(local.registries());
      String json = server.submit(() -> SeedWorlds.encodeGenerator(level)).join();
      SeedWorld rebuilt = SeedWorlds.fromShared(level.dimension(), world.seed(), json, world.dimensionType());
      TileBuilder shared = new TileBuilder(FillWorld.of(rebuilt), null);
      int same = 0;
      int total = 0;
      for (int i = 0; i < 16; i++) {
         int chunkX = 40 + i * 7;
         int chunkZ = -30 + i * 5;
         ColumnRuns[] a = direct.probeChunk(chunkX, chunkZ, true);
         ColumnRuns[] b = shared.probeChunk(chunkX, chunkZ, true);
         for (int index = 0; index < 256; index++) {
            total++;
            same += sameColumn(a[index], b[index]) ? 1 : 0;
         }
      }
      SeedLods.LOGGER.info(String.format(Locale.ROOT, "BOXY_SEEDLOD_SHARED fingerprints %s (differ: %s); shared generator columns identical %.1f%%",
         serverPrint.equals(localPrint) ? "match" : "DIFFER", com.golem.boxy.seedlod.world.WorldgenFingerprint.differences(serverPrint, localPrint),
         100.0 * same / total));
   }

   private static boolean sameColumn(ColumnRuns a, ColumnRuns b) {
      if (a.count() != b.count()) {
         return false;
      }
      for (int run = 0; run < a.count(); run++) {
         if (a.top(run) != b.top(run) || a.state(run) != b.state(run)) {
            return false;
         }
      }
      return true;
   }

   /** Heightmap tops and the ground block of a generated chunk; server thread. */
   private static Real read(net.minecraft.world.level.chunk.ChunkAccess access) {
      LevelChunk chunk = (LevelChunk)access;
      int[] ground = new int[256];
      int[] everything = new int[256];
      BlockState[] blocks = new BlockState[256];
      net.minecraft.core.BlockPos.MutableBlockPos at = new net.minecraft.core.BlockPos.MutableBlockPos();
      for (int index = 0; index < 256; index++) {
         int x = index & 15;
         int z = index >> 4;
         ground[index] = chunk.getHeight(GROUND, x, z);
         everything[index] = chunk.getHeight(EVERYTHING, x, z);
         blocks[index] = chunk.getBlockState(at.set(chunk.getPos().getMinBlockX() + x, ground[index], chunk.getPos().getMinBlockZ() + z));
      }
      return new Real(ground, everything, blocks);
   }

   /** Highest y whose block a heightmap would count, matching {@code ChunkAccess.getHeight}. */
   private static int top(ColumnRuns column, Predicate<BlockState> counts) {
      for (int run = column.count() - 1; run >= 0; run--) {
         if (counts.test(column.state(run))) {
            return column.top(run) - 1;
         }
      }
      return column.floor() - 1;
   }
}
