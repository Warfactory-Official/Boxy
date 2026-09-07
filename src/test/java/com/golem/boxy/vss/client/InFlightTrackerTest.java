package com.golem.boxy.vss.client;

import com.golem.boxy.vss.common.PositionUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class InFlightTrackerTest {
    @Test
    void dimensionResetDoesNotReuseRequestIds() {
        var tracker = new InFlightTracker();
        long position = PositionUtil.packPosition(-3, 7);
        int old = tracker.send(position);
        tracker.markPending(position, System.nanoTime(), false);
        tracker.clear();
        int current = tracker.send(position);
        assertNotEquals(old, current);
        assertFalse(tracker.matches(old, position));
        assertTrue(tracker.matches(current, position));
        assertNull(tracker.removeByRequestId(old));
        assertTrue(tracker.matches(current, position));
    }

    @Test
    void timeoutReopensStationaryPlayersColumn() {
        var tracker = new InFlightTracker();
        long position = PositionUtil.packPosition(31, -9);
        int id = tracker.send(position);
        tracker.markPending(position, System.nanoTime() - 1000, true);
        var timedOut = new ArrayList<Long>();
        tracker.timeoutSweep(0, timedOut::add);
        assertEquals(java.util.List.of(position), timedOut);
        assertEquals(0, tracker.size());
        assertEquals(0, tracker.generationCount());
        assertFalse(tracker.matches(id, position));
    }

    @Test
    void payloadMustMatchRequestedPosition() {
        var tracker = new InFlightTracker();
        long position = PositionUtil.packPosition(1, 2);
        int id = tracker.send(position);
        assertFalse(tracker.matches(id, PositionUtil.packPosition(2, 1)));
        assertTrue(tracker.matches(id, position));
    }
}
