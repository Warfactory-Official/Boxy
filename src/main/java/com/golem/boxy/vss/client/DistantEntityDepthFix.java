package com.golem.boxy.vss.client;

import com.golem.boxy.vss.common.TrackedEntityTypes;
import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.config.DistantEntityDepthMode;
import com.golem.boxy.vss.config.VSSClientConfig;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;


/**
 * Fixes distant-entity z-fighting by <b>re-banding the depth buffer around each tracked entity</b>.
 *
 * <p><b>The problem.</b> Distant entities share Minecraft's standard 24-bit fixed-point depth buffer under a
 * projection with near = 0.05, which crams everything beyond ~256 blocks into the last ~0.02% of the depth
 * range: at 512 blocks one depth step spans ~0.3 blocks, far coarser than the 0.03–0.06-block gaps between a
 * model's overlapping layers (skin overlays, armor, vehicle panels). Layer pairs land on the same or randomly
 * adjacent depth values and shimmer as the camera moves. No global remedy fits this stack (reversed-Z /
 * float depth cannot simply be imposed on every vanilla and shaderpack pass).
 *
 * <p><b>The fix.</b> A distant entity only occupies a tiny slice of eye-space depth,
 * {@code [zc - slab, zc + slab]}. So its draws are diverted into a private immediate buffer and flushed with:
 * <ol>
 *   <li>a projection whose <b>z row</b> is rebuilt for near/far = that slab (x/y rows untouched, so the
 *       screen-space footprint is bit-identical) — the entity's depth now spans the full NDC range at full
 *       fp32 precision instead of the last few ulps below 1.0;</li>
 *   <li>{@code glDepthRange(wLo, wHi)} set to the exact window-depth band the slab occupies under the
 *       <b>real</b> projection (computed here in double precision).</li>
 * </ol>
 * The fixed-function viewport transform maps the well-spread NDC monotonically into that band, so the entity
 * gets every depth step the band physically has (full internal ordering), sub-step layer pairs collapse into
 * <i>stable</i> ties resolved by draw order, and depth compositing against Voxy LOD terrain, vanilla terrain
 * and other entities is preserved because the band's endpoints are the true depth values of the slab bounds.
 * The modelview is untouched, so fog, lighting and animations are unaffected.
 *
 * <p><b>Modes</b> ({@link DistantEntityDepthMode}). In {@code BASIC} the band is exactly the true one —
 * compositing exact, but a thin slab only holds ~30 depth steps at 32 chunks, so mid-size gaps (limbs, armor
 * plates, ~0.1–0.3 blocks) still sit near one step and can flicker. {@code PRECISE} keeps exact compositing
 * <i>and</i> gets the full 24 bits of depth across the slab by splitting the depth test's two jobs into
 * separate passes (the buffer can't answer both questions at once at these ranges):
 * <ol>
 *   <li><b>Mask pass</b> — the entity is drawn color-masked-off with the exact band: depth-passing fragments
 *       write the true depth <i>and</i> set a stencil bit. The bit is now a pixel-exact "visible here" mask,
 *       decided against terrain at true depth.</li>
 *   <li><b>Depth clear</b> — a stencil-gated fullscreen quad resets depth to 1.0 inside the mask, giving the
 *       entity a private depth region.</li>
 *   <li><b>Fine pass</b> — the entity is drawn again (color on, stencil-clipped to the mask) with the tight
 *       projection across the <b>full 0–1 depth range</b>: 24 bits over a few blocks, essentially perfect
 *       internal ordering.</li>
 *   <li><b>Restore pass</b> — the entity is drawn once more, color off, depth func ALWAYS, with the exact
 *       band, rewriting true depth inside the mask (so water/particles/entities drawn later still composite
 *       correctly) and zeroing the stencil bit.</li>
 * </ol>
 * The stencil bit is {@link #STENCIL_BIT} (bit 7), masked writes only, so Voxy's LOD-region stencil value
 * (bit 0, quirk 41) is untouched; Voxy also re-clears its stencil every frame. The color/depth-func overrides
 * use <b>raw GL</b> deliberately: {@code GlStateManager}'s cache still believes the steady-state values, so
 * the RenderType shards' redundant state sets are skipped and the overrides survive the flush.
 *
 * <p><b>Shaderpacks (Iris).</b> The re-band also applies under an active shaderpack: Iris's patched
 * gbuffer entity programs are {@code ShaderInstance}s whose {@code ProjMat} uniform is uploaded per draw from
 * the <b>live</b> {@code RenderSystem} projection, so the z-row swap reaches them, and written depth values
 * remain valid points on the global depth curve (composite passes that reconstruct position from depth stay
 * coherent). The multi-pass {@code PRECISE} path is <b>not</b> used under a pack: it needs a stencil
 * attachment, and Iris's shared G-buffer depth texture has none (nor a way to request one), while its
 * per-program framebuffer binding hides the real render target from us. {@code PRECISE} therefore degrades
 * to the exact-band single pass there — identical to {@code BASIC}: correct occlusion, mild residual layer
 * shimmer. Iris's shadow pass is orthographic and fails the perspective check, flushing plainly.
 *
 * <p><b>Stand-downs</b> (fall back to the vanilla path / a plain flush, never break rendering): feature or
 * config toggle off; closer than {@link #ENGAGE_DISTANCE}; the glowing-outline buffer source (a wrapper we
 * must not bypass); a non-perspective projection (also covers shadow passes); a bounding box so large the
 * slab isn't "thin" anymore; a degenerate band (entity entirely past the far plane).
 *
 * <p>Render-thread only (called from the {@code LevelRenderer.renderEntity} wrap in
 * {@code MixinLevelRendererDistantEntities}). Lives outside the mixin package per quirk 28.
 */
public final class DistantEntityDepthFix {
    /** Don't engage inside this eye distance (blocks) — vanilla depth still resolves model layers there. */
    private static final double ENGAGE_DISTANCE = 64.0;
    private static final double ENGAGE_DISTANCE_SQ = ENGAGE_DISTANCE * ENGAGE_DISTANCE;
    /** Extra depth slack (blocks) around the bounding box so nameplates, held items and layers fit the band. */
    private static final double SLAB_MARGIN = 3.0;
    /** Stencil bit for the PRECISE visibility mask. Bit 7 — clear of Voxy's LOD-mask bit 0 (quirk 41). */
    private static final int STENCIL_BIT = 0x80;

    /** Private immediate buffer so a re-banded entity's draws never mix into the frame's shared batches. */
    private static ByteBufferBuilder backingBuffer = new ByteBufferBuilder(1536);
    private static MultiBufferSource.BufferSource BUFFER = MultiBufferSource.immediate(backingBuffer);

    private static final Matrix4f SHARP_PROJ = new Matrix4f(); // render-thread scratch
    private static final Matrix4f QUAD_PROJ = new Matrix4f();  // render-thread scratch
    private static final Matrix4f EYE_TRANSFORM = new Matrix4f();

    private static boolean diagPreciseFallback; // render-thread only

    private DistantEntityDepthFix() {}

    /** The private buffer source a qualifying entity should be rendered into (via the {@code build} runnable). */
    public static MultiBufferSource.BufferSource buffer() {
        return BUFFER;
    }

    /**
     * Called once per client tick (from {@link DistantEntityTicker}), <b>between</b> frames: the PRECISE
     * multi-pass needs a stencil attachment on the main render target — MC has none by default, and without
     * one the stencil test silently always-passes, so the "gated" depth-clear quad would wipe the whole
     * frame's depth (entities then x-ray through everything). Enabling it recreates the framebuffer, which is
     * only safe outside the frame; until it's on, {@link #render} stays on the single-pass fallback.
     */
    public static void ensureStencil() {
        if (VSSClientConfig.CONFIG.distantEntityDepthMode != DistantEntityDepthMode.PRECISE || shaderPackInUse()) {
            return;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (main != null && !main.isStencilEnabled()) {
            main.enableStencil();
            VSSLogger.info("Boxy: enabled main-framebuffer stencil for the PRECISE distant-entity depth fix");
        }
    }

    /**
     * Whether this entity's draws should be diverted for a re-banded render. Hot path (per entity per frame):
     * guards are ordered cheapest-first, feature gate first (hot-path rule (b) in the developer guide).
     */
    public static boolean applies(Entity entity, double x, double y, double z, MultiBufferSource bufferSource) {
        if (VSSClientConfig.CONFIG.distantEntityDepthMode == DistantEntityDepthMode.OFF
                || !ClientEntitySync.enabled()) {
            return false;
        }
        if (x * x + y * y + z * z < ENGAGE_DISTANCE_SQ) {
            return false;
        }
        // The glowing path passes an OutlineBufferSource whose wrapping we must not bypass. Any other
        // source (vanilla's BufferSource, Iris's wrapper under a shaderpack) is fine to substitute.
        if (bufferSource instanceof OutlineBufferSource) {
            return false;
        }
        return TrackedEntityTypes.clientContains(entity.getType());
    }

    /**
     * Renders one qualifying entity with re-banded depth. {@code build} submits the entity's geometry into
     * {@link #buffer()} (the mixin wraps the original {@code EntityRenderDispatcher.render} call) and may be
     * run several times in PRECISE mode. {@code (x, y, z)} is the camera-relative render position. Any
     * bail-out still builds and flushes once plainly, so the entity always renders.
     */
    public static void render(Entity entity, double x, double y, double z, PoseStack poseStack, Runnable build) {
        DepthRenderState state = new DepthRenderState();
        var shader = RenderSystem.getShader();
        int program = GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
        try {
            renderBanded(entity, x, y, z, poseStack, build, state);
        } catch (RuntimeException | Error failure) {
            backingBuffer.close();
            backingBuffer = new ByteBufferBuilder(1536);
            BUFFER = MultiBufferSource.immediate(backingBuffer);
            throw failure;
        } finally {
            state.restore();
            RenderSystem.setShader(() -> shader);
            org.lwjgl.opengl.GL20.glUseProgram(program);
        }
    }

    private static void renderBanded(Entity entity, double x, double y, double z, PoseStack poseStack,
            Runnable build, DepthRenderState state) {
        Matrix4f proj = RenderSystem.getProjectionMatrix();
        boolean zeroToOne = GL11.glGetInteger(org.lwjgl.opengl.GL45.GL_CLIP_DEPTH_MODE) == org.lwjgl.opengl.GL45.GL_ZERO_TO_ONE;
        // Re-bandable only under a standard perspective projection: clip w must be -z_eye (w row = 0,0,-1,0).
        boolean perspective = Math.abs(proj.m23() + 1.0f) < 1.0e-4f && Math.abs(proj.m33()) < 1.0e-4f
                && Math.abs(proj.m03()) < 1.0e-4f && Math.abs(proj.m13()) < 1.0e-4f;
        // In 1.21.1 the camera rotation lives in RenderSystem's model-view, not the entity PoseStack.
        Matrix4f pose = EYE_TRANSFORM.set(RenderSystem.getModelViewMatrix()).mul(poseStack.last().pose());
        double zc = -(pose.m02() * x + pose.m12() * y + pose.m22() * z + pose.m32());
        double slab = Math.max(entity.getBbWidth(), entity.getBbHeight()) + SLAB_MARGIN;
        if (!perspective || proj.m32() >= 0 || state.range[0] != 0.0 || state.range[1] != 1.0
                || zc < ENGAGE_DISTANCE || slab > zc / 3.0) {
            build.run();
            BUFFER.endBatch();
            return;
        }
        double zLo = zc - slab;
        double zHi = zc + slab;
        boolean precise = VSSClientConfig.CONFIG.distantEntityDepthMode == DistantEntityDepthMode.PRECISE;
        // Multi-pass requires a real stencil attachment (see ensureStencil) — without one the stencil test
        // always-passes and the depth-clear quad would destroy the frame's depth buffer (quirk 45). Under a
        // shaderpack there is no stencil to be had (Iris's G-buffer depth has no stencil attachment and its
        // per-program framebuffer binding hides the real target), so PRECISE degrades to the exact-band
        // single pass there — same as BASIC: correct occlusion, mild residual layer shimmer.
        boolean multipass = precise
                && Minecraft.getInstance().getMainRenderTarget().isStencilEnabled()
                && !GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
                && !shaderPackInUse();
        if (precise && !multipass && !diagPreciseFallback && shaderPackInUse()) {
            diagPreciseFallback = true;
            VSSLogger.info("Boxy: PRECISE distant-entity depth uses the exact-band single pass under shaderpacks "
                    + "(the multi-pass needs a stencil buffer the shader pipeline does not provide)");
        }
        // Window-space band the slab occupies under the real projection, in double precision.
        double wLo = windowDepth(proj, zLo, zeroToOne);
        double wHi = Math.min(windowDepth(proj, zHi, zeroToOne), 1.0); // the band may reach past the far plane
        if (wLo < 0.0 || wHi - wLo < 1.0e-9) {
            build.run();
            BUFFER.endBatch();
            return;
        }
        // Same projection with the z row rebuilt for near/far = the slab. X/Y rows untouched.
        Matrix4f tight = SHARP_PROJ.set(proj);
        tight.m22((float) (-(zeroToOne ? zHi : zHi + zLo) / (zHi - zLo)));
        tight.m32((float) (-(zeroToOne ? 1.0 : 2.0) * zHi * zLo / (zHi - zLo)));
        VertexSorting sorting = RenderSystem.getVertexSorting();

        if (!multipass) {
            flushBanded(build, tight, proj, sorting, wLo, wHi);
            TrackedEntityTypes.diagSharpDepth(entity.getType());
            return;
        }

        // PRECISE multi-pass (see class doc). Color/depth-func overrides are raw GL on purpose:
        // GlStateManager's cache keeps the steady-state values, so the RenderType shards' redundant sets are
        // skipped during endBatch and the overrides hold. The finally block restores that steady state.
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        try {
            // Bit 7 belongs to this pass; do not reuse mask pixels left by an earlier entity/frame.
            int previousClear = GL11.glGetInteger(GL11.GL_STENCIL_CLEAR_VALUE);
            GL11.glStencilMask(STENCIL_BIT);
            GL11.glClearStencil(0);
            GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
            GL11.glClearStencil(previousClear);
            // 1. Mask pass: true-band depth + stencil bit where the depth test passes (color off).
            GL11.glColorMask(false, false, false, false);
            GL11.glStencilFunc(GL11.GL_ALWAYS, STENCIL_BIT, STENCIL_BIT);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
            GL11.glStencilMask(STENCIL_BIT);
            flushBanded(build, tight, proj, sorting, wLo, wHi);

            // 2. Depth clear inside the mask (stencil early-out makes the fullscreen quad cheap).
            GL11.glStencilFunc(GL11.GL_EQUAL, STENCIL_BIT, STENCIL_BIT);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
            GL11.glDepthFunc(GL11.GL_ALWAYS);
            drawFullscreenDepthQuad(proj, sorting);
            GL11.glDepthFunc(GL11.GL_LEQUAL);

            // 3. Fine pass: the visible render, 24 bits of depth across the slab, clipped to the mask.
            GL11.glColorMask(true, true, true, true);
            flushBanded(build, tight, proj, sorting, 0.0, 1.0);

            // 4. Restore pass: rewrite true depth inside the mask (color off, depth ALWAYS) and zero the
            //    stencil bit (GL_ZERO under the masked write is idempotent per fragment, unlike INVERT).
            GL11.glColorMask(false, false, false, false);
            GL11.glDepthFunc(GL11.GL_ALWAYS);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_ZERO);
            flushBanded(build, tight, proj, sorting, wLo, wHi);
        } finally {
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glStencilMask(0xFF);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
        }
        TrackedEntityTypes.diagSharpDepth(entity.getType());
    }

    /** Immediate sources can flush when a renderer changes RenderType, so band the build as well as the final flush. */
    private static void flushBanded(Runnable build, Matrix4f bandProj, Matrix4f restoreProj, VertexSorting sorting,
            double wLo, double wHi) {
        RenderSystem.setProjectionMatrix(bandProj, sorting);
        GL11.glDepthRange(wLo, wHi);
        try {
            build.run();
            BUFFER.endBatch();
        } finally {
            GL11.glDepthRange(0.0, 1.0);
            RenderSystem.setProjectionMatrix(restoreProj, sorting);
        }
    }

    /** Draws a fullscreen quad at window depth 1.0 (caller sets depth func/stencil; color is masked off). */
    private static void drawFullscreenDepthQuad(Matrix4f restoreProj, VertexSorting sorting) {
        RenderSystem.setProjectionMatrix(QUAD_PROJ.identity(), sorting);
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShader(GameRenderer::getPositionShader);
        try {
            BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            quad.addVertex(-1.0f, -1.0f, 1.0f);
            quad.addVertex(1.0f, -1.0f, 1.0f);
            quad.addVertex(1.0f, 1.0f, 1.0f);
            quad.addVertex(-1.0f, 1.0f, 1.0f);
            BufferUploader.drawWithShader(quad.buildOrThrow());
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(restoreProj, sorting);
        }
    }

    /** Window-space depth ([0,1], depth range aside) of an on-axis point at eye distance {@code zEye}. */
    private static double windowDepth(Matrix4f proj, double zEye, boolean zeroToOne) {
        double zClip = (double) proj.m22() * -zEye + proj.m32();
        return zeroToOne ? zClip / zEye : 0.5 * (zClip / zEye) + 0.5;
    }

    private static boolean shaderPackInUse() {
        return me.cortex.voxy.client.core.util.IrisUtil.irisShaderPackEnabled();
    }
}
