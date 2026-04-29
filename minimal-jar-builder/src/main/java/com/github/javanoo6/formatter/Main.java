package com.github.javanoo6.formatter;

/**
 * Build-time engine verifier.
 * <p>
 * Run against the formatter-engine.zip classpath to confirm all required
 * IntelliJ Platform classes are present before using the engine in formatter-cli.
 * <p>
 * Usage (after unpacking formatter-engine.zip to /tmp/engine):
 * java -cp "/tmp/engine/lib/*:/tmp/engine/plugins/java/lib/*:/tmp/engine/plugins/editorconfig/lib/*" \
 * com.github.javanoo6.formatter.Main
 */
public class Main {

    private static final String[] REQUIRED_CLASSES = {
            "com.intellij.psi.codeStyle.CodeStyleManager",
            "com.intellij.codeInsight.actions.ReformatCodeProcessor",
            "com.intellij.codeInsight.actions.OptimizeImportsProcessor",
            "com.intellij.codeInsight.actions.RearrangeCodeProcessor",
            "com.intellij.openapi.application.ApplicationStarter",
            "com.intellij.psi.PsiManager",
            "com.intellij.openapi.vfs.LocalFileSystem",
            "com.intellij.openapi.project.Project",
    };

    public static void main(String[] args) throws Exception {
        System.out.println("Verifying IntelliJ Platform engine JAR set...\n");
        boolean allOk = true;
        for (String cls : REQUIRED_CLASSES) {
            try {
                Class.forName(cls);
                System.out.println("  OK   " + cls);
            } catch (ClassNotFoundException e) {
                System.out.println("  MISS " + cls);
                allOk = false;
            }
        }
        System.out.println();
        if (allOk) {
            System.out.println("Engine JAR set is complete. All required classes found.");
        } else {
            System.err.println("Engine JAR set is INCOMPLETE. Add missing JARs.");
            System.exit(1);
        }
    }
}
