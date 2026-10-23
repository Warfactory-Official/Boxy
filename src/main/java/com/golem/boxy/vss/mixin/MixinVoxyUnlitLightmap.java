package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.LodWaterSweep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Debug only: while {@code /boxylod sweep} grabs its unlit frame, Voxy's LOD shaders sample a white lightmap, so the
 * frame shows what LOD surfaces look like with full light. Every Voxy draw binds the lightmap through
 * {@code LightMapHelper.getLightmapTextureId}, and the vertex shader samples it each frame, so one frame suffices.
 */
@Mixin(targets = "me.cortex.voxy.client.core.rendering.util.LightMapHelper", remap = false)
public class MixinVoxyUnlitLightmap {
    @Inject(method = "getLightmapTextureId", at = @At("HEAD"), cancellable = true, require = 0)
    private static void boxy$whiteWhileMeasuring(CallbackInfoReturnable<Integer> cir) {
        if (LodWaterSweep.unlit) {
            cir.setReturnValue(LodWaterSweep.whiteTexture());
        }
    }
}
