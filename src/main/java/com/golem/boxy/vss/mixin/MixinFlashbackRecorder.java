package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.FlashbackVoxy;
import java.lang.reflect.Field;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.commonImpl.VoxyCommon;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric Voxy's {@code MixinFlashbackRecorder}: a new recording names the Voxy database the world is drawn from. The
 * constructor is matched by name alone because its parameter is a Minecraft type Connector remaps.
 */
@Pseudo
@Mixin(targets = "com.moulberry.flashback.record.Recorder", remap = false)
public abstract class MixinFlashbackRecorder {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void boxy$recordVoxyPath(CallbackInfo ci) {
        if (VoxyCommon.isAvailable() && VoxyCommon.getInstance() instanceof VoxyClientInstance voxy) {
            try {
                Field field = getClass().getDeclaredField("metadata");
                field.setAccessible(true);
                if (field.get(this) instanceof FlashbackVoxy.Meta meta) {
                    meta.boxy$setVoxyPath(voxy.getStorageBasePath().toFile());
                }
            } catch (ReflectiveOperationException failure) {
                // the recording still works, it just won't carry LODs
                LoggerFactory.getLogger("boxy").warn("Could not stamp Voxy's storage path into the Flashback recording", failure);
            }
        }
    }
}
