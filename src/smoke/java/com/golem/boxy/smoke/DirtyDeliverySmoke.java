package com.golem.boxy.smoke;

import com.golem.boxy.vss.client.ClientNetworking;
import com.golem.boxy.vss.common.PositionUtil;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Verifies server notification -> dirty request -> empty-column response -> actual Voxy voxel clearing. */
final class DirtyDeliverySmoke {
    private static int phase;
    private static int ticks;
    private static volatile Throwable failure;

    static boolean check(Minecraft mc) {
        if (++ticks > 600) throw new AssertionError("Dirty terrain delivery timed out at phase " + phase);
        if (failure != null) throw new AssertionError("Dirty terrain fixture failed", failure);
        var worldId = WorldIdentifier.of(mc.level);
        var engine = VoxyCommon.getInstance().getNullable(worldId);
        if (engine == null) return false;
        if (phase == 0) {
            // Native-ingested terrain need not have a VSS timestamp. Seed precisely that case.
            try {
                var manager = ClientNetworking.getRequestManager();
                var field = manager.getClass().getDeclaredField("columnTimestamps");
                field.setAccessible(true);
                ((it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap) field.get(manager)).remove(PositionUtil.packPosition(2, 2));
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            var section = new LevelChunkSection(mc.level.registryAccess().registryOrThrow(Registries.BIOME));
            section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState(), false);
            if (!VoxelIngestService.rawIngest(worldId, section, 2, 15, 2, null, null)) return false;
            phase = 1;
            return false;
        }
        var section = engine.acquireIfExists(0, 1, 7, 1);
        boolean air = true;
        if (section != null) {
            try { air = Mapper.isAir(section.copyData()[WorldSection.getIndex(0, 16, 0)]); }
            finally { section.release(); }
        }
        if (phase == 1 && !air) {
            phase = 2;
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    var chunk = server.overworld().getChunk(2, 2);
                    var terrain = chunk.getSection(server.overworld().getSectionIndex(240));
                    if (!terrain.hasOnlyAir()) throw new AssertionError("Dirty test requires an empty high section");
                    terrain.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState(), false);
                    terrain.setBlockState(0, 0, 0, Blocks.AIR.defaultBlockState(), false);
                    chunk.setUnsaved(true);
                } catch (Throwable error) { failure = error; }
            });
        } else if (phase == 2 && air) {
            try {
                var manager = ClientNetworking.getRequestManager();
                var field = manager.getClass().getDeclaredField("columnTimestamps");
                field.setAccessible(true);
                if (((it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap) field.get(manager))
                        .get(PositionUtil.packPosition(2, 2)) <= 0) return false;
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            phase = 3;
            mc.getSingleplayerServer().execute(() -> {
                var level = mc.getSingleplayerServer().overworld();
                var chunk = level.getChunk(2, 2);
                chunk.getSection(level.getSectionIndex(240)).setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState(), false);
                chunk.setUnsaved(true);
            });
        } else if (phase == 3 && !air) {
            phase = 4;
            mc.getSingleplayerServer().execute(() -> {
                var level = mc.getSingleplayerServer().overworld();
                var chunk = level.getChunk(2, 2);
                chunk.getSection(level.getSectionIndex(240)).setBlockState(0, 0, 0, Blocks.AIR.defaultBlockState(), false);
                chunk.setUnsaved(true);
            });
        } else if (phase == 4 && air) {
            com.mojang.logging.LogUtils.getLogger().info("BOXY_DIRTY_DELIVERY_SMOKE_OK: native-only column inside view distance, stale section clearing, repeated live addition/removal verified in Voxy");
            phase = 5;
        }
        return phase == 5;
    }
}
