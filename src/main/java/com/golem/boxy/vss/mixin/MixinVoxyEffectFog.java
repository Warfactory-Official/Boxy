package com.golem.boxy.vss.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shaderpacks do not consistently fog LOD terrain during blindness/darkness. No GL state changes. */
@Mixin(targets = "me.cortex.voxy.client.core.AbstractRenderPipeline", remap = false)
public class MixinVoxyEffectFog {
    @Inject(method = "runPipeline", at = @At("HEAD"), cancellable = true, require = 0)
    private void boxy$skipLodsWhileBlinded(@Coerce Object viewport, int framebuffer, int width, int height, CallbackInfo ci) {
        var camera = Minecraft.getInstance().getCameraEntity();
        if (camera instanceof net.minecraft.world.entity.LivingEntity entity
                && (entity.hasEffect(MobEffects.BLINDNESS) || entity.hasEffect(MobEffects.DARKNESS))) {
            ci.cancel();
        }
    }
}
