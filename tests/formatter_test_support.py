"""Shared integration harness for the packaged formatter CLI (JDK 21 required)."""

from pathlib import Path
import subprocess
import tempfile
import unittest

JAR = Path(__file__).resolve().parents[1] / "formatter-cli/target/formatter-cli-full.jar"


class FormatterTestCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not JAR.is_file():
            raise RuntimeError("Build minimal-jar-builder and formatter-cli first")
        cls.temp = tempfile.TemporaryDirectory(prefix="formatter-test-")
        cls.root = Path(cls.temp.name)
        cls.runtime = cls.root / "runtime"
        cls.runtime.mkdir()
        # Isolate engine extraction, daemon port, and IDE caches from normal CLI use.
        cls.command = ["java", f"-Djava.io.tmpdir={cls.runtime}", "-jar", str(JAR)]

    @classmethod
    def tearDownClass(cls):
        try:
            subprocess.run(cls.command + ["--stop-daemon"], capture_output=True, timeout=30)
        finally:
            cls.temp.cleanup()

    def setUp(self):
        self.project = self.root / self._testMethodName
        self.project.mkdir()

    def run_formatter(self, *args, expected_code=0):
        result = subprocess.run(
                self.command + [str(arg) for arg in args],
                capture_output=True, text=True, timeout=120,
        )
        logs = "\n".join(
                path.read_text(errors="replace") for path in self.runtime.glob("*.log")
        )
        self.assertEqual(result.returncode, expected_code, result.stdout + result.stderr + logs)
        return result

    def write_source(self, name, content):
        source = self.project / name
        source.write_text(content)
        return source
