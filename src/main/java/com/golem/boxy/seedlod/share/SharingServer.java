package com.golem.boxy.seedlod.share;

import com.golem.boxy.seedlod.SeedLods;
import com.golem.boxy.seedlod.world.SeedWorlds;
import com.golem.boxy.seedlod.world.WorldgenFingerprint;
import com.golem.boxy.vss.config.VSSServerConfig;
import com.golem.boxy.vss.net.VssChannels;
import com.golem.boxy.vss.payloads.SeedRefusalS2CPayload;
import com.golem.boxy.vss.payloads.SeedRequestC2SPayload;
import com.golem.boxy.vss.payloads.SeedWorldS2CPayload;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/**
 * Server half of seed sharing. Never references client classes. A client asks once per login; the answer is
 * every noise-generated dimension's seed and generator when sharing is on and the client's worldgen fingerprint
 * equals ours, otherwise a refusal carrying our fingerprint (empty when sharing is off) and our extra packs.
 */
public final class SharingServer {
   private static MinecraftServer fingerprintedFor;
   private static Map<String, String> fingerprint;

   private SharingServer() {
   }

   /** Main thread. */
   public static void onRequest(ServerPlayer player, SeedRequestC2SPayload request) {
      MinecraftServer server = player.server;
      if (!VSSServerConfig.CONFIG.shareSeedWithClients) {
         VssChannels.sendToClient(player, new SeedRefusalS2CPayload(Map.of(), List.of()));
         return;
      }
      Map<String, String> ours = fingerprint(server);
      if (!ours.equals(request.fingerprint())) {
         SeedLods.LOGGER.info("Not sharing the seed with {}: their worldgen differs in {} (packs here {}, there {})",
            player.getName().getString(),
            WorldgenFingerprint.differences(ours, request.fingerprint()),
            extraPacks(server), request.extraPacks());
         VssChannels.sendToClient(player, new SeedRefusalS2CPayload(ours, extraPacks(server)));
         return;
      }
      int shared = 0;
      for (ServerLevel level : server.getAllLevels()) {
         if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator)) {
            continue;
         }
         try {
            VssChannels.sendToClient(player, new SeedWorldS2CPayload(level.dimension().location(), level.getSeed(), SeedWorlds.encodeGenerator(level)));
            shared++;
         } catch (RuntimeException failure) {
            SeedLods.LOGGER.warn("Cannot share {}: {}", level.dimension().location(), failure.getMessage());
         }
      }
      SeedLods.LOGGER.info("Shared the seed of {} dimension(s) with {}", shared, player.getName().getString());
   }

   private static synchronized Map<String, String> fingerprint(MinecraftServer server) {
      if (fingerprintedFor != server) {
         fingerprint = WorldgenFingerprint.of(server.registryAccess());
         fingerprintedFor = server;
      }
      return fingerprint;
   }

   /** Packs the world itself adds (its datapacks folder); clients need the same in their extra packs folder. */
   private static List<String> extraPacks(MinecraftServer server) {
      return server.getPackRepository().getSelectedPacks().stream()
         .filter(pack -> pack.getPackSource() == PackSource.WORLD)
         .map(Pack::getId)
         .toList();
   }
}
