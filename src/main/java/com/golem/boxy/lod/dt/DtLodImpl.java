package com.golem.boxy.lod.dt;

import com.dtteam.dynamictrees.block.branch.BranchBlock;
import com.dtteam.dynamictrees.model.modeldata.ModelConnections;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.LightLayer;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

final class DtLodImpl {

    private static final Predicate<BlockState> IS_BRANCH = s -> s.getBlock() instanceof BranchBlock;

    private DtLodImpl() {
    }

    /** Block states of the world around one section: the section being converted, else Voxy's stored lvl0 data. */
    private static final class View implements BlockAndTintGetter {
        private final Mapper mapper;
        private final WorldEngine engine;
        private final PalettedContainer<BlockState> own;
        private final int bx, by, bz;
        private final Long2ObjectOpenHashMap<WorldSection> sections = new Long2ObjectOpenHashMap<>();

        View(Mapper mapper, WorldEngine engine, @Nullable PalettedContainer<BlockState> own, int bx, int by, int bz) {
            this.mapper = mapper;
            this.engine = engine;
            this.own = own;
            this.bx = bx;
            this.by = by;
            this.bz = bz;
        }

        void close() {
            for (WorldSection s : this.sections.values()) {
                if (s != null) {
                    s.release();
                }
            }
        }

        @Nullable
        WorldSection section(int wx, int wy, int wz) {
            long key = (((long) (wx >> 5)) & 0x3FFFFF) << 42 | (((long) (wy >> 5)) & 0xFFFFF) << 22 | ((long) (wz >> 5)) & 0x3FFFFF;
            if (this.sections.containsKey(key)) {
                return this.sections.get(key);
            }
            WorldSection s = this.engine == null ? null : this.engine.acquireIfExists(0, wx >> 5, wy >> 5, wz >> 5);
            this.sections.put(key, s);
            return s;
        }

        BlockState stateAt(int wx, int wy, int wz) {
            if (this.own != null && (wx >> 4) == (this.bx >> 4) && (wy >> 4) == (this.by >> 4) && (wz >> 4) == (this.bz >> 4)) {
                return this.own.get(wx & 15, wy & 15, wz & 15);
            }
            WorldSection s = section(wx, wy, wz);
            if (s == null) {
                return Blocks.AIR.defaultBlockState();
            }
            long id = s._unsafeGetRawDataArray()[WorldSection.getIndex(wx & 31, wy & 31, wz & 31)];
            int blockId = Mapper.getBlockId(id);
            return blockId == 0 ? Blocks.AIR.defaultBlockState()
                    : ConnectedBranchState.baseOf(this.mapper.getBlockStateFromBlockId(blockId));
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return stateAt(pos.getX(), pos.getY(), pos.getZ());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            return 1.0f;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return null;
        }

        @Override
        public int getBrightness(LightLayer type, BlockPos pos) {
            return 0;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return -1;
        }
    }

    private static int variantId(Mapper mapper, View view, BlockState state, int x, int y, int z) {
        BranchBlock branch = (BranchBlock) state.getBlock();
        int[] radii = branch.getConnectionData(view, new BlockPos(x, y, z), state).getAllRadii();
        return mapper.getIdForBlockState(ConnectedBranchState.of(state, radii));
    }

    static BlockState connect(BlockState state, BlockAndTintGetter level, int x, int y, int z) {
        if (!(state.getBlock() instanceof BranchBlock branch)) {
            return state;
        }
        return ConnectedBranchState.of(state, branch.getConnectionData(level, new BlockPos(x, y, z), state).getAllRadii());
    }

    static void converted(VoxelizedSection section, Mapper mapper, @Nullable WorldEngine engine,
                          PalettedContainer<BlockState> container) {
        if (!container.maybeHas(IS_BRANCH)) {
            return;
        }
        int bx = section.x << 4, by = section.y << 4, bz = section.z << 4;
        View view = new View(mapper, engine, container, bx, by, bz);
        try {
            long[] data = section.section;
            for (int i = 0; i < 4096; i++) {
                int x = i & 15, z = (i >> 4) & 15, y = (i >> 8) & 15;
                BlockState state = container.get(x, y, z);
                if (state.getBlock() instanceof BranchBlock) {
                    int id = variantId(mapper, view, state, bx + x, by + y, bz + z);
                    data[i] = Mapper.withBlockBiome(data[i], id, Mapper.getBiomeId(data[i]));
                }
            }
        } finally {
            view.close();
        }
    }

    static void inserted(WorldEngine engine, VoxelizedSection section) {
        Mapper mapper = engine.getMapper();
        for (Direction dir : Direction.values()) {
            int nx = section.x + dir.getStepX(), ny = section.y + dir.getStepY(), nz = section.z + dir.getStepZ();
            View view = new View(mapper, engine, null, 0, 0, 0);
            try {
                WorldSection ws = view.section(nx << 4, ny << 4, nz << 4);
                if (ws == null) {
                    continue;
                }
                long[] stored = ws._unsafeGetRawDataArray();
                int ox = (nx << 4) & 31, oy = (ny << 4) & 31, oz = (nz << 4) & 31;
                // Layer of the neighbour touching `section`.
                int fixedAxis = dir.getAxis().ordinal();
                int layer = dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 0 : 15;
                VoxelizedSection patched = null;
                for (int a = 0; a < 16; a++) {
                    for (int b = 0; b < 16; b++) {
                        int x = fixedAxis == 0 ? layer : a;
                        int y = fixedAxis == 1 ? layer : (fixedAxis == 0 ? a : b);
                        int z = fixedAxis == 2 ? layer : b;
                        long id = stored[WorldSection.getIndex(ox + x, oy + y, oz + z)];
                        int blockId = Mapper.getBlockId(id);
                        if (blockId == 0) {
                            continue;
                        }
                        BlockState state = ConnectedBranchState.baseOf(mapper.getBlockStateFromBlockId(blockId));
                        if (!(state.getBlock() instanceof BranchBlock)) {
                            continue;
                        }
                        int fresh = variantId(mapper, view, state, (nx << 4) + x, (ny << 4) + y, (nz << 4) + z);
                        if (fresh == blockId) {
                            continue;
                        }
                        if (patched == null) {
                            patched = VoxelizedSection.createEmpty().setPosition(nx, ny, nz);
                            int nonAir = 0;
                            for (int yy = 0; yy < 16; yy++) {
                                for (int zz = 0; zz < 16; zz++) {
                                    for (int xx = 0; xx < 16; xx++) {
                                        long v = stored[WorldSection.getIndex(ox + xx, oy + yy, oz + zz)];
                                        patched.section[(yy << 8) | (zz << 4) | xx] = v;
                                        nonAir += Mapper.getBlockId(v) != 0 ? 1 : 0;
                                    }
                                }
                            }
                            patched.lvl0NonAirCount = nonAir;
                        }
                        int idx = (y << 8) | (z << 4) | x;
                        patched.section[idx] = Mapper.withBlockBiome(patched.section[idx], fresh, Mapper.getBiomeId(id));
                    }
                }
                if (patched != null) {
                    DtLod.FIXING.set(true);
                    try {
                        WorldVoxilizedSectionMipper.mipSection(patched, mapper);
                        WorldUpdater.insertUpdate(engine, patched);
                    } finally {
                        DtLod.FIXING.set(false);
                    }
                }
            } finally {
                view.close();
            }
        }
    }

    /** Baked quads of a connected branch: DT's model needs its connections as ModelData. */
    static List<net.minecraft.client.renderer.block.model.BakedQuad> quads(BakedModel model, ConnectedBranchState state,
                                                                           @Nullable Direction side, RandomSource random) {
        BranchBlock branch = (BranchBlock) state.base.getBlock();
        ModelData data = new ModelConnections(state.radii).setFamily(branch).toModelData();
        return model.getQuads(state.base, side, random, data, (RenderType) null);
    }
}
