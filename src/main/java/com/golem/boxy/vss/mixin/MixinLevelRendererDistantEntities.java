package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.ClientEntitySync;
import com.golem.boxy.vss.client.DistantEntityDepthFix;
import com.golem.boxy.vss.client.DistantEntityLighting;
import com.golem.boxy.vss.common.TrackedEntityTypes;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The native render-loop gates and per-entity depth pass; unrelated entity types retain vanilla behavior. */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRendererDistantEntities {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void boxy$beginDepthFrame(CallbackInfo ci) {
        com.golem.boxy.vss.client.VoxyLodDepthJoin.beginFrame();
    }
    @WrapOperation(method = "renderLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiled(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean boxy$renderEntitiesInUncompiledChunks(LevelRenderer instance, BlockPos pos,
            Operation<Boolean> original, @Local Entity entity) {
        boolean compiled = original.call(instance, pos);
        if (!compiled && ClientEntitySync.enabled() && TrackedEntityTypes.clientContains(entity.getType())) {
            TrackedEntityTypes.diagChunkBypass();
            return true;
        }
        return compiled;
    }

    @WrapOperation(method = "renderLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
    private boolean boxy$overrideForeignEntityCull(EntityRenderDispatcher dispatcher, Entity entity,
            Frustum frustum, double camX, double camY, double camZ, Operation<Boolean> original) {
        boolean visible = original.call(dispatcher, entity, frustum, camX, camY, camZ);
        if (visible || !ClientEntitySync.enabled() || !TrackedEntityTypes.clientContains(entity.getType())) return visible;
        double max = (double) ClientEntitySync.distanceChunks() * 16.0;
        if (entity.distanceToSqr(camX, camY, camZ) > max * max) return false;
        AABB bounds = entity.getBoundingBoxForCulling().inflate(0.5);
        if (bounds.hasNaN() || bounds.getSize() == 0.0) {
            bounds = new AABB(entity.getX() - 2, entity.getY() - 2, entity.getZ() - 2,
                    entity.getX() + 2, entity.getY() + 2, entity.getZ() + 2);
        }
        boolean inFrustum = entity.noCulling || frustum.isVisible(bounds);
        if (!inFrustum && entity instanceof Leashable leashable && leashable.getLeashHolder() != null) {
            inFrustum = frustum.isVisible(leashable.getLeashHolder().getBoundingBoxForCulling());
        }
        if (inFrustum) TrackedEntityTypes.diagForeignCullOverride(entity.getType());
        return inFrustum;
    }

    @WrapOperation(method = "renderEntity",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private void boxy$renderDistantEntityRebanded(EntityRenderDispatcher dispatcher, Entity entity,
            double x, double y, double z, float yaw, float partialTicks, PoseStack poseStack,
             MultiBufferSource bufferSource, int packedLight, Operation<Void> original) {
        int light = DistantEntityLighting.resolve(entity, partialTicks, packedLight);
        if (ClientEntitySync.enabled() && TrackedEntityTypes.clientContains(entity.getType())) {
            com.golem.boxy.vss.client.VoxyLodDepthJoin.joinIfNeeded();
        }
        if (!DistantEntityDepthFix.applies(entity, x, y, z, bufferSource)) {
            original.call(dispatcher, entity, x, y, z, yaw, partialTicks, poseStack, bufferSource, light);
            return;
        }
        DistantEntityDepthFix.render(entity, x, y, z, poseStack,
                () -> original.call(dispatcher, entity, x, y, z, yaw, partialTicks, poseStack,
                        DistantEntityDepthFix.buffer(), light));
    }
}
