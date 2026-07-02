package com.golem.boxy.loader;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * Boxy's wrapper around the bundled (shaded) Mixin Transmogrifier service.
 *
 * <p>Boxy bundles Mixin Transmogrifier to upgrade Forge's runtime Mixin so Voxy's {@code iris.*} constructor
 * injects apply at their original points (shader support, §12). Sinytra Connector bundles the <em>same</em>
 * Transmogrifier, which causes two distinct clashes when both are installed:
 * <ul>
 *   <li>Two service-layer modules can't both export {@code io.github.steelwoolmc.mixintransmog} — Boxy shades
 *       its copy to {@code com.golem.boxy.libs.mixintransmog} to dodge that split package (quirk 39).</li>
 *   <li>modlauncher keys transformation services by {@link #name()} and rejects duplicates, but two
 *       Transmogrifier copies both report {@code "mixin-transmogrifier"} ({@code Duplicate key} crash). So Boxy
 *       registers <em>this</em> wrapper (a unique name) instead of the Transmogrifier service directly, and
 *       only drives the bundled Transmogrifier when Connector is absent (quirk 40).</li>
 * </ul>
 *
 * <p>The upgrade itself (an {@code Unsafe} swap of the Mixin module's jar source, in the Transmogrifier's
 * static initializer) is <b>not</b> safe to run twice, so when Connector is present we stand down and let its
 * Transmogrifier do it. The check has <b>no false positives</b> — the {@code org.sinytra.connector} module
 * exists only if Connector is installed — so a standalone Boxy always performs the upgrade, byte-for-byte as
 * before this wrapper existed.
 */
public class BoxyTransmogService implements ITransformationService {
    private static final Logger LOGGER = LoggerFactory.getLogger("Boxy/MixinUpgrade");

    // The shaded Transmogrifier service (build.gradle shadowJar `relocate io.github.steelwoolmc.mixintransmog
    // -> com.golem.boxy.libs.mixintransmog`). Referenced by name so this class compiles before shading runs
    // and is not itself rewritten by the relocation. Loading it runs its static initializer = the Mixin upgrade.
    private static final String SHADED_TRANSMOG = "com.golem.boxy.libs.mixintransmog.MixinTransformationService";

    private final ITransformationService delegate; // null when Connector is present (we stand down)

    public BoxyTransmogService() {
        ITransformationService d = null;
        if (connectorPresent()) {
            LOGGER.info("Boxy: Sinytra Connector detected — deferring the Mixin upgrade to its Transmogrifier.");
        } else {
            try {
                d = (ITransformationService) Class.forName(SHADED_TRANSMOG).getConstructor().newInstance();
            } catch (Throwable t) {
                LOGGER.error("Boxy: failed to start the bundled Mixin Transmogrifier; shader-pack support may be unavailable.", t);
            }
        }
        this.delegate = d;
    }

    /** True if Sinytra Connector is on the service layer (it bundles its own Transmogrifier, which wins). */
    private static boolean connectorPresent() {
        try {
            ModuleLayer layer = BoxyTransmogService.class.getModule().getLayer();
            return layer != null && layer.findModule("org.sinytra.connector").isPresent();
        } catch (Throwable t) {
            return false; // on any doubt, run our own upgrade (the standalone path)
        }
    }

    @Override
    public String name() {
        return "boxy-mixin-transmogrifier";
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) throws IncompatibleEnvironmentException {
        if (delegate != null) delegate.onLoad(env, otherServices);
    }

    @Override
    public void initialize(IEnvironment environment) {
        if (delegate != null) delegate.initialize(environment);
    }

    @Override
    public List<ITransformationService.Resource> beginScanning(IEnvironment environment) {
        return delegate != null ? delegate.beginScanning(environment) : List.of();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<ITransformer> transformers() {
        return delegate != null ? delegate.transformers() : List.of();
    }
}
