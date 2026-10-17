from pathlib import Path
import importlib.util
import os
import subprocess
import sys
import unittest
import uuid

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/build_qa.py"
spec = importlib.util.spec_from_file_location("qa_build_cache", SCRIPT)
qa_build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa_build)


class CacheInputTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        value = os.environ.get("QA_CACHE_TEST_ROOT")
        if not value:
            raise RuntimeError("QA_CACHE_TEST_ROOT must be an explicit external directory")
        cls.root = Path(value)
        source_root = SCRIPT.parents[3]
        if not cls.root.is_absolute() or cls.root.resolve().is_relative_to(source_root):
            raise RuntimeError("QA_CACHE_TEST_ROOT must be outside the source checkout")
        cls.root.mkdir(parents=True, exist_ok=True)

    def new_directory(self):
        path = self.root / uuid.uuid4().hex
        path.mkdir()
        return path

    def test_missing_or_relative_input_is_rejected(self):
        for value in [None, "", "relative-cache"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                qa_build.resolve_cache(value)

    def test_cache_requires_every_pinned_artifact(self):
        cache = self.new_directory()
        artifacts = []
        for name, pin in qa_build.PINS.items():
            artifact = cache / name / "13.1.5" / pin / (name + "-13.1.5.aar")
            artifact.parent.mkdir(parents=True)
            artifact.write_bytes(b"synthetic-cache-entry")
            artifacts.append(artifact)
        self.assertEqual(cache, qa_build.resolve_cache(cache))
        for artifact in artifacts:
            data = artifact.read_bytes()
            artifact.unlink()
            with self.subTest(module=artifact.name), self.assertRaises(ValueError):
                qa_build.resolve_cache(cache)
            artifact.write_bytes(data)

    def test_cli_without_cache_stops_before_output_creation(self):
        parent = self.new_directory()
        output = parent / "output-must-not-exist"
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
        env.pop(qa_build.CACHE_ENV, None)
        result = subprocess.run(
            [sys.executable, "-B", str(SCRIPT), "--label", "missing-cache", "--output-root", str(output)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, timeout=15,
        )
        self.assertEqual(2, result.returncode)
        self.assertIn(b"supply --gradle-cache", result.stderr)
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
