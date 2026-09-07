package com.golem.boxy.vss.net;

import com.golem.boxy.vss.common.VSSConstants;
import com.golem.boxy.vss.payloads.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

/** Optional native payloads, dispatched on the logical main thread on both sides. */
public final class VssChannels {
    public interface ServerHandler {
        void onHandshake(ServerPlayer sender, HandshakeC2SPayload payload);
        void onBatchRequest(ServerPlayer sender, BatchChunkRequestC2SPayload payload);
        void onCancel(ServerPlayer sender, CancelRequestC2SPayload payload);
        void onBandwidth(ServerPlayer sender, BandwidthUpdateC2SPayload payload);
    }

    public interface ClientHandler {
        void onSessionConfig(SessionConfigS2CPayload payload);
        void onBatchResponse(BatchResponseS2CPayload payload);
        void onDirtyColumns(DirtyColumnsS2CPayload payload);
        void onVoxelColumn(VoxelColumnS2CPayload payload);
    }

    public static volatile ServerHandler serverHandler;
    public static volatile ClientHandler clientHandler;
    private static boolean ready;

    private VssChannels() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("boxy-1.21.1-" + VSSConstants.PROTOCOL_VERSION)
                .optional().executesOn(HandlerThread.MAIN);
        registrar.playToServer(VssPayload.HANDSHAKE, StreamCodec.ofMember(HandshakeC2SPayload::encode, HandshakeC2SPayload::decode),
                (msg, ctx) -> { if (serverHandler != null) serverHandler.onHandshake((ServerPlayer) ctx.player(), msg); });
        registrar.playToServer(VssPayload.REQUEST, StreamCodec.ofMember(BatchChunkRequestC2SPayload::encode, BatchChunkRequestC2SPayload::decode),
                (msg, ctx) -> { if (serverHandler != null) serverHandler.onBatchRequest((ServerPlayer) ctx.player(), msg); });
        registrar.playToServer(VssPayload.CANCEL, StreamCodec.ofMember(CancelRequestC2SPayload::encode, CancelRequestC2SPayload::decode),
                (msg, ctx) -> { if (serverHandler != null) serverHandler.onCancel((ServerPlayer) ctx.player(), msg); });
        registrar.playToServer(VssPayload.BANDWIDTH, StreamCodec.ofMember(BandwidthUpdateC2SPayload::encode, BandwidthUpdateC2SPayload::decode),
                (msg, ctx) -> { if (serverHandler != null) serverHandler.onBandwidth((ServerPlayer) ctx.player(), msg); });
        registrar.playToClient(VssPayload.SESSION, StreamCodec.ofMember(SessionConfigS2CPayload::encode, SessionConfigS2CPayload::decode),
                (msg, ctx) -> { if (clientHandler != null) clientHandler.onSessionConfig(msg); });
        registrar.playToClient(VssPayload.RESPONSE, StreamCodec.ofMember(BatchResponseS2CPayload::encode, BatchResponseS2CPayload::decode),
                (msg, ctx) -> { if (clientHandler != null) clientHandler.onBatchResponse(msg); });
        registrar.playToClient(VssPayload.DIRTY, StreamCodec.ofMember(DirtyColumnsS2CPayload::encode, DirtyColumnsS2CPayload::decode),
                (msg, ctx) -> { if (clientHandler != null) clientHandler.onDirtyColumns(msg); });
        registrar.playToClient(VssPayload.COLUMN, StreamCodec.ofMember(VoxelColumnS2CPayload::encode, VoxelColumnS2CPayload::decode),
                (msg, ctx) -> { if (clientHandler != null) clientHandler.onVoxelColumn(msg); });
        ready = true;
    }

    public static boolean isReady() {
        return ready;
    }

    /** Called only by the client-side request pipeline. Optional channels must be checked before sending. */
    public static void sendToServer(VssPayload payload) {
        var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
        if (connection != null && connection.hasChannel(payload.type())) {
            PacketDistributor.sendToServer(payload);
        }
    }

    public static void sendToClient(ServerPlayer player, VssPayload payload) {
        if (player.connection.hasChannel(payload.type())) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
