package com.golem.boxy.vss.client;

import com.golem.boxy.vss.BoxyVss;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = BoxyVss.MODID, dist = Dist.CLIENT)
public final class BoxyClient {
    public BoxyClient() {
        ClientNetworking.init();
    }
}
