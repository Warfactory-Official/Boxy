package com.golem.boxy.vss.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;

/**
 * Measures dark LOD water on screen as the camera turns. At each yaw it holds the camera still until Voxy settles,
 * grabs the frame, then grabs a second frame with Voxy's lightmap swapped for white ({@code MixinVoxyUnlitLightmap}).
 * A pixel that is water-coloured unlit but much darker lit is LOD water starved of light; a pixel black in both is
 * dark for some other reason (culling, blending). Writes lit/unlit/overlay PNGs and a CSV per run under
 * {@code boxy-lodwater/}.
 */
public final class LodWaterSweep {
    /** Read by {@code MixinVoxyUnlitLightmap} on the render thread. */
    public static volatile boolean unlit;
    private static DynamicTexture white;

    private static boolean active;
    private static int steps, settle, step, wait;
    private static float pitch, startYaw, savedYaw, savedPitch;
    private static boolean savedHideGui;
    private static NativeImage lit;
    private static File dir;
    private static final List<String> csv = new ArrayList<>();
    private static double worst = -1, best = Double.MAX_VALUE;
    private static int worstYaw, bestYaw;

    private LodWaterSweep() {
    }

    public static boolean running() {
        return active;
    }

    /** The highest starved-water share of the screen, in percent, over the last sweep's angles. */
    public static double worstWaterPct() {
        return worst;
    }

    public static int whiteTexture() {
        if (white == null) {
            NativeImage image = new NativeImage(16, 16, false);
            image.fillRect(0, 0, 16, 16, 0xFFFFFFFF);
            white = new DynamicTexture(image);
        }
        return white.getId();
    }

    public static String start(Minecraft mc, int stepCount, float pitchDeg, int settleTicks) {
        if (active) {
            return "A sweep is already running.";
        }
        if (mc.player == null || mc.level == null) {
            return "Not in a world.";
        }
        dir = new File(mc.gameDirectory, "boxy-lodwater/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        try {
            Files.createDirectories(dir.toPath());
        } catch (IOException e) {
            return "Could not create " + dir + ": " + e.getMessage();
        }
        steps = stepCount;
        pitch = pitchDeg;
        settle = settleTicks;
        step = 0;
        wait = settle;
        savedYaw = startYaw = mc.player.getYRot();
        savedPitch = mc.player.getXRot();
        savedHideGui = mc.options.hideGui;
        mc.options.hideGui = true;
        worst = -1;
        best = Double.MAX_VALUE;
        csv.clear();
        long day = mc.level.getDayTime() % 24000;
        boolean shaders = me.cortex.voxy.client.core.util.IrisUtil.irisShaderPackEnabled();
        csv.add(String.format("# pos=%.1f,%.1f,%.1f fov=%d dayTime=%d shaderpack=%b settleTicks=%d",
                mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), mc.options.fov().get(), day, shaders, settle));
        csv.add("step,yaw,pitch,starvedWaterPct,starvedOtherPct,blackBothPct");
        active = true;
        String warn = (day > 12500 && day < 23500 ? " It is night: everything reads dark, run at /time set noon." : "")
                + (shaders ? " A shaderpack is active: it lights LODs itself, so the unlit frame is not unlit." : "");
        return "Sweeping " + steps + " yaws at pitch " + pitchDeg + ", " + settle + " ticks each. Keep the mouse still."
                + warn;
    }

    static void tick(Minecraft mc) {
        if (!active) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            finish(mc, "Sweep aborted: left the world.");
            return;
        }
        float yaw = startYaw + 360f * step / steps;
        // re-applied every tick so stray mouse input cannot move the measured view
        mc.player.setYRot(yaw);
        mc.player.yRotO = yaw;
        mc.player.setXRot(pitch);
        mc.player.xRotO = pitch;
        if (--wait > 0) {
            return;
        }
        if (lit == null) {
            lit = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            unlit = true;
            wait = 3; // a full frame must render with the white lightmap before the grab
            return;
        }
        NativeImage dark = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        unlit = false;
        try {
            record(Math.round(yaw - startYaw), dark);
        } catch (IOException e) {
            finish(mc, "Sweep failed writing images: " + e.getMessage());
            return;
        } finally {
            lit.close();
            lit = null;
            dark.close();
        }
        if (++step >= steps) {
            try {
                Files.write(new File(dir, "sweep.csv").toPath(), csv);
            } catch (IOException e) {
                finish(mc, "Sweep failed writing CSV: " + e.getMessage());
                return;
            }
            finish(mc, String.format("Sweep done. Starved water %.2f%% of screen at +%d deg, %.2f%% at +%d deg. %s",
                    worst, worstYaw, best, bestYaw, dir));
            return;
        }
        wait = settle;
    }

    private static void record(int yawOffset, NativeImage dark) throws IOException {
        int w = Math.min(lit.getWidth(), dark.getWidth()), h = Math.min(lit.getHeight(), dark.getHeight());
        long water = 0, other = 0, black = 0;
        NativeImage overlay = new NativeImage(w, h, false);
        try {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int l = lit.getPixelRGBA(x, y);
                    int kind = LodWaterPixels.classify(l, dark.getPixelRGBA(x, y));
                    switch (kind) {
                        case LodWaterPixels.STARVED_WATER -> { water++; l = 0xFFFF00FF; }
                        case LodWaterPixels.STARVED_OTHER -> { other++; l = 0xFF00FFFF; }
                        case LodWaterPixels.BLACK_BOTH -> { black++; l = 0xFF00FF00; }
                        default -> { }
                    }
                    overlay.setPixelRGBA(x, y, l);
                }
            }
            String name = String.format("yaw%03d", yawOffset);
            lit.writeToFile(new File(dir, name + "_lit.png"));
            dark.writeToFile(new File(dir, name + "_unlit.png"));
            overlay.writeToFile(new File(dir, name + "_overlay.png"));
        } finally {
            overlay.close();
        }
        double total = (double) w * h / 100.0;
        double waterPct = water / total;
        csv.add(String.format("%d,%d,%.1f,%.3f,%.3f,%.3f", step, yawOffset, pitch, waterPct, other / total, black / total));
        if (waterPct > worst) {
            worst = waterPct;
            worstYaw = yawOffset;
        }
        if (waterPct < best) {
            best = waterPct;
            bestYaw = yawOffset;
        }
    }

    private static void finish(Minecraft mc, String message) {
        active = false;
        unlit = false;
        if (lit != null) {
            lit.close();
            lit = null;
        }
        mc.options.hideGui = savedHideGui;
        if (mc.player != null) {
            mc.player.setYRot(savedYaw);
            mc.player.yRotO = savedYaw;
            mc.player.setXRot(savedPitch);
            mc.player.xRotO = savedPitch;
            mc.player.displayClientMessage(Component.literal(message), false);
        }
    }
}
