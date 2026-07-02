package com.golem.boxy.vss;

import com.golem.boxy.loader.BoxyModLocator;
import com.golem.boxy.vss.client.ClientNetworking;
import com.golem.boxy.vss.net.VssChannels;
import com.golem.boxy.vss.server.ServerNetworking;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * The {@code @Mod("boxy")} entry point for Boxy's standalone game-layer mod — the home of the
 * <b>Voxy Server Side (VSS)</b> port: a client+server LOD-streaming protocol layered on top of Voxy.
 *
 * <p>Unlike Boxy's main jar (which runs on Forge's service layer and is never scanned as a game mod),
 * this class lives in a complete, self-contained mod jar that {@link BoxyModLocator}
 * hands to Forge as its own {@code ModFile}, next to the remapped Voxy jar. It depends on Voxy and
 * calls into it at runtime ({@code VoxelIngestService.rawIngest(...)} etc.); Voxy's jar stays pristine.
 * This is also what makes "Boxy" appear in the Forge mods list — superseding the old {@code BoxyInfoMod}
 * that used to be smuggled into Voxy's jar.
 */
@Mod(BoxyVss.MODID)
public final class BoxyVss {
    public static final String MODID = "boxy";
    private static final Logger LOGGER = LogUtils.getLogger();

    public BoxyVss() {
        LOGGER.info("Boxy game-layer mod initialising (Voxy Server Side port).");
        // Build the network channel (both sides) and install the server-side service + C2S handlers.
        // The server service is created on ServerStartedEvent, so this is safe on a dedicated server too.
        VssChannels.register();
        ServerNetworking.init();
        // Client-side receivers + the Voxy ingest bridge. Guarded so ClientNetworking (which references
        // client-only Minecraft/Voxy classes) is never classloaded on a dedicated server.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientNetworking.init();
        }
    }
}
