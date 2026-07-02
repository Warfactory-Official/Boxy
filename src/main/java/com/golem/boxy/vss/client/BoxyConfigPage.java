package com.golem.boxy.vss.client;

import com.golem.boxy.vss.config.VSSClientConfig;
import com.google.common.collect.ImmutableList;
import me.jellysquid.mods.sodium.client.gui.options.OptionFlag;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
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
 * exposed field is read live by Boxy each tick/frame, so edits take effect immediately — except
 * {@code mipmapEntityTextures}, which is flagged {@link OptionFlag#REQUIRES_GAME_RESTART}.
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
                        .setTooltip(Component.literal("Download and render distant terrain (LOD columns) streamed by a Boxy server. "
                                + "Turning this off stops terrain streaming but still lets the server's distant-entity list sync."))
                        .setControl(TickBoxControl::new)
                        .setBinding((cfg, v) -> cfg.receiveServerLods = v, cfg -> cfg.receiveServerLods)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.literal("Client LOD distance"))
                        .setTooltip(Component.literal("Cap on how far (in chunks) to request server LODs. 0 = use the server's distance. "
                                + "Effective distance is min(this, server, Voxy's render distance)."))
                        .setControl(opt -> new SliderControl(opt, 0, 512, 16, v -> Component.literal(v == 0 ? "Server" : v + " chunks")))
                        .setBinding((cfg, v) -> cfg.lodDistanceChunks = v, cfg -> cfg.lodDistanceChunks)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.literal("Off-thread LOD processing"))
                        .setTooltip(Component.literal("Deserialize received LOD columns on a background thread instead of the client thread. "
                                + "Recommended on."))
                        .setControl(TickBoxControl::new)
                        .setBinding((cfg, v) -> cfg.offThreadSectionProcessing = v, cfg -> cfg.offThreadSectionProcessing)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        // ---- Distant entities ----
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.literal("Distant entity rendering"))
                        .setTooltip(Component.literal("Render players and configured mobs far beyond vanilla's range, out toward Voxy's LOD distance. "
                                + "This is a local opt-out: turning it off disables the feature even on a Boxy server. While connected, the "
                                + "entity-type list comes from the server; for singleplayer/non-Boxy servers, edit renderedEntityTypes in "
                                + "vss-client-config.json."))
                        .setControl(TickBoxControl::new)
                        .setBinding((cfg, v) -> cfg.extendEntityRenderDistance = v, cfg -> cfg.extendEntityRenderDistance)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.literal("Entity render distance"))
                        .setTooltip(Component.literal("How far (in chunks) to render distant entities when not synced by a Boxy server. "
                                + "While connected to a Boxy server, the server's distance is used instead."))
                        .setControl(opt -> new SliderControl(opt, 1, 512, 1, v -> Component.literal(v + " chunks")))
                        .setBinding((cfg, v) -> cfg.entityRenderDistanceChunks = v, cfg -> cfg.entityRenderDistanceChunks)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.literal("Mipmap entity textures"))
                        .setTooltip(Component.literal("Rebuild entity/skin textures with a mipmap chain so distant entities don't shimmer/alias. "
                                + "Costs a little VRAM and changes texture handling for all entities. Requires a game restart."))
                        .setControl(TickBoxControl::new)
                        .setBinding((cfg, v) -> cfg.mipmapEntityTextures = v, cfg -> cfg.mipmapEntityTextures)
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .build())
                .build());

        return new OptionPage(Component.literal("Boxy"), ImmutableList.copyOf(groups));
    }
}
