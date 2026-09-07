package com.golem.boxy.vss.client;

import com.golem.boxy.vss.config.DistantEntityDepthMode;
import com.golem.boxy.vss.config.VSSClientConfig;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Native Sodium configuration API. Discovered only by Sodium on a client. */
@ConfigEntryPointForge("boxy")
public final class BoxyConfigPage implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var cfg = VSSClientConfig.CONFIG;
        StorageEventHandler storage = cfg::saveClamped;
        var page = builder.createOptionPage().setName(Component.literal("Boxy"));
        page.addOptionGroup(builder.createOptionGroup().setName(Component.literal("Server Terrain"))
                .addOption(builder.createBooleanOption(ResourceLocation.parse("boxy:receive_server_lods"))
                        .setName(Component.literal("Receive server LODs"))
                        .setTooltip(Component.literal("Download distant terrain from Boxy servers. Entity synchronization remains active. Reconnect after changing this setting."))
                        .setBinding(v -> cfg.receiveServerLods = v, () -> cfg.receiveServerLods)
                        .setDefaultValue(true).setStorageHandler(storage))
                .addOption(builder.createIntegerOption(ResourceLocation.parse("boxy:lod_distance"))
                        .setName(Component.literal("Client LOD distance"))
                        .setTooltip(Component.literal("Distance in chunks, limited by Voxy and the server. Zero uses the server limit."))
                        .setRange(0, 512, 1).setDefaultValue(0)
                        .setValueFormatter(v -> Component.literal(v == 0 ? "Server" : v + " chunks"))
                        .setBinding(v -> cfg.lodDistanceChunks = v, () -> cfg.lodDistanceChunks).setStorageHandler(storage))
                .addOption(builder.createBooleanOption(ResourceLocation.parse("boxy:off_thread_processing"))
                        .setName(Component.literal("Off-thread LOD processing"))
                        .setTooltip(Component.literal("Deserialize received terrain on a background thread."))
                        .setBinding(v -> cfg.offThreadSectionProcessing = v, () -> cfg.offThreadSectionProcessing)
                        .setDefaultValue(true).setStorageHandler(storage))
                .addOption(builder.createBooleanOption(ResourceLocation.parse("boxy:incremental_rescan"))
                        .setName(Component.literal("Incremental LOD rescan"))
                        .setTooltip(Component.literal("Experimental: retain confirmed scan rings while moving instead of restarting the scan."))
                        .setBinding(v -> cfg.incrementalSpiralRescan = v, () -> cfg.incrementalSpiralRescan)
                        .setDefaultValue(false).setStorageHandler(storage)));
        page.addOptionGroup(builder.createOptionGroup().setName(Component.literal("Distant Entities"))
                .addOption(builder.createBooleanOption(ResourceLocation.parse("boxy:distant_entities"))
                        .setName(Component.literal("Distant entity rendering"))
                        .setTooltip(Component.literal("Render configured entities beyond vanilla range. Turning this off overrides the server's settings."))
                        .setBinding(v -> cfg.extendEntityRenderDistance = v, () -> cfg.extendEntityRenderDistance)
                        .setDefaultValue(true).setStorageHandler(storage))
                .addOption(builder.createIntegerOption(ResourceLocation.parse("boxy:entity_distance"))
                        .setName(Component.literal("Entity render distance"))
                        .setTooltip(Component.literal("Local fallback distance in chunks. Boxy servers supply their own distance and entity list."))
                        .setRange(1, 512, 1).setDefaultValue(32)
                        .setValueFormatter(v -> Component.literal(v + " chunks"))
                        .setBinding(v -> cfg.entityRenderDistanceChunks = v, () -> cfg.entityRenderDistanceChunks).setStorageHandler(storage))
                .addOption(builder.createEnumOption(ResourceLocation.parse("boxy:entity_depth"), DistantEntityDepthMode.class)
                        .setName(Component.literal("Distant entity depth fix"))
                        .setElementNameProvider(v -> Component.literal(switch (v) { case OFF -> "Off"; case BASIC -> "Basic"; case PRECISE -> "Precise"; }))
                        .setTooltip(Component.literal("Reduce model-layer flicker. Precise uses multiple passes; with Iris shaderpacks it uses Basic instead."))
                        .setBinding(v -> cfg.distantEntityDepthMode = v, () -> cfg.distantEntityDepthMode)
                        .setDefaultValue(DistantEntityDepthMode.PRECISE).setStorageHandler(storage)));
        page.addOptionGroup(builder.createOptionGroup().setName(Component.literal("LOD Rendering"))
                .addOption(builder.createBooleanOption(ResourceLocation.parse("boxy:conservative_hiz"))
                        .setName(Component.literal("Conservative LOD occlusion"))
                        .setTooltip(Component.literal("Prevent missing terrain at screen edges. Uses additional video memory."))
                        .setBinding(v -> cfg.conservativeHiZ = v, () -> cfg.conservativeHiZ)
                        .setDefaultValue(true).setStorageHandler(storage))
                .addOption(builder.createBooleanOption(ResourceLocation.parse("boxy:mipped_light"))
                        .setName(Component.literal("Brighten distant LOD light"))
                        .setTooltip(Component.literal("Use maximum child light when coarsening terrain. May bleed light; affects newly ingested terrain only."))
                        .setBinding(v -> cfg.brightenMippedLodLight = v, () -> cfg.brightenMippedLodLight)
                        .setDefaultValue(true).setStorageHandler(storage))
                .addOption(builder.createBooleanOption(ResourceLocation.parse("boxy:uniform_skylight"))
                        .setName(Component.literal("Fill uniform skylight"))
                        .setTooltip(Component.literal("Recover missing full-skylight arrays when ingesting terrain. Affects newly ingested terrain only."))
                        .setBinding(v -> cfg.fillUniformSkyLight = v, () -> cfg.fillUniformSkyLight)
                        .setDefaultValue(true).setStorageHandler(storage)));
        builder.registerOwnModOptions().addPage(page);
    }
}
