package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.VSSConstants;
import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.config.VSSServerConfig;
import com.golem.boxy.vss.net.VssChannels;
import com.golem.boxy.vss.payloads.BandwidthUpdateC2SPayload;
import com.golem.boxy.vss.payloads.BatchChunkRequestC2SPayload;
import com.golem.boxy.vss.payloads.CancelRequestC2SPayload;
import com.golem.boxy.vss.payloads.HandshakeC2SPayload;
import com.golem.boxy.vss.payloads.SessionConfigS2CPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

/**
 * Server-side bootstrap + C2S handlers (port of VSS's {@code VSSServerNetworking}). The request-processing
 * service is created on {@link ServerStartedEvent} (covering both dedicated and integrated servers) and torn
 * down on {@link ServerStoppingEvent}; it ticks on the server-tick END phase. Per-player sessions only become
 * active once a client sends a {@link HandshakeC2SPayload}, so pure singleplayer streams over the loopback
 * connection too.
 */
public final class ServerNetworking {
    private static volatile RequestProcessingService requestService;

    private ServerNetworking() {}

    public static RequestProcessingService getRequestService() {
        return requestService;
    }

    public static void init() {
        VssChannels.serverHandler = new VssChannels.ServerHandler() {
            @Override
            public void onHandshake(ServerPlayer sender, HandshakeC2SPayload payload) {
                handleHandshake(sender, payload);
            }

            @Override
            public void onBatchRequest(ServerPlayer sender, BatchChunkRequestC2SPayload payload) {
                RequestProcessingService service = requestService;
                if (service != null) {
                    service.handleBatchRequest(sender, payload);
                }
            }

            @Override
            public void onCancel(ServerPlayer sender, CancelRequestC2SPayload payload) {
                RequestProcessingService service = requestService;
                if (service != null) {
                    service.handleCancel(sender, payload);
                }
            }

            @Override
            public void onBandwidth(ServerPlayer sender, BandwidthUpdateC2SPayload payload) {
                RequestProcessingService service = requestService;
                if (service != null) {
                    service.handleBandwidthUpdate(sender, payload);
                }
            }
        };

        MinecraftForge.EVENT_BUS.addListener(ServerNetworking::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(ServerNetworking::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(ServerNetworking::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(ServerNetworking::onPlayerLoggedOut);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        VSSLogger.info("Starting VSS LOD request processing service");
        requestService = new RequestProcessingService(server);
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        RequestProcessingService service = requestService;
        if (service != null) {
            VSSLogger.info("Stopping VSS LOD request processing service");
            service.shutdown();
            requestService = null;
        }
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            RequestProcessingService service = requestService;
            if (service != null) {
                service.tick();
            }
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        RequestProcessingService service = requestService;
        if (service != null && event.getEntity() instanceof ServerPlayer player) {
            service.removePlayer(player.getUUID());
        }
    }

    private static void handleHandshake(ServerPlayer player, HandshakeC2SPayload payload) {
        VSSLogger.info("VSS handshake received from " + player.getName().getString()
                + " (protocol v" + payload.protocolVersion() + ", capabilities=" + payload.capabilities() + ")");
        VSSServerConfig config = VSSServerConfig.CONFIG;
        RequestProcessingService service = requestService;
        boolean effectiveEnabled = config.enabled && service != null;
        int serverCaps = VSSConstants.CAPABILITY_VOXEL_COLUMNS;

        VssChannels.sendToClient(player, new SessionConfigS2CPayload(
                VSSConstants.PROTOCOL_VERSION,
                effectiveEnabled,
                config.lodDistanceChunks,
                serverCaps,
                config.syncOnLoadRateLimitPerPlayer,
                config.syncOnLoadConcurrencyLimitPerPlayer,
                config.generationRateLimitPerPlayer,
                config.generationConcurrencyLimitPerPlayer,
                config.enableChunkGeneration,
                config.bytesPerSecondLimitPerPlayer,
                config.extendEntityTracking,
                config.entityTrackingDistanceChunks,
                config.trackedEntityTypes));

        if (payload.protocolVersion() != VSSConstants.PROTOCOL_VERSION) {
            VSSLogger.warn("Player " + player.getName().getString() + " has incompatible VSS protocol version "
                    + payload.protocolVersion() + " (server: " + VSSConstants.PROTOCOL_VERSION + "), skipping LOD distribution");
        } else if (effectiveEnabled) {
            service.registerPlayer(player, payload.capabilities());
            VSSLogger.info("Player " + player.getName().getString() + " registered for VSS LOD request processing"
                    + (payload.capabilities() != 0 ? " (caps=" + payload.capabilities() + ")" : ""));
        }
    }
}
