package com.golem.boxy.vss.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-syncs the GL depth-test state after Voxy's render pipeline, fixing shaderpack passes (clouds, etc.)
 * rendering on top of solid terrain on Forge/Oculus.
 *
 * <p><b>The bug.</b> When a shaderpack sets {@code excludeLodsFromVanillaDepth} (Complementary does),
 * Voxy's {@code IrisVoxyRenderPipeline.finish()} takes a branch that ends with a <em>raw</em>
 * {@code GL11.glDisable(GL_DEPTH_TEST)} — bypassing Minecraft's {@code RenderSystem} state cache. Control
 * then returns to Oculus, whose subsequent passes are driven through {@code RenderSystem}: it still believes
 * depth-testing is <em>enabled</em> (from the terrain pass), so its {@code enableDepthTest()} calls no-op and
 * the GL state stays <em>disabled</em>. Anything that draws relying on that cached state — clouds, weather,
 * particles — renders with no depth test and punches through solid geometry. (It doesn't bite on Fabric
 * because that render path re-establishes the state differently.)
 *
 * <p><b>The fix.</b> At the very end of {@code runPipeline}, force the depth-test state back through
 * {@code RenderSystem} so its cache and the GL state agree again (toggling off→on guarantees a real
 * {@code glEnable} regardless of the stale cache). Targeted by string so no Voxy compile dependency is
 * needed; {@code @Coerce} keeps the Voxy {@code Viewport} parameter off Boxy's classpath. {@code require = 0}
 * so it degrades to a no-op if Voxy's signature ever changes.
 */
@Mixin(targets = "me.cortex.voxy.client.core.AbstractRenderPipeline", remap = false)
public class MixinVoxyDepthStateRestore {
    @Inject(method = "runPipeline", at = @At("TAIL"), require = 0)
    private void boxy$restoreDepthState(@Coerce Object viewport, int sourceFrameBuffer, int srcWidth, int srcHeight, CallbackInfo ci) {
        // off→on forces a real glEnable even though RenderSystem's cache still reads "enabled" after Voxy's
        // raw glDisable, bringing the cache and GL back into agreement for Oculus's following passes.
        RenderSystem.disableDepthTest();
        RenderSystem.enableDepthTest();
    }
}
