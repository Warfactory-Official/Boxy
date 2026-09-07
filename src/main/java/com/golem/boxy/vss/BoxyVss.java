package com.golem.boxy.vss;

import com.golem.boxy.vss.net.VssChannels;
import com.golem.boxy.vss.server.ServerNetworking;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/** Native NeoForge entry point. No Voxy or client classes are needed on a dedicated server. */
@Mod(BoxyVss.MODID)
public final class BoxyVss {
    public static final String MODID = "boxy";

    public BoxyVss(IEventBus modBus) {
        modBus.addListener(VssChannels::register);
        ServerNetworking.init();
    }
}
