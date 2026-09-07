package com.golem.boxy.smoke;

import com.golem.boxy.vss.BoxyServerIngest;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;

final class HbmTerrainSmoke {
    private static int ticks;
    private static int phase;
    private static long before;
    private static volatile Throwable failure;

    static void tick(Minecraft mc) {
        if (++ticks < 320) return; // Let the unrelated startup ingestion sweep finish.
        if (ticks > 1000) throw new AssertionError("HBM direct update timed out at phase " + phase);
        if (failure != null) throw new AssertionError("Actual HBM repair failed", failure);
        var id = WorldIdentifier.of(mc.level);
        var engine = VoxyCommon.getInstance().getNullable(id);
        if (engine == null) return;
        if (phase == 0) {
            before = BoxyServerIngest.getBulkIngestCount();
            var section = new LevelChunkSection(mc.level.registryAccess().registryOrThrow(Registries.BIOME));
            section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState(), false);
            if (VoxelIngestService.rawIngest(id, section, 20, 15, 0, null, null)) phase = 1;
            return;
        }
        var section = engine.acquireIfExists(0, 10, 7, 0);
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
                    var level = server.overworld();
                    var chunk = level.getChunk(20, 0);
                    chunk.getSection(level.getSectionIndex(240)).setBlockState(0, 0, 0, Blocks.AIR.defaultBlockState(), false);
                    boolean[] touched = new boolean[chunk.getSections().length];
                    touched[level.getSectionIndex(240)] = true;
                    Class.forName("com.hbm.util.ChunkCarver").getMethod("repairAndResend",
                            net.minecraft.server.level.ServerLevel.class, net.minecraft.world.level.chunk.LevelChunk.class,
                            int.class, int.class, boolean[].class).invoke(null, level, chunk, 20, 0, touched);
                } catch (Throwable error) { failure = error; }
            });
        } else if (phase == 2 && air && BoxyServerIngest.getBulkIngestCount() > before) {
            com.mojang.logging.LogUtils.getLogger().info("BOXY_HBM_DIRECT_SMOKE_OK: actual HBM repair/relight hook cleared distant Voxy terrain with VSS reception disabled");
            com.mojang.logging.LogUtils.getLogger().info("BOXY_CLIENT_SMOKE_OK: direct HBM integration verified");
            mc.stop();
            phase = 3;
        }
    }
}
