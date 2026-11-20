package com.golem.boxy.vss.common.processing;

import java.util.concurrent.atomic.AtomicLong;

/** Shared by the processing thread and the baseline readers. */
public class SequenceCounter {
   private final AtomicLong value = new AtomicLong();

   public SequenceCounter() {
   }

   public long next() {
      return this.value.getAndIncrement();
   }
}
