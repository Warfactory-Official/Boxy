package com.golem.boxy.vss.mixin;

import java.lang.reflect.Field;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.compat.IFlashbackMeta;
import me.cortex.voxy.commonImpl.VoxyCommon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Restores Voxy's Flashback LOD-in-replay integration on Flashback 0.11.0.
 *
 * <p>Voxy's own {@code flashback.MixinFlashbackRecorder} injects at the TAIL of {@code Recorder.<init>} but,
 * via its handler's first parameter, targets {@code <init>(RegistryAccess)} — a constructor Flashback removed.
 * 0.11.0's {@code Recorder} has only a no-arg constructor, so Voxy's injector finds no target and aborts fatally
 * the moment a recording starts. Boxy strips Voxy's broken mixin ({@code VoxyRemapper.INCOMPATIBLE_MIXINS}) and
 * applies this one against the current constructor instead ({@code method = "<init>"} + a handler taking only
 * {@link CallbackInfo} resolves to the no-arg {@code <init>()V}).
 *
 * <p>At record-start it copies Voxy's current LOD storage base path into the replay's {@code FlashbackMeta}
 * through Voxy's {@code IFlashbackMeta} interface (added by Voxy's still-compatible {@code MixinFlashbackMeta}),
 * which then serialises it into {@code metadata.json}; on playback Voxy's {@code FlashbackCompat} reads it back
 * so the replay renders the recorded LODs. This mirrors Voxy's original logic, only re-targeted.
 *
 * <p><b>Why string-target + reflection (no Flashback compile dependency).</b> Boxy targets Java 17 / MC 1.20.1;
 * the published Flashback artifacts are Java-21 bytecode for the 1.21 line and won't compile under
 * {@code --release 17}. Rather than couple the build to a specific Flashback jar, this reads the
 * {@code metadata} field reflectively — the field name is Flashback's own (not Minecraft), so it is stable
 * across the SRG remap. The injected handler is merged into {@code Recorder}, so the reflective access is a
 * class reading its own private field (no JPMS module-access concern). {@code remap = false} (Flashback isn't
 * Minecraft); {@code require = 0} plus the {@code catch} mean a future Flashback change degrades to
 * "no LOD-in-replay" rather than re-crashing. When Flashback is absent the target class is missing and Mixin
 * skips this silently.
 */
@Mixin(targets = "com.moulberry.flashback.record.Recorder", remap = false)
public class MixinBoxyFlashbackRecorder {

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void boxy$stampVoxyStoragePath(CallbackInfo ci) {
        if (!VoxyCommon.isAvailable() || !(VoxyCommon.getInstance() instanceof VoxyClientInstance client)) {
            return;
        }
        try {
            Field metadataField = this.getClass().getDeclaredField("metadata");
            metadataField.setAccessible(true);
            Object metadata = metadataField.get(this);
            if (metadata instanceof IFlashbackMeta flashbackMeta) {
                flashbackMeta.setVoxyPath(client.getStorageBasePath().toFile());
            }
        } catch (ReflectiveOperationException e) {
            // Flashback renamed/removed the field — degrade to no LOD-in-replay rather than crashing.
        }
    }
}
