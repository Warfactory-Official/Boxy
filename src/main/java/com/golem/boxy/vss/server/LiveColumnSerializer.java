package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.PositionUtil;
import com.golem.boxy.vss.common.VSSConstants;
import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.common.processing.OffThreadProcessor;
import com.golem.boxy.vss.common.voxel.SerializedColumnCache;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns {@link ColumnSnapshot}s into wire bytes off the server thread and publishes the results into the
 * players' disk-result queues, so they drain through the same permit/dedup/payload path as a disk read.
 *
 * <p>Deliberately its own pool rather than reusing either existing one. The single VSS processing thread is
 * the router for every player and must stay responsive — moving a large share of a tick's CPU onto it just
 * relocates the bottleneck. The disk pool is sized and prioritised for blocking region-file I/O
 * ({@code Thread.MIN_PRIORITY}), so a CPU-bound serialization burst there would starve real reads.
 */
public final class LiveColumnSerializer {
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
    private static final int QUEUE_CAPACITY_PER_THREAD = 64;

    private final ThreadPoolExecutor executor;
    private final ChunkDiskReader resultSink;
    private final SerializedColumnCache bytesCache;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    public LiveColumnSerializer(ChunkDiskReader resultSink, SerializedColumnCache bytesCache) {
        this.resultSink = resultSink;
        this.bytesCache = bytesCache;
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(threads * QUEUE_CAPACITY_PER_THREAD), r -> {
            Thread thread = new Thread(r, "VSS Column Serializer #" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            // Above the disk pool's MIN_PRIORITY: this path is latency-sensitive and CPU-bound, and a player
            // is actively waiting on the column.
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        });
    }

    /**
     * Hands the snapshot to a worker. Always produces exactly one result for {@code req} — including on
     * rejection or failure — because the caller already holds a syncOnLoad permit and a pending entry that
     * only a result releases.
     */
    public void submit(OffThreadProcessor.LiveSerializeRequest req, ColumnSnapshot snapshot) {
        var results = this.resultSink.getPlayerQueue(req.playerUuid());
        if (results == null) return;
        if (this.isShutdown.get()) {
            this.publishRetry(req, results);
            return;
        }

        try {
            this.executor.execute(() -> this.serializeAndPublish(req, snapshot, results));
        } catch (RejectedExecutionException rejected) {
            // Queue full. Mirrors ChunkDiskReader's saturation path: tell the client to back off rather than
            // claiming the column doesn't exist.
            if (VSSLogger.isDebugEnabled()) {
                VSSLogger.debug("Column serializer saturated, returning rate-limited for " + req.cx() + "," + req.cz());
            }
            results.add(ChunkDiskReader.saturatedResult(
                    req.playerUuid(), req.requestId(), req.cx(), req.cz(), req.submissionOrder()));
        }
    }

    private void serializeAndPublish(OffThreadProcessor.LiveSerializeRequest req, ColumnSnapshot snapshot,
            ConcurrentLinkedQueue<ChunkDiskReader.ReadResult> results) {
        try {
            byte[] sectionBytes = ColumnSnapshotter.serialize(snapshot);
            this.bytesCache.put(req.dimension(), PositionUtil.packPosition(req.cx(), req.cz()), sectionBytes);
            results.add(ChunkDiskReader.liveResult(
                    req.playerUuid(), req.requestId(), req.cx(), req.cz(), req.dimension(),
                    sectionBytes, VSSConstants.epochSeconds(), req.submissionOrder()));
        } catch (Throwable t) {
            VSSLogger.error("Failed to serialize snapshot of column [" + req.cx() + ", " + req.cz()
                    + "] in " + req.dimension(), t);
            this.publishRetry(req, results);
        }
    }

    private void publishRetry(OffThreadProcessor.LiveSerializeRequest req, ConcurrentLinkedQueue<ChunkDiskReader.ReadResult> results) {
        results.add(ChunkDiskReader.saturatedResult(
                req.playerUuid(), req.requestId(), req.cx(), req.cz(), req.submissionOrder()));
    }

    public void shutdown() {
        this.isShutdown.set(true);
        this.executor.shutdownNow();

        try {
            if (!this.executor.awaitTermination(5L, TimeUnit.SECONDS)) {
                VSSLogger.warn("Column serializer threads did not terminate within 5 seconds");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
