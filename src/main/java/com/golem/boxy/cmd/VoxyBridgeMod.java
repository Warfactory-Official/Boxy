package com.golem.boxy.cmd;

import com.mojang.logging.LogUtils;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * The {@code @Mod("voxy")} entry point Boxy injects into the remapped Voxy jar (Voxy itself has none).
 * Boxy's own jar lives on Forge's early service layer and is never loaded as a game mod, so this class
 * — which lives inside Voxy's jar on the game layer, alongside Minecraft and the Forge event bus — is
 * what registers the {@code /voxy} command. Everything is wrapped defensively so a failure here can
 * never stop Voxy from loading and rendering.
 */
@Mod("voxy")
public class VoxyBridgeMod {
    private static final Logger LOGGER = LogUtils.getLogger();

    public VoxyBridgeMod(FMLJavaModLoadingContext context) {
        try {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                MinecraftForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
                LOGGER.info("Boxy: /voxy command bridge installed.");
            }
        } catch (Throwable t) {
            LOGGER.error("Boxy: failed to install /voxy command bridge", t);
        }
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        try {
            if (VoxyCommon.isAvailable()) {
                BoxyCommands.register(event.getDispatcher());
            }
        } catch (Throwable t) {
            LOGGER.error("Boxy: failed to register /voxy command", t);
        }
    }
}
