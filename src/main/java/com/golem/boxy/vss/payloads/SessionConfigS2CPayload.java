package com.golem.boxy.vss.payloads;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C: sent in reply to a handshake; tells the client whether VSS is enabled and the server's limits.
 * Also carries the server's distant-entity render settings ({@code entityRender*}/{@code entityTypes}) so a
 * client automatically uses the server's entity whitelist + distance without editing its own config (see
 * {@code ClientEntitySync}).
 */
public record SessionConfigS2CPayload(
        int protocolVersion,
        boolean enabled,
        int lodDistanceChunks,
        int serverCapabilities,
        int syncOnLoadRateLimitPerPlayer,
        int syncOnLoadConcurrencyLimitPerPlayer,
        int generationRateLimitPerPlayer,
        int generationConcurrencyLimitPerPlayer,
        boolean generationEnabled,
        long playerBandwidthLimit,
        boolean entityRenderEnabled,
        int entityRenderDistanceChunks,
        List<String> entityTypes) implements VssPayload {
    private static final int MAX_ENTITY_TYPE_ENTRIES = 1024;
    private static final int MAX_ENTITY_ID_LENGTH = 256;

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(protocolVersion);
        buf.writeBoolean(enabled);
        buf.writeVarInt(lodDistanceChunks);
        buf.writeVarInt(serverCapabilities);
        buf.writeVarInt(syncOnLoadRateLimitPerPlayer);
        buf.writeVarInt(syncOnLoadConcurrencyLimitPerPlayer);
        buf.writeVarInt(generationRateLimitPerPlayer);
        buf.writeVarInt(generationConcurrencyLimitPerPlayer);
        buf.writeBoolean(generationEnabled);
        buf.writeVarLong(playerBandwidthLimit);
        buf.writeBoolean(entityRenderEnabled);
        buf.writeVarInt(entityRenderDistanceChunks);
        int count = Math.min(entityTypes.size(), MAX_ENTITY_TYPE_ENTRIES);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeUtf(entityTypes.get(i), MAX_ENTITY_ID_LENGTH);
        }
    }

    public static SessionConfigS2CPayload decode(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        boolean enabled = buf.readBoolean();
        int lodDist = buf.readVarInt();
        int serverCaps = buf.readVarInt();
        int syncRate = buf.readVarInt();
        int syncConc = buf.readVarInt();
        int genRate = buf.readVarInt();
        int genConc = buf.readVarInt();
        boolean genEnabled = buf.readBoolean();
        long bwLimit = buf.readVarLong();
        boolean entEnabled = buf.readBoolean();
        int entDist = buf.readVarInt();
        int count = Math.min(Math.max(buf.readVarInt(), 0), MAX_ENTITY_TYPE_ENTRIES);
        List<String> entTypes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entTypes.add(buf.readUtf(MAX_ENTITY_ID_LENGTH));
        }
        return new SessionConfigS2CPayload(version, enabled, lodDist, serverCaps, syncRate, syncConc, genRate, genConc,
                genEnabled, bwLimit, entEnabled, entDist, entTypes);
    }
}
