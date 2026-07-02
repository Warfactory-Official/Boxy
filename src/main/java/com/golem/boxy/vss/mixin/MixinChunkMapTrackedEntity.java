package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.common.TrackedEntityTypes;
import com.golem.boxy.vss.config.VSSServerConfig;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Extends server-side entity tracking range for the configured entity types so the <b>real</b> entity is
 * sent to distant players and drawn by the vanilla renderer with full fidelity. Without this the server
 * stops sending an entity past the receiver's view distance.
 *
 * <p>Targets the (package-private) inner class {@code ChunkMap$TrackedEntity}. {@code updatePlayer} (SRG
 * {@code m_140497_}) computes the send range as {@code Math.min(getEffectiveRange(), viewDistance*16)}; we
 * redirect that {@code Math.min} so a configured type uses {@code entityTrackingDistanceChunks*16} uncapped.
 * Hand-SRG, {@code remap = false}; {@code require = 0} so a mismatch degrades instead of aborting the class
 * transform (cf. {@link ChunkMapSaveHook}). A one-time diagnostic line is logged via
 * {@link TrackedEntityTypes#diagRedirect} so the server log confirms the mixin applied and the shadow bound.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity", remap = false)
public abstract class MixinChunkMapTrackedEntity {
    @Shadow
    @Final
    Entity f_140472_; // TrackedEntity.entity — the tracked entity

    @Redirect(
            method = "m_140497_",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"),
            require = 0)
    private int boxy$extendTrackingRange(int effectiveRange, int viewDistanceCap) {
        Entity entity = this.f_140472_;
        boolean extend = entity != null
                && VSSServerConfig.CONFIG.extendEntityTracking
                && TrackedEntityTypes.serverContains(entity.getType());
        TrackedEntityTypes.diagRedirect(entity == null ? null : entity.getType(), extend,
                VSSServerConfig.CONFIG.entityTrackingDistanceChunks);
        if (extend) {
            return VSSServerConfig.CONFIG.entityTrackingDistanceChunks * 16;
        }
        return Math.min(effectiveRange, viewDistanceCap);
    }
}
