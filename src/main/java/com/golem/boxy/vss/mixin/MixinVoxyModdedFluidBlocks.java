package com.golem.boxy.vss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;

/**
 * Lets Voxy bake fluid blocks that do not extend {@link LiquidBlock}.
 *
 * <p><b>The bug.</b> Voxy decides "is this a fluid" with {@code block instanceof LiquidBlock}. Any other block with a
 * fluid state is taken for a waterlogged block, and {@code ModelFactory.addEntry} first bakes that fluid's
 * {@code createLegacyBlock()}. For a modded fluid block (Golem Core's {@code FluidBlock}, used by HBM's fluids) the
 * legacy block is the same block again, so {@code addEntry} recurses until a StackOverflowError kills the render
 * worker thread and its LOD sections never mesh.
 *
 * <p><b>The fix.</b> Every Voxy {@code instanceof LiquidBlock} check also accepts a block whose fluid's legacy block
 * is itself, so such blocks take Voxy's fluid path (fluid renderer bake, no fluid LUT) exactly like vanilla water.
 * Waterlogged blocks are unaffected: their fluid's legacy block is water, not themselves.
 *
 * <p>Targeted by string ({@code remap = false}, Voxy classes); {@code require = 0} so each check degrades to Voxy's
 * original behaviour if a method changes.
 */
@Mixin(targets = {
    "me.cortex.voxy.client.core.model.ModelFactory",
    "me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery"
}, remap = false)
public class MixinVoxyModdedFluidBlocks {

    @WrapOperation(method = {"addEntry", "processTextureBakeResult", "renderToOutput"},
                   constant = @Constant(classValue = LiquidBlock.class), require = 0)
    private boolean boxy$moddedFluidBlock(Object block, Operation<Boolean> original) {
        if (original.call(block)) {
            return true;
        }
        if (!(block instanceof Block b)) {
            return false;
        }
        FluidState fluid = b.defaultBlockState().getFluidState();
        return !fluid.isEmpty() && fluid.createLegacyBlock().getBlock() == b;
    }
}
