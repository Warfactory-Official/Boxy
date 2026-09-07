package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.config.VSSServerConfig;
import com.golem.boxy.vss.server.ServerNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bulk section editors such as HBM mark chunks unsaved without emitting individual block updates. */
@Mixin(ChunkAccess.class)
public abstract class MixinChunkLiveDirty {
    @Unique private String boxy$dirtyDimension;

    @Inject(method = "setUnsaved", at = @At("RETURN"), require = 0)
    private void boxy$markBulkEdit(boolean unsaved, CallbackInfo ci) {
        if (!unsaved || !VSSServerConfig.CONFIG.liveDirtyUpdates
                || !((Object) this instanceof LevelChunk chunk)
                || !(chunk.getLevel() instanceof ServerLevel level)) return;
        var service = ServerNetworking.getRequestService();
        if (service == null) return;
        if (boxy$dirtyDimension == null) boxy$dirtyDimension = level.dimension().location().toString();
        // Repeated true notifications matter: another edit may follow a broadcast before autosave.
        service.getDirtyTracker().markDirty(boxy$dirtyDimension, chunk.getPos().x, chunk.getPos().z);
    }
}
