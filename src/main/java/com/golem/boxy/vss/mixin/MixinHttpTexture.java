package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.BoxyMipmappable;
import com.golem.boxy.vss.config.VSSClientConfig;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.HttpTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes downloaded player skins ({@code HttpTexture}) through the mipmapped upload (see
 * {@link MixinAbstractTexture}) — these are the custom skins that {@link MixinSimpleTexture} (resource
 * textures only) doesn't cover. Gated by the client {@code mipmapEntityTextures} toggle (off by default).
 * {@code upload(NativeImage)} = SRG {@code m_118020_}. Client-only; hand-SRG, {@code remap = false}.
 */
@Mixin(value = HttpTexture.class, remap = false)
public abstract class MixinHttpTexture {
    @Inject(method = "m_118020_", at = @At("HEAD"), cancellable = true, require = 0)
    private void boxy$mipmapSkin(NativeImage image, CallbackInfo ci) {
        if (VSSClientConfig.CONFIG.mipmapEntityTextures) {
            ((BoxyMipmappable) (Object) this).boxy$uploadMipmapped(image, false, false);
            ci.cancel();
        }
    }
}
