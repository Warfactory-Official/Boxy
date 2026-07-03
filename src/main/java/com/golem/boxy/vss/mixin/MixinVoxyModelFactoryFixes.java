package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.VoxyBackports;
import com.golem.boxy.vss.common.VSSLogger;
import me.cortex.voxy.client.core.model.ColourDepthTextureData;
import me.cortex.voxy.common.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.List;
import java.util.Optional;

/**
 * Three model-bakery fixes backported (as reimplementations) from upstream Voxy 0.2.17-beta into
 * the 0.2.14-alpha build Boxy loads. All run on Voxy's single "Model factory processor" thread,
 * which is what makes the shadowed collections safe to touch here.
 *
 * <p><b>1. Missing-biome crash fix</b> ({@link #boxy$fallbackMissingBiome}). 0.2.14's
 * {@code processAllThings} resolves each queued biome with
 * {@code registry.getOptional(id).orElseThrow()} — a {@code NoSuchElementException} on the bake
 * thread (escalated to a hard crash by {@code ModelBakerySubsystem.tick}) whenever the on-disk
 * {@code .voxy} store references a biome that no longer exists (removed biome mod, changed
 * datapack). Upstream 0.2.17 logs and substitutes a default; this redirect does the same (plains,
 * or any registered biome if even plains is gone).
 *
 * <p><b>2. Translucent-layer reclassification</b> ({@link #boxy$reclassifyTranslucentLayer}).
 * Upstream re-checks a translucent-declared bake against its actual rendered texels and demotes it
 * to solid/cutout when nothing is actually translucent (see
 * {@link VoxyBackports#reclassifyTranslucent}). Injected as a {@code @ModifyArgs} on the layer
 * argument of the {@code processTextureBakeResult} call, after the texture data exists and after
 * 0.2.14's own leaves-override already ran.
 *
 * <p><b>3. Emission metadata + biome-tint registration repair</b> ({@link #boxy$onModelBaked}).
 * On each <i>fresh</i> bake result (dedup hits return null and inherit the first state's model,
 * exactly as upstream):
 * <ul>
 *   <li>writes the state's own light emission into metadata bits 55-58 — the same bits upstream's
 *   {@code ModelQueries.lightEmission} reads and 0.2.14 leaves unused — which the companion
 *   {@link MixinVoxyRenderDataFactoryEmissive} applies to every meshed quad. Note the write lands
 *   just after {@code idMappings} publication, so a mesher racing within that microsecond window
 *   meshes one section without glow until its next rebuild — accepted, since matching upstream's
 *   pre-publication placement would need fragile mid-method locals;</li>
 *   <li>repairs the biome-tint registration gap: 0.2.14 skips registering a biome-coloured model
 *   in {@code modelsRequiringBiomeColours} when it bakes before any biome is known (world-load
 *   ordering), so the model never receives tint colours when biomes arrive — permanently grey
 *   grass/leaves/water for that model. Registering it (with the index-0 slot the zeroed model
 *   record already encodes) lets Voxy's own {@code addBiome0} rebuild indexes and colours on the
 *   next biome add, exactly as upstream. Gated on the model-record flags (offset 24: bit0 = has
 *   colour provider, bit1 = biome-dependent) because {@code addBiome0} throws on a registered
 *   model without a colour provider (the waterlogged-block case sets the metadata bit but not the
 *   flag).</li>
 * </ul>
 *
 * <p>Targeted by string ({@code remap = false}, Voxy class; {@code @At} targets are JDK/Voxy-own
 * names, and MC class names in descriptors are identical between official and SRG);
 * {@code require = 0} on every injector so each degrades independently to 0.2.14's original
 * behaviour.
 */
@Mixin(targets = "me.cortex.voxy.client.core.model.ModelFactory", remap = false)
public class MixinVoxyModelFactoryFixes {

    @Shadow @Final private long[] metadataCache;
    @Shadow @Final private List<Biome> biomes;
    @Shadow @Final private List<Pair<Integer, BlockState>> modelsRequiringBiomeColours;

    /** modelFlags int offset inside the model record (6 face ints, then flags). */
    private static final long BOXY$MODEL_FLAGS_OFFSET = 4L * 6;

    @Redirect(method = "processAllThings",
              at = @At(value = "INVOKE",
                       target = "Ljava/util/Optional;orElseThrow()Ljava/lang/Object;"),
              require = 0)
    private Object boxy$fallbackMissingBiome(Optional<?> resolved) {
        if (resolved.isPresent()) {
            return resolved.get();
        }
        var level = Minecraft.getInstance().level;
        if (level != null) {
            var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
            Biome fallback = registry.get(Biomes.PLAINS);
            if (fallback == null) {
                for (Biome any : registry) {
                    fallback = any;
                    break;
                }
            }
            if (fallback != null) {
                VSSLogger.warn("Boxy: a biome referenced by Voxy's store is no longer registered"
                        + " (removed mod/datapack?) — substituting a default instead of crashing");
                return fallback;
            }
        }
        return resolved.orElseThrow(); // no fallback available — surface Voxy's original error
    }

    @ModifyArgs(method = "processModelResult",
                at = @At(value = "INVOKE",
                         target = "Lme/cortex/voxy/client/core/model/ModelFactory;processTextureBakeResult(ILnet/minecraft/world/level/block/state/BlockState;[Lme/cortex/voxy/client/core/model/ColourDepthTextureData;ZZLnet/minecraft/client/renderer/RenderType;)Lme/cortex/voxy/client/core/model/ModelFactory$ModelBakeResultUpload;"),
                require = 0)
    private void boxy$reclassifyTranslucentLayer(Args args) {
        if (args.<RenderType>get(5) == RenderType.translucent()) {
            args.set(5, VoxyBackports.reclassifyTranslucent(args.<ColourDepthTextureData[]>get(2)));
        }
    }

    @Inject(method = "processTextureBakeResult", at = @At("RETURN"), require = 0)
    private void boxy$onModelBaked(int blockId, BlockState blockState, ColourDepthTextureData[] textureData,
                                   boolean isShaded, boolean darkenedTinting, RenderType layer,
                                   CallbackInfoReturnable<Object> cir) {
        Object result = cir.getReturnValue();
        if (result == null) {
            return; // dedup hit — the shared model keeps its original bake's data, like upstream
        }
        var upload = (AccessorVoxyModelBakeUpload) result;
        int modelId = upload.boxy$getModelId();
        if (modelId < 0 || modelId >= this.metadataCache.length) {
            return;
        }

        int emission = VoxyBackports.blockLightEmission(blockState);
        if (emission > 0) {
            this.metadataCache[modelId] |= ((long) emission) << VoxyBackports.METADATA_EMISSION_SHIFT;
        }

        if (this.biomes.isEmpty()) {
            // 0.2.14 only registers biome-coloured models when at least one biome is already
            // known; the model record buffer is zeroed, so its biome index already reads as the
            // 0 upstream would write — only the registration itself is missing.
            int modelFlags = MemoryUtil.memGetInt(upload.boxy$getModel().address + BOXY$MODEL_FLAGS_OFFSET);
            boolean hasColourProvider = (modelFlags & 1) != 0;
            boolean biomeDependent = (modelFlags & 2) != 0;
            if (hasColourProvider && biomeDependent) {
                this.modelsRequiringBiomeColours.add(new Pair<>(modelId, blockState));
            }
        }
    }
}
