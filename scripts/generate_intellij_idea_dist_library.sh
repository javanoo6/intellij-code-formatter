#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
idea_dist="$repo_root/minimal-jar-builder/target/idea-dist"
output_dir="$repo_root/.idea/libraries"
output_file="$output_dir/IntelliJ_Idea_Dist.xml"

if [[ ! -d "$idea_dist" ]]; then
  echo "Missing $idea_dist"
  echo "Run: mvn -pl minimal-jar-builder generate-resources"
  exit 1
fi

mkdir -p "$output_dir"

tmp_file="$(mktemp)"
trap 'rm -f "$tmp_file"' EXIT

{
  echo '<component name="libraryTable">'
  echo '  <library name="IntelliJ Idea Dist">'
  echo '    <CLASSES>'

  find "$idea_dist/lib" -maxdepth 1 -type f -name '*.jar' | sort | while read -r jar; do
    rel="${jar#"$repo_root"/}"
    echo "      <root url=\"jar://\$PROJECT_DIR\$/$rel!/\" />"
  done

  if [[ -d "$idea_dist/plugins/java/lib" ]]; then
    find "$idea_dist/plugins/java/lib" -maxdepth 1 -type f -name '*.jar' | sort | while read -r jar; do
      rel="${jar#"$repo_root"/}"
      echo "      <root url=\"jar://\$PROJECT_DIR\$/$rel!/\" />"
    done
  fi

  if [[ -d "$idea_dist/plugins/editorconfig/lib" ]]; then
    find "$idea_dist/plugins/editorconfig/lib" -maxdepth 1 -type f -name '*.jar' | sort | while read -r jar; do
      rel="${jar#"$repo_root"/}"
      echo "      <root url=\"jar://\$PROJECT_DIR\$/$rel!/\" />"
    done
  fi

  echo '    </CLASSES>'
  echo '    <JAVADOC />'
  echo '    <SOURCES />'
  echo '  </library>'
  echo '</component>'
} > "$tmp_file"

mv "$tmp_file" "$output_file"

echo "Wrote $output_file"
