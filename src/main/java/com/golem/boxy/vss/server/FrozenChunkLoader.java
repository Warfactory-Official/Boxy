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
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Optional, opt-in: keeps a sliding window of <b>load-only (non-ticking)</b> chunks around each player so
 * the configured entity types in otherwise-unloaded terrain still load and render at distance (via
 * {@link MixinChunkMapTrackedEntity} + the client cull mixin). Without this, distant entities only show in
 * naturally-loaded areas (players, spawn chunks, Chunky/forceload).
 *
 * <p>Uses a custom (in-memory, non-persisted) {@link TicketType} via
 * {@code addRegionTicket(type, pos, 0, pos)} — radius 0 ⇒ chunk level 33 = {@code FullChunkStatus.FULL}:
 * entities load and become {@code Visibility.TRACKED} (sent to clients) but do <b>not</b> tick (no AI,
 * movement, or mob spawning). Chunks already loaded by the player's own (ticking) tickets keep their lower
 * level — our ticket never downgrades them — so only the ring beyond view distance is frozen.
 *
 * <p><b>Cost:</b> loads (and <i>generates</i> if ungenerated) the whole {@code forceLoadRadiusChunks}
 * square per player — memory + disk I/O + one-time worldgen, scaling radius² × players. Default off,
 * radius hard-capped. Entities here are frozen (static).
 */
@Mod.EventBusSubscriber(modid = "boxy", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FrozenChunkLoader {
    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("boxy_entity_load", Comparator.comparingLong(ChunkPos::toLong));
    /** addRegionTicket radius 0 ⇒ ticket level 33 (FULL, accessible, non-ticking). */
    private static final int LOAD_ONLY_RADIUS = 0;

    private static final Map<UUID, PlayerLoad> LOADS = new HashMap<>();

    private FrozenChunkLoader() {}

    private static final class PlayerLoad {
        ServerLevel level;
        int centerCx = Integer.MAX_VALUE;
        int centerCz = Integer.MAX_VALUE;
        final LongSet forced = new LongOpenHashSet();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        VSSServerConfig cfg = VSSServerConfig.CONFIG;
        if (!cfg.forceLoadTrackedEntities) {
            if (!LOADS.isEmpty()) {
                releaseAll();
            }
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            updatePlayer(player, cfg.forceLoadRadiusChunks);
        }
    }

    private static void updatePlayer(ServerPlayer player, int radius) {
        ServerLevel level = player.serverLevel();
        int cx = player.getBlockX() >> 4;
        int cz = player.getBlockZ() >> 4;

        PlayerLoad load = LOADS.computeIfAbsent(player.getUUID(), u -> new PlayerLoad());
        if (load.level != level) {
            releaseLoad(load);
            load.level = level;
            load.centerCx = Integer.MAX_VALUE; // force a full rebuild after a dimension change
        } else if (cx == load.centerCx && cz == load.centerCz) {
            return; // player hasn't crossed a chunk boundary — nothing to do
        }
        load.centerCx = cx;
        load.centerCz = cz;

        LongSet desired = new LongOpenHashSet((2 * radius + 1) * (2 * radius + 1));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                desired.add(ChunkPos.asLong(cx + dx, cz + dz));
            }
        }

        for (long pos : desired) {
            if (load.forced.add(pos)) {
                ChunkPos cp = new ChunkPos(pos);
                level.getChunkSource().addRegionTicket(TICKET, cp, LOAD_ONLY_RADIUS, cp);
            }
        }
        LongIterator it = load.forced.iterator();
        while (it.hasNext()) {
            long pos = it.nextLong();
            if (!desired.contains(pos)) {
                ChunkPos cp = new ChunkPos(pos);
                level.getChunkSource().removeRegionTicket(TICKET, cp, LOAD_ONLY_RADIUS, cp);
                it.remove();
            }
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
                load.level.getChunkSource().removeRegionTicket(TICKET, cp, LOAD_ONLY_RADIUS, cp);
            }
        }
        load.forced.clear();
    }
}
