package com.golem.boxy.vss.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LodWaterPixelsTest {
    /** NativeImage 1.21.1 order: ABGR, red in the low byte. */
    private static int abgr(int r, int g, int b) {
        return 0xFF000000 | b << 16 | g << 8 | r;
    }

    @Test
    void unchangedPixelsAreNormal() {
        int sky = abgr(150, 190, 255);
        assertEquals(LodWaterPixels.NORMAL, LodWaterPixels.classify(sky, sky));
    }

    @Test
    void darkLitBlueUnlitIsStarvedWater() {
        assertEquals(LodWaterPixels.STARVED_WATER, LodWaterPixels.classify(abgr(32, 40, 47), abgr(106, 153, 179)));
    }

    @Test
    void darkLitGreenUnlitIsStarvedOther() {
        assertEquals(LodWaterPixels.STARVED_OTHER, LodWaterPixels.classify(abgr(17, 22, 21), abgr(32, 69, 25)));
    }

    // pixel pairs below are from a real sweep (lit frame, unlit frame)
    @Test
    void correctlyLitLodWaterIsNormal() {
        assertEquals(LodWaterPixels.NORMAL, LodWaterPixels.classify(abgr(69, 117, 149), abgr(80, 143, 154)));
        assertEquals(LodWaterPixels.NORMAL, LodWaterPixels.classify(abgr(125, 146, 178), abgr(143, 183, 217)));
    }

    @Test
    void deepWaterOverDimSeabedIsNormal() {
        assertEquals(LodWaterPixels.NORMAL, LodWaterPixels.classify(abgr(39, 90, 122), abgr(97, 144, 165)));
        assertEquals(LodWaterPixels.NORMAL, LodWaterPixels.classify(abgr(37, 84, 116), abgr(79, 125, 148)));
        assertEquals(LodWaterPixels.NORMAL, LodWaterPixels.classify(abgr(36, 68, 20), abgr(69, 110, 52)));
    }

    @Test
    void sky0WaterIsStarvedEvenInFog() {
        assertEquals(LodWaterPixels.STARVED_WATER, LodWaterPixels.classify(abgr(38, 46, 55), abgr(90, 136, 166)));
        assertEquals(LodWaterPixels.STARVED_WATER, LodWaterPixels.classify(abgr(55, 64, 76), abgr(102, 140, 163)));
        assertEquals(LodWaterPixels.STARVED_WATER, LodWaterPixels.classify(abgr(97, 112, 131), abgr(136, 175, 207)));
    }

    @Test
    void blackEvenWithFullLightIsBlackBoth() {
        assertEquals(LodWaterPixels.BLACK_BOTH, LodWaterPixels.classify(abgr(2, 2, 2), abgr(5, 5, 5)));
    }
}
