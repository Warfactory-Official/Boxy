package com.golem.boxy.loader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Turns Voxy's published (Fabric intermediary) jar into a jar Forge 1.20.1 can load directly:
 *
 * <ol>
 *   <li>Remaps every class — including the statically-baked mixin annotations — from intermediary to
 *       SRG with {@link TinyRemapper} + {@link MixinExtension} (see {@link BoxyMappings}).</li>
 *   <li>Synthesizes a Forge {@code META-INF/mods.toml} from {@code fabric.mod.json}.</li>
 *   <li>Converts {@code voxy.accesswidener} into a Forge {@code META-INF/accesstransformer.cfg}.</li>
 *   <li>Adds the {@code MixinConfigs} manifest attribute so Forge's Mixin picks up Voxy's configs.</li>
 *   <li>Flattens Voxy's bundled {@code META-INF/jars/*} (rocksdb, lwjgl-lmdb/zstd, jedis, lz4, xz,
 *       commons-pool2) into the jar, since Forge does not read Fabric's nested-jar format.</li>
 * </ol>
 *
 * The result is cached under {@code <gamedir>/.boxy/cache} keyed by the input jar's content hash, so
 * the (relatively expensive) remap only happens when the Voxy jar changes.
 */
public final class VoxyRemapper {
    private VoxyRemapper() {}

    private static final String AW_NAME = "voxy.accesswidener";
    // Bump when the remap/patch logic changes, so stale cached jars are not reused.
    // v28: split Boxy's game-layer code into a standalone mod jar; dropped the merged @Mod("boxy").
    // v29: findMinecraftJar() also locates the dedicated-server SRG jar, so the remap classpath is present
    //      on servers (without it, MC refs like Registries.field_41241 were left intermediary -> crash).
    // v30: v29 regressed the CLIENT (could pick a server jar there, under-remapping client mixin shadows);
    //      now strictly prefer the client/joined jar and only fall back to the server jar on a dedicated server.
    // v31: findMinecraftJar() now matches the MC version (1.20.1); a launcher's shared libraries dir can hold
    //      SRG jars for several MC versions, and picking e.g. a 1.18.2 jar mis-remapped Voxy -> MixinTransformerError.
    // v32: Oculus (Iris) shader support — Voxy's iris.* mixins now remap against Oculus when it is on the
    //      classpath, and the cache key gains an "-iris" tag so adding/removing Oculus re-runs the remap
    //      instead of reusing a jar resolved under the other condition.
    // v33: rewrite the iris.* mixins' constructor INVOKE injects to @At("TAIL") (MixinCtorInjectFix) —
    //      Forge's runtime Mixin 0.8.5 rejects constructor INVOKE injection and crashed on shaderpack load.
    // v34: drop MixinCtorInjectFix. Boxy now bundles Mixin Transmogrifier + Fabric Mixin 0.12.11 (like
    //      Sinytra Connector), upgrading the runtime Mixin so the iris.* constructor injects apply at their
    //      original points. The v33 @At("TAIL") reorder left Voxy's pipeline built at the wrong point in
    //      Oculus's lifecycle → inaccurate lighting + a black band under shaderpacks; this restores parity
    //      with Fabric/Sinytra.
    // v35: shade Voxy's references to Boxy's Fabric API stubs (net.fabricmc.api/loader/fabric.*) into the
    //      Boxy-private namespace com.golem.boxy.fabricapi.* (see fabricStubRelocation + shadowJar relocate),
    //      so Boxy's service jar no longer claims net.fabricmc.* and can coexist with Sinytra Connector
    //      (which also ships those packages) instead of crashing with a JPMS split-package error at launch.
    // v36: stop stripping minecraft.MixinWindow (quirk 6). The bundled Mixin upgrade (v34) accepts its
    //      constructor INVOKE inject, so it now applies — restoring Fabric parity (render-thread priority).
    // v37: strip Voxy's flashback.MixinFlashbackRecorder (targets Flashback's removed <init>(RegistryAccess);
    //      0.11.0's Recorder is no-arg). Boxy ships MixinBoxyFlashbackRecorder against the no-arg ctor instead.
    // v38: patch getLightmapUv in assets/voxy/shaders/lod/lighting.glsl (backport of upstream 0.2.17-beta):
    //      the 0.2.14 formula maps lightmap indices 0-15 straight to 0..1 without the 15/16 texel-grid
    //      scale, so high light levels sample off texel centers; upstream rescales to texel centers
    //      (base*(15/16) + 0.5/16). Pure asset rewrite in assemble() — see patchLightingGlsl().
    private static final String REMAP_VERSION = "v38";

    /**
     * Voxy mixins that cannot apply on Forge 1.20.1 + Embeddium and must be removed from the configs:
     * <ul>
     *   <li>{@code chunky.MixinFabricWorld} — targets {@code org.popcraft.chunky.platform.FabricWorld}
     *       (Fabric-only); Boxy provides its own Forge chunky mixin instead.</li>
     *   <li>{@code flashback.MixinFlashbackRecorder} — injects into {@code Recorder.<init>(RegistryAccess)},
     *       a constructor removed in Flashback 0.11.0 (now no-arg), so the injector aborts fatally. Boxy ships
     *       {@code MixinBoxyFlashbackRecorder} (in boxy.mixins.json) targeting the no-arg ctor instead. The
     *       sibling {@code flashback.MixinFlashbackMeta} stays — it's still compatible and does the
     *       metadata (de)serialisation the replacement relies on.</li>
     * </ul>
     * The {@code SodiumOptionsGUI} mixins are kept: Embeddium retains that class with a matching
     * constructor, so Voxy's config page injects into Embeddium's video settings normally.
     *
     * <p><b>No longer stripped:</b> {@code minecraft.MixinWindow} used to be here because its
     * {@code @Inject @At("INVOKE")} into a constructor is rejected by Forge's <em>stock</em> Mixin 0.8.5
     * (quirk 6) — the very same limit that affected Voxy's {@code iris.*} shader mixins. Now that Boxy
     * bundles the upgraded Mixin (Mixin Transmogrifier + Fabric Mixin 0.12.11, quirk 35, active whether
     * standalone or alongside Connector), constructor INVOKE injection is accepted, so MixinWindow applies
     * cleanly and is kept — restoring Fabric parity (it sets the render-thread priority).
     */
    private static final java.util.Set<String> INCOMPATIBLE_MIXINS = java.util.Set.of(
            "chunky.MixinFabricWorld",
            "flashback.MixinFlashbackRecorder"   // Boxy ships a replacement targeting 0.11.0's no-arg Recorder()
    );

    /**
     * Converts Forge's runtime (SRG) Minecraft jar into an intermediary-named jar, cached. This is fed
     * to {@link #remap} as classpath so tiny-remapper can resolve Voxy's inherited member calls and
     * mixin targets (without it, ~inherited calls and some mixin selectors stay intermediary).
     */
    public static Path prepareIntermediaryMc(Path srgMc, Path cacheDir) throws IOException {
        Files.createDirectories(cacheDir);
        // Versioned: the srg->intermediary mapping (e.g. the getColor fallback) changes with REMAP_VERSION,
        // so a stale intermediary MC would make tiny-remapper fail to resolve those members in the Voxy remap.
        Path out = cacheDir.resolve("mc-intermediary-" + REMAP_VERSION + "-" + hash(srgMc) + ".jar");
        if (Files.exists(out)) {
            return out;
        }
        Path tmp = Files.createTempFile("boxy-intmc-", ".jar");
        Files.deleteIfExists(tmp);
        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(BoxyMappings.srgToIntermediary())
                .build();
        try (OutputConsumerPath consumer = new OutputConsumerPath.Builder(tmp).assumeArchive(true).build()) {
            consumer.addNonClassFiles(srgMc, NonClassCopyMode.SKIP_META_INF, remapper);
            remapper.readInputs(srgMc);
            remapper.apply(consumer);
        } finally {
            remapper.finish();
        }
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return out;
    }

    /**
     * Remaps + patches {@code voxyJar}, returning a cached Forge-ready jar. Idempotent.
     *
     * @param sodiumAssetSource jar (Embeddium) to copy {@code assets/sodium/**} from into Voxy's jar.
     *        Voxy {@code #import}s Sodium shaders via its own classloader, which (under Forge's module
     *        isolation) only sees Voxy's jar — so the Sodium shader assets must live inside it.
     * @param oculusJar jar (Oculus) that provides the Iris classes Voxy's {@code iris.*} mixins target,
     *        or null when no shader mod is installed. Only used to key the cache here (the jar itself is
     *        handed to tiny-remapper via {@code remapClasspath}); unlike Sodium, no Iris assets need
     *        copying — Voxy patches the active shaderpack in place rather than importing shipped shaders.
     */
    public static Path remap(Path voxyJar, Path cacheDir, List<Path> remapClasspath, Path sodiumAssetSource, Path oculusJar) throws IOException {
        Files.createDirectories(cacheDir);
        // The "-iris" tag keys the cache on whether Oculus was on the remap classpath, so a user who adds
        // (or removes) Oculus later gets a fresh remap rather than a jar whose iris.* mixins were resolved
        // under the other condition.
        String shaderTag = oculusJar != null ? "-iris" : "";
        Path output = cacheDir.resolve("voxy-srg-" + REMAP_VERSION + shaderTag + "-" + hash(voxyJar) + ".jar");
        if (Files.exists(output)) {
            return output;
        }

        Path commandMod = prepareCommandMod(cacheDir);
        Path remapped = Files.createTempFile("boxy-remap-", ".jar");
        try {
            runTinyRemapper(voxyJar, remapped, remapClasspath);
            assemble(voxyJar, remapped, output, sodiumAssetSource, commandMod);
        } finally {
            Files.deleteIfExists(remapped);
        }
        return output;
    }

    /**
     * Extracts Boxy's bundled command mod ({@code com.golem.boxy.cmd}, already reobfuscated to
     * SRG at build time) to a cached jar so it can be merged into the remapped Voxy jar as a game-layer
     * {@code @Mod}. Returns null if the bundle is absent.
     */
    private static Path prepareCommandMod(Path cacheDir) throws IOException {
        Path out = cacheDir.resolve("boxy-cmd-" + REMAP_VERSION + ".jar");
        if (Files.exists(out)) {
            return out;
        }
        try (InputStream is = VoxyRemapper.class.getResourceAsStream("/boxy/voxy-cmd.libzip")) {
            if (is == null) {
                return null;
            }
            Path tmp = Files.createTempFile("boxy-cmd-", ".jar");
            Files.copy(is, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }

    // Boxy ships stub Fabric API classes (net.fabricmc.api/loader/fabric.*) that Voxy links against. Sinytra
    // Connector ALSO ships net.fabricmc.* (the real Fabric loader), and both Boxy's service jar and Connector
    // are strict-JPMS service-layer modules — so the shared packages collide as a split package and the game
    // crashes in ModuleLayerHandler.buildLayer, BEFORE any Boxy code runs (so we can't detect Connector and
    // step aside at runtime). The fix: shade the stubs into a Boxy-private namespace (com.golem.boxy.fabricapi)
    // so Boxy never claims net.fabricmc.* at all — exactly how tiny-remapper itself is shaded. We relocate
    // Voxy's references here; the matching relocation for Boxy's OWN classes (the stubs + BoxyFabricLoaderImpl)
    // lives in build.gradle's shadowJar `relocate` block. Keep the two in sync with the stub set under
    // src/main/java/net/fabricmc/. (Connector already defers Voxy loading to Boxy, so all features stay intact.)
    private static final String FABRIC_STUB_SRC = "net/fabricmc/";
    private static final String FABRIC_STUB_DST = "com/golem/boxy/fabricapi/";
    private static final String[] FABRIC_STUBS = {
            "api/EnvType", "api/ModInitializer", "api/ClientModInitializer",
            "loader/api/FabricLoader", "loader/api/ModContainer", "loader/api/Version",
            "loader/api/metadata/CustomValue", "loader/api/metadata/ModMetadata",
            "fabric/api/client/command/v2/FabricClientCommandSource",
    };

    /** Relocates Voxy's references to Boxy's Fabric stubs into the Boxy-private namespace (see above). */
    private static IMappingProvider fabricStubRelocation() {
        return out -> {
            for (String c : FABRIC_STUBS) out.acceptClass(FABRIC_STUB_SRC + c, FABRIC_STUB_DST + c);
        };
    }

    private static void runTinyRemapper(Path input, Path output, List<Path> classpath) throws IOException {
        // tiny-remapper's OutputConsumerPath opens the output as a zip filesystem; it must not already
        // exist as an empty file (the caller's createTempFile makes one), or it fails to find a zip header.
        Files.deleteIfExists(output);
        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(BoxyMappings.intermediaryToSrg())
                .withMappings(fabricStubRelocation())
                .renameInvalidLocals(false)
                .ignoreFieldDesc(false)
                .extension(new MixinExtension())
                .build();
        try (OutputConsumerPath consumer = new OutputConsumerPath.Builder(output).assumeArchive(true).build()) {
            // Copy non-class resources, strip jar signatures. Mixin configs / fabric.mod.json ride along;
            // we rewrite the ones that matter during assemble().
            consumer.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, remapper);
            if (classpath != null && !classpath.isEmpty()) {
                remapper.readClassPath(classpath.toArray(Path[]::new));
            }
            remapper.readInputs(input);
            remapper.apply(consumer);
        } finally {
            remapper.finish();
        }
    }

    /** Second pass over the remapped jar: inject mods.toml + AT + manifest, flatten bundled libs. */
    private static void assemble(Path originalVoxyJar, Path remapped, Path output, Path sodiumAssetSource, Path commandMod) throws IOException {
        String fmj = readEntry(remapped, "fabric.mod.json");
        String aw = readEntry(remapped, AW_NAME);

        var mixinConfigNames = new java.util.HashSet<>(VoxyModsToml.mixinConfigs(fmj));
        var selectorRemapper = new MixinSelectorRemapper(BoxyMappings.classMap(), BoxyMappings.memberNameMap());
        var written = new java.util.HashSet<String>();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output))) {
            // 1. Copy the remapped jar's entries, except the ones we replace/drop.
            try (ZipFile in = new ZipFile(remapped.toFile())) {
                var entries = in.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String name = e.getName();
                    if (name.equals("META-INF/MANIFEST.MF")) continue;       // rewritten below
                    if (name.equals(AW_NAME)) continue;                       // -> accesstransformer.cfg
                    if (name.equals("META-INF/mods.toml")) continue;          // synthesized below
                    if (name.startsWith("META-INF/jars/")) continue;          // flattened below
                    if (name.equals("META-INF/accesstransformer.cfg")) continue;
                    if (e.isDirectory()) continue;
                    if (!written.add(name)) continue;
                    // Mixin configs get rewritten to drop entries incompatible with Forge+Embeddium.
                    if (mixinConfigNames.contains(name)) {
                        byte[] data;
                        try (InputStream is = in.getInputStream(e)) {
                            data = is.readAllBytes();
                        }
                        writeEntry(out, name, rewriteMixinConfig(name, new String(data, StandardCharsets.UTF_8)));
                        continue;
                    }
                    // Backported shader fix (upstream 0.2.17-beta): correct lightmap UV texel centers.
                    if (name.equals(LIGHTING_GLSL)) {
                        byte[] data;
                        try (InputStream is = in.getInputStream(e)) {
                            data = is.readAllBytes();
                        }
                        writeEntry(out, name, patchLightingGlsl(new String(data, StandardCharsets.UTF_8)));
                        continue;
                    }
                    // Mixin classes get a second pass to fix MC class names left in selector strings.
                    if (name.contains("/mixin/") && name.endsWith(".class")) {
                        byte[] data;
                        try (InputStream is = in.getInputStream(e)) {
                            data = is.readAllBytes();
                        }
                        // NOTE: Voxy's iris.* mixins inject INVOKE into constructors. Forge's *stock* Mixin
                        // 0.8.5 rejects that, but Boxy now bundles Mixin Transmogrifier + Fabric Mixin
                        // 0.12.11 (see the service-layer ITransformationService registration), which upgrades
                        // the runtime Mixin to a build that accepts constructor injection — exactly as Sinytra
                        // Connector does. So those injects apply at their original points (matching Fabric),
                        // and no @At("TAIL") rewrite is needed. The old MixinCtorInjectFix pass was removed in
                        // v34 because its reorder degraded shader lighting (a black band under Oculus).
                        writeEntry(out, name, selectorRemapper.fix(data));
                        continue;
                    }
                    out.putNextEntry(new ZipEntry(name));
                    try (InputStream is = in.getInputStream(e)) {
                        is.transferTo(out);
                    }
                    out.closeEntry();
                }
            }

            // 2. Rewritten manifest (keep Multi-Release, add MixinConfigs, drop Fabric keys).
            written.add("META-INF/MANIFEST.MF");
            writeEntry(out, "META-INF/MANIFEST.MF", buildManifest(remapped, VoxyModsToml.mixinConfigs(fmj)));

            // 3. Synthetic Forge mods.toml.
            written.add("META-INF/mods.toml");
            writeEntry(out, "META-INF/mods.toml", VoxyModsToml.build(fmj));

            // 4. Access widener -> access transformer.
            if (aw != null) {
                String at = AccessWidenerToAt.convert(aw, BoxyMappings.intermediaryToSrg());
                if (!at.isBlank()) {
                    written.add("META-INF/accesstransformer.cfg");
                    writeEntry(out, "META-INF/accesstransformer.cfg", at);
                }
            }

            // 4b. Forge registers a mod's assets as a resource pack only if it has a pack.mcmeta.
            // Fabric mods don't ship one, so Voxy's shaders/lang/textures would be invisible without this.
            if (written.add("pack.mcmeta")) {
                writeEntry(out, "pack.mcmeta",
                        "{\"pack\":{\"description\":\"Voxy (loaded by Boxy)\",\"pack_format\":15}}");
            }

            // 5. Flatten Voxy's bundled libraries into this jar (skipping anything already written).
            flattenBundledJars(originalVoxyJar, out, written);

            // 6. Copy Sodium shader assets (from Embeddium) so Voxy's #import <sodium:...> resolves
            // inside its own module.
            if (sodiumAssetSource != null) {
                copySodiumAssets(sodiumAssetSource, out, written);
            }

            // 7. Merge Boxy's (SRG-remapped) command mod classes so the @Mod("voxy") that registers
            // /voxy lives on the game layer inside Voxy's jar.
            if (commandMod != null) {
                mergeClasses(commandMod, out, written);
            }
        }
    }

    /** Merges the {@code .class} entries of {@code jar} into {@code out} (skipping already-written). */
    private static void mergeClasses(Path jar, ZipOutputStream out, java.util.Set<String> written) throws IOException {
        try (ZipFile in = new ZipFile(jar.toFile())) {
            var entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
                if (!written.add(e.getName())) continue;
                out.putNextEntry(new ZipEntry(e.getName()));
                try (InputStream is = in.getInputStream(e)) {
                    is.transferTo(out);
                }
                out.closeEntry();
            }
        }
    }

    /** Copies {@code assets/sodium/**} entries from {@code source} (Embeddium) into the Voxy jar. */
    private static void copySodiumAssets(Path source, ZipOutputStream out, java.util.Set<String> written) {
        try (ZipFile in = new ZipFile(source.toFile())) {
            var entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.startsWith("assets/sodium/")) continue;
                if (!written.add(n)) continue;
                out.putNextEntry(new ZipEntry(n));
                try (InputStream is = in.getInputStream(e)) {
                    is.transferTo(out);
                }
                out.closeEntry();
            }
        } catch (IOException ignored) {}
    }

    private static byte[] buildManifest(Path remapped, List<String> mixinConfigs) throws IOException {
        Manifest mf = new Manifest();
        try (ZipFile in = new ZipFile(remapped.toFile())) {
            ZipEntry e = in.getEntry("META-INF/MANIFEST.MF");
            if (e != null) {
                try (InputStream is = in.getInputStream(e)) {
                    mf.read(is);
                }
            }
        }
        Attributes main = mf.getMainAttributes();
        if (main.getValue(Attributes.Name.MANIFEST_VERSION) == null) {
            main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        }
        // Register Voxy's mixin configs the way Forge 1.20.1 actually honors: the manifest MixinConfigs
        // attribute (this is how Embeddium and other Forge mods do it). Keep Multi-Release; drop Fabric keys.
        if (!mixinConfigs.isEmpty()) {
            main.putValue("MixinConfigs", String.join(",", mixinConfigs));
        }
        main.remove(new Attributes.Name("Fabric-Jar-Type"));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        mf.write(bos);
        return bos.toByteArray();
    }

    /** Extracts each {@code META-INF/jars/*.jar} and merges its classes/resources into {@code out}. */
    private static void flattenBundledJars(Path voxyJar, ZipOutputStream out, java.util.Set<String> written) throws IOException {
        try (ZipFile in = new ZipFile(voxyJar.toFile())) {
            var entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.getName().startsWith("META-INF/jars/") || !e.getName().endsWith(".jar")) continue;
                try (ZipInputStream lib = new ZipInputStream(in.getInputStream(e))) {
                    ZipEntry le;
                    while ((le = lib.getNextEntry()) != null) {
                        String n = le.getName();
                        if (le.isDirectory()) continue;
                        if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA"))) continue;
                        if (n.equals("META-INF/MANIFEST.MF") || n.equals("module-info.class")) continue;
                        if (n.equals("fabric.mod.json")) continue;            // libs' own fabric metadata is irrelevant
                        if (!written.add(n)) continue;                        // main jar / earlier lib wins on collision
                        out.putNextEntry(new ZipEntry(n));
                        lib.transferTo(out);
                        out.closeEntry();
                    }
                }
            }
        }
    }

    /** Removes {@link #INCOMPATIBLE_MIXINS} entries from a Voxy mixin config and adds Boxy's own. */
    private static String rewriteMixinConfig(String configName, String json) {
        // Voxy's configs are JSON-with-comments; strip // comments and any resulting trailing commas.
        String clean = json.replaceAll("(?m)//[^\n]*", "").replaceAll(",(\\s*[\\]}])", "$1");
        JsonObject obj = JsonParser.parseString(clean).getAsJsonObject();
        for (String key : new String[]{"mixins", "client", "server"}) {
            if (obj.has(key) && obj.get(key).isJsonArray()) {
                JsonArray filtered = new JsonArray();
                for (JsonElement el : obj.getAsJsonArray(key)) {
                    if (!(el.isJsonPrimitive() && INCOMPATIBLE_MIXINS.contains(el.getAsString()))) {
                        filtered.add(el);
                    }
                }
                obj.add(key, filtered);
            }
        }
        // Add Boxy's Forge Chunky mixin to the common config (its package is me.cortex.voxy.commonImpl.mixin,
        // so "chunky.MixinForgeWorld" resolves to the class Boxy injected). It no-ops if Chunky is absent.
        if (configName.equals("common.voxy.mixins.json")) {
            JsonArray mixins = obj.has("mixins") && obj.get("mixins").isJsonArray()
                    ? obj.getAsJsonArray("mixins") : new JsonArray();
            mixins.add("chunky.MixinForgeWorld");
            mixins.add("chunky.MixinVoxelIngestStarlight");
            obj.add("mixins", mixins);
        }
        return new Gson().toJson(obj);
    }

    /** The shader asset patched by {@link #patchLightingGlsl} (REMAP_VERSION v38). */
    private static final String LIGHTING_GLSL = "assets/voxy/shaders/lod/lighting.glsl";

    /**
     * Backport of the upstream 0.2.17-beta {@code getLightmapUv} fix. 0.2.14 maps the 16x16
     * lightmap indices straight onto 0..1 plus a half-texel offset, without the 15/16 rescale that
     * puts every index on a texel center — so high light levels sample between texels. Upstream:
     * {@code base*(15.0/16.0) + 0.5/16.0}. The replacement is a byte-exact substring swap of the
     * single {@code return clamp(...)} expression; if Voxy's shader ever changes, the pattern
     * misses and the asset is copied unmodified (degrade, don't break).
     */
    private static String patchLightingGlsl(String src) {
        String old = "return clamp((vec2((index>>4)&0xFu, index&0xFu)/15)+vec2(8.0f/256)";
        String fixed = "return clamp((vec2((index>>4)&0xFu, index&0xFu)/15)*(15.0f/16.0f)+vec2(0.5f/16.0f)";
        if (!src.contains(old)) {
            org.slf4j.LoggerFactory.getLogger("Boxy/Remapper")
                    .warn("Boxy: lighting.glsl did not match the expected 0.2.14 getLightmapUv pattern; leaving it unpatched");
            return src;
        }
        return src.replace(old, fixed);
    }

    private static String readEntry(Path jar, String name) throws IOException {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry e = zf.getEntry(name);
            if (e == null) return null;
            try (InputStream is = zf.getInputStream(e)) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static void writeEntry(ZipOutputStream out, String name, String content) throws IOException {
        writeEntry(out, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeEntry(ZipOutputStream out, String name, byte[] content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content);
        out.closeEntry();
    }

    private static String hash(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            throw new IOException("hash failed", e);
        }
    }
}
