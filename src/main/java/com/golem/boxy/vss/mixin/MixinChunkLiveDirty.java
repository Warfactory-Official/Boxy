package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.common.tracking.LiveDirtyChunk;
import com.golem.boxy.vss.config.VSSServerConfig;
import com.golem.boxy.vss.server.ServerNetworking;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bulk section editors such as HBM mark chunks unsaved without emitting individual block updates.
 * Quiet sources excluded: a FULL reload relights => {@code setUnsaved(true)} => dirty => client refresh => reload, forever.
 */
@Mixin(ChunkAccess.class)
public abstract class MixinChunkLiveDirty implements LiveDirtyChunk {
    @Unique private String boxy$dirtyDimension;
    @Unique private boolean boxy$quiet;
    @Unique private boolean boxy$contentEdited;

    @Inject(method = "setUnsaved", at = @At("RETURN"), require = 0)
    private void boxy$markBulkEdit(boolean unsaved, CallbackInfo ci) {
        if (!unsaved || boxy$quiet || !((Object) this instanceof LevelChunk chunk)
                || !(chunk.getLevel() instanceof ServerLevel level)) return;
        boxy$contentEdited = true;
        if (!VSSServerConfig.CONFIG.liveDirtyUpdates) return;
        var service = ServerNetworking.getRequestService();
        if (service == null) return;
        if (boxy$dirtyDimension == null) boxy$dirtyDimension = level.dimension().location().toString();
        // Repeated true notifications matter: another edit may follow a broadcast before autosave.
        service.getDirtyTracker().markDirty(boxy$dirtyDimension, chunk.getPos().x, chunk.getPos().z);
    }

    @WrapOperation(method = {"setLightCorrect", "setData", "removeData"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setUnsaved(Z)V"), require = 0)
    private void boxy$quietSelf(ChunkAccess self, boolean unsaved, Operation<Void> original) {
        boxy$quiet = true;
        try {
            original.call(self, unsaved);
        } finally {
            boxy$quiet = false;
        }
    }

    @Override
    public void boxy$setUnsavedQuietly(boolean unsaved) {
        // Light engine sees a loaded FULL chunk through its imposter, which forwards setUnsaved to the wrapped one.
        if ((Object) this instanceof ImposterProtoChunk imposter) {
            ((LiveDirtyChunk) imposter.getWrapped()).boxy$setUnsavedQuietly(unsaved);
            return;
        }
        boxy$quiet = true;
        try {
            ((ChunkAccess) (Object) this).setUnsaved(unsaved);
        } finally {
            boxy$quiet = false;
        }
    }

    @Override
    public void boxy$markContentEdited() {
        boxy$contentEdited = true;
    }

    @Override
    public boolean boxy$takeContentEdited() {
        boolean edited = boxy$contentEdited;
        boxy$contentEdited = false;
        return edited;
    }
}
