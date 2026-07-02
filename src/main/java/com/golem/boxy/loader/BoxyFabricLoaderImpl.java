package com.golem.boxy.loader;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Forge-backed implementation of the Fabric {@link FabricLoader} facade.
 *
 * <p>Boxy's locator runs on Forge's early <em>service</em> layer, so this class loads there too. The
 * service layer can see {@code fmlloader} ({@link FMLLoader}, {@link FMLPaths}) but NOT {@code fmlcore}
 * (e.g. {@code net.minecraftforge.fml.ModList}, which lives on the game layer). A direct reference to
 * {@code ModList} therefore fails with {@code NoClassDefFoundError} when Voxy calls in. So:
 * <ul>
 *   <li>{@link #getModContainer} is served <b>synthetically</b> from the Voxy metadata the locator
 *       already parsed ({@link #setVoxyInfo}) — no {@code ModList} needed.</li>
 *   <li>{@link #isModLoaded} probes {@code ModList} reflectively via the caller's (game-layer)
 *       classloader and falls back to {@code false}.</li>
 *   <li>{@link #getEnvironmentType}/{@link #getConfigDir} use {@code fmlloader}, which is fine here.</li>
 * </ul>
 */
public class BoxyFabricLoaderImpl implements FabricLoader {
    public static final BoxyFabricLoaderImpl INSTANCE = new BoxyFabricLoaderImpl();

    private static volatile String voxyId = "voxy";
    private static volatile String voxyVersion = "0.0.0";
    private static volatile String voxyCommit = "boxycompat0000000000000000000000000000000";
    private static volatile Path voxyJar;
    private static volatile FileSystem voxyFs;

    /** Called by the locator once Voxy has been located/remapped, so this facade can answer without ModList. */
    public static void setVoxyInfo(String id, String version, String commit, Path remappedJar) {
        if (id != null) voxyId = id;
        if (version != null) voxyVersion = version;
        if (commit != null && !commit.isBlank()) voxyCommit = commit;
        voxyJar = remappedJar;
    }

    @Override
    public boolean isModLoaded(String modId) {
        if (modId.equals(voxyId)) return true;
        if (isModLoadedForge(modId)) return true;
        // Voxy gates its whole shader integration on isModLoaded("iris") (see IrisUtil.IRIS_INSTALLED).
        // On Forge the Iris fork is Oculus (mod id "oculus"), which keeps Iris's net.irisshaders.iris.*
        // classes — so Voxy links against it fine — but registers under a different id. Oculus declares
        // provides=["iris"], yet Forge 1.20.1's ModList.isLoaded only checks real mod ids (indexedMods),
        // not provides aliases, so the reflective probe above misses it. Map the Fabric "iris" id onto
        // Forge's "oculus" explicitly so shaders engage when Oculus is installed.
        if (modId.equals("iris")) return isModLoadedForge("oculus");
        return false;
    }

    /** Best-effort: reach the game-layer ModList via the calling thread's classloader. */
    private static boolean isModLoadedForge(String modId) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Class<?> ml = Class.forName("net.minecraftforge.fml.ModList", false, cl);
            Object inst = ml.getMethod("get").invoke(null);
            if (inst == null) return false;
            return (Boolean) ml.getMethod("isLoaded", String.class).invoke(inst, modId);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public EnvType getEnvironmentType() {
        return FMLLoader.getDist().isClient() ? EnvType.CLIENT : EnvType.SERVER;
    }

    @Override
    public Optional<ModContainer> getModContainer(String modId) {
        if (modId.equals(voxyId)) {
            return Optional.of(new SyntheticContainer());
        }
        return Optional.empty();
    }

    @Override
    public <T> List<T> getEntrypoints(String key, Class<T> type) {
        return List.of();
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    private static synchronized FileSystem voxyFileSystem() {
        if (voxyFs == null && voxyJar != null) {
            try {
                voxyFs = FileSystems.newFileSystem(voxyJar);
            } catch (FileSystemAlreadyExistsException e) {
                voxyFs = FileSystems.getFileSystem(voxyJar.toUri());
            } catch (Exception e) {
                return null;
            }
        }
        return voxyFs;
    }

    /** Synthetic container for Voxy: serves version, the "commit" custom value, and the jar root. */
    private record SyntheticContainer() implements ModContainer {
        @Override
        public ModMetadata getMetadata() {
            return new SyntheticMetadata();
        }

        @Override
        public List<Path> getRootPaths() {
            FileSystem fs = voxyFileSystem();
            if (fs != null) {
                return List.of(fs.getRootDirectories().iterator().next());
            }
            return voxyJar != null ? List.of(voxyJar) : List.of();
        }
    }

    private record SyntheticMetadata() implements ModMetadata {
        @Override
        public Version getVersion() {
            return () -> voxyVersion;
        }

        @Override
        public CustomValue getCustomValue(String key) {
            if (key.equals("commit")) {
                return () -> voxyCommit;
            }
            return () -> "";
        }
    }
}
