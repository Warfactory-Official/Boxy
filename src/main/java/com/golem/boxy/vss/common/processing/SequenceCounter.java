package com.golem.boxy.vss.common.processing;

public class SequenceCounter {
   private long value = 0L;

   public SequenceCounter() {
   }

   public long next() {
      return this.value++;
   }
}
