package com.golem.boxy.vss.common.processing;

public interface ReadResultAccess {
   String dimension();

   int chunkX();

   int chunkZ();

   int requestId();

   long columnTimestamp();

   boolean notFound();

   long submissionOrder();

   default boolean saturated() {
      return false;
   }

   default byte[] sectionBytes() {
      return null;
   }

   default int estimatedBytes() {
      return 0;
   }
}
