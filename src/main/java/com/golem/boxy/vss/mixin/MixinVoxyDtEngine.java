package com.golem.boxy.vss.mixin;

import com.golem.boxy.lod.dt.DtLod;
import me.cortex.voxy.common.world.WorldEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Engine lookup for {@code DtLod}: ingest knows the mapper, neighbours live in the engine. */
@Mixin(targets = "me.cortex.voxy.common.world.WorldEngine", remap = false)
public class MixinVoxyDtEngine {

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void boxy$registerEngine(CallbackInfo ci) {
        DtLod.engineCreated((WorldEngine) (Object) this);
    }
}
