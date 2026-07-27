package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.config.VSSClientConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes Voxy's hierarchical-Z occlusion buffer conservative, fixing LOD sections that vanish (leaving
 * black/empty patches, worst toward the screen edges) at high field of view.
 *
 * <p><b>The bug.</b> {@code HiZBuffer.buildMipChain} allocates the HiZ texture at
 * {@code Integer.highestOneBit(width) x Integer.highestOneBit(height)} — the <i>previous</i> power of two.
 * At 1920x1080 that makes level 0 a 1024x512 texture: a 1.875x / 2.11x downscale of the depth buffer.
 * Level 0 is filled by {@code hiz/blit.fsh}, which reads a single {@code textureGather} — exactly 2x2
 * source texels — while it needs to cover ~4 of them. Source texels are therefore missed, and the stored
 * value is an <b>under</b>-estimate of the true maximum depth.
 *
 * <p>A hierarchical-Z buffer is only correct if it over-estimates. {@code screenspace.glsl} culls a node
 * when {@code pointSample < _minBB.z}, so an under-estimated max <b>over-culls</b>: whole LOD nodes are
 * dropped by {@code traversal_dev.comp}'s {@code if (outsideFrustum() || isCulledByHiz())}, taking their
 * children with them. No error, no log line — just missing terrain.
 *
 * <p><b>Why it scales with FOV.</b> Missing a source texel only matters where depth changes sharply
 * between neighbouring pixels. A high FOV compresses grazing, oblique views into the screen periphery,
 * where the per-pixel depth gradient is largest — so the under-sampling error is worst exactly at the
 * screen edges, and grows steeply as the corners open further off-axis. It affects both the vanilla and
 * the Iris pipelines because the occlusion traversal is shared by both.
 *
 * <p><b>The fix.</b> Round <i>up</i> to the next power of two instead, so level 0 is at least the depth
 * buffer's resolution and the 2x2 gather over-covers. Every later mip is then an exact 2x reduction of a
 * power-of-two texture, where the gather is exact — so the whole chain becomes conservative. The redirect
 * covers all four {@code highestOneBit} call sites in the method (the two in the resize comparison and the
 * two passed to {@code alloc}) so the cached {@code width}/{@code height} stay consistent; {@code alloc}
 * derives the mip count from the size it is given and {@code getPackedLevels()} reads the same fields, so
 * the {@code packedHizSize} uniform the shader indexes with follows automatically — no shader change.
 *
 * <p>Costs memory: at 1920x1080 the HiZ becomes 2048x2048 rather than 1024x512 (~22 MB vs ~2.8 MB with
 * mips). Gated on {@link VSSClientConfig#conservativeHiZ} (default on, read live — toggling triggers the
 * existing size-change reallocation on the next frame).
 *
 * <p>Targeted by string ({@code remap = false}, Voxy class); {@code require = 0} so it degrades to Voxy's
 * original behaviour if the method shape ever changes.
 *
 * <p><b>Mixin gotcha encoded here:</b> the handler is deliberately an <i>instance</i> method even though
 * the call it redirects ({@code Integer.highestOneBit}) is static. Mixin matches the handler's
 * {@code static} modifier against the <b>enclosing</b> target method — {@code buildMipChain}, an instance
 * method — not against the redirected call. Getting this wrong is an
 * {@code InvalidInjectionException: 'static' modifier of handler method does not match target}, which
 * fails the whole mixin at apply time <b>despite {@code require = 0}</b> (a validation error, unlike a
 * missing target, which degrades) and crashes the game when Voxy first loads {@code HiZBuffer}.
 */
@Mixin(targets = "me.cortex.voxy.client.core.rendering.util.HiZBuffer", remap = false)
public class MixinVoxyHiZConservative {

    @Redirect(method = "buildMipChain",
              at = @At(value = "INVOKE", target = "Ljava/lang/Integer;highestOneBit(I)I"),
              require = 0)
    private int boxy$roundHiZSizeUp(int value) {
        if (!VSSClientConfig.CONFIG.conservativeHiZ) {
            return Integer.highestOneBit(value);
        }
        // Smallest power of two >= value, clamped to 1<<30 so the shift cannot overflow.
        if (value <= 1) {
            return 1;
        }
        if (value > (1 << 30)) {
            return 1 << 30;
        }
        return Integer.highestOneBit(value - 1) << 1;
    }
}
