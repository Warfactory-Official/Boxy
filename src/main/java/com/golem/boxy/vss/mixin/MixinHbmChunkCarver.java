package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.config.VSSServerConfig;
import com.golem.boxy.vss.server.BulkTerrainUpdateEvent;
import com.golem.boxy.vss.server.ServerNetworking;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

@Pseudo
@Mixin(targets = "com.hbm.util.ChunkCarver", remap = false)
public abstract class MixinHbmChunkCarver {
    @Unique private static final TicketType<Long> boxy$repairTicket =
            TicketType.create("boxy_hbm_repair", Comparator.<Long>naturalOrder(), 200);
    @Unique private static final AtomicLong boxy$repairIds = new AtomicLong();

    @WrapOperation(method = "repairAndResend", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ThreadedLevelLightEngine;lightChunk(Lnet/minecraft/world/level/chunk/ChunkAccess;Z)Ljava/util/concurrent/CompletableFuture;"))
    private static CompletableFuture<ChunkAccess> boxy$afterRelight(ThreadedLevelLightEngine engine,
            ChunkAccess access, boolean alreadyLit, Operation<CompletableFuture<ChunkAccess>> original) {
        if (!VSSServerConfig.CONFIG.liveDirtyUpdates || !(access instanceof LevelChunk chunk)
                || !(chunk.getLevel() instanceof ServerLevel level)) return original.call(engine, access, alreadyLit);
        var service = ServerNetworking.getRequestService();
        if (service == null) return original.call(engine, access, alreadyLit);
        long id = boxy$repairIds.incrementAndGet();
        level.getChunkSource().addRegionTicket(boxy$repairTicket, chunk.getPos(), 0, id);
        try {
            return original.call(engine, access, alreadyLit).whenCompleteAsync((lit, error) -> {
                try {
                    if (error != null) return;
                    if (ServerNetworking.getRequestService() != service || !level.getServer().isRunning()
                            || !VSSServerConfig.CONFIG.liveDirtyUpdates) return;
                    service.getDirtyTracker().markDirty(level.dimension().location().toString(), chunk.getPos().x, chunk.getPos().z);
                    if (level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z) == chunk) {
                        NeoForge.EVENT_BUS.post(new BulkTerrainUpdateEvent(chunk));
                    }
                } catch (Exception failure) {
                    VSSLogger.error("HBM bulk terrain notification failed", failure);
                } finally {
                    level.getChunkSource().removeRegionTicket(boxy$repairTicket, chunk.getPos(), 0, id);
                }
            }, level.getServer());
        } catch (RuntimeException | Error failure) {
            level.getChunkSource().removeRegionTicket(boxy$repairTicket, chunk.getPos(), 0, id);
            throw failure;
        }
    }
}
