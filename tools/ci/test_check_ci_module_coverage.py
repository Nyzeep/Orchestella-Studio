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
AVF_PACKAGE = "io.github.nyzeep.orchestella.runtime.engineavf"
ALPHA = f"{AVF_PACKAGE}.AvfAlphaTest"
BETA = f"{AVF_PACKAGE}.AvfBetaTest"
ORPHAN = f"{AVF_PACKAGE}.AvfOrphanTest"
GHOST = f"{AVF_PACKAGE}.AvfGhostTest"


def run(root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--root", str(root)],
        capture_output=True,
        text=True,
        check=False,
    )


def write_source(
    module_dir: Path,
    fqn: str,
    *,
    junit: bool = False,
    manual_reason: str | None = None,
) -> Path:
    """Write a test source: a ``main`` harness, or a JUnit test that also has one."""
    package, _, name = fqn.rpartition(".")
    path = module_dir / "src" / "test" / "java" / Path(*package.split(".")) / f"{name}.java"
    path.parent.mkdir(parents=True, exist_ok=True)
    if junit:
        body = "    @Test\n    public void testSomething() {}\n\n"
        imports = "import org.junit.Test;\n"
    else:
        body = ""
        imports = ""
    marker = "" if manual_reason is None else f"// fl-manual-driver: {manual_reason}\n"
    path.write_text(
        f"package {package};\n\n{imports}\n{marker}public final class {name} {{\n"
        f"{body}    public static void main(String[] args) {{}}\n}}\n",
        encoding="utf-8",
    )
    return path


def fixture(
    root: Path,
    *,
    modules: tuple[str, ...] = ("contract", "network", "engine-avf"),
    ci_modules: tuple[str, ...] = ("contract", "network", "engine-avf"),
    release_modules: tuple[str, ...] = ("contract", "network", "engine-avf"),
    ci_app_tests: bool = True,
    release_app_tests: bool = True,
    harnesses: tuple[tuple[str, str], ...] = (("avfAlphaTest", ALPHA),),
    listed: tuple[str, ...] = ("avfAlphaTest",),
    sources: tuple[str, ...] | None = None,
    junit_sources: tuple[str, ...] = (),
    manual_sources: tuple[tuple[str, str], ...] = (),
    ci_extra: str = "",
) -> None:
    (root / ".github" / "workflows").mkdir(parents=True)
    module_dir = root / "runtime" / "engine-avf"
    module_dir.mkdir(parents=True)
    (module_dir / "src" / "test" / "java").mkdir(parents=True)
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

    tasks = "\n".join(
        f'tasks.register<JavaExec>("{name}") {{\n    mainClass.set("{main_class}")\n}}'
        for name, main_class in harnesses
    )
    entries = "\n".join(f'    "{name}",' for name in listed)
    (module_dir / "build.gradle.kts").write_text(
        tasks
        + "\n\nval avfAllTestTasks = listOf(\n"
        + entries
        + '\n)\n\ntasks.named("test") { dependsOn(avfAllTestTasks) }\n',
        encoding="utf-8",
    )

    # Every registered mainClass gets a real source unless the caller says
    # otherwise, so only the behaviour under test can fail the gate.
    write_only = [fqn for _, fqn in harnesses] if sources is None else list(sources)
    for fqn in write_only:
        write_source(module_dir, fqn)
    for fqn in junit_sources:
        write_source(module_dir, fqn, junit=True)
    for fqn, reason in manual_sources:
        write_source(module_dir, fqn, manual_reason=reason)


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
            fixture(
                root,
                harnesses=(("avfAlphaTest", ALPHA), ("avfBetaTest", BETA)),
                listed=("avfAlphaTest",),
            )
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

    def test_registered_harness_passes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root, harnesses=(("avfOrphanTest", ORPHAN),), listed=("avfOrphanTest",))
            result = run(root)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn(
                "PASS  runtime/engine-avf registers harness AvfOrphanTest", result.stdout
            )

    def test_unregistered_harness_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root, sources=(ALPHA, ORPHAN))
            result = run(root)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn(
                "FAIL  runtime/engine-avf registers harness AvfOrphanTest: "
                "runtime/engine-avf/src/test/java/io/github/nyzeep/orchestella/runtime/"
                f"engineavf/AvfOrphanTest.java -> missing mainClass {ORPHAN}",
                result.stdout,
            )
            self.assertNotIn("registers harness AvfAlphaTest: ", result.stdout)

    def test_main_class_without_a_source_file_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root, harnesses=(("avfGhostTest", GHOST),), listed=("avfGhostTest",), sources=())
            result = run(root)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn(
                "FAIL  runtime/engine-avf JavaExec avfGhostTest mainClass exists: "
                f"avfGhostTest -> {GHOST} has no .java file",
                result.stdout,
            )

    def test_junit_test_with_main_is_not_flagged(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            junit_fqn = f"{AVF_PACKAGE}.JunitWithMainTest"
            fixture(root, junit_sources=(junit_fqn,))
            result = run(root)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertNotIn("JunitWithMainTest", result.stdout)

    def test_declared_manual_driver_is_reported_not_failed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = f"{AVF_PACKAGE}.AvfManualDriver"
            reason = "needs a payload binary built beforehand"
            fixture(root, manual_sources=((driver, reason),))
            result = run(root)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn(
                f"INFO  runtime/engine-avf manual driver AvfManualDriver: {reason}",
                result.stdout,
            )

    def test_manual_driver_without_a_reason_still_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = f"{AVF_PACKAGE}.AvfManualDriver"
            fixture(root, manual_sources=((driver, ""),))
            result = run(root)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn(
                "FAIL  runtime/engine-avf registers harness AvfManualDriver", result.stdout
            )

    def test_current_repository_is_green(self) -> None:
        """The real checkout has no silent harnesses and no dangling mainClass.

        The only non-task harness is ``PvmCompilerBridgeInterop``, which declares
        a manual-driver reason; it must show up as INFO, not disappear.
        """
        result = run(REPOSITORY)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("INFO  runtime/fastlane-distcc manual driver PvmCompilerBridgeInterop", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
