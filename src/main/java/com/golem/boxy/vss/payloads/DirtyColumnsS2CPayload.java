package com.golem.boxy.vss.payloads;

import com.golem.boxy.vss.common.VSSConstants;
import net.minecraft.network.FriendlyByteBuf;

/** S2C: a set of packed column positions that changed on the server and should be re-requested. */
public record DirtyColumnsS2CPayload(long[] dirtyPositions) implements VssPayload {
    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(dirtyPositions.length);
        for (long pos : dirtyPositions) {
            buf.writeLong(pos);
        }
    }

    public static DirtyColumnsS2CPayload decode(FriendlyByteBuf buf) {
        int rawLen = Math.max(buf.readVarInt(), 0);
        int len = Math.min(rawLen, VSSConstants.MAX_DIRTY_COLUMN_POSITIONS);
        long[] positions = new long[len];
        for (int i = 0; i < len; i++) {
            positions[i] = buf.readLong();
        }
        int excess = rawLen - len;
        if (excess > 0) {
            int toSkip = (int) Math.min(excess * 8L, (long) buf.readableBytes());
            buf.skipBytes(toSkip);
        }
        return new DirtyColumnsS2CPayload(positions);
    }
}
