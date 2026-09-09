package com.golem.boxy.bootstrap;

import cpw.mods.jarhandling.JarContents;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.Locale;
import java.util.Map;

/** Adds Boxy's nested game-layer mod without re-adding the already claimed service jar. */
public final class BoxyServiceModLocator implements IModFileCandidateLocator {
    private static final String JIJ_DIR = "META-INF/jarjar";

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        Path directory = locateServiceRoot().resolve(JIJ_DIR);
        if (!Files.isDirectory(directory)) return;

        try (var files = Files.list(directory)) {
            files.filter(BoxyServiceModLocator::isJar).forEach(path -> mountAndAdd(path, pipeline));
        } catch (IOException error) {
            throw new IllegalStateException("Failed to list Boxy Jar-in-Jar entries in " + directory, error);
        }
    }

    private static void mountAndAdd(Path path, IDiscoveryPipeline pipeline) {
        try {
            String schemeSpecificPart = path.toAbsolutePath().toUri().getRawSchemeSpecificPart();
            URI uri = new URI("jij:" + schemeSpecificPart).normalize();
            var fileSystem = FileSystems.newFileSystem(uri, Map.of("packagePath", path));
            var contents = JarContents.of(fileSystem.getPath("/"));
            pipeline.addJarContent(contents, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
        } catch (URISyntaxException | IOException error) {
            throw new IllegalStateException("Failed to add Boxy Jar-in-Jar entry " + path.getFileName(), error);
        }
    }

    private static boolean isJar(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static Path locateServiceRoot() {
        CodeSource codeSource = BoxyServiceModLocator.class.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            throw new IllegalStateException("CodeSource unavailable; cannot resolve Boxy service jar");
        }

        URI uri;
        try {
            uri = codeSource.getLocation().toURI();
        } catch (URISyntaxException error) {
            throw new IllegalStateException("Could not parse Boxy CodeSource location " + codeSource.getLocation(), error);
        }

        try {
            return Paths.get(uri);
        } catch (Exception error) {
            throw new IllegalStateException("Could not resolve Boxy service jar from " + uri, error);
        }
    }
}
