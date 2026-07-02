package com.golem.boxy.vss.mixin;

import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.config.VSSServerConfig;
import com.golem.boxy.vss.server.RequestProcessingService;
import com.golem.boxy.vss.server.ServerNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Live (edit-gated) dirty-column sync: marks a column dirty the instant a block in it changes, instead of
 * waiting for the chunk to be <b>saved</b> (cf. {@link ChunkMapSaveHook}, which is save-gated — under
 * vanilla's default autosave that's minutes of latency). With this, a change to a loaded distant chunk
 * reaches LOD clients within the broadcast interval + the client's re-request round-trip (a few seconds),
 * rather than at save granularity. Everything downstream is unchanged — the broadcaster invalidates the
 * server timestamp, the client re-requests, and the in-memory probe serves the fresh column.
 *
 * <p>Targets {@code ServerLevel.sendBlockUpdated} (SRG {@code m_7260_}); vanilla calls it for every block
 * change that should notify clients (player edits, redstone, pistons, fluids, growth, …) but NOT during
 * initial chunk load, so it captures exactly the edits worth syncing without spamming on worldgen. Only
 * loaded chunks ever fire it — an unloaded distant chunk is inert — which is precisely the set of columns
 * whose changes are syncable. The whole column is marked dirty (LOD granularity is per-column).
 *
 * <p>Hand-SRG, {@code remap = false}; {@code require = 0} so a signature mismatch disables the feature
 * instead of aborting world load (cf. {@link ChunkMapSaveHook}). No-op unless the VSS service is running and
 * {@code liveDirtyUpdates} is on. The dimension id is cached per level so the hot path allocates nothing; a
 * one-time INFO line per dimension confirms the hook applied.
 */
@Mixin(value = ServerLevel.class, remap = false)
public abstract class MixinServerLevelLiveDirty {
    @Unique
    private String boxy$dimensionId;
    @Unique
    private boolean boxy$diagLogged;

    @Inject(method = "m_7260_", at = @At("HEAD"), require = 0)
    private void boxy$markColumnDirtyOnEdit(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        if (!VSSServerConfig.CONFIG.liveDirtyUpdates) {
            return;
        }
        RequestProcessingService service = ServerNetworking.getRequestService();
        if (service == null) {
            return;
        }
        String dimension = this.boxy$dimensionId;
        if (dimension == null) {
            dimension = ((ServerLevel) (Object) this).dimension().location().toString();
            this.boxy$dimensionId = dimension;
        }
        service.getDirtyTracker().markDirty(dimension, pos.getX() >> 4, pos.getZ() >> 4);
        if (!this.boxy$diagLogged) {
            this.boxy$diagLogged = true;
            VSSLogger.info("Boxy live dirty-sync ACTIVE (edit-gated) — first change at chunk ["
                    + (pos.getX() >> 4) + ", " + (pos.getZ() >> 4) + "] in " + dimension);
        }
    }
}
