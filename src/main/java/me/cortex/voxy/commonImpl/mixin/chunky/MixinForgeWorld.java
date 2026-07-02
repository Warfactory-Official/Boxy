package me.cortex.voxy.commonImpl.mixin.chunky;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.util.Either;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

/**
 * Forge replacement for Voxy's Fabric-only {@code chunky.MixinFabricWorld} (the Chunky auto-ingest
 * hook). Boxy compiles this in dev names, the build reobfuscates the body to SRG, and VoxyRemapper
 * injects it into Voxy's jar + adds it to {@code common.voxy.mixins.json}.
 *
 * <p>Chunky's {@code ForgeWorld.getChunkAtAsync} calls
 * {@code ChunkHolder.getOrScheduleFuture(ChunkStatus.FULL, ChunkMap)} — the same call Voxy's Fabric
 * mixin wraps — so we wrap it identically and ingest the fully-lit FULL chunk from the future result.
 *
 * <p>The ingest runs <b>inline</b> in the future callback, exactly like Voxy's original: at that point
 * Chunky still holds the chunk's ticket, so the chunk and its light data are loaded. Deferring to a
 * later server tick (an earlier Boxy attempt) let chunks outside the player's view distance unload and
 * drop their light before ingest, so pre-generated far terrain never became an LOD.
 *
 * <p>Starlight (Forge) compatibility — accepting the light data it produces — lives in
 * {@code MixinVoxelIngestStarlight}; Starlight's light reads are concurrent-safe, so inline ingest on
 * C2ME's worker thread is fine.
 *
 * <p>{@code remap = false}: Boxy's reobf maps the body's bytecode but not annotation strings, so the
 * {@code @At} target is written directly in SRG ({@code m_140049_} = getOrScheduleFuture).
 */
@Mixin(targets = "org.popcraft.chunky.platform.ForgeWorld", remap = false)
public class MixinForgeWorld {
    @WrapOperation(
            method = "getChunkAtAsync",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkHolder;m_140049_(Lnet/minecraft/world/level/chunk/ChunkStatus;Lnet/minecraft/server/level/ChunkMap;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> voxy$ingestGeneratedChunk(
            ChunkHolder holder, ChunkStatus status, ChunkMap storage,
            Operation<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> original) {
        return original.call(holder, status, storage).thenApply(result -> {
            result.ifLeft(chunk -> {
                if (chunk instanceof LevelChunk worldChunk) {
                    VoxelIngestService.tryAutoIngestChunk(worldChunk);
                }
            });
            return result;
        });
    }
}
