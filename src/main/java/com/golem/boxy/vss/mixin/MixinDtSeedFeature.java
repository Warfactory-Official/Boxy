package com.golem.boxy.vss.mixin;

import com.dtteam.dynamictrees.api.worldgen.GroundFinder;
import com.dtteam.dynamictrees.api.worldgen.LevelContext;
import com.golem.boxy.seedlod.decor.CaptureLevel;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;

/** Dynamic Trees worldgen inside seed-LOD decoration: {@link CaptureLevel} has no {@link Level}; null level => overworld ground finder. */
@Mixin(targets = "com.dtteam.dynamictrees.worldgen.feature.DynamicTreeFeature", remap = false)
public class MixinDtSeedFeature {

    @WrapOperation(method = "place", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
            target = "Lcom/dtteam/dynamictrees/api/worldgen/LevelContext;create(Lnet/minecraft/world/level/LevelAccessor;)Lcom/dtteam/dynamictrees/api/worldgen/LevelContext;"), require = 0)
    private LevelContext boxy$captureContext(LevelAccessor accessor, Operation<LevelContext> original) {
        if (accessor instanceof CaptureLevel capture) {
            return new LevelContext(capture.dimension(), capture.getSeed(), accessor, null);
        }
        return original.call(accessor);
    }

    @WrapOperation(method = "generateTrees", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
            target = "Lcom/dtteam/dynamictrees/api/worldgen/GroundFinder;getGroundFinder(Lnet/minecraft/world/level/Level;)Lcom/dtteam/dynamictrees/api/worldgen/GroundFinder;"), require = 0)
    private GroundFinder boxy$groundFinder(Level level, Operation<GroundFinder> original) {
        return level == null ? GroundFinder.OVERWORLD : original.call(level);
    }
}
