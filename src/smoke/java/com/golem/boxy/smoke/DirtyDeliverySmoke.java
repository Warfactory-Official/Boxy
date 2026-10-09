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
    /** Reflection must run on LodRequestPlanner's single worker; the client thread only polls completion. */
    private static java.util.concurrent.Future<?> timestampTask;
    private static Object timestampResult;

    private static java.util.concurrent.Future<?> timestampTask(Object manager, long position, boolean remove) {
        try {
            var plannerField = manager.getClass().getDeclaredField("planner");
            plannerField.setAccessible(true);
            Object planner = plannerField.get(manager);
            var executorField = manager.getClass().getDeclaredField("executor");
            executorField.setAccessible(true);
            var executor = (java.util.concurrent.ExecutorService) executorField.get(manager);
            return executor.submit(() -> {
                var timestampsField = planner.getClass().getDeclaredField("columnTimestamps");
                timestampsField.setAccessible(true);
                var timestamps = (it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap) timestampsField.get(planner);
                if (remove) {
                    timestamps.remove(position);
                    return null;
                }
                return timestamps.get(position);
            });
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to schedule planner timestamp fixture", e);
        }
    }

    /** Returns false while pending; get() is only called after isDone(), so the client thread never blocks. */
    private static boolean pollTimestampTask() {
        if (timestampTask == null || !timestampTask.isDone()) return false;
        try {
            timestampResult = timestampTask.get();
            timestampTask = null;
            return true;
        } catch (Exception e) {
            timestampTask = null;
            throw new AssertionError("Planner timestamp fixture failed", e);
        }
    }

    static boolean check(Minecraft mc) {
        if (++ticks > 600) throw new AssertionError("Dirty terrain delivery timed out at phase " + phase);
        if (failure != null) throw new AssertionError("Dirty terrain fixture failed", failure);
        var worldId = WorldIdentifier.of(mc.level);
        var engine = VoxyCommon.getInstance().getNullable(worldId);
        if (engine == null) return false;
        if (phase == 0) {
            // Native-ingested terrain need not have a VSS timestamp. Seed precisely that case.
            var manager = ClientNetworking.getRequestManager();
            if (manager == null) return false;
            if (timestampTask == null) {
                timestampTask = timestampTask(manager, PositionUtil.packPosition(2, 2), true);
                return false;
            }
            if (!pollTimestampTask()) return false;
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
            var manager = ClientNetworking.getRequestManager();
            if (manager == null) return false;
            if (timestampTask == null) {
                timestampTask = timestampTask(manager, PositionUtil.packPosition(2, 2), false);
                return false;
            }
            if (!pollTimestampTask()) return false;
            if (timestampResult == null || ((Long) timestampResult) <= 0L) return false;
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
