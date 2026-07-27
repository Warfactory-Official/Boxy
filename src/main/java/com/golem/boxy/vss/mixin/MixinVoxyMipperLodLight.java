package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.config.VSSClientConfig;
import me.cortex.voxy.common.world.other.Mapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops distant LOD terrain from going dark as the LOD level coarsens.
 *
 * <p><b>The bug.</b> {@code Mipper.mip} reduces eight child voxels to one. Only when <i>all eight are
 * air</i> does it average their light; in every other case it picks a single representative child and
 * {@code return}s it verbatim — inheriting that child's light wholesale. The representative is chosen by
 * {@code max((getBlockStateOpacity(child) << 4) | childIndex)}, i.e. <b>by opacity alone, with no regard
 * for light</b>. Since Minecraft stores light <i>at</i> a position and an opaque block's own position
 * holds ~0, the opacity vote almost always elects a child whose stored light is 0 — and that becomes the
 * whole node's light. Each further mip level re-runs the vote on already-darkened data, so the error
 * compounds with LOD level. Voxy's own TODOs in that method acknowledge it: <i>"also average out the
 * light level and set that as the new light level"</i> and <i>"i think it needs to compute the _max_
 * light level"</i>.
 *
 * <p><b>Why it tracks FOV.</b> LOD level is chosen by {@code shouldDecend()}: {@code _screenSize > minSSS},
 * where {@code minSSS = subDivisionSize² / (width·height)} — so a node is subdivided once its projected
 * area exceeds {@code subDivisionSize²} <i>pixels</i>. That area scales as {@code 1/tan²(fov/2)}, so a
 * wider FOV shrinks every node on screen and pushes the whole view to coarser LODs; dropping FOV from 110
 * to 30 changes it by ~28x, forcing much finer LODs and hiding the darkening. Distance coarsens the LOD
 * the same way, which is why it reads as "dark chunks in the distance". It shows in both render pipelines
 * because the damage is baked into the voxel data long before any pipeline runs.
 *
 * <p><b>The fix.</b> Keep Voxy's representative child — the opacity vote is right for silhouette and
 * material — but lift its light to the maximum over <b>all eight</b> children, per channel (block light is
 * the high nibble of {@code getLightId}, sky light the low nibble). This is the "max light level" the
 * author's TODO asks for. The lift can only raise light, never lower it.
 *
 * <p><b>Air must be included in that vote.</b> {@code WorldConversionFactory} stores every voxel with the
 * light at its own position and keeps air voxels via {@code Mapper.airWithLight}, so in a solid block's
 * own entry the light is ~0 and the light you actually see on its face lives in the <i>adjacent air</i>.
 * An earlier revision of this mixin voted over non-air children only, on the reasoning that air "carries
 * no surface" — that discarded the only children holding real light and left flat, fully-lit terrain black
 * at voxel granularity while raised features stayed lit. Do not reintroduce that filter.
 *
 * <p><b>The all-air branch is included too.</b> Voxy averages the eight children there, and an opaque face
 * is lit by its <i>neighbour</i> — for a surface block that is the air cell above it, so those averaged air
 * cells are exactly what the renderer samples. Averaging compounds: level 1 has averaged once, level 4 four
 * times, each pass pulling toward the mean of a larger neighbourhood. Taking the max supersedes the average
 * in both branches and makes the result independent of how many mip levels have been applied.
 * {@link MixinVoxyMipperLightFix} still corrects that branch's block-light truncation when this is disabled.
 *
 * <p><b>Takes effect on ingest only.</b> {@code Mipper.mip} runs from {@code WorldVoxilizedSectionMipper}
 * during {@code VoxelIngestService} ingest, so the mip pyramid is baked into world storage. Terrain
 * already in the Voxy database keeps its old light until it is re-ingested.
 *
 * <p>Targeted by string ({@code remap = false}, Voxy class); {@code require = 0} so it degrades to Voxy's
 * original behaviour if the method shape ever changes. The handler is {@code static} to match {@code mip},
 * and the callback is {@code CallbackInfoReturnable<Long>} because {@code mip} returns {@code long} — both
 * are validation errors that would fail the whole mixin at apply time despite {@code require = 0} (§7.7).
 */
@Mixin(targets = "me.cortex.voxy.common.world.other.Mipper", remap = false)
public class MixinVoxyMipperLodLight {

    @Inject(method = "mip", at = @At("RETURN"), cancellable = true, require = 0)
    private static void boxy$liftMippedLodLight(long I000, long I100, long I001, long I101,
                                                long I010, long I110, long I011, long I111,
                                                Mapper mapper, CallbackInfoReturnable<Long> cir) {
        if (!VSSClientConfig.CONFIG.brightenMippedLodLight) {
            return;
        }

        // NOTE: the all-air case is deliberately NOT skipped. Voxy handles it by *averaging* the eight
        // children's light, and an opaque face is lit by its NEIGHBOUR — for a surface block that is the
        // air cell above it. So the averaged air cells are exactly the ones the renderer reads. Averaging
        // compounds: level 1 has averaged once, level 4 four times, each pass pulling toward the mean of a
        // larger neighbourhood that includes shadowed air beside terrain. That is the whole reason a high
        // "Pixels² of subdivision size" (coarser LOD) goes black while a low one looks fine — more averages.
        // Taking the max here supersedes the average in both branches and can only brighten.

        // Max over ALL eight children, air included (see the class doc: air is where the light lives).
        int light = Mapper.getLightId(I000);
        int maxBlock = light & 0xF0;
        int maxSky = light & 0x0F;
        maxBlock = Math.max(maxBlock, Mapper.getLightId(I100) & 0xF0);
        maxBlock = Math.max(maxBlock, Mapper.getLightId(I001) & 0xF0);
        maxBlock = Math.max(maxBlock, Mapper.getLightId(I101) & 0xF0);
        maxBlock = Math.max(maxBlock, Mapper.getLightId(I010) & 0xF0);
        maxBlock = Math.max(maxBlock, Mapper.getLightId(I110) & 0xF0);
        maxBlock = Math.max(maxBlock, Mapper.getLightId(I011) & 0xF0);
        maxBlock = Math.max(maxBlock, Mapper.getLightId(I111) & 0xF0);
        maxSky = Math.max(maxSky, Mapper.getLightId(I100) & 0x0F);
        maxSky = Math.max(maxSky, Mapper.getLightId(I001) & 0x0F);
        maxSky = Math.max(maxSky, Mapper.getLightId(I101) & 0x0F);
        maxSky = Math.max(maxSky, Mapper.getLightId(I010) & 0x0F);
        maxSky = Math.max(maxSky, Mapper.getLightId(I110) & 0x0F);
        maxSky = Math.max(maxSky, Mapper.getLightId(I011) & 0x0F);
        maxSky = Math.max(maxSky, Mapper.getLightId(I111) & 0x0F);

        long result = cir.getReturnValueJ();
        int current = Mapper.getLightId(result);
        int lifted = Math.max(maxBlock, current & 0xF0) | Math.max(maxSky, current & 0x0F);
        if (lifted != current) {
            cir.setReturnValue(Mapper.withLight(result, lifted));
        }
    }
}
