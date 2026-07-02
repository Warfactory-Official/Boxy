package com.golem.boxy.vss.payloads;

import com.golem.boxy.vss.common.VSSConstants;
import net.minecraft.network.FriendlyByteBuf;

/** S2C: batched non-data responses. responseTypes[i] is 0=RateLimited, 1=UpToDate, 2=NotGenerated. */
public record BatchResponseS2CPayload(byte[] responseTypes, int[] requestIds, int count) implements VssPayload {
    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeByte(responseTypes[i]);
            buf.writeVarInt(requestIds[i]);
        }
    }

    public static BatchResponseS2CPayload decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > VSSConstants.MAX_BATCH_RESPONSES) {
            throw new IllegalArgumentException("Batch response count out of range: " + count);
        }
        byte[] responseTypes = new byte[count];
        int[] requestIds = new int[count];
        for (int i = 0; i < count; i++) {
            responseTypes[i] = buf.readByte();
            requestIds[i] = buf.readVarInt();
        }
        return new BatchResponseS2CPayload(responseTypes, requestIds, count);
    }
}
