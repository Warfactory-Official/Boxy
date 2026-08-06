package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.processing.OffThreadProcessor;
import com.golem.boxy.vss.common.voxel.SerializedColumnCache;
import com.golem.boxy.vss.payloads.VoxelColumnS2CPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Forge implementation of the platform-agnostic {@link OffThreadProcessor}: routes incoming requests to
 * the disk reader / generation service and turns finished reads into {@link VoxelColumnS2CPayload}s queued
 * per player. (Port of VSS's {@code FabricOffThreadProcessor}.)
 */
public class ForgeOffThreadProcessor extends OffThreadProcessor<PlayerRequestState, ChunkDiskReader.ReadResult> {
    private final ChunkDiskReader diskReader;
    private final ChunkGenerationService generationService;
    private final ConcurrentHashMap<String, ServerLevel> dimensionLevelMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ResourceKey<Level>> dimensionKeyCache = new ConcurrentHashMap<>();

    public ForgeOffThreadProcessor(
            Map<UUID, PlayerRequestState> players,
            ChunkDiskReader diskReader,
            ChunkGenerationService generationService,
            Path dataDir,
            int perDimensionTimestampCacheSizeMB,
            SerializedColumnCache bytesCache) {
        super(players, diskReader != null, generationService != null, dataDir, perDimensionTimestampCacheSizeMB, bytesCache);
        this.diskReader = diskReader;
        this.generationService = generationService;
    }

    public void updateDimensionContext(String dimension, ServerLevel level) {
        this.dimensionLevelMap.putIfAbsent(dimension, level);
    }

    @Override
    protected ChunkDiskReader.ReadResult pollDiskResult(PlayerRequestState state) {
        if (this.diskReader == null) {
            return null;
        }
        ConcurrentLinkedQueue<ChunkDiskReader.ReadResult> queue = this.diskReader.getPlayerQueue(state.getPlayerUUID());
        return queue == null ? null : queue.poll();
    }

    @Override
    protected ChunkDiskReader.ReadResult pollGenerationResult(PlayerRequestState state) {
        if (this.generationService == null) {
            return null;
        }
        ConcurrentLinkedQueue<ChunkDiskReader.ReadResult> queue = this.generationService.getPlayerQueue(state.getPlayerUUID());
        return queue == null ? null : queue.poll();
    }

    @Override
    protected void enqueueResultPayloads(PlayerRequestState state, ChunkDiskReader.ReadResult result) {
        if (result.sectionBytes() != null) {
            this.buildAndEnqueueColumnPayload(
                    state, result.chunkX(), result.chunkZ(), result.dimension(), result.requestId(),
                    result.columnTimestamp(), result.submissionOrder(), result.sectionBytes(), result.estimatedBytes());
        }
    }

    @Override
    protected void submitDiskRead(UUID playerUuid, int requestId, String dimension, int cx, int cz, long submissionOrder) {
        if (this.diskReader != null) {
            ServerLevel level = this.dimensionLevelMap.get(dimension);
            if (level != null) {
                this.diskReader.submitReadDirect(playerUuid, requestId, level, cx, cz, submissionOrder);
            }
        }
    }

    @Override
    protected void buildAndEnqueueColumnPayload(
            PlayerRequestState state, int cx, int cz, String dimension, int requestId,
            long columnTimestamp, long submissionOrder, byte[] sectionBytes, int estimatedBytes) {
        ResourceKey<Level> dimensionKey = this.dimensionKeyCache
                .computeIfAbsent(dimension, d -> ResourceKey.create(Registries.DIMENSION, new ResourceLocation(d)));
        VoxelColumnS2CPayload payload = new VoxelColumnS2CPayload(requestId, cx, cz, dimensionKey, columnTimestamp, sectionBytes);
        state.addReadyPayload(new PlayerRequestState.QueuedPayload(payload, requestId, estimatedBytes, submissionOrder));
    }

    @Override
    public void shutdown() {
        super.shutdown();
        this.dimensionLevelMap.clear();
    }
}
