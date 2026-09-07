package com.golem.boxy.smoke;

import com.golem.boxy.vss.client.ClientNetworking;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Opens a disposable copy of the server smoke world and exercises native client + integrated streaming. */
@Mod(value = "boxy_smoke", dist = Dist.CLIENT)
public final class BoxyClientSmoke {
    private int ticks;
    private int dimensionPhase;
    private int dimensionTicks;
    private long columnsBeforeReturn;
    public static int distantPigDraws;
    public static int bandedPigDraws;

    public BoxyClientSmoke() {
        if (Boolean.getBoolean("boxy.smokeHbm")) {
            com.golem.boxy.vss.config.VSSClientConfig.CONFIG.receiveServerLods = false;
        }
        if (Boolean.getBoolean("boxy.smokeStability")) {
            com.golem.boxy.vss.config.VSSClientConfig.CONFIG.receiveServerLods = false;
            me.cortex.voxy.client.RenderStatistics.enabled = true;
        }
        com.golem.boxy.vss.config.VSSClientConfig.CONFIG.distantEntityDepthMode =
                com.golem.boxy.vss.config.DistantEntityDepthMode.valueOf(System.getProperty("boxy.smokeDepthMode", "PRECISE"));
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.renderDistance().set(4);
        mc.options.simulationDistance().set(5);
        if (mc.level == null || mc.player == null) return;
        if (Boolean.getBoolean("boxy.smokeHbm")) {
            HbmTerrainSmoke.tick(mc);
            return;
        }
        if (Boolean.getBoolean("boxy.smokeStability")) {
            StabilitySmoke.tick(mc);
            return;
        }
        if (Boolean.getBoolean("boxy.smokeOcclusion")) {
            OcclusionSmoke.tick(mc);
            return;
        }
        ++ticks;
        if (Boolean.getBoolean("boxy.smokeDimensions") && !checkDimensionCycle(mc)) return;
        if (ticks < 160) return;
        if (VoxyCommon.getInstance() == null || IGetVoxyRenderSystem.getNullable() == null) {
            throw new AssertionError("Voxy client/renderer did not initialize");
        }
        if (!ClientNetworking.isServerEnabled() || ClientNetworking.getRequestManager() == null
                || ClientNetworking.getColumnsReceived() == 0) {
            throw new AssertionError("Integrated VSS handshake/stream did not complete");
        }
        if (distantPigDraws == 0) throw new AssertionError("Distant pig never reached the entity renderer");
        if (bandedPigDraws == 0) throw new AssertionError("Distant pig never rendered inside a depth band");
        if (!DirtyDeliverySmoke.check(mc)) return;
        if (!OuterDirtyDeliverySmoke.check(mc)) return;
        if (!EntityPairingSmoke.check(mc)) return;
        checkLightHandoff(mc);
        try (var image = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(new java.io.File(mc.gameDirectory, "boxy-smoke.png"));
        } catch (java.io.IOException e) {
            throw new AssertionError("Unable to capture client smoke frame", e);
        }
        com.mojang.logging.LogUtils.getLogger().info("BOXY_CLIENT_SMOKE_OK: native renderer, {} distant pig draws ({} banded), integrated VSS received {} columns",
                distantPigDraws, bandedPigDraws, ClientNetworking.getColumnsReceived());
        mc.stop();
    }

    private static void checkLightHandoff(Minecraft mc) {
        for (int i = 0; i < 1000 && (!mc.level.isLightUpdateQueueEmpty() || mc.level.getLightEngine().hasLightWork()); i++) {
            mc.level.pollLightUpdates();
            mc.level.getLightEngine().runLightUpdates();
        }
        if (!mc.level.isLightUpdateQueueEmpty() || mc.level.getLightEngine().hasLightWork()) {
            throw new AssertionError("Light fixture could not settle queued updates");
        }
        var ghast = net.minecraft.world.entity.EntityType.GHAST.create(mc.level);
        if (ghast == null) throw new AssertionError("Cannot create light probe fixture");
        ghast.setPos(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        int light = mc.getEntityRenderDispatcher().getPackedLightCoords(ghast, 1);
        if (light == 0) throw new AssertionError("Light fixture needs a lit native chunk");
        var probe = net.minecraft.core.BlockPos.containing(ghast.getLightProbePosition(1));
        if (!mc.level.hasChunk(probe.getX() >> 4, probe.getZ() >> 4)
                || !mc.level.getLightEngine().lightOnInSection(net.minecraft.core.SectionPos.of(probe))) {
            throw new AssertionError("Native light fixture is not ready");
        }
        com.golem.boxy.vss.client.DistantEntityLighting.resolve(ghast, 1, light);
        ghast.setPos(1_000_000, 120, 1_000_000);
        if (com.golem.boxy.vss.client.DistantEntityLighting.resolve(ghast, 1, 0) != light) {
            throw new AssertionError("Missing distant lighting discarded valid native light");
        }
        ghast.setPos(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        mc.level.queueLightUpdate(() -> {});
        if (com.golem.boxy.vss.client.DistantEntityLighting.resolve(ghast, 1, 0) != light) {
            throw new AssertionError("Returning chunk accepted light before queued updates completed");
        }
        mc.level.pollLightUpdates();
        if (com.golem.boxy.vss.client.DistantEntityLighting.resolve(ghast, 1, 0) != 0) {
            throw new AssertionError("Valid darkness was suppressed");
        }
        com.golem.boxy.vss.client.DistantEntityLighting.resolve(ghast, 1, light);
        var packet = new net.minecraft.network.protocol.game.ClientboundLightUpdatePacket(
                new net.minecraft.world.level.ChunkPos(probe), mc.level.getLightEngine(), null, null);
        mc.getConnection().handleLightUpdatePacket(packet);
        if (com.golem.boxy.vss.client.DistantEntityLighting.resolve(ghast, 1, 0) != light) {
            throw new AssertionError("Native light packet failed to rearm a continuously loaded entity");
        }
        for (int i = 0; i < 1000 && (!mc.level.isLightUpdateQueueEmpty() || mc.level.getLightEngine().hasLightWork()); i++) {
            mc.level.pollLightUpdates();
            mc.level.getLightEngine().runLightUpdates();
        }
        if (com.golem.boxy.vss.client.DistantEntityLighting.resolve(ghast, 1, 0) != 0) {
            throw new AssertionError("Completed native light packet did not release the hold");
        }
        com.golem.boxy.vss.client.DistantEntityLighting.clear();
        com.mojang.logging.LogUtils.getLogger().info("BOXY_LIGHT_HANDOFF_SMOKE_OK: native light availability, outgoing hold, incoming queued-light hold, live native packet invalidation, immediate valid darkness");
    }

    private boolean checkDimensionCycle(Minecraft mc) {
        if (ticks > 1200) throw new AssertionError("Dimension transition timed out");
        if (dimensionPhase == 0 && ticks >= 60) {
            dimensionPhase = 1;
            changeDimension(mc, net.minecraft.world.level.Level.NETHER);
        } else if (dimensionPhase == 1 && mc.level.dimension() == net.minecraft.world.level.Level.NETHER && ++dimensionTicks >= 40) {
            dimensionPhase = 2;
            dimensionTicks = 0;
            columnsBeforeReturn = ClientNetworking.getColumnsReceived();
            changeDimension(mc, net.minecraft.world.level.Level.OVERWORLD);
        } else if (dimensionPhase == 2 && mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD && ++dimensionTicks >= 100) {
            if (ClientNetworking.getColumnsReceived() <= columnsBeforeReturn) throw new AssertionError("Terrain streaming stalled after returning dimensions");
            com.mojang.logging.LogUtils.getLogger().info("BOXY_DIMENSION_SMOKE_OK: overworld -> nether -> overworld; terrain streaming resumed");
            dimensionPhase = 3;
        }
        return dimensionPhase == 3;
    }

    private static void changeDimension(Minecraft mc, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(uuid);
            var level = server.getLevel(dimension);
            player.teleportTo(level, 0.5, 120, 0.5, -90, 0);
            if (dimension == net.minecraft.world.level.Level.OVERWORLD) {
                var pos = new net.minecraft.core.BlockPos(160, 119, 0);
                level.setBlockAndUpdate(pos, level.getBlockState(pos).isAir()
                        ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()
                        : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
        });
    }
}
