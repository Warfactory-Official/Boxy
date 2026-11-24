package com.golem.boxy.vss.mixin;

import com.golem.boxy.lod.dt.DtLod;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Voxy persists mapper entries as plain block state NBT; a connected branch also saves its radii. */
@Mixin(targets = "me.cortex.voxy.common.world.other.Mapper$StateEntry", remap = false)
public abstract class MixinVoxyDtStateEntry {

    @Shadow
    public BlockState state;

    @WrapMethod(method = "serialize", require = 0)
    private byte[] boxy$saveConnections(Operation<byte[]> original) {
        return DtLod.saveConnections(original.call(), this.state);
    }

    @WrapMethod(method = "deserialize", require = 0)
    private static Mapper.StateEntry boxy$loadConnections(int id, byte[] data, boolean[] forceResave,
                                                          Operation<Mapper.StateEntry> original) {
        return DtLod.loadConnections(original.call(id, data, forceResave), id, data);
    }
}
