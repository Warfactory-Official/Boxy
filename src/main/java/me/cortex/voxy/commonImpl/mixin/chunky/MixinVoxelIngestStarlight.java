package me.cortex.voxy.commonImpl.mixin.chunky;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starlight (Forge) compatibility for Voxy's chunk ingest. Boxy-only; compiled in dev names,
 * reobfuscated to SRG, injected into Voxy's jar and added to {@code common.voxy.mixins.json}
 * alongside {@link MixinForgeWorld}.
 *
 * <p>{@code VoxelIngestService.enqueueIngest} gates ingest on
 * {@code lightEngine.getDebugSectionType(layer, pos) == LIGHT_AND_DATA}. That debug state is
 * maintained by vanilla's {@code LayerLightSectionStorage}, but the <b>Forge</b> Starlight port
 * computes light entirely in its own storage and never touches it — so the query reports "no data"
 * even for fully-lit chunks ({@code isLightCorrect() == true}). Voxy then rejects every Chunky-
 * generated chunk, so pre-generated terrain never becomes an LOD.
 *
 * <p>(Voxy declares {@code "starlight": "*"} on Fabric, where Starlight <i>does</i> keep that vanilla
 * state populated; only the Forge port diverges, which is why this is Boxy-specific.)
 *
 * <p>Fix: when the vanilla query says "not LIGHT_AND_DATA", fall back to whether the layer actually
 * has a light array for the section ({@code getLayerListener(layer).getDataLayerData(pos) != null}).
 * Starlight implements {@code getDataLayerData} (clients receive light through it), so this trusts the
 * real, present light data. It is a no-op without Starlight: vanilla already returns LIGHT_AND_DATA
 * whenever a data layer exists, so the original value is returned unchanged.
 */
@Mixin(value = VoxelIngestService.class, remap = false)
public class MixinVoxelIngestStarlight {
    private static final Logger BOXY_LOGGER = LoggerFactory.getLogger("Boxy/Starlight");
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

    @WrapOperation(
            method = "enqueueIngest",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;m_284493_(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/SectionPos;)Lnet/minecraft/world/level/lighting/LayerLightSectionStorage$SectionType;"))
    private LayerLightSectionStorage.SectionType voxy$starlightAwareLightType(
            LevelLightEngine engine, LightLayer layer, SectionPos pos,
            Operation<LayerLightSectionStorage.SectionType> original) {
        LayerLightSectionStorage.SectionType type = original.call(engine, layer, pos);
        if (type == LayerLightSectionStorage.SectionType.LIGHT_AND_DATA) {
            return type;
        }
        try {
            if (engine.getLayerListener(layer).getDataLayerData(pos) != null) {
                if (ANNOUNCED.compareAndSet(false, true)) {
                    BOXY_LOGGER.info("Starlight-aware light gate engaged — accepting light data the vanilla debug query missed");
                }
                return LayerLightSectionStorage.SectionType.LIGHT_AND_DATA;
            }
        } catch (Throwable ignored) {
        }
        return type;
    }
}
