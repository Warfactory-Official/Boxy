package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.BoxyConfigPage;
import me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Appends Boxy's client-settings page to Embeddium's Video Settings screen — the same mechanism Voxy uses for
 * its own page (see Voxy's {@code me.cortex.voxy.client.mixin.sodium.MixinSodiumOptionsGUI}). Both injectors
 * coexist: each adds one page at the tail of the constructor.
 *
 * <p>Targets Embeddium's {@code SodiumOptionsGUI} — a non-Minecraft class — so {@code remap = false} (nothing
 * MC to remap; the {@code <init>} selector and the Embeddium {@code pages} field are namespace-agnostic).
 * {@code require = 0} so a differing Embeddium version degrades (no Boxy page) instead of crashing.
 *
 * <p><b>Client-only</b> (listed in {@code boxy.mixins.json}'s {@code client} section). Embeddium is absent on a
 * dedicated server, so this and the classes it references never load there.
 */
@Mixin(value = SodiumOptionsGUI.class, remap = false)
public class MixinEmbeddiumOptionsGUI {
    @Shadow @Final private List<OptionPage> pages;

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void boxy$addConfigPage(Screen prevScreen, CallbackInfo ci) {
        this.pages.add(BoxyConfigPage.page());
    }
}
