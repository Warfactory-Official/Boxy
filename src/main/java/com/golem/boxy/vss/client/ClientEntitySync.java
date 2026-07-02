package com.golem.boxy.vss.client;

import com.golem.boxy.vss.common.TrackedEntityTypes;
import com.golem.boxy.vss.config.VSSClientConfig;

import java.util.List;

/**
 * The client's <b>effective</b> distant-entity render settings while connected to a Boxy server.
 *
 * <p>On the handshake reply ({@code SessionConfigS2CPayload}) the server sends its entity list, render
 * distance, and enable flag; we apply them here <b>in memory only</b> — the client's {@code vss-client-config.json}
 * is never touched. While a sync is active the server's values win (so a player automatically matches the
 * server's whitelist/distance without editing anything); when no sync is active (not a Boxy server, or after
 * logout) we fall back to the local config. The local {@code extendEntityRenderDistance} always acts as a
 * client-side opt-out. The synced type set itself lives in {@link TrackedEntityTypes}.
 */
public final class ClientEntitySync {
    private static volatile boolean active = false;
    private static volatile boolean serverEnabled = false;
    private static volatile int serverDistanceChunks = 0;

    private ClientEntitySync() {}

    public static void applyFromServer(boolean enabled, int distanceChunks, List<String> types) {
        serverEnabled = enabled;
        serverDistanceChunks = distanceChunks;
        TrackedEntityTypes.setSyncedTypes(types);
        active = true;
    }

    /** Revert to the local client config (called on logout). */
    public static void clear() {
        active = false;
        TrackedEntityTypes.clearSynced();
    }

    /** Feature on? A client may always opt out locally; otherwise the server decides while synced. */
    public static boolean enabled() {
        if (!VSSClientConfig.CONFIG.extendEntityRenderDistance) {
            return false;
        }
        return !active || serverEnabled;
    }

    /** Render distance in chunks: the server's value while synced, else the local config. */
    public static int distanceChunks() {
        return active ? serverDistanceChunks : VSSClientConfig.CONFIG.entityRenderDistanceChunks;
    }
}
