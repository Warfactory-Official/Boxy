package com.golem.boxy.vss.server;

import com.golem.boxy.vss.mixin.AccessorLevelChunkSection;
import io.netty.buffer.Unpooled;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.util.ArrayList;

/**
 * Splits column serialization into a cheap server-thread half and an expensive off-thread half.
 *
 * <p>{@link SectionSerializer} does the whole job inline, and its cost is dominated by
 * {@code FriendlyByteBuf.writeLongArray} — a per-element {@code writeLong} loop over the block-state
 * containers, roughly 4k-8k calls for a full column. This class replaces that with array clones on the server
 * thread (an intrinsic memcpy) and moves every buffer write to a worker.
 *
 * <p>Output is byte-for-byte identical to {@link SectionSerializer#serializeColumn}, so the client
 * deserializer and {@link NbtSectionSerializer}'s disk-path layout are unaffected.
 */
public final class ColumnSnapshotter {
    /** Scratch for writing the small biome container on the server thread (a few dozen bytes per section). */
    private static final ThreadLocal<FriendlyByteBuf> SCRATCH_BIOME_BUF =
            ThreadLocal.withInitial(() -> new FriendlyByteBuf(Unpooled.buffer(1024)));
    private static final ThreadLocal<ArrayList<ColumnSnapshot.SectionSnapshot>> SCRATCH_SECTIONS =
            ThreadLocal.withInitial(ArrayList::new);
    /** Output scratch, grown once to the high-water mark. Per-thread, so worker threads never share one. */
    private static final ThreadLocal<FriendlyByteBuf> SCRATCH_OUT_BUF =
            ThreadLocal.withInitial(() -> new FriendlyByteBuf(Unpooled.buffer(64 * 1024)));

    private ColumnSnapshotter() {}

    /**
     * <b>Server thread only.</b> Copies the live block-state containers and light data out of the chunk.
     *
     * <p>Must not run anywhere else: {@code PalettedContainer.copy()} reads the volatile data field without
     * taking the ThreadingDetector, so a copy racing a {@code setBlockState} tears <em>silently</em> rather
     * than throwing. Bracketing the copy in acquire/release doesn't make off-thread copying safe — it makes an
     * off-thread <em>mutator</em> fail loudly instead of corrupting the LOD we ship to clients.
     */
    public static ColumnSnapshot snapshot(MinecraftServer server, ServerLevel level, LevelChunk chunk, int cx, int cz) {
        BoxyThreads.assertServerThread(server, "ColumnSnapshotter.snapshot");

        int minSectionY = level.getMinSection();
        LevelChunkSection[] sections = chunk.getSections();
        LevelLightEngine lightEngine = level.getLightEngine();
        LayerLightEventListener blockLightListener = lightEngine.getLayerListener(LightLayer.BLOCK);
        LayerLightEventListener skyLightListener = lightEngine.getLayerListener(LightLayer.SKY);
        FriendlyByteBuf biomeScratch = SCRATCH_BIOME_BUF.get();
        ArrayList<ColumnSnapshot.SectionSnapshot> included = SCRATCH_SECTIONS.get();
        included.clear();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null) {
                continue;
            }

            int sectionY = minSectionY + i;
            SectionPos sectionPos = SectionPos.of(cx, sectionY, cz);
            byte[] blockLight = snapshotLight(blockLightListener.getDataLayerData(sectionPos));
            if (section.hasOnlyAir() && blockLight == null) {
                continue;
            }

            byte[] skyLight = snapshotLight(skyLightListener.getDataLayerData(sectionPos));

            PalettedContainer<BlockState> states = section.getStates();
            PalettedContainer<BlockState> statesCopy;
            states.acquire();
            try {
                statesCopy = states.copy();
            } finally {
                states.release();
            }

            biomeScratch.clear();
            section.getBiomes().write(biomeScratch);
            byte[] biomeBytes = new byte[biomeScratch.readableBytes()];
            biomeScratch.readBytes(biomeBytes);

            short nonEmptyBlockCount = ((AccessorLevelChunkSection) (Object) section).boxy$getNonEmptyBlockCount();
            included.add(new ColumnSnapshot.SectionSnapshot(
                    sectionY, nonEmptyBlockCount, statesCopy, biomeBytes, blockLight, skyLight));
        }

        ColumnSnapshot snapshot = new ColumnSnapshot(cx, cz,
                included.toArray(new ColumnSnapshot.SectionSnapshot[0]));
        included.clear(); // don't let the scratch list pin container copies and light arrays until next call
        return snapshot;
    }

    /**
     * Any thread, but only one per {@link ColumnSnapshot} — see that class for why. Returns null when the
     * column has nothing worth sending, matching {@link SectionSerializer}'s empty-column result.
     */
    public static byte[] serialize(ColumnSnapshot snapshot) {
        ColumnSnapshot.SectionSnapshot[] sections = snapshot.sections();
        if (sections.length == 0) {
            return null;
        }

        FriendlyByteBuf buf = SCRATCH_OUT_BUF.get();
        buf.clear(); // reusable scratch — reset indices, keep capacity; do NOT release
        buf.writeVarInt(sections.length);

        for (ColumnSnapshot.SectionSnapshot section : sections) {
            // Byte-identical to LevelChunkSection.write: nonEmptyBlockCount, block states, biomes.
            buf.writeByte(section.sectionY());
            buf.writeShort(section.nonEmptyBlockCount());
            section.states().write(buf);
            buf.writeBytes(section.biomeBytes());

            buf.writeBoolean(section.blockLight() != null);
            if (section.blockLight() != null) {
                buf.writeBytes(section.blockLight());
            }

            buf.writeBoolean(section.skyLight() != null);
            if (section.skyLight() != null) {
                buf.writeBytes(section.skyLight());
            }
        }

        byte[] serialized = new byte[buf.readableBytes()];
        buf.readBytes(serialized);
        return serialized;
    }

    /**
     * Light bytes safe to read from another thread later, or null when the section has no light.
     *
     * <p>{@link SectionSerializer#lightBytes} returns either a shared all-one-level constant (for a homogenous
     * layer) or the light engine's own backing array. The former is never written and can be handed off; the
     * latter is mutated by the light engine's background executor, so it has to be copied.
     */
    private static byte[] snapshotLight(DataLayer layer) {
        byte[] bytes = SectionSerializer.lightBytes(layer);
        if (bytes == null) {
            return null;
        }

        return layer.isDefinitelyHomogenous() ? bytes : bytes.clone();
    }
}
