package com.golem.boxy.vss.payloads;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;

/**
 * S2C: the server will not share its seed with this client. {@code fingerprint} is the server's own (registry to digest),
 * empty when sharing is off; with it and {@code serverPacks} the client can say what differs.
 */
public record SeedRefusalS2CPayload(Map<String, String> fingerprint, List<String> serverPacks) implements VssPayload {
    private static final int MAX_TEXT = 4096;
    private static final int MAX_PACKS = 256;

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeMap(fingerprint, (out, key) -> out.writeUtf(key, MAX_TEXT), (out, value) -> out.writeUtf(value, MAX_TEXT));
        buf.writeVarInt(Math.min(serverPacks.size(), MAX_PACKS));
        for (int i = 0; i < Math.min(serverPacks.size(), MAX_PACKS); i++) {
            buf.writeUtf(serverPacks.get(i), MAX_TEXT);
        }
    }

    public static SeedRefusalS2CPayload decode(FriendlyByteBuf buf) {
        Map<String, String> fingerprint = buf.readMap(in -> in.readUtf(MAX_TEXT), in -> in.readUtf(MAX_TEXT));
        int count = Math.clamp(buf.readVarInt(), 0, MAX_PACKS);
        List<String> packs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            packs.add(buf.readUtf(MAX_TEXT));
        }
        return new SeedRefusalS2CPayload(fingerprint, packs);
    }
}
