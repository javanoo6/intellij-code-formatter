# Architecture Flow Diagram

This document shows how the IdeaFormatterStarter components interact during build-time and runtime.

## Build Time Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                              BUILD TIME                             │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  Maven Project Structure                                            │
│                                                                     │
│  pom.xml (parent)                                                   │
│  ├── formatter-cli/ (thin launcher)                                 │
│  ├── formatter-intellij-plugin/                                     │
│  │   ├── IdeaFormatterStarter.java                                  │
│  │   └── plugin.xml (extension point registration)                  │
│  └── minimal-jar-builder/ (creates distributable JAR)               │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Build Process                                                      │
│                                                                     │
│  mvn package → minimal-jar-builder creates:                         │
│  ├── IDEA_FORMATTER_HOME/lib/idea-code-formatter.jar                │
│  │   ├── IdeaFormatterStarter.class                                 │
│  │   ├── META-INF/plugin.xml                                        │
│  │   └── (IntelliJ Platform API dependencies)                       │
│  └── formatter-cli/target/formatter-cli.jar                         │
│      └── FormatterLauncher.class                                    │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Extension Point Registration                                       │
│                                                                     │
│  plugin.xml:                                                        │
│  <extensions defaultExtensionNs="com.intellij">                     │
│    <applicationStarter id="ideaformatter"                           │
│      implementation="com.github.javanoo6.formatter.plugin.          │
│                       IdeaFormatterStarter"                         │
│      internal="true" />                                             │
│  </extensions>                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

## Runtime Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                              RUNTIME                                │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  User Command: idea-format --format file.java                       │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Thin CLI Process (FormatterLauncher)                               │
│                                                                     │
│  ├── Parses user arguments                                          │
│  ├── Locates IDEA_FORMATTER_HOME                                    │
│  ├── Spawns IntelliJ subprocess:                                    │
│  │   java -jar idea-code-formatter.jar ideaformatter --format ...   │
│  └── Waits for subprocess exit code                                 │
└─────────────────────────────────────────────────────────────────────┘
                              │
                    ┌─────────┴─────────┐
                    ▼                   ▼
┌───────────────────────────────────┐  ┌─────────────────────────────┐
│  IntelliJ Subprocess              │  │  Platform Initialization    │
│  (loads idea-code-formatter.jar)  │  │                             │
│                                   │  │  ├── Extension system       │
│  ApplicationStarterEP             │  │  ├── ApplicationStarter     │
│  .findStarter("ideaformatter")    │  │  └── Finds our starter      │
└───────────────────────────────────┘  └─────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  IdeaFormatterStarter.main(args)                                    │
│                                                                     │
│  ├── Parses: --format, --optimize-imports, --rearrange              │
│  ├── Opens temporary project:                                       │
│  │   ProjectManagerEx.getInstanceEx().openProject()                 │
│  └── Wraps work in WriteCommandAction:                              │
│      WriteCommandAction.runWriteCommandAction(project, () -> {      │
│          for (Path file : files) {                                  │
│              processFile(project, file, ...);                       │
│          }                                                          │
│      })                                                             │
└─────────────────────────────────────────────────────────────────────┘
                              │
                    ┌─────────┴─────────┐
                    ▼                   ▼
┌─────────────────────────────┐  ┌─────────────────────────────┐
│  File Processing            │  │  Safety & Access Control    │
│                             │  │                             │
│  processFile():             │  │  NonProjectFileWritingAccess│
│  ├── VirtualFile            │  │  .allowWriting(files)       │
│  ├── PsiFile                │  │                             │
│  └── Apply processors:      │  │  WriteCommandAction:        │
│      ├── ReformatCode       │  │  ├── Ensures write context  │
│      ├── OptimizeImports    │  │  └── Thread safety          │
│      └── RearrangeCode      │  └─────────────────────────────┘
└─────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Platform APIs (from IntelliJ SDK)                                  │
│                                                                     │
│  Code Formatting Processors:                                        │
│  ├── ReformatCodeProcessor → CodeStyleManager.reformatText()        │
│  ├── OptimizeImportsProcessor → ImportOptimizer.processFile()       │
│  └── RearrangeCodeProcessor → ArrangementEngine.arrange()           │
│                                                                     │
│  File System & PSI:                                                 │
│  ├── LocalFileSystem → VirtualFile                                  │
│  ├── PsiManager → PsiFile                                           │
│  └── FileDocumentManager → saveAllDocuments()                       │
│                                                                     │
│  Project Management:                                                │
│  ├── ProjectManagerEx → openProject()                               │
│  └── OpenProjectTask → withProjectName()                            │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Cleanup & Exit                                                     │
│                                                                     │
│  ├── FileDocumentManager.getInstance().saveAllDocuments()           │
│  ├── ProjectManagerEx.getInstanceEx().forceCloseProject(project)    │
│  └── System.exit(0)                                                 │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Thin CLI receives exit code → returns to user                      │
└─────────────────────────────────────────────────────────────────────┘
```

## Key Relationships

### Build-time flow:
- Maven builds both the thin CLI and the IntelliJ plugin
- minimal-jar-builder packages the plugin with IntelliJ Platform APIs
- plugin.xml registers the custom ApplicationStarter extension point

### Runtime flow:
- Thin CLI spawns IntelliJ subprocess with our JAR
- IntelliJ's extension system loads our IdeaFormatterStarter
- The starter uses platform APIs for formatting, file access, and thread safety
- All work happens inside the IntelliJ subprocess, never in the thin CLI

### Critical dependencies:
- `WriteCommandAction` ensures thread-safe file modifications
- `NonProjectFileWritingAccessProvider` allows writing outside the project
- Built-in processors (`ReformatCodeProcessor`, etc.) do the actual formatting work
