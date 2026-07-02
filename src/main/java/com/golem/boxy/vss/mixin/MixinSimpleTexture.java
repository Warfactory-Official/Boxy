package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.BoxyMipmappable;
import com.golem.boxy.vss.config.VSSClientConfig;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes resource-loaded <b>entity</b> textures ({@code textures/entity/**}, incl. default player skins)
 * through the mipmapped upload (see {@link MixinAbstractTexture}). Scoped by path so GUI/other textures —
 * which never minify and would only get blurry — are untouched. Gated by the client {@code mipmapEntityTextures}
 * toggle (off by default) — when off, vanilla loading runs unchanged. {@code doLoad} = SRG {@code m_118136_};
 * field {@code location} = {@code f_118129_}. Client-only; hand-SRG, {@code remap = false}.
 */
@Mixin(value = SimpleTexture.class, remap = false)
public abstract class MixinSimpleTexture {
    @Shadow
    @Final
    protected ResourceLocation f_118129_; // SimpleTexture.location

    @Inject(method = "m_118136_", at = @At("HEAD"), cancellable = true, require = 0)
    private void boxy$mipmapEntityTexture(NativeImage image, boolean blur, boolean clamp, CallbackInfo ci) {
        if (VSSClientConfig.CONFIG.mipmapEntityTextures
                && this.f_118129_ != null && this.f_118129_.getPath().startsWith("textures/entity/")) {
            ((BoxyMipmappable) (Object) this).boxy$uploadMipmapped(image, blur, clamp);
            ci.cancel();
        }
    }
}
