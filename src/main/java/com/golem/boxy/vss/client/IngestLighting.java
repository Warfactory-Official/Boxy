package com.golem.boxy.vss.client;

import com.golem.boxy.vss.config.VSSClientConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;

public final class IngestLighting {
    private IngestLighting() {}

    public static DataLayer skyLight(LayerLightEventListener listener, SectionPos pos) {
        DataLayer layer = listener.getDataLayerData(pos);
        if ((layer != null && !layer.isEmpty()) || !VSSClientConfig.CONFIG.fillUniformSkyLight) return layer;
        for (int dx = 0; dx <= 15; dx += 15) {
            for (int dy = 0; dy <= 15; dy += 15) {
                for (int dz = 0; dz <= 15; dz += 15) {
                    if (listener.getLightValue(new BlockPos(pos.minBlockX() + dx, pos.minBlockY() + dy, pos.minBlockZ() + dz)) == 15) {
                        return new DataLayer(15);
                    }
                }
            }
        }
        return layer;
    }
}
