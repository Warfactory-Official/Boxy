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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Server-side bootstrap + C2S handlers (port of VSS's {@code VSSServerNetworking}). The request-processing
 * service is created on {@link ServerStartedEvent} (covering both dedicated and integrated servers) and torn
 * down on {@link ServerStoppingEvent}; it ticks on the server-tick END phase. Per-player sessions only become
 * active once a client sends a {@link HandshakeC2SPayload}, so pure singleplayer streams over the loopback
 * connection too.
 */
public final class ServerNetworking {
    private static volatile RequestProcessingService requestService;
    private static final java.util.Set<java.util.UUID> entitySessions = new java.util.HashSet<>();

    public static boolean hasEntitySession(ServerPlayer player) {
        return entitySessions.contains(player.getUUID());
    }

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

        NeoForge.EVENT_BUS.addListener(ServerNetworking::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerNetworking::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerNetworking::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerNetworking::onPlayerLoggedOut);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        VSSLogger.info("Starting VSS LOD request processing service");
        requestService = new RequestProcessingService(server);
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        entitySessions.clear();
        RequestProcessingService service = requestService;
        if (service != null) {
            VSSLogger.info("Stopping VSS LOD request processing service");
            service.shutdown();
            requestService = null;
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        RequestProcessingService service = requestService;
        if (service != null) {
            service.tick();
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        entitySessions.remove(event.getEntity().getUUID());
        RequestProcessingService service = requestService;
        if (service != null && event.getEntity() instanceof ServerPlayer player) {
            service.removePlayer(player.getUUID());
        }
    }

    private static void handleHandshake(ServerPlayer player, HandshakeC2SPayload payload) {
        if (payload.protocolVersion() == VSSConstants.PROTOCOL_VERSION) {
            entitySessions.add(player.getUUID());
        } else {
            entitySessions.remove(player.getUUID());
        }
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
