package com.golem.boxy.smoke;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** A real ghast behind a server-built wall outside the client's four-chunk vanilla view. */
public final class OcclusionSmoke {
    public static final int ENTITY_X = Integer.getInteger("boxy.smokeOcclusionDistance", 160);
    private static final int WALL_X = ENTITY_X - 32;
    public static volatile java.util.UUID target;
    private static int ticks;
    public static int measuredPasses;
    public static int joins;
    public static long samples;
    private static volatile boolean wallReady;

    public static void place(ServerPlayer player) {
        var level = player.serverLevel();
        for (int x : new int[]{8, WALL_X >> 4, ENTITY_X >> 4}) {
            for (int z = -1; z <= 1; z++) {
                var pos = new ChunkPos(x, z);
                level.getChunkSource().addRegionTicket(TicketType.FORCED, pos, 0, pos);
                level.getChunk(x, z);
            }
        }
        level.getEntitiesOfClass(Entity.class, new AABB(ENTITY_X - 5, 105, -8, ENTITY_X + 9, 139, 9),
                entity -> entity.getType() == EntityType.GHAST || entity.getType() == EntityType.PIG).forEach(Entity::discard);
        setWall(player, false);
        if (WALL_X != 128) setWallAt(player, 128, false);
        var ghast = EntityType.GHAST.create(level);
        ghast.setNoAi(true);
        ghast.setNoGravity(true);
        ghast.setPersistenceRequired();
        ghast.moveTo(ENTITY_X + 0.5, 120, 0.5);
        target = ghast.getUUID();
        level.addFreshEntity(ghast);
        player.setGameMode(GameType.SPECTATOR);
        player.teleportTo(level, 0.5, 120, 0.5, -90, 0);
    }

    private static void setWall(ServerPlayer player, boolean solid) {
        setWallAt(player, WALL_X, solid);
    }

    private static void setWallAt(ServerPlayer player, int wallX, boolean solid) {
        var level = player.serverLevel();
        var state = solid ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        for (int x = wallX; x < wallX + 4; x++) {
            for (int y = 104; y < 140; y++) {
                for (int z = -16; z < 17; z++) {
                    level.setBlock(new BlockPos(x, y, z), state, 3);
                }
            }
        }
        // Isolate rendering from spiral/cache timing: submit detached sections directly to the client engine.
        for (int z = -1; z <= 1; z++) {
            int chunkX = wallX >> 4;
            int chunkZ = z;
            var chunk = level.getChunk(chunkX, chunkZ);
            for (int sectionY = 6; sectionY <= 8; sectionY++) {
                int y = sectionY;
                var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                var copy = new net.minecraft.world.level.chunk.LevelChunkSection(level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME));
                try {
                    chunk.getSection(level.getSectionIndexFromSectionY(y)).write(buffer);
                    copy.read(buffer);
                } finally {
                    buffer.release();
                }
                Minecraft.getInstance().execute(() -> {
                    var clientLevel = Minecraft.getInstance().level;
                    if (clientLevel != null && me.cortex.voxy.commonImpl.VoxyCommon.getInstance() != null) {
                        me.cortex.voxy.common.world.service.VoxelIngestService.rawIngest(
                                me.cortex.voxy.commonImpl.WorldIdentifier.of(clientLevel), copy, chunkX, y, chunkZ,
                                null, new net.minecraft.world.level.chunk.DataLayer(15));
                    }
                });
            }
        }
    }

    public static void tick(Minecraft mc) {
        ++ticks;
        if (ticks == 60) {
            var uuid = mc.player.getUUID();
            mc.getSingleplayerServer().execute(() -> setWall(mc.getSingleplayerServer().getPlayerList().getPlayer(uuid), false));
        }
        if (ticks == 100) {
            measuredPasses = 0;
            samples = 0;
        }
        if (ticks == 140) {
            if (measuredPasses == 0 || samples == 0) throw new AssertionError("Occlusion calibration: unobstructed ghast never produced samples");
            com.mojang.logging.LogUtils.getLogger().info("BOXY_OCCLUSION_VISIBLE: {} samples across {} passes", samples, measuredPasses);
            var uuid = mc.player.getUUID();
            mc.getSingleplayerServer().execute(() -> {
                setWall(mc.getSingleplayerServer().getPlayerList().getPlayer(uuid), true);
                wallReady = true;
            });
        }
        if (ticks == 500) {
            measuredPasses = 0;
            samples = 0;
            joins = 0;
        }
        if (ticks >= 540) {
            try (var image = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                image.writeToFile(new java.io.File(mc.gameDirectory, "boxy-occlusion.png"));
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            com.mojang.logging.LogUtils.getLogger().info("BOXY_OCCLUSION_HIDDEN: {} samples across {} passes; wallReady={}", samples, measuredPasses, wallReady);
            if (!wallReady || measuredPasses == 0 || samples != 0 || joins == 0) throw new AssertionError("Ghast rendered through LOD-only wall: " + samples + " samples, " + joins + " depth joins");
            com.mojang.logging.LogUtils.getLogger().info("BOXY_CLIENT_SMOKE_OK: ghast visible without wall, occluded behind LOD wall");
            mc.stop();
        }
    }
}
