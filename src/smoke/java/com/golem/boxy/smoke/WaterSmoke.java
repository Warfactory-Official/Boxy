package com.golem.boxy.smoke;

import com.golem.boxy.vss.client.LodWaterData;
import com.golem.boxy.vss.client.LodWaterSweep;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dark LOD water regression. A walled lake on a stone column tops out at y 126 under open sky, out past the four-chunk view so it only
 * reaches the client through Boxy's VSS streaming. Its top at y 126 sits on a section's top layer at every LOD level
 * above 0 (as sea-level water at y 62 does), so each coarser level lights it from the air section above. Fails when
 * {@link LodWaterData#audit} finds any dark water top in the lake or {@link LodWaterSweep} sees starved water on screen.
 */
public final class WaterSmoke {
    static final int X0 = 96, X1 = 224, Z0 = -64, Z1 = 64, FLOOR = 119, TOP = 126;
    private static final double MAX_SCREEN_PCT = 0.1;
    private static int ticks, readyAt = -1;
    private static boolean sweeping;
    private static float savedSubdivision;
    private static java.util.List<LodWaterData.AuditRow> rows;

    private WaterSmoke() {
    }

    public static void place(ServerPlayer player) {
        var level = player.serverLevel();
        for (int cx = (X0 - 1) >> 4; cx <= X1 >> 4; cx++) {
            for (int cz = (Z0 - 1) >> 4; cz <= Z1 >> 4; cz++) {
                var pos = new ChunkPos(cx, cz);
                level.getChunkSource().addRegionTicket(TicketType.FORCED, pos, 0, pos);
                level.getChunk(cx, cz);
            }
        }
        BlockState stone = Blocks.STONE.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
        var pos = new BlockPos.MutableBlockPos();
        for (int x = X0 - 1; x <= X1; x++) {
            for (int z = Z0 - 1; z <= Z1; z++) {
                boolean rim = x < X0 || x >= X1 || z < Z0 || z >= Z1;
                level.setBlock(pos.set(x, FLOOR, z), stone, 2);
                for (int y = FLOOR + 1; y <= TOP; y++) {
                    level.setBlock(pos.set(x, y, z), rim ? stone : water, 2);
                }
                // solid down to the ground: anything under the lake sits in its shadow, really dark, and would read as
                // starved on screen
                for (int y = FLOOR - 1; y >= level.getMinBuildHeight(); y--) {
                    BlockState below = level.getBlockState(pos.set(x, y, z));
                    if (!below.isAir() && below.getFluidState().isEmpty()) {
                        break;
                    }
                    level.setBlock(pos, stone, 2);
                }
                // open sky over the lake whatever the world generated there
                for (int y = TOP + 1; y < level.getMaxBuildHeight(); y++) {
                    if (!level.getBlockState(pos.set(x, y, z)).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
        level.setDayTime(6000L);
        level.setWeatherParameters(6000, 0, false, false);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, level.getServer());
        player.setGameMode(GameType.SPECTATOR);
        player.teleportTo(level, 0.5, 200, 0.5, -90, 30);
    }

    public static void tick(Minecraft mc) {
        ++ticks;
        if (sweeping) {
            if (LodWaterSweep.running()) {
                return;
            }
            finish(mc);
            return;
        }
        if (readyAt < 0) {
            if (ticks % 20 == 0 && lakeInVoxy(mc)) {
                readyAt = ticks;
            } else if (ticks > 3600) {
                throw new AssertionError("Water smoke: the lake never reached Voxy through VSS streaming");
            }
            return;
        }
        if (ticks < readyAt + 200) {
            return; // let streaming finish the lake's columns and Voxy mesh them
        }
        WorldEngine engine = engine(mc);
        if (engine == null) throw new AssertionError("Water smoke: Voxy has no world engine");
        try {
            rows = LodWaterData.audit(engine, (X0 + X1) / 2, (Z0 + Z1) / 2, (X1 - X0) / 2 - 1, FLOOR, TOP + 2);
        } finally {
            engine.releaseRef();
        }
        // Close up Voxy draws the lake at level 0, which is lit; a larger subdivision size draws it at the coarse
        // levels distant water uses, so the screen sees what the audit measures.
        savedSubdivision = me.cortex.voxy.client.config.VoxyConfig.CONFIG.subDivisionSize;
        me.cortex.voxy.client.config.VoxyConfig.CONFIG.subDivisionSize = 512f;
        mc.player.setYRot(-90);
        mc.player.setXRot(30);
        com.mojang.logging.LogUtils.getLogger().info("BOXY_WATER_SMOKE sweep: {}", LodWaterSweep.start(mc, 1, 30f, 100));
        sweeping = true;
    }

    private static void finish(Minecraft mc) {
        me.cortex.voxy.client.config.VoxyConfig.CONFIG.subDivisionSize = savedSubdivision;
        var log = com.mojang.logging.LogUtils.getLogger();
        long dark = 0, tops = 0;
        for (var row : rows) {
            log.info("BOXY_WATER_SMOKE audit {}", row);
            dark += row.dark();
            tops += row.tops();
        }
        double screen = LodWaterSweep.worstWaterPct();
        log.info("BOXY_WATER_SMOKE result: {} of {} lake water tops lit dark, starved water {}% of screen", dark, tops, screen);
        if (tops == 0) throw new AssertionError("Water smoke: audit found no lake water in Voxy");
        if (dark > 0 || screen > MAX_SCREEN_PCT) {
            throw new AssertionError("Dark LOD water: " + dark + " of " + tops + " lake water tops lit dark, starved water "
                    + screen + "% of screen (limit " + MAX_SCREEN_PCT + "%)");
        }
        log.info("BOXY_CLIENT_SMOKE_OK: LOD water lit at every level ({} tops), starved water {}% of screen", tops, screen);
        mc.stop();
    }

    /** True once the lake's middle is stored at level 0, i.e. VSS streamed and Voxy ingested it. */
    private static boolean lakeInVoxy(Minecraft mc) {
        if (mc.level == null || VoxyCommon.getInstance() == null) {
            return false;
        }
        WorldEngine engine = engine(mc);
        if (engine == null) {
            return false;
        }
        try {
            int x = (X0 + X1) / 2, z = (Z0 + Z1) / 2;
            WorldSection section = engine.acquireIfExists(0, x >> 5, TOP >> 5, z >> 5);
            if (section == null) {
                return false;
            }
            try {
                long id = section._unsafeGetRawDataArray()[WorldSection.getIndex(x & 31, TOP & 31, z & 31)];
                return !Mapper.isAir(id) && engine.getMapper().getBlockStateFromBlockId(Mapper.getBlockId(id)).is(Blocks.WATER);
            } finally {
                section.release();
            }
        } finally {
            engine.releaseRef();
        }
    }

    private static WorldEngine engine(Minecraft mc) {
        var voxy = VoxyCommon.getInstance();
        var id = WorldIdentifier.of(mc.level);
        return voxy == null || !voxy.isRunning() || id == null ? null : voxy.getOrCreate(id, true);
    }
}
