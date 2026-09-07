package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.common.TrackedEntityTypes;
import com.golem.boxy.vss.config.VSSServerConfig;
import com.golem.boxy.vss.server.ServerNetworking;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Extend both 1.21.1 tracking gates, without overriding entity visibility or pending chunk delivery. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class MixinChunkMapTrackedEntity {
    @Shadow @Final Entity entity;
    @Shadow @Final private Set<ServerPlayerConnection> seenBy;

    @Unique
    private boolean boxy$extendsTracking(ServerPlayer player) {
        return VSSServerConfig.CONFIG.extendEntityTracking
                && ServerNetworking.hasEntitySession(player)
                && TrackedEntityTypes.serverContains(entity.getType());
    }

    @ModifyExpressionValue(method = "updatePlayer", at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"))
    private int boxy$extendTrackingRange(int original, ServerPlayer player) {
        boolean extend = boxy$extendsTracking(player);
        TrackedEntityTypes.diagRedirect(entity.getType(), extend, VSSServerConfig.CONFIG.entityTrackingDistanceChunks);
        return extend ? Math.max(original, VSSServerConfig.CONFIG.entityTrackingDistanceChunks * 16) : original;
    }

    @ModifyExpressionValue(method = "updatePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;isChunkTracked(Lnet/minecraft/server/level/ServerPlayer;II)Z"))
    private boolean boxy$trackOutsideChunkView(boolean original, ServerPlayer player) {
        if (original || !boxy$extendsTracking(player)) return original;
        ChunkPos pos = entity.chunkPosition();
        // Native chunk delivery must not tear down an existing distant-entity pairing.
        return seenBy.contains(player.connection) || !player.connection.chunkSender.isPending(pos.toLong());
    }
}
