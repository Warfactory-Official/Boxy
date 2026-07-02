package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.VSSConstants;
import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.common.processing.AbstractChunkDiskReader;
import com.golem.boxy.vss.common.processing.ReadResultAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * Off-thread chunk NBT reader: reads already-generated chunks straight from the region files (never
 * loading them into the live world) on a small thread pool, and serializes them via
 * {@link NbtSectionSerializer}. {@code ServerChunkCache.chunkMap} is public in 1.20.1, so no accessor is
 * needed for it; the protected {@code read(ChunkPos)} is reached through {@code AccessorChunkMap}.
 */
public class ChunkDiskReader extends AbstractChunkDiskReader<ChunkDiskReader.ReadResult> {

    static ReadResult emptyResult(UUID playerUuid, int requestId, int chunkX, int chunkZ, long submissionOrder) {
        return new ReadResult(playerUuid, requestId, chunkX, chunkZ, null, null, 0, 0L, true, false, submissionOrder);
    }

    static ReadResult saturatedResult(UUID playerUuid, int requestId, int chunkX, int chunkZ, long submissionOrder) {
        return new ReadResult(playerUuid, requestId, chunkX, chunkZ, null, null, 0, 0L, false, true, submissionOrder);
    }

    public ChunkDiskReader(int threadCount) {
        super(threadCount);
    }

    public void submitReadDirect(UUID playerUuid, int requestId, ServerLevel level, int chunkX, int chunkZ, long submissionOrder) {
        if (this.isShutdown()) {
            return;
        }
        this.diag.recordSubmitted();
        ResourceKey<Level> dimension = level.dimension();
        RegistryAccess registryAccess = level.registryAccess();
        ChunkMap chunkMap = level.getChunkSource().chunkMap;

        try {
            this.executor.submit(() -> {
                if (!this.isShutdown()) {
                    try {
                        this.readChunkNbtAndSerialize(playerUuid, requestId, chunkMap, chunkX, chunkZ, dimension, registryAccess, submissionOrder);
                    } catch (Exception e) {
                        VSSLogger.error("Failed to read chunk from disk at " + chunkX + ", " + chunkZ, e);
                        this.diag.recordError();
                        this.diag.recordCompleted(0L);
                        this.addResult(playerUuid, emptyResult(playerUuid, requestId, chunkX, chunkZ, submissionOrder));
                    }
                }
            });
        } catch (RejectedExecutionException rejected) {
            if (VSSLogger.isDebugEnabled()) {
                VSSLogger.debug("Disk reader executor saturated, returning rate-limited for " + chunkX + "," + chunkZ);
            }
            this.diag.recordSaturation();
            this.diag.recordCompleted(0L);
            this.addResult(playerUuid, saturatedResult(playerUuid, requestId, chunkX, chunkZ, submissionOrder));
        }
    }

    private void readChunkNbtAndSerialize(
            UUID playerUuid, int requestId, ChunkMap chunkMap, int chunkX, int chunkZ,
            ResourceKey<Level> dimension, RegistryAccess registryAccess, long submissionOrder) {
        if (this.isShutdown()) {
            return;
        }
        long startNs = System.nanoTime();
        byte[] serializedSections;
        try {
            serializedSections = NbtSectionSerializer.readAndSerializeSections(chunkMap, registryAccess, chunkX, chunkZ);
        } catch (Exception e) {
            VSSLogger.error("Failed to read chunk NBT from disk at " + chunkX + ", " + chunkZ, e);
            this.diag.recordError();
            this.diag.recordCompleted(System.nanoTime() - startNs);
            this.addResult(playerUuid, emptyResult(playerUuid, requestId, chunkX, chunkZ, submissionOrder));
            return;
        }

        if (serializedSections == null) {
            this.diag.recordEmpty();
            this.diag.recordCompleted(System.nanoTime() - startNs);
            this.addResult(playerUuid, emptyResult(playerUuid, requestId, chunkX, chunkZ, submissionOrder));
        } else if (serializedSections.length == 0) {
            long columnTimestamp = VSSConstants.epochSeconds();
            String dimensionStr = dimension.location().toString();
            this.diag.recordEmpty();
            this.diag.recordCompleted(System.nanoTime() - startNs);
            this.addResult(playerUuid, new ReadResult(playerUuid, requestId, chunkX, chunkZ, null, dimensionStr, 0, columnTimestamp, false, false, submissionOrder));
        } else {
            long columnTimestamp = VSSConstants.epochSeconds();
            String dimensionStr = dimension.location().toString();
            int estimatedBytes = serializedSections.length + VSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
            this.diag.recordCompleted(System.nanoTime() - startNs);
            this.addResult(playerUuid, new ReadResult(playerUuid, requestId, chunkX, chunkZ, serializedSections, dimensionStr, estimatedBytes, columnTimestamp, false, false, submissionOrder));
        }
    }

    public record ReadResult(
            UUID playerUuid,
            int requestId,
            int chunkX,
            int chunkZ,
            byte[] sectionBytes,
            String dimension,
            int estimatedBytes,
            long columnTimestamp,
            boolean notFound,
            boolean saturated,
            long submissionOrder) implements ReadResultAccess {
    }
}
