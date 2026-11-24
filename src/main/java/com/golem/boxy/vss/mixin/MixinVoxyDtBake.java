package com.golem.boxy.vss.mixin;

import com.golem.boxy.lod.dt.ConnectedBranchState;
import com.golem.boxy.lod.dt.DtLod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** Bakes a connected branch with its base state's model, fed the connections as model data. */
@Mixin(targets = "me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery", remap = false)
public class MixinVoxyDtBake {

    @WrapOperation(method = "bakeBlockModel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/BlockModelShaper;getBlockModel(Lnet/minecraft/world/level/block/state/BlockState;)"
                    + "Lnet/minecraft/client/resources/model/BakedModel;"), require = 0)
    private BakedModel boxy$baseModel(BlockModelShaper shaper, BlockState state, Operation<BakedModel> original) {
        return original.call(shaper, ConnectedBranchState.baseOf(state));
    }

    @WrapOperation(method = "bakeBlockModel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/model/BakedModel;getQuads(Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;)Ljava/util/List;"), require = 0)
    private List<BakedQuad> boxy$connectedQuads(BakedModel model, BlockState modelState, Direction side, RandomSource random,
                                                Operation<List<BakedQuad>> original, @Local(argsOnly = true) BlockState state) {
        if (state instanceof ConnectedBranchState connected && DtLod.enabled()) {
            return DtLod.quads(model, connected, side, random);
        }
        return original.call(model, modelState, side, random);
    }
}
