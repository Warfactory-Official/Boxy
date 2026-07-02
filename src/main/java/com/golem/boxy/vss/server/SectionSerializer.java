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
    /** Shared all-zero reference for the vectorized empty-light check in {@link #hasNonZeroData}. */
    private static final byte[] ZERO_LIGHT = new byte[2048];

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
                DataLayer blLayer = blockLightListener.getDataLayerData(sectionPos);
                boolean hasBlockLight = blLayer != null && hasNonZeroData(blLayer);
                if (!section.hasOnlyAir() || hasBlockLight) {
                    includedSections.add(new SectionInfo(i, sectionY, sectionPos, blLayer, hasBlockLight));
                }
            }
        }

        if (includedSections.isEmpty()) {
            return new LoadedColumnData(cx, cz, null, 0);
        }

        FriendlyByteBuf buf = SCRATCH_BUF.get();
        buf.clear(); // reusable scratch — reset indices, keep capacity; do NOT release
        buf.writeVarInt(includedSections.size());
        LayerLightEventListener skyLightListener = lightEngine.getLayerListener(LightLayer.SKY);

        for (SectionInfo info : includedSections) {
            LevelChunkSection section = sections[info.index];
            buf.writeByte(info.sectionY);
            section.write(buf);
            buf.writeBoolean(info.hasBlockLight);
            if (info.hasBlockLight) {
                buf.writeBytes(info.blLayer.getData());
            }

            DataLayer slLayer = skyLightListener.getDataLayerData(info.sectionPos);
            boolean hasSkyLight = slLayer != null && hasNonZeroData(slLayer);
            buf.writeBoolean(hasSkyLight);
            if (hasSkyLight) {
                buf.writeBytes(slLayer.getData());
            }
        }

        byte[] serialized = new byte[buf.readableBytes()];
        buf.readBytes(serialized);
        includedSections.clear(); // drop DataLayer/SectionPos refs so the scratch list doesn't pin them
        return new LoadedColumnData(cx, cz, serialized, serialized.length);
    }

    private static boolean hasNonZeroData(DataLayer layer) {
        byte[] data = layer.getData();
        // Arrays.equals is a JIT-vectorized intrinsic, so the all-zero test (the common case for a section
        // with no light) is far cheaper than a byte-by-byte scan. Light DataLayers are always 2048 bytes; a
        // different length can't match ZERO_LIGHT and is treated as "has data" (safe — include, don't drop).
        return data != null && !Arrays.equals(data, ZERO_LIGHT);
    }

    private record SectionInfo(int index, int sectionY, SectionPos sectionPos, DataLayer blLayer, boolean hasBlockLight) {}
}
