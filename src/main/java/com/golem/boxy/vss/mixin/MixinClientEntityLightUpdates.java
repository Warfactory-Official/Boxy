package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.DistantEntityLighting;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Invalidates the sampled source before native queued lighting is applied, even without chunk unload. */
@Mixin(ClientPacketListener.class)
public abstract class MixinClientEntityLightUpdates {
    @WrapOperation(method = "handleLevelChunkWithLight", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;queueLightUpdate(Ljava/lang/Runnable;)V"))
    private void boxy$chunkLight(ClientLevel level, Runnable update, Operation<Void> original,
                                @Local(argsOnly = true) ClientboundLevelChunkWithLightPacket packet) {
        DistantEntityLighting.lightUpdateQueued(level, packet.getX(), packet.getZ());
        original.call(level, update);
    }

    @WrapOperation(method = "handleLightUpdatePacket", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;queueLightUpdate(Ljava/lang/Runnable;)V"))
    private void boxy$lightUpdate(ClientLevel level, Runnable update, Operation<Void> original,
                                 @Local(argsOnly = true) ClientboundLightUpdatePacket packet) {
        DistantEntityLighting.lightUpdateQueued(level, packet.getX(), packet.getZ());
        original.call(level, update);
    }
}
