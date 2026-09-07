package com.golem.boxy.vss.mixin;

import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link LevelChunkSection}'s {@code nonEmptyBlockCount} field, which is the
 * first thing {@code LevelChunkSection.write} puts on the wire. Off-thread column serialization reproduces
 * that byte layout from a snapshot rather than from the live section, so it needs the value directly.
 *
 * <p>It must be the live incrementally-maintained count, not one recomputed from a copied container:
 * {@code recalcBlockCounts()} counts waterlogged blocks differently, so a recomputed value would silently
 * disagree with what the client's {@code LevelChunkSection.read} and Voxy's ingest expect.
 *
 */
@Mixin(LevelChunkSection.class)
public interface AccessorLevelChunkSection {
    @Accessor("nonEmptyBlockCount")
    short boxy$getNonEmptyBlockCount();
}
