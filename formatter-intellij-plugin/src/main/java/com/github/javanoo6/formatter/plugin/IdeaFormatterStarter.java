package com.github.javanoo6.formatter.plugin;

import com.intellij.codeInsight.actions.OptimizeImportsProcessor;
import com.intellij.codeInsight.actions.RearrangeCodeProcessor;
import com.intellij.codeInsight.actions.ReformatCodeProcessor;
import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
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
            runDaemon(Path.of(args.get(2)));
            return;
        }
        try {
            handleRequest(args.subList(1, args.size()));
        } catch (Exception e) {
            die(e.getMessage());
        }
        System.exit(0);
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
        Project project = openProject(projectBase);
        if (project == null) throw new RuntimeException("Failed to open a temporary project at " + projectBase);

        try {
            final boolean fmt = doFormat;
            final boolean opt = doOptimizeImports;
            final boolean rea = doRearrange;
            WriteCommandAction.runWriteCommandAction(project, () -> {
                for (Path filePath : files) {
                    processFile(project, filePath, fmt, opt, rea);
                }
            });
            FileDocumentManager.getInstance().saveAllDocuments();
        } finally {
            ProjectManagerEx.getInstanceEx().forceCloseProject(project);
        }
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
        if (optimizeImports) new OptimizeImportsProcessor(project, psiFile).run();
        if (rearrange) new RearrangeCodeProcessor(psiFile).run();

        // Explicitly save this file's document — saveAllDocuments() is unreliable
        // across project open/close cycles in a long-lived daemon JVM
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc != null) {
            FileDocumentManager.getInstance().saveDocument(doc);
        }
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
}
