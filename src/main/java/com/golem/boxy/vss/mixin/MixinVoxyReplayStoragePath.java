package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.FlashbackVoxy;
import java.nio.file.Path;
import me.cortex.voxy.common.platform.PlatformUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The NeoForge Voxy stubs {@code getReplayStoragePath} to null. Answering it with the replay's recorded database makes
 * {@code VoxyClientInstance} open that one with ingest off, as Fabric Voxy does, instead of an empty one in Flashback's
 * temp world. Redirected at the call in the constructor: {@code PlatformUtilImpl} itself is loaded before mixins
 * prepare and cannot be targeted.
 */
@Mixin(targets = "me.cortex.voxy.client.VoxyClientInstance", remap = false)
public abstract class MixinVoxyReplayStoragePath {
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/common/platform/PlatformUtil;getReplayStoragePath(Z)Ljava/nio/file/Path;"))
    private Path boxy$replayStoragePath(PlatformUtil platform, boolean flashbackInstalled) {
        Path path = flashbackInstalled ? FlashbackVoxy.replayStoragePath() : null;
        return path != null ? path : platform.getReplayStoragePath(flashbackInstalled);
    }
}
