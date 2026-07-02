package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.PositionUtil;
import com.golem.boxy.vss.common.VSSConstants;
import com.golem.boxy.vss.common.VSSLogger;
import com.golem.boxy.vss.common.tracking.DirtyColumnTracker;
import com.golem.boxy.vss.config.VSSServerConfig;
import com.golem.boxy.vss.net.VssChannels;
import com.golem.boxy.vss.payloads.DirtyColumnsS2CPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Periodically pushes changed (saved) columns to the players in range, so their LODs refresh. */
class DirtyColumnBroadcaster {
    private final MinecraftServer server;
    private final Map<UUID, PlayerRequestState> players;
    private final ForgeOffThreadProcessor offThreadProcessor;
    private final DirtyColumnTracker dirtyTracker;
    private int counter = 0;
    private long[] positionFilterBuffer = null;

    DirtyColumnBroadcaster(MinecraftServer server, Map<UUID, PlayerRequestState> players,
                           ForgeOffThreadProcessor offThreadProcessor, DirtyColumnTracker dirtyTracker) {
        this.server = server;
        this.players = players;
        this.offThreadProcessor = offThreadProcessor;
        this.dirtyTracker = dirtyTracker;
    }

    void tick(VSSServerConfig config) {
        int intervalTicks = config.dirtyBroadcastIntervalSeconds * 20;
        if (++this.counter < intervalTicks) {
            return;
        }
        this.counter = 0;
        Set<UUID> failedPlayers = null;

        for (ServerLevel level : this.server.getAllLevels()) {
            String dimensionStr = level.dimension().location().toString();
            long[] dirty = this.dirtyTracker.drainDirty(dimensionStr);
            if (dirty == null || dirty.length == 0) {
                continue;
            }
            this.offThreadProcessor.invalidateTimestamps(dimensionStr, dirty);
            int bufLen = Math.min(dirty.length, VSSConstants.MAX_DIRTY_COLUMN_POSITIONS);
            if (this.positionFilterBuffer == null || this.positionFilterBuffer.length < bufLen) {
                this.positionFilterBuffer = new long[bufLen];
            }

            for (PlayerRequestState state : this.players.values()) {
                if (!state.hasCompletedHandshake()) {
                    continue;
                }
                ServerPlayer player = state.getPlayer();
                if ((failedPlayers != null && failedPlayers.contains(player.getUUID()))
                        || !state.getLastDimension().equals(level.dimension())
                        || player.isRemoved()) {
                    continue;
                }
                int playerCx = player.getBlockX() >> 4;
                int playerCz = player.getBlockZ() >> 4;
                int lodDist = config.lodDistanceChunks;
                int count = 0;
                for (long packed : dirty) {
                    if (!PositionUtil.isOutOfRange(packed, playerCx, playerCz, lodDist)) {
                        this.positionFilterBuffer[count++] = packed;
                        if (count >= VSSConstants.MAX_DIRTY_COLUMN_POSITIONS) {
                            break;
                        }
                    }
                }
                if (count > 0) {
                    long[] result = new long[count];
                    System.arraycopy(this.positionFilterBuffer, 0, result, 0, count);
                    state.clearDiskReadDoneForPositions(result);
                    try {
                        VssChannels.sendToClient(player, new DirtyColumnsS2CPayload(result));
                    } catch (Exception e) {
                        VSSLogger.error("Failed to send dirty columns to " + player.getName().getString(), e);
                        if (failedPlayers == null) {
                            failedPlayers = new HashSet<>();
                        }
                        failedPlayers.add(player.getUUID());
                    }
                }
            }
        }
    }
}
