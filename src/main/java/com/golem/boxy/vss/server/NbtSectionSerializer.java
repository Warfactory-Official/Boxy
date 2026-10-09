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
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Reads a chunk's NBT off the region files (without loading it into the live world) and serializes its
 * sections into the same wire blob as {@link SectionSerializer}. This is the disk path used by
 * {@code ChunkDiskReader} to serve already-generated terrain at scale.
 *
 * <p>The block-state and biome codecs follow Minecraft 1.21.1's ChunkSerializer layout.
 */
final class NbtSectionSerializer {
    private static final byte[] EMPTY = new byte[0];
    private static final ThreadLocal<CodecCache> CODEC_CACHE = ThreadLocal.withInitial(CodecCache::new);
    private static final ThreadLocal<ArrayList<ParsedSection>> PARSED_SECTIONS =
            ThreadLocal.withInitial(() -> new ArrayList<>(16));
    private static final ThreadLocal<FriendlyByteBuf> OUTPUT_BUFFER = ThreadLocal.withInitial(
            () -> new FriendlyByteBuf(Unpooled.buffer(4096)));

    private NbtSectionSerializer() {}

    static byte[] readAndSerializeSections(ChunkMap chunkMap, RegistryAccess registryAccess, int cx, int cz) throws Exception {
        Optional<CompoundTag> optionalTag = chunkMap.read(new ChunkPos(cx, cz)).get(10L, TimeUnit.SECONDS);
        if (optionalTag.isEmpty()) {
            return null;
        }

        CompoundTag chunkNbt = optionalTag.get();
        String statusStr = chunkNbt.contains("Status", Tag.TAG_STRING) ? chunkNbt.getString("Status") : null;
        if (statusStr == null || ChunkStatus.byName(statusStr) != ChunkStatus.FULL) {
            return null;
        }

        CodecCache codecs = CODEC_CACHE.get();
        if (codecs.registryAccess != registryAccess) codecs.reset(registryAccess);

        ListTag sectionsList = chunkNbt.getList("sections", Tag.TAG_COMPOUND);
        if (sectionsList.isEmpty()) {
            return new byte[0];
        }

        ArrayList<ParsedSection> parsed = PARSED_SECTIONS.get();
        parsed.clear();
        try {
            for (int idx = 0; idx < sectionsList.size(); idx++) {
                CompoundTag sectionTag = sectionsList.getCompound(idx);
                int sectionY = sectionTag.contains("Y") ? sectionTag.getInt("Y") : Integer.MIN_VALUE;
                if (sectionY == Integer.MIN_VALUE) {
                    continue;
                }
                byte[] blockLightData = sectionTag.contains("BlockLight", Tag.TAG_BYTE_ARRAY) ? sectionTag.getByteArray("BlockLight") : EMPTY;
                LevelChunkSection section = parseSection(sectionTag, codecs.blockStateCodec, codecs.biomeCodec,
                        codecs.defaultBiome, codecs.biomeRegistry, blockLightData);
                if (section != null) {
                    byte[] skyLightData = sectionTag.contains("SkyLight", Tag.TAG_BYTE_ARRAY) ? sectionTag.getByteArray("SkyLight") : EMPTY;
                    parsed.add(new ParsedSection(sectionY, section, blockLightData, skyLightData));
                }
            }

            if (parsed.isEmpty()) {
                return new byte[0];
            }

            FriendlyByteBuf buf = OUTPUT_BUFFER.get();
            buf.clear();
            buf.ensureWritable(parsed.size() * 1024);
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
            buf.getBytes(buf.readerIndex(), result);
            return result;
        } finally {
            parsed.clear();
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

    /** Codec construction walks registry-backed codec graphs and is expensive on every disk column. */
    private static final class CodecCache {
        private RegistryAccess registryAccess;
        private Registry<Biome> biomeRegistry;
        private Holder<Biome> defaultBiome;
        private Codec<PalettedContainer<BlockState>> blockStateCodec;
        private Codec<PalettedContainer<Holder<Biome>>> biomeCodec;

        private void reset(RegistryAccess access) {
            // Construct the complete replacement before publishing its key. A failed lookup or codec
            // construction must remain retryable, rather than exposing a partially initialized cache.
            Registry<Biome> registry = access.registryOrThrow(Registries.BIOME);
            Holder<Biome> plains = registry.getHolderOrThrow(Biomes.PLAINS);
            Codec<PalettedContainer<BlockState>> states = PalettedContainer.codecRW(
                    Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES,
                    Blocks.AIR.defaultBlockState());
            Codec<PalettedContainer<Holder<Biome>>> biomes = PalettedContainer.codecRW(
                    registry.asHolderIdMap(), registry.holderByNameCodec(),
                    PalettedContainer.Strategy.SECTION_BIOMES, plains);
            this.biomeRegistry = registry;
            this.defaultBiome = plains;
            this.blockStateCodec = states;
            this.biomeCodec = biomes;
            this.registryAccess = access;
        }
    }
}
