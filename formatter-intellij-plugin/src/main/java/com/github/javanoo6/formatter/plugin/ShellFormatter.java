package com.github.javanoo6.formatter.plugin;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.psi.PsiFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Runs the bundled shfmt synchronously so documents are saved before the CLI reports success. */
final class ShellFormatter {
    private ShellFormatter() {
    }

    static void format(PsiFile file) {
        Document document = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        if (document == null) {
            throw new IllegalStateException("Cannot read shell script: " + file.getVirtualFile().getPath());
        }
        try {
            document.setText(formatText(document.getText(), file.getVirtualFile().toNioPath()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot format shell script: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Shell formatting interrupted", e);
        }
    }

    private static String formatText(String text, Path source) throws IOException, InterruptedException {
        Path binary = bundledBinary();
        // ZIP extraction does not preserve POSIX executable permissions.
        if (!Files.isExecutable(binary) && !binary.toFile().setExecutable(true, true)) {
            throw new IOException("Cannot make bundled shell formatter executable: " + binary);
        }
        Path work = Files.createTempDirectory("formatter-shfmt-");
        Path input = work.resolve("input");
        Path output = work.resolve("output");
        Path error = work.resolve("error");
        Process process = null;
        try {
            Files.writeString(input, text, StandardCharsets.UTF_8);
            // Filename enables shfmt's own EditorConfig discovery, including shell-specific options.
            // File redirection avoids pipe deadlocks for large scripts and permits a bounded wait.
            process = new ProcessBuilder(binary.toString(), "--filename", source.toString())
                    .directory(source.getParent().toFile())
                    .redirectInput(input.toFile())
                    .redirectOutput(output.toFile())
                    .redirectError(error.toFile())
                    .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw new IOException("Shell formatting timed out after 30 seconds: " + source);
            }
            if (process.exitValue() != 0) {
                String diagnostic = Files.readString(error, StandardCharsets.UTF_8).strip();
                throw new IOException(source + " (exit " + process.exitValue() + "): " + diagnostic);
            }
            return Files.readString(output, StandardCharsets.UTF_8);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly().waitFor();
            }
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
            Files.deleteIfExists(error);
            Files.deleteIfExists(work);
        }
    }

    private static Path bundledBinary() throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String platform;
        if (os.startsWith("linux")) platform = "linux";
        else if (os.startsWith("mac")) platform = "darwin";
        else if (os.startsWith("windows")) platform = "windows";
        else throw new IOException("Shell formatting is unsupported on " + os);

        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        arch = switch (arch) {
            case "amd64", "x86_64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> throw new IOException("Shell formatting is unsupported on " + os + "/" + arch);
        };
        String extension = platform.equals("windows") ? ".exe" : "";
        Path binary = Path.of(System.getProperty("idea.home.path"), "tools", "shfmt",
                "shfmt_" + platform + "_" + arch + extension);
        if (!Files.isRegularFile(binary)) {
            throw new IOException("Bundled shell formatter is missing for " + platform + "/" + arch
                    + ": " + binary + ". Use a complete formatter-cli-full.jar or rebuild the engine.");
        }
        return binary;
    }
}
