package com.golem.boxy.vss.server;

import com.golem.boxy.vss.config.VSSServerConfig;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;
import com.golem.boxy.vss.common.TrackedEntityTypes;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

/** Activates non-ticking chunk windows around discovered entities within a player's tracking range. */
@EventBusSubscriber(modid = "boxy")
public final class FrozenChunkLoader {
    private static final TicketType<UUID> TICKET =
            TicketType.create("boxy_entity_load", Comparator.<UUID>naturalOrder());
    /** addRegionTicket radius 0 ⇒ ticket level 33 (FULL, accessible, non-ticking). */
    private static final int LOAD_ONLY_RADIUS = 0;

    private static final Map<UUID, PlayerLoad> LOADS = new HashMap<>();

    private FrozenChunkLoader() {}

    private static final class PlayerLoad {
        final UUID owner;
        ServerLevel level;
        double centerCx = Double.NaN;
        double centerCz = Double.NaN;
        int radius = -1;
        int distance = -1;
        long revision = -1;
        List<String> types = List.of();
        final LongSet forced = new LongOpenHashSet();

        PlayerLoad(UUID owner) {
            this.owner = owner;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        VSSServerConfig cfg = VSSServerConfig.CONFIG;
        MinecraftServer server = event.getServer();
        boolean typesChanged = TrackedEntityTypes.refreshServerTypes();
        for (ServerLevel level : server.getAllLevels()) {
            // Also discover already-loaded entities after a whitelist change.
            if (typesChanged || server.getTickCount() % 20 == 0) {
                for (Entity entity : level.getAllEntities()) DiscoveredEntities.get(level).observe(entity);
            }
            DiscoveredEntities.get(level).refresh(level);
        }
        if (!cfg.forceLoadTrackedEntities || !cfg.extendEntityTracking) {
            if (!LOADS.isEmpty()) {
                releaseAll();
            }
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ServerNetworking.hasEntitySession(player)) {
                updatePlayer(player, cfg);
            } else {
                PlayerLoad load = LOADS.remove(player.getUUID());
                if (load != null) releaseLoad(load);
            }
        }
    }

    public static void updatePlayer(ServerPlayer player, VSSServerConfig cfg) {
        ServerLevel level = player.serverLevel();
        DiscoveredEntities cache = DiscoveredEntities.get(level);
        int radius = cfg.forceLoadRadiusChunks;
        double cx = player.getX();
        double cz = player.getZ();

        PlayerLoad load = LOADS.computeIfAbsent(player.getUUID(), PlayerLoad::new);
        if (load.level != level) {
            releaseLoad(load);
            load.level = level;
            load.centerCx = Double.NaN;
        } else if (cx == load.centerCx && cz == load.centerCz && radius == load.radius
                && load.distance == cfg.entityTrackingDistanceChunks && load.revision == cache.revision()
                && load.types.equals(cfg.trackedEntityTypes)) {
            return;
        }
        load.centerCx = cx;
        load.centerCz = cz;
        load.radius = radius;
        load.distance = cfg.entityTrackingDistanceChunks;
        load.revision = cache.revision();
        load.types = new ArrayList<>(cfg.trackedEntityTypes);

        LongSet desired = cache.desiredChunks(cx, cz, load.distance, radius);

        for (long pos : desired) {
            if (load.forced.add(pos)) {
                ChunkPos cp = new ChunkPos(pos);
                level.getChunkSource().addRegionTicket(TICKET, cp, LOAD_ONLY_RADIUS, load.owner);
            }
        }
        LongIterator it = load.forced.iterator();
        while (it.hasNext()) {
            long pos = it.nextLong();
            if (!desired.contains(pos)) {
                ChunkPos cp = new ChunkPos(pos);
                level.getChunkSource().removeRegionTicket(TICKET, cp, LOAD_ONLY_RADIUS, load.owner);
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level) DiscoveredEntities.get(level).observe(event.getEntity());
    }

    @SubscribeEvent
    public static void onEntitySection(EntityEvent.EnteringSection event) {
        Entity entity = event.getEntity();
        if (event.didChunkChange() && entity.isAddedToLevel() && !entity.isRemoved()
                && entity.level() instanceof ServerLevel level
                && !(entity instanceof Player) && TrackedEntityTypes.serverContains(entity.getType())) {
            DiscoveredEntities.get(level).remember(entity.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
                    ChunkPos.asLong(event.getNewPos().x(), event.getNewPos().z()));
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getEntity();
        Entity.RemovalReason reason = entity.getRemovalReason();
        DiscoveredEntities cache = DiscoveredEntities.get(level);
        if (reason != null && (reason.shouldDestroy() || reason == Entity.RemovalReason.CHANGED_DIMENSION)) {
            cache.forget(entity.getUUID());
        } else if (!(entity instanceof Player)
                && TrackedEntityTypes.serverContains(entity.getType())) {
            cache.remember(entity.getUUID(), BuiltInRegistries.ENTITY_TYPE
                    .getKey(entity.getType()), entity.chunkPosition().toLong());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerLoad load = LOADS.remove(player.getUUID());
            if (load != null) {
                releaseLoad(load);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        releaseAll();
    }

    private static void releaseAll() {
        for (PlayerLoad load : LOADS.values()) {
            releaseLoad(load);
        }
        LOADS.clear();
    }

    private static void releaseLoad(PlayerLoad load) {
        if (load.level != null) {
            LongIterator it = load.forced.iterator();
            while (it.hasNext()) {
                ChunkPos cp = new ChunkPos(it.nextLong());
                load.level.getChunkSource().removeRegionTicket(TICKET, cp, LOAD_ONLY_RADIUS, load.owner);
            }
        }
        load.forced.clear();
    }
}
