package com.golem.boxy.vss.client;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

/** Raw state touched by Boxy's depth passes. Restore without changing Minecraft's cached state. */
final class DepthRenderState {
    private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    private final boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
    private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
    private final boolean[] color = new boolean[4];
    final double[] range = new double[2];
    private final int[] front = new int[7];
    private final int[] back = new int[7];

    DepthRenderState() {
        try (var stack = MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < 4; i++) color[i] = mask.get(i) != 0;
        }
        GL11.glGetDoublev(GL11.GL_DEPTH_RANGE, range);
        int[] frontKeys = {GL11.GL_STENCIL_FUNC, GL11.GL_STENCIL_REF, GL11.GL_STENCIL_VALUE_MASK,
                GL11.GL_STENCIL_WRITEMASK, GL11.GL_STENCIL_FAIL, GL11.GL_STENCIL_PASS_DEPTH_FAIL, GL11.GL_STENCIL_PASS_DEPTH_PASS};
        int[] backKeys = {GL20.GL_STENCIL_BACK_FUNC, GL20.GL_STENCIL_BACK_REF, GL20.GL_STENCIL_BACK_VALUE_MASK,
                GL20.GL_STENCIL_BACK_WRITEMASK, GL20.GL_STENCIL_BACK_FAIL, GL20.GL_STENCIL_BACK_PASS_DEPTH_FAIL, GL20.GL_STENCIL_BACK_PASS_DEPTH_PASS};
        for (int i = 0; i < 7; i++) {
            front[i] = GL11.glGetInteger(frontKeys[i]);
            back[i] = GL11.glGetInteger(backKeys[i]);
        }
    }

    void restore() {
        if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
        if (stencil) GL11.glEnable(GL11.GL_STENCIL_TEST); else GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDepthMask(depthMask);
        GL11.glDepthFunc(depthFunc);
        GL11.glDepthRange(range[0], range[1]);
        GL11.glColorMask(color[0], color[1], color[2], color[3]);
        // RenderType shards may have changed Minecraft's caches during a private-buffer flush.
        if (depth) com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        else com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        com.mojang.blaze3d.systems.RenderSystem.depthMask(depthMask);
        com.mojang.blaze3d.systems.RenderSystem.depthFunc(depthFunc);
        com.mojang.blaze3d.systems.RenderSystem.colorMask(color[0], color[1], color[2], color[3]);
        GL20.glStencilFuncSeparate(GL11.GL_FRONT, front[0], front[1], front[2]);
        GL20.glStencilMaskSeparate(GL11.GL_FRONT, front[3]);
        GL20.glStencilOpSeparate(GL11.GL_FRONT, front[4], front[5], front[6]);
        GL20.glStencilFuncSeparate(GL11.GL_BACK, back[0], back[1], back[2]);
        GL20.glStencilMaskSeparate(GL11.GL_BACK, back[3]);
        GL20.glStencilOpSeparate(GL11.GL_BACK, back[4], back[5], back[6]);
    }
}
