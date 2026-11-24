package com.golem.boxy.vss.mixin;

import com.golem.boxy.lod.dt.DtLod;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dynamic Trees branches: Voxy maps one block state per voxel, so a branch bakes once, unconnected. Each branch voxel
 * is re-mapped to a state carrying its connections ({@code DtLod}).
 */
@Mixin(targets = "me.cortex.voxy.common.voxelization.WorldConversionFactory", remap = false)
public class MixinVoxyDtConvert {

    @Inject(method = "convert(Lme/cortex/voxy/common/voxelization/VoxelizedSection;Lme/cortex/voxy/common/world/other/Mapper;"
            + "Lnet/minecraft/world/level/chunk/PalettedContainer;Lnet/minecraft/world/level/chunk/PalettedContainerRO;"
            + "Lme/cortex/voxy/common/voxelization/ILightingSupplier;ZJ)Lme/cortex/voxy/common/voxelization/VoxelizedSection;",
            at = @At("RETURN"), require = 0)
    private static void boxy$connectBranches(VoxelizedSection section, Mapper mapper,
                                             PalettedContainer<BlockState> blocks,
                                             PalettedContainerRO<Holder<Biome>> biomes,
                                             @org.spongepowered.asm.mixin.injection.Coerce Object lighting,
                                             boolean zoom, long seed, CallbackInfoReturnable<VoxelizedSection> cir) {
        DtLod.converted(cir.getReturnValue(), mapper, blocks);
    }
}
