package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.common.tracking.LiveDirtyChunk;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkHolder.class)
public abstract class MixinLiveDirtyQuietLight {
    @WrapOperation(method = "sectionLightChanged",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setUnsaved(Z)V"), require = 0)
    private void boxy$quietLight(ChunkAccess chunk, boolean unsaved, Operation<Void> original) {
        ((LiveDirtyChunk) chunk).boxy$setUnsavedQuietly(unsaved);
    }
}
