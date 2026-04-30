package com.github.javanoo6.formatter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


/**
 * Locates (or extracts) the IntelliJ engine, then either connects to a running
 * daemon or spawns a subprocess that runs IdeaFormatterStarter.
 *
 * Daemon mode (default): on first call the daemon JVM is started in the background
 * and its port written to $TMPDIR/intellij-formatter-daemon-{cacheKey}.port.
 * Subsequent calls connect directly, skipping the ~3-5s IntelliJ startup cost.
 *
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
    private final boolean noDaemon;

    public FormatterLauncher(Path engineDirOverride, Path editorConfigPath,
                             boolean format, boolean optimizeImports, boolean rearrange,
                             boolean noDaemon) {
        this.engineDirOverride = engineDirOverride;
        this.editorConfigPath = editorConfigPath;
        this.format = format;
        this.optimizeImports = optimizeImports;
        this.rearrange = rearrange;
        this.noDaemon = noDaemon;
    }

    // -------------------------------------------------------------------------
    // Public entry points
    // -------------------------------------------------------------------------

    public int launch(List<Path> files) throws Exception {
        Path engine = resolveEngine();
        Path pluginsRoot = engine.resolve("custom-plugins");

        if (!noDaemon) {
            Path portFile = daemonPortFile();
            Integer result = trySendToDaemon(portFile, files);
            if (result != null) return result;

            startDaemon(engine, pluginsRoot, portFile);
            waitForPortFile(portFile);
            result = trySendToDaemon(portFile, files);
            if (result != null) return result;

            System.err.println("[formatter] WARNING: daemon unreachable, falling back to one-shot");
        }

        List<String> cmd = buildCommand(engine, pluginsRoot, files);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        return pb.start().waitFor();
    }

    public int stopDaemon() throws Exception {
        Path portFile = daemonPortFile();
        if (!Files.exists(portFile)) {
            System.out.println("[formatter] No daemon running (port file absent).");
            return 0;
        }
        int port;
        try {
            port = Integer.parseInt(Files.readString(portFile).trim());
        } catch (Exception e) {
            deleteQuietly(portFile);
            System.out.println("[formatter] No daemon running (stale port file cleaned up).");
            return 0;
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            s.setSoTimeout(5_000);
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println("--stop");
            String response = in.readLine();
            System.out.println("[formatter] Daemon stopped: " + response);
        } catch (ConnectException e) {
            System.out.println("[formatter] Daemon already gone (stale port file cleaned up).");
        }
        deleteQuietly(portFile);
        return 0;
    }

    // -------------------------------------------------------------------------
    // Daemon helpers
    // -------------------------------------------------------------------------

    private Path daemonPortFile() {
        return Path.of(System.getProperty("java.io.tmpdir"),
                "intellij-formatter-daemon-" + cacheKey() + ".port");
    }

    private Integer trySendToDaemon(Path portFile, List<Path> files) {
        if (!Files.exists(portFile)) return null;
        int port;
        try {
            port = Integer.parseInt(Files.readString(portFile).trim());
        } catch (Exception e) {
            return null;
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            s.setSoTimeout(60_000);
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println(buildRequestLine(files));
            String response = in.readLine();
            if (response == null) return null;
            if (response.startsWith("OK")) return 0;
            if (response.startsWith("ERR")) {
                System.err.println("[formatter] " + response.substring(4));
                return 1;
            }
            return null;
        } catch (ConnectException | SocketTimeoutException e) {
            // Daemon died without cleaning up its port file
            deleteQuietly(portFile);
            return null;
        } catch (Exception e) {
            deleteQuietly(portFile);
            return null;
        }
    }

    private String buildRequestLine(List<Path> files) {
        List<String> parts = new ArrayList<>();
        if (format) parts.add("--format");
        if (optimizeImports) parts.add("--optimize-imports");
        if (rearrange) parts.add("--rearrange");
        if (editorConfigPath != null) {
            parts.add("--editorconfig");
            parts.add(editorConfigPath.toAbsolutePath().toString());
        }
        for (Path f : files) parts.add(f.toAbsolutePath().toString());
        return String.join(" ", parts);
    }

    private void startDaemon(Path engine, Path pluginsRoot, Path portFile) throws Exception {
        List<String> cmd = buildDaemonCommand(engine, pluginsRoot, portFile);
        Path logFile = Path.of(System.getProperty("java.io.tmpdir"),
                "intellij-formatter-daemon-" + cacheKey() + ".log");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(logFile.toFile());
        pb.redirectError(logFile.toFile());
        pb.start(); // fire and forget — no waitFor()
        System.out.println("[formatter] Starting daemon (log: " + logFile + ")");
    }

    private void waitForPortFile(Path portFile) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(portFile)) return;
            Thread.sleep(100);
        }
        Path logFile = Path.of(System.getProperty("java.io.tmpdir"),
                "intellij-formatter-daemon-" + cacheKey() + ".log");
        throw new RuntimeException(
                "Daemon did not start within 30 seconds. Check log: " + logFile);
    }

    // -------------------------------------------------------------------------
    // Engine resolution
    // -------------------------------------------------------------------------

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
        } catch (Exception ignored) {
        }
    }

    private Path resolveEngine() throws Exception {
        if (engineDirOverride != null && Files.isDirectory(engineDirOverride)) {
            return engineDirOverride.toAbsolutePath();
        }
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
    // Subprocess command construction
    // -------------------------------------------------------------------------

    private void addJvmFlags(List<String> cmd, Path engine, Path configDir, Path systemDir, Path pluginsRoot) {
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
    }

    private List<String> buildCommand(Path engine, Path pluginsRoot, List<Path> files) throws Exception {
        List<String> cp = new ArrayList<>();
        collectJars(engine.resolve("lib"), cp);

        Path configDir = Files.createTempDirectory("idea-config-");
        Path systemDir = Files.createTempDirectory("idea-system-");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            deleteQuietly(configDir);
            deleteQuietly(systemDir);
        }));

        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        addJvmFlags(cmd, engine, configDir, systemDir, pluginsRoot);
        cmd.add("-cp");
        cmd.add(String.join(File.pathSeparator, cp));
        cmd.add("com.intellij.idea.Main");
        cmd.add("ideaformatter");

        if (format) cmd.add("--format");
        if (optimizeImports) cmd.add("--optimize-imports");
        if (rearrange) cmd.add("--rearrange");
        if (editorConfigPath != null) {
            cmd.add("--editorconfig");
            cmd.add(editorConfigPath.toAbsolutePath().toString());
        }
        for (Path f : files) cmd.add(f.toAbsolutePath().toString());

        return cmd;
    }

    private List<String> buildDaemonCommand(Path engine, Path pluginsRoot, Path portFile) throws Exception {
        List<String> cp = new ArrayList<>();
        collectJars(engine.resolve("lib"), cp);

        // Stable dirs (not random temp) so the CLI's shutdown hook never deletes them
        String key = cacheKey();
        Path configDir = Path.of(System.getProperty("java.io.tmpdir"), "intellij-formatter-config-" + key);
        Path systemDir = Path.of(System.getProperty("java.io.tmpdir"), "intellij-formatter-system-" + key);
        Files.createDirectories(configDir);
        Files.createDirectories(systemDir);

        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        addJvmFlags(cmd, engine, configDir, systemDir, pluginsRoot);
        cmd.add("-cp");
        cmd.add(String.join(File.pathSeparator, cp));
        cmd.add("com.intellij.idea.Main");
        cmd.add("ideaformatter");
        cmd.add("--daemon");
        cmd.add(portFile.toAbsolutePath().toString());

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
