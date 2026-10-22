package com.golem.boxy.smoke;

import com.golem.boxy.seedlod.fill.SeedFiller;
import com.golem.boxy.seedlod.fill.SeedValidation;
import net.minecraft.client.Minecraft;

/** Seed LODs start by themselves in singleplayer and build tiles without failing; waits for a requested validation. */
final class SeedLodSmoke {
    private SeedLodSmoke() {
    }

    static boolean check(Minecraft mc) {
        SeedFiller.Progress progress = SeedFiller.progress();
        // Tiles remembered from an earlier run of this smoke world count: there may be nothing left to build.
        if (progress == null || progress.tiles() == 0 && progress.remembered() == 0) {
            return false;
        }
        if (progress.failures() > 0) {
            throw new AssertionError(progress.failures() + " seed LOD tiles failed; see the log");
        }
        if (SeedValidation.requested() && !SeedValidation.finished) {
            SeedValidation.runIfAsked(mc.getSingleplayerServer());
            return false;
        }
        com.mojang.logging.LogUtils.getLogger().info("BOXY_SEEDLOD_SMOKE_OK: fill running in singleplayer, {} tiles built, {} remembered",
                progress.tiles(), progress.remembered());
        return true;
    }
}
