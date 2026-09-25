package com.golem.boxy.vss.common.processing;

import java.util.function.Supplier;

/** Detached column data. Deferred encoders must capture copies, never a live chunk or level. */
public final class LoadedColumnData {
   private final int cx;
   private final int cz;
   private byte[] serializedSections;
   private int estimatedBytes;
   private Supplier<byte[]> encoder;

   public LoadedColumnData(int cx, int cz, byte[] serializedSections, int estimatedBytes) {
      this.cx = cx;
      this.cz = cz;
      this.serializedSections = serializedSections;
      this.estimatedBytes = estimatedBytes;
   }

   public static LoadedColumnData deferred(int cx, int cz, Supplier<byte[]> encoder) {
      LoadedColumnData column = new LoadedColumnData(cx, cz, null, 0);
      column.encoder = java.util.Objects.requireNonNull(encoder);
      return column;
   }

   public int cx() { return cx; }
   public int cz() { return cz; }

   /** Called by the processing worker; shared generation callbacks encode the snapshot only once. */
   public synchronized byte[] serializedSections() {
      if (encoder != null) {
         serializedSections = encoder.get();
         estimatedBytes = serializedSections == null ? 0 : serializedSections.length + 25;
         encoder = null;
      }
      return serializedSections;
   }

   public synchronized int estimatedBytes() {
      serializedSections();
      return estimatedBytes;
   }
}
