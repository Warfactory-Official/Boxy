package com.golem.boxy.vss.common.processing;

/** Shared by the processing thread and the baseline readers. */
public class SequenceCounter {
   private final java.util.concurrent.atomic.AtomicLong value = new java.util.concurrent.atomic.AtomicLong();

   public SequenceCounter() {
   }

   public long next() {
      return this.value.getAndIncrement();
   }
}
