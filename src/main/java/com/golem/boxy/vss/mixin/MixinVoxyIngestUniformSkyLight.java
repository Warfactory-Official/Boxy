package com.golem.boxy.vss.mixin;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;


/**
 * Fixes fully-lit terrain being ingested pitch black, which is why LODs built while you play look broken
 * but the same terrain re-run through {@code /voxy import} looks correct.
 *
 * <p><b>The bug.</b> {@code VoxelIngestService.getLightingSupplier} starts from a supplier that returns
 * light 0 for every voxel and only replaces it if a light layer is present <i>and</i> non-empty:
 *
 * <pre>{@code
 * ILightingSupplier supplier = (x,y,z) -> (byte) 0;
 * boolean sl = sla != null && !sla.isEmpty();
 * boolean bl = bla != null && !bla.isEmpty();
 * if (sl || bl) { ... }
 * }</pre>
 *
 * Minecraft does not allocate a sky-light array for a section that is <b>uniformly full skylight</b> —
 * the storage answers such queries from its "above the top populated section" fast path. For those
 * sections {@code getDataLayerData} returns null (or an {@code isEmpty()} layer), both flags are false,
 * and every voxel is ingested with light 0. Open, flat, fully-lit terrain is exactly the shape that hits
 * this, which is why it shows as black patches scattered across plains while raised features stay lit.
 *
 * <p>Region files, by contrast, serialise explicit {@code SkyLight} byte arrays, so the importer always
 * gets real data — hence "import fixes it, playing there does not". The damage is baked into the Voxy
 * database at ingest, so {@code /voxy reload} and restarts do not clear it.
 *
 * <p><b>The fix.</b> When the sky-light layer for a section is absent or empty, ask the light engine what
 * the sky light actually is (sampling the section's eight corners and taking the maximum, which is exact
 * for a section vanilla considers uniform) and, only if that is full 15, synthesise a uniform
 * {@code DataLayer} so the real value reaches {@code getLightingSupplier}. Anything below 15 is left
 * untouched — an unallocated layer under terrain genuinely means darkness, and returning the original
 * there preserves Voxy's behaviour exactly.
 *
 * <p>Block light is deliberately not touched: an unallocated block-light layer really does mean "no light
 * sources", so 0 is correct. The {@code ordinal = 1} below selects the sky call ({@code blp} is queried
 * first, {@code slp} second); if that ever drifted, the "only when the sample is 15" guard makes the
 * mixin a no-op rather than a hazard, since a whole section uniformly at block light 15 does not occur.
 *
 * <p>Targeted by string ({@code remap = false}, Voxy class); {@code require = 0} so it
 * degrades to Voxy's original behaviour if the method shape changes. The handler is an instance method to
 * match {@code enqueueIngest} (§7.7).
 */
@Mixin(targets = "me.cortex.voxy.common.world.service.VoxelIngestService", remap = false)
public class MixinVoxyIngestUniformSkyLight {

    @Redirect(method = "enqueueIngest",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/level/lighting/LayerLightEventListener;"
                               + "getDataLayerData(Lnet/minecraft/core/SectionPos;)Lnet/minecraft/world/level/chunk/DataLayer;",
                       ordinal = 1),
              require = 0)
    private DataLayer boxy$skyLightOrUniform(LayerLightEventListener listener, SectionPos pos) {
        return com.golem.boxy.vss.client.IngestLighting.skyLight(listener, pos);
    }
}
