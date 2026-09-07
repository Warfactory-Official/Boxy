package com.golem.boxy.vss.server;

import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.Event;

/** Server-thread signal while a completed bulk edit and its lighting are still loaded. */
public final class BulkTerrainUpdateEvent extends Event {
    private final LevelChunk chunk;

    public BulkTerrainUpdateEvent(LevelChunk chunk) { this.chunk = chunk; }
    public LevelChunk chunk() { return chunk; }
}
