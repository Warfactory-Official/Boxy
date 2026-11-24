package com.golem.boxy.lod.dt;

import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Dynamic Trees branch with its neighbour connections frozen in. Voxy stores one block state per voxel and bakes one
 * model per state; DT's branch model needs the connection radii as {@code ModelData}, so connectivity has to live in
 * the state. Interned: Voxy's mapper keys by identity. Not in the block state registry; never placed in a world.
 */
public final class ConnectedBranchState extends BlockState {

    private record Key(BlockState base, int[] radii) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.base == base && Arrays.equals(k.radii, radii);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(base) * 31 + Arrays.hashCode(radii);
        }
    }

    private static final ConcurrentHashMap<Key, ConnectedBranchState> INTERN = new ConcurrentHashMap<>();

    public final BlockState base;
    public final int[] radii;

    private ConnectedBranchState(BlockState base, int[] radii) {
        super(base.getBlock(), new Reference2ObjectArrayMap<Property<?>, Comparable<?>>(base.getValues()), null);
        this.base = base;
        this.radii = radii;
        initCache();
    }

    public static ConnectedBranchState of(BlockState base, int[] radii) {
        int[] r = radii.clone();
        return INTERN.computeIfAbsent(new Key(base, r), k -> new ConnectedBranchState(base, r));
    }

    public static BlockState baseOf(BlockState state) {
        return state instanceof ConnectedBranchState c ? c.base : state;
    }

    @Override
    public String toString() {
        return "Connected" + base + Arrays.toString(radii);
    }
}
