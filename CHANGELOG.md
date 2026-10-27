# Changelog

## 0.0.7

- Add YAML formatting for both `.yaml` and `.yml` files using the bundled IntelliJ YAML plugin.
- Add Bash/shell formatting for `.sh`, `.bash`, and shell-shebang files using bundled shfmt 3.14.1.
- Include SHA-256-verified shfmt binaries for Linux and macOS on AMD64/ARM64, and Windows on AMD64, with its BSD license.
- Support EditorConfig indentation and shell options, including explicitly selected config roots for external files.
- Report shell parse errors without changing the failed script; wait for formatting to finish before reporting success.
- Validate one-shot and daemon formatting, invalid shell recovery, heredocs, Bash arrays, and existing languages on Linux with JDK 21. macOS and Windows were
  not tested for this release.

## 0.0.6

- Add Python formatting for `.py`, `.pyw`, and `.pyi` files using the bundled Python Community Edition plugin.
- Respect Python EditorConfig indentation settings without requiring a Python interpreter.
- Bundle TOML support required by the Python plugin.
- Add integration checks for Python formatting, daemon reuse, EditorConfig settings, and existing language support.
- Python import sorting and unused-import removal remain unsupported in the standalone temporary project.
- Validate on Linux with JDK 21; macOS was not retested for this release.

## 0.0.5

- Include the previously unreleased external-file EditorConfig handling already used by the Neovim configuration.
- Fix formatter startup on macOS by opening the required Apple desktop Java modules.
- Select bundled JNA libraries using the JVM architecture instead of always using amd64.
- Document Homebrew JDK 21 setup.
- Validate formatting on macOS Apple Silicon with JDK 21. Intel macOS and Linux have not been retested.

## 0.0.4

- Add Markdown formatting support.
