package com.golem.boxy.seedlod;

import com.golem.boxy.seedlod.fill.RealColumns;
import com.golem.boxy.seedlod.fill.SeedFiller;
import com.golem.boxy.seedlod.share.SharingClient;
import com.golem.boxy.seedlod.world.SeedWorld;
import com.golem.boxy.seedlod.world.SeedWorlds;
import com.golem.boxy.vss.client.ClientNetworking;
import com.golem.boxy.vss.client.LodRequestManager;
import com.golem.boxy.vss.net.VssChannels;
import com.golem.boxy.vss.payloads.SeedRefusalS2CPayload;
import com.golem.boxy.vss.payloads.SeedWorldS2CPayload;
import java.util.concurrent.CompletableFuture;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Client lifecycle: seed requests on login, starting and stopping the fill, and Boxy's real-terrain hand-off. */
public final class SeedLodsClient {
   private static final int START_CHECK_TICKS = 20;
   private static int ticks;
   private static volatile boolean preparing;
   /** A dimension whose fill failed to start; not retried until the next login, so a broken setup is logged once. */
   private static ResourceKey<Level> failedDimension;

   private SeedLodsClient() {
   }

   public static void init() {
      VssChannels.seedClientHandler = new VssChannels.SeedHandler() {
         @Override
         public void onSeedWorld(SeedWorldS2CPayload payload) {
            SharingClient.onWorld(payload);
         }

         @Override
         public void onSeedRefusal(SeedRefusalS2CPayload payload) {
            SharingClient.onRefusal(payload);
         }
      };
      // A real column arrived while the fill was writing it: have Boxy fetch it again as if it changed.
      RealColumns.onOverwritten = (dimension, column) -> Minecraft.getInstance().execute(() -> {
         ClientLevel level = Minecraft.getInstance().level;
         LodRequestManager manager = ClientNetworking.getRequestManager();
         if (manager != null && level != null && level.dimension().equals(dimension)) {
            manager.onDirtyColumns(new long[]{column});
         }
      });
      NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> event.getDispatcher().register(SeedLodsCommand.tree()));
      NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> SharingClient.loggedIn());
      NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
         SeedFiller.stop();
         failedDimension = null;
         SharingClient.forget();
         RealColumns.clear();
      });
      NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> SeedFiller.stop());
      NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick(Minecraft.getInstance()));
   }

   /**
    * Whether Voxy may hold seed terrain for this world, so a real column must clear the sections it omits (a seed
    * tree where the server has air would otherwise remain). Earlier sessions' seed terrain counts too.
    */
   public static boolean mayHoldSeedTerrain() {
      return SeedLodsConfig.get().enabled || SeedFiller.running();
   }

   private static void tick(Minecraft client) {
      if (client.player != null && SeedFiller.running()) {
         SeedFiller.observe(client.player.getX(), client.player.getZ(), client.options.getEffectiveRenderDistance(),
            focalPixels(client));
      }
      if (++ticks < START_CHECK_TICKS) {
         return;
      }
      ticks = 0;
      SeedLodsConfig config = SeedLodsConfig.get();
      if (!config.enabled) {
         if (SeedFiller.running()) {
            SeedFiller.stop();
         }
         return;
      }
      ClientLevel level = client.level;
      if (level == null || client.player == null || preparing || SeedFiller.runningIn(level.dimension())
         || level.dimension().equals(failedDimension)) {
         return;
      }
      VoxyInstance voxy = VoxyCommon.getInstance();
      WorldIdentifier id = WorldIdentifier.of(level);
      if (voxy == null || !voxy.isRunning() || id == null || !voxy.isIngestEnabled(id)) {
         return;
      }
      ResourceKey<Level> dimension = level.dimension();
      IntegratedServer server = client.getSingleplayerServer();
      if (server == null && !SharingClient.knows(dimension)) {
         return;
      }
      preparing = true;
      CompletableFuture.supplyAsync(() -> {
         if (server != null) {
            ServerLevel serverLevel = server.getLevel(dimension);
            return serverLevel == null ? null : SeedWorlds.fromServer(serverLevel);
         }
         return SharingClient.worldFor(dimension, level.dimensionType());
      }).whenComplete((world, failure) -> client.execute(() -> {
         preparing = false;
         if (failure != null) {
            SeedLods.LOGGER.warn("Seed LODs could not prepare {}", dimension.location(), failure);
         } else if (world != null) {
            begin(client, world, voxy, id, config);
         }
      }));
   }

   private static void begin(Minecraft client, SeedWorld world, VoxyInstance voxy, WorldIdentifier id, SeedLodsConfig config) {
      if (client.level == null || !client.level.dimension().equals(world.dimension()) || !SeedLodsConfig.get().enabled) {
         return;
      }
      WorldEngine engine = voxy.getOrCreate(id, true);
      if (engine == null) {
         return;
      }
      try {
         SeedFiller.start(world, voxy, engine, WorldIdentifier.getWorldId(id), config.threads, config.radiusChunks, config.decorate);
      } catch (RuntimeException | LinkageError failure) {
         // start() only owns the engine reference once the filler exists.
         engine.releaseRef();
         failedDimension = world.dimension();
         SeedLods.LOGGER.error("Seed LODs could not start in {}; not retrying until the next login", world.dimension().location(), failure);
      }
   }

   private static double focalPixels(Minecraft client) {
      double fov = Math.toRadians(client.options.fov().get());
      return client.getWindow().getHeight() / (2.0 * Math.tan(fov / 2.0));
   }
}
