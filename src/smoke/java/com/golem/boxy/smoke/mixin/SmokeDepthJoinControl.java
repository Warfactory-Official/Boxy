package com.golem.boxy.smoke.mixin;

import com.golem.boxy.vss.client.VoxyLodDepthJoin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Negative control: the occlusion test must fail without the repair, not pass merely because no entity was drawn. */
@Mixin(value = VoxyLodDepthJoin.class, remap = false)
public class SmokeDepthJoinControl {
    @Inject(method = "capture", at = @At("HEAD"), cancellable = true)
    private static void boxy$disableProducerForControl(CallbackInfo ci) {
        if (Boolean.getBoolean("boxy.smokeStabilityControl")) ci.cancel();
    }
    @Inject(method = "joinDepth", at = @At("RETURN"))
    private static void boxy$observeJoin(CallbackInfo ci) {
        com.golem.boxy.smoke.OcclusionSmoke.joins++;
    }

    @Inject(method = "joinIfNeeded", at = @At("HEAD"), cancellable = true)
    private static void boxy$negativeControl(CallbackInfo ci) {
        if (Boolean.getBoolean("boxy.smokeOcclusion") && Boolean.getBoolean("boxy.smokeSkipDepthJoin")) ci.cancel();
    }
}
