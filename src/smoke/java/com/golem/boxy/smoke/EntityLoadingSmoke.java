package com.golem.boxy.smoke;

import com.golem.boxy.vss.server.DiscoveredEntities;
import com.golem.boxy.vss.server.FrozenChunkLoader;

import com.golem.boxy.vss.config.VSSServerConfig;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.UUID;

/** Exercises real chunk entity unload/reload without a graphical client or a network connection. */
public final class EntityLoadingSmoke {
    private static final ChunkPos TARGET = new ChunkPos(40, 0);
    private final MinecraftServer server;
    private final ServerPlayer player;
    private UUID entityId;
    private int ticks;
    private int phase;
    private int overlapTick;
    private ServerPlayer secondPlayer;

    private EntityLoadingSmoke(MinecraftServer server) {
        this.server = server;
        var level = server.overworld();
        player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), "EntityLoadSmoke"),
                ClientInformation.createDefault());
        player.setPos(0.5, 120, 0.5);
        var cfg = VSSServerConfig.CONFIG;
        cfg.forceLoadTrackedEntities = true;
        cfg.extendEntityTracking = true;
        cfg.entityTrackingDistanceChunks = 64;
        cfg.forceLoadRadiusChunks = 1;
        level.getChunkSource().addRegionTicket(TicketType.FORCED, TARGET, 0, TARGET);
        level.getChunk(TARGET.x, TARGET.z);
    }

    public static void start(MinecraftServer server) {
        DiscoveredEntitiesChecks.run();
        var test = new EntityLoadingSmoke(server);
        NeoForge.EVENT_BUS.addListener(test::tick);
    }

    private void tick(ServerTickEvent.Post event) {
        if (event.getServer() != server) return;
        if (++ticks > 1200) throw new AssertionError("Entity loading timed out in phase " + phase);
        var level = server.overworld();
        var cache = DiscoveredEntities.get(level);
        var cfg = VSSServerConfig.CONFIG;
        if (phase == 0 && level.areEntitiesLoaded(TARGET.toLong())) {
            var pig = EntityType.PIG.create(level);
            if (pig == null) throw new AssertionError("Cannot create force-loading fixture");
            pig.moveTo(640.5, 120, 0.5);
            pig.setNoAi(true);
            pig.setNoGravity(true);
            pig.setPersistenceRequired();
            entityId = pig.getUUID();
            if (!level.addFreshEntity(pig)) throw new AssertionError("Cannot add fixture");
            if (!cache.desiredChunks(player.getX(), player.getZ(), 64, 1).contains(TARGET.toLong())) {
                throw new AssertionError("Entity discovery did not create a distant window");
            }
            if (!DiscoveredEntities.get(server.getLevel(net.minecraft.world.level.Level.NETHER))
                    .desiredChunks(0.5, 0.5, 64, 1).isEmpty()) {
                throw new AssertionError("Discovery leaked across dimensions");
            }
            level.getChunkSource().removeRegionTicket(TicketType.FORCED, TARGET, 0, TARGET);
            phase = 1;
        } else if (phase == 1 && level.getEntity(entityId) == null && !level.areEntitiesLoaded(TARGET.toLong())) {
            if (!cache.desiredChunks(0.5, 0.5, 64, 1).contains(TARGET.toLong())) {
                throw new AssertionError("Chunk unload erased discovery");
            }
            FrozenChunkLoader.updatePlayer(player, cfg);
            phase = 2;
        } else if (phase == 2 && level.getEntity(entityId) != null) {
            if (level.isPositionEntityTicking(level.getEntity(entityId).blockPosition())) {
                throw new AssertionError("Entity window unexpectedly ticks");
            }
            if (level.getChunkSource().getChunkNow(41, 1) == null) return;
            var entity = level.getEntity(entityId);
            entity.setPos(656.5, 120, 0.5);
            if (!cache.desiredChunks(0.5, 0.5, 64, 1).contains(ChunkPos.asLong(42, 0))) {
                throw new AssertionError("Entity movement did not move discovery window");
            }
            entity.setPos(640.5, 120, 0.5);
            secondPlayer = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), "EntityLoadOverlap"),
                    ClientInformation.createDefault());
            secondPlayer.setPos(0.5, 120, 0.5);
            FrozenChunkLoader.updatePlayer(secondPlayer, cfg);
            FrozenChunkLoader.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
            overlapTick = ticks;
            phase = 6;
        } else if (phase == 6 && ticks - overlapTick >= 40) {
            if (level.getEntity(entityId) == null) throw new AssertionError("Logout removed another player's tickets");
            FrozenChunkLoader.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(secondPlayer));
            player.setPos(-2048, 120, 0.5);
            FrozenChunkLoader.updatePlayer(player, cfg);
            phase = 3;
        } else if (phase == 3 && level.getEntity(entityId) == null && !level.areEntitiesLoaded(TARGET.toLong())) {
            player.setPos(0.5, 120, 0.5);
            FrozenChunkLoader.updatePlayer(player, cfg);
            phase = 4;
        } else if (phase == 4 && level.getEntity(entityId) != null) {
            level.getEntity(entityId).discard();
            if (cache.save(new net.minecraft.nbt.CompoundTag(), level.registryAccess()).getList("entities", 10)
                    .stream().anyMatch(tag -> ((net.minecraft.nbt.CompoundTag) tag).getUUID("uuid").equals(entityId))) {
                throw new AssertionError("Destroyed entity remained cached");
            }
            FrozenChunkLoader.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
            LogUtils.getLogger().info("BOXY_ENTITY_LOADING_SMOKE_OK: discovery, dimension isolation, unload retention, distant reload, entity-centered radius, movement, non-ticking, overlapping players/logout, range exit/reentry, destruction");
            server.halt(false);
            phase = 5;
        }
    }
}
