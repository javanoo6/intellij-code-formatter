## Core API Dependencies

### 1. ApplicationStarter Interface
**Location:** [ApplicationStarter.kt](https://github.com/JetBrains/intellij-community/blob/bfcc8d73f9255f476efa048f487aebfbfd615dad/platform/ide-core/src/com/intellij/openapi/application/ApplicationStarter.kt)

This is the main interface that enables custom command-line applications in IntelliJ.

```kotlin
// Lines 28-35: Main interface definition
interface ApplicationStarter {
  companion object {
    const val NON_MODAL: Int = 1
    const val ANY_MODALITY: Int = 2  
    const val NOT_IN_EDT: Int = 3
    
    @ApiStatus.Internal
    const val EP_FQN: String = "com.intellij.appStarter"
    
    private val EP_NAME = ExtensionPointName<ApplicationStarterEP>(EP_FQN)
    
    @ApiStatus.Internal
    @JvmStatic
    fun findStarter(key: String): ApplicationStarter? = EP_NAME.findByIdOrFromInstance(key, idGetter = { "no-${key}" })?.get()
  }
}
```

**Key methods:**
- [Line 69](https://github.com/JetBrains/intellij-community/blob/bfcc8d73f9255f476efa048f487aebfbfd615dad/platform/ide-core/src/com/intellij/openapi/application/ApplicationStarter.kt#L69): `fun main(args: List<String>) {}` - Called with command-line arguments
- [Line 74](https://github.com/JetBrains/intellij-community/blob/bfcc8d73f9255f476efa048f487aebfbfd615dad/platform/ide-core/src/com/intellij/openapi/application/ApplicationStarter.kt#L74): `val isHeadless: Boolean get() = true` - Enables headless operation
- [Line 60](https://github.com/JetBrains/intellij-community/blob/bfcc8d73f9255f476efa048f487aebfbfd615dad/platform/ide-core/src/com/intellij/openapi/application/ApplicationStarter.kt#L60): `fun premain(args: List<String>) {}` - Called before application initialization

### 2. ApplicationStarterEP Extension Point
**Location:** [ApplicationStarterEP.java](https://github.com/JetBrains/intellij-community/blob/bfcc8d73f9255f476efa048f487aebfbfd615dad/platform/ide-core/src/com/intellij/openapi/application/ApplicationStarterEP.java)

```java
// Line 13: Extension point implementation
public final class ApplicationStarterEP extends LazyExtensionInstance<ApplicationStarter> implements PluginAware {
  @RequiredElement
  @Attribute("implementation")
  public String implementation;
  
  @Attribute("internal") 
  public boolean isInternal = false;
}
```

This shows how custom starters are registered via plugin.xml with an `implementation` attribute.

### 3. Code Formatting Processors

#### ReformatCodeProcessor
**Location:** [ReformatCodeProcessor.java](https://github.com/JetBrains/intellij-community/blob/bfcc8d73f9255f476efa048f487aebfbfd615dad/platform/lang-impl/src/com/intellij/codeInsight/actions/ReformatCodeProcessor.java)

```java
// Lines 85-90: Constructor used by IdeaFormatterStarter
public ReformatCodeProcessor(Project project, PsiFile file, @Nullable TextRange range, boolean processChangedTextOnly) {
    super(project, file, getProgressText(), getCommandName(), processChangedTextOnly);
    if (range != null) {
        myRanges.add(range);
    }
}
```

The processor uses `CodeStyleManager.getInstance(myProject).reformatText()` ([line 239](https://github.com/JetBrains/intellij-community/blob/bfcc8d73f9255f476efa048f487aebfbfd615dad/platform/lang-impl/src/com/intellij/codeInsight/actions/ReformatCodeProcessor.java#L239)) internally.

#### OptimizeImportsProcessor  
**Location:** [OptimizeImportsProcessor.java](https://github.com/JetBrains/intellij-community/blob/bfcc8d73f9255f476efa048f487aebfbfd615dad/platform/lang-impl/src/com/intellij/codeInsight/actions/OptimizeImportsProcessor.java)

```java
// Lines 61-63: Constructor used by IdeaFormatterStarter
public OptimizeImportsProcessor(@NotNull Project project, @NotNull PsiFile file) {
    super(project, file, getProgressText(), getCommandName(), false);
}
```

#### RearrangeCodeProcessor
**Location:** [RearrangeCodeProcessor.java](https://github.com/JetBrains/intellij-community/blob/bfcc8d73f9255f476efa048f487aebfbfd615dad/platform/lang-impl/src/com/intellij/codeInsight/actions/RearrangeCodeProcessor.java)

```java
// Lines 45-47: Constructor used by IdeaFormatterStarter  
public RearrangeCodeProcessor(@NotNull PsiFile psiFile) {
    super(psiFile.getProject(), psiFile, getProgressText(), CodeInsightBundle.message("command.rearrange.code"), false);
}
```

### 4. Write Command Actions
**Location:** [WriteCommandAction.java](https://github.com/JetBrains/intellij-community/blob/bfcc8d73f9255f476efa048f487aebfbfd615dad/platform/core-api/src/com/intellij/openapi/command/WriteCommandAction.java)

```java
// Lines 352-354: Method used for thread-safe write operations
public static <T> T runWriteCommandAction(Project project, final @NotNull Computable<T> computable) {
    return writeCommandAction(project).compute(() -> computable.compute());
}
```

This ensures all file modifications happen in proper write contexts.

### 5. Non-Project File Writing Access
**Location:** [NonProjectFileWritingAccessProvider.java](https://github.com/JetBrains/intellij-community/blob/bfcc8d73f9255f476efa048f487aebfbfd615dad/platform/ide-core-impl/src/com/intellij/openapi/fileEditor/impl/NonProjectFileWritingAccessProvider.java)

```java
// Lines 173-177: Critical method for allowing writes to non-project files
public static void allowWriting(Iterable<? extends VirtualFile> allowedFiles) {
    for (VirtualFile eachAllowed : allowedFiles) {
        ACCESS_ALLOWED.getValue(eachAllowed).incrementAndGet();
    }
}
```

This was discovered during debugging when the formatter couldn't write to files outside the temporary project.

### 6. Project Management
**Location:** [ProjectManagerEx.kt](https://github.com/JetBrains/intellij-community/blob/bfcc8d73f9255f476efa048f487aebfbfd615dad/platform/ide-core-impl/src/com/intellij/openapi/project/ex/ProjectManagerEx.kt)

```kotlin
// Line 75: Method used to open temporary projects
abstract fun openProject(projectStoreBaseDir: Path, options: OpenProjectTask): Project?

// Lines 93-96: Method used to close projects
@Internal
fun forceCloseProject(project: Project): Boolean {
    return forceCloseProject(project = project, save = false)
}
```

### 7. OpenProjectTask Builder
**Location:** [OpenProjectTask.kt](https://github.com/JetBrains/intellij-community/blob/bfcc8d73f9255f476efa048f487aebfbfd615dad/platform/ide-core-impl/src/com/intellij/ide/impl/OpenProjectTask.kt)

```kotlin
// Lines 102-105: Builder pattern for project opening options
companion object {
    @JvmStatic
    fun build(): OpenProjectTask = OpenProjectTask()
}

// Line 111: Method to set project name
fun withProjectName(projectName: String?): OpenProjectTask = copy(projectName = projectName)
```

### Key Design Decisions

1. **Headless Operation**: Uses `ApplicationStarter` interface designed for CLI tools
2. **Temporary Projects**: Creates lightweight projects just for EditorConfig discovery
3. **Built-in Processors**: Leverages IntelliJ's existing code style processors instead of custom implementation
4. **Thread Safety**: Uses `WriteCommandAction` for all file modifications
5. **File Access**: Explicitly allows writing to non-project files via `NonProjectFileWritingAccessProvider`
