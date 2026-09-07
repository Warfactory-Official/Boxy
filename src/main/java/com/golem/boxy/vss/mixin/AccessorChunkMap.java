package com.golem.boxy.vss.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface AccessorChunkMap {
    @Accessor("level")
    ServerLevel boxy$getLevel();

    @Invoker("getChunks")
    Iterable<ChunkHolder> boxy$getChunks();
}
