package com.golem.boxy.vss.client;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * Client-side store of the last <b>server-authoritative</b> absolute position per entity id, captured from
 * the entity move/teleport packet handlers (see {@link com.golem.boxy.vss.mixin.MixinClientPacketListenerServerPos}).
 *
 * <p>{@link DistantEntityTicker} reads it to pin a distant non-living entity's Y to the server value.
 * Without that, a vehicle (e.g. the jet mod) that simulates gravity in its client tick would free-fall
 * through the un-loaded terrain out past view distance — the client only has Voxy LOD there, not real
 * collision blocks — then snap back on each (infrequent) tracking update, then fall again.
 *
 * <p>Single-threaded: both the packet handlers and the client tick run on the client main thread, so no
 * synchronization is needed. Keyed by entity id; cleared on logout.
 */
public final class DistantEntityServerPos {
    private static final Int2ObjectMap<double[]> POS = new Int2ObjectOpenHashMap<>();

    private DistantEntityServerPos() {}

    /** Record the server's absolute position + rotation for an entity (overwrites any previous value). */
    public static void put(int entityId, double x, double y, double z, float yRot, float xRot) {
        double[] p = POS.get(entityId);
        if (p == null) {
            POS.put(entityId, new double[]{x, y, z, yRot, xRot});
        } else {
            p[0] = x;
            p[1] = y;
            p[2] = z;
            p[3] = yRot;
            p[4] = xRot;
        }
    }

    /** Last server state {@code {x, y, z, yRot, xRot}} for this entity id, or {@code null} if none yet. */
    public static double[] get(int entityId) {
        return POS.get(entityId);
    }

    public static void clear() {
        POS.clear();
    }
}
