package com.golem.boxy.vss.server;

import com.golem.boxy.vss.config.VSSServerConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Anti-x-ray LOD: sections wholly below {@code minSurface - DEPTH} -> uniform filler, no block light.
 * minSurface = column min over xz of min(OCEAN_FLOOR, MOTION_BLOCKING_NO_LEAVES): sea floor under water, ground under canopy.
 */
final class SurfaceMask {
    static final int DEPTH = 8;
    /** keepFrom when masking is off or the surface is unknown. */
    static final int KEEP_ALL = Integer.MIN_VALUE;
    private static final Heightmap.Types[] SURFACES = {Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES};

    private SurfaceMask() {}

    /** Server thread. Lowest section Y sent real. */
    static int keepFrom(LevelChunk chunk) {
        if (!VSSServerConfig.CONFIG.surfaceOnlyLods) return KEEP_ALL;
        int min = Integer.MAX_VALUE;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                min = Math.min(min, Math.min(chunk.getHeight(SURFACES[0], x, z), chunk.getHeight(SURFACES[1], x, z)) + 1);
            }
        }
        return keepFromSurface(min);
    }

    /** Disk NBT {@code Heightmaps}; absent => KEEP_ALL. */
    static int keepFrom(CompoundTag chunkNbt, LevelHeightAccessor height) {
        if (!VSSServerConfig.CONFIG.surfaceOnlyLods) return KEEP_ALL;
        CompoundTag maps = chunkNbt.getCompound("Heightmaps");
        int bits = Mth.ceillog2(height.getHeight() + 1);
        int min = Integer.MAX_VALUE;
        SimpleBitStorage[] storage = new SimpleBitStorage[SURFACES.length];
        for (int i = 0; i < SURFACES.length; i++) {
            if (!maps.contains(SURFACES[i].getSerializationKey(), Tag.TAG_LONG_ARRAY)) return KEEP_ALL;
            storage[i] = new SimpleBitStorage(bits, 256, maps.getLongArray(SURFACES[i].getSerializationKey()));
        }
        for (int i = 0; i < 256; i++) {
            min = Math.min(min, Math.min(storage[0].get(i), storage[1].get(i)));
        }
        return keepFromSurface(min + height.getMinBuildHeight());
    }

    /** {@code firstAir}: lowest heightmap value (first free y above the surface). */
    private static int keepFromSurface(int firstAir) {
        return Math.floorDiv(firstAir - DEPTH, 16);
    }

    static PalettedContainer<BlockState> filler(ResourceKey<Level> dimension, int sectionY) {
        BlockState state = Level.NETHER.equals(dimension) ? Blocks.NETHERRACK.defaultBlockState()
                : Level.END.equals(dimension) ? Blocks.END_STONE.defaultBlockState()
                : Level.OVERWORLD.equals(dimension) && sectionY < 0 ? Blocks.DEEPSLATE.defaultBlockState()
                : Blocks.STONE.defaultBlockState();
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, state, PalettedContainer.Strategy.SECTION_STATES);
    }
}
