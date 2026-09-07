package com.golem.boxy.vss;

import com.golem.boxy.vss.mixin.AccessorChunkMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;

/** Bounded integrated-server sweep of spawn chunks generated before Voxy's client session starts. */
@EventBusSubscriber(modid = BoxyVss.MODID, value = Dist.CLIENT)
public final class BoxyServerIngest {
    private static final Map<ResourceKey<Level>, LongSet> SEEN = new HashMap<>();
    private static int sweepsLeft;
    private static int emptyStreak;
    private static int waitTicksLeft;
    private static int ticksSinceSweep;
    private static boolean bulkLogged;
    private static volatile long bulkIngestCount;
    private static net.minecraft.server.MinecraftServer activeServer;

    public static long getBulkIngestCount() { return bulkIngestCount; }

    private BoxyServerIngest() {}

    @SubscribeEvent
    public static void onBulkTerrainUpdate(com.golem.boxy.vss.server.BulkTerrainUpdateEvent event) {
        var chunk = event.chunk();
        if (!(chunk.getLevel() instanceof net.minecraft.server.level.ServerLevel level)
                || level.getServer() != activeServer || !activeServer.isRunning()) return;
        var instance = VoxyCommon.getInstance();
        if (instance == null) return;
        var world = me.cortex.voxy.commonImpl.WorldIdentifier.of(chunk.getLevel());
        if (world == null || !instance.isIngestEnabled(world)) return;
        var light = chunk.getLevel().getLightEngine();
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            for (int i = 0; i < chunk.getSections().length; i++) {
                var source = chunk.getSection(i);
                // Voxy's worker must not read HBM's mutable palettes after this callback returns.
                source.acquire();
                net.minecraft.world.level.chunk.LevelChunkSection copy;
                try {
                    copy = new net.minecraft.world.level.chunk.LevelChunkSection(source.getStates().copy(), source.getBiomes().recreate());
                    buffer.clear();
                    source.getBiomes().write(buffer);
                    copy.readBiomes(buffer);
                } finally { source.release(); }
                int y = chunk.getMinSection() + i;
                var pos = net.minecraft.core.SectionPos.of(chunk.getPos(), y);
                var block = light.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK).getDataLayerData(pos);
                var sky = com.golem.boxy.vss.client.IngestLighting.skyLight(
                        light.getLayerListener(net.minecraft.world.level.LightLayer.SKY), pos);
                if (!VoxelIngestService.rawIngest(world, copy, chunk.getPos().x, y, chunk.getPos().z,
                        block == null ? null : block.copy(), sky == null ? null : sky.copy())) {
                    com.golem.boxy.vss.common.VSSLogger.warn("HBM direct terrain ingest unavailable for " + chunk.getPos());
                    return;
                }
            }
            if (!bulkLogged) {
                bulkLogged = true;
                com.golem.boxy.vss.common.VSSLogger.info("Boxy HBM direct terrain ingest ACTIVE: completed bulk edits feed Voxy independently of player chunk tracking");
            }
            bulkIngestCount++;
        } finally { buffer.release(); }
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) activeServer = player.server;
        sweepsLeft = 15;
        emptyStreak = 0;
        waitTicksLeft = 600;
        ticksSinceSweep = 0;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (sweepsLeft <= 0) return;
        if (VoxyCommon.getInstance() == null) {
            if (--waitTicksLeft <= 0) sweepsLeft = 0;
            return;
        }
        if (++ticksSinceSweep < 20) return;
        ticksSinceSweep = 0;
        int budget = 512;
        for (var level : event.getServer().getAllLevels()) {
            LongSet seen = SEEN.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet());
            for (ChunkHolder holder : ((AccessorChunkMap) level.getChunkSource().chunkMap).boxy$getChunks()) {
                var chunk = holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
                if (chunk == null || seen.contains(chunk.getPos().toLong())) continue;
                if (VoxelIngestService.tryAutoIngestChunk(chunk)) {
                    seen.add(chunk.getPos().toLong());
                    if (--budget == 0) break;
                }
            }
            if (budget == 0) break;
        }
        sweepsLeft--;
        emptyStreak = budget == 512 ? emptyStreak + 1 : 0;
        if (emptyStreak >= 2) sweepsLeft = 0;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (activeServer == event.getServer()) activeServer = null;
        SEEN.clear();
        sweepsLeft = 0;
        bulkLogged = false;
    }
}
