package com.golem.boxy.vss.common.processing;

import java.util.ArrayDeque;
import java.util.UUID;

public interface PlayerStateAccess {
   void drainDirtyClearRequests();

   void clearProcessingState();

   boolean hasDiskReadDone(int cx, int cz);

   void markDiskReadDone(int cx, int cz);

   int getSendQueueSize();

   int getPendingSyncCount();

   int getPendingGenerationCount();

   boolean supportsVoxelColumns();

   UUID getPlayerUUID();

   RateLimiterSet getRateLimiters();

   ArrayDeque<AbstractPlayerRequestState.QueuedRequest> getWaitingQueue();

   int getWaitingQueueSize();

   IncomingRequest pollIncomingRequest();

   void addPendingRequest(PendingRequest request);

   PendingRequest removePendingByPosition(int cx, int cz);

   PendingRequest removePendingByRequestId(int requestId);

   boolean hasPendingRequest(int cx, int cz);

   Integer pollCancel();
}
