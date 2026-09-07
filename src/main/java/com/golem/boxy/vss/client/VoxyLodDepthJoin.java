package com.golem.boxy.vss.client;

import com.golem.boxy.vss.common.VSSLogger;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * Joins current-frame LOD depth into the terrain framebuffer before tracked entities, with or without Iris.
 *
 * <p>A zero-to-one projection matrix does not imply a zero-to-one stored clip convention: OpenGL's
 * clip-depth mode determines the window-depth mapping. Capture that mode at the producer rather than
 * inferring it from Voxy's RenderProperties. Otherwise the inverse MVP can place a hill behind an entity
 * that is actually behind the hill. The native final blit can suffer the same mismatch at long distances.
 *
 * <p>The producer hook supplies the pipeline, viewport and destination framebuffer without reflection.
 * Only depth captured during this renderLevel invocation is eligible. Reprojection runs before any
 * precision re-banding and independently of the Off/Basic/Precise setting. All touched GL state is restored.
 */
public final class VoxyLodDepthJoin {
    private static AbstractRenderPipeline sourcePipeline;
    private static Viewport<?> sourceViewport;
    private static boolean sourceZeroOne;
    private static int targetFramebuffer;
    private static int targetWidth;
    private static int targetHeight;
    private static int program = -1; // -1 = not built yet, 0 = build failed (disabled)
    private static int uInvMvp;
    private static int uReMvp;
    private static int uSourceZeroOne;
    private static int uTargetZeroOne;
    private static int uTargetReverse;
    private static int vao;
    private static int sampler;
    private static boolean joined;
    private static boolean diagLogged;
    private static boolean warned;
    private static final float[] MAT_SCRATCH = new float[16];

    private VoxyLodDepthJoin() {}

    public static void beginFrame() {
        joined = false;
        sourcePipeline = null;
        sourceViewport = null;
    }

    public static void capture(AbstractRenderPipeline pipeline, Viewport<?> viewport, int framebuffer, int width, int height) {
        if (me.cortex.voxy.client.core.util.IrisUtil.irisShadowActive()) return;
        if (pipeline instanceof NormalRenderPipeline) {
            var renderer = IGetVoxyRenderSystem.getNullable();
            float fogEnd = renderer == null ? RenderSystem.getShaderFogEnd() : renderer.getCapturedFogEnd();
            // The native normal pipeline skips its colour composite too in this case.
            if (fogEnd < net.minecraft.client.Minecraft.getInstance().gameRenderer.getRenderDistance()) return;
        }
        sourcePipeline = pipeline;
        sourceViewport = viewport;
        sourceZeroOne = GL11.glGetInteger(org.lwjgl.opengl.GL45.GL_CLIP_DEPTH_MODE) == org.lwjgl.opengl.GL45.GL_ZERO_TO_ONE;
        targetFramebuffer = framebuffer;
        targetWidth = width;
        targetHeight = height;
        joined = false;
    }

    /** Called before a tracked entity, while its original projection is still active. */
    public static void joinIfNeeded() {
        if (sourcePipeline == null || sourceViewport == null || joined
                || me.cortex.voxy.client.core.util.IrisUtil.irisShadowActive()) return;
        joined = true;
        try {
            joinDepth();
        } catch (RuntimeException | LinkageError failure) {
            if (!warned) {
                warned = true;
                VSSLogger.warn("Boxy: unable to join LOD depth for entity occlusion", failure);
            }
        }
    }

    private static void joinDepth() {
        Viewport<?> viewport = sourceViewport;
        ensureGlObjects();
        if (program == 0) {
            return;
        }
        int depthTex = sourcePipeline instanceof IrisVoxyRenderPipeline iris
                ? iris.fbTranslucent.getDepthTex().id : sourcePipeline.fb.getDepthTex().id;
        Matrix4f invMvp = new Matrix4f(viewport.MVP).invert();
        Matrix4f reMvp = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix());

        // Save every binding we touch (raw, so GlStateManager's caches stay in sync with reality).
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] prevViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevSampler = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        DepthRenderState depthState = new DepthRenderState();
        try {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, targetFramebuffer);
            GL11.glViewport(0, 0, targetWidth, targetHeight);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            GL33.glBindSampler(0, sampler); // NEAREST, no depth-compare — the texture's own params may be unsampleable
            GL20.glUniformMatrix4fv(uInvMvp, false, invMvp.get(MAT_SCRATCH));
            GL20.glUniformMatrix4fv(uReMvp, false, reMvp.get(MAT_SCRATCH));
            // Projection convention is not storage convention: GL maps clip depth into the texture.
            GL20.glUniform1i(uSourceZeroOne, sourceZeroOne ? 1 : 0);
            GL20.glUniform1i(uTargetZeroOne, GL11.glGetInteger(org.lwjgl.opengl.GL45.GL_CLIP_DEPTH_MODE) == org.lwjgl.opengl.GL45.GL_ZERO_TO_ONE ? 1 : 0);
            boolean reverse = RenderSystem.getProjectionMatrix().m32() > 0;
            GL20.glUniform1i(uTargetReverse, reverse ? 1 : 0);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDepthFunc(reverse ? GL11.GL_GEQUAL : GL11.GL_LEQUAL);
            GL11.glColorMask(false, false, false, false);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } finally {
            depthState.restore();
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevFramebuffer);
            GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            GL33.glBindSampler(0, prevSampler);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            GL13.glActiveTexture(prevActive);
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
        }
        if (!diagLogged) {
            diagLogged = true;
            VSSLogger.info("Boxy: joining current-frame Voxy depth for distant-entity occlusion");
        }
    }

    private static void ensureGlObjects() {
        if (program != -1) {
            return;
        }
        program = 0; // stays 0 (disabled) unless everything below succeeds
        int vs = compile(GL20.GL_VERTEX_SHADER, """
                #version 330 core
                out vec2 UV;
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    UV = p;
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                }
                """);
        // Unproject using the producer's actual GL mapping, then project for the entity render pass.
        int fs = compile(GL20.GL_FRAGMENT_SHADER, """
                #version 330 core
                uniform sampler2D depthTex;
                uniform mat4 invMvp;
                uniform mat4 reMvp;
                uniform bool sourceZeroOne;
                uniform bool targetZeroOne;
                uniform bool targetReverse;
                in vec2 UV;
                void main() {
                    float d = texture(depthTex, UV).r;
                    if (d == 0.0f || d == 1.0f) {
                        discard;
                    }
                    vec4 view = invMvp * vec4(UV * 2.0 - 1.0, sourceZeroOne ? d : d * 2.0 - 1.0, 1.0);
                    view /= view.w;
                    vec4 clip = reMvp * vec4(view.xyz, 1.0f);
                    float nd = clip.z / clip.w;
                    float depth = targetZeroOne ? nd : nd * 0.5 + 0.5;
                    float epsilon = 1.0 / float((1 << 24) - 1);
                    depth = targetReverse ? max(epsilon, depth) : min(1.0 - epsilon, depth);
                    gl_FragDepth = gl_DepthRange.near + gl_DepthRange.diff * clamp(depth, 0.0, 1.0);
                }
                """);
        if (vs == 0 || fs == 0) {
            if (vs != 0) GL20.glDeleteShader(vs);
            if (fs != 0) GL20.glDeleteShader(fs);
            return;
        }
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            VSSLogger.warn("Boxy: LOD depth-join program failed to link: " + GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return;
        }
        uInvMvp = GL20.glGetUniformLocation(prog, "invMvp");
        uReMvp = GL20.glGetUniformLocation(prog, "reMvp");
        uSourceZeroOne = GL20.glGetUniformLocation(prog, "sourceZeroOne");
        uTargetZeroOne = GL20.glGetUniformLocation(prog, "targetZeroOne");
        uTargetReverse = GL20.glGetUniformLocation(prog, "targetReverse");
        int uTex = GL20.glGetUniformLocation(prog, "depthTex");
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(prog);
        GL20.glUniform1i(uTex, 0);
        GL20.glUseProgram(prevProgram);
        vao = GL30.glGenVertexArrays();
        sampler = GL33.glGenSamplers();
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL33.glSamplerParameteri(sampler, GL30.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        program = prog;
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            VSSLogger.warn("Boxy: LOD depth-join shader failed to compile: " + GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
