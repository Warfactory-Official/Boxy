package com.golem.boxy.vss;

import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * Boxy enhancement (no Voxy equivalent): ingest server-side chunks that were generated without the
 * player ever rendering them — most importantly vanilla's spawn-area pre-generation, which runs during
 * world load (before Voxy's client instance exists) over a radius larger than render distance. Voxy's
 * stock hooks only ingest client-rendered or Chunky-requested chunks, so that terrain never became an
 * LOD.
 *
 * <p>A generation-time hook cannot catch the spawn area (Voxy is not initialised yet when it
 * generates), so instead, <b>once when the player joins a world</b>, we walk the currently-loaded full
 * chunks on the server thread and feed unseen ones to Voxy's ingest. This is a short burst, not a
 * perpetual timer: it sweeps roughly once a second and stops as soon as two consecutive sweeps find
 * nothing new (hard-capped at {@link #MAX_SWEEPS}). The repeats exist only because the spawn area may
 * still be finishing generation/lighting in the first second or two — a chunk whose light is not ready
 * yet simply isn't marked seen and is retried on the next sweep. A per-dimension dedup set plus a
 * per-sweep budget keep it cheap and spike-free.
 *
 * <p>This class lives in Boxy's standalone game-layer mod jar (modid "boxy"), so its
 * {@code @Mod.EventBusSubscriber(modid = "boxy")} registers under the matching ModContainer.
 * {@code ChunkMap.getChunks()} is {@code protected}, so chunk enumeration goes through reflection with
 * SRG names (this class is reobfuscated official->SRG by the {@code boxyModJar} build step).
 */
@Mod.EventBusSubscriber(modid = BoxyVss.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BoxyServerIngest {
    private static final Logger LOG = LoggerFactory.getLogger("Boxy/ServerIngest");
    private static final int SWEEP_INTERVAL_TICKS = 20;    // ~1s between sweeps during the burst
    private static final int MAX_SWEEPS = 15;              // hard cap (~15s) so it can't run forever
    private static final int STOP_AFTER_EMPTY = 2;         // stop once this many sweeps in a row add nothing
    private static final int WAIT_BUDGET_TICKS = 30 * 20;  // give Voxy up to ~30s to come up after join
    private static final int MAX_INGESTS_PER_SWEEP = 512;  // spread large backlogs over a few sweeps

    private static final Map<ResourceKey<Level>, LongSet> SEEN = new HashMap<>();
    private static int sweepsLeft;
    private static int emptyStreak;
    private static int waitTicksLeft;
    private static int ticksSinceSweep;

    private static boolean reflectionFailed;
    private static boolean sweepWarned;
    private static Field chunkMapField;    // ServerChunkCache.chunkMap  (f_8325_)
    private static Method getChunksMethod; // ChunkMap.getChunks()       (m_140416_)
    private static Method getFullChunk;    // ChunkHolder.getFullChunk() (m_212234_)

    private BoxyServerIngest() {}

    /** Arm a fresh burst when the player loads into a world. */
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        sweepsLeft = MAX_SWEEPS;
        emptyStreak = 0;
        waitTicksLeft = WAIT_BUDGET_TICKS;
        ticksSinceSweep = 0;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || sweepsLeft <= 0 || reflectionFailed) {
            return;
        }
        // Wait (without consuming a sweep) until Voxy is actually up, then sweep on an interval.
        if (VoxyCommon.getInstance() == null) {
            if ((waitTicksLeft -= 1) <= 0) {
                sweepsLeft = 0; // Voxy never came up (e.g. dedicated server) — give up.
            }
            return;
        }
        if (++ticksSinceSweep < SWEEP_INTERVAL_TICKS) {
            return;
        }
        ticksSinceSweep = 0;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !ensureReflection()) {
            return;
        }
        int budget = MAX_INGESTS_PER_SWEEP;
        for (ServerLevel level : server.getAllLevels()) {
            budget = sweep(level, budget);
            if (budget <= 0) {
                break;
            }
        }
        int ingestedThisSweep = MAX_INGESTS_PER_SWEEP - budget;
        sweepsLeft--;
        emptyStreak = ingestedThisSweep == 0 ? emptyStreak + 1 : 0;
        if (emptyStreak >= STOP_AFTER_EMPTY) {
            sweepsLeft = 0; // converged — nothing new is loading; stop until the next world join.
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SEEN.clear();
        sweepsLeft = 0;
    }

    private static int sweep(ServerLevel level, int budget) {
        LongSet seen = SEEN.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet());
        try {
            ServerChunkCache cache = level.getChunkSource();
            Object map = chunkMapField.get(cache);
            Iterable<?> holders = (Iterable<?>) getChunksMethod.invoke(map);
            for (Object holder : holders) {
                if (budget <= 0) {
                    break;
                }
                Object full = getFullChunk.invoke(holder);
                if (!(full instanceof LevelChunk chunk)) {
                    continue;
                }
                long key = chunk.getPos().toLong();
                if (seen.contains(key)) {
                    continue;
                }
                // Only mark seen once Voxy accepts it — chunks still mid-lighting return false and are
                // retried on a later sweep in the burst.
                if (VoxelIngestService.tryAutoIngestChunk(chunk)) {
                    seen.add(key);
                    budget--;
                }
            }
        } catch (Throwable t) {
            if (!sweepWarned) {
                sweepWarned = true;
                LOG.warn("server-side chunk sweep failed", t);
            }
        }
        return budget;
    }

    private static boolean ensureReflection() {
        if (getFullChunk != null) {
            return true;
        }
        try {
            chunkMapField = ServerChunkCache.class.getDeclaredField("f_8325_");
            chunkMapField.setAccessible(true);
            getChunksMethod = Class.forName("net.minecraft.server.level.ChunkMap").getDeclaredMethod("m_140416_");
            getChunksMethod.setAccessible(true);
            getFullChunk = Class.forName("net.minecraft.server.level.ChunkHolder").getDeclaredMethod("m_212234_");
            getFullChunk.setAccessible(true);
            return true;
        } catch (Throwable t) {
            reflectionFailed = true;
            LOG.warn("could not resolve chunk-enumeration reflection; server-side spawn ingest disabled", t);
            return false;
        }
    }
}
