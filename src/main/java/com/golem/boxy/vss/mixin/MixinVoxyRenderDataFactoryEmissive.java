package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.VoxyBackports;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.util.ScanMesher2D;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mesh-side half of the emissive-LOD backport (upstream 0.2.17-beta's {@code applyQuadLight}):
 * every quad {@code RenderDataFactory} emits gets its block-light nibble raised to at least the
 * emitting block's own light emission, so glowstone/lava/sea-lantern/torch LODs glow. The
 * metadata side (writing emission into model metadata bits 55-58) is
 * {@link MixinVoxyModelFactoryFixes}; the shared patch logic is
 * {@link VoxyBackports#applyEmissiveLight}.
 *
 * <p>Upstream wraps all 15 {@code Mesher.putNext} emission sites — no more, no less — so this
 * mixin redirects exactly the same set: the nine instance {@code generate*Geometry} methods plus
 * the two static helpers ({@code meshNonOpaqueFace}, {@code dualMeshNonOpaqueOuterX}). Two
 * handlers because injector staticness must match the enclosing method. Rather than threading
 * upstream's {@code selfMeta} local through (unreachable from a redirect), the patch re-derives it
 * from the quad's own client-model-id bits (26-41, {@code packPartialQuadData}) — the identical
 * metadata long, including after the fluid-id remap sites, since those rewrite the quad's id and
 * the meta variable together. This runs per emitted quad on the mesh workers (shared with
 * Embeddium's chunk builders), so the instance-method sites resolve the {@code ModelFactory} from
 * the shadowed field; only the two static helpers (no {@code this}) pay a ThreadLocal lookup,
 * parked by the {@code generateMesh} HEAD hook. If that hook ever fails to apply, the ThreadLocal
 * reads null and the static sites pass quads through unchanged.
 *
 * <p>The receiver is coerced to the public {@code ScanMesher2D} supertype (the compiled call-site
 * owner is the private inner {@code RenderDataFactory$Mesher}; {@code putNext} is declared public
 * final on the supertype, and calling it from the handler is a real call, not a re-redirect).
 *
 * <p>Targeted by string ({@code remap = false}, Voxy classes only); {@code require = 0} on every
 * injector so partial application degrades toward 0.2.14's original (unlit-emissive) meshing
 * instead of failing the class transform.
 */
@Mixin(targets = "me.cortex.voxy.client.core.rendering.building.RenderDataFactory", remap = false)
public class MixinVoxyRenderDataFactoryEmissive {

    @Shadow @Final private ModelFactory modelMan;

    // generateMesh returns BuiltSection, so the callback must be a CallbackInfoReturnable —
    // a plain CallbackInfo is a *validation* error that fails the whole mixin despite require = 0.
    @Inject(method = "generateMesh", at = @At("HEAD"), require = 0)
    private void boxy$enterMeshing(CallbackInfoReturnable<?> cir) {
        // Deliberately never cleared: generateMesh routinely unwinds via
        // IdNotYetComputedException, and the value is refreshed on every entry (see VoxyBackports).
        VoxyBackports.enterMeshing(this.modelMan);
    }

    @Redirect(method = {
                      "generateYZOpaqueInnerGeometry",
                      "generateYZOpaqueOuterGeometry",
                      "generateYZFluidInnerGeometry",
                      "generateYZFluidOuterGeometry",
                      "generateYZNonOpaqueOuterGeometry",
                      "generateXOpaqueInnerGeometry",
                      "generateXOuterOpaqueGeometry",
                      "generateXInnerFluidGeometry",
                      "generateXOuterFluidGeometry"},
              at = @At(value = "INVOKE",
                       target = "Lme/cortex/voxy/client/core/rendering/building/RenderDataFactory$Mesher;putNext(J)V"),
              require = 0)
    private void boxy$emissivePutNext(@Coerce ScanMesher2D mesher, long quad) {
        // Shadowed field, not the ThreadLocal — this runs per emitted quad on the mesh workers.
        mesher.putNext(VoxyBackports.applyEmissiveLight(quad, this.modelMan));
    }

    @Redirect(method = {"meshNonOpaqueFace", "dualMeshNonOpaqueOuterX"},
              at = @At(value = "INVOKE",
                       target = "Lme/cortex/voxy/client/core/rendering/building/RenderDataFactory$Mesher;putNext(J)V"),
              require = 0)
    private static void boxy$emissivePutNextStatic(@Coerce ScanMesher2D mesher, long quad) {
        mesher.putNext(VoxyBackports.applyEmissiveLightTL(quad));
    }
}
