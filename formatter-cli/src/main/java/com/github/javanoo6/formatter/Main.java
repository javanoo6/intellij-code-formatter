package com.github.javanoo6.formatter;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "formatter",
        description = "Format source files using the IntelliJ Platform engine.",
        mixinStandardHelpOptions = true,
        version = "1.0"
)
public class Main implements Callable<Integer> {

    @Option(names = "--editorconfig",
            description = "Path to the .editorconfig file. Its parent directory becomes the EditorConfig root. Auto-discovered from file location if omitted.")
    private Path editorConfigPath;

    @Option(names = "--format",
            description = "Reformat code (respects .editorconfig and code style settings).")
    private boolean format;

    @Option(names = "--optimize-imports",
            description = "Remove unused imports and sort the remaining ones.")
    private boolean optimizeImports;

    @Option(names = "--rearrange",
            description = "Rearrange class members according to the arrangement rules.")
    private boolean rearrange;

    @Option(names = "--engine-dir",
            description = "Path to an already-unpacked IntelliJ engine directory. Defaults to ./engine/ next to the JAR, then to the bundled ZIP extracted to $TMPDIR.")
    private Path engineDir;

    @Parameters(paramLabel = "<file>", description = "Source files or directories to process.")
    private List<Path> files;

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        if (!format && !optimizeImports && !rearrange) {
            System.err.println("Error: specify at least one of --format, --optimize-imports, --rearrange");
            return 1;
        }
        if (files==null || files.isEmpty()) {
            System.err.println("Error: at least one file path is required.");
            return 1;
        }
        return new FormatterLauncher(engineDir, editorConfigPath, format, optimizeImports, rearrange)
                .launch(files);
    }
}
