package com.golem.boxy.vss.server;

import com.golem.boxy.vss.common.PositionUtil;
import com.golem.boxy.vss.common.processing.AbstractPlayerRequestState;
import com.golem.boxy.vss.common.processing.IncomingRequest;
import com.golem.boxy.vss.payloads.VssPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public class PlayerRequestState extends AbstractPlayerRequestState<PlayerRequestState.QueuedPayload> {
    private volatile ServerPlayer player;
    private ResourceKey<Level> lastDimension;

    public PlayerRequestState(ServerPlayer player, int syncRate, int syncConcurrency, int genRate, int genConcurrency) {
        super(player.getUUID(), syncRate, syncConcurrency, genRate, genConcurrency);
        this.player = player;
        this.lastDimension = player.serverLevel().dimension();
    }

    public void addRequest(int requestId, long packedPosition, long clientTimestamp) {
        int cx = PositionUtil.unpackX(packedPosition);
        int cz = PositionUtil.unpackZ(packedPosition);
        this.enqueueIncomingRequest(new IncomingRequest(requestId, cx, cz, clientTimestamp));
    }

    public void onDimensionChange() {
        this.onDimensionChangeBase();
    }

    public void updatePlayer(ServerPlayer newPlayer) {
        this.player = newPlayer;
    }

    public ServerPlayer getPlayer() {
        return this.player;
    }

    public ResourceKey<Level> getLastDimension() {
        return this.lastDimension;
    }

    public boolean checkDimensionChange() {
        ResourceKey<Level> currentDim = this.player.serverLevel().dimension();
        if (!currentDim.equals(this.lastDimension)) {
            this.lastDimension = currentDim;
            return true;
        }
        return false;
    }

    public record QueuedPayload(VssPayload payload, int requestId, int estimatedBytes, long submissionOrder)
            implements Comparable<PlayerRequestState.QueuedPayload> {
        @Override
        public int compareTo(PlayerRequestState.QueuedPayload other) {
            return Long.compare(this.submissionOrder, other.submissionOrder);
        }
    }
}
