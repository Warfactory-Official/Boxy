package com.golem.boxy.vss.payloads;

import net.minecraft.network.FriendlyByteBuf;

/** C2S: client tells the server its desired receive rate (bytes/second). */
public record BandwidthUpdateC2SPayload(long desiredRate) implements VssPayload {
    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarLong(desiredRate);
    }

    public static BandwidthUpdateC2SPayload decode(FriendlyByteBuf buf) {
        return new BandwidthUpdateC2SPayload(buf.readVarLong());
    }
}
