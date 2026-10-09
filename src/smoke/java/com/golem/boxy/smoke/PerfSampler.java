package com.golem.boxy.smoke;

import com.golem.boxy.vss.client.ClientNetworking;
import com.golem.boxy.vss.client.LodRequestManager;
import com.mojang.logging.LogUtils;
import me.cortex.voxy.client.RenderStatistics;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Arrays;
import java.util.Locale;

/** Opt-in, smoke-only observations of actual tick work and displayed frame cadence. */
public final class PerfSampler {
    private static final Samples TICKS = new Samples(16_384);
    private static final Samples FRAMES = new Samples(131_072);
    private static boolean installed;
    private static boolean measuring;
    private static long measurementStart;
    private static long tickStart;
    private static long previousFrame;
    private static long tick;
    private static boolean finished;

    private PerfSampler() {}

    static boolean enabled() {
        return Boolean.getBoolean("boxy.perf");
    }

    /** Called by the smoke driver; registration is inert until the measurement phase begins. */
    static void tick(Minecraft minecraft) {
        install();
    }

    private static void install() {
        if (!enabled() || installed) return;
        installed = true;
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, PerfSampler::beforeTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, PerfSampler::afterTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, PerfSampler::afterFrame);
    }

    /** Discards startup/loading samples and starts an explicit, repeatable measurement phase. */
    public static void startMeasurement() {
        if (!enabled()) return;
        install();
        TICKS.clear();
        FRAMES.clear();
        tick = 0L;
        tickStart = 0L;
        previousFrame = 0L;
        measurementStart = System.nanoTime();
        measuring = true;
        finished = false;
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) server.execute(PerfServerSampler::startMeasurement);
        LogUtils.getLogger().info("BOXY_PERF_MEASUREMENT_START: client tick work and frame intervals");
    }

    private static boolean inWorld(Minecraft minecraft) {
        return minecraft.level != null && minecraft.player != null && minecraft.screen == null
                && minecraft.getOverlay() == null && !minecraft.isPaused();
    }

    private static void beforeTick(ClientTickEvent.Pre event) {
        tickStart = measuring && inWorld(Minecraft.getInstance()) ? System.nanoTime() : 0L;
    }

    private static void afterTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        long start = tickStart;
        tickStart = 0L;
        if (start == 0L || !measuring || !inWorld(minecraft)) return;
        TICKS.add(System.nanoTime() - start);
        tick++;
    }

    private static void afterFrame(RenderFrameEvent.Post event) {
        if (!measuring || !inWorld(Minecraft.getInstance())) {
            previousFrame = 0L; // Do not bridge menu/loading pauses into the next frame interval.
            return;
        }
        long now = System.nanoTime();
        if (previousFrame != 0L) FRAMES.add(now - previousFrame);
        previousFrame = now;
    }

    static void finish(Minecraft minecraft) {
        if (!enabled() || finished || !measuring) return;
        measuring = false;
        finished = true;
        tickStart = 0L;
        previousFrame = 0L;
        long elapsedMs = measurementStart == 0L ? 0L : (System.nanoTime() - measurementStart) / 1_000_000L;
        var server = minecraft.getSingleplayerServer();
        if (server != null) server.execute(() -> PerfServerSampler.finish(server));
        // Sorting, formatting and logging happen only after measurement has stopped. The integrated
        // server finishes on its own thread; ServerStoppingEvent is an idempotent fallback.
        logWindow(minecraft, elapsedMs);
    }

    static long measuredTicks() {
        return tick;
    }

    private static void logWindow(Minecraft minecraft, long elapsedMs) {
        Stats ticks = TICKS.stats();
        Stats frames = FRAMES.stats();
        long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024L * 1024L);
        LodRequestManager manager = ClientNetworking.getRequestManager();
        String planner = manager == null ? "none" : String.format(Locale.ROOT,
                "pending=%d queue=%d scan_ring=%d confirmed_ring=%d budget=%d missing_vanilla=%d",
                manager.getPendingCount(), manager.getQueueRemaining(), manager.getScanRing(),
                manager.getConfirmedRing(), manager.getLastBudget(), manager.getMissingVanillaChunks());
        String render = RenderStatistics.enabled ? String.format(Locale.ROOT, "quads=%s visible=%s",
                Arrays.toString(RenderStatistics.quadCount), Arrays.toString(RenderStatistics.visibleSections)) : "disabled";
        LogUtils.getLogger().info("BOXY_PERF_CLIENT_SUMMARY tick={} tick_samples={} avg_tick_ms={} p95_tick_ms={} p99_tick_ms={} frame_samples={} avg_frame_interval_ms={} p95_frame_interval_ms={} p99_frame_interval_ms={} measurement_ms={} fps={} heap_used_mb={} columns={} bytes={} queued_columns={} {} {}",
                tick, ticks.count(), format(ticks.averageMs()), format(ticks.p95Ms()), format(ticks.p99Ms()),
                frames.count(), format(frames.averageMs()), format(frames.p95Ms()), format(frames.p99Ms()),
                elapsedMs,
                minecraft.getFps(), usedMb, ClientNetworking.getColumnsReceived(), ClientNetworking.getBytesReceived(),
                ClientNetworking.getQueuedColumnCount(), planner, render);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private record Stats(int count, double averageMs, double p95Ms, double p99Ms) {}

    /** Fixed-capacity storage avoids per-frame allocations; normal smoke phases fit without wrapping. */
    private static final class Samples {
        private final long[] values;
        private int count;
        private int next;

        Samples(int capacity) { values = new long[capacity]; }
        void clear() { count = 0; next = 0; }
        void add(long nanos) {
            values[next] = Math.max(0L, nanos);
            next = (next + 1) % values.length;
            count = Math.min(values.length, count + 1);
        }
        Stats stats() {
            if (count == 0) return new Stats(0, 0, 0, 0);
            long[] sorted = Arrays.copyOf(values, count);
            Arrays.sort(sorted);
            double sum = 0;
            for (long value : sorted) sum += value;
            return new Stats(count, sum / count / 1_000_000.0,
                    sorted[Math.max(0, (int) Math.ceil(count * 0.95) - 1)] / 1_000_000.0,
                    sorted[Math.max(0, (int) Math.ceil(count * 0.99) - 1)] / 1_000_000.0);
        }
    }
}
