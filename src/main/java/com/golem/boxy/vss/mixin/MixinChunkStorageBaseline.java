package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.server.RequestProcessingService;
import com.golem.boxy.vss.server.ServerNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Baseline late capture: every chunk NBT {@link ChunkMap} hands to storage. C2ME swaps the IOWorker below, not this. */
@Mixin(ChunkStorage.class)
public class MixinChunkStorageBaseline {
    // require = 0 as ChunkMapSaveHook: no match => chunks the baseline copy lacks take the live route.
    @Inject(method = "write", at = @At("HEAD"), require = 0)
    private void boxy$onWrite(ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if ((Object) this instanceof ChunkMap map) {
            RequestProcessingService service = ServerNetworking.getRequestService();
            if (service != null) {
                service.onChunkWritten(((AccessorChunkMap) map).boxy$getLevel(), pos, tag);
            }
        }
    }
}
