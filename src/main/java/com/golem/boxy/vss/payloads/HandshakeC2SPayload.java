package com.golem.boxy.vss.payloads;

import net.minecraft.network.FriendlyByteBuf;

/** C2S: client announces its protocol version + capability bitmask on connect. */
public record HandshakeC2SPayload(int protocolVersion, int capabilities) implements VssPayload {
    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(protocolVersion);
        buf.writeVarInt(capabilities);
    }

    public static HandshakeC2SPayload decode(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        int caps = buf.readVarInt();
        return new HandshakeC2SPayload(version, caps);
    }
}
