package com.golem.boxy.smoke;

import com.golem.boxy.vss.common.PositionUtil;
import com.golem.boxy.vss.common.voxel.SerializedColumnCache;
import com.golem.boxy.vss.config.VSSServerConfig;
import com.golem.boxy.vss.server.ServerNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

final class BulkTerrainSmoke {
    static void check(ServerLevel level) {
        var service = ServerNetworking.getRequestService();
        var tracker = service.getDirtyTracker();
        var chunk = level.getChunk(0, 0);
        var section = chunk.getSection(level.getSectionIndex(120));
        var original = section.getBlockState(1, 8, 1);
        String dimension = level.dimension().location().toString();
        long packed = PositionUtil.packPosition(0, 0);
        boolean enabled = VSSServerConfig.CONFIG.liveDirtyUpdates;
        try {
            var field = service.getClass().getDeclaredField("bytesCache");
            field.setAccessible(true);
            var bytes = (SerializedColumnCache) field.get(service);
            VSSServerConfig.CONFIG.liveDirtyUpdates = true;
            tracker.drainDirty(dimension);
            bytes.put(dimension, packed, new byte[]{1});
            // HBM's fast crater/fallout path: edit the section, then notify the owning chunk once.
            section.setBlockState(1, 8, 1, Blocks.STONE.defaultBlockState(), false);
            chunk.setUnsaved(true);
            if (tracker.drainDirty(dimension) == null || bytes.get(dimension, packed) != null) {
                throw new AssertionError("Bulk section edit did not invalidate the live column cache");
            }
            bytes.put(dimension, packed, new byte[]{2});
            section.setBlockState(1, 8, 1, Blocks.AIR.defaultBlockState(), false);
            chunk.setUnsaved(true);
            if (tracker.drainDirty(dimension) == null || bytes.get(dimension, packed) != null) {
                throw new AssertionError("Repeated edit to an already-unsaved chunk was lost");
            }
            chunk.setUnsaved(false);
            if (tracker.drainDirty(dimension) != null) throw new AssertionError("Clearing unsaved marked terrain dirty");
            VSSServerConfig.CONFIG.liveDirtyUpdates = false;
            chunk.setUnsaved(true);
            if (tracker.drainDirty(dimension) != null) throw new AssertionError("Live dirty opt-out ignored");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        } finally {
            VSSServerConfig.CONFIG.liveDirtyUpdates = enabled;
            section.setBlockState(1, 8, 1, original, false);
            chunk.setUnsaved(true);
        }
        com.mojang.logging.LogUtils.getLogger().info("BOXY_BULK_TERRAIN_SMOKE_OK: direct section edits, immediate cache invalidation, repeated unsaved edits, config opt-out");
    }
}
