package com.golem.boxy.vss.payloads;

import net.minecraft.network.FriendlyByteBuf;

/** C2S: cancel a single in-flight request by id. */
public record CancelRequestC2SPayload(int requestId) implements VssPayload {
    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(requestId);
    }

    public static CancelRequestC2SPayload decode(FriendlyByteBuf buf) {
        return new CancelRequestC2SPayload(buf.readVarInt());
    }
}
