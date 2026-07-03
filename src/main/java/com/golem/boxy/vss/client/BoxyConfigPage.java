package com.golem.boxy.vss.client;

import com.golem.boxy.vss.config.DistantEntityDepthMode;
import com.golem.boxy.vss.config.VSSClientConfig;
import com.google.common.collect.ImmutableList;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds Boxy's page for Embeddium's Video Settings screen — mirrors Voxy's {@code VoxyConfigScreenPages}.
 * Options bind to {@link VSSClientConfig#CONFIG} through {@link BoxyOptionStorage}; when the user applies the
 * screen, Embeddium runs the bindings then calls {@link BoxyOptionStorage#save()} to clamp + write the JSON.
 *
 * <p>Bindings operate on the {@link VSSClientConfig} returned by {@link BoxyOptionStorage#getData()}. Every
 * exposed field is read live by Boxy each tick/frame, so edits take effect immediately.
 * {@code renderedEntityTypes} (a string list) is deliberately not exposed here — Embeddium has no list
 * control, and while connected to a Boxy server the server's synced list is authoritative anyway; it remains
 * editable in {@code vss-client-config.json}.
 *
 * <p><b>Client-only</b> — references Embeddium ({@code me.jellysquid.*}); reached only from the client mixin,
 * never loaded on a dedicated server.
 */
public final class BoxyConfigPage {
    private BoxyConfigPage() {}

    public static OptionPage page() {
        BoxyOptionStorage storage = BoxyOptionStorage.INSTANCE;
        List<OptionGroup> groups = new ArrayList<>();

        // ---- LOD terrain streaming ----
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.literal("Receive server LODs"))
                        .setTooltip(Component.literal("Download distant terrain from servers that stream it, so you can see far beyond your render distance. "
                                + "Distant entities are unaffected by this setting."))
                        .setControl(TickBoxControl::new)
                        .setBinding((cfg, v) -> cfg.receiveServerLods = v, cfg -> cfg.receiveServerLods)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.literal("Client LOD distance"))
                        .setTooltip(Component.literal("How far to request streamed terrain, in chunks. \"Server\" means as far as the server allows. "
                                + "Never goes beyond Voxy's render distance."))
                        .setControl(opt -> new SliderControl(opt, 0, 512, 16, v -> Component.literal(v == 0 ? "Server" : v + " chunks")))
                        .setBinding((cfg, v) -> cfg.lodDistanceChunks = v, cfg -> cfg.lodDistanceChunks)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.literal("Off-thread LOD processing"))
                        .setTooltip(Component.literal("Process received terrain on a background thread for smoother frame rates. Recommended on."))
                        .setControl(TickBoxControl::new)
                        .setBinding((cfg, v) -> cfg.offThreadSectionProcessing = v, cfg -> cfg.offThreadSectionProcessing)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.literal("Incremental LOD rescan"))
                        .setTooltip(Component.literal("Experimental: skip re-checking terrain you already have while moving around. "
                                + "Can improve frame rates while flying at large distances."))
                        .setControl(TickBoxControl::new)
                        .setBinding((cfg, v) -> cfg.incrementalSpiralRescan = v, cfg -> cfg.incrementalSpiralRescan)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        // ---- Distant entities ----
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.literal("Distant entity rendering"))
                        .setTooltip(Component.literal("Show players (and certain mobs) far beyond the normal range. "
                                + "On supported servers, the server decides which mobs are included."))
                        .setControl(TickBoxControl::new)
                        .setBinding((cfg, v) -> cfg.extendEntityRenderDistance = v, cfg -> cfg.extendEntityRenderDistance)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.literal("Entity render distance"))
                        .setTooltip(Component.literal("How far away entities can be shown, in chunks. "
                                + "Supported servers may use their own distance instead."))
                        .setControl(opt -> new SliderControl(opt, 1, 512, 1, v -> Component.literal(v + " chunks")))
                        .setBinding((cfg, v) -> cfg.entityRenderDistanceChunks = v, cfg -> cfg.entityRenderDistanceChunks)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(DistantEntityDepthMode.class, storage)
                        .setName(Component.literal("Distant entity depth fix"))
                        .setTooltip(Component.literal("Fix flickering and shimmering surfaces on faraway entities. "
                                + "Basic is a single cheap pass; Precise draws distant entities in a few extra passes "
                                + "for a fully stable image. While a shaderpack is active, Precise behaves like Basic."))
                        .setControl(opt -> new CyclingControl<>(opt, DistantEntityDepthMode.class, new Component[]{
                                Component.literal("Off"), Component.literal("Basic"), Component.literal("Precise")}))
                        .setBinding((cfg, v) -> cfg.distantEntityDepthMode = v, cfg -> cfg.distantEntityDepthMode)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        return new OptionPage(Component.literal("Boxy"), ImmutableList.copyOf(groups));
    }
}
