package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.BoxyMipmappable;
import com.golem.boxy.vss.common.VSSLogger;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives entity/skin textures mipmaps so they don't alias into garbled noise at the long distances Boxy now
 * renders them. Vanilla loads entity textures with 0 mip levels and the entity RenderTypes filter them
 * NEAREST/no-mipmap ({@code TextureStateShard(tex, false, false)}) — fine at normal range, but heavy
 * minification turns the unfiltered full-res sampling into shimmer/garble.
 *
 * <p>{@link #boxy$uploadMipmapped} rebuilds the texture with an alpha-aware mip chain
 * ({@link MipmapGenerator}) and {@code NEAREST_MIPMAP_LINEAR} filtering — pixel-crisp up close, clean LOD
 * far. The entity RenderType's {@code TextureStateShard} re-asserts a {@code (blur, false)} filter on every
 * bind (confirmed in 1.20.1: its setup calls {@code AbstractTexture.setFilter}), which resets our texture to
 * a non-mipmap min filter — so {@link #boxy$keepMipmap} runs at the <b>tail</b> of {@code setFilter} (where
 * this texture is already bound) and restores the mipmap min filter for any texture we've mipmapped (a
 * no-op for everything else). Falls back to a plain upload for non-power-of-two sizes or on any error, so a
 * texture never breaks. Client-only; only exercised when the client opts in via {@code mipmapEntityTextures}
 * (off by default) — the entry points ({@link MixinSimpleTexture}, {@link MixinHttpTexture}) gate on it.
 * Hand-SRG, {@code remap = false}.
 */
@Mixin(value = AbstractTexture.class, remap = false)
public abstract class MixinAbstractTexture implements BoxyMipmappable {
    @Unique
    private boolean boxy$mipmapped;

    @Override
    public boolean boxy$isMipmapped() {
        return this.boxy$mipmapped;
    }

    @Override
    public void boxy$uploadMipmapped(NativeImage image, boolean blur, boolean clamp) {
        AbstractTexture self = (AbstractTexture) (Object) this;
        int w = image.getWidth();
        int h = image.getHeight();
        // Full mip chain down to ~1x1 so the texture stays clean at the extreme distances Boxy renders
        // entities. Capping at 4 (vanilla's atlas default) left a 16x16 floor for a 256x256 texture, which
        // still aliased into garble when the entity shrank below that. numberOfTrailingZeros stops at the
        // first odd dimension, so non-power-of-two sizes still halve cleanly.
        int levels = Math.min(Integer.numberOfTrailingZeros(w), Integer.numberOfTrailingZeros(h));
        if (levels <= 0) {
            boxy$plainUpload(self, image, blur, clamp);
            return;
        }
        try {
            NativeImage[] mips = MipmapGenerator.generateMipLevels(new NativeImage[]{image}, levels);
            TextureUtil.prepareImage(self.getId(), levels, w, h);
            for (int i = 0; i < mips.length; i++) {
                mips[i].upload(i, 0, 0, 0, 0, mips[i].getWidth(), mips[i].getHeight(), false, clamp, false, true);
            }
            this.boxy$mipmapped = true;
            self.setFilter(false, true); // NEAREST_MIPMAP_LINEAR
        } catch (Throwable t) {
            VSSLogger.warn("Boxy: failed to mipmap a texture, using plain upload: " + t);
            boxy$plainUpload(self, image, blur, clamp);
        }
    }

    @Unique
    private static void boxy$plainUpload(AbstractTexture self, NativeImage image, boolean blur, boolean clamp) {
        TextureUtil.prepareImage(self.getId(), 0, image.getWidth(), image.getHeight());
        image.upload(0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), blur, clamp, false, true);
    }

    // The entity RenderType calls setFilter(blur, false) on every bind, resetting our texture to a non-mipmap
    // min filter. setFilter has just bound this texture, so at TAIL we restore the mipmap min filter for the
    // textures we mipmapped. (Direct GL is used rather than a @ModifyVariable on the arg, which was
    // unreliable at HEAD.) m_117960_ = AbstractTexture.setFilter(ZZ)V.
    @Inject(method = "m_117960_", at = @At("TAIL"), require = 0)
    private void boxy$keepMipmap(boolean blur, boolean mipmap, CallbackInfo ci) {
        if (this.boxy$mipmapped && !mipmap) {
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                    blur ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_NEAREST_MIPMAP_LINEAR);
        }
    }
}
