package com.golem.boxy.vss.client;

import com.golem.boxy.vss.common.VSSConstants;
import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.config.VSSClientConfig;
import com.golem.boxy.vss.net.VssChannels;
import com.golem.boxy.vss.payloads.BatchResponseS2CPayload;
import com.golem.boxy.vss.payloads.DirtyColumnsS2CPayload;
import com.golem.boxy.vss.payloads.HandshakeC2SPayload;
import com.golem.boxy.vss.payloads.SessionConfigS2CPayload;
import com.golem.boxy.vss.payloads.VoxelColumnS2CPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side bootstrap + S2C handlers (port of VSS's {@code VSSClientNetworking}). On login the client
 * sends a {@link HandshakeC2SPayload}; if the server answers with an enabled {@link SessionConfigS2CPayload},
 * a {@link LodRequestManager} is created to drive the spiral request loop each client tick. Received columns
 * are queued in {@link ClientColumnProcessor} and ingested via {@link VoxyClientBridge}.
 *
 * <p>S2C handlers are delivered on the client thread by {@link VssChannels} (consumerMainThread), so no
 * extra {@code Minecraft#execute} hop is needed.
 */
public final class ClientNetworking {
    private static volatile boolean serverEnabled = false;
    private static volatile int serverLodDistance = 0;
    private static final AtomicLong columnsReceived = new AtomicLong();
    private static final AtomicLong bytesReceived = new AtomicLong();
    private static volatile long connectionStartMs = 0L;
    private static volatile LodRequestManager requestManager;
    private static final ClientColumnProcessor columnProcessor = new ClientColumnProcessor();

    private ClientNetworking() {}

    public static boolean isServerEnabled() { return serverEnabled; }
    public static int getServerLodDistance() { return serverLodDistance; }
    public static long getColumnsReceived() { return columnsReceived.get(); }
    public static long getBytesReceived() { return bytesReceived.get(); }
    public static long getColumnsDropped() { return columnProcessor.getColumnsDropped(); }
    public static long getConnectionStartMs() { return connectionStartMs; }
    public static LodRequestManager getRequestManager() { return requestManager; }
    public static int getQueuedColumnCount() { return columnProcessor.getQueuedCount(); }

    public static void init() {
        VssChannels.clientHandler = new VssChannels.ClientHandler() {
            @Override
            public void onSessionConfig(SessionConfigS2CPayload payload) {
                handleSessionConfig(payload);
            }

            @Override
            public void onBatchResponse(BatchResponseS2CPayload payload) {
                LodRequestManager manager = requestManager;
                if (manager == null) {
                    return;
                }
                for (int i = 0; i < payload.count(); i++) {
                    int requestId = payload.requestIds()[i];
                    byte type = payload.responseTypes()[i];
                    switch (type) {
                        case VSSConstants.RESPONSE_RATE_LIMITED -> manager.onRateLimited(requestId);
                        case VSSConstants.RESPONSE_UP_TO_DATE -> manager.onColumnUpToDate(requestId);
                        case VSSConstants.RESPONSE_NOT_GENERATED -> manager.onColumnNotGenerated(requestId);
                        default -> VSSLogger.warn("Unknown batch response type: " + type);
                    }
                }
            }

            @Override
            public void onDirtyColumns(DirtyColumnsS2CPayload payload) {
                LodRequestManager manager = requestManager;
                if (manager != null) {
                    manager.onDirtyColumns(payload.dirtyPositions());
                }
            }

            @Override
            public void onVoxelColumn(VoxelColumnS2CPayload payload) {
                columnsReceived.incrementAndGet();
                bytesReceived.addAndGet(payload.estimatedBytes());
                LodRequestManager manager = requestManager;
                // wasCached ⇒ this is a re-sync of a column the client already had, so the ingest must clear
                // any sub-chunks that emptied since (the server omits now-air sections from the stream).
                boolean isUpdate = manager != null && manager.onColumnReceived(payload.requestId(), payload.columnTimestamp());
                columnProcessor.offer(payload, isUpdate);
            }
        };

        MinecraftForge.EVENT_BUS.addListener(ClientNetworking::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(ClientNetworking::onLoggingIn);
        MinecraftForge.EVENT_BUS.addListener(ClientNetworking::onLoggingOut);
        MinecraftForge.EVENT_BUS.addListener(DistantEntityTicker::onClientTick);
    }

    private static void handleSessionConfig(SessionConfigS2CPayload payload) {
        VSSLogger.info("Server session config received (protocol v" + payload.protocolVersion()
                + ", LOD distance: " + payload.lodDistanceChunks() + " chunks, enabled: " + payload.enabled()
                + ", syncRate: " + payload.syncOnLoadRateLimitPerPlayer() + ")");
        if (payload.protocolVersion() != VSSConstants.PROTOCOL_VERSION) {
            VSSLogger.warn("Server has incompatible VSS protocol version " + payload.protocolVersion()
                    + " (client: " + VSSConstants.PROTOCOL_VERSION + "), LOD distribution disabled");
            serverEnabled = false;
            return;
        }
        serverEnabled = payload.enabled();
        serverLodDistance = payload.lodDistanceChunks();
        // Adopt the server's distant-entity render settings in memory (config file untouched). This is
        // unconditional so the client ALWAYS loads the server's entity list while connected, independent of
        // the receiveServerLods (LOD terrain) opt-out below.
        ClientEntitySync.applyFromServer(payload.entityRenderEnabled(), payload.entityRenderDistanceChunks(), payload.entityTypes());
        // LOD terrain streaming still honours the client's receiveServerLods opt-out (entity sync above does not).
        if (payload.enabled() && VSSClientConfig.CONFIG.receiveServerLods) {
            connectionStartMs = System.currentTimeMillis();
            LodRequestManager manager = new LodRequestManager();
            Minecraft mc = Minecraft.getInstance();
            ServerData serverData = mc.getCurrentServer();
            IntegratedServer spServer = mc.getSingleplayerServer();
            String serverAddr;
            if (serverData != null && serverData.ip != null) {
                serverAddr = serverData.ip;
            } else if (spServer != null) {
                Path worldDir = spServer.getWorldPath(LevelResource.ROOT).getFileName();
                serverAddr = "local:" + (worldDir != null ? worldDir : "world");
            } else {
                serverAddr = "unknown";
            }
            manager.onSessionConfig(payload, serverAddr);
            requestManager = manager;
        }
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        LodRequestManager manager = requestManager;
        // Don't pile up LOD requests while the (singleplayer) server is paused and can't drain them — on
        // unpause the burst of stale requests becomes a flood of responses. In multiplayer isPaused() is
        // false, so this is a no-op there.
        if (manager != null && serverEnabled && !Minecraft.getInstance().isPaused()) {
            manager.tick();
        }
        columnProcessor.scheduleProcessing(serverEnabled);
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        serverEnabled = false;
        serverLodDistance = 0;
        requestManager = null;
        // Always handshake so the server can push its session config — including its distant-entity list — even
        // when this client has LOD terrain streaming (receiveServerLods) turned off. The channel is optional
        // (acceptMissingOr), so a non-Boxy/vanilla server simply never replies.
        try {
            int clientCaps = VoxyClientBridge.isAvailable() ? VSSConstants.CAPABILITY_VOXEL_COLUMNS : 0;
            VssChannels.sendToServer(new HandshakeC2SPayload(VSSConstants.PROTOCOL_VERSION, clientCaps));
        } catch (Exception e) {
            VSSLogger.debug("Handshake send failed (server likely doesn't have VSS): " + e.getMessage());
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        LodRequestManager manager = requestManager;
        if (manager != null) {
            manager.disconnect();
            manager.saveCache();
        }
        columnProcessor.shutdown();
        columnProcessor.resetStats();
        ClientEntitySync.clear();
        DistantEntityServerPos.clear();
        serverEnabled = false;
        serverLodDistance = 0;
        columnsReceived.set(0L);
        bytesReceived.set(0L);
        connectionStartMs = 0L;
        requestManager = null;
    }
}
