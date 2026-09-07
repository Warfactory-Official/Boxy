package com.golem.boxy.smoke;

import net.minecraft.client.Minecraft;

/** Moving-camera captures in a separate disposable configuration, with terrain streaming disabled. */
public final class StabilitySmoke {
    private static int ticks;
    private static int captures;
    private static int[] stationaryQuads;
    private static int unstableSamples;

    public static void tick(Minecraft mc) {
        ticks++;
        mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        mc.player.setXRot(25);
        mc.player.setYRot(-90 + (ticks >= 200 && ticks < 500 ? (float) Math.sin((ticks - 200) * 0.07) * 25 : 0));
        if (ticks == 200) {
            com.golem.boxy.vss.config.VSSClientConfig.CONFIG.extendEntityRenderDistance = false;
        }
        if (ticks >= 200 && ticks % 40 == 0) {
            String phase = Boolean.getBoolean("boxy.smokeStabilityControl") ? "control" : "repair";
            try (var image = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(new java.io.File(mc.gameDirectory, "stability-" + phase + "-" + captures++ + ".png"));
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            int error = org.lwjgl.opengl.GL11.glGetError();
            if (error != org.lwjgl.opengl.GL11.GL_NO_ERROR) throw new AssertionError("GL error during stability test: " + error);
            com.mojang.logging.LogUtils.getLogger().info("BOXY_STABILITY: tick={} quads={} visible={} depthMask={} stencil={} error={}",
                    ticks, java.util.Arrays.toString(me.cortex.voxy.client.RenderStatistics.quadCount),
                    java.util.Arrays.toString(me.cortex.voxy.client.RenderStatistics.visibleSections),
                    org.lwjgl.opengl.GL11.glGetBoolean(org.lwjgl.opengl.GL11.GL_DEPTH_WRITEMASK),
                    org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_STENCIL_TEST), error);
            if (ticks >= 520) {
                int[] current = me.cortex.voxy.client.RenderStatistics.quadCount.clone();
                if (stationaryQuads != null && !java.util.Arrays.equals(stationaryQuads, current)) unstableSamples++;
                stationaryQuads = current;
                var info = new java.util.ArrayList<String>();
                me.cortex.voxy.client.core.IGetVoxyRenderSystem.getNullable().addDebugInfo(info);
                com.mojang.logging.LogUtils.getLogger().info("BOXY_STABILITY_DETAIL: camera={} debug={}", mc.gameRenderer.getMainCamera().getPosition(), info);
            }
        }
        if (ticks >= 640) {
            if (stationaryQuads == null || java.util.Arrays.stream(stationaryQuads).sum() == 0 || unstableSamples != 0) {
                throw new AssertionError("Stationary LOD geometry did not stabilize: " + unstableSamples + " changes");
            }
            com.mojang.logging.LogUtils.getLogger().info("BOXY_CLIENT_SMOKE_OK: moving-camera run settled to identical stationary LOD geometry");
            mc.stop();
        }
    }
}
