package com.golem.boxy.vss.server;

import com.mojang.serialization.Codec;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkStorage;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads a chunk's NBT off the region files (without loading it into the live world) and serializes its
 * sections into the same wire blob as {@link SectionSerializer}. This is the disk path used by
 * {@code ChunkDiskReader} to serve already-generated terrain at scale.
 *
 * <p>The 1.21 original used a bundled {@code ChunkSectionsCodecFactory} ({@code class_11897}); on 1.20.1
 * the block-state and biome {@link PalettedContainer} codecs are built inline exactly as vanilla
 * {@code ChunkSerializer.read} does, and the NBT is read with the classic (non-Optional) accessors.
 */
final class NbtSectionSerializer {
    private static final byte[] EMPTY = new byte[0];
    // ChunkStorage.read(ChunkPos) — protected, declared on the ChunkMap superclass. Reached reflectively
    // (SRG name, like BoxyServerIngest) rather than via a mixin @Invoker, so it degrades gracefully if the
    // chunk-I/O layer is reworked (e.g. C2ME) instead of failing ChunkMap's class transform.
    private static volatile Method READ_METHOD;

    private NbtSectionSerializer() {}

    private static Method resolveReadMethod() throws ReflectiveOperationException {
        Method m = READ_METHOD;
        if (m == null) {
            m = ChunkStorage.class.getDeclaredMethod("m_223454_", ChunkPos.class);
            m.setAccessible(true);
            READ_METHOD = m;
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    static byte[] readAndSerializeSections(ChunkMap chunkMap, RegistryAccess registryAccess, int cx, int cz) throws Exception {
        CompletableFuture<Optional<CompoundTag>> future =
                (CompletableFuture<Optional<CompoundTag>>) resolveReadMethod().invoke(chunkMap, new ChunkPos(cx, cz));
        Optional<CompoundTag> optionalTag = future.get(10L, TimeUnit.SECONDS);
        if (optionalTag.isEmpty()) {
            return null;
        }

        CompoundTag chunkNbt = optionalTag.get();
        String statusStr = chunkNbt.contains("Status", Tag.TAG_STRING) ? chunkNbt.getString("Status") : null;
        if (statusStr == null || ChunkStatus.byName(statusStr) != ChunkStatus.FULL) {
            return null;
        }

        Registry<Biome> biomeRegistry = registryAccess.registryOrThrow(Registries.BIOME);
        Holder<Biome> defaultBiome = biomeRegistry.getHolderOrThrow(Biomes.PLAINS);
        Codec<PalettedContainer<BlockState>> blockStateCodec = PalettedContainer.codecRW(
                Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
        Codec<PalettedContainer<Holder<Biome>>> biomeCodec = PalettedContainer.codecRW(
                biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, defaultBiome);

        ListTag sectionsList = chunkNbt.getList("sections", Tag.TAG_COMPOUND);
        if (sectionsList.isEmpty()) {
            return null;
        }

        ArrayList<ParsedSection> parsed = new ArrayList<>(sectionsList.size());
        for (int idx = 0; idx < sectionsList.size(); idx++) {
            CompoundTag sectionTag = sectionsList.getCompound(idx);
            int sectionY = sectionTag.contains("Y") ? sectionTag.getInt("Y") : Integer.MIN_VALUE;
            if (sectionY == Integer.MIN_VALUE) {
                continue;
            }
            byte[] blockLightData = sectionTag.contains("BlockLight", Tag.TAG_BYTE_ARRAY) ? sectionTag.getByteArray("BlockLight") : EMPTY;
            LevelChunkSection section = parseSection(sectionTag, blockStateCodec, biomeCodec, defaultBiome, biomeRegistry, blockLightData);
            if (section != null) {
                byte[] skyLightData = sectionTag.contains("SkyLight", Tag.TAG_BYTE_ARRAY) ? sectionTag.getByteArray("SkyLight") : EMPTY;
                parsed.add(new ParsedSection(sectionY, section, blockLightData, skyLightData));
            }
        }

        if (parsed.isEmpty()) {
            return new byte[0];
        }

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(parsed.size() * 1024));
        try {
            buf.writeVarInt(parsed.size());
            for (ParsedSection p : parsed) {
                buf.writeByte(p.sectionY);
                p.section.write(buf);
                boolean hasBlockLight = p.blockLight.length == 2048;
                buf.writeBoolean(hasBlockLight);
                if (hasBlockLight) {
                    buf.writeBytes(p.blockLight);
                }
                boolean hasSkyLight = p.skyLight.length == 2048;
                buf.writeBoolean(hasSkyLight);
                if (hasSkyLight) {
                    buf.writeBytes(p.skyLight);
                }
            }
            byte[] result = new byte[buf.readableBytes()];
            buf.readBytes(result);
            return result;
        } finally {
            buf.release();
        }
    }

    private static LevelChunkSection parseSection(
            CompoundTag sectionTag,
            Codec<PalettedContainer<BlockState>> blockStateCodec,
            Codec<PalettedContainer<Holder<Biome>>> biomeCodec,
            Holder<Biome> defaultBiome,
            Registry<Biome> biomeRegistry,
            byte[] blockLightData) {
        if (!sectionTag.contains("block_states", Tag.TAG_COMPOUND)) {
            return null;
        }
        PalettedContainer<BlockState> blockStates = blockStateCodec
                .parse(NbtOps.INSTANCE, sectionTag.getCompound("block_states"))
                .result().orElse(null);
        if (blockStates == null) {
            return null;
        }

        PalettedContainer<Holder<Biome>> biomes = null;
        if (sectionTag.contains("biomes", Tag.TAG_COMPOUND)) {
            biomes = biomeCodec.parse(NbtOps.INSTANCE, sectionTag.getCompound("biomes")).result().orElse(null);
        }
        PalettedContainerRO<Holder<Biome>> biomeContainer = biomes != null
                ? biomes
                : new PalettedContainer<>(biomeRegistry.asHolderIdMap(), defaultBiome, PalettedContainer.Strategy.SECTION_BIOMES);

        LevelChunkSection section = new LevelChunkSection(blockStates, biomeContainer);

        // A non-empty (has-blocks) section that carries no real block light is dropped (mirrors VSS): such
        // a section's light is recomputed client-side, so shipping it adds nothing.
        if (section.hasOnlyAir()) {
            if (blockLightData.length != 2048) {
                return null;
            }
            boolean hasLight = false;
            for (byte b : blockLightData) {
                if (b != 0) {
                    hasLight = true;
                    break;
                }
            }
            if (!hasLight) {
                return null;
            }
        }
        return section;
    }

    private record ParsedSection(int sectionY, LevelChunkSection section, byte[] blockLight, byte[] skyLight) {}
}
