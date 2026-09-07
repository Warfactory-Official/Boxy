package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.ClientEntitySync;
import com.golem.boxy.vss.common.TrackedEntityTypes;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lifts the client-side entity render-distance cull ({@code Entity.shouldRenderAtSqrDistance})
 * for the configured entity types, so entities the server now tracks at distance actually
 * render instead of being culled at vanilla's short range. Type-gated (not a blanket un-cull) to avoid an
 * FPS hit from rendering every nearby item/mob to the full distance.
 *
 * <p>Client-only — listed in {@code boxy.mixins.json} "client", so it never loads on a dedicated server.
 * The method is declared on
 * {@link Entity}; players inherit it (no override), so this covers remote players too.
 */
@Mixin(Entity.class)
public abstract class MixinEntityRenderDistance {
    @Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
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
