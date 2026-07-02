package com.golem.boxy.vss.client;

import com.golem.boxy.vss.config.VSSClientConfig;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;

/**
 * Embeddium {@link OptionStorage} backing Boxy's page in the Video Settings screen (mirrors Voxy's
 * {@code VoxyConfig}). The option bindings read/write the fields of {@link VSSClientConfig#CONFIG} directly;
 * {@link #save()} clamps and persists {@code vss-client-config.json} when the user applies the screen.
 *
 * <p><b>Client-only.</b> This references Embeddium ({@code me.jellysquid.*}), which is absent on a dedicated
 * server. It is reached only through the client-side {@code MixinEmbeddiumOptionsGUI}, so it never loads
 * server-side — do not reference it from {@code common}/{@code server} code.
 */
public final class BoxyOptionStorage implements OptionStorage<VSSClientConfig> {
    public static final BoxyOptionStorage INSTANCE = new BoxyOptionStorage();

    private BoxyOptionStorage() {}

    @Override
    public VSSClientConfig getData() {
        return VSSClientConfig.CONFIG;
    }

    @Override
    public void save() {
        VSSClientConfig.CONFIG.saveClamped();
    }
}
