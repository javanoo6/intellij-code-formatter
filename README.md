# IntelliJ Headless Formatter

This repository builds a standalone formatter CLI that uses the IntelliJ Platform to:

- reformat code
- optimize imports
- rearrange members

It can format Java and other file types supported by the plugins bundled into the engine ZIP, including Markdown.

The final runnable artifact is:

- `formatter-cli/target/formatter-cli-full.jar`

It edits files in place.

Supported languages: Java, XML, JSON, Markdown, Python (`.py`, `.pyw`, `.pyi`),
YAML (`.yaml`, `.yml`), and Bash/shell scripts (`.sh`, `.bash`, or a shell shebang).
EditorConfig support is bundled for code style settings.

## Prerequisites

- JDK 21
- Maven
- Internet access for the first build

On macOS with Homebrew:

```bash
brew install openjdk@21 maven
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

The launcher selects the bundled JNA native library for the JVM architecture
(`aarch64`/`arm64` or `amd64`) and enables the Java desktop module access needed
by IntelliJ on macOS. The same JAR is used on each platform.
macOS Apple Silicon was verified with JDK 21; Intel macOS and Linux were not
retested for this change.

Check versions:

```bash
java -version
mvn -version
```

## What To Build

You do not need to build or run IntelliJ manually.

For end users, the important modules are:

- `minimal-jar-builder`
- `formatter-cli`

The `formatter-intellij-plugin` module is internal repo structure. You do not run it directly.

## Step-By-Step

### 1. Clone the repository

```bash
git clone https://github.com/javanoo6/intellij-code-formatter
cd intellij-code-formatter
```

### 2. Build the engine ZIP

This downloads the IntelliJ Community distribution and repackages the runtime needed by the formatter.
It also downloads Python Community Edition from JetBrains Marketplace. Its version is
pinned by `python.plugin.version` in the root POM and must remain compatible with
`intellij.version` ([plugin compatibility](https://plugins.jetbrains.com/plugin/7322-python-community-edition/versions/stable/680217)).
The engine also bundles YAML and Shell Script plugins and downloads pinned,
SHA-256-verified `shfmt` binaries from GitHub for shell formatting.

```bash
mvn package -pl minimal-jar-builder
```

Expected output artifact:

- `minimal-jar-builder/target/formatter-engine.zip`

### 3. Build the runnable formatter jar

This bundles the engine ZIP into the final CLI jar.

```bash
mvn package -pl formatter-cli
```

Expected output artifact:

- `formatter-cli/target/formatter-cli-full.jar`

## Run The Formatter

Basic shape:

```bash
java -jar formatter-cli/target/formatter-cli-full.jar \
  --format \
  --optimize-imports \
  --rearrange \
  --editorconfig /absolute/path/to/.editorconfig \
  /absolute/path/to/File.java
```

### Python

Use the same CLI flags for Python files; no Python interpreter is required:

```bash
java -jar formatter-cli/target/formatter-cli-full.jar --format /absolute/path/to/script.py
```

Formatting uses JetBrains' Python formatter and respects `.editorconfig`,
including `[*.py]` indentation settings. Python import sorting and unused-import
removal are not currently supported in the standalone temporary project;
`--optimize-imports` may leave Python imports unchanged.

After building both artifacts, run the Python integration checks (requires Python 3):

```bash
python3 tests/python_formatting_test.py
```

### YAML and Bash

Use `--format` for YAML and shell scripts:

```bash
java -jar formatter-cli/target/formatter-cli-full.jar --format config.yaml config.yml script.sh
```

YAML uses IntelliJ's formatter. Shell scripts use bundled
[`shfmt` 3.14.1](https://github.com/mvdan/sh/releases/tag/v3.14.1); no separate
installation or runtime download is needed. Shell binaries are included for Linux
and macOS on Intel/AMD64 and ARM64, and Windows on AMD64.

Both support `.editorconfig` discovery and the existing `--editorconfig` flag.
Shell formatting uses tabs by default; configure spaces with:

```ini
[*.{sh,bash}]
indent_style = space
indent_size = 2
```

`shfmt` selects the shell dialect from the filename and shebang. Use a Bash
shebang (`#!/usr/bin/env bash`) for Bash-specific syntax in `.sh` files, or set
`shell_variant = bash` in `.editorconfig`. Invalid shell syntax returns a nonzero
exit code and leaves that script unchanged. Scripts are parsed, never executed.

Run all language integration checks after building:

```bash
python3 -m unittest discover -s tests -p '*_test.py' -v
```

## Daemon Mode

By default, the formatter runs in **daemon mode**. On the first call, a background IntelliJ JVM is started and kept alive. Subsequent calls connect to it
directly, skipping the 3–5 second startup cost.

```
First call  (~5s): engine extracted, daemon started, file formatted
Second call (<1s): connects to running daemon, file formatted instantly
```

The daemon port is stored in `$TMPDIR/intellij-formatter-daemon-*.port`.  
Daemon logs go to `$TMPDIR/intellij-formatter-daemon-*.log`.

### Stop the daemon

```bash
java -jar formatter-cli/target/formatter-cli-full.jar --stop-daemon
```

### Bypass the daemon (one-shot mode)

```bash
java -jar formatter-cli/target/formatter-cli-full.jar \
  --no-daemon \
  --format \
  /absolute/path/to/File.java
```

## What Happens On First Run

On first execution, the jar extracts the bundled IntelliJ runtime to a temporary directory under `/tmp`, then starts the daemon and formats the file.

Typical log lines:

```text
[formatter] First run: extracting IntelliJ engine to /tmp/intellij-formatter-engine-...
[formatter] Engine ready at /tmp/intellij-formatter-engine-...
[formatter] Starting daemon (log: /tmp/intellij-formatter-daemon-....log)
```

## Rebuild After Changes

If you change runtime packaging or formatter logic, rebuild in this order:

```bash
mvn package -pl minimal-jar-builder
mvn package -pl formatter-cli
```

`formatter-cli` must be rebuilt after `minimal-jar-builder`, because it embeds the latest `formatter-engine.zip`.

## Troubleshooting

### `Missing formatter engine ZIP ... Run mvn package -pl minimal-jar-builder successfully first.`

You built `formatter-cli` before `minimal-jar-builder`.

Fix:

```bash
mvn package -pl minimal-jar-builder
mvn package -pl formatter-cli
```

### The file is not changing

Check:

- you are running `formatter-cli-full.jar`, not `formatter-cli-1.0-SNAPSHOT.jar`
- the file path is absolute and correct
- you passed at least one of:
    - `--format`
    - `--optimize-imports`
    - `--rearrange`

### I see no `[formatter] Processing:` output

In daemon mode this is expected — the daemon's stdout goes to its log file, not your terminal. Inspect it:

```bash
cat $TMPDIR/intellij-formatter-daemon-*.log
```

### Daemon seems stuck or unresponsive

Stop it and let the next call start a fresh one:

```bash
java -jar formatter-cli/target/formatter-cli-full.jar --stop-daemon
```

If `--stop-daemon` reports no daemon running but a stale process exists, kill it manually:

```bash
kill $(pgrep -f 'ideaformatter --daemon')
```

### I only want the final artifact

Use:

- `formatter-cli/target/formatter-cli-full.jar`

That is the intended end-user jar.

## License

This repository's launcher and formatter integration code are licensed under the
[MIT License](LICENSE).

Bundled IntelliJ Platform components, language plugins, libraries, and shfmt retain
their respective licenses and copyright notices. The engine ZIP includes the
upstream IntelliJ `LICENSE.txt`, `NOTICE.txt`, and `license/` files, and shfmt's
license is included under `tools/shfmt/LICENSE`.
