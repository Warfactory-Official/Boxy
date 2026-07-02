package com.golem.boxy.vss.client;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Implemented on {@code AbstractTexture} (via {@code MixinAbstractTexture}) so the entity/skin texture
 * loaders can request an alpha-aware mipmapped upload instead of vanilla's single-level one. Lives outside
 * the {@code mixin} package so it can be referenced directly (Mixin forbids direct references to non-mixin
 * classes inside a declared mixin package).
 */
public interface BoxyMipmappable {
    void boxy$uploadMipmapped(NativeImage image, boolean blur, boolean clamp);

    boolean boxy$isMipmapped();
}
