package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.common.tracking.LiveDirtyChunk;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Block-entity data ({@code setChanged}, every tick on a running machine) is not LOD content. */
@Mixin(Level.class)
public abstract class MixinLiveDirtyQuietBlockEntity {
    @WrapOperation(method = "blockEntityChanged",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;setUnsaved(Z)V"), require = 0)
    private void boxy$quietBlockEntity(LevelChunk chunk, boolean unsaved, Operation<Void> original) {
        ((LiveDirtyChunk) chunk).boxy$setUnsavedQuietly(unsaved);
    }
}
