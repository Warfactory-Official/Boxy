package com.golem.boxy.vss.common.processing;

import java.util.UUID;

public sealed interface SendAction permits SendAction.RateLimited, SendAction.ColumnUpToDate, SendAction.ColumnNotGenerated {
   UUID playerUuid();

   int requestId();

   default byte responseType() {
      // (Java 21 type-pattern switch in the original; rewritten for Java 17.)
      if (this instanceof RateLimited) {
         return 0;
      } else if (this instanceof ColumnUpToDate) {
         return 1;
      } else if (this instanceof ColumnNotGenerated) {
         return 2;
      }
      throw new IllegalStateException("Unknown SendAction: " + this);
   }

   public record ColumnNotGenerated(UUID playerUuid, int requestId) implements SendAction {
   }

   public record ColumnUpToDate(UUID playerUuid, int requestId) implements SendAction {
   }

   public record RateLimited(UUID playerUuid, int requestId) implements SendAction {
   }
}
