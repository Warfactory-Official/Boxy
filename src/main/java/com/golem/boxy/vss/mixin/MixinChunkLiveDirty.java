package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.common.tracking.DirtyColumnTracker;
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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bulk section editors such as HBM mark chunks unsaved without emitting individual block updates.
 * Quiet sources excluded: a FULL reload relights => {@code setUnsaved(true)} => dirty => client refresh => reload, forever.
 */
@Mixin(ChunkAccess.class)
public abstract class MixinChunkLiveDirty implements LiveDirtyChunk {
    /** Per-thread, not per-chunk: light threads and region workers hit the same chunk concurrently. */
    @Unique private static final ThreadLocal<int[]> boxy$quietDepth = ThreadLocal.withInitial(() -> new int[1]);
    @Unique private DirtyColumnTracker.Dimension boxy$dirty;
    @Unique private final AtomicBoolean boxy$contentEdited = new AtomicBoolean();

    @Inject(method = "setUnsaved", at = @At("RETURN"), require = 0)
    private void boxy$markBulkEdit(boolean unsaved, CallbackInfo ci) {
        if (!unsaved || boxy$quietDepth.get()[0] != 0 || !((Object) this instanceof LevelChunk chunk)
                || !(chunk.getLevel() instanceof ServerLevel level)) return;
        boxy$markContentEdited();
        if (!VSSServerConfig.CONFIG.liveDirtyUpdates) return;
        var service = ServerNetworking.getRequestService();
        if (service == null) return;
        var tracker = service.getDirtyTracker();
        var dirty = boxy$dirty;
        if (dirty == null || dirty.tracker() != tracker) {
            dirty = tracker.dimension(level.dimension().location().toString());
            boxy$dirty = dirty;
        }
        // Repeated true notifications matter: another edit may follow a broadcast before autosave.
        dirty.mark(chunk.getPos().x, chunk.getPos().z);
    }

    @WrapOperation(method = {"setLightCorrect", "setData", "removeData"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setUnsaved(Z)V"), require = 0)
    private void boxy$quietSelf(ChunkAccess self, boolean unsaved, Operation<Void> original) {
        int[] depth = boxy$quietDepth.get();
        depth[0]++;
        try {
            original.call(self, unsaved);
        } finally {
            depth[0]--;
        }
    }

    @Override
    public void boxy$setUnsavedQuietly(boolean unsaved) {
        // Light engine sees a loaded FULL chunk through its imposter, which forwards setUnsaved to the wrapped one.
        if ((Object) this instanceof ImposterProtoChunk imposter) {
            ((LiveDirtyChunk) imposter.getWrapped()).boxy$setUnsavedQuietly(unsaved);
            return;
        }
        int[] depth = boxy$quietDepth.get();
        depth[0]++;
        try {
            ((ChunkAccess) (Object) this).setUnsaved(unsaved);
        } finally {
            depth[0]--;
        }
    }

    @Override
    public void boxy$markContentEdited() {
        // Read-first: repeated edits do no volatile store.
        if (!boxy$contentEdited.get()) boxy$contentEdited.set(true);
    }

    @Override
    public boolean boxy$takeContentEdited() {
        return boxy$contentEdited.get() && boxy$contentEdited.getAndSet(false);
    }
}
