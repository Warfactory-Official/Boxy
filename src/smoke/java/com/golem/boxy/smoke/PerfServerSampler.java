package com.golem.boxy.smoke;

import com.golem.boxy.vss.server.RequestProcessingService;
import com.golem.boxy.vss.server.ServerNetworking;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Arrays;
import java.util.Locale;

/** Opt-in server work timing; the 20 TPS scheduler's idle wait is excluded. */
public final class PerfServerSampler {
    private static final long[] TICK_NANOS = new long[16_384];
    private static int sampleCount;
    private static int samplePosition;
    private static long tickStart;
    private static long tick;
    private static boolean finished;

    private PerfServerSampler() {}

    /** Call on the server thread to reset at the same explicit phase as the client. */
    public static void startMeasurement() {
        sampleCount = 0;
        samplePosition = 0;
        tickStart = 0L;
        tick = 0L;
        finished = false;
    }

    static void beforeTick(ServerTickEvent.Pre event) {
        if (!Boolean.getBoolean("boxy.perf") || finished) return;
        tickStart = System.nanoTime();
    }

    static void tick(ServerTickEvent.Post event) {
        if (!Boolean.getBoolean("boxy.perf") || finished) return;
        long start = tickStart;
        tickStart = 0L;
        if (start == 0L) return;
        TICK_NANOS[samplePosition] = Math.max(0L, System.nanoTime() - start);
        samplePosition = (samplePosition + 1) % TICK_NANOS.length;
        sampleCount = Math.min(TICK_NANOS.length, sampleCount + 1);
        tick++;
    }

    static void finish(MinecraftServer server) {
        if (!Boolean.getBoolean("boxy.perf") || finished) return;
        finished = true;
        tickStart = 0L;
        logWindow();
    }

    static void onServerStopping(ServerStoppingEvent event) {
        finish(event.getServer());
    }

    private static void logWindow() {
        long[] sorted = Arrays.copyOf(TICK_NANOS, sampleCount);
        Arrays.sort(sorted);
        double sum = 0;
        for (long value : sorted) sum += value;
        double averageMs = sampleCount == 0 ? 0.0 : sum / sampleCount / 1_000_000.0;
        double p95Ms = sampleCount == 0 ? 0.0 : sorted[Math.max(0, (int) Math.ceil(sampleCount * 0.95) - 1)] / 1_000_000.0;
        double p99Ms = sampleCount == 0 ? 0.0 : sorted[Math.max(0, (int) Math.ceil(sampleCount * 0.99) - 1)] / 1_000_000.0;
        double maxMs = sampleCount == 0 ? 0.0 : sorted[sampleCount - 1] / 1_000_000.0;
        RequestProcessingService service = ServerNetworking.getRequestService();
        String diagnostics = service == null ? "service=none" : String.format(Locale.ROOT,
                "diag=\"%s\" bw_window=%d", service.getTickDiagnostics(), service.getWindowBandwidthRate());
        long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024L * 1024L);
        LogUtils.getLogger().info("BOXY_PERF_SERVER_SUMMARY tick={} tick_samples={} avg_tick_ms={} p95_tick_ms={} p99_tick_ms={} max_tick_ms={} heap_used_mb={} {}",
                tick, sampleCount, format(averageMs), format(p95Ms), format(p99Ms), format(maxMs), usedMb, diagnostics);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
