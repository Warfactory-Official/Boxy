package com.golem.boxy.seedlod.world;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * A short digest of everything that shapes terrain: noise settings, density functions, noise parameters,
 * biome parameter lists, and the set of biomes. Both sides compute it the same way over their own registries;
 * equal digests mean a shared generator decodes on the client into the same terrain. Each registry is hashed
 * separately so a mismatch can name what differs. Decoration (features, structures) is not part of it: those
 * only change details, and a client missing a mod's decoration still draws the right ground.
 */
public final class WorldgenFingerprint {
   private WorldgenFingerprint() {
   }

   /** Registry name to 8-hex-digit digest, in a fixed order. */
   public static Map<String, String> of(RegistryAccess registries) {
      DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
      Map<String, String> parts = new LinkedHashMap<>();
      parts.put("noise_settings", hashed(registries, ops, Registries.NOISE_SETTINGS, NoiseGeneratorSettings.DIRECT_CODEC));
      parts.put("density_function", hashed(registries, ops, Registries.DENSITY_FUNCTION, DensityFunction.DIRECT_CODEC));
      parts.put("noise", hashed(registries, ops, Registries.NOISE, NormalNoise.NoiseParameters.DIRECT_CODEC));
      parts.put("biome_parameters", hashed(registries, ops, Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,
         MultiNoiseBiomeSourceParameterList.DIRECT_CODEC));
      parts.put("biomes", keysOnly(registries, Registries.BIOME));
      return parts;
   }

   /** Names of the parts that differ, empty when the two describe the same terrain. */
   public static List<String> differences(Map<String, String> ours, Map<String, String> theirs) {
      java.util.Set<String> names = new java.util.TreeSet<>(ours.keySet());
      names.addAll(theirs.keySet());
      return names.stream().filter(name -> !java.util.Objects.equals(ours.get(name), theirs.get(name))).toList();
   }

   private static <T> String hashed(RegistryAccess registries, DynamicOps<JsonElement> ops, ResourceKey<? extends Registry<T>> which, Codec<T> codec) {
      Optional<? extends Registry<T>> registry = registries.registry(which);
      if (registry.isEmpty()) {
         return "none";
      }
      TreeMap<String, T> sorted = new TreeMap<>();
      registry.get().holders().forEach(holder -> sorted.put(holder.key().location().toString(), holder.value()));
      MessageDigest digest = sha256();
      sorted.forEach((name, value) -> {
         digest.update(name.getBytes(StandardCharsets.UTF_8));
         // Values that cannot be written out count by name only.
         codec.encodeStart(ops, value).result().ifPresent(json -> digest.update(json.toString().getBytes(StandardCharsets.UTF_8)));
      });
      return shortHex(digest);
   }

   private static String keysOnly(RegistryAccess registries, ResourceKey<? extends Registry<Biome>> which) {
      MessageDigest digest = sha256();
      registries.registry(which).ifPresent(registry -> registry.holders()
         .map(Holder.Reference::key).map(key -> key.location().toString()).sorted()
         .forEach(name -> digest.update(name.getBytes(StandardCharsets.UTF_8))));
      return shortHex(digest);
   }

   private static MessageDigest sha256() {
      try {
         return MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException(e);
      }
   }

   private static String shortHex(MessageDigest digest) {
      return HexFormat.of().formatHex(digest.digest(), 0, 4);
   }
}
