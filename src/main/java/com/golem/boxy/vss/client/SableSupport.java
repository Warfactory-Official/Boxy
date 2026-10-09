package com.golem.boxy.vss.client;

import com.golem.boxy.vss.common.VSSLogger;

/**
 * Optional Sable interoperability for Voxy's LOD pipeline.
 *
 * <p>Voxy renders exclusively with DSA texture binds ({@code glBindTextureUnit}), which never touch
 * the active texture unit. Sodium/Iris typically leave the active unit on the lightmap unit, and
 * Sable's sub-level renderer binds through {@code GlStateManager._bindTexture()} (current unit),
 * so the block atlas can land on the wrong unit and contraptions render black. Resetting the active
 * unit to {@code TEXTURE0} after Voxy's producer pass restores the state Sable expects.</p>
 *
 * <p>All detection is reflection-free at compile time: Sable is an optional runtime mod, so presence
 * is probed through {@code ModList} and cached. Every method is a no-op when Sable is absent or when
 * GL is unavailable (unit tests, dedicated servers).</p>
 */
public final class SableSupport {
    private static volatile Boolean cachedPresent;
    private static boolean logged;

    private SableSupport() {
    }

    /** True when the {@code sable} mod is loaded in this instance. */
    public static boolean isSableLoaded() {
        Boolean cached = cachedPresent;
        if (cached != null) {
            return cached;
        }
        boolean present = false;
        try {
            present = net.neoforged.fml.ModList.get().isLoaded("sable");
        } catch (Throwable ignored) {
            present = false;
        }
        cachedPresent = present;
        return present;
    }

    /**
     * Restores {@code GL_TEXTURE0} as the active unit after Voxy's producer pass when Sable is
     * present. Call on the render thread with a current GL context; otherwise a no-op.
     */
    public static void resetActiveTextureUnitAfterVoxy() {
        if (!isSableLoaded()) {
            return;
        }
        try {
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            if (!logged) {
                logged = true;
                VSSLogger.info("Boxy: Sable detected, resetting active texture unit after Voxy LOD pass");
            }
        } catch (Throwable ignored) {
            // Headless/smoke contexts without GL; leave state untouched.
        }
    }

    /** Test-only reset for the cached presence probe. */
    static void clearCacheForTests() {
        cachedPresent = null;
        logged = false;
    }
}
