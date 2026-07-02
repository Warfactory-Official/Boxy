package com.golem.boxy.loader;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.util.List;
import java.util.Set;

/**
 * A no-op modlauncher transformation service. Its only job is to exist: Forge's
 * {@code ModDirTransformerDiscoverer} promotes any {@code mods/} jar that declares an
 * {@link ITransformationService} onto the early service layer, which is where Forge then discovers
 * Boxy's {@link BoxyModLocator}. This mirrors how Sinytra Connector bootstraps its own locator.
 */
public class BoxyTransformationService implements ITransformationService {

    @Override
    public String name() {
        return "boxy";
    }

    @Override
    public void initialize(IEnvironment environment) {}

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {}

    @Override
    @SuppressWarnings("rawtypes")
    public List<ITransformer> transformers() {
        return List.of();
    }
}
