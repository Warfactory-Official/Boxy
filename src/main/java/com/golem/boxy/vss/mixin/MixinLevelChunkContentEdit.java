package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.common.tracking.LiveDirtyChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Content edits seen by the save hook: block sets (incl. un-notified, flag 0) and fresh promotion from worldgen. */
@Mixin(LevelChunk.class)
public abstract class MixinLevelChunkContentEdit {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void boxy$blockSet(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null) ((LiveDirtyChunk) this).boxy$markContentEdited();
    }

    @Inject(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
            at = @At("RETURN"))
    private void boxy$generated(CallbackInfo ci) {
        ((LiveDirtyChunk) this).boxy$markContentEdited();
    }
}
