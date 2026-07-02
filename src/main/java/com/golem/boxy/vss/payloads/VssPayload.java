package com.golem.boxy.vss.payloads;

import com.golem.boxy.vss.net.VssChannels;
import net.minecraft.network.FriendlyByteBuf;

/**
 * A VSS network payload. The original (Voxy Server Side, MC 1.21) used the 1.20.5+
 * {@code CustomPacketPayload}/{@code StreamCodec} API; on Forge 1.20.1 we model each payload as a plain
 * object with a {@link #encode} method and a {@code static decode(FriendlyByteBuf)} factory, dispatched
 * over a Forge {@link VssChannels SimpleChannel}. The byte layout is
 * preserved exactly (all standard {@link FriendlyByteBuf} ops), so the protocol is Boxy&lt;-&gt;Boxy
 * faithful.
 */
public interface VssPayload {
    void encode(FriendlyByteBuf buf);
}
