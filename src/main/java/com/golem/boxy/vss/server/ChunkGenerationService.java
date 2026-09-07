package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.VSSConstants;
import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.common.processing.LoadedColumnData;
import com.golem.boxy.vss.common.processing.TickSnapshot.GenerationReadyData;
import com.golem.boxy.vss.config.VSSServerConfig;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * On-demand chunk generation: when a client requests a column the server has never generated, this
 * service adds a chunk ticket to force generation to {@code FULL}, polls each server tick until the
 * chunk is available, serializes it, and releases the ticket. Per-player + global concurrency limited.
 *
 * <p>On Minecraft 1.21.1 we use
 * {@code ServerChunkCache.addRegionTicket}/{@code removeRegionTicket} with a dedicated {@link TicketType}.
 */
public class ChunkGenerationService {
    private static final TicketType<ChunkPos> VSS_GEN_TICKET =
            TicketType.create("boxy_vss_gen", Comparator.comparingLong(ChunkPos::toLong));
    private static final int TICKET_RADIUS = 0; // level 33: FULL without enabling block/entity ticking

    private final LinkedHashMap<PendingGenerationKey, PendingGeneration> active = new LinkedHashMap<>();
    private final Map<UUID, Integer> perPlayerActiveCount = new HashMap<>();
    private final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<ChunkDiskReader.ReadResult>> playerResults = new ConcurrentHashMap<>();
    private final int maxConcurrent;
    private final int maxPerPlayerActive;
    private final int timeoutTicks;
    private volatile long totalSubmitted = 0L;
    private volatile long totalCompleted = 0L;
    private volatile long totalTimeouts = 0L;

    public ChunkGenerationService(VSSServerConfig config) {
        this.maxConcurrent = config.generationConcurrencyLimitGlobal;
        this.maxPerPlayerActive = config.generationConcurrencyLimitPerPlayer;
        this.timeoutTicks = config.generationTimeoutSeconds * 20;
    }

    public boolean submitGeneration(UUID playerUuid, int requestId, ServerLevel level, int cx, int cz, long submissionOrder) {
        if (this.perPlayerActiveCount.getOrDefault(playerUuid, 0) >= this.maxPerPlayerActive) return false;
        PendingGenerationKey key = new PendingGenerationKey(level.dimension(), cx, cz);
        PendingGeneration existing = this.active.get(key);
        if (existing != null) {
            existing.callbacks.add(new GenerationCallback(playerUuid, requestId, submissionOrder));
            incrementCount(this.perPlayerActiveCount, playerUuid);
            return true;
        }
        int playerActive = this.perPlayerActiveCount.getOrDefault(playerUuid, 0);
        if (this.active.size() < this.maxConcurrent && playerActive < this.maxPerPlayerActive) {
            ChunkPos pos = new ChunkPos(cx, cz);
            level.getChunkSource().addRegionTicket(VSS_GEN_TICKET, pos, TICKET_RADIUS, pos);
            PendingGeneration gen = new PendingGeneration(pos, level);
            gen.callbacks.add(new GenerationCallback(playerUuid, requestId, submissionOrder));
            this.active.put(key, gen);
            incrementCount(this.perPlayerActiveCount, playerUuid);
            this.totalSubmitted++;
            return true;
        }
        return false;
    }

    public List<GenerationReadyData> tick() {
        if (this.active.isEmpty()) {
            return List.of();
        }
        List<GenerationReadyData> ready = null;
        Iterator<Map.Entry<PendingGenerationKey, PendingGeneration>> iter = this.active.entrySet().iterator();

        while (iter.hasNext()) {
            PendingGeneration gen = iter.next().getValue();
            gen.ticksWaiting++;
            if (gen.ticksWaiting > this.timeoutTicks) {
                VSSLogger.debug("Generation timeout for chunk " + gen.pos.x + "," + gen.pos.z + " after " + gen.ticksWaiting
                        + " ticks (" + gen.callbacks.size() + " callbacks)");
                for (GenerationCallback cb : gen.callbacks) {
                    this.addResult(cb.playerUuid, ChunkDiskReader.saturatedResult(cb.playerUuid, cb.requestId, gen.pos.x, gen.pos.z, cb.submissionOrder));
                    decrementCount(this.perPlayerActiveCount, cb.playerUuid);
                }
                releaseTicket(gen);
                iter.remove();
                this.totalTimeouts++;
            } else {
                LevelChunk chunk = gen.level.getChunkSource().getChunkNow(gen.pos.x, gen.pos.z);
                if (chunk != null) {
                    try {
                        long columnTimestamp = VSSConstants.epochSeconds();
                        LoadedColumnData columnData = SectionSerializer.serializeColumn(gen.level, chunk, gen.pos.x, gen.pos.z);
                        for (GenerationCallback cb : gen.callbacks) {
                            if (ready == null) {
                                ready = new ArrayList<>();
                            }
                            ready.add(new GenerationReadyData(cb.playerUuid, cb.requestId, columnData, columnTimestamp, cb.submissionOrder, gen.level.dimension().location().toString()));
                            decrementCount(this.perPlayerActiveCount, cb.playerUuid);
                        }
                        this.totalCompleted++;
                    } catch (Exception e) {
                        VSSLogger.error("Failed to extract primitives for generated chunk at " + gen.pos.x + ", " + gen.pos.z, e);
                        for (GenerationCallback cb : gen.callbacks) {
                            this.addResult(cb.playerUuid, ChunkDiskReader.saturatedResult(cb.playerUuid, cb.requestId, gen.pos.x, gen.pos.z, cb.submissionOrder));
                            decrementCount(this.perPlayerActiveCount, cb.playerUuid);
                        }
                    }
                    releaseTicket(gen);
                    iter.remove();
                }
            }
        }

        return ready != null ? ready : List.of();
    }

    private static void releaseTicket(PendingGeneration gen) {
        gen.level.getChunkSource().removeRegionTicket(VSS_GEN_TICKET, gen.pos, TICKET_RADIUS, gen.pos);
    }

    void registerPlayer(UUID playerUuid) {
        this.playerResults.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>());
    }

    void addResult(UUID playerUuid, ChunkDiskReader.ReadResult result) {
        ConcurrentLinkedQueue<ChunkDiskReader.ReadResult> queue = this.playerResults.get(playerUuid);
        if (queue != null) {
            queue.add(result);
        }
    }

    public ConcurrentLinkedQueue<ChunkDiskReader.ReadResult> getPlayerQueue(UUID playerUuid) {
        return this.playerResults.get(playerUuid);
    }

    public void removePlayerResults(UUID playerUuid) {
        this.playerResults.remove(playerUuid);
    }

    public void removePlayer(UUID playerUuid) {
        this.removePlayerResults(playerUuid);
        this.perPlayerActiveCount.remove(playerUuid);
        Iterator<Map.Entry<PendingGenerationKey, PendingGeneration>> iter = this.active.entrySet().iterator();
        while (iter.hasNext()) {
            PendingGeneration gen = iter.next().getValue();
            gen.callbacks.removeIf(cb -> cb.playerUuid.equals(playerUuid));
            if (gen.callbacks.isEmpty()) {
                releaseTicket(gen);
                iter.remove();
            }
        }
    }

    public void shutdown() {
        for (PendingGeneration gen : this.active.values()) {
            releaseTicket(gen);
        }
        this.active.clear();
        this.perPlayerActiveCount.clear();
        this.playerResults.clear();
    }

    public String getDiagnostics() {
        return String.format("submitted=%d, completed=%d, active=%d, timeouts=%d", this.totalSubmitted, this.totalCompleted, this.active.size(), this.totalTimeouts);
    }

    public long getTotalSubmitted() { return this.totalSubmitted; }
    public long getTotalCompleted() { return this.totalCompleted; }
    public long getTotalTimeouts() { return this.totalTimeouts; }

    private static void incrementCount(Map<UUID, Integer> map, UUID uuid) {
        map.merge(uuid, 1, Integer::sum);
    }

    private static void decrementCount(Map<UUID, Integer> map, UUID uuid) {
        Integer count = map.get(uuid);
        if (count != null) {
            if (count <= 1) {
                map.remove(uuid);
            } else {
                map.put(uuid, count - 1);
            }
        }
    }

    record GenerationCallback(UUID playerUuid, int requestId, long submissionOrder) {}

    static class PendingGeneration {
        final ChunkPos pos;
        final ServerLevel level;
        final List<GenerationCallback> callbacks = new ArrayList<>();
        int ticksWaiting = 0;

        PendingGeneration(ChunkPos pos, ServerLevel level) {
            this.pos = pos;
            this.level = level;
        }
    }

    private record PendingGenerationKey(ResourceKey<Level> dimension, int cx, int cz) {}
}
