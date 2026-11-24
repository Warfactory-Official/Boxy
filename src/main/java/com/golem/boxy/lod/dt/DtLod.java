package com.golem.boxy.lod.dt;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.neoforged.fml.ModList;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Dynamic Trees branches in Voxy LODs keep their connections. All DT types live in {@link DtLodImpl}, only touched
 * when the mod is present.
 */
public final class DtLod {

    private static volatile Boolean enabled;
    private static final Map<Mapper, WorldEngine> ENGINES = Collections.synchronizedMap(new WeakHashMap<>());
    /** Re-inserts of neighbour fix-ups must not trigger fix-ups. */
    static final ThreadLocal<Boolean> FIXING = ThreadLocal.withInitial(() -> false);

    private DtLod() {
    }

    public static boolean enabled() {
        Boolean e = enabled;
        if (e == null) {
            ModList list = ModList.get();
            if (list == null) {
                return false;
            }
            enabled = e = list.isLoaded("dynamictrees");
        }
        return e;
    }

    /** Seed-LOD decorated block: branches get their connected variant from the placed neighbourhood. */
    public static BlockState connect(BlockState state, net.minecraft.world.level.BlockAndTintGetter level, int x, int y, int z) {
        return enabled() ? DtLodImpl.connect(state, level, x, y, z) : state;
    }

    public static void engineCreated(WorldEngine engine) {
        ENGINES.put(engine.getMapper(), engine);
    }

    public static java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads(
            net.minecraft.client.resources.model.BakedModel model, ConnectedBranchState state,
            net.minecraft.core.Direction side, net.minecraft.util.RandomSource random) {
        return DtLodImpl.quads(model, state, side, random);
    }

    private static final String TAG = "boxy_dt_radii";

    public static byte[] saveConnections(byte[] serialized, BlockState state) {
        if (!(state instanceof ConnectedBranchState connected)) {
            return serialized;
        }
        try {
            var tag = net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(serialized),
                    net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            tag.putIntArray(TAG, connected.radii);
            var out = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.writeCompressed(tag, out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Mapper.StateEntry loadConnections(Mapper.StateEntry entry, int id, byte[] data) {
        if (!enabled()) {
            return entry;
        }
        try {
            var tag = net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(data),
                    net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            if (!tag.contains(TAG)) {
                return entry;
            }
            return new Mapper.StateEntry(id, ConnectedBranchState.of(entry.state, tag.getIntArray(TAG)));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** After Voxy converted a chunk section: branch voxels get their connected variant. */
    public static void converted(VoxelizedSection section, Mapper mapper, PalettedContainer<BlockState> container) {
        if (enabled() && !FIXING.get()) {
            DtLodImpl.converted(section, mapper, ENGINES.get(mapper), container);
        }
    }

    /** After Voxy inserted a section: branch voxels of loaded neighbours facing it are re-connected. */
    public static void inserted(WorldEngine engine, VoxelizedSection section) {
        if (enabled() && !FIXING.get()) {
            DtLodImpl.inserted(engine, section);
        }
    }
}
