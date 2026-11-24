package com.golem.boxy.vss.mixin;

import com.golem.boxy.lod.dt.DtLod;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A section landed: loaded neighbours' branch voxels facing it are re-connected. */
@Mixin(targets = "me.cortex.voxy.common.world.WorldUpdater", remap = false)
public class MixinVoxyDtUpdater {

    @Inject(method = "insertUpdate", at = @At("RETURN"), require = 0)
    private static void boxy$reconnectNeighbours(WorldEngine into, VoxelizedSection section, CallbackInfo ci) {
        DtLod.inserted(into, section);
    }
}
