package com.golem.boxy.vss.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link ClientLevel}'s {@code tickingEntities} list (SRG {@code f_171630_}) so the distant-entity
 * ticker can tell which entities the normal tick loop already handles. {@code hasChunkAt} is unreliable here
 * because Voxy populates the client chunk cache in the LOD region (so it reports chunks as present where the
 * entity is in fact not ticking). Client-only; hand-SRG, {@code remap = false}.
 */
@Mixin(value = ClientLevel.class, remap = false)
public interface AccessorClientLevel {
    @Accessor("f_171630_")
    EntityTickList boxy$getTickingEntities();
}
