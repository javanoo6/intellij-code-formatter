package com.github.javanoo6.formatter.plugin;

import com.intellij.codeInsight.actions.OptimizeImportsProcessor;
import com.intellij.codeInsight.actions.RearrangeCodeProcessor;
import com.intellij.codeInsight.actions.ReformatCodeProcessor;
import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Custom IntelliJ ApplicationStarter registered under id="ideaformatter" in plugin.xml.
 * <p>
 * Invoked by IntelliJ's application startup when the command-line contains:
 * com.intellij.idea.Main ideaformatter [flags] file1 file2 ...
 * <p>
 * Supported flags:
 * --format            reformat code via CodeStyleManager
 * --optimize-imports  remove unused / sort imports via OptimizeImportsProcessor
 * --rearrange         rearrange class members via RearrangeCodeProcessor
 * --editorconfig <p>  path to .editorconfig; its parent dir is used as the project root
 * so IntelliJ's EditorConfig plugin picks up the file automatically
 * <p>
 * This class runs INSIDE the spawned IntelliJ subprocess (not in the thin CLI process).
 * IntelliJ Platform JARs are on the classpath of that subprocess; the caller (FormatterLauncher)
 * never loads IntelliJ classes directly.
 */
public class IdeaFormatterStarter implements ApplicationStarter {

    private static void die(String msg) {
        System.err.println("[formatter] ERROR: " + msg);
        System.exit(1);
    }

    @Override
    public void main(@NotNull List<String> args) {
        boolean doFormat = false;
        boolean doOptimizeImports = false;
        boolean doRearrange = false;
        Path editorConfigDir = null;
        List<Path> files = new ArrayList<>();

        // args[0] is "ideaformatter" (the routing key) - skip it
        Iterator<String> it = args.listIterator(1);
        while (it.hasNext()) {
            String arg = it.next();
            switch (arg) {
                case "--format" -> doFormat = true;
                case "--optimize-imports" -> doOptimizeImports = true;
                case "--rearrange" -> doRearrange = true;
                case "--editorconfig" -> {
                    if (!it.hasNext()) die("--editorconfig requires a path argument");
                    Path ec = Path.of(it.next()).toAbsolutePath();
                    editorConfigDir = ec.getParent() != null ? ec.getParent() : Path.of(".");
                }
                default -> {
                    if (arg.startsWith("-")) die("Unknown option: " + arg);
                    files.add(Path.of(arg).toAbsolutePath());
                }
            }
        }

        if (files.isEmpty()) die("No source files specified.");

        // Set project base so IntelliJ's EditorConfig plugin finds the .editorconfig
        // by walking up from that directory (standard EditorConfig discovery behaviour).
        Path projectBase = editorConfigDir != null
                ? editorConfigDir
                : files.get(0).getParent();

        Project project = openProject(projectBase);
        if (project == null) die("Failed to open a temporary project at " + projectBase);

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

        System.exit(0);
    }

    private void processFile(Project project, Path filePath,
                             boolean format, boolean optimizeImports, boolean rearrange) {
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filePath);
        if (vf == null) {
            System.err.println("[formatter] SKIP (VirtualFile not found): " + filePath);
            return;
        }

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
