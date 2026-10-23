package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.FlashbackVoxy;
import com.google.gson.JsonObject;
import java.io.File;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fabric Voxy's {@code MixinFlashbackMeta}: a replay's Voxy storage path, saved as {@code voxy_storage_path}. */
@Pseudo
@Mixin(targets = "com.moulberry.flashback.record.FlashbackMeta", remap = false)
public abstract class MixinFlashbackMeta implements FlashbackVoxy.Meta {
    @Unique private File boxy$voxyPath;

    @Override
    public File boxy$getVoxyPath() {
        return boxy$voxyPath;
    }

    @Override
    public void boxy$setVoxyPath(File path) {
        boxy$voxyPath = path;
    }

    @Inject(method = "toJson", at = @At("RETURN"))
    private void boxy$saveVoxyPath(CallbackInfoReturnable<JsonObject> cir) {
        JsonObject json = cir.getReturnValue();
        if (json != null && boxy$voxyPath != null) {
            json.addProperty("voxy_storage_path", boxy$voxyPath.getAbsolutePath());
        }
    }

    @Inject(method = "fromJson", at = @At("RETURN"))
    private static void boxy$loadVoxyPath(JsonObject json, CallbackInfoReturnable<Object> cir) {
        if (json != null && json.has("voxy_storage_path") && cir.getReturnValue() instanceof FlashbackVoxy.Meta meta) {
            meta.boxy$setVoxyPath(new File(json.get("voxy_storage_path").getAsString()));
        }
    }
}
