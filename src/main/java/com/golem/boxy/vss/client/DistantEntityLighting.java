package com.golem.boxy.vss.client;

import com.golem.boxy.vss.common.TrackedEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.WeakHashMap;

public final class DistantEntityLighting {
    private static final Map<Entity, EntityLightHistory> HISTORY = new WeakHashMap<>();
    private static final Map<LevelChunk, Object> LIGHT_SOURCES = new WeakHashMap<>();

    private DistantEntityLighting() {}

    public static int resolve(Entity entity, float partialTick, int packedLight) {
        if (!ClientEntitySync.enabled() || !TrackedEntityTypes.clientContains(entity.getType())) {
            HISTORY.remove(entity);
            return packedLight;
        }
        if (entity.isOnFire()) {
            // Fire overrides environmental lighting; don't retain a pre-fire transition state.
            HISTORY.remove(entity);
            return packedLight;
        }
        if (!(entity.level() instanceof ClientLevel level)) return packedLight;
        BlockPos pos = BlockPos.containing(entity.getLightProbePosition(partialTick));
        LevelChunk chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4,
                ChunkStatus.FULL, false);
        boolean present = chunk != null;
        boolean ready = present && level.getLightEngine().lightOnInSection(SectionPos.of(pos))
                && level.isLightUpdateQueueEmpty() && !level.getLightEngine().hasLightWork();
        EntityLightHistory history = HISTORY.computeIfAbsent(entity, key -> new EntityLightHistory());
        history.lightSource(chunk == null ? null : LIGHT_SOURCES.computeIfAbsent(chunk, key -> new Object()));
        return history.sample(level.dimension(), level.getGameTime(), packedLight, present, ready);
    }

    public static void lightUpdateQueued(ClientLevel level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk != null) LIGHT_SOURCES.put(chunk, new Object());
    }

    public static void clear() {
        HISTORY.clear();
        LIGHT_SOURCES.clear();
    }
}
