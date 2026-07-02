package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.ClientEntitySync;
import com.golem.boxy.vss.common.TrackedEntityTypes;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The vanilla entity render loop ({@code LevelRenderer.renderLevel}, SRG {@code m_109599_}) only draws an
 * entity whose chunk is compiled: {@code isOutsideBuildHeight(y) || isChunkCompiled(pos)} (SRG
 * {@code m_202430_}). Distant entities we now track sit in chunks the client never loads (only Voxy LOD is
 * there), so {@code isChunkCompiled} is false and they're skipped — even though they're tracked, in the
 * entity store, and pass the distance-cull override. This was the real reason distant players "unloaded".
 *
 * <p>We wrap that single {@code isChunkCompiled} call so entities in un-compiled chunks still render when
 * the feature is on. Only the server-extended configured types ever sit in such chunks, so this is
 * naturally scoped to them. Client-only ({@code boxy.mixins.json} "client"); hand-SRG, {@code remap = false};
 * {@code require = 0} to degrade gracefully. Uses MixinExtras {@code @WrapOperation} (on Boxy's classpath).
 */
@Mixin(value = LevelRenderer.class, remap = false)
public abstract class MixinLevelRendererDistantEntities {
    @WrapOperation(
            method = "m_109599_",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;m_202430_(Lnet/minecraft/core/BlockPos;)Z"),
            require = 0)
    private boolean boxy$renderEntitiesInUncompiledChunks(LevelRenderer instance, BlockPos pos, Operation<Boolean> original) {
        boolean compiled = original.call(instance, pos);
        if (!compiled && ClientEntitySync.enabled()) {
            TrackedEntityTypes.diagChunkBypass();
            return true;
        }
        return compiled;
    }
}
