package com.golem.boxy.smoke;

import com.golem.boxy.vss.server.DiscoveredEntities;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.UUID;

final class DiscoveredEntitiesChecks {
    static void run() {
        radiusIsCenteredOnEntityAndWindowsOverlap();
        activationUsesNearestPointOfEntityChunk();
        discoveryRoundTripMovementAndRemoval();
    }

    private static void assertTrue(boolean value) {
        if (!value) throw new AssertionError("Discovery check failed");
    }

    private static void assertFalse(boolean value) {
        assertTrue(!value);
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private static void radiusIsCenteredOnEntityAndWindowsOverlap() {
        var chunks = new LongOpenHashSet();
        DiscoveredEntities.addWindow(chunks, 0.5, 0.5, ChunkPos.asLong(40, 0), 64, 2);
        assertEquals(25, chunks.size());
        assertTrue(chunks.contains(ChunkPos.asLong(38, -2)));
        assertTrue(chunks.contains(ChunkPos.asLong(42, 2)));
        assertFalse(chunks.contains(ChunkPos.asLong(0, 0)));
        DiscoveredEntities.addWindow(chunks, 0.5, 0.5, ChunkPos.asLong(41, 0), 64, 2);
        assertEquals(30, chunks.size());
    }

    private static void activationUsesNearestPointOfEntityChunk() {
        var chunks = new LongOpenHashSet();
        DiscoveredEntities.addWindow(chunks, 15.9, 15.9, ChunkPos.asLong(1, 1), 1, 1);
        assertEquals(9, chunks.size());
        chunks.clear();
        DiscoveredEntities.addWindow(chunks, 0, 0, ChunkPos.asLong(2, 2), 1, 1);
        assertTrue(chunks.isEmpty());
        DiscoveredEntities.addWindow(chunks, 0, 0, ChunkPos.asLong(-2, 0), 1, 1);
        assertEquals(9, chunks.size());
    }

    private static void discoveryRoundTripMovementAndRemoval() {
        var cache = new DiscoveredEntities();
        UUID uuid = UUID.randomUUID();
        var type = ResourceLocation.parse("minecraft:ghast");
        cache.remember(uuid, type, ChunkPos.asLong(40, -3));
        long revision = cache.revision();
        cache.remember(uuid, type, ChunkPos.asLong(40, -3));
        assertEquals(revision, cache.revision());
        cache.remember(uuid, type, ChunkPos.asLong(41, -3));
        assertTrue(cache.revision() > revision);
        CompoundTag saved = cache.save(new CompoundTag(), null);
        var restored = DiscoveredEntities.load(saved, null);
        assertFalse(restored.isDirty());
        assertEquals(saved, restored.save(new CompoundTag(), null));
        restored.forget(uuid);
        assertTrue(restored.save(new CompoundTag(), null).getList("entities", 10).isEmpty());
        assertFalse(cache.save(new CompoundTag(), null).getList("entities", 10).isEmpty());
        cache.remember(uuid, type, ChunkPos.asLong(31, 0));
        assertTrue(cache.desiredChunks(528, 0, 1, 0).contains(ChunkPos.asLong(31, 0)));
        cache.remember(uuid, type, ChunkPos.asLong(-33, 0));
        assertFalse(cache.desiredChunks(528, 0, 1, 0).contains(ChunkPos.asLong(31, 0)));
        assertTrue(cache.desiredChunks(-528, 0, 1, 0).contains(ChunkPos.asLong(-33, 0)));
        var config = com.golem.boxy.vss.config.VSSServerConfig.CONFIG;
        var original = config.trackedEntityTypes;
        try {
            config.trackedEntityTypes = java.util.Arrays.asList("minecraft:pig", null);
            com.golem.boxy.vss.common.TrackedEntityTypes.refreshServerTypes();
            assertTrue(cache.desiredChunks(-528, 0, 1, 0).isEmpty());
        } finally {
            config.trackedEntityTypes = original;
            com.golem.boxy.vss.common.TrackedEntityTypes.refreshServerTypes();
        }
    }
}
