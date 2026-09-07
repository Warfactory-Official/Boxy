package com.golem.boxy.vss.common.processing;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DimensionIsolationTest {
    @Test
    void extremeCoordinatesCannotOverflowRangeValidation() {
        assertEquals(Integer.MAX_VALUE, com.golem.boxy.vss.common.PositionUtil.chebyshevDistance(Integer.MIN_VALUE, Integer.MIN_VALUE, 0, 0));
    }
    @Test
    void sameCoordinatesInDifferentDimensionsNeverShareReads() {
        var tracker = new DedupTracker();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        assertFalse(tracker.tryAttachOrCreate(42, "overworld", first, 1, 0));
        assertFalse(tracker.tryAttachOrCreate(42, "nether", second, 2, 1));
        assertTrue(tracker.removeGroup(42, "overworld", first, 1).attached().isEmpty());
        assertNotNull(tracker.removeGroup(42, "nether", second, 2));
    }

    @Test
    void lateCompletionCannotConsumeNewDedupGroup() {
        var tracker = new DedupTracker();
        var player = UUID.randomUUID();
        tracker.tryAttachOrCreate(42, "overworld", player, 1, 0);
        tracker.removePlayer(player);
        tracker.tryAttachOrCreate(42, "overworld", player, 2, 1);
        assertNull(tracker.removeGroup(42, "overworld", player, 1));
        assertNotNull(tracker.removeGroup(42, "overworld", player, 2));
    }

    @Test
    void dimensionResetReturnsAllPermits() {
        var state = new AbstractPlayerRequestState<Integer>(UUID.randomUUID(), 100, 1, 100, 1) {};
        assertTrue(state.getRateLimiters().syncOnLoad().tryAcquire());
        assertTrue(state.getRateLimiters().generation().tryAcquire());
        state.addPendingRequest(new PendingRequest(1, 0, 0, RequestType.SYNC));
        state.clearProcessingState();
        assertNull(state.removePendingByRequestId(1));
        assertTrue(state.getRateLimiters().syncOnLoad().tryAcquire());
        assertTrue(state.getRateLimiters().generation().tryAcquire());
    }
}
