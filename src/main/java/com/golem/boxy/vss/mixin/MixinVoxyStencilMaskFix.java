package com.golem.boxy.vss.mixin;

import org.lwjgl.opengl.GL11C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes "Voxy LODs don't render" when a mod that manipulates the GL stencil buffer — most notably
 * <b>ModularUI</b> — is installed alongside Boxy.
 *
 * <p><b>The bug.</b> Voxy masks its LODs to the region beyond vanilla terrain using the stencil buffer. Its
 * {@code AbstractRenderPipeline.initDepthStencil} starts by clearing its framebuffer's stencil to 1
 * ({@code glClearNamedFramebufferfi(targetFb, GL_DEPTH_STENCIL, 0, 1.0f, 1)}) and only sets
 * {@code glStencilMask(0xFF)} a few lines <em>later</em>. {@code glClearNamedFramebufferfi} honours the stencil
 * write mask. ModularUI runs {@code Stencil.reset()} every render tick, which leaves the GL stencil write mask at
 * {@code 0x00}; that state persists into Voxy's pipeline, so Voxy's stencil clear is masked to a no-op. The
 * LOD-region mask is therefore never established, and Voxy's rasterized occlusion cull — whose
 * {@code early_fragment_tests} include a stencil {@code EQUAL 1} test — rejects <em>every</em> section. Result:
 * the whole Voxy pipeline runs cleanly (no crash, no GL error, geometry streamed and traversed) but 0 sections
 * pass the cull, so no LODs ever draw.
 *
 * <p>This is <b>independent</b> of Forge's {@code RenderTarget.enableStencil()} (which ModularUI also calls): the
 * culprit is the stencil <em>write mask</em>, not the presence of a stencil attachment or the stencil test state —
 * which is why detaching the attachment, neutralising the test, and skipping {@code enableStencil} all failed to
 * fix it.
 *
 * <p><b>The fix.</b> Force the stencil write mask back to {@code 0xFF} at the head of {@code initDepthStencil}, so
 * Voxy's own clear lands and its mask-build logic runs correctly. It's a no-op when the mask is already {@code 0xFF}
 * (i.e. without ModularUI), so Voxy's behaviour is unchanged in the common case. Targeted by string so no Voxy
 * compile dependency is needed; {@code remap = false} (Voxy class); {@code require = 0} to degrade gracefully if
 * Voxy's signature changes.
 */
@Mixin(targets = "me.cortex.voxy.client.core.AbstractRenderPipeline", remap = false)
public class MixinVoxyStencilMaskFix {

    @Inject(method = "initDepthStencil", at = @At("HEAD"), require = 0)
    private void boxy$restoreStencilWriteMask(int sourceFrameBuffer, int targetFb, int srcWidth, int srcHeight,
                                              int width, int height, CallbackInfo ci) {
        GL11C.glStencilMask(0xFF);
    }
}
