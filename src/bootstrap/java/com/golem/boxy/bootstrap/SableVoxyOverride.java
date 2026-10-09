package com.golem.boxy.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lifts Voxy's hard {@code sable = incompatible} block when Sable is actually installed.
 *
 * <p>Voxy 0.2.15-beta for NeoForge 1.21.1 declares in its own
 * {@code META-INF/neoforge.mods.toml}:</p>
 * <pre>
 * [[dependencies.voxy]]
 * modId="sable"
 * type="incompatible"
 * reason="Difficult to diagnose slowdowns"
 * versionRange="[0,)"
 * </pre>
 * <p>NeoForge's {@code ModSorter} aborts startup whenever both mods are present, before any game code
 * runs. The metadata carries no functional patch; it is a load gate to avoid slowdown bug reports
 * (Sable is intrusive: Rapier physics, sub-level chunk tracking, extensive mixins). Boxy does not
 * modify Voxy's jar. Instead it uses NeoForge's supported escape hatch:
 * {@code config/fml.toml} {@code dependencyOverrides.voxy = ["-sable"]}, which tells the sorter to
 * ignore Voxy's constraint against Sable for this instance.</p>
 *
 * <p>File writes affect the next launch if FML already loaded its config, so this helper also
 * best-effort patches the already-loaded in-memory {@code FMLConfig.dependencyOverrides} via
 * reflection. Both paths are safe no-ops when Sable or Voxy are absent, when reflection is blocked,
 * or when the override already exists.</p>
 */
public final class SableVoxyOverride {
    static final String TARGET_MOD = "voxy";
    static final String REMOVED_DEP = "sable";
    static final String OVERRIDE_ENTRY = "-sable";

    private static final Pattern DOTTED_VOXY =
            Pattern.compile("(?m)^(?<prefix>\\s*dependencyOverrides\\s*\\.\\s*\"?voxy\"?\\s*=\\s*)(?<value>\\[[^\\]]*\\]|\"[^\"]*\"|'[^']*')(?<suffix>\\s*(?:#.*)?)$");
    private static final Pattern TABLE_HEADER =
            Pattern.compile("(?m)^\\s*\\[dependencyOverrides\\]\\s*(?:#.*)?$");
    private static final Pattern TABLE_VOXY =
            Pattern.compile("(?m)^(?<prefix>\\s*\"?voxy\"?\\s*=\\s*)(?<value>\\[[^\\]]*\\]|\"[^\"]*\"|'[^']*')(?<suffix>\\s*(?:#.*)?)$");
    private static final Pattern INLINE_TABLE =
            Pattern.compile("(?m)^(?<prefix>\\s*dependencyOverrides\\s*=\\s*)\\{(?<body>[^}]*)\\}(?<suffix>\\s*(?:#.*)?)$");
    private static final Pattern MOD_ID = Pattern.compile("modId\\s*=\\s*[\"'](?<id>[^\"']+)[\"']");

    private SableVoxyOverride() {
    }

    /** File + in-memory override when both Voxy and Sable jars are present in {@code modsDir}. */
    public static void ensureVoxySableCompat(Path gameDir, Path modsDir) {
        try {
            if (!isModPresent(modsDir, TARGET_MOD) || !isModPresent(modsDir, REMOVED_DEP)) {
                return;
            }
        } catch (Exception ignored) {
            return;
        }
        ensureFileOverride(gameDir);
        tryPatchInMemory();
    }

    /** In-memory only variant for discovery-time call sites without a game directory. */
    public static void ensureInMemoryOnly(Path modsDir) {
        try {
            if (modsDir == null || !Files.isDirectory(modsDir)) {
                tryPatchInMemory();
                return;
            }
            if (!isModPresent(modsDir, TARGET_MOD) || !isModPresent(modsDir, REMOVED_DEP)) {
                return;
            }
        } catch (Exception ignored) {
            return;
        }
        tryPatchInMemory();
    }

    static boolean isModPresent(Path modsDir, String modId) {
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return false;
        }
        try (var files = Files.list(modsDir)) {
            var jars = files.filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")).toList();
            for (Path jar : jars) {
                if (isModJar(jar, modId)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    static boolean isModJar(Path jar, String modId) {
        try (JarFile zip = new JarFile(jar.toFile())) {
            var entry = zip.getJarEntry("META-INF/neoforge.mods.toml");
            if (entry == null) {
                return false;
            }
            try (InputStream input = zip.getInputStream(entry)) {
                String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                Matcher matcher = MOD_ID.matcher(metadata);
                while (matcher.find()) {
                    if (modId.equals(matcher.group("id"))) {
                        return true;
                    }
                }
                return false;
            }
        } catch (IOException ignored) {
            return false;
        }
    }

    static void ensureFileOverride(Path gameDir) {
        if (gameDir == null) {
            return;
        }
        Path configFile = gameDir.resolve("config").resolve("fml.toml");
        try {
            Files.createDirectories(configFile.getParent());
            String current = Files.exists(configFile) ? Files.readString(configFile, StandardCharsets.UTF_8) : "";
            String updated = ensureOverrideInText(current);
            if (updated == null || updated.equals(current)) {
                return;
            }
            Files.writeString(configFile, updated, StandardCharsets.UTF_8);
            System.out.println("[Boxy] Added Voxy/Sable dependency override to config/fml.toml (voxy ignores its sable incompatibility)");
        } catch (Exception error) {
            System.err.println("[Boxy] Warning: could not write Voxy/Sable dependency override: " + error.getMessage());
        }
    }

    /**
     * Returns updated TOML content with {@code dependencyOverrides.voxy} containing {@code "-sable"},
     * or {@code null} when no change is needed. Pure function for testing; never touches disk.
     */
    static String ensureOverrideInText(String content) {
        String input = content == null ? "" : content;
        if (hasSableOverride(input)) {
            return null;
        }
        Matcher dotted = DOTTED_VOXY.matcher(input);
        if (dotted.find()) {
            String merged = mergeValue(dotted.group("value"));
            if (merged == null) {
                return null;
            }
            return dotted.replaceFirst(Matcher.quoteReplacement(dotted.group("prefix") + merged + dotted.group("suffix")));
        }
        Matcher table = TABLE_HEADER.matcher(input);
        if (table.find()) {
            int sectionStart = table.end();
            int sectionEnd = findSectionEnd(input, sectionStart);
            String section = input.substring(sectionStart, sectionEnd);
            Matcher entry = TABLE_VOXY.matcher(section);
            if (entry.find()) {
                String merged = mergeValue(entry.group("value"));
                if (merged == null) {
                    return null;
                }
                String replaced = entry.replaceFirst(Matcher.quoteReplacement(entry.group("prefix") + merged + entry.group("suffix")));
                return input.substring(0, sectionStart) + replaced + input.substring(sectionEnd);
            }
            String insert = "voxy = [\"-sable\"]\n";
            String prefix = input.substring(0, sectionStart);
            if (!prefix.endsWith("\n")) {
                prefix += "\n";
            }
            return prefix + insert + input.substring(sectionStart);
        }
        Matcher inline = INLINE_TABLE.matcher(input);
        if (inline.find()) {
            String body = inline.group("body");
            if (body.trim().isEmpty()) {
                return inline.replaceFirst(Matcher.quoteReplacement(inline.group("prefix") + "{ voxy = [\"-sable\"] }" + inline.group("suffix")));
            }
            String rewritten = rewriteInlineBody(body);
            if (rewritten == null) {
                return null;
            }
            return inline.replaceFirst(Matcher.quoteReplacement(inline.group("prefix") + "{ " + rewritten + " }" + inline.group("suffix")));
        }
        String suffix = input.isEmpty() || input.endsWith("\n") ? "" : "\n";
        return input + suffix + "dependencyOverrides.voxy = [\"-sable\"]\n";
    }

    private static boolean hasSableOverride(String content) {
        Matcher dotted = DOTTED_VOXY.matcher(content);
        while (dotted.find()) {
            if (valueContainsSable(dotted.group("value"))) {
                return true;
            }
        }
        Matcher table = TABLE_HEADER.matcher(content);
        while (table.find()) {
            int sectionStart = table.end();
            int sectionEnd = findSectionEnd(content, sectionStart);
            Matcher entry = TABLE_VOXY.matcher(content.substring(sectionStart, sectionEnd));
            while (entry.find()) {
                if (valueContainsSable(entry.group("value"))) {
                    return true;
                }
            }
        }
        Matcher inline = INLINE_TABLE.matcher(content);
        while (inline.find()) {
            if (inlineBodyContainsSable(inline.group("body"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean valueContainsSable(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("[")) {
            return trimmed.contains("\"-sable\"") || trimmed.contains("'-sable'");
        }
        return trimmed.equals("\"-sable\"") || trimmed.equals("'-sable'");
    }

    private static boolean inlineBodyContainsSable(String body) {
        Matcher entry = Pattern.compile("(?m)(?<key>\"?voxy\"?)\\s*=\\s*(?<value>\\[[^\\]]*\\]|\"[^\"]*\"|'[^']*')").matcher(body);
        while (entry.find()) {
            if (valueContainsSable(entry.group("value"))) {
                return true;
            }
        }
        return false;
    }

    private static String mergeValue(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("[")) {
            if (valueContainsSable(trimmed)) {
                return null;
            }
            String inner = trimmed.substring(1, trimmed.lastIndexOf(']'));
            if (inner.trim().isEmpty()) {
                return "[\"-sable\"]";
            }
            return "[" + inner + ", \"-sable\"]";
        }
        if (valueContainsSable(trimmed)) {
            return null;
        }
        return "[" + trimmed + ", \"-sable\"]";
    }

    private static String rewriteInlineBody(String body) {
        Matcher entry = Pattern.compile("(?<key>\"?voxy\"?)\\s*=\\s*(?<value>\\[[^\\]]*\\]|\"[^\"]*\"|'[^']*')").matcher(body);
        if (entry.find()) {
            String merged = mergeValue(entry.group("value"));
            if (merged == null) {
                return null;
            }
            return entry.replaceFirst(Matcher.quoteReplacement(entry.group("key") + " = " + merged));
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            return "voxy = [\"-sable\"]";
        }
        String separator = trimmed.endsWith(",") ? " " : ", ";
        return body + separator + "voxy = [\"-sable\"]";
    }

    private static int findSectionEnd(String content, int from) {
        Matcher nextHeader = Pattern.compile("(?m)^\\s*\\[.+\\]\\s*(?:#.*)?$").matcher(content);
        if (nextHeader.find(from)) {
            return nextHeader.start();
        }
        return content.length();
    }

    /**
     * Best-effort patch of the already-loaded {@code FMLConfig.dependencyOverrides} map so the
     * current launch (whose config was read before this bootstrap ran) still skips Voxy's sable
     * gate. Failures are swallowed: the file write covers the next launch.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean tryPatchInMemory() {
        try {
            Class<?> config = Class.forName("net.neoforged.fml.loading.FMLConfig");
            java.lang.reflect.Field field = config.getDeclaredField("dependencyOverrides");
            field.setAccessible(true);
            Object current = field.get(null);
            java.util.Map<String, java.util.List<?>> copy = new java.util.HashMap<>();
            if (current instanceof java.util.Map<?, ?> map) {
                for (var entry : map.entrySet()) {
                    Object key = entry.getKey();
                    Object list = entry.getValue();
                    if (key instanceof String && list instanceof java.util.List<?> items) {
                        copy.put((String) key, new java.util.ArrayList<>(items));
                    }
                }
            }
            java.util.List<?> existing = copy.get(TARGET_MOD);
            if (existing != null) {
                for (Object item : existing) {
                    if (isSableRemoval(item)) {
                        return false;
                    }
                }
            }
            Class<?> overrideClass = Class.forName("net.neoforged.fml.loading.FMLConfig$DependencyOverride");
            java.lang.reflect.Constructor<?> constructor = overrideClass.getDeclaredConstructor(String.class, boolean.class);
            constructor.setAccessible(true);
            Object sableRemoval = constructor.newInstance(REMOVED_DEP, true);
            java.util.List<Object> updated = existing == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>((java.util.Collection<?>) existing);
            updated.add(sableRemoval);
            copy.put(TARGET_MOD, java.util.List.copyOf((java.util.Collection) updated));
            field.set(null, java.util.Collections.unmodifiableMap(copy));
            System.out.println("[Boxy] Applied in-memory Voxy/Sable dependency override for this launch");
            return true;
        } catch (ClassNotFoundException missing) {
            return false;
        } catch (Exception error) {
            System.err.println("[Boxy] Warning: could not apply in-memory Voxy/Sable override: " + error.getMessage());
            return false;
        }
    }

    private static boolean isSableRemoval(Object override) {
        try {
            java.lang.reflect.Method modId = override.getClass().getMethod("modId");
            java.lang.reflect.Method remove = override.getClass().getMethod("remove");
            return REMOVED_DEP.equals(modId.invoke(override)) && Boolean.TRUE.equals(remove.invoke(override));
        } catch (Exception ignored) {
            return false;
        }
    }
}
