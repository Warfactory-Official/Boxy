package com.golem.boxy.vss.common.tracking;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DirtyColumnTrackerTest {
    @Test
    void interleavedMarksInvalidateOncePerColumnAndDrainWindow() {
        var tracker = new DirtyColumnTracker();
        var invalidations = new AtomicInteger();
        tracker.setInvalidationSink((dimension, pos) -> invalidations.incrementAndGet());
        tracker.markDirty("overworld", 1, 2);
        tracker.markDirty("overworld", 3, 4);
        tracker.markDirty("overworld", 1, 2);
        tracker.markDirty("overworld", 3, 4);
        assertEquals(2, invalidations.get());
        assertEquals(2, tracker.drainDirty("overworld").length);
        assertNull(tracker.drainDirty("overworld"));
        tracker.markDirty("overworld", 1, 2);
        assertEquals(3, invalidations.get());
        assertEquals(1, tracker.drainDirty("overworld").length);
    }

    @Test
    void dimensionDrainsAndInvalidationsAreIndependent() {
        var tracker = new DirtyColumnTracker();
        var invalidations = new AtomicInteger();
        tracker.setInvalidationSink((dimension, pos) -> invalidations.incrementAndGet());
        tracker.markDirty("overworld", -1, -2);
        tracker.markDirty("nether", -1, -2);
        assertEquals(2, invalidations.get());
        tracker.drainDirty("overworld");
        tracker.markDirty("nether", -1, -2);
        assertEquals(2, invalidations.get());
        assertEquals(1, tracker.drainDirty("nether").length);
    }
}
