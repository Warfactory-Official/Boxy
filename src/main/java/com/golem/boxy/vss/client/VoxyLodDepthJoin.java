package com.golem.boxy.vss.client;

import com.golem.boxy.vss.common.VSSLogger;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import java.lang.reflect.Field;

/**
 * Joins Voxy's LOD depth into the depth buffer entities are tested against, <b>under Oculus shaderpacks</b>.
 *
 * <p>On the vanilla pipeline Voxy's {@code NormalRenderPipeline.finish} always reprojects its LOD depth into
 * MC's depth buffer, so distant entities depth-test against LOD hills correctly. On the Iris pipeline that
 * blit is conditional — the shaderpack's voxy patch can set {@code excludeLodsFromVanillaDepth}, and a
 * resolution-scale mismatch silently skips it — so on many packs the depth buffer never learns LOD depth and
 * distant tracked entities x-ray through LOD terrain (quirk 46; an issue that predates the depth re-band).
 *
 * <p>This helper performs the same reprojection Voxy's own {@code blit_texture_depth_cutout.frag} does
 * (sample Voxy depth → unproject by Voxy's inverse MVP → reproject with {@code vanillaProjection × modelView}
 * → clamp just below 1.0 → {@code gl_FragDepth}), as a fullscreen triangle drawn with a tiny <b>raw-GL</b>
 * program: color writes off, depth func LEQUAL — a min-join, so it is idempotent if Voxy already blitted.
 * Raw GL keeps it invisible to Iris (no {@code RenderSystem} shader involvement) and leaves
 * {@code GlStateManager}'s caches truthful (every touched binding is saved/restored raw). Runs at most once
 * per frame (deduped on {@code Viewport.frameId}), lazily from {@code DistantEntityDepthFix} when the first
 * distant entity of the frame renders under an active pack; skips silently when Voxy's renderer is absent or
 * on the normal (non-Iris) pipeline. Voxy internals are reached directly (Boxy compiles against the dev jar;
 * only the {@code VoxyRenderSystem.pipeline} field is private → one cached reflective read). Any failure
 * throws to the caller, which disables the join for the session — degrading to the old behaviour, never
 * breaking rendering.
 */
public final class VoxyLodDepthJoin {
    private static Field pipelineField;
    private static int program = -1; // -1 = not built yet, 0 = build failed (disabled)
    private static int uInvMvp;
    private static int uReMvp;
    private static int vao;
    private static int sampler;
    private static int lastJoinedFrame = Integer.MIN_VALUE;
    private static boolean diagLogged;
    private static final float[] MAT_SCRATCH = new float[16];

    private VoxyLodDepthJoin() {}

    /** Render thread only. Throws on any unexpected failure — the caller disables the join for the session. */
    public static void joinIfNeeded() throws Exception {
        VoxyRenderSystem rs = IGetVoxyRenderSystem.getNullable();
        if (rs == null) {
            return;
        }
        Viewport<?> viewport = rs.getViewport();
        if (viewport == null || viewport.frameId == lastJoinedFrame) {
            return;
        }
        if (pipelineField == null) {
            Field f = VoxyRenderSystem.class.getDeclaredField("pipeline");
            f.setAccessible(true);
            pipelineField = f;
        }
        if (!(pipelineField.get(rs) instanceof IrisVoxyRenderPipeline iris)) {
            lastJoinedFrame = viewport.frameId; // normal pipeline already joins its depth itself
            return;
        }
        ensureGlObjects();
        if (program == 0) {
            return;
        }
        lastJoinedFrame = viewport.frameId;
        // fbTranslucent holds the opaque LOD depth snapshot (Voxy's own finish blits from it too).
        int depthTex = iris.fbTranslucent.getDepthTex().id;
        Matrix4f invMvp = new Matrix4f(viewport.MVP).invert();
        Matrix4f reMvp = new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView);

        // Save every binding we touch (raw, so GlStateManager's caches stay in sync with reality).
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevSampler = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        try {
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            GL33.glBindSampler(0, sampler); // NEAREST, no depth-compare — the texture's own params may be unsampleable
            GL20.glUniformMatrix4fv(uInvMvp, false, invMvp.get(MAT_SCRATCH));
            GL20.glUniformMatrix4fv(uReMvp, false, reMvp.get(MAT_SCRATCH));
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL); // min-join
            GL11.glColorMask(false, false, false, false);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } finally {
            GL11.glColorMask(true, true, true, true);
            GL33.glBindSampler(0, prevSampler);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            GL13.glActiveTexture(prevActive);
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
        }
        if (!diagLogged) {
            diagLogged = true;
            VSSLogger.info("Boxy: joining Voxy LOD depth into the shader depth buffer (distant-entity occlusion under shaderpacks)");
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
        // Mirrors Voxy's blit_texture_depth_cutout.frag depth path exactly.
        int fs = compile(GL20.GL_FRAGMENT_SHADER, """
                #version 330 core
                uniform sampler2D depthTex;
                uniform mat4 invMvp;
                uniform mat4 reMvp;
                in vec2 UV;
                void main() {
                    float d = texture(depthTex, UV).r;
                    if (d == 0.0f || d == 1.0f) {
                        discard;
                    }
                    vec4 view = invMvp * vec4(vec3(UV, d) * 2.0f - 1.0f, 1.0f);
                    view /= view.w;
                    vec4 clip = reMvp * vec4(view.xyz, 1.0f);
                    float nd = min(1.0f - (2.0f / float((1 << 24) - 1)), clip.z / clip.w);
                    gl_FragDepth = clamp(nd * 0.5f + 0.5f, 0.0f, 1.0f);
                }
                """);
        if (vs == 0 || fs == 0) {
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
