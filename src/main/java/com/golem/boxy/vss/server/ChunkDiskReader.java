package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.PositionUtil;
import com.golem.boxy.vss.common.VSSConstants;
import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.common.processing.AbstractChunkDiskReader;
import com.golem.boxy.vss.common.processing.ReadResultAccess;
import com.golem.boxy.vss.common.voxel.SerializedColumnCache;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;

/** Region reads without loading chunks. Every worker captures the result queue of its original session. */
public class ChunkDiskReader extends AbstractChunkDiskReader<ChunkDiskReader.ReadResult> {
    static ReadResult emptyResult(UUID player, int id, int x, int z, long order) {
        return new ReadResult(player, id, x, z, null, null, 0, 0, true, false, order);
    }

    static ReadResult saturatedResult(UUID player, int id, int x, int z, long order) {
        return new ReadResult(player, id, x, z, null, null, 0, 0, false, true, order);
    }

    static ReadResult liveResult(UUID player, int id, int x, int z, String dimension,
            byte[] bytes, long timestamp, long order) {
        int size = bytes == null ? 0 : bytes.length + VSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
        return new ReadResult(player, id, x, z, bytes, dimension, size, timestamp, false, false, order);
    }

    private final SerializedColumnCache bytesCache;
    private record FreshKey(UUID player, int requestId) {}
    private final java.util.concurrent.ConcurrentHashMap<FreshKey, Object> freshReads = new java.util.concurrent.ConcurrentHashMap<>();

    void cancelFresh(UUID player, int requestId) {
        if (requestId == -1) freshReads.keySet().removeIf(key -> key.player().equals(player));
        else freshReads.remove(new FreshKey(player, requestId));
    }
    private java.util.function.BiConsumer<ServerLevel, com.golem.boxy.vss.common.processing.OffThreadProcessor.LiveSerializeRequest> dirtyReload;

    void setDirtyReload(java.util.function.BiConsumer<ServerLevel, com.golem.boxy.vss.common.processing.OffThreadProcessor.LiveSerializeRequest> reload) {
        this.dirtyReload = reload;
    }

    public ChunkDiskReader(int threadCount, SerializedColumnCache bytesCache) {
        super(threadCount);
        this.bytesCache = bytesCache;
    }

    public void submitReadDirect(UUID player, int id, ServerLevel level, int x, int z, long order, boolean forceFresh) {
        if (isShutdown()) return;
        ConcurrentLinkedQueue<ReadResult> results = getPlayerQueue(player);
        if (results == null) return;
        diag.recordSubmitted();
        RegistryAccess registries = level.registryAccess();
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        String dimension = level.dimension().location().toString();
        Object token = new Object();
        FreshKey key = new FreshKey(player, id);
        if (forceFresh) freshReads.put(key, token);
        try {
            executor.submit(() -> readAndSerialize(player, id, chunkMap, registries, dimension, x, z, order, results, forceFresh, level, token));
        } catch (RejectedExecutionException rejected) {
            freshReads.remove(key, token);
            diag.recordSaturation();
            diag.recordCompleted(0);
            results.add(saturatedResult(player, id, x, z, order));
        }
    }

    private void readAndSerialize(UUID player, int id, ChunkMap map, RegistryAccess registries,
            String dimension, int x, int z, long order, ConcurrentLinkedQueue<ReadResult> results, boolean forceFresh, ServerLevel level, Object token) {
        if (isShutdown()) return;
        long start = System.nanoTime();
        boolean scheduledReload = false;
        try {
            long position = PositionUtil.packPosition(x, z);
            byte[] bytes = forceFresh ? null : bytesCache.get(dimension, position);
            if (bytes == null) {
                bytes = NbtSectionSerializer.readAndSerializeSections(map, registries, x, z);
                if (bytes != null && !forceFresh) bytesCache.put(dimension, position, bytes);
            }
            if (bytes == null) {
                diag.recordEmpty();
                results.add(forceFresh ? saturatedResult(player, id, x, z, order) : emptyResult(player, id, x, z, order));
            } else if (forceFresh) {
                // The disk copy proves existence, not freshness. Reacquire FULL and snapshot live terrain.
                var request = new com.golem.boxy.vss.common.processing.OffThreadProcessor.LiveSerializeRequest(player, id, dimension, x, z, order);
                level.getServer().execute(() -> {
                    boolean active = freshReads.remove(new FreshKey(player, id), token);
                    if (!isShutdown() && getPlayerQueue(player) == results) {
                        if (active) dirtyReload.accept(level, request);
                        else results.add(saturatedResult(player, id, x, z, order));
                    }
                });
                scheduledReload = true;
            } else {
                if (bytes.length == 0) diag.recordEmpty();
                results.add(liveResult(player, id, x, z, dimension, bytes, VSSConstants.epochSeconds(), order));
            }
        } catch (Exception e) {
            diag.recordError();
            VSSLogger.error("Failed to read chunk from disk at " + x + ", " + z, e);
            results.add(saturatedResult(player, id, x, z, order));
        } finally {
            if (!scheduledReload) freshReads.remove(new FreshKey(player, id), token);
            diag.recordCompleted(System.nanoTime() - start);
        }
    }

    public record ReadResult(UUID playerUuid, int requestId, int chunkX, int chunkZ, byte[] sectionBytes,
            String dimension, int estimatedBytes, long columnTimestamp, boolean notFound, boolean saturated,
            long submissionOrder) implements ReadResultAccess {}
}
