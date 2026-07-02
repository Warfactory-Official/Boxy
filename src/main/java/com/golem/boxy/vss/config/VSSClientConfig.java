package com.golem.boxy.vss.config;
import com.golem.boxy.vss.common.VssMath;
import java.util.ArrayList;
import java.util.List;

public class VSSClientConfig extends JsonConfig {
   private static final String FILE_NAME = "vss-client-config.json";
   public static VSSClientConfig CONFIG = load(VSSClientConfig.class, "vss-client-config.json");
   public boolean receiveServerLods = true;
   public int lodDistanceChunks = 0;
   public boolean offThreadSectionProcessing = true;
   public boolean extendEntityRenderDistance = true;
   public List<String> renderedEntityTypes = new ArrayList<>(List.of("minecraft:player"));
   public int entityRenderDistanceChunks = 32;
   // Mipmap entity/skin textures so distant entities don't alias into garbled noise. Off by default: it
   // changes texture handling globally for all entities and costs a little VRAM. Requires a restart.
   public boolean mipmapEntityTextures = false;

   public VSSClientConfig() {
   }

   @Override
   protected String getFileName() {
      return "vss-client-config.json";
   }

   @Override
   protected void validate() {
      this.lodDistanceChunks = VssMath.clamp((long)this.lodDistanceChunks, 0, 512);
      this.entityRenderDistanceChunks = VssMath.clamp((long)this.entityRenderDistanceChunks, 1, 512);
      if (this.renderedEntityTypes == null) {
         this.renderedEntityTypes = new ArrayList<>(List.of("minecraft:player"));
      }
   }

   /** Clamp fields to their valid ranges and persist to disk. Used by the in-game (Embeddium) settings page. */
   public void saveClamped() {
      this.validate();
      this.save();
   }
}
