package com.golem.boxy.vss.payloads;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: one dimension's seed and chunk generator (JSON naming worldgen entries by key), sent only when the
 * server opts in with {@code shareSeedWithClients} and the client's worldgen fingerprint matches.
 */
public record SeedWorldS2CPayload(ResourceLocation dimension, long seed, String generator) implements VssPayload {
    private static final int MAX_GENERATOR = 1 << 18;

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeLong(seed);
        buf.writeUtf(generator, MAX_GENERATOR);
    }

    public static SeedWorldS2CPayload decode(FriendlyByteBuf buf) {
        return new SeedWorldS2CPayload(buf.readResourceLocation(), buf.readLong(), buf.readUtf(MAX_GENERATOR));
    }
}
