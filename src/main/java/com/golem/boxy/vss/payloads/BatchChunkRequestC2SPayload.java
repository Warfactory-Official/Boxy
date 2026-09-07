package com.golem.boxy.vss.payloads;

import com.golem.boxy.vss.common.VSSConstants;
import net.minecraft.network.FriendlyByteBuf;

/** C2S: timestamp 0 generates, -1 discovers, -2 forces a dirty refresh, and positive values validate a version. */
public record BatchChunkRequestC2SPayload(int[] requestIds, long[] packedPositions, long[] clientTimestamps, int count)
        implements VssPayload {
    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeVarInt(requestIds[i]);
            buf.writeLong(packedPositions[i]);
            buf.writeLong(clientTimestamps[i]);
        }
    }

    public static BatchChunkRequestC2SPayload decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > VSSConstants.MAX_BATCH_CHUNK_REQUESTS) {
            throw new IllegalArgumentException("Batch chunk request count out of range: " + count);
        }
        int[] requestIds = new int[count];
        long[] packedPositions = new long[count];
        long[] clientTimestamps = new long[count];
        for (int i = 0; i < count; i++) {
            requestIds[i] = buf.readVarInt();
            packedPositions[i] = buf.readLong();
            clientTimestamps[i] = buf.readLong();
        }
        return new BatchChunkRequestC2SPayload(requestIds, packedPositions, clientTimestamps, count);
    }
}
