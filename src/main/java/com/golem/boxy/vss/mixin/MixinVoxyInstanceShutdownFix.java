package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.common.VSSLogger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.StampedLock;

/**
 * Fixes a shutdown deadlock in Voxy 0.2.14's {@code VoxyInstance.shutdown} (backport of the
 * upstream 0.2.17-beta fix).
 *
 * <p><b>The bug.</b> The force-close path takes {@code activeWorldLock.writeLock()} and then, for
 * any world still marked used, busy-waits {@code while (world.isWorldUsed()) Thread.sleep(10)}
 * <i>while holding the write lock</i>. Releasing a world goes through that same StampedLock (e.g.
 * {@code getOrCreateWorld}/release paths take the read lock), so if any thread still holds a world
 * when the instance shuts down, the releaser blocks on the lock the shutdown thread holds and the
 * game hangs forever on quit / world switch. Upstream 0.2.17 fixes it by unlocking around the
 * busy-wait (and snapshotting the map to dodge the accompanying CME).
 *
 * <p><b>The fix here.</b> Equivalent liveness without restructuring Voxy's loop: inject just
 * <i>before</i> the {@code writeLock()} call (the only one in {@code shutdown}; the ingest/saving
 * services are already down at that point) and drain — poll the active worlds under short read
 * locks until none is still used. When Voxy's original loop then runs under the write lock, every
 * world is already unused, so its lock-holding busy-wait never triggers. A 60&nbsp;s cap means a
 * pathologically stuck world falls through to Voxy's original behaviour (hang) rather than us
 * masking a different bug, and an interrupt stops the drain immediately.
 *
 * <p>Targeted by string ({@code remap = false}, Voxy class; the {@code @At} target is a JDK
 * method); {@code require = 0} so it degrades to Voxy's original behaviour if the method shape
 * changes.
 */
@Mixin(targets = "me.cortex.voxy.commonImpl.VoxyInstance", remap = false)
public class MixinVoxyInstanceShutdownFix {

    @Shadow @Final private StampedLock activeWorldLock;
    @Shadow @Final private HashMap<WorldIdentifier, WorldEngine> activeWorlds;

    @Inject(method = "shutdown",
            at = @At(value = "INVOKE",
                     target = "Ljava/util/concurrent/locks/StampedLock;writeLock()J"),
            require = 0)
    private void boxy$drainUsedWorldsBeforeWriteLock(CallbackInfo ci) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        boolean logged = false;
        while (true) {
            int used = 0;
            long stamp = this.activeWorldLock.readLock();
            try {
                for (WorldEngine world : this.activeWorlds.values()) {
                    if (world.isWorldUsed()) {
                        used++;
                    }
                }
            } finally {
                this.activeWorldLock.unlockRead(stamp);
            }
            if (used == 0) {
                return;
            }
            if (!logged) {
                logged = true;
                VSSLogger.info("Boxy: waiting for " + used + " Voxy world(s) to be released before shutdown"
                        + " (avoids the 0.2.14 shutdown deadlock)");
            }
            if (System.nanoTime() - deadline > 0) {
                VSSLogger.error("Boxy: Voxy world(s) still in use after 60s; proceeding with Voxy's original shutdown path");
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
