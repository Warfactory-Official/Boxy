package com.golem.boxy.vss.client;

import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.config.VSSClientConfig;
import com.golem.boxy.vss.payloads.VoxelColumnS2CPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Buffers received {@link VoxelColumnS2CPayload}s and deserializes them (optionally off the client thread)
 * into {@link VoxelColumnData}, then hands them to {@link VoxyClientBridge}. The deserialization mirrors the
 * server serializers: {@code varInt count; per section { byte Y; LevelChunkSection.read; bool+2048 blockLight;
 * bool+2048 skyLight }}.
 */
class ClientColumnProcessor {
    static final int MAX_QUEUED_COLUMNS = 8000;
    private static final long DROP_WARN_INTERVAL_MS = 5000L;
    private static final int MAX_SECTIONS_PER_COLUMN = 64;

    private final ConcurrentLinkedQueue<QueuedColumn> columnQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();
    private final AtomicLong columnsDropped = new AtomicLong();
    private volatile long lastDropWarnMs = 0L;
    private volatile ExecutorService executor = createExecutor();
    private final AtomicBoolean processing = new AtomicBoolean();
    private volatile boolean shuttingDown;

    ClientColumnProcessor() {}

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Boxy-VSS-ColumnProcessor");
            t.setDaemon(true);
            return t;
        });
    }

    void offer(VoxelColumnS2CPayload payload, boolean isUpdate) {
        if (this.shuttingDown) {
            return;
        }
        if (this.queueSize.get() < MAX_QUEUED_COLUMNS) {
            this.columnQueue.add(new QueuedColumn(payload, isUpdate));
            this.queueSize.incrementAndGet();
        } else {
            long dropped = this.columnsDropped.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - this.lastDropWarnMs > DROP_WARN_INTERVAL_MS) {
                this.lastDropWarnMs = now;
                VSSLogger.warn("Column processing queue full (" + MAX_QUEUED_COLUMNS + "), " + dropped + " columns dropped total");
            }
        }
    }

    void scheduleProcessing(boolean serverEnabled) {
        if (this.shuttingDown) {
            return;
        }
        if (!serverEnabled || !VSSClientConfig.CONFIG.receiveServerLods || !VoxyClientBridge.isAvailable()) {
            this.columnQueue.clear();
            this.queueSize.set(0);
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            this.columnQueue.clear();
            this.queueSize.set(0);
            return;
        }
        if (this.columnQueue.isEmpty()) {
            return;
        }
        if (VSSClientConfig.CONFIG.offThreadSectionProcessing) {
            if (this.processing.compareAndSet(false, true)) {
                try {
                    this.executor.execute(() -> {
                        try {
                            this.drainColumnQueue(level);
                        } finally {
                            this.processing.set(false);
                        }
                    });
                } catch (Exception e) {
                    this.processing.set(false);
                }
            }
        } else {
            this.drainColumnQueue(level);
        }
    }

    private void drainColumnQueue(ClientLevel level) {
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        QueuedColumn queued;
        while (!Thread.currentThread().isInterrupted() && (queued = this.columnQueue.poll()) != null) {
            this.queueSize.decrementAndGet();
            VoxelColumnS2CPayload payload = queued.payload();
            if (!level.dimension().equals(payload.dimension())) {
                continue;
            }
            byte[] decompressed = payload.decompressedSections();
            if (decompressed == null || decompressed.length == 0) {
                continue;
            }
            try {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(decompressed));
                try {
                    int sectionCount = Math.max(0, Math.min(buf.readVarInt(), MAX_SECTIONS_PER_COLUMN));
                    VoxelColumnData.SectionData[] sectionDatas = new VoxelColumnData.SectionData[sectionCount];
                    for (int i = 0; i < sectionCount; i++) {
                        int sectionY = buf.readByte();
                        LevelChunkSection section = new LevelChunkSection(biomeRegistry);
                        section.read(buf);
                        DataLayer blockLight = null;
                        if (buf.readBoolean()) {
                            byte[] lightBytes = new byte[2048];
                            buf.readBytes(lightBytes);
                            blockLight = new DataLayer(lightBytes);
                        }
                        DataLayer skyLight = null;
                        if (buf.readBoolean()) {
                            byte[] lightBytes = new byte[2048];
                            buf.readBytes(lightBytes);
                            skyLight = new DataLayer(lightBytes);
                        }
                        sectionDatas[i] = new VoxelColumnData.SectionData(sectionY, section, blockLight, skyLight);
                    }
                    VoxelColumnData columnData = new VoxelColumnData(sectionDatas, payload.columnTimestamp());
                    VoxyClientBridge.ingest(level, payload.dimension(), payload.chunkX(), payload.chunkZ(), columnData, queued.isUpdate());
                } finally {
                    buf.release();
                }
            } catch (Exception e) {
                VSSLogger.error("Failed to process voxel column at " + payload.chunkX() + "," + payload.chunkZ(), e);
            }
        }
    }

    void shutdown() {
        this.shuttingDown = true;
        ExecutorService old = this.executor;
        old.shutdownNow();
        this.columnQueue.clear();
        this.queueSize.set(0);
        try {
            old.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.processing.set(false);
        this.executor = createExecutor();
        this.shuttingDown = false;
    }

    int getQueuedCount() {
        return this.queueSize.get();
    }

    long getColumnsDropped() {
        return this.columnsDropped.get();
    }

    void resetStats() {
        this.columnsDropped.set(0L);
        this.lastDropWarnMs = 0L;
    }

    /** A queued column plus whether it re-syncs one the client already had (⇒ clear sub-chunks that emptied). */
    private record QueuedColumn(VoxelColumnS2CPayload payload, boolean isUpdate) {}
}
