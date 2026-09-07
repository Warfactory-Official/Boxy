package com.golem.boxy.smoke.mixin;

import com.golem.boxy.smoke.OcclusionSmoke;
import com.golem.boxy.vss.client.DistantEntityDepthFix;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL15;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = DistantEntityDepthFix.class, remap = false)
public class SmokeDepthQuery {
    @Unique private static boolean boxy$measure;
    @Unique private static boolean boxy$logged;

    @WrapMethod(method = "render")
    private static void boxy$selectEntity(Entity entity, double x, double y, double z, PoseStack pose, Runnable build, Operation<Void> original) {
        boxy$measure = Boolean.getBoolean("boxy.smokeOcclusion") && entity.getUUID().equals(OcclusionSmoke.target);
        if (boxy$measure && !boxy$logged) {
            boxy$logged = true;
            var projection = com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix();
            com.mojang.logging.LogUtils.getLogger().info("BOXY_DEPTH_TARGET: clipMode={}, m22={}, m32={}",
                    org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL45.GL_CLIP_DEPTH_MODE), projection.m22(), projection.m32());
        }
        try {
            original.call(entity, x, y, z, pose, build);
        } finally {
            boxy$measure = false;
        }
    }

    @WrapMethod(method = "flushBanded")
    private static void boxy$measureSamples(Runnable build, Matrix4f band, Matrix4f restore, VertexSorting sorting,
            double lo, double hi, Operation<Void> original) {
        if (!boxy$measure) {
            original.call(build, band, restore, sorting, lo, hi);
            return;
        }
        int query = GL15.glGenQueries();
        GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, query);
        try {
            original.call(build, band, restore, sorting, lo, hi);
        } finally {
            GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
            OcclusionSmoke.samples += Integer.toUnsignedLong(GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT));
            OcclusionSmoke.measuredPasses++;
            GL15.glDeleteQueries(query);
        }
    }
}
