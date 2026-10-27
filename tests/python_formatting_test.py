"""Integration checks for the built formatter-cli-full.jar (JDK 21 required)."""

import unittest

from formatter_test_support import FormatterTestCase


class PythonFormattingTest(FormatterTestCase):
    def test_python_and_pyw_one_shot(self):
        sources = [
            self.write_source(name, "def add(a,b):\n    return a+b\n")
            for name in ("sample.py", "window.pyw")
        ]
        self.run_formatter("--no-daemon", "--format", *sources)
        for source in sources:
            with self.subTest(source=source.name):
                self.assertIn("def add(a, b):", source.read_text())
                self.assertIn("    return a + b", source.read_text())

    def test_python_stub(self):
        source = self.write_source("sample.pyi", "def add(a:int,b:int)->int: ...\n")
        self.run_formatter("--format", source)
        self.assertIn("def add(a: int, b: int) -> int:", source.read_text())

    def test_editorconfig(self):
        config = self.write_source(
            ".editorconfig", "root = true\n\n[*.py]\nindent_style = space\nindent_size = 2\n"
        )
        source = self.write_source("sample.py", "def add(a,b):\n    return a+b\n")
        self.run_formatter("--format", "--editorconfig", config, source)
        self.assertIn("\n  return a + b", source.read_text())

    def test_daemon_reloads_and_formatting_is_idempotent(self):
        source = self.write_source("sample.py", "value=1+2\n")
        self.run_formatter("--format", source)
        self.assertEqual("value = 1 + 2\n", source.read_text())
        source.write_text("value=3+4\n")
        self.run_formatter("--format", source)
        self.assertEqual("value = 3 + 4\n", source.read_text())
        self.run_formatter("--format", source)
        self.assertEqual("value = 3 + 4\n", source.read_text())

    def test_format_with_other_operations_enabled(self):
        source = self.write_source(
            "sample.py", "import sys\nimport os\n\nvalue=(sys.version,os.name)\n"
        )
        self.run_formatter("--format", "--optimize-imports", "--rearrange", source)
        self.assertIn("value = (sys.version, os.name)", source.read_text())

    def test_existing_languages(self):
        samples = {
            "Sample.java": "class Sample{int sum(int a,int b){return a+b;}}\n",
            "sample.json": '{"a":1,"b":[2,3]}\n',
            "sample.md": "|a|b|\n|---|---|\n|1|2|\n",
            "sample.xml": '<root><child a="1">value</child><child>other</child></root>\n',
        }
        sources = [self.write_source(name, content) for name, content in samples.items()]
        self.run_formatter("--format", *sources)
        for source in sources:
            with self.subTest(source=source.name):
                self.assertNotEqual(samples[source.name], source.read_text())


if __name__ == "__main__":
    unittest.main(verbosity=2)
