package com.golem.boxy.vss.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.golem.boxy.vss.common.VSSLogger;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;

public abstract class JsonConfig {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

   public JsonConfig() {
   }

   protected abstract String getFileName();

   protected void validate() {
   }

   public void save() {
      try {
         Path path = this.resolvePath();
         Files.createDirectories(path.getParent());
         Files.writeString(path, GSON.toJson(this));
      } catch (Exception e) {
         VSSLogger.error("Failed to save config " + this.getFileName(), e);
      }
   }

   private Path resolvePath() {
      return FMLPaths.CONFIGDIR.get().resolve(this.getFileName());
   }

   protected static <T extends JsonConfig> T load(Class<T> type, String fileName) {
      Path path = FMLPaths.CONFIGDIR.get().resolve(fileName);
      T config = null;
      if (Files.isRegularFile(path)) {
         try {
            config = (T) GSON.fromJson(Files.readString(path), type);
            if (config == null) {
               VSSLogger.warn("Config " + fileName + " was empty/invalid, regenerating with defaults");
            }
         } catch (Exception e) {
            VSSLogger.error("Failed to read config " + fileName + ", regenerating with defaults", e);
         }
      }

      if (config == null) {
         try {
            config = type.getDeclaredConstructor().newInstance();
         } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot instantiate config " + type.getName(), e);
         }
      }

      // Always re-save: merges newly-added options into an existing file (and rewrites a corrupt/partial one)
      // instead of leaving a stale file that's missing the new keys until it's manually deleted.
      config.validate();
      config.save();
      return config;
   }
}
