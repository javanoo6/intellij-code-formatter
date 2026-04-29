package com.github.javanoo6.formatter;

import java.io.File;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


/**
 * Locates (or extracts) the IntelliJ engine, then spawns a subprocess that runs
 * IdeaFormatterStarter via IntelliJ's application startup machinery.
 * <p>
 * The engine ZIP (produced by minimal-jar-builder) already contains the plugin at:
 * custom-plugins/ideaformatter/lib/formatter-plugin.jar
 * custom-plugins/ideaformatter/META-INF/plugin.xml
 * No runtime plugin setup is needed — just point idea.plugins.path at custom-plugins/.
 * <p>
 * Engine resolution order:
 * 1. --engine-dir flag
 * 2. ./engine/ directory next to the running JAR
 * 3. Bundled engine/formatter-engine.zip extracted to $TMPDIR/intellij-formatter-engine/
 */
public class FormatterLauncher {

    private static final String ENGINE_RESOURCE = "/engine/formatter-engine.zip";
    private static final String CACHED_ENGINE_DIR_PREFIX = "intellij-formatter-engine-";

    private final Path engineDirOverride;
    private final Path editorConfigPath;
    private final boolean format;
    private final boolean optimizeImports;
    private final boolean rearrange;

    public FormatterLauncher(Path engineDirOverride, Path editorConfigPath,
                             boolean format, boolean optimizeImports, boolean rearrange) {
        this.engineDirOverride = engineDirOverride;
        this.editorConfigPath = editorConfigPath;
        this.format = format;
        this.optimizeImports = optimizeImports;
        this.rearrange = rearrange;
    }

    private static void collectJars(Path dir, List<String> target) throws Exception {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                    .map(p -> p.toAbsolutePath().toString())
                    .forEach(target::add);
        }
    }

    // -------------------------------------------------------------------------
    // Engine resolution
    // -------------------------------------------------------------------------

    private static void deleteQuietly(Path path) {
        try {
            if (!Files.exists(path)) return;
            try (var stream = Files.walk(path)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (Exception ignored) {
        }
    }

    public int launch(List<Path> files) throws Exception {
        Path engine = resolveEngine();
        // The plugin lives inside the engine ZIP at custom-plugins/ — no setup needed
        Path pluginsRoot = engine.resolve("custom-plugins");
        List<String> cmd = buildCommand(engine, pluginsRoot, files);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        return pb.start().waitFor();
    }

    // -------------------------------------------------------------------------
    // Subprocess command construction
    // -------------------------------------------------------------------------

    private Path resolveEngine() throws Exception {
        if (engineDirOverride!=null && Files.isDirectory(engineDirOverride)) {
            return engineDirOverride.toAbsolutePath();
        }

        // Look for ./engine/ sibling to our JAR
        try {
            Path jarPath = Path.of(FormatterLauncher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path sibling = jarPath.getParent().resolve("engine");
            if (Files.isDirectory(sibling)) {
                return sibling.toAbsolutePath();
            }
        } catch (URISyntaxException ignored) {
        }

        return extractBundledEngine();
    }

    private Path extractBundledEngine() throws Exception {
        Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), cacheKey());

        // Sentinel file signals a complete prior extraction
        if (Files.isDirectory(cacheDir) && Files.exists(cacheDir.resolve(".extracted"))) {
            return cacheDir;
        }

        System.out.println("[formatter] First run: extracting IntelliJ engine to " + cacheDir + " ...");
        Files.createDirectories(cacheDir);

        try (InputStream raw = FormatterLauncher.class.getResourceAsStream(ENGINE_RESOURCE)) {
            Objects.requireNonNull(raw, "Bundled engine ZIP not found at classpath:" + ENGINE_RESOURCE
                    + ". Run `mvn package -pl minimal-jar-builder` first.");
            try (ZipInputStream zis = new ZipInputStream(raw)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry())!=null) {
                    Path dest = cacheDir.resolve(entry.getName()).normalize();
                    if (!dest.startsWith(cacheDir)) {
                        throw new SecurityException("Zip-slip detected in: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                    zis.closeEntry();
                }
            }
        }

        Files.writeString(cacheDir.resolve(".extracted"), "ok");
        System.out.println("[formatter] Engine ready at " + cacheDir);
        return cacheDir;
    }

    private List<String> buildCommand(Path engine, Path pluginsRoot, List<Path> files) throws Exception {
        // Collect only the platform classpath from lib/**.
        // Bundled plugins under plugins/** should be loaded by IntelliJ's plugin manager,
        // not preloaded onto the application classpath.
        List<String> cp = new ArrayList<>();
        collectJars(engine.resolve("lib"), cp);

        // Temp directories for IntelliJ config and system state (isolated per run)
        Path configDir = Files.createTempDirectory("idea-config-");
        Path systemDir = Files.createTempDirectory("idea-system-");

        // Clean up config/system dirs on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            deleteQuietly(configDir);
            deleteQuietly(systemDir);
        }));

        List<String> cmd = new ArrayList<>();

        // Use the same JVM that launched us
        cmd.add(ProcessHandle.current().info().command().orElse("java"));

        // Required --add-opens for IntelliJ on JDK 17+
        cmd.add("--add-opens=java.base/java.io=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.lang.ref=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.net=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.nio=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.nio.charset=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.text=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.time=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.util=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.util.concurrent=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/sun.net.dns=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/sun.nio.ch=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/sun.nio.fs=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/sun.security.ssl=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/sun.security.util=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/com.sun.java.swing=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/com.sun.java.swing.plaf.gtk=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/java.awt=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/java.awt.dnd.peer=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/java.awt.event=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/java.awt.font=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/java.awt.image=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/javax.swing=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/javax.swing.text=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/sun.awt=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/sun.awt.datatransfer=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/sun.awt.image=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/sun.font=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/sun.java2d=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/sun.swing=ALL-UNNAMED");
        cmd.add("--add-opens=java.management/sun.management=ALL-UNNAMED");
        cmd.add("--add-opens=jdk.attach/sun.tools.attach=ALL-UNNAMED");
        cmd.add("--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED");
        cmd.add("--add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED");
        cmd.add("--add-opens=jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED");

        // IntelliJ system properties
        cmd.add("-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader");
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-Didea.vendor.name=JetBrains");
        cmd.add("-Didea.platform.prefix=Idea");
        cmd.add("-Didea.headless.enable.statistics=false");
        cmd.add("-Didea.suppress.statistics.report=true");
        cmd.add("-Didea.fatal.error.notification=disabled");
        cmd.add("-Didea.home.path=" + engine.toAbsolutePath());
        cmd.add("-Didea.config.path=" + configDir.toAbsolutePath());
        cmd.add("-Didea.system.path=" + systemDir.toAbsolutePath());
        cmd.add("-Didea.plugins.path=" + pluginsRoot.toAbsolutePath());
        cmd.add("-Didea.log.path=" + systemDir.resolve("log").toAbsolutePath());
        cmd.add("-Djna.boot.library.path=" + engine.resolve("lib/jna/amd64").toAbsolutePath());
        cmd.add("-Dpty4j.preferred.native.folder=" + engine.resolve("lib/pty4j").toAbsolutePath());
        cmd.add("-Djna.nosys=true");
        cmd.add("-Djna.noclasspath=true");
        cmd.add("-Dintellij.platform.runtime.repository.path=" + engine.resolve("modules/module-descriptors.jar").toAbsolutePath());
        cmd.add("-Dsplash=false");
        cmd.add("-Daether.connector.resumeDownloads=false");
        cmd.add("-Dcompose.swing.render.on.graphics=true");
        cmd.add("-Xmx512m");

        // Classpath
        cmd.add("-cp");
        cmd.add(String.join(File.pathSeparator, cp));

        // IntelliJ main class — routes to our registered "ideaformatter" appStarter
        cmd.add("com.intellij.idea.Main");
        cmd.add("ideaformatter");

        // Our custom flags
        if (format) cmd.add("--format");
        if (optimizeImports) cmd.add("--optimize-imports");
        if (rearrange) cmd.add("--rearrange");
        if (editorConfigPath!=null) {
            cmd.add("--editorconfig");
            cmd.add(editorConfigPath.toAbsolutePath().toString());
        }

        // Files to process
        for (Path f : files) {
            cmd.add(f.toAbsolutePath().toString());
        }

        return cmd;
    }

    private String cacheKey() {
        try {
            Path jarPath = Path.of(FormatterLauncher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(jarPath)) {
                long stamp = Files.getLastModifiedTime(jarPath).toMillis();
                return CACHED_ENGINE_DIR_PREFIX + stamp;
            }
        } catch (Exception ignored) {
        }
        return CACHED_ENGINE_DIR_PREFIX + "dev";
    }
}
