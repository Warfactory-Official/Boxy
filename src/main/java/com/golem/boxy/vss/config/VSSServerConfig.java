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
   public int syncOnLoadRateLimitPerPlayer = 800;
   public int syncOnLoadConcurrencyLimitPerPlayer = 200;
   public int generationRateLimitPerPlayer = 80;
   public int generationConcurrencyLimitPerPlayer = 16;
   public int perDimensionTimestampCacheSizeMB = 32;
   public boolean extendEntityTracking = true;
   public List<String> trackedEntityTypes = new ArrayList<>(List.of("minecraft:player"));
   public int entityTrackingDistanceChunks = 32;
   public boolean forceLoadTrackedEntities = false;
   public int forceLoadRadiusChunks = 2;

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
      if (this.trackedEntityTypes == null) {
         this.trackedEntityTypes = new ArrayList<>(List.of("minecraft:player"));
      }
   }
}
