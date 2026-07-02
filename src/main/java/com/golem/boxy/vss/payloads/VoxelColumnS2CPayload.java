package com.golem.boxy.vss.payloads;

import com.golem.boxy.vss.common.VSSConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * S2C: a serialized column of LOD chunk sections (block states + light), the payload the client feeds
 * into Voxy. The dimension is encoded as a small ordinal for the three vanilla dimensions, or a string
 * id for modded ones. {@code sectionBytes} is the section blob produced by the server serializers
 * (see {@code SectionSerializer}/{@code NbtSectionSerializer}).
 */
public final class VoxelColumnS2CPayload implements VssPayload {
    private static final int MAX_SECTIONS_SIZE = 2097152;
    private static final int MAX_DIMENSION_STRING_LENGTH = 256;

    private final int requestId;
    private final int chunkX;
    private final int chunkZ;
    private final ResourceKey<Level> dimension;
    private final long columnTimestamp;
    private final byte[] sectionBytes;

    public VoxelColumnS2CPayload(int requestId, int chunkX, int chunkZ, ResourceKey<Level> dimension, long columnTimestamp, byte[] sectionBytes) {
        this.requestId = requestId;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.dimension = dimension;
        this.columnTimestamp = columnTimestamp;
        this.sectionBytes = sectionBytes;
    }

    public int requestId() { return requestId; }
    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public ResourceKey<Level> dimension() { return dimension; }
    public long columnTimestamp() { return columnTimestamp; }
    public byte[] decompressedSections() { return sectionBytes; }

    public int estimatedBytes() {
        return sectionBytes.length + VSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
    }

    private static int dimensionToOrdinal(ResourceKey<Level> dim) {
        if (dim == Level.OVERWORLD) return 0;
        if (dim == Level.NETHER) return 1;
        if (dim == Level.END) return 2;
        return -1;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(requestId);
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        int ordinal = dimensionToOrdinal(dimension);
        buf.writeVarInt(ordinal);
        if (ordinal == -1) {
            buf.writeUtf(dimension.location().toString());
        }
        buf.writeLong(columnTimestamp);
        buf.writeByteArray(sectionBytes);
    }

    public static VoxelColumnS2CPayload decode(FriendlyByteBuf buf) {
        int requestId = buf.readVarInt();
        int cx = buf.readInt();
        int cz = buf.readInt();
        int ordinal = buf.readVarInt();
        ResourceKey<Level> dim;
        switch (ordinal) {
            case 0 -> dim = Level.OVERWORLD;
            case 1 -> dim = Level.NETHER;
            case 2 -> dim = Level.END;
            default -> dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(buf.readUtf(MAX_DIMENSION_STRING_LENGTH)));
        }
        long columnTimestamp = buf.readLong();
        byte[] sectionBytes = buf.readByteArray(MAX_SECTIONS_SIZE);
        return new VoxelColumnS2CPayload(requestId, cx, cz, dim, columnTimestamp, sectionBytes);
    }
}
