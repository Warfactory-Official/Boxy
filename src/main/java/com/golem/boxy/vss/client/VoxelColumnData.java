package com.golem.boxy.vss.client;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** A deserialized LOD column ready to hand to Voxy: its sections plus the server's column timestamp. */
public record VoxelColumnData(SectionData[] sections, long columnTimestamp) {
    public record SectionData(int sectionY, LevelChunkSection section, DataLayer blockLight, DataLayer skyLight) {}
}
