package com.golem.boxy.vss.mixin;

import me.cortex.voxy.common.util.MemoryBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for Voxy's private {@code ModelFactory$ModelBakeResultUpload} — the per-model bake
 * result {@code processTextureBakeResult} returns. {@link MixinVoxyModelFactoryFixes} uses it to
 * read the fresh model id and the model record buffer (whose {@code modelFlags} int at offset 24
 * distinguishes the tint cases) from the opaque return value.
 */
@Mixin(targets = "me.cortex.voxy.client.core.model.ModelFactory$ModelBakeResultUpload", remap = false)
public interface AccessorVoxyModelBakeUpload {

    @Accessor(value = "modelId", remap = false)
    int boxy$getModelId();

    @Accessor(value = "model", remap = false)
    MemoryBuffer boxy$getModel();
}
