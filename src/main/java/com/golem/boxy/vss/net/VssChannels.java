package com.golem.boxy.vss.net;

import com.golem.boxy.vss.common.VSSConstants;
import com.golem.boxy.vss.payloads.BandwidthUpdateC2SPayload;
import com.golem.boxy.vss.payloads.BatchChunkRequestC2SPayload;
import com.golem.boxy.vss.payloads.BatchResponseS2CPayload;
import com.golem.boxy.vss.payloads.CancelRequestC2SPayload;
import com.golem.boxy.vss.payloads.DirtyColumnsS2CPayload;
import com.golem.boxy.vss.payloads.HandshakeC2SPayload;
import com.golem.boxy.vss.payloads.SessionConfigS2CPayload;
import com.golem.boxy.vss.payloads.VoxelColumnS2CPayload;
import com.golem.boxy.vss.payloads.VssPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * The Forge transport for VSS. Voxy Server Side used Fabric's {@code PayloadTypeRegistry} +
 * {@code Client/ServerPlayNetworking} (1.20.5+ API); here we use a single Forge {@link SimpleChannel}
 * with one indexed message per payload. The channel accepts <em>missing</em> on the remote
 * ({@code acceptMissingOr}), so a Boxy server never rejects a vanilla client (and vice versa) — exactly
 * like a Fabric optional channel. Actual capability negotiation is done at the app level by the
 * Handshake -&gt; SessionConfig exchange.
 *
 * <p>Incoming payloads are delivered on the main (server/client) thread via {@code consumerMainThread}
 * and routed to the installed {@link ServerHandler}/{@link ClientHandler}, which the server/client
 * bootstraps set once they are ready.
 */
public final class VssChannels {
    public static final ResourceLocation CHANNEL_ID = new ResourceLocation("boxy", "vss");
    private static final String PROTOCOL = "vss-" + VSSConstants.PROTOCOL_VERSION;

    private static SimpleChannel channel;

    /** Installed by the server bootstrap; receives C2S payloads on the server thread. */
    public interface ServerHandler {
        void onHandshake(ServerPlayer sender, HandshakeC2SPayload payload);
        void onBatchRequest(ServerPlayer sender, BatchChunkRequestC2SPayload payload);
        void onCancel(ServerPlayer sender, CancelRequestC2SPayload payload);
        void onBandwidth(ServerPlayer sender, BandwidthUpdateC2SPayload payload);
    }

    /** Installed by the client bootstrap; receives S2C payloads on the client thread. */
    public interface ClientHandler {
        void onSessionConfig(SessionConfigS2CPayload payload);
        void onBatchResponse(BatchResponseS2CPayload payload);
        void onDirtyColumns(DirtyColumnsS2CPayload payload);
        void onVoxelColumn(VoxelColumnS2CPayload payload);
    }

    public static volatile ServerHandler serverHandler;
    public static volatile ClientHandler clientHandler;

    private VssChannels() {}

    /** Build the channel and register all 8 message types. Call once during mod construction. */
    public static void register() {
        SimpleChannel ch = NetworkRegistry.ChannelBuilder
                .named(CHANNEL_ID)
                .networkProtocolVersion(() -> PROTOCOL)
                .clientAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL))
                .serverAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL))
                .simpleChannel();

        int id = 0;

        // ---- C2S ----
        ch.messageBuilder(HandshakeC2SPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder((msg, buf) -> msg.encode(buf)).decoder(HandshakeC2SPayload::decode)
                .consumerMainThread((msg, ctx) -> {
                    ServerHandler h = serverHandler;
                    if (h != null) h.onHandshake(ctx.get().getSender(), msg);
                }).add();
        ch.messageBuilder(BatchChunkRequestC2SPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder((msg, buf) -> msg.encode(buf)).decoder(BatchChunkRequestC2SPayload::decode)
                .consumerMainThread((msg, ctx) -> {
                    ServerHandler h = serverHandler;
                    if (h != null) h.onBatchRequest(ctx.get().getSender(), msg);
                }).add();
        ch.messageBuilder(CancelRequestC2SPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder((msg, buf) -> msg.encode(buf)).decoder(CancelRequestC2SPayload::decode)
                .consumerMainThread((msg, ctx) -> {
                    ServerHandler h = serverHandler;
                    if (h != null) h.onCancel(ctx.get().getSender(), msg);
                }).add();
        ch.messageBuilder(BandwidthUpdateC2SPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder((msg, buf) -> msg.encode(buf)).decoder(BandwidthUpdateC2SPayload::decode)
                .consumerMainThread((msg, ctx) -> {
                    ServerHandler h = serverHandler;
                    if (h != null) h.onBandwidth(ctx.get().getSender(), msg);
                }).add();

        // ---- S2C ----
        ch.messageBuilder(SessionConfigS2CPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((msg, buf) -> msg.encode(buf)).decoder(SessionConfigS2CPayload::decode)
                .consumerMainThread((msg, ctx) -> {
                    ClientHandler h = clientHandler;
                    if (h != null) h.onSessionConfig(msg);
                }).add();
        ch.messageBuilder(BatchResponseS2CPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((msg, buf) -> msg.encode(buf)).decoder(BatchResponseS2CPayload::decode)
                .consumerMainThread((msg, ctx) -> {
                    ClientHandler h = clientHandler;
                    if (h != null) h.onBatchResponse(msg);
                }).add();
        ch.messageBuilder(DirtyColumnsS2CPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((msg, buf) -> msg.encode(buf)).decoder(DirtyColumnsS2CPayload::decode)
                .consumerMainThread((msg, ctx) -> {
                    ClientHandler h = clientHandler;
                    if (h != null) h.onDirtyColumns(msg);
                }).add();
        ch.messageBuilder(VoxelColumnS2CPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((msg, buf) -> msg.encode(buf)).decoder(VoxelColumnS2CPayload::decode)
                .consumerMainThread((msg, ctx) -> {
                    ClientHandler h = clientHandler;
                    if (h != null) h.onVoxelColumn(msg);
                }).add();

        channel = ch;
    }

    public static boolean isReady() {
        return channel != null;
    }

    /** Send a C2S payload to the connected server (client side). */
    public static void sendToServer(VssPayload payload) {
        if (channel != null) channel.sendToServer(payload);
    }

    /** Send an S2C payload to a specific player (server side). */
    public static void sendToClient(ServerPlayer player, VssPayload payload) {
        if (channel != null) channel.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }
}
