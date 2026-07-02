package com.golem.boxy.vss.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link ChunkMap}'s {@code level} field (SRG {@code f_140133_}) so the dirty-save hook can resolve
 * the dimension. {@code remap = false} + hand-written SRG (Boxy's mod jar is reobfuscated official-&gt;SRG at
 * build time, but mixin annotation strings are not).
 *
 * <p>Note: the off-thread NBT read ({@code ChunkStorage.read}) is reached via reflection in
 * {@code NbtSectionSerializer} rather than an {@code @Invoker} here — that method is declared on the
 * superclass {@code ChunkStorage}, and reflection degrades gracefully (e.g. under C2ME's reworked chunk I/O)
 * instead of failing the whole {@code ChunkMap} class transform.
 */
@Mixin(value = ChunkMap.class, remap = false)
public interface AccessorChunkMap {
    @Accessor("f_140133_")
    ServerLevel boxy$getLevel();
}
