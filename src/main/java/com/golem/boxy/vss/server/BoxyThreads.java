package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.VSSLogger;
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
     * Logs (does not throw) when {@code what} runs off the server thread. Deliberately not fatal: a false
     * positive during shutdown or from a mod-supplied executor should not take down a world, and the log line
     * is enough to find the caller.
     */
    public static void assertServerThread(MinecraftServer server, String what) {
        if (VSSLogger.isDebugEnabled() && !isServerThread(server)) {
            VSSLogger.error(what + " ran on " + Thread.currentThread().getName()
                    + " but must run on the server thread", new IllegalStateException("wrong thread"));
        }
    }
}
