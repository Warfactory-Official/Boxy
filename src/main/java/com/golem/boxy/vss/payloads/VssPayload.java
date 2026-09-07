package com.golem.boxy.vss.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Boxy 1.21.1 payloads. Section palettes are specific to the negotiated Minecraft registries. */
public sealed interface VssPayload extends CustomPacketPayload permits HandshakeC2SPayload,
        BatchChunkRequestC2SPayload, CancelRequestC2SPayload, BandwidthUpdateC2SPayload,
        SessionConfigS2CPayload, BatchResponseS2CPayload, DirtyColumnsS2CPayload, VoxelColumnS2CPayload {
    Type<HandshakeC2SPayload> HANDSHAKE = new Type<>(ResourceLocation.parse("boxy:handshake"));
    Type<BatchChunkRequestC2SPayload> REQUEST = new Type<>(ResourceLocation.parse("boxy:request"));
    Type<CancelRequestC2SPayload> CANCEL = new Type<>(ResourceLocation.parse("boxy:cancel"));
    Type<BandwidthUpdateC2SPayload> BANDWIDTH = new Type<>(ResourceLocation.parse("boxy:bandwidth"));
    Type<SessionConfigS2CPayload> SESSION = new Type<>(ResourceLocation.parse("boxy:session"));
    Type<BatchResponseS2CPayload> RESPONSE = new Type<>(ResourceLocation.parse("boxy:response"));
    Type<DirtyColumnsS2CPayload> DIRTY = new Type<>(ResourceLocation.parse("boxy:dirty"));
    Type<VoxelColumnS2CPayload> COLUMN = new Type<>(ResourceLocation.parse("boxy:column"));

    void encode(FriendlyByteBuf buf);

    @Override
    default Type<? extends CustomPacketPayload> type() {
        return switch (this) {
            case HandshakeC2SPayload ignored -> HANDSHAKE;
            case BatchChunkRequestC2SPayload ignored -> REQUEST;
            case CancelRequestC2SPayload ignored -> CANCEL;
            case BandwidthUpdateC2SPayload ignored -> BANDWIDTH;
            case SessionConfigS2CPayload ignored -> SESSION;
            case BatchResponseS2CPayload ignored -> RESPONSE;
            case DirtyColumnsS2CPayload ignored -> DIRTY;
            case VoxelColumnS2CPayload ignored -> COLUMN;
        };
    }
}
