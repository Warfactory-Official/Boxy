package com.golem.boxy.vss.server;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * A detached, thread-confined copy of everything needed to serialize one chunk column, produced on the server
 * thread by {@link ColumnSnapshotter#snapshot} and consumed off-thread by {@link ColumnSnapshotter#serialize}.
 *
 * <p><b>Exactly one thread may ever touch a given instance after it is handed off.</b> The block-state
 * containers are real {@link PalettedContainer}s and {@code write()} takes their ThreadingDetector, which in
 * Minecraft is armed here: two threads writing the same snapshot is a ReportedException, not a silent race.
 * Create it, submit it to one task, serialize it, drop it. In particular never cache one: caches hold
 * {@code byte[]}, which is immutable by convention here and safe to share.
 *
 * <p>Everything is a copy of live state rather than a reference to it, so the source chunk unloading between
 * snapshot and serialize is harmless.
 */
public record ColumnSnapshot(int cx, int cz, ColumnSnapshot.SectionSnapshot[] sections) {

    /**
     * @param nonEmptyBlockCount the live value read via {@code AccessorLevelChunkSection}, not a recount
     * @param states             a main-thread {@code PalettedContainer.copy()} — owned solely by this snapshot
     * @param biomeBytes         biomes serialized inline on the server thread; the biome container is
     *                           {@code PalettedContainerRO} with no {@code copy()}, and at 64 entries of
     *                           <=3 bits it is far cheaper to just write than to work around that
     * @param blockLight         2048 bytes, or null when the section carries no block light
     * @param skyLight           2048 bytes, or null when the section carries no sky light
     */
    public record SectionSnapshot(
            int sectionY,
            short nonEmptyBlockCount,
            PalettedContainer<BlockState> states,
            byte[] biomeBytes,
            byte[] blockLight,
            byte[] skyLight) {
    }
}
