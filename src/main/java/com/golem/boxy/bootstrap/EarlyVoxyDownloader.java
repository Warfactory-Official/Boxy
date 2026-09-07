package com.golem.boxy.bootstrap;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

/** Runs before NeoForge's ordinary mod validation so Voxy can be present for the same launch. */
public final class EarlyVoxyDownloader implements ITransformationService {
    private static final String URL = "https://github.com/Warfactory-Official/voxy/releases/download/latest/1.21.1-neoforge-voxy-0.2.15-beta+1.21.1-neoforge.jar";
    private static final String FILE_NAME = "1.21.1-neoforge-voxy-0.2.15-beta+1.21.1-neoforge.jar";
    private static final String MOD_ID = "voxy";

    @Override
    public String name() {
        return "boxyvoxybootstrap";
    }

    @Override
    public void initialize(IEnvironment environment) {
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) {
        String launchTarget = environment.getProperty(IEnvironment.Keys.LAUNCHTARGET.get()).orElse("");
        System.out.println("[Boxy] Dependency bootstrap launch target: " + launchTarget);
        if (launchTarget.toLowerCase(Locale.ROOT).contains("server")) {
            System.out.println("[Boxy] Skipping client-only Voxy download on a server launch");
            return;
        }

        Path gameDir = environment.getProperty(IEnvironment.Keys.GAMEDIR.get())
                .orElseGet(() -> Path.of(System.getProperty("user.dir", ".")));
        System.out.println("[Boxy] Dependency bootstrap game directory: " + gameDir.toAbsolutePath().normalize());
        Path modsDir = gameDir.resolve("mods");
        try {
            Files.createDirectories(modsDir);
            if (containsVoxy(modsDir)) return;
            download(modsDir.resolve(FILE_NAME));
        } catch (Exception error) {
            throw new IllegalStateException("Boxy could not install Voxy. Check your internet connection or install Voxy manually in "
                    + modsDir, error);
        }
    }

    @Override
    public List<? extends ITransformer<?>> transformers() {
        return List.of();
    }

    private static boolean containsVoxy(Path modsDir) throws IOException {
        try (var files = Files.list(modsDir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .anyMatch(EarlyVoxyDownloader::isVoxyJar);
        }
    }

    private static boolean isVoxyJar(Path path) {
        try (JarFile jar = new JarFile(path.toFile())) {
            var entry = jar.getJarEntry("META-INF/neoforge.mods.toml");
            if (entry == null) return false;
            try (InputStream input = jar.getInputStream(entry)) {
                String metadata = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                int dependencies = metadata.indexOf("[[dependencies");
                String mods = dependencies >= 0 ? metadata.substring(0, dependencies) : metadata;
                return mods.matches("(?s).*\\[\\[mods\\]\\].*modId\\s*=\\s*[\\\"]voxy[\\\"].*");
            }
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void download(Path destination) throws IOException {
        Path partial = destination.resolveSibling(destination.getFileName() + ".part");
        Progress progress = Progress.open();
        try {
            progress.status("Setting up dependencies", 0, -1);
            HttpURLConnection connection = (HttpURLConnection) URI.create(URL).toURL().openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Boxy/2.0.0-alpha.1 Voxy dependency bootstrap");
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) {
                throw new IOException("Voxy download returned HTTP " + response);
            }
            long total = connection.getContentLengthLong();
            long copied = 0;
            try (InputStream input = connection.getInputStream(); var output = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    copied += read;
            progress.status("Downloading dependencies", copied, total);
                }
            } finally {
                connection.disconnect();
            }
            if (!isVoxyJar(partial)) throw new IOException("Downloaded file is not a valid Voxy NeoForge jar");
            try {
                Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            progress.status("Voxy ready", copied, copied);
        } finally {
            progress.close();
            Files.deleteIfExists(partial);
        }
    }

    private static final class Progress {
        private final JFrame window;
        private final JLabel label;
        private final JProgressBar bar;
        private String lastLoggedText;
        private int lastLoggedPercent = -1;

        private Progress(JFrame window, JLabel label, JProgressBar bar) {
            this.window = window;
            this.label = label;
            this.bar = bar;
        }

        static Progress open() {
            if (GraphicsEnvironment.isHeadless()) return new Progress(null, null, null);
            AtomicReference<Progress> result = new AtomicReference<>();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    JFrame window = new JFrame("Boxy");
                    window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                    Color background = new Color(24, 24, 28);
                    Color foreground = new Color(232, 232, 238);
                    window.setResizable(false);
                    window.getContentPane().setBackground(background);
                    window.setLayout(new BorderLayout(12, 12));
                    JLabel label = new JLabel("Setting up dependencies");
                    label.setForeground(foreground);
                    label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
                    label.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 14, 0, 14));
                    JProgressBar bar = new JProgressBar();
                    bar.setPreferredSize(new Dimension(420, 18));
                    bar.setOpaque(true);
                    bar.setBorderPainted(true);
                    bar.setBackground(new Color(52, 52, 62));
                    bar.setForeground(new Color(92, 160, 255));
                    bar.setStringPainted(true);
                    bar.setString("Preparing...");
                    bar.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                    bar.setBorder(javax.swing.BorderFactory.createLineBorder(new Color(72, 72, 84)));
                    window.add(label, BorderLayout.NORTH);
                    window.add(bar, BorderLayout.CENTER);
                    window.setPreferredSize(new Dimension(460, 100));
                    window.pack();
                    window.setLocationRelativeTo(null);
                    window.setAlwaysOnTop(true);
                    window.setVisible(true);
                    result.set(new Progress(window, label, bar));
                });
            } catch (Exception ignored) {
                return new Progress(null, null, null);
            }
            return result.get();
        }

        void status(String text, long current, long total) {
            int percent = total > 0 ? (int) (current * 100 / total) : -1;
            boolean logStep = total > 0 && (lastLoggedPercent < 0 || percent == 100
                    || percent / 10 > lastLoggedPercent / 10);
            if (!text.equals(lastLoggedText) || (total <= 0 && logStep) || logStep) {
                lastLoggedText = text;
                if (total > 0) lastLoggedPercent = percent;
                System.out.println("[Boxy] " + text + (percent >= 0 ? " " + percent + "%" : ""));
            }
            if (window == null) return;
            SwingUtilities.invokeLater(() -> {
                label.setText(text);
                if (total > 0) {
                    bar.setIndeterminate(false);
                    int displayPercent = (int) Math.min(100, current * 100 / total);
                    bar.setValue(displayPercent);
                    bar.setString(displayPercent + "%");
                } else {
                    bar.setIndeterminate(true);
                    bar.setString("Preparing...");
                }
            });
        }

        void close() {
            if (window != null) SwingUtilities.invokeLater(window::dispose);
        }
    }
}
