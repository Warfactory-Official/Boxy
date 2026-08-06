package com.golem.boxy.vss.common.processing;

import com.golem.boxy.vss.common.voxel.SerializedColumnCache;
import java.util.concurrent.ConcurrentLinkedQueue;

record ProcessingContext(
   ConcurrentLinkedQueue<SendAction> sendActions,
   ConcurrentLinkedQueue<OffThreadProcessor.GenerationTicketRequest> generationTicketRequests,
   ProcessingDiagnostics diagnostics,
   SequenceCounter sequence,
   SerializedColumnCache bytesCache
) {
}
