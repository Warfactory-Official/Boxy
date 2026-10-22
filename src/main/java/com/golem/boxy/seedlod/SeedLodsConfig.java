package com.golem.boxy.seedlod;

import com.golem.boxy.vss.config.JsonConfig;

/** Client settings for seed LODs, {@code config/boxy-seedlod-client.json}. */
public final class SeedLodsConfig extends JsonConfig {
   public static final int MIN_RADIUS = 16;
   public static final int MAX_RADIUS = 4096;
   /** Draw terrain from the seed where no real terrain has arrived. */
   public boolean enabled = true;
   /** Ask Boxy servers for their seed (they must opt in). */
   public boolean useServerSeeds = true;
   /** Place trees, plants, snow and structures with vanilla's own features near the player. */
   public boolean decorate = true;
   /** Fill radius in chunks; Voxy's render distance caps it. */
   public int radiusChunks = 256;
   public int threads = defaultThreads();

   /** Read on first use; the class holder makes that lazy and thread-safe. */
   public static SeedLodsConfig get() {
      return Loaded.CONFIG;
   }

   private static final class Loaded {
      static final SeedLodsConfig CONFIG = load(SeedLodsConfig.class, "boxy-seedlod-client.json");
   }

   public static int defaultThreads() {
      return Math.max(1, Runtime.getRuntime().availableProcessors() / 3);
   }

   public static int maxThreads() {
      return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
   }

   @Override
   protected String getFileName() {
      return "boxy-seedlod-client.json";
   }

   @Override
   protected void validate() {
      radiusChunks = Math.clamp(radiusChunks, MIN_RADIUS, MAX_RADIUS);
      threads = Math.clamp(threads, 1, maxThreads());
   }

   /** Clamp and persist; used by Boxy's Sodium settings page. */
   public void saveClamped() {
      validate();
      save();
   }
}
