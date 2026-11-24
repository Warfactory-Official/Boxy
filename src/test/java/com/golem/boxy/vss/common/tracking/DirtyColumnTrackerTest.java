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

    @Test
    void negativeAndRegionBoundaryColumnsRoundTrip() {
        var tracker = new DirtyColumnTracker();
        int[][] cols = {{-1, -1}, {0, 0}, {31, 31}, {32, 32}, {-32, -33}, {-33, 31}, {Integer.MAX_VALUE >> 4, Integer.MIN_VALUE >> 4}};
        for (int[] c : cols) tracker.markDirty("d", c[0], c[1]);
        var expected = new java.util.HashSet<Long>();
        for (int[] c : cols) expected.add(com.golem.boxy.vss.common.PositionUtil.packPosition(c[0], c[1]));
        var actual = new java.util.HashSet<Long>();
        for (long p : tracker.drainDirty("d")) actual.add(p);
        assertEquals(expected, actual);
        assertNull(tracker.drainDirty("d"));
    }

    @Test
    void manyRegionsSurviveTableGrowth() {
        var tracker = new DirtyColumnTracker();
        for (int rx = -40; rx < 40; rx++) for (int rz = -40; rz < 40; rz++) tracker.markDirty("d", rx << 5, rz << 5);
        assertEquals(6400, tracker.drainDirty("d").length);
        for (int rx = -40; rx < 40; rx++) tracker.markDirty("d", rx << 5 | 7, 3);
        assertEquals(80, tracker.drainDirty("d").length);
    }

    @Test
    void concurrentMarksAndDrainsLoseNothingAndFireSinkOncePerWindow() throws Exception {
        var tracker = new DirtyColumnTracker();
        var sinkCalls = new AtomicInteger();
        tracker.setInvalidationSink((d, p) -> sinkCalls.incrementAndGet());
        int threads = 8, columns = 4096, rounds = 50;
        var drained = new java.util.concurrent.atomic.AtomicLong();
        var stop = new java.util.concurrent.atomic.AtomicBoolean();
        var drainer = new Thread(() -> {
            while (!stop.get()) {
                var d = tracker.drainDirty("d");
                if (d != null) drained.addAndGet(d.length);
            }
        });
        drainer.start();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                for (int r = 0; r < rounds; r++)
                    for (int i = 0; i < columns; i++) tracker.markDirty("d", i % 64 - 32, i / 64 - 32);
            }));
        }
        for (var f : futures) f.get();
        pool.shutdown();
        stop.set(true);
        drainer.join();
        var tail = tracker.drainDirty("d");
        if (tail != null) drained.addAndGet(tail.length);
        assertEquals(sinkCalls.get(), drained.get());
        assertTrue(drained.get() >= columns);
    }
}
