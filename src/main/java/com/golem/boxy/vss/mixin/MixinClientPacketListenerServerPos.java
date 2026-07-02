package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.DistantEntityServerPos;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Records the server-authoritative absolute position of every non-locally-controlled entity, captured where
 * the client computes it and hands it to {@code Entity.lerpTo} (SRG {@code m_6453_}, descriptor
 * {@code (DDDFFIZ)V}). {@link DistantEntityServerPos} keeps it; {@link com.golem.boxy.vss.client.DistantEntityTicker}
 * uses it to pin a distant non-living entity's Y to the server value so client-side gravity can't make it
 * fall through the un-loaded terrain (a vehicle like the jet mod gets a position update only every few
 * ticks, so between updates it would free-fall, then snap back).
 *
 * <p>Wraps the {@code lerpTo} call in {@code handleMoveEntity} ({@code m_7865_}) and {@code handleTeleportEntity}
 * ({@code m_6435_}). Client-only; hand-SRG, {@code remap = false}, {@code require = 0} so it degrades instead
 * of crashing. Uses MixinExtras {@code @WrapOperation} (on Boxy's classpath).
 */
@Mixin(value = ClientPacketListener.class, remap = false)
public abstract class MixinClientPacketListenerServerPos {
    @WrapOperation(
            method = {"m_7865_", "m_6435_"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;m_6453_(DDDFFIZ)V"),
            require = 0)
    private void boxy$captureServerPos(Entity instance, double x, double y, double z, float yRot, float xRot,
                                       int steps, boolean teleport, Operation<Void> original) {
        DistantEntityServerPos.put(instance.getId(), x, y, z, yRot, xRot);
        original.call(instance, x, y, z, yRot, xRot, steps, teleport);
    }
}
