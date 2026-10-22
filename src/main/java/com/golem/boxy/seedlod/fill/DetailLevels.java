package com.golem.boxy.seedlod.fill;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;

/**
 * The finest Voxy level worth writing at a distance. Voxy's traversal splits a node into its children when the
 * node's projected area exceeds {@code subDivisionSize²} pixels (see {@code MixinVoxyMipperLodLight}). A level-L
 * node is a cube 32 << L blocks across whose visible faces project to roughly {@code ((32 << L) * focal / d)²}
 * pixels at distance d (up to ~1.7x that seen corner-on), focal being pixels per block at unit distance. Writing
 * one level finer than Voxy needs costs four times the columns, so the level chosen is the coarsest Voxy will not
 * split face-on ({@link #MARGIN} 1). Seen corner-on Voxy may want one level finer a little further out; it then
 * draws the coarser node as a leaf, slightly blurrier, never a hole.
 */
final class DetailLevels {
   private static final double MARGIN = 1.0;
   private static final double FALLBACK_SUBDIVISION = 64.0;

   private DetailLevels() {
   }

   /** Pixels per block at unit distance for the current window and field of view. */
   static double focalPixels() {
      Minecraft client = Minecraft.getInstance();
      if (client == null || client.getWindow() == null) {
         return 0.0;
      }
      double fov = Math.toRadians(client.options.fov().get());
      return client.getWindow().getHeight() / (2.0 * Math.tan(fov / 2.0));
   }

   static double subdivisionPixels() {
      try {
         double size = VoxyConfig.CONFIG.subDivisionSize;
         return size > 0.0 ? size : FALLBACK_SUBDIVISION;
      } catch (LinkageError | RuntimeException e) {
         return FALLBACK_SUBDIVISION;
      }
   }

   /** Distance in blocks within which Voxy wants level 0, i.e. never splits a level-0 node's parent. */
   static double fullDetailReach(double focal, double subdivision) {
      return focal <= 0.0 ? 256.0 : 64.0 * focal / subdivision / MARGIN;
   }

   /** Finest level needed at {@code distance}: 0 within the full-detail reach, one more per doubling beyond. */
   static int levelAt(double distance, double fullDetailReach) {
      if (distance <= fullDetailReach) {
         return 0;
      }
      int level = 1 + (int)Math.floor(Math.log(distance / fullDetailReach) / Math.log(2.0));
      return Math.min(level, 4);
   }

   /** Voxy's drawn radius in blocks; its render distance counts level-4 sections (512 blocks). */
   static double voxyReachBlocks() {
      try {
         return VoxyConfig.CONFIG.sectionRenderDistance * 512.0;
      } catch (LinkageError | RuntimeException e) {
         return 0.0;
      }
   }
}
