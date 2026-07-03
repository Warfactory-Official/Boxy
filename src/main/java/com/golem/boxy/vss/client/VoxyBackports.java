package com.golem.boxy.vss.client;

import me.cortex.voxy.client.core.model.ColourDepthTextureData;
import me.cortex.voxy.client.core.model.ModelFactory;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Shared logic for the Voxy engine fixes Boxy backports from upstream Voxy 0.2.17-beta into the
 * 0.2.14-alpha (1.20.1) build it loads. Everything here is a from-scratch reimplementation of the
 * upstream behaviour — no Voxy code is shipped; the mixins in {@code com.golem.boxy.vss.mixin}
 * inject these routines at the equivalent points.
 *
 * <p>Client-only: referenced exclusively from client-list mixins, so it never loads on a dedicated
 * server (where Voxy's client classes may not be initialized).
 */
public final class VoxyBackports {

    private VoxyBackports() {}

    // ------------------------------------------------------------------------------------------
    // Emissive-block LOD lighting (upstream 0.2.17: ModelFactory emission metadata + applyQuadLight)
    // ------------------------------------------------------------------------------------------

    /**
     * Model metadata bits 55-58 hold the block's own light emission (0-15). Upstream writes them as
     * {@code metadata |= emission << (48+7)} and reads them via {@code ModelQueries.lightEmission};
     * bits 55+ are unused by 0.2.14's {@code ModelQueries}, so stashing them there is safe.
     */
    public static final int METADATA_EMISSION_SHIFT = 8 * 6 + 7; // 55

    /**
     * In the packed quad format the light byte sits at bits 55-62 ({@code RenderDataFactory.LM =
     * 0xFF << 55}); the block-light nibble is its upper half, bits 59-62.
     */
    private static final int QUAD_BLOCK_LIGHT_SHIFT = 59;
    private static final long QUAD_BLOCK_LIGHT_MASK = 0xFL << QUAD_BLOCK_LIGHT_SHIFT;

    /**
     * The ModelFactory whose models the current thread is meshing. {@code RenderDataFactory} is
     * per-mesh-worker-thread, and its static meshing helpers have no path back to the factory, so
     * the {@code generateMesh} HEAD hook records it here. Deliberately not cleared on exit:
     * {@code generateMesh} routinely unwinds via {@code IdNotYetComputedException}, so a
     * clear-at-RETURN would be unreliable anyway — the value is refreshed on every entry and only
     * read from the redirected {@code putNext} call sites, which all sit inside
     * {@code generateMesh}'s call tree.
     */
    private static final ThreadLocal<ModelFactory> MESHING_FACTORY = new ThreadLocal<>();

    public static void enterMeshing(ModelFactory factory) {
        MESHING_FACTORY.set(factory);
    }

    /**
     * Upstream {@code applyQuadLight}: raise the quad's block-light nibble to at least the emitting
     * block's own light emission, so glowstone/lava/sea-lantern LODs glow instead of relying purely
     * on the stored neighbour light (which higher mip levels lose). The quad carries its client
     * model id at bits 26-41 ({@code packPartialQuadData}), which resolves to the same metadata long
     * upstream threads through as {@code selfMeta}.
     *
     * <p>Hot path (runs per emitted quad on the mesh workers, which are shared with Embeddium's
     * chunk builders): the instance-method redirect passes the shadowed {@code modelMan} directly;
     * only the two static meshing helpers pay the ThreadLocal lookup via
     * {@link #applyEmissiveLightTL}.
     */
    public static long applyEmissiveLight(long quad, ModelFactory factory) {
        int clientModelId = (int) ((quad >>> 26) & 0xFFFF);
        long emission = (factory.getModelMetadataFromClientId(clientModelId) >>> METADATA_EMISSION_SHIFT) & 0xFL;
        if (emission == 0) {
            return quad;
        }
        long emissionBits = emission << QUAD_BLOCK_LIGHT_SHIFT;
        if ((quad & QUAD_BLOCK_LIGHT_MASK) >= emissionBits) {
            return quad;
        }
        return (quad & ~QUAD_BLOCK_LIGHT_MASK) | emissionBits;
    }

    /** ThreadLocal-resolving variant for the static meshing helpers (no {@code this} to shadow). */
    public static long applyEmissiveLightTL(long quad) {
        ModelFactory factory = MESHING_FACTORY.get();
        if (factory == null) {
            return quad; // generateMesh hook didn't apply — degrade to original behaviour
        }
        return applyEmissiveLight(quad, factory);
    }

    /**
     * Upstream {@code getBlockLightEmission}: a state with emissive rendering is treated as
     * full-bright, otherwise its registered light emission, clamped to the 4-bit range. Any
     * exception (a modded emissive-rendering predicate probing the world) yields 0 so a bake can
     * never break.
     */
    public static int blockLightEmission(BlockState state) {
        try {
            int emission = state.getLightEmission();
            if (emission < 15 && state.emissiveRendering(new SingleStateGetter(state), BlockPos.ZERO)) {
                emission = 15;
            }
            return Math.max(0, Math.min(15, emission));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Minimal BlockGetter that answers every position with the one state, like upstream's. */
    private record SingleStateGetter(BlockState state) implements BlockGetter {
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return this.state;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return this.state.getFluidState();
        }

        @Override
        public int getHeight() {
            return 0;
        }

        @Override
        public int getMinBuildHeight() {
            return 0;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Render-layer reclassification (upstream 0.2.17: TextureUtils.hasTranslucentPixel /
    // isSolidWhereDrawn + the translucent re-check in processModelResult)
    // ------------------------------------------------------------------------------------------

    /**
     * A block whose declared render layer is translucent but whose baked textures contain no
     * actually-translucent pixel gets reclassified: all-opaque-where-drawn → solid, otherwise
     * cutout. Fixes wrong-layer LOD artifacts (false transparency / missing alpha discard) for
     * blocks that declare translucent conservatively.
     */
    public static RenderType reclassifyTranslucent(ColourDepthTextureData[] faces) {
        for (ColourDepthTextureData face : faces) {
            if (hasTranslucentPixel(face)) {
                return RenderType.translucent();
            }
        }
        for (ColourDepthTextureData face : faces) {
            if (!isSolidWhereDrawn(face)) {
                return RenderType.cutout();
            }
        }
        return RenderType.solid();
    }

    private static boolean hasTranslucentPixel(ColourDepthTextureData face) {
        int[] colour = face.colour();
        int[] depth = face.depth();
        for (int i = 0; i < colour.length; i++) {
            if ((depth[i] & 0xFF) != 0) { // only judge written pixels
                int alpha = colour[i] >>> 24;
                if (alpha != 0 && alpha != 255) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSolidWhereDrawn(ColourDepthTextureData face) {
        int[] colour = face.colour();
        int[] depth = face.depth();
        for (int i = 0; i < colour.length; i++) {
            if ((depth[i] & 0xFF) != 0 && (colour[i] >>> 24) != 255) {
                return false;
            }
        }
        return true;
    }
}
