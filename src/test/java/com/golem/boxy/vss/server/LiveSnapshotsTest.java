package com.golem.boxy.vss.server;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class LiveSnapshotsTest {
    private static final Object EMPTY = new Object();

    private static class Collect implements LiveSnapshots.Sink<Object> {
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final AtomicReference<Thread> thread = new AtomicReference<>();

        public void accept(Object s) {
            accepted.incrementAndGet();
            thread.set(Thread.currentThread());
        }

        public void fail(Throwable c) {
            failed.incrementAndGet();
        }

        public void deferredDone() {
            done.incrementAndGet();
        }
    }

    @Test
    void hostAbsentRunsInlineOnCaller() {
        var snaps = new LiveSnapshots(null);
        var sink = new Collect();
        assertFalse(snaps.deferred());
        assertNull(snaps.capture(0, null, 0, 0, () -> EMPTY, sink));
        assertEquals(1, sink.accepted.get());
        assertSame(Thread.currentThread(), sink.thread.get());
        assertEquals(0, sink.done.get());
        snaps.sweep(Integer.MAX_VALUE / 2);
    }

    @Test
    void inlineSnapshotFailureGoesToSink() {
        var snaps = new LiveSnapshots(null);
        var sink = new Collect();
        assertNull(snaps.capture(0, null, 0, 0, () -> {
            throw new IllegalStateException("boom");
        }, sink));
        assertEquals(0, sink.accepted.get());
        assertEquals(1, sink.failed.get());
    }

    /** Mutator and snapshots share one owner thread, as with a region worker; a torn read would show unequal cells. */
    @Test
    void snapshotsRunOnOwnerAndNeverTearAgainstMutator() throws Exception {
        ExecutorService owner = Executors.newSingleThreadExecutor(r -> new Thread(r, "owner"));
        try {
            int[] cells = new int[256];
            AtomicBoolean torn = new AtomicBoolean();
            AtomicBoolean offOwner = new AtomicBoolean();
            AtomicBoolean stop = new AtomicBoolean();
            var snaps = new LiveSnapshots((level, cx, cz, work) -> owner.execute(work));
            Supplier<Object> read = () -> {
                if (!Thread.currentThread().getName().equals("owner")) offOwner.set(true);
                int first = cells[0];
                for (int v : cells) if (v != first) torn.set(true);
                return EMPTY;
            };
            Thread mutator = new Thread(() -> {
                int n = 0;
                while (!stop.get()) {
                    int v = ++n;
                    owner.execute(() -> {
                        for (int i = 0; i < cells.length; i++) cells[i] = v;
                    });
                    if ((n & 1023) == 0) Thread.onSpinWait();
                }
            });
            mutator.start();
            int requests = 2000;
            CountDownLatch all = new CountDownLatch(requests);
            AtomicInteger total = new AtomicInteger();
            for (int i = 0; i < requests; i++) {
                assertTrue(snaps.deferred());
                snaps.capture(0, null, i, 0, read, new Collect() {
                    @Override
                    public void accept(Object s) {
                        total.incrementAndGet();
                    }

                    @Override
                    public void deferredDone() {
                        all.countDown();
                    }
                });
                if ((i & 63) == 0) Thread.yield();
            }
            assertTrue(all.await(30, TimeUnit.SECONDS));
            stop.set(true);
            mutator.join();
            assertEquals(requests, total.get());
            assertFalse(torn.get());
            assertFalse(offOwner.get());
        } finally {
            owner.shutdownNow();
        }
    }

    @Test
    void unrunCaptureTimesOutExactlyOnce() {
        var queued = new AtomicReference<Runnable>();
        var snaps = new LiveSnapshots((level, cx, cz, work) -> queued.set(work));
        var sink = new Collect();
        var handle = snaps.capture(10, null, 0, 0, () -> EMPTY, sink);
        assertNotNull(handle);
        snaps.sweep(10 + LiveSnapshots.TIMEOUT_TICKS - 1);
        assertEquals(0, sink.failed.get());
        snaps.sweep(10 + LiveSnapshots.TIMEOUT_TICKS);
        assertEquals(1, sink.failed.get());
        assertEquals(1, sink.done.get());
        queued.get().run();
        assertEquals(0, sink.accepted.get());
        snaps.sweep(10 + LiveSnapshots.TIMEOUT_TICKS + 1);
        assertEquals(1, sink.failed.get());
    }

    @Test
    void cancelSuppressesSinkOnlyBeforeRun() {
        var queued = new AtomicReference<Runnable>();
        var snaps = new LiveSnapshots((level, cx, cz, work) -> queued.set(work));
        var cancelled = new Collect();
        var handle = snaps.capture(0, null, 0, 0, () -> EMPTY, cancelled);
        assertTrue(handle.cancel());
        queued.get().run();
        assertEquals(0, cancelled.accepted.get() + cancelled.failed.get() + cancelled.done.get());

        var ran = new Collect();
        var second = snaps.capture(0, null, 0, 0, () -> EMPTY, ran);
        queued.get().run();
        assertEquals(1, ran.accepted.get());
        assertFalse(second.cancel());
        snaps.sweep(0);
    }

    @Test
    void bridgeAbsentWhenApiMissing() {
        assertNull(TessellateBridge.bind("org.texboobcat.tessellate.api.Missing", "x.Missing", getClass().getClassLoader()));
    }
}
