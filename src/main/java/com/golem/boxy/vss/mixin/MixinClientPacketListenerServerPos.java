package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.DistantEntityServerPos;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Capture absolute packet positions before interpolation, so distant vehicles can reject unloaded-world gravity. */
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListenerServerPos {
    @WrapOperation(method = {"handleMoveEntity", "handleTeleportEntity"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;lerpTo(DDDFFI)V"), require = 3)
    private void boxy$captureServerPos(Entity instance, double x, double y, double z, float yRot, float xRot,
                                       int steps, Operation<Void> original) {
        DistantEntityServerPos.put(instance.getId(), x, y, z, yRot, xRot);
        original.call(instance, x, y, z, yRot, xRot, steps);
    }
}
