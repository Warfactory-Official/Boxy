package com.golem.boxy.vss.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityLightHistoryTest {
    private static final int SKY = 15 << 20;

    @Test
    void changedChunkOrLightPacketRearmsWithoutAnUnloadedFrame() {
        var history = new EntityLightHistory();
        Object dimension = new Object();
        Object source = new Object();
        history.lightSource(source);
        history.sample(dimension, 0, SKY, true, true);
        history.lightSource(new Object());
        assertEquals(SKY, history.sample(dimension, 100, 0, true, false));
        assertEquals(SKY, history.sample(dimension, 120, 0, true, false));
        assertEquals(0, history.sample(dimension, 121, 0, true, true));
    }

    @Test
    void holdsMissingLightWithoutExtendingTheDeadline() {
        var history = new EntityLightHistory();
        Object dimension = new Object();
        assertEquals(SKY, history.sample(dimension, 100, SKY, true, true));
        assertEquals(SKY, history.sample(dimension, 101, 0, false, false));
        assertEquals(SKY, history.sample(dimension, 140, 0, false, false));
        assertEquals(0, history.sample(dimension, 141, 0, false, false));
    }

    @Test
    void acceptsRealDarknessImmediatelyAndDoesNotInventInitialLight() {
        var history = new EntityLightHistory();
        Object dimension = new Object();
        assertEquals(0, history.sample(dimension, 0, 0, false, false));
        history.sample(dimension, 1, SKY, true, true);
        assertEquals(0, history.sample(dimension, 2, 0, true, true));
        assertEquals(0, history.sample(dimension, 3, 0, false, false));
    }

    @Test
    void doesNotCrossDimensionsOrClockResets() {
        var history = new EntityLightHistory();
        Object dimension = new Object();
        history.sample(dimension, 100, SKY, true, true);
        assertEquals(0, history.sample(dimension, 99, 0, false, false));
        history.sample(dimension, 100, SKY, true, true);
        assertEquals(0, history.sample(new Object(), 101, 0, false, false));
    }

    @Test
    void ignoresUnavailableSamplesAndResumesSamplingWhenReady() {
        var history = new EntityLightHistory();
        Object dimension = new Object();
        history.sample(dimension, 0, SKY, true, true);
        assertEquals(SKY, history.sample(dimension, 1, 240, false, false));
        assertEquals(64, history.sample(dimension, 2, 64, true, true));
        assertEquals(64, history.sample(dimension, 3, 0, false, false));
    }

    @Test
    void returningChunkWaitsForLightAfterLongDistantStay() {
        var history = new EntityLightHistory();
        Object dimension = new Object();
        history.sample(dimension, 0, 64, true, true);
        assertEquals(SKY, history.sample(dimension, 100, SKY, false, false));
        assertEquals(SKY, history.sample(dimension, 101, 0, true, false));
        assertEquals(SKY, history.sample(dimension, 120, 0, true, false));
        assertEquals(32, history.sample(dimension, 121, 32, true, true));
        // Unrelated queued work must not delay real lighting changes once the handoff is complete.
        assertEquals(0, history.sample(dimension, 122, 0, true, false));
    }

    @Test
    void incomingHoldExpiresWithoutRefreshingItself() {
        var history = new EntityLightHistory();
        Object dimension = new Object();
        history.sample(dimension, 0, SKY, false, false);
        assertEquals(SKY, history.sample(dimension, 100, 0, true, false));
        assertEquals(SKY, history.sample(dimension, 140, 0, true, false));
        assertEquals(0, history.sample(dimension, 141, 0, true, false));
    }
}
