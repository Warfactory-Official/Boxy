package com.golem.boxy.vss.config;
import java.util.ArrayList;
import java.util.List;

public class VSSClientConfig extends JsonConfig {
   private static final String FILE_NAME = "vss-client-config.json";
   public static VSSClientConfig CONFIG = load(VSSClientConfig.class, "vss-client-config.json");
   public boolean receiveServerLods = true;
   public int lodDistanceChunks = 0;
   public boolean offThreadSectionProcessing = true;
   // Spiral rescan strategy. false (default) = the original behaviour: the outward LOD scan restarts from
   // ring 0 whenever the player crosses a chunk boundary or a dirty-column broadcast arrives — simple and
   // proven, but re-walks the whole scan area each time (costly at large LOD distances). true = incremental:
   // the scan re-centers by the distance moved and dirty broadcasts re-open only the affected ring, keeping
   // confirmed inner rings closed. Much cheaper on the client thread while travelling, but newer; kept opt-in
   // so the safe path is the default. Read live — toggling takes effect on the next scan.
   public boolean incrementalSpiralRescan = false;
   public boolean extendEntityRenderDistance = true;
   public List<String> renderedEntityTypes = new ArrayList<>(List.of("minecraft:player"));
   public int entityRenderDistanceChunks = 32;
   // Fix distant-entity z-fighting by re-banding the depth buffer around each tracked entity at render time
   // (projection z-row swap + glDepthRange around a per-entity flush; see DistantEntityDepthFix and
   // DistantEntityDepthMode: OFF / BASIC = depth stays exact / PRECISE = ~10x more depth steps for a
   // slightly fuzzy occlusion boundary). Applies with or without an Iris shaderpack (shaderpack support
   // is experimental). Read live.
   public DistantEntityDepthMode distantEntityDepthMode = DistantEntityDepthMode.PRECISE;
   // Round Voxy's hierarchical-Z occlusion buffer UP to the next power of two instead of down, so its depth
   // pyramid over-estimates (the only direction that is safe for an occlusion test) rather than under-estimates.
   // Voxy rounds down, which makes level 0 a downscale that its single 2x2 textureGather cannot fully cover;
   // the resulting too-small maximum over-culls LOD nodes wherever the per-pixel depth gradient is steep —
   // i.e. the grazing views a high FOV pushes into the screen periphery, which is why LOD sections go missing
   // at the screen edges at FOV 110. Costs HiZ memory (~22 MB vs ~2.8 MB at 1920x1080). See
   // MixinVoxyHiZConservative. Read live — toggling reallocates on the next frame.
   public boolean conservativeHiZ = true;
   // Lift a mipped LOD voxel's light to the maximum over its eight children (air included — Minecraft keeps
   // light at a position, so an opaque block's own entry is ~0 and the light you see on its face lives in
   // the adjacent air). Voxy's Mipper.mip elects its representative child by opacity alone and returns it
   // verbatim, so the elected child's stored 0 becomes the node's light and distant terrain darkens as the
   // LOD coarsens. See MixinVoxyMipperLodLight. Applies at ingest — existing stored LODs keep their old
   // light until re-ingested.
   public boolean brightenMippedLodLight = true;
   // Supply full skylight for sections Minecraft leaves without a sky-light array because they are uniformly
   // lit. Voxy's getLightingSupplier treats an absent/empty layer as light 0, so open flat terrain ingests
   // pitch black while playing; region files store explicit arrays, which is why /voxy import produces
   // correct data for the same terrain. See MixinVoxyIngestUniformSkyLight. Applies at ingest.
   public boolean fillUniformSkyLight = true;

   public VSSClientConfig() {
   }

   @Override
   protected String getFileName() {
      return "vss-client-config.json";
   }

   @Override
   protected void validate() {
      this.lodDistanceChunks = Math.clamp((long)this.lodDistanceChunks, 0, 512);
      this.entityRenderDistanceChunks = Math.clamp((long)this.entityRenderDistanceChunks, 1, 512);
      if (this.renderedEntityTypes == null) {
         this.renderedEntityTypes = new ArrayList<>(List.of("minecraft:player"));
      }
      if (this.distantEntityDepthMode == null) {
         this.distantEntityDepthMode = DistantEntityDepthMode.PRECISE;
      }
   }

   /** Clamp fields to their valid ranges and persist to disk. Used by the native Sodium settings page. */
   public void saveClamped() {
      this.validate();
      this.save();
   }
}
