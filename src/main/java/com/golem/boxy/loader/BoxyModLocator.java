package com.golem.boxy.loader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Forge mod locator that finds the (unmodified, Fabric-intermediary) Voxy jar, remaps it to Forge
 * SRG via {@link VoxyRemapper}, and feeds the result into Forge's discovery pipeline. This is the
 * Forge 1.20.1 counterpart of Foxy's {@code FoxyLocator}/{@code FoxyFabricModReader}, but with the
 * extra remap step Forge requires (NeoForge could load Voxy's bytecode as-is; Forge cannot).
 */
public class BoxyModLocator extends AbstractJarFileModLocator {
    private static final Logger LOGGER = LoggerFactory.getLogger("Boxy/Locator");

    @Override
    public String name() {
        return "boxy";
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {}

    @Override
    public Stream<Path> scanCandidates() {
        try {
            Path voxy = findVoxyJar();
            if (voxy == null) {
                LOGGER.info("Boxy: no Voxy jar found in mods; nothing to load.");
                return Stream.empty();
            }
            LOGGER.info("Boxy: found Voxy at {}", voxy);
            ensureRadiumPlayerChunkTickDisabled();
            Path cacheDir = FMLPaths.GAMEDIR.get().resolve(".boxy").resolve("cache");
            Path embeddium = findSodiumAssetSource();
            Path oculus = findOculusJar();
            List<Path> classpath = gatherClasspath();
            // Embeddium must be on the remap classpath so tiny-remapper's MixinExtension can resolve
            // Voxy's sodium.* mixin targets and remap their selectors (e.g. the RenderSectionManager
            // constructor descriptor). Without it those selectors keep intermediary names and fail.
            if (embeddium != null) {
                classpath.add(embeddium);
            }
            // Oculus is the Forge fork of Iris and keeps Iris's net.irisshaders.iris.* classes, which
            // Voxy's iris.* shader mixins target. Putting it on the classpath lets tiny-remapper resolve
            // those targets (and their inheritance) so the iris mixins remap cleanly — the same role
            // Embeddium plays for the sodium.* mixins. Absent Oculus, the iris mixins simply target
            // classes that aren't present at runtime and Mixin drops them (shaders stay off).
            if (oculus != null) {
                classpath.add(oculus);
                LOGGER.info("Boxy: found Oculus (Iris) at {} — enabling shader-pack support", oculus.getFileName());
            }
            Path remapped = VoxyRemapper.remap(voxy, cacheDir, classpath, embeddium, oculus);
            // Feed Voxy's metadata to the FabricLoader facade so it can answer getModContainer("voxy")
            // synthetically (the service layer can't reach the game-layer ModList).
            registerVoxyInfo(voxy, remapped);
            LOGGER.info("Boxy: remapped Voxy -> {}", remapped.getFileName());
            // Also hand Forge Boxy's own standalone game-layer mod (the Voxy Server Side port). It is a
            // complete, reobf'd mod jar bundled inside Boxy's jar; we extract it and return it as a
            // second candidate so Forge discovers it as the "boxy" mod, separate from Voxy's jar.
            Path boxyMod = prepareBoxyMod(cacheDir);
            if (boxyMod != null) {
                LOGGER.info("Boxy: staged standalone Boxy mod -> {}", boxyMod.getFileName());
                return Stream.of(remapped, boxyMod);
            }
            return Stream.of(remapped);
        } catch (Throwable t) {
            LOGGER.error("Boxy: failed to locate/remap Voxy", t);
            return Stream.empty();
        }
    }

    /**
     * Extracts Boxy's bundled standalone game-layer mod (the reobf'd {@code boxy/boxy-mod.libzip}) to the
     * cache and returns its path, for Forge to load as the "boxy" mod. Re-extracted every launch (it is
     * tiny and tracks the installed Boxy version), so there is no stale-cache concern. Returns null if the
     * bundle is absent (older/partial build) — Boxy then loads Voxy only, without the server-side feature.
     */
    private static Path prepareBoxyMod(Path cacheDir) {
        try (InputStream is = BoxyModLocator.class.getResourceAsStream("/boxy/boxy-mod.libzip")) {
            if (is == null) {
                LOGGER.warn("Boxy: boxy-mod.libzip not bundled; server-side LOD mod unavailable.");
                return null;
            }
            Files.createDirectories(cacheDir);
            Path out = cacheDir.resolve("boxy-mod.jar");
            Files.copy(is, out, StandardCopyOption.REPLACE_EXISTING);
            return out;
        } catch (Exception e) {
            LOGGER.error("Boxy: failed to stage the standalone Boxy mod jar", e);
            return null;
        }
    }

    /** Looks in the mods directory for a jar with a {@code fabric.mod.json} declaring id "voxy". */
    private static Path findVoxyJar() {
        Path modsDir = FMLPaths.MODSDIR.get();
        if (modsDir == null || !Files.isDirectory(modsDir)) return null;
        try (Stream<Path> jars = Files.list(modsDir)) {
            return jars
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(BoxyModLocator::isVoxyFabricJar)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads id/version/commit from Voxy's fabric.mod.json and hands them + the remapped jar to the facade. */
    private static void registerVoxyInfo(Path voxyJar, Path remapped) {
        String id = "voxy", version = "0.0.0", commit = null;
        try (ZipFile zip = new ZipFile(voxyJar.toFile())) {
            ZipEntry fmj = zip.getEntry("fabric.mod.json");
            if (fmj != null) {
                try (InputStream is = zip.getInputStream(fmj)) {
                    JsonObject o = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (o.has("id")) id = o.get("id").getAsString();
                    if (o.has("version") && !o.get("version").getAsString().startsWith("$")) {
                        version = o.get("version").getAsString();
                    }
                    if (o.has("custom") && o.getAsJsonObject("custom").has("commit")) {
                        String c = o.getAsJsonObject("custom").get("commit").getAsString();
                        if (!c.startsWith("$")) commit = c;
                    }
                }
            }
        } catch (Exception ignored) {}
        BoxyFabricLoaderImpl.setVoxyInfo(id, version, commit, remapped);
    }

    /** Finds the mods-folder jar (Embeddium) that ships Sodium's shader assets Voxy imports. */
    private static Path findSodiumAssetSource() {
        Path modsDir = FMLPaths.MODSDIR.get();
        if (modsDir == null || !Files.isDirectory(modsDir)) return null;
        try (Stream<Path> jars = Files.list(modsDir)) {
            return jars
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> jarContains(p, "assets/sodium/shaders/include/fog.glsl"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Finds the mods-folder jar (Oculus) carrying the Iris classes Voxy's shader mixins link against.
     * Matched by the presence of {@code net/irisshaders/iris/Iris.class} — on Forge 1.20.1 the only jar
     * that ships those classes is Oculus (the Iris fork). Returns null when no shader mod is installed,
     * in which case Voxy runs without shader support exactly as before.
     */
    private static Path findOculusJar() {
        Path modsDir = FMLPaths.MODSDIR.get();
        if (modsDir == null || !Files.isDirectory(modsDir)) return null;
        try (Stream<Path> jars = Files.list(modsDir)) {
            return jars
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> jarContains(p, "net/irisshaders/iris/Iris.class"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Radium (the Forge Lithium port) breaks player chunk delivery on this stack: with its
     * {@code mixin.world.player_chunk_tick} optimization active, chunks around a moving/flying player
     * intermittently never reach the client — no vanilla render, no collision ("finicky physics") — and
     * Voxy paints LOD terrain into the holes, which presents as LODs rendering over real chunks / being
     * culled wrongly (quirk 43; bisected in-game — every stage of Voxy's pipeline measured healthy while
     * the vanilla chunks were simply absent client-side). Radium reads Lithium's config format from
     * {@code config/lithium.properties}, so when a Lithium-port jar is present this writes the
     * kill-switch for that one optimization before Radium's mixin plugin reads the file (mixin configs
     * initialize after mod discovery, so locate time is early enough). An existing explicit setting for
     * the key — either value — is respected and left untouched.
     */
    private static void ensureRadiumPlayerChunkTickDisabled() {
        final String key = "mixin.world.player_chunk_tick";
        try {
            Path modsDir = FMLPaths.MODSDIR.get();
            if (modsDir == null || !Files.isDirectory(modsDir)) return;
            boolean lithiumPortPresent;
            try (Stream<Path> jars = Files.list(modsDir)) {
                lithiumPortPresent = jars
                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .anyMatch(p -> jarContains(p, "lithium.mixins.json"));
            }
            if (!lithiumPortPresent) return;

            Path config = FMLPaths.CONFIGDIR.get().resolve("lithium.properties");
            if (Files.exists(config)) {
                for (String line : Files.readAllLines(config, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith(key) && trimmed.substring(key.length()).trim().startsWith("=")) {
                        return; // explicitly configured (either way) — respect it
                    }
                }
            } else {
                Files.createDirectories(config.getParent());
            }
            String entry = System.lineSeparator()
                    + "# Added by Boxy: Radium's player_chunk_tick optimization drops chunk delivery to moving" + System.lineSeparator()
                    + "# players on this stack (holes with no render/collision that Voxy fills with LODs, quirk 43)." + System.lineSeparator()
                    + key + "=false" + System.lineSeparator();
            Files.writeString(config, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            LOGGER.info("Boxy: Radium/Lithium port detected — wrote {}=false to {} (chunk-delivery workaround, quirk 43)",
                    key, config.getFileName());
        } catch (Exception e) {
            LOGGER.warn("Boxy: failed to apply the Radium player_chunk_tick workaround", e);
        }
    }

    private static boolean isVoxyFabricJar(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            // Skip anything Forge can already load on its own.
            if (zip.getEntry("META-INF/mods.toml") != null) return false;
            ZipEntry fmj = zip.getEntry("fabric.mod.json");
            if (fmj == null) return false;
            try (InputStream is = zip.getInputStream(fmj)) {
                JsonObject obj = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
                return obj.has("id") && obj.get("id").getAsString().equals("voxy");
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Classpath for tiny-remapper's inheritance / mixin-target resolution. The key entry is an
     * intermediary-named Minecraft jar (derived from Forge's runtime SRG MC), without which Voxy's
     * inherited member calls and mixin selectors do not fully remap.
     */
    private static List<Path> gatherClasspath() {
        List<Path> cp = new ArrayList<>();
        Path srgMc = findMinecraftJar();
        if (srgMc != null) {
            try {
                Path cacheDir = FMLPaths.GAMEDIR.get().resolve(".boxy").resolve("cache");
                cp.add(VoxyRemapper.prepareIntermediaryMc(srgMc, cacheDir));
                LOGGER.info("Boxy: using intermediary Minecraft classpath from {}", srgMc.getFileName());
            } catch (Exception e) {
                LOGGER.warn("Boxy: could not build intermediary MC classpath; member/mixin remap may be incomplete", e);
            }
        } else {
            LOGGER.warn("Boxy: Minecraft jar not found; member/mixin remap may be incomplete");
        }
        return cp;
    }

    // The client (joined) SRG jar has Minecraft.class; a dedicated-server SRG jar has MinecraftServer.class.
    // tiny-remapper needs whichever is present on its classpath to resolve MC references AND mixin shadows
    // (without it, intermediary names like Registries.field_41241 or @Shadow field_11574 survive -> crash).
    // We STRONGLY prefer the client/joined jar — it also carries client classes, so client-side Voxy mixins
    // remap fully (the original Boxy behaviour) — and only fall back to a server jar when NO client jar exists
    // anywhere (a dedicated server). Mixing this up (e.g. picking a server jar on a client) under-remaps the
    // client-side mixins.
    private static final String CLIENT_MARKER = "net/minecraft/client/Minecraft.class";
    private static final String SERVER_MARKER = "net/minecraft/server/MinecraftServer.class";

    // Boxy is pinned to one MC version, but a launcher's shared libraries dir (e.g. Prism/MultiMC share
    // one libraries/ across all instances) can hold SRG Minecraft jars for several versions at once.
    // Remapping Voxy against the wrong-version jar mis-resolves mixin targets/shadows and throws
    // MixinTransformerError when those mixins apply at world load — the same wrong-classpath failure mode
    // as quirks 21/22. So jar selection must match this version, not just grab the first -srg.jar found.
    private static final String MC_VERSION = "1.20.1";

    /**
     * Finds Forge's runtime (SRG) Minecraft jar — the client (joined) jar on a client, or the
     * {@code server-*-srg.jar} on a dedicated server. We need the SRG jar specifically (its members are
     * {@code f_/m_}-named); the raw obfuscated Mojang jar does not contain these class names.
     */
    private static Path findMinecraftJar() {
        Path client = findMarkerJar(CLIENT_MARKER, "client");
        return client != null ? client : findMarkerJar(SERVER_MARKER, "server");
    }

    private static Path findMarkerJar(String marker, String subdir) {
        // 1. classpath-style system properties.
        for (String prop : new String[]{"legacyClassPath", "java.class.path", "jdk.module.path"}) {
            Path p = searchPathList(System.getProperty(prop, ""), marker);
            if (p != null) return p;
        }
        // 2. The launcher's library directory (Prism/MultiMC/vanilla set -DlibraryDirectory).
        String libDir = System.getProperty("libraryDirectory");
        if (libDir != null && !libDir.isBlank()) {
            Path p = searchLibraries(Path.of(libDir).resolve("net").resolve("minecraft").resolve(subdir), marker);
            if (p != null) return p;
        }
        // 3. Walk up from the game directory looking for a libraries/net/minecraft/<subdir> folder.
        try {
            Path g = FMLPaths.GAMEDIR.get();
            for (int i = 0; i < 6 && g != null; i++, g = g.getParent()) {
                Path p = searchLibraries(g.resolve("libraries").resolve("net").resolve("minecraft").resolve(subdir), marker);
                if (p != null) return p;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Path searchPathList(String value, String marker) {
        if (value == null || value.isEmpty()) return null;
        List<Path> srg = new ArrayList<>();
        List<Path> other = new ArrayList<>();
        for (String entry : value.split(java.io.File.pathSeparator)) {
            if (!entry.endsWith(".jar")) continue;
            Path p = Path.of(entry);
            if (Files.exists(p) && jarContains(p, marker)) {
                (entry.contains("srg") ? srg : other).add(p); // prefer the SRG jar
            }
        }
        Path pick = pickForMcVersion(srg);
        return pick != null ? pick : pickForMcVersion(other);
    }

    private static Path searchLibraries(Path dir, String marker) {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> walk = Files.walk(dir, 3)) {
            List<Path> candidates = walk
                    .filter(p -> p.getFileName().toString().endsWith("-srg.jar"))
                    .filter(p -> jarContains(p, marker))
                    .toList();
            return pickForMcVersion(candidates);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * From candidate Minecraft SRG jars, pick the one matching {@link #MC_VERSION}. A shared libraries dir
     * can hold SRG jars for several MC versions; picking the wrong one remaps Voxy against the wrong
     * Minecraft and breaks mixin application at runtime. Falls back to the first candidate (with a warning)
     * only when none carries the version in its name/path.
     */
    private static Path pickForMcVersion(List<Path> candidates) {
        if (candidates.isEmpty()) return null;
        for (Path p : candidates) {
            if (matchesMcVersion(p)) return p;
        }
        Path fallback = candidates.get(0);
        if (candidates.size() > 1) {
            LOGGER.warn("Boxy: no SRG Minecraft jar matched MC {}; falling back to {} (remap may target the wrong version). Candidates: {}",
                    MC_VERSION, fallback.getFileName(),
                    candidates.stream().map(c -> c.getFileName().toString()).toList());
        }
        return fallback;
    }

    private static boolean matchesMcVersion(Path jar) {
        String name = jar.getFileName().toString();
        String full = jar.toString().replace('\\', '/');
        return name.contains("-" + MC_VERSION + "-")  // client-1.20.1-<mcp>-srg.jar
                || name.contains("-" + MC_VERSION + ".")
                || full.contains("/" + MC_VERSION + "/")   // .../net/minecraft/client/1.20.1/...
                || full.contains("/" + MC_VERSION + "-");  // .../1.20.1-20230612.114412/...
    }

    private static boolean jarContains(Path jar, String entry) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry(entry) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
