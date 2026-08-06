package com.golem.boxy.vss.config;
import com.golem.boxy.vss.common.VssMath;
import java.util.ArrayList;
import java.util.List;

public class VSSServerConfig extends JsonConfig {
   private static final String FILE_NAME = "vss-server-config.json";
   public static final VSSServerConfig CONFIG = load(VSSServerConfig.class, "vss-server-config.json");
   public boolean enabled = true;
   public int lodDistanceChunks = 256;
   public int bytesPerSecondLimitPerPlayer = 20971520;
   public int diskReaderThreads = 5;
   public int sendQueueLimitPerPlayer = 4000;
   public int bytesPerSecondLimitGlobal = 104857600;
   public boolean enableChunkGeneration = true;
   public int generationConcurrencyLimitGlobal = 32;
   public int generationTimeoutSeconds = 60;
   public int dirtyBroadcastIntervalSeconds = 2;
   public boolean liveDirtyUpdates = true;
   // Loaded columns are no longer serialized inline during the probe; they acquire a syncOnLoad permit and
   // are serialized on the next tick, so they now hold permits that the old inline path never took. Sized to
   // match the per-player probe cap so a burst of loaded chunks isn't immediately pushed into the waiting
   // queue. Overflow is still graceful (waiting queue, then RateLimited), just slower.
   public int syncOnLoadRateLimitPerPlayer = 1000;
   public int syncOnLoadConcurrencyLimitPerPlayer = 512;
   public int generationRateLimitPerPlayer = 80;
   public int generationConcurrencyLimitPerPlayer = 16;
   public int perDimensionTimestampCacheSizeMB = 32;
   public boolean extendEntityTracking = true;
   public List<String> trackedEntityTypes = new ArrayList<>(List.of("minecraft:player"));
   public int entityTrackingDistanceChunks = 32;
   public boolean forceLoadTrackedEntities = false;
   public int forceLoadRadiusChunks = 2;
   /**
    * Wall-clock budget, in microseconds, for everything this mod does to live chunks on the server thread in
    * one tick: probing which requested columns are loaded, plus serializing the ones the router asked for.
    * Shared across all players rather than per-player, so total cost is bounded regardless of player count.
    * 2000us is ~4% of a 50ms tick. 0 disables the budget (unbounded — debugging/parity only).
    */
   public int probeBudgetMicros = 2000;
   /**
    * Per-dimension budget, in MB, for the cache of already-serialized column bytes. Its main job is sparing
    * the disk reader from re-reading and re-decoding a region file every time a different player asks for the
    * same column; on the live path a hit also serves the column immediately instead of deferring it a tick.
    * At 20-120KB per column, 64MB holds roughly 600-3000 columns. 0 disables the cache.
    */
   public int serializedColumnCacheSizeMB = 64;

   public VSSServerConfig() {
   }

   @Override
   protected String getFileName() {
      return "vss-server-config.json";
   }

   @Override
   protected void validate() {
      this.lodDistanceChunks = VssMath.clamp((long)this.lodDistanceChunks, 1, 512);
      this.bytesPerSecondLimitPerPlayer = VssMath.clamp((long)this.bytesPerSecondLimitPerPlayer, 1024, 104857600);
      this.diskReaderThreads = VssMath.clamp((long)this.diskReaderThreads, 1, 64);
      this.sendQueueLimitPerPlayer = VssMath.clamp((long)this.sendQueueLimitPerPlayer, 1, 100000);
      this.bytesPerSecondLimitGlobal = (int)VssMath.clamp((long)this.bytesPerSecondLimitGlobal, 1024L, 1073741824L);
      this.generationConcurrencyLimitGlobal = VssMath.clamp((long)this.generationConcurrencyLimitGlobal, 1, 256);
      this.generationTimeoutSeconds = VssMath.clamp((long)this.generationTimeoutSeconds, 1, 600);
      this.dirtyBroadcastIntervalSeconds = VssMath.clamp((long)this.dirtyBroadcastIntervalSeconds, 1, 300);
      this.syncOnLoadRateLimitPerPlayer = VssMath.clamp((long)this.syncOnLoadRateLimitPerPlayer, 1, 1000);
      this.syncOnLoadConcurrencyLimitPerPlayer = VssMath.clamp((long)this.syncOnLoadConcurrencyLimitPerPlayer, 1, 1000);
      this.generationRateLimitPerPlayer = VssMath.clamp((long)this.generationRateLimitPerPlayer, 1, 1000);
      this.generationConcurrencyLimitPerPlayer = VssMath.clamp((long)this.generationConcurrencyLimitPerPlayer, 1, 1000);
      this.perDimensionTimestampCacheSizeMB = VssMath.clamp((long)this.perDimensionTimestampCacheSizeMB, 1, 256);
      this.entityTrackingDistanceChunks = VssMath.clamp((long)this.entityTrackingDistanceChunks, 1, 512);
      this.forceLoadRadiusChunks = VssMath.clamp((long)this.forceLoadRadiusChunks, 1, 64);
      if (this.probeBudgetMicros != 0) {
         this.probeBudgetMicros = VssMath.clamp((long)this.probeBudgetMicros, 100, 25000);
      }

      if (this.serializedColumnCacheSizeMB != 0) {
         this.serializedColumnCacheSizeMB = VssMath.clamp((long)this.serializedColumnCacheSizeMB, 1, 2048);
      }
      if (this.trackedEntityTypes == null) {
         this.trackedEntityTypes = new ArrayList<>(List.of("minecraft:player"));
      }
   }
}
