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

## What Happens On First Run

On first execution, the jar extracts the bundled IntelliJ runtime to a temporary directory under `/tmp`, then runs the formatter headlessly.

Typical log lines:

```text
[formatter] Engine ready at /tmp/intellij-formatter-engine-...
[formatter] Processing: FormatterResult.java
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

### I only want the final artifact

Use:

- `formatter-cli/target/formatter-cli-full.jar`

That is the intended end-user jar.
