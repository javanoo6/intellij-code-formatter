"""Bash and YAML integration checks against the release JAR."""

import shutil
import subprocess
import unittest

from formatter_test_support import FormatterTestCase


class ShellYamlFormattingTest(FormatterTestCase):
    def test_yaml_and_yml_one_shot(self):
        sources = [
                self.write_source(name, "root:\n   child:  [1,2,3]\n")
                for name in ("sample.yaml", "sample.yml")
        ]
        self.run_formatter("--no-daemon", "--format", *sources)
        for source in sources:
            with self.subTest(source=source.name):
                self.assertEqual("root:\n  child: [ 1,2,3 ]\n", source.read_text())

    def test_yaml_editorconfig_and_block_scalar(self):
        config = self.write_source(
                ".editorconfig", "root = true\n\n[*.{yaml,yml}]\nindent_style = space\nindent_size = 4\n"
        )
        sources = [
                self.write_source(name, "root:\n  text: |\n    hello world\n    second line\n  count:  1\n")
                for name in ("sample.yaml", "sample.yml")
        ]
        self.run_formatter("--format", "--editorconfig", config, *sources)
        for source in sources:
            with self.subTest(source=source.name):
                self.assertIn("\n    text: |\n", source.read_text())
                self.assertIn("hello world\n", source.read_text())
                self.assertIn("\n    count: 1\n", source.read_text())

    def test_bash_sh_and_shebang_one_shot(self):
        sources = [
                self.write_source(name, "#!/usr/bin/env bash\nif true;then\necho hello\nfi\n")
                for name in ("sample.bash", "sample.sh", "script")
        ]
        for source in sources:
            source.chmod(0o755)
        self.run_formatter("--no-daemon", "--format", *sources)
        for source in sources:
            with self.subTest(source=source.name):
                self.assertEqual(
                        "#!/usr/bin/env bash\nif true; then\n\techo hello\nfi\n", source.read_text()
                )
                self.assertTrue(source.stat().st_mode & 0o100)
                if shutil.which("bash"):
                    subprocess.run(["bash", "-n", str(source)], check=True, capture_output=True)

    def test_bash_editorconfig(self):
        config = self.write_source(
                ".editorconfig", "root = true\n\n[*.sh]\nindent_style = space\nindent_size = 2\n"
        )
        source = self.write_source("sample.sh", "#!/bin/bash\nif true;then\necho hello\nfi\n")
        self.run_formatter("--format", "--editorconfig", config, source)
        self.assertEqual("#!/bin/bash\nif true; then\n  echo hello\nfi\n", source.read_text())

    def test_external_files_respect_explicit_editorconfig(self):
        config = self.write_source(
                ".editorconfig", "root = true\n\n[*.{sh,yml}]\nindent_style = space\nindent_size = 4\n"
        )
        outside = self.project / "outside"
        outside.mkdir()
        config_dir = self.project / "config"
        config_dir.mkdir()
        config = config.rename(config_dir / ".editorconfig")
        shell = self.write_source("outside/script.sh", "#!/bin/bash\nif true;then\necho hello\nfi\n")
        yaml = self.write_source("outside/sample.yml", "root:\n  child:  1\n")
        self.run_formatter("--format", "--editorconfig", config, shell, yaml)
        self.assertIn("\n    echo hello\n", shell.read_text())
        self.assertEqual("root:\n    child: 1\n", yaml.read_text())
        self.assertFalse(list(config_dir.glob("ideaformatter-link-*")))

    def test_daemon_reloads_and_is_idempotent(self):
        shell = self.write_source("sample.sh", "#!/bin/bash\nif true;then\necho first\nfi\n")
        yaml = self.write_source("sample.yml", "root:\n   child:  [1,2]\n")
        self.run_formatter("--format", shell, yaml)
        first = (shell.read_text(), yaml.read_text())
        self.run_formatter("--format", shell, yaml)
        self.assertEqual(first, (shell.read_text(), yaml.read_text()))
        shell.write_text("#!/bin/bash\nif true;then\necho second\nfi\n")
        yaml.write_text("root:\n   child:  [3,4]\n")
        self.run_formatter("--format", shell, yaml)
        self.assertIn("\n\techo second\n", shell.read_text())
        self.assertEqual("root:\n  child: [ 3,4 ]\n", yaml.read_text())

    def test_invalid_shell_fails_without_modifying_source(self):
        invalid = "#!/bin/bash\nif true; then\n"
        for mode in ([], ["--no-daemon"]):
            with self.subTest(mode=mode):
                source = self.write_source("invalid.sh", invalid)
                result = self.run_formatter(*mode, "--format", source, expected_code=1)
                self.assertIn("Cannot format shell script", result.stdout + result.stderr)
                self.assertEqual(invalid, source.read_text())
        # A rejected request must not prevent subsequent daemon requests from succeeding.
        valid = self.write_source("valid.sh", "#!/bin/bash\necho  hello\n")
        self.run_formatter("--format", valid)
        self.assertEqual("#!/bin/bash\necho hello\n", valid.read_text())

    def test_heredoc_and_bash_arrays(self):
        source = self.write_source(
                "sample.bash", '#!/bin/bash\nvalues=(one two)\nif [[ ${values[0]} == one ]];then\ncat <<\'EOF\'\n  literal $value\nEOF\nfi\n'
        )
        self.run_formatter("--format", source)
        self.assertIn("\n  literal $value\nEOF\n", source.read_text())
        if shutil.which("bash"):
            subprocess.run(["bash", "-n", str(source)], check=True, capture_output=True)

    def test_mixed_request_with_all_operations(self):
        shell = self.write_source("sample.sh", "#!/bin/bash\necho  hello\n")
        yaml = self.write_source("sample.yaml", "root:\n   child:  1\n")
        python = self.write_source("sample.py", "value=1+2\n")
        self.run_formatter("--format", "--optimize-imports", "--rearrange", shell, yaml, python)
        self.assertEqual("#!/bin/bash\necho hello\n", shell.read_text())
        self.assertEqual("root:\n  child: 1\n", yaml.read_text())
        self.assertEqual("value = 1 + 2\n", python.read_text())


if __name__ == "__main__":
    unittest.main(verbosity=2)
