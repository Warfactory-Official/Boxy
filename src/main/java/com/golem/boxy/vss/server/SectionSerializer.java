package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.processing.LoadedColumnData;
import io.netty.buffer.Unpooled;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Serializes the sections of an <em>in-memory</em> chunk (block states + biomes via
 * {@link LevelChunkSection#write}, plus the two 2048-byte light {@link DataLayer}s) into the wire blob
 * carried by {@code VoxelColumnS2CPayload}. Used for loaded-chunk probes and freshly-generated chunks.
 * The byte layout matches {@link NbtSectionSerializer} (disk path) and the client deserializer.
 */
public final class SectionSerializer {
    /** Shared all-zero reference for the vectorized empty-light check in {@link #lightBytes}. */
    private static final byte[] ZERO_LIGHT = new byte[2048];

    /**
     * Pre-filled light arrays for uniform levels 1-15, indexed by light value (slot 0 is unused — an
     * all-zero layer is reported as "no light" instead). Byte layout matches DataLayer's own nibble
     * packing: both nibbles hold the level. Shared and never mutated; callers only read them into a buffer.
     */
    private static final byte[][] UNIFORM_LIGHT = new byte[16][];

    static {
        for (int level = 1; level < 16; level++) {
            byte[] filled = new byte[2048];
            Arrays.fill(filled, (byte) (level | level << 4));
            UNIFORM_LIGHT[level] = filled;
        }
    }

    /**
     * Reusable per-thread serialization scratch. serializeColumn runs on the server main thread for every
     * column served (loaded-chunk probes — up to hundreds per tick during streaming — and completed
     * generations); a fresh buffer per column allocated ~24KB and then grow-copied several times for a real
     * column (100–300KB with light data). The scratch buffer grows once to the high-water mark and is
     * cleared per column; only the final byte[] snapshot escapes, so reuse is safe. Never release() these.
     */
    private static final ThreadLocal<FriendlyByteBuf> SCRATCH_BUF =
            ThreadLocal.withInitial(() -> new FriendlyByteBuf(Unpooled.buffer(64 * 1024)));
    private static final ThreadLocal<ArrayList<SectionInfo>> SCRATCH_SECTIONS =
            ThreadLocal.withInitial(ArrayList::new);

    private SectionSerializer() {}

    public static LoadedColumnData serializeColumn(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        int minSectionY = level.getMinSection();
        LevelChunkSection[] sections = chunk.getSections();
        LevelLightEngine lightEngine = level.getLightEngine();
        LayerLightEventListener blockLightListener = lightEngine.getLayerListener(LightLayer.BLOCK);
        ArrayList<SectionInfo> includedSections = SCRATCH_SECTIONS.get();
        includedSections.clear();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section != null) {
                int sectionY = minSectionY + i;
                SectionPos sectionPos = SectionPos.of(cx, sectionY, cz);
                byte[] blockLight = lightBytes(blockLightListener.getDataLayerData(sectionPos));
                if (!section.hasOnlyAir() || blockLight != null) {
                    includedSections.add(new SectionInfo(i, sectionY, sectionPos, blockLight));
                }
            }
        }

        if (includedSections.isEmpty()) {
            return new LoadedColumnData(cx, cz, new byte[0], 25);
        }

        FriendlyByteBuf buf = SCRATCH_BUF.get();
        buf.clear(); // reusable scratch — reset indices, keep capacity; do NOT release
        buf.writeVarInt(includedSections.size());
        LayerLightEventListener skyLightListener = lightEngine.getLayerListener(LightLayer.SKY);

        for (SectionInfo info : includedSections) {
            LevelChunkSection section = sections[info.index];
            buf.writeByte(info.sectionY);
            section.write(buf);
            buf.writeBoolean(info.blockLight != null);
            if (info.blockLight != null) {
                buf.writeBytes(info.blockLight);
            }

            byte[] skyLight = lightBytes(skyLightListener.getDataLayerData(info.sectionPos));
            buf.writeBoolean(skyLight != null);
            if (skyLight != null) {
                buf.writeBytes(skyLight);
            }
        }

        byte[] serialized = new byte[buf.readableBytes()];
        buf.readBytes(serialized);
        includedSections.clear(); // drop light/SectionPos refs so the scratch list doesn't pin them
        return new LoadedColumnData(cx, cz, serialized, serialized.length);
    }

    /**
     * The 2048 light bytes to send for {@code layer}, or null if it carries no light at all.
     *
     * <p>Deliberately avoids {@link DataLayer#getData()} on a homogenous layer. getData() lazily allocates a
     * 2048-byte array and publishes it into the layer's own field — and DataLayers are owned by the light
     * engine, which runs on a background executor, so calling it from the server thread is a write race on a
     * shared object. Homogenous layers are not a corner case: sky-light sections above the terrain are stored
     * uniform-15, so the old code did this for most columns. For those we hand back a shared pre-filled array
     * with the identical byte layout, leaving the light engine's DataLayer untouched.
     */
    static byte[] lightBytes(DataLayer layer) {
        if (layer == null || layer.isEmpty()) {
            return null; // isEmpty() == "no backing array and default 0" — definitively dark, nothing allocated
        }
        if (layer.isDefinitelyHomogenous()) {
            for (int level = 1; level < 16; level++) {
                if (layer.isDefinitelyFilledWith(level)) {
                    return UNIFORM_LIGHT[level];
                }
            }
        }
        // Heterogeneous: the backing array already exists, so getData() just returns it. Arrays.equals is a
        // JIT-vectorized intrinsic, making the all-zero test far cheaper than a byte-by-byte scan. Light
        // DataLayers are always 2048 bytes; a different length can't match ZERO_LIGHT and is treated as
        // "has data" (safe — include, don't drop).
        byte[] data = layer.getData();
        return data != null && !Arrays.equals(data, ZERO_LIGHT) ? data : null;
    }

    private record SectionInfo(int index, int sectionY, SectionPos sectionPos, byte[] blockLight) {}
}
