package com.golem.boxy.vss.server;

import net.minecraft.server.MinecraftServer;

/**
 * Thread-affinity checks for the server-side VSS code.
 *
 * <p>Most of this package splits state between the server thread and the VSS processing thread purely by
 * convention, documented in comments. Several paths (live chunk reads, PalettedContainer copies, light
 * DataLayer access) are only safe on the server thread and fail in ways that are hard to attribute — a
 * silently torn snapshot, or a ThreadingDetector ReportedException surfacing on whichever thread lost the
 * race. These helpers make the assumption checkable.
 *
 * <p>The assertion is debug-gated: it costs a reference compare, but the whole point is that it fires during
 * development rather than shipping a check into a per-column hot path.
 */
public final class BoxyThreads {
    private BoxyThreads() {}

    public static boolean isServerThread(MinecraftServer server) {
        return server != null && Thread.currentThread() == server.getRunningThread();
    }

    /**
     * Server thread: inline. Otherwise queued FIFO on the server executor. Chunk tickets are server-thread only
     * (DistanceManager); region workers must route through this.
     */
    public static void runOnServer(MinecraftServer server, Runnable op) {
        if (server.isSameThread()) {
            op.run();
        } else {
            server.execute(op);
        }
    }
}
