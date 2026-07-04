package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.ClientEntitySync;
import com.golem.boxy.vss.client.DistantEntityDepthFix;
import com.golem.boxy.vss.common.TrackedEntityTypes;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
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
 *
 * <p>A second wrap, on the {@code EntityRenderDispatcher.render} call inside
 * {@code LevelRenderer.renderEntity} (SRG {@code m_109517_} → {@code m_114384_}), routes qualifying distant
 * entities through {@link DistantEntityDepthFix}: their draws go to a private buffer flushed with a
 * re-banded projection + depth range so they stop z-fighting at distance (§11 of the developer guide).
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

    /**
     * Safety net against third-party entity cullers. Mods like Sodium/Embeddium Extra add their own
     * entity-distance culling by injecting deeper in the chain ({@code EntityRenderer.shouldRender}), which
     * vetoes distant tracked entities before Boxy's distance gate (gate 2) can speak — the whole feature
     * silently stops rendering. We wrap the {@code EntityRenderDispatcher.shouldRender} call (SRG
     * {@code m_114397_}) in {@code renderLevel}: when it returns false for a tracked type while the feature
     * is on, we re-run the decision vanilla would have made — Boxy's extended distance cull plus the real
     * frustum test — so a foreign distance veto is overridden without breaking frustum culling.
     */
    @WrapOperation(
            method = "m_109599_",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;m_114397_(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"),
            require = 0)
    private boolean boxy$overrideForeignEntityCull(EntityRenderDispatcher dispatcher, Entity entity,
            Frustum frustum, double camX, double camY, double camZ, Operation<Boolean> original) {
        boolean visible = original.call(dispatcher, entity, frustum, camX, camY, camZ);
        if (visible || !ClientEntitySync.enabled() || !TrackedEntityTypes.clientContains(entity.getType())) {
            return visible;
        }
        double max = (double) ClientEntitySync.distanceChunks() * 16.0;
        if (entity.distanceToSqr(camX, camY, camZ) > max * max) {
            return false;
        }
        boolean inFrustum = entity.noCulling || frustum.isVisible(entity.getBoundingBoxForCulling().inflate(0.5));
        if (inFrustum) {
            TrackedEntityTypes.diagForeignCullOverride(entity.getType());
        }
        return inFrustum;
    }

    @WrapOperation(
            method = "m_109517_",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;m_114384_(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"),
            require = 0)
    private void boxy$renderDistantEntityRebanded(EntityRenderDispatcher dispatcher, Entity entity,
            double x, double y, double z, float yaw, float partialTicks, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight, Operation<Void> original) {
        if (!DistantEntityDepthFix.applies(entity, x, y, z, bufferSource)) {
            original.call(dispatcher, entity, x, y, z, yaw, partialTicks, poseStack, bufferSource, packedLight);
            return;
        }
        // The build runnable submits the entity into the fix's private buffer; PRECISE mode runs it several
        // times (mask / fine / depth-restore passes). The helper flushes after every build, so a throwing
        // renderer can't leak partial geometry into a later flush.
        DistantEntityDepthFix.render(entity, x, y, z, poseStack,
                () -> original.call(dispatcher, entity, x, y, z, yaw, partialTicks, poseStack,
                        DistantEntityDepthFix.buffer(), packedLight));
    }
}
