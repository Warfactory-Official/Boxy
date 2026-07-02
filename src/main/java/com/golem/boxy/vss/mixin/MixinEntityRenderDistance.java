package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.ClientEntitySync;
import com.golem.boxy.vss.common.TrackedEntityTypes;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lifts the client-side entity render-distance cull ({@code Entity.shouldRenderAtSqrDistance}, SRG
 * {@code m_6783_}) for the configured entity types, so entities the server now tracks at distance actually
 * render instead of being culled at vanilla's short range. Type-gated (not a blanket un-cull) to avoid an
 * FPS hit from rendering every nearby item/mob to the full distance.
 *
 * <p>Client-only — listed in {@code boxy.mixins.json} "client", so it never loads on a dedicated server.
 * Hand-SRG, {@code remap = false}; {@code require = 0} for safety. {@code m_6783_} is declared on
 * {@link Entity}; players inherit it (no override), so this covers remote players too.
 */
@Mixin(value = Entity.class, remap = false)
public abstract class MixinEntityRenderDistance {
    @Inject(method = "m_6783_", at = @At("HEAD"), cancellable = true, require = 0)
    private void boxy$extendRenderDistance(double distanceSq, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (ClientEntitySync.enabled()
                && TrackedEntityTypes.clientContains(self.getType())) {
            TrackedEntityTypes.diagClientCull(self.getType());
            double max = (double) ClientEntitySync.distanceChunks() * 16.0;
            if (distanceSq <= max * max) {
                cir.setReturnValue(true);
            }
        }
    }
}
