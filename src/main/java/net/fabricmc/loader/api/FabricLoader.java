package net.fabricmc.loader.api;

import com.golem.boxy.loader.BoxyFabricLoaderImpl;
import net.fabricmc.api.EnvType;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

// Voxy calls FabricLoader.getInstance().<method>; Boxy routes those to Forge.
public interface FabricLoader {
    static FabricLoader getInstance() {
        return BoxyFabricLoaderImpl.INSTANCE;
    }

    boolean isModLoaded(String modId);

    EnvType getEnvironmentType();

    Optional<ModContainer> getModContainer(String modId);

    <T> List<T> getEntrypoints(String key, Class<T> type);

    Path getConfigDir();
}
