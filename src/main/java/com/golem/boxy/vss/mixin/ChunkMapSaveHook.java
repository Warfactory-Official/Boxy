package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.server.RequestProcessingService;
import com.golem.boxy.vss.server.ServerNetworking;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dirty-column live sync: when {@link ChunkMap}{@code .save(ChunkAccess)} (SRG {@code m_140258_}) succeeds, mark that
 * column dirty so the server re-pushes it to clients (via {@code DirtyColumnBroadcaster}). No-op when the VSS service
 * is not running. {@code remap = false} + hand-SRG (see {@link AccessorChunkMap}).
 */
@Mixin(value = ChunkMap.class, remap = false)
public class ChunkMapSaveHook {
    @Unique
    private String boxy$cachedDimension;

    // require = 0: dirty-sync is an optional feature, so if another mod (e.g. C2ME's reworked chunk I/O)
    // changes ChunkMap.save such that this injection point doesn't match, disable the feature rather than
    // aborting ChunkMap's class transform (which would crash world load).
    @Inject(method = "m_140258_", at = @At("RETURN"), require = 0)
    private void boxy$onChunkSaved(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            RequestProcessingService service = ServerNetworking.getRequestService();
            if (service != null) {
                if (this.boxy$cachedDimension == null) {
                    ServerLevel level = ((AccessorChunkMap) this).boxy$getLevel();
                    this.boxy$cachedDimension = level.dimension().location().toString();
                }
                service.getDirtyTracker().markDirty(this.boxy$cachedDimension, chunk.getPos().x, chunk.getPos().z);
            }
        }
    }
}
