package com.github.javanoo6.formatter.plugin;

import com.intellij.codeInsight.actions.OptimizeImportsProcessor;
import com.intellij.codeInsight.actions.RearrangeCodeProcessor;
import com.intellij.codeInsight.actions.ReformatCodeProcessor;
import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessProvider;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.lang.java.JavaImportOptimizer;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Custom IntelliJ ApplicationStarter registered under id="ideaformatter" in plugin.xml.
 *
 * One-shot mode (default):
 *   com.intellij.idea.Main ideaformatter [--format] [--optimize-imports] [--rearrange]
 *     [--editorconfig <path>] file1 file2 ...
 *
 * Daemon mode:
 *   com.intellij.idea.Main ideaformatter --daemon <portFilePath>
 *   Listens on a TCP localhost port, handles requests until --stop is received.
 *   Each request is a space-separated arg line; response is "OK" or "ERR <msg>".
 */
public class IdeaFormatterStarter implements ApplicationStarter {

    private static void die(String msg) {
        System.err.println("[formatter] ERROR: " + msg);
        System.exit(1);
    }

    @Override
    public void main(@NotNull List<String> args) {
        // args[0] is "ideaformatter" (routing key)
        if (args.size() >= 3 && "--daemon".equals(args.get(1))) {
            Thread daemonThread = new Thread(() -> runDaemon(Path.of(args.get(2))), "ideaformatter-daemon");
            daemonThread.setDaemon(false);
            daemonThread.start();
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                handleRequest(args.subList(1, args.size()));
                System.exit(0);
            } catch (Exception e) {
                die(e.getMessage());
            }
        }, "ideaformatter-worker");
        worker.setDaemon(false);
        worker.start();
    }

    private void runDaemon(Path portFile) {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            // Atomic write: write to .tmp then rename so the CLI never reads a partial file
            Path tmp = Path.of(portFile + ".tmp");
            Files.writeString(tmp, port + "\n");
            Files.move(tmp, portFile, StandardCopyOption.ATOMIC_MOVE);
            System.out.println("[daemon] listening on port " + port);

            while (true) {
                try (Socket client = ss.accept()) {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter out = new PrintWriter(
                            new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true);

                    String line = in.readLine();
                    if (line == null) continue;

                    if ("--stop".equals(line.trim())) {
                        out.println("OK");
                        Files.deleteIfExists(portFile);
                        System.exit(0);
                    }
                    try {
                        handleRequest(parseRequestLine(line));
                        out.println("OK");
                    } catch (Exception e) {
                        out.println("ERR " + e.getMessage());
                    }
                } catch (Exception e) {
                    System.err.println("[daemon] connection error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[daemon] fatal: " + e.getMessage());
            System.exit(1);
        }
    }

    private static List<String> parseRequestLine(String line) {
        return Arrays.asList(line.trim().split("\\s+"));
    }

    private void handleRequest(List<String> args) throws Exception {
        boolean doFormat = false;
        boolean doOptimizeImports = false;
        boolean doRearrange = false;
        Path editorConfigDir = null;
        List<Path> files = new ArrayList<>();

        Iterator<String> it = args.iterator();
        while (it.hasNext()) {
            String arg = it.next();
            switch (arg) {
                case "--format" -> doFormat = true;
                case "--optimize-imports" -> doOptimizeImports = true;
                case "--rearrange" -> doRearrange = true;
                case "--editorconfig" -> {
                    if (!it.hasNext()) throw new IllegalArgumentException("--editorconfig requires a path argument");
                    Path ec = Path.of(it.next()).toAbsolutePath();
                    editorConfigDir = ec.getParent() != null ? ec.getParent() : Path.of(".");
                }
                default -> {
                    if (arg.startsWith("-")) throw new IllegalArgumentException("Unknown option: " + arg);
                    files.add(Path.of(arg).toAbsolutePath());
                }
            }
        }

        if (files.isEmpty()) throw new IllegalArgumentException("No source files specified.");

        Path projectBase = editorConfigDir != null ? editorConfigDir : files.get(0).getParent();
        Project project = invokeAndWait(() -> openProject(projectBase));
        if (project == null) throw new RuntimeException("Failed to open a temporary project at " + projectBase);

        RemappedWorkspace remappedWorkspace = createRemappedWorkspace(editorConfigDir, files);

        try {
            DumbService.getInstance(project).waitForSmartMode();
            final boolean fmt = doFormat;
            final boolean opt = doOptimizeImports;
            final boolean rea = doRearrange;
            invokeAndWait(() -> WriteCommandAction.runWriteCommandAction(project, () -> {
                for (Path filePath : remappedWorkspace.filesToProcess()) {
                    processFile(project, filePath, fmt, opt, rea);
                }
            }));
            invokeAndWait(() -> FileDocumentManager.getInstance().saveAllDocuments());
        } finally {
            remappedWorkspace.cleanup();
            invokeAndWait(() -> ProjectManagerEx.getInstanceEx().forceCloseProject(project));
        }
    }

    private RemappedWorkspace createRemappedWorkspace(Path editorConfigDir, List<Path> files) throws Exception {
        if (editorConfigDir == null) {
            return new RemappedWorkspace(files, List.of());
        }

        Path normalizedRoot = editorConfigDir.toAbsolutePath().normalize();
        List<Path> remappedFiles = new ArrayList<>(files.size());
        List<RemappedFile> cleanupFiles = new ArrayList<>();

        for (Path file : files) {
            Path normalizedFile = file.toAbsolutePath().normalize();
            if (normalizedFile.startsWith(normalizedRoot)) {
                remappedFiles.add(normalizedFile);
                continue;
            }

            Path remappedPath = createEditorConfigSiblingPath(normalizedRoot, normalizedFile);
            Files.deleteIfExists(remappedPath);

            boolean copyBackRequired = false;
            try {
                Files.createLink(remappedPath, normalizedFile);
            } catch (Exception linkError) {
                Files.copy(normalizedFile, remappedPath, StandardCopyOption.REPLACE_EXISTING);
                copyBackRequired = true;
            }

            remappedFiles.add(remappedPath);
            cleanupFiles.add(new RemappedFile(remappedPath, normalizedFile, copyBackRequired));
        }

        return new RemappedWorkspace(remappedFiles, cleanupFiles);
    }

    private Path createEditorConfigSiblingPath(Path editorConfigDir, Path targetFile) throws Exception {
        String fileName = targetFile.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String baseName = dot >= 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot >= 0 ? fileName.substring(dot) : "";
        String prefix = "ideaformatter-link-" + sanitizeFileComponent(baseName) + "-";
        if (prefix.length() < 3) {
            prefix = "ifl";
        }
        return Files.createTempFile(editorConfigDir, prefix, extension);
    }

    private String sanitizeFileComponent(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void processFile(Project project, Path filePath,
                             boolean format, boolean optimizeImports, boolean rearrange) {
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filePath);
        if (vf == null) {
            System.err.println("[formatter] SKIP (VirtualFile not found): " + filePath);
            return;
        }
        // Force reload from disk so daemon doesn't use a stale in-memory version
        vf.refresh(false, false);

        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) {
            System.err.println("[formatter] SKIP (no PSI for file): " + filePath);
            return;
        }
        System.out.println("[formatter] Processing: " + filePath.getFileName());
        NonProjectFileWritingAccessProvider.allowWriting(List.of(vf));
        if (format) new ReformatCodeProcessor(project, psiFile, null, false).run();
        if (optimizeImports) optimizeImports(project, psiFile);
        if (rearrange) new RearrangeCodeProcessor(psiFile).run();

        // Explicitly save this file's document — saveAllDocuments() is unreliable
        // across project open/close cycles in a long-lived daemon JVM
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc != null) {
            FileDocumentManager.getInstance().saveDocument(doc);
        }
    }

    private void optimizeImports(Project project, PsiFile psiFile) {
        if (psiFile instanceof PsiJavaFile) {
            // JavaImportOptimizer can optimize plain PsiJavaFile instances directly.
            // OptimizeImportsProcessor skips Java files outside configured source roots,
            // which is common for our lightweight temporary projects.
            new JavaImportOptimizer().processFile(psiFile).run();
            return;
        }

        new OptimizeImportsProcessor(project, psiFile).run();
    }

    /**
     * Opens a lightweight IntelliJ project rooted at the given directory.
     * Creates a temporary .idea/ dir so IntelliJ treats it as a proper project,
     * which is required for EditorConfig discovery and PSI resolution.
     */
    private Project openProject(Path basePath) {
        try {
            return ProjectManagerEx.getInstanceEx()
                    .openProject(basePath.toAbsolutePath(), OpenProjectTask.build().withProjectName("formatter-project"));
        } catch (Exception e) {
            System.err.println("[formatter] Error opening project: " + e.getMessage());
            return null;
        }
    }

    private static void invokeAndWait(Runnable runnable) {
        ApplicationManager.getApplication().invokeAndWait(runnable);
    }

    private static <T> T invokeAndWait(ThrowingSupplier<T> supplier) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                result.set(supplier.get());
            } catch (Exception e) {
                error.set(e);
            }
        });
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private record RemappedWorkspace(List<Path> filesToProcess, List<RemappedFile> cleanupFiles) {
        private void cleanup() {
            for (RemappedFile cleanupFile : cleanupFiles) {
                try {
                    if (cleanupFile.copyBackRequired()) {
                        Files.copy(cleanupFile.remappedPath(),
                                cleanupFile.originalPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.deleteIfExists(cleanupFile.remappedPath());
                } catch (Exception ignored) {
                }
            }
        }
    }

    private record RemappedFile(Path remappedPath, Path originalPath, boolean copyBackRequired) {
    }
}
