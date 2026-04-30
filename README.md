# IntelliJ Headless Formatter

This repository builds a standalone formatter CLI that uses the IntelliJ Platform to:

- reformat code
- optimize imports
- rearrange members

The final runnable artifact is:

- `formatter-cli/target/formatter-cli-full.jar`

It edits files in place.

## Prerequisites

- JDK 21
- Maven
- Internet access for the first build

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

## Daemon Mode

By default, the formatter runs in **daemon mode**. On the first call, a background IntelliJ JVM is started and kept alive. Subsequent calls connect to it directly, skipping the 3–5 second startup cost.

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
