package com.golem.boxy.vss.mixin;

import com.golem.boxy.seedlod.fill.VoxyDemand;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.world.WorldEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Seed LODs write coarse Voxy nodes (levels 1-4) that have no children yet. When Voxy wants to split one, its
 * {@code makeLeafChildRequest} logs "existence mask of 0", invalidates the node and reloads it, then asks again
 * next frame. Real data never produces such nodes, so while seed LODs run this declines the request quietly
 * (no warning, no reload) and tells the fill where finer detail is wanted ({@link VoxyDemand}). Loading such a
 * node as a child is fine for Voxy (it becomes a leaf) but logs "child existence of 0" each time; that one line
 * is dropped while seed LODs run.
 */
@Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.NodeManager", remap = false)
public abstract class MixinVoxySeedLeafRequest {
    @Shadow @Final private me.cortex.voxy.client.core.rendering.hierachical.NodeStore nodeData;
    @Shadow @Final private LongOpenHashSet topLevelNodes;

    @Inject(method = "makeLeafChildRequest", at = @At("HEAD"), cancellable = true)
    private void boxy$seedLeafWantsDetail(int nodeId, CallbackInfo ci) {
        if (!VoxyDemand.listening()) {
            return;
        }
        long position = nodeData.nodePosition(nodeId);
        if (nodeData.getNodeChildExistence(nodeId) == 0 && !topLevelNodes.contains(position) && WorldEngine.getLevel(position) > 0) {
            VoxyDemand.wanted(position);
            nodeData.unmarkRequestInFlight(nodeId);
            ci.cancel();
        }
    }

    @Redirect(method = "finishRequest(ILme/cortex/voxy/client/core/rendering/hierachical/NodeChildRequest;)V", at = @At(value = "INVOKE", target = "Lme/cortex/voxy/common/Logger;warn([Ljava/lang/Object;)V"))
    private void boxy$quietSeedLeafChild(Object[] message) {
        if (!VoxyDemand.listening()) {
            me.cortex.voxy.common.Logger.warn(message);
        }
    }
}
