package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.VSSLogger;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Live-chunk snapshot delivered to the region owning the chunk; main never blocks.
 * <ul>
 * <li>{@code host == null} (no Tessellate / not async): inline on caller, {@code sink} invoked before return.
 * <li>else: queued via host; {@code sink} runs on the owning region worker (must be thread-safe), exactly once;
 *     no run within {@link #TIMEOUT_TICKS} => {@link #sweep} fails it on main.
 * </ul>
 */
public final class LiveSnapshots {
    static final int TIMEOUT_TICKS = 200;
    private static final int PENDING = 0, CLAIMED = 1, CANCELLED = 2;

    @FunctionalInterface
    interface Host {
        void execute(Object level, int cx, int cz, Runnable work);
    }

    public interface Sink<S> {
        void accept(S snapshot);

        void fail(Throwable cause);

        /** Deferred path only, after accept/fail, on the thread that ran it. */
        default void deferredDone() {}
    }

    /** Cancel = main abandons a still-queued capture. */
    public static final class Handle {
        private final AtomicInteger state = new AtomicInteger(PENDING);
        private volatile boolean finished;
        private final long deadlineTick;
        private final Sink<?> sink;

        private Handle(int nowTick, Sink<?> sink) {
            this.sink = sink;
            this.deadlineTick = nowTick + TIMEOUT_TICKS;
        }

        /** true => sink will never run. */
        public boolean cancel() {
            return state.compareAndSet(PENDING, CANCELLED);
        }
    }

    @Nullable
    private final Host host;
    private final ConcurrentLinkedQueue<Handle> inFlight = new ConcurrentLinkedQueue<>();

    LiveSnapshots(@Nullable Host host) {
        this.host = host;
    }

    public static LiveSnapshots create() {
        return new LiveSnapshots(TessellateBridge.bind());
    }

    public boolean deferred() {
        return host != null;
    }

    /** null => inline (sink already ran) or already finished; else handle of the queued capture. */
    @Nullable
    public <S> Handle capture(int nowTick, Object level, int cx, int cz, Supplier<S> snapshot, Sink<S> sink) {
        if (host == null) {
            run(snapshot, sink);
            return null;
        }
        Handle handle = new Handle(nowTick, sink);
        inFlight.add(handle);
        host.execute(level, cx, cz, () -> {
            if (!handle.state.compareAndSet(PENDING, CLAIMED)) {
                return;
            }
            try {
                run(snapshot, sink);
                sink.deferredDone();
            } finally {
                handle.finished = true;
            }
        });
        return handle.finished ? null : handle;
    }

    /** Main, once per tick. */
    void sweep(int now) {
        if (inFlight.isEmpty()) {
            return;
        }
        for (Iterator<Handle> it = inFlight.iterator(); it.hasNext(); ) {
            Handle h = it.next();
            if (h.finished || h.state.get() == CANCELLED) {
                it.remove();
            } else if (now >= h.deadlineTick && h.state.compareAndSet(PENDING, CANCELLED)) {
                it.remove();
                try {
                    h.sink.fail(new IllegalStateException("live snapshot not run by owning region in " + TIMEOUT_TICKS + " ticks"));
                } finally {
                    h.sink.deferredDone();
                }
            }
        }
    }

    private static <S> void run(Supplier<S> snapshot, Sink<S> sink) {
        try {
            sink.accept(snapshot.get());
        } catch (Throwable t) {
            try {
                sink.fail(t);
            } catch (Throwable inner) {
                VSSLogger.error("Live snapshot sink failed", inner);
            }
        }
    }
}
