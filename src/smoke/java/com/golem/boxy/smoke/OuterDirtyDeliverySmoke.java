package com.golem.boxy.smoke;

import com.golem.boxy.vss.client.ClientNetworking;
import com.golem.boxy.vss.common.PositionUtil;
import com.golem.boxy.vss.server.ServerNetworking;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;

final class OuterDirtyDeliverySmoke {
    private static int phase;
    private static int ticks;
    private static volatile boolean unloaded;
    private static volatile Throwable failure;
    private static long reloadsBefore;

    static boolean check(Minecraft mc) {
        if (++ticks > 600) throw new AssertionError("Outer dirty refresh timed out at " + phase);
        if (failure != null) throw new AssertionError(failure);
        var server = mc.getSingleplayerServer();
        var worldId = WorldIdentifier.of(mc.level);
        if (phase == 0) {
            phase = 1;
            server.execute(() -> {
                try {
                    var level = server.overworld();
                    var chunk = level.getChunk(20, 0);
                    var section = chunk.getSection(level.getSectionIndex(240));
                    if (!section.hasOnlyAir()) throw new AssertionError("Outer fixture requires an empty high section");
                    section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState(), false);
                    section.setBlockState(0, 0, 0, Blocks.AIR.defaultBlockState(), false);
                    chunk.setUnsaved(true);
                } catch (Throwable error) { failure = error; }
            });
        } else if (phase == 1) {
            server.execute(() -> unloaded = server.overworld().getChunkSource().getChunkNow(20, 0) == null);
            if (!unloaded) return false;
            var section = new LevelChunkSection(mc.level.registryAccess().registryOrThrow(Registries.BIOME));
            section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState(), false);
            if (!VoxelIngestService.rawIngest(worldId, section, 20, 15, 0, null, null)) return false;
            reloadsBefore = ServerNetworking.getRequestService().getDirtyReloadCompletedCount();
            phase = 2;
        } else {
            var engine = VoxyCommon.getInstance().getNullable(worldId);
            var section = engine.acquireIfExists(0, 10, 7, 0);
            boolean air = true;
            if (section != null) {
                try { air = Mapper.isAir(section.copyData()[WorldSection.getIndex(0, 16, 0)]); }
                finally { section.release(); }
            }
            if (phase == 2 && !air) {
                ClientNetworking.getRequestManager().onDirtyColumns(new long[]{PositionUtil.packPosition(20, 0)});
                phase = 3;
            } else if (phase == 3 && air && ServerNetworking.getRequestService().getDirtyReloadCompletedCount() > reloadsBefore) {
                com.mojang.logging.LogUtils.getLogger().info("BOXY_OUTER_DIRTY_SMOKE_OK: unloaded terrain refreshed through temporary FULL ticket and stale Voxy voxel cleared without player approach");
                phase = 4;
            }
        }
        return phase == 4;
    }
}
