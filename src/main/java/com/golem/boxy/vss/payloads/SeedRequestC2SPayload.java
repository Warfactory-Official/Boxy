package com.golem.boxy.vss.payloads;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;

/**
 * C2S: a client that can draw terrain from a seed asks for it, stating its worldgen fingerprint (registry to digest) and the extra
 * data packs it loaded so the server can explain a mismatch. Answered by {@link SeedWorldS2CPayload} per
 * dimension or by {@link SeedRefusalS2CPayload}.
 */
public record SeedRequestC2SPayload(Map<String, String> fingerprint, List<String> extraPacks) implements VssPayload {
    private static final int MAX_TEXT = 4096;
    private static final int MAX_PACKS = 256;

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeMap(fingerprint, (out, key) -> out.writeUtf(key, MAX_TEXT), (out, value) -> out.writeUtf(value, MAX_TEXT));
        buf.writeVarInt(Math.min(extraPacks.size(), MAX_PACKS));
        for (int i = 0; i < Math.min(extraPacks.size(), MAX_PACKS); i++) {
            buf.writeUtf(extraPacks.get(i), MAX_TEXT);
        }
    }

    public static SeedRequestC2SPayload decode(FriendlyByteBuf buf) {
        Map<String, String> fingerprint = buf.readMap(in -> in.readUtf(MAX_TEXT), in -> in.readUtf(MAX_TEXT));
        int count = Math.clamp(buf.readVarInt(), 0, MAX_PACKS);
        List<String> packs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            packs.add(buf.readUtf(MAX_TEXT));
        }
        return new SeedRequestC2SPayload(fingerprint, packs);
    }
}
