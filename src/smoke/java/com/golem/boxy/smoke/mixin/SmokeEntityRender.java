package com.golem.boxy.smoke.mixin;

import com.golem.boxy.smoke.BoxyClientSmoke;
import com.golem.boxy.smoke.OcclusionSmoke;
import com.golem.boxy.vss.config.DistantEntityDepthMode;
import com.golem.boxy.vss.config.VSSClientConfig;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.lwjgl.opengl.GL15;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public class SmokeEntityRender {
    @WrapMethod(method = "render")
    private void boxy$observeUnbandedDraw(Entity entity, double x, double y, double z, float yaw, float partialTick,
            PoseStack pose, MultiBufferSource source, int light, Operation<Void> original) {
        if (!Boolean.getBoolean("boxy.smokeOcclusion") || !entity.getUUID().equals(OcclusionSmoke.target)
                || VSSClientConfig.CONFIG.distantEntityDepthMode != DistantEntityDepthMode.OFF) {
            original.call(entity, x, y, z, yaw, partialTick, pose, source, light);
            return;
        }
        if (!(source instanceof MultiBufferSource.BufferSource buffers)) throw new AssertionError("Unsupported test buffer source");
        buffers.endBatch(); // Exclude geometry queued by other entities from this measurement.
        int query = GL15.glGenQueries();
        GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, query);
        try {
            original.call(entity, x, y, z, yaw, partialTick, pose, source, light);
            buffers.endBatch();
        } finally {
            GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
            OcclusionSmoke.samples += Integer.toUnsignedLong(GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT));
            OcclusionSmoke.measuredPasses++;
            GL15.glDeleteQueries(query);
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void boxy$observeDraw(Entity entity, double x, double y, double z, float yaw, float partialTick,
            PoseStack pose, MultiBufferSource source, int light, CallbackInfo ci) {
        if (entity.getType() == EntityType.PIG && x * x + y * y + z * z > 128 * 128) {
            BoxyClientSmoke.distantPigDraws++;
            if (RenderSystem.getProjectionMatrix().m32() < -10) {
                BoxyClientSmoke.bandedPigDraws++;
            }
        }
    }
}
