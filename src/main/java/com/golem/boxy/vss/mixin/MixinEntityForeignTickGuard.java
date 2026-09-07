package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.client.ClientEntitySync;
import com.golem.boxy.vss.client.DistantEntityTicker;
import com.golem.boxy.vss.common.TrackedEntityTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops other mods from re-ticking Boxy's distant entities behind its back.
 *
 * <p>ReForgedPlay (a Forge ReplayMod port) ships {@code Mixin_FixEntityNotTracking}, which on every entity
 * move/teleport packet re-ticks (up to 100×) any entity NOT in {@code ClientLevel.tickingEntities} — the
 * exact same set Boxy's {@link DistantEntityTicker} owns. For a non-living entity (a vehicle like the jet
 * mod) that simulates gravity, those extra ticks free-fall/fling it through the un-loaded terrain, fighting
 * Boxy's pin and making it render "all over the place" past view distance (the position analog of quirk 30,
 * triggered by a foreign ticker rather than the entity's own).
 *
 * <p>Boxy's distant entities must be ticked <b>only</b> by {@link DistantEntityTicker} (which pins them). This
 * guard cancels {@code Entity.tick}/{@code rideTick} for a Boxy-managed
 * distant entity whenever the caller is <b>not</b> Boxy's own ticker ({@link DistantEntityTicker#isBoxyTicking()}):
 * a foreign re-tick loop then sees the entity unmoved and stops, while Boxy's own tick runs normally. The
 * common in-view tick fast-rejects at the {@code tickingEntities} check.
 *
 * <p>Client-only ({@code boxy.mixins.json} "client"); {@code require = 0} so
 * it degrades instead of crashing. The {@code instanceof ClientLevel} guard keeps it inert for server-side
 * entity ticks on the integrated server (where {@code Entity.tick} also runs for {@code ServerLevel} entities).
 */
@Mixin(Entity.class)
public abstract class MixinEntityForeignTickGuard {
    @Inject(method = {"tick", "rideTick"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void boxy$skipForeignDistantTick(CallbackInfo ci) {
        // All guards below are independent early-returns, so ordering is purely a cost question. This runs
        // for EVERY entity tick on the client, so the feature gate goes first: when the feature is off
        // (vanilla server, or local opt-out) it exits on a few plain/volatile field reads, before the
        // accessor cast and tickingEntities map lookup that the enabled path needs.
        if (!ClientEntitySync.enabled()) {
            return;
        }
        if (DistantEntityTicker.isBoxyTicking()) {
            return; // our own managed tick — allow it
        }
        Entity self = (Entity) (Object) this;
        if (!(self.level() instanceof ClientLevel clientLevel)) {
            return; // client entities only (Entity.tick also runs server-side on the integrated server)
        }
        if (((AccessorClientLevel) clientLevel).boxy$getTickingEntities().contains(self)) {
            return; // in view / normal tick loop — leave it alone (fast path for the common case)
        }
        if (self == Minecraft.getInstance().player) {
            return; // never block the local player's own tick (it drives the camera)
        }
        if (TrackedEntityTypes.clientContains(self.getType())) {
            ci.cancel(); // foreign re-tick of a Boxy-managed distant entity — skip it
        }
    }
}
