package com.golem.boxy.vss.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.locks.LockSupport;

/**
 * Cuts the latency of Voxy's block-model baking by replacing the bake thread's fixed-interval poll
 * with a wakeable park.
 *
 * <p><b>The inefficiency.</b> {@code ModelBakerySubsystem} runs a dedicated "Model factory processor"
 * thread that loops {@code processAllThings(); Thread.sleep(10);} — Voxy's own comment on the sleep
 * reads {@code //TODO: replace with LockSupport.park();}. Every model bake request (posted by the
 * section meshers via {@code requestBlockBake} whenever they hit a block id with no model yet) sits in
 * the queue for up to 10&nbsp;ms before the bake even starts. Meshing tasks that threw
 * {@code IdNotYetComputedException} requeue and wait on exactly those models, so during world load /
 * fast travel / VSS streaming this poll interval directly delays LOD fill-in.
 *
 * <p><b>The fix.</b> Redirect the {@code Thread.sleep(10)} to {@code LockSupport.parkNanos(10ms)} —
 * identical worst-case timing, so shutdown and any unhooked work paths behave exactly as before — and
 * {@code unpark} the thread the moment work arrives ({@code requestBlockBake} / {@code addBiome}) so a
 * fresh request is processed immediately instead of on the next poll tick. {@code shutdown()} also
 * gets an unpark right before {@code join()} (after {@code isRunning} is already false) so world close
 * doesn't eat the residual park.
 *
 * <p>The run loop compiles to the synthetic method {@code lambda$new$0()V} (verified against the Voxy
 * dev jar with javap; {@code lambda$new$1} is the uncaught-exception handler). Targeted by string so no
 * Voxy compile dependency is needed; {@code remap = false} (Voxy class, JDK targets); {@code require = 0}
 * on every injector so each degrades to a no-op if Voxy's internals change — the redirect and the
 * unparks are independently safe (park without unpark keeps the 10&nbsp;ms cap; unpark without park is
 * a cheap no-op).
 */
@Mixin(targets = "me.cortex.voxy.client.core.model.ModelBakerySubsystem", remap = false)
public class MixinVoxyModelBakeryWake {

    @Shadow @Final private Thread processingThread;

    @Redirect(method = "lambda$new$0()V",
              at = @At(value = "INVOKE", target = "Ljava/lang/Thread;sleep(J)V"),
              require = 0)
    private void boxy$parkInsteadOfSleep(long millis) {
        // Same 10ms upper bound as the sleep it replaces, but wakeable via unpark below. A banked
        // permit (unpark while the thread is mid-processAllThings) just makes the next park return
        // instantly, which re-runs the loop — exactly what we want.
        LockSupport.parkNanos(millis * 1_000_000L);
    }

    @Inject(method = "requestBlockBake", at = @At("TAIL"), require = 0)
    private void boxy$wakeOnBlockBakeRequest(int blockId, CallbackInfo ci) {
        // TAIL = the final return, reached only on the enqueue path (the duplicate/out-of-range
        // early returns skip it), so we wake the bake thread exactly when new work exists.
        LockSupport.unpark(this.processingThread);
    }

    @Inject(method = "addBiome", at = @At("TAIL"), require = 0)
    private void boxy$wakeOnBiomeAdd(@Coerce Object biomeEntry, CallbackInfo ci) {
        LockSupport.unpark(this.processingThread);
    }

    @Inject(method = "shutdown",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;join()V"),
            require = 0)
    private void boxy$wakeForShutdown(CallbackInfo ci) {
        // isRunning is already false here, so the woken thread exits its loop and join() returns
        // immediately instead of after the residual park interval.
        LockSupport.unpark(this.processingThread);
    }
}
