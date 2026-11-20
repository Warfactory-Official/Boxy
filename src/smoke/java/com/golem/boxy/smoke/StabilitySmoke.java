package com.golem.boxy.smoke;

import com.golem.boxy.vss.config.VSSClientConfig;
import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.opengl.GL11;

/** Moving-camera captures in a separate disposable configuration, with terrain streaming disabled. */
public final class StabilitySmoke {
    private static int ticks;
    private static int captures;
    private static int[] stationaryQuads;
    private static int unstableSamples;

    public static void tick(Minecraft mc) {
        ticks++;
        mc.options.cloudStatus().set(CloudStatus.OFF);
        mc.player.setXRot(25);
        mc.player.setYRot(-90 + (ticks >= 200 && ticks < 500 ? (float) Math.sin((ticks - 200) * 0.07) * 25 : 0));
        if (ticks == 200) {
            VSSClientConfig.CONFIG.extendEntityRenderDistance = false;
        }
        if (ticks >= 200 && ticks % 40 == 0) {
            if (!PerfSampler.enabled()) {
                String phase = Boolean.getBoolean("boxy.smokeStabilityControl") ? "control" : "repair";
                try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    image.writeToFile(new File(mc.gameDirectory, "stability-" + phase + "-" + captures++ + ".png"));
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) throw new AssertionError("GL error during stability test: " + error);
            if (!PerfSampler.enabled()) {
                LogUtils.getLogger().info("BOXY_STABILITY: tick={} quads={} visible={} depthMask={} stencil={} error={}",
                        ticks, Arrays.toString(me.cortex.voxy.client.RenderStatistics.quadCount),
                        Arrays.toString(me.cortex.voxy.client.RenderStatistics.visibleSections),
                        GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                        GL11.glIsEnabled(GL11.GL_STENCIL_TEST), error);
            }
            if (ticks >= 520) {
                int[] current = me.cortex.voxy.client.RenderStatistics.quadCount.clone();
                if (stationaryQuads != null && !Arrays.equals(stationaryQuads, current)) unstableSamples++;
                stationaryQuads = current;
                if (!PerfSampler.enabled()) {
                    var info = new ArrayList<String>();
                    me.cortex.voxy.client.core.IGetVoxyRenderSystem.getNullable().addDebugInfo(info);
                    LogUtils.getLogger().info("BOXY_STABILITY_DETAIL: camera={} debug={}", mc.gameRenderer.getMainCamera().getPosition(), info);
                }
            }
        }
        if (ticks >= 640 && (!PerfSampler.enabled() || PerfSampler.measuredTicks() >= 400L)) {
            if (stationaryQuads == null || Arrays.stream(stationaryQuads).sum() == 0 || unstableSamples != 0) {
                throw new AssertionError("Stationary LOD geometry did not stabilize: " + unstableSamples + " changes");
            }
            PerfSampler.finish(mc);
            LogUtils.getLogger().info("BOXY_CLIENT_SMOKE_OK: moving-camera run settled to identical stationary LOD geometry");
            mc.stop();
        }
    }
}
