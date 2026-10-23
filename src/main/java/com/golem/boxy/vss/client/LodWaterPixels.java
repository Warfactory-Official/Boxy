package com.golem.boxy.vss.client;

/** Pixel classification for {@link LodWaterSweep}, kept free of Minecraft classes so it unit-tests. */
final class LodWaterPixels {
    private LodWaterPixels() {
    }

    static final int NORMAL = 0, STARVED_WATER = 1, STARVED_OTHER = 2, BLACK_BOTH = 3;
    private static final double STARVED_RATIO = 0.72;
    private static final double GREYED = 0.45;

    /**
     * One pixel of the lit frame against the same pixel with Voxy's lightmap forced white. Pixels as NativeImage
     * hands them out in 1.21.1: ABGR, red in the low byte. Calibrated on a real sweep: correctly lit LOD water keeps
     * ~0.83 of its unlit luma (the daytime lightmap is not pure white), sky-0 water 0.36-0.66 (fog pulls it up with
     * distance), so starved means below 0.72. That ratio alone also flags healthy deep water: it is translucent, its
     * dim seabed brightens under the white lightmap too, but the lit pixel stays saturated blue (0.63-0.70), while a
     * surface drawn at light 0 goes grey (saturation 0.18-0.31). So starved also needs a greyed lit pixel. Water: blue clearly dominant unlit (biome water tints; swamp's green
     * water is not caught). Pixels Voxy does not draw (sky, near terrain, HUD) are identical in both frames.
     */
    static int classify(int litPixel, int unlitPixel) {
        double lumLit = luma(litPixel), lumUnlit = luma(unlitPixel);
        if (lumUnlit < 0.06) {
            return lumLit < 0.06 ? BLACK_BOTH : NORMAL;
        }
        if (lumLit >= STARVED_RATIO * lumUnlit || saturation(litPixel) >= GREYED) {
            return NORMAL;
        }
        int r = unlitPixel & 0xFF, g = unlitPixel >> 8 & 0xFF, b = unlitPixel >> 16 & 0xFF;
        return b > r * 1.25 && b > g ? STARVED_WATER : STARVED_OTHER;
    }

    private static double luma(int abgr) {
        return (0.2126 * (abgr & 0xFF) + 0.7152 * (abgr >> 8 & 0xFF) + 0.0722 * (abgr >> 16 & 0xFF)) / 255.0;
    }

    private static double saturation(int abgr) {
        int r = abgr & 0xFF, g = abgr >> 8 & 0xFF, b = abgr >> 16 & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        return max == 0 ? 0 : (max - Math.min(r, Math.min(g, b))) / (double) max;
    }
}
