#!/usr/bin/env python3
"""Tests for the CI module-coverage / AVF harness wiring guard."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check_ci_module_coverage.py")
REPOSITORY = Path(__file__).resolve().parents[2]


def run(root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--root", str(root)],
        capture_output=True,
        text=True,
        check=False,
    )


def fixture(
    root: Path,
    *,
    modules: tuple[str, ...] = ("contract", "network"),
    ci_modules: tuple[str, ...] = ("contract", "network"),
    release_modules: tuple[str, ...] = ("contract", "network"),
    ci_app_tests: bool = True,
    release_app_tests: bool = True,
    harnesses: tuple[str, ...] = ("avfAlphaTest",),
    listed: tuple[str, ...] = ("avfAlphaTest",),
    ci_extra: str = "",
) -> None:
    (root / ".github" / "workflows").mkdir(parents=True)
    (root / "runtime" / "engine-avf").mkdir(parents=True)
    includes = "\n".join(f'include(":runtime:{name}")' for name in modules)
    (root / "settings.gradle.kts").write_text(includes + "\n", encoding="utf-8")

    def checks(names: tuple[str, ...], app_tests: bool) -> str:
        lines = [f":runtime:{name}:check" for name in names]
        if app_tests:
            lines.append(":app:testDebugUnitTest")
        return "\n".join(lines)

    (root / ".github" / "workflows" / "ci.yml").write_text(
        checks(ci_modules, ci_app_tests) + "\n" + ci_extra, encoding="utf-8"
    )
    (root / ".github" / "workflows" / "release.yml").write_text(
        checks(release_modules, release_app_tests) + "\n", encoding="utf-8"
    )

    tasks = "\n".join(f'tasks.register<JavaExec>("{name}") {{ }}' for name in harnesses)
    entries = "\n".join(f'    "{name}",' for name in listed)
    (root / "runtime" / "engine-avf" / "build.gradle.kts").write_text(
        tasks
        + "\n\nval avfAllTestTasks = listOf(\n"
        + entries
        + '\n)\n\ntasks.named("test") { dependsOn(avfAllTestTasks) }\n',
        encoding="utf-8",
    )


class CheckCiModuleCoverageTest(unittest.TestCase):
    def test_missing_runtime_check_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root, ci_modules=("contract",))
            result = run(root)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("FAIL  ci.yml runs :runtime:network:check", result.stdout)

    def test_full_fixture_passes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root)
            result = run(root)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("SUMMARY: ", result.stdout)

    def test_missing_app_unit_tests_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root, release_app_tests=False)
            result = run(root)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("FAIL  release.yml runs :app:testDebugUnitTest", result.stdout)

    def test_harness_missing_from_check_dependency_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root, harnesses=("avfAlphaTest", "avfBetaTest"), listed=("avfAlphaTest",))
            result = run(root)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("FAIL  avfAllTestTasks includes avfBetaTest", result.stdout)

    def test_reference_to_a_removed_harness_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root, ci_extra=":runtime:engine-avf:avfDeletedTest\n")
            result = run(root)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("avfDeletedTest is not a registered task", result.stdout)

    def test_current_repository_passes(self) -> None:
        result = run(REPOSITORY)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
