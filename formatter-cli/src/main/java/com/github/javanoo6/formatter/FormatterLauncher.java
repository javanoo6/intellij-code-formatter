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
 * Locates (or extracts) the IntelliJ engine, sets up the plugin directory,
 * then spawns a subprocess that runs IdeaFormatterStarter via IntelliJ's
 * application startup machinery.
 *
 * Engine resolution order:
 *   1. --engine-dir flag
 *   2. ./engine/ directory next to the running JAR
 *   3. Bundled engine/formatter-engine.zip extracted to $TMPDIR/intellij-formatter-engine/
 */
public class FormatterLauncher {

    private static final String ENGINE_RESOURCE = "/engine/formatter-engine.zip";
    private static final String PLUGIN_XML_RESOURCE = "/META-INF/plugin.xml";
    private static final String CACHED_ENGINE_DIR = "intellij-formatter-engine";

    private final Path engineDirOverride;
    private final Path editorConfigPath;
    private final boolean format;
    private final boolean optimizeImports;
    private final boolean rearrange;

    public FormatterLauncher(Path engineDirOverride, Path editorConfigPath,
                             boolean format, boolean optimizeImports, boolean rearrange) {
        this.engineDirOverride = engineDirOverride;
        this.editorConfigPath  = editorConfigPath;
        this.format            = format;
        this.optimizeImports   = optimizeImports;
        this.rearrange         = rearrange;
    }

    public int launch(List<Path> files) throws Exception {
        Path engine    = resolveEngine();
        Path pluginDir = buildPluginDir(engine);
        List<String> cmd = buildCommand(engine, pluginDir, files);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        return pb.start().waitFor();
    }

    // -------------------------------------------------------------------------
    // Engine resolution
    // -------------------------------------------------------------------------

    private Path resolveEngine() throws Exception {
        if (engineDirOverride != null && Files.isDirectory(engineDirOverride)) {
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
        } catch (URISyntaxException ignored) {}

        return extractBundledEngine();
    }

    private Path extractBundledEngine() throws Exception {
        Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), CACHED_ENGINE_DIR);

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
                while ((entry = zis.getNextEntry()) != null) {
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

    // -------------------------------------------------------------------------
    // Plugin directory setup
    //
    // IntelliJ discovers plugins from idea.plugins.path. Each plugin must be a
    // subdirectory containing lib/<plugin>.jar and META-INF/plugin.xml.
    //
    //   <pluginsRoot>/
    //     ideaformatter/
    //       lib/
    //         formatter-cli.jar   ← our JAR (contains IdeaFormatterStarter)
    //       META-INF/
    //         plugin.xml          ← registers the appStarter extension
    // -------------------------------------------------------------------------

    private Path buildPluginDir(Path engine) throws Exception {
        Path pluginsRoot = engine.resolve("custom-plugins");
        Path pluginDir   = pluginsRoot.resolve("ideaformatter");
        Path libDir      = pluginDir.resolve("lib");
        Path metaInfDir  = pluginDir.resolve("META-INF");

        Files.createDirectories(libDir);
        Files.createDirectories(metaInfDir);

        // Our JAR (the running formatter-cli-full.jar) contains IdeaFormatterStarter
        try {
            Path ourJar = Path.of(FormatterLauncher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Files.copy(ourJar, libDir.resolve("formatter-cli.jar"), StandardCopyOption.REPLACE_EXISTING);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Cannot locate formatter-cli JAR", e);
        }

        // plugin.xml bundled inside our JAR as a classpath resource
        try (InputStream is = FormatterLauncher.class.getResourceAsStream(PLUGIN_XML_RESOURCE)) {
            Objects.requireNonNull(is, "plugin.xml resource not found at classpath:" + PLUGIN_XML_RESOURCE);
            Files.copy(is, metaInfDir.resolve("plugin.xml"), StandardCopyOption.REPLACE_EXISTING);
        }

        return pluginsRoot;
    }

    // -------------------------------------------------------------------------
    // Subprocess command construction
    // -------------------------------------------------------------------------

    private List<String> buildCommand(Path engine, Path pluginsRoot, List<Path> files) throws Exception {
        // Collect engine classpath: lib/** + plugins/java/lib/** + plugins/editorconfig/lib/**
        List<String> cp = new ArrayList<>();
        collectJars(engine.resolve("lib"), cp);
        collectJars(engine.resolve("plugins/java/lib"), cp);
        collectJars(engine.resolve("plugins/editorconfig/lib"), cp);

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
        cmd.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.util=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.io=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/java.awt=ALL-UNNAMED");
        cmd.add("--add-opens=java.desktop/sun.awt=ALL-UNNAMED");

        // IntelliJ system properties
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-Didea.headless.enable.statistics=false");
        cmd.add("-Didea.suppress.statistics.report=true");
        cmd.add("-Didea.fatal.error.notification=disabled");
        cmd.add("-Didea.home.path=" + engine.toAbsolutePath());
        cmd.add("-Didea.config.path=" + configDir.toAbsolutePath());
        cmd.add("-Didea.system.path=" + systemDir.toAbsolutePath());
        cmd.add("-Didea.plugins.path=" + pluginsRoot.toAbsolutePath());
        cmd.add("-Didea.log.path=" + systemDir.resolve("log").toAbsolutePath());
        cmd.add("-Xmx512m");

        // Classpath
        cmd.add("-cp");
        cmd.add(String.join(File.pathSeparator, cp));

        // IntelliJ main class — routes to our registered "ideaformatter" appStarter
        cmd.add("com.intellij.idea.Main");
        cmd.add("ideaformatter");

        // Our custom flags
        if (format)          cmd.add("--format");
        if (optimizeImports) cmd.add("--optimize-imports");
        if (rearrange)       cmd.add("--rearrange");
        if (editorConfigPath != null) {
            cmd.add("--editorconfig");
            cmd.add(editorConfigPath.toAbsolutePath().toString());
        }

        // Files to process
        for (Path f : files) {
            cmd.add(f.toAbsolutePath().toString());
        }

        return cmd;
    }

    private static void collectJars(Path dir, List<String> target) throws Exception {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                  .map(p -> p.toAbsolutePath().toString())
                  .forEach(target::add);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            if (!Files.exists(path)) return;
            try (var stream = Files.walk(path)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                      .map(Path::toFile)
                      .forEach(File::delete);
            }
        } catch (Exception ignored) {}
    }
}
