package com.golem.boxy.vss.mixin;

import me.cortex.voxy.common.world.other.Mapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fixes Voxy 0.2.14's LOD mip chain destroying block light (backport of the upstream 0.2.17-beta
 * Mipper fix).
 *
 * <p><b>The bug.</b> In the all-air case {@code Mipper.mip} averages the eight children's light:
 * it sums {@code getLightId(child) & 0xF0} — values already scaled into the <i>high</i> nibble
 * (multiples of 16) — divides by 8, then builds the light byte as
 * {@code (blockLight << 4) | skyLight}. That second shift pushes the already-high-nibble block
 * light past bit 8, so {@code Mapper.withLight}'s {@code & 0xFF} truncates it to garbage (an
 * all-15 neighbourhood averages to 240, shifts to 3840, masks to 0). Every mip level re-applies
 * the corruption, so torch/lava/glowstone light goes dark in distant LODs while sky light (the
 * low nibble, computed correctly) survives — which is why the bug reads as "distant terrain loses
 * block light at night".
 *
 * <p><b>The fix.</b> Upstream 0.2.17 keeps the averaged value in the high nibble:
 * {@code ((blockLight / 8) & 0xF0) | skyLight}. The composed-but-unmasked value Voxy passes to
 * {@code withLight} still contains everything needed ({@code light == (avg << 4) | sky} as a full
 * int), so a redirect on the single {@code withLight} call in {@code mip} can recover the correct
 * byte: {@code ((light >> 4) & 0xF0) | (light & 0x0F)}.
 *
 * <p>Targeted by string ({@code remap = false}, Voxy class); {@code require = 0} so it degrades to
 * Voxy's original (buggy but harmless) behaviour if the method shape ever changes.
 */
@Mixin(targets = "me.cortex.voxy.common.world.other.Mipper", remap = false)
public class MixinVoxyMipperLightFix {

    @Redirect(method = "mip",
              at = @At(value = "INVOKE",
                       target = "Lme/cortex/voxy/common/world/other/Mapper;withLight(JI)J"),
              require = 0)
    private static long boxy$fixMippedBlockLight(long id, int light) {
        // light == (averagedBlockLight << 4) | averagedSkyLight, where averagedBlockLight is
        // already in high-nibble scale (0..240). Undo the erroneous extra shift and re-mask.
        return Mapper.withLight(id, ((light >> 4) & 0xF0) | (light & 0x0F));
    }
}
