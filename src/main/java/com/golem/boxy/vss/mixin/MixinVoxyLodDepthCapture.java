package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.VoxyLodDepthJoin;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Capture depth at its producer, before Sodium/Iris restore or change the clip convention. */
@Mixin(value = AbstractRenderPipeline.class, remap = false)
public class MixinVoxyLodDepthCapture {
    @Inject(method = "runPipeline", at = @At("RETURN"))
    private void boxy$captureDepth(Viewport<?> viewport, int framebuffer, int width, int height, CallbackInfo ci) {
        VoxyLodDepthJoin.capture((AbstractRenderPipeline) (Object) this, viewport, framebuffer, width, height);
    }
}
