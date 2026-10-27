"""Exercise Java import cleanup during cold startup and repeated project opens."""

import unittest

from formatter_test_support import FormatterTestCase


class JavaFormattingTest(FormatterTestCase):
    def check_cleanup(self, mode):
        for index in range(4):
            with self.subTest(mode=mode, project=index):
                directory = self.project / str(index)
                directory.mkdir()
                source = directory / "Sample.java"
                source.write_text(
                        "import java.util.List;\n"
                        "import java.util.Map;\n"
                        "class Sample{int sum(int a,int b){return a+b;}}\n"
                )
                self.run_formatter(*mode, "--format", "--optimize-imports", "--rearrange", source)
                result = source.read_text()
                self.assertNotIn("import ", result)
                self.assertIn("return a + b;", result)

    def test_cold_one_shot_java_cleanup(self):
        self.check_cleanup(["--no-daemon"])

    def test_repeated_daemon_java_cleanup(self):
        self.check_cleanup([])


if __name__ == "__main__":
    unittest.main(verbosity=2)
