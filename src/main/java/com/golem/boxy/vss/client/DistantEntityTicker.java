package com.golem.boxy.vss.client;

import com.golem.boxy.vss.common.TrackedEntityTypes;
import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.mixin.AccessorClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the client-side tick of distant tracked entities so they actually <b>move</b>.
 *
 * <p>Minecraft only ticks entities in {@code ClientLevel.tickingEntities} (entities whose chunk is loaded
 * and ticking). An entity beyond the client's loaded chunks is rendered (it's in
 * {@code entitiesForRendering()}, used by the render mixins) but never ticked, so its position
 * interpolation never runs and it freezes at the loaded-chunk border — fine while stationary, stuck while
 * moving. We tick the configured types that the normal loop is <b>not</b> ticking, exactly as
 * {@code ClientLevel.tickEntities} would, so they interpolate to the positions the server keeps sending.
 *
 * <p>The "is it already ticked?" test is membership in {@code tickingEntities} (via {@link AccessorClientLevel})
 * — <b>not</b> {@code hasChunkAt}, which Voxy makes unreliable by populating the client chunk cache in the
 * LOD region. Runs on {@code ClientTickEvent} END, client-only; skips the local player and passengers (their
 * vehicle ticks them).
 */
public final class DistantEntityTicker {
    private DistantEntityTicker() {}

    /**
     * Set only while this ticker runs its own managed tick of a distant entity, so
     * {@link com.golem.boxy.vss.mixin.MixinEntityForeignTickGuard} can tell Boxy's own tick apart from a
     * foreign re-tick (e.g. ReplayMod's {@code Mixin_FixEntityNotTracking}, which re-ticks the same
     * not-in-{@code tickingEntities} set and would fling a gravity-affected jet). Client main thread only.
     */
    private static volatile boolean boxyTicking;

    /** True while Boxy's distant-entity ticker is mid-tick of an entity (see {@link #boxyTicking}). */
    public static boolean isBoxyTicking() {
        return boxyTicking;
    }

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ClientEntitySync.enabled()) {
            return;
        }
        // Between-frames framebuffer prep for the PRECISE depth fix (recreating it mid-frame is unsafe).
        DistantEntityDepthFix.ensureStencil();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) {
            return;
        }
        EntityTickList ticking = ((AccessorClientLevel) level).boxy$getTickingEntities();

        // Collect first, then tick — ticking can move an entity to another section and mutate the store.
        List<Entity> toTick = null;
        for (Entity e : level.entitiesForRendering()) {
            if (e == mc.player || e.isRemoved() || e.isPassenger()
                    || !TrackedEntityTypes.clientContains(e.getType())
                    || ticking.contains(e)) { // already ticked by the normal loop
                continue;
            }
            if (toTick == null) {
                toTick = new ArrayList<>();
            }
            toTick.add(e);
        }
        if (toTick == null) {
            return;
        }
        for (Entity e : toTick) {
            try {
                // Non-living entities (vehicles like the jet mod) simulate gravity in their client tick, but
                // out here the collision terrain isn't loaded (only Voxy LOD) — so they'd free-fall and snap
                // back on each server update. Worse, their attitude (pitch) is derived from that bogus
                // vertical motion, so they nose-dive then snap level. We still tick them (animations,
                // passengers, interpolated X/Z), then pin Y *and* rotation to the server-authoritative values.
                // Living entities are lerp-only client-side (no gravity), so they're left untouched.
                boolean pinY = !(e instanceof LivingEntity);
                double[] sp = pinY ? DistantEntityServerPos.get(e.getId()) : null;
                double holdY = pinY ? e.getY() : 0.0;       // pre-tick fallbacks, used until the first
                float holdYRot = pinY ? e.getYRot() : 0.0f; // server update for this entity arrives
                float holdXRot = pinY ? e.getXRot() : 0.0f;
                boxyTicking = true; // mark our own tick so MixinEntityForeignTickGuard allows it (and only it)
                try {
                    level.tickNonPassenger(e); // setOldPosAndRot + tick + position passengers (e.g. a pilot)
                } finally {
                    boxyTicking = false;
                }
                if (pinY) {
                    e.setPos(e.getX(), sp != null ? sp[1] : holdY, e.getZ());
                    Vec3 dm = e.getDeltaMovement();
                    e.setDeltaMovement(dm.x, 0.0, dm.z); // cancel accumulated downward velocity
                    e.setYRot(sp != null ? (float) sp[3] : holdYRot);
                    e.setXRot(sp != null ? (float) sp[4] : holdXRot);
                    // The vehicle's tick also corrupts the *old* rotation that the renderer interpolates from,
                    // so the model swings between that bogus value and our pinned one every frame. Reset both
                    // old fields to the previous pinned values so rotation interpolates cleanly (prev -> new).
                    e.yRotO = holdYRot;
                    e.xRotO = holdXRot;
                }
                TrackedEntityTypes.diagDistantTick(e.getType());
            } catch (Throwable t) {
                VSSLogger.debug("Boxy: distant entity tick failed for " + e.getType() + ": " + t);
            }
        }
    }
}
