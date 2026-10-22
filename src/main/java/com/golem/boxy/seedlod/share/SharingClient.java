package com.golem.boxy.seedlod.share;

import com.golem.boxy.seedlod.SeedLods;
import com.golem.boxy.seedlod.SeedLodsConfig;
import com.golem.boxy.seedlod.world.LocalWorldgen;
import com.golem.boxy.seedlod.world.SeedWorld;
import com.golem.boxy.seedlod.world.SeedWorlds;
import com.golem.boxy.seedlod.world.WorldgenFingerprint;
import com.golem.boxy.vss.net.VssChannels;
import com.golem.boxy.vss.payloads.SeedRefusalS2CPayload;
import com.golem.boxy.vss.payloads.SeedRequestC2SPayload;
import com.golem.boxy.vss.payloads.SeedWorldS2CPayload;
import com.golem.boxy.vss.payloads.VssPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Client half of seed sharing, plus seeds typed in by hand for servers without Boxy. Knows, per dimension,
 * where a seed came from and turns it into a {@link SeedWorld} on demand.
 */
public final class SharingClient {
   private record Shared(long seed, String generator) {
   }

   private static final Map<ResourceKey<Level>, Shared> SHARED = new ConcurrentHashMap<>();
   private static final Map<ResourceKey<Level>, SeedWorld> BUILT = new ConcurrentHashMap<>();
   private static volatile Long typedSeed;

   private SharingClient() {
   }

   /** On login: read local worldgen in the background, then ask a Boxy server for its seed. */
   public static void loggedIn() {
      if (!SeedLodsConfig.get().useServerSeeds || Minecraft.getInstance().hasSingleplayerServer()
         || !VssChannels.serverHas(VssPayload.SEED_REQUEST)) {
         return;
      }
      CompletableFuture.supplyAsync(() -> {
         LocalWorldgen local = LocalWorldgen.get();
         return local == null ? null : new SeedRequestC2SPayload(
            WorldgenFingerprint.of(local.registries()), local.extraPacks());
      }).thenAccept(request -> {
         if (request != null) {
            Minecraft.getInstance().execute(() -> VssChannels.sendToServer(request));
         }
      });
   }

   public static void onWorld(SeedWorldS2CPayload payload) {
      ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, payload.dimension());
      SHARED.put(dimension, new Shared(payload.seed(), payload.generator()));
      BUILT.remove(dimension);
      SeedLods.LOGGER.info("This server shared the seed of {}", payload.dimension());
   }

   public static void onRefusal(SeedRefusalS2CPayload payload) {
      if (payload.fingerprint().isEmpty()) {
         SeedLods.LOGGER.info("This server does not share its seed (shareSeedWithClients is off)");
         return;
      }
      LocalWorldgen local = LocalWorldgen.get();
      List<String> differ = local == null ? List.of("everything")
         : WorldgenFingerprint.differences(WorldgenFingerprint.of(local.registries()), payload.fingerprint());
      StringBuilder message = new StringBuilder("Seed LODs are off here: this server's terrain data differs from yours (")
         .append(String.join(", ", differ)).append(").");
      List<String> theirPacks = new ArrayList<>(payload.serverPacks());
      if (local != null) {
         theirPacks.removeAll(local.extraPacks());
      }
      if (!theirPacks.isEmpty()) {
         message.append(" It runs data packs you lack: ").append(String.join(", ", theirPacks))
            .append(". Put them in the ").append(LocalWorldgen.EXTRA_PACKS).append(" folder.");
      }
      say(message.toString());
   }

   /** {@code /boxyseed serverseed}: use a seed for a server that cannot share one (vanilla worldgen only). */
   public static String useTypedSeed(long seed) {
      typedSeed = seed;
      BUILT.clear();
      return "Seed " + seed + " will be used for this server's overworld, nether and end, assuming vanilla terrain.";
   }

   /** The world to draw for a dimension, or null when no seed is known for it. Builds and caches it. */
   public static SeedWorld worldFor(ResourceKey<Level> dimension, DimensionType type) {
      SeedWorld built = BUILT.get(dimension);
      if (built != null) {
         return built;
      }
      Shared shared = SHARED.get(dimension);
      Long typed = typedSeed;
      if (shared == null && typed == null) {
         return null;
      }
      try {
         built = shared != null
            ? SeedWorlds.fromShared(dimension, shared.seed(), shared.generator(), type)
            : SeedWorlds.fromSeedOnly(dimension, typed, type);
      } catch (RuntimeException failure) {
         SeedLods.LOGGER.warn("Seed LODs cannot draw {}: {}", dimension.location(), failure.getMessage());
         built = null;
      }
      if (built != null) {
         BUILT.put(dimension, built);
      } else {
         SHARED.remove(dimension);
      }
      return built;
   }

   public static boolean knows(ResourceKey<Level> dimension) {
      return SHARED.containsKey(dimension) || typedSeed != null;
   }

   public static void forget() {
      SHARED.clear();
      BUILT.clear();
      typedSeed = null;
   }

   static void say(String line) {
      SeedLods.LOGGER.info(line);
      Minecraft client = Minecraft.getInstance();
      if (client.player != null) {
         client.player.displayClientMessage(Component.literal(line).withStyle(ChatFormatting.YELLOW), false);
      }
   }
}
