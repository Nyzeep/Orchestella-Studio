#!/usr/bin/env python3
"""Fail when CI or release gates stop covering every runtime Gradle module.

The source of truth is ``settings.gradle.kts``: every ``include(":runtime:*")``
must appear as ``:runtime:<name>:check`` in both ``ci.yml`` and
``release.yml``, and both workflows must run ``:app:testDebugUnitTest``.
The AVF JavaExec harnesses are also checked: each registered harness must be
part of ``avfAllTestTasks`` so ``:runtime:engine-avf:check`` runs (and fails)
it, and no workflow may name a harness that no longer exists.

Gradle only discovers JUnit tests, so a harness written as a plain
``public static void main(String[])`` class is silently skipped unless it is
wired into a ``tasks.register<JavaExec>``. Two inverse checks close that hole:

* every harness under ``runtime/<module>/src/test/java`` (``main`` present, no
  ``@Test`` on it) must be the ``mainClass`` of some JavaExec task in that
  module's ``build.gradle.kts`` -- otherwise it is dead coverage;
* every JavaExec ``mainClass`` must resolve to a real ``.java`` file in the
  module, so a renamed/deleted harness cannot leave a task pointing at air.

A harness that genuinely cannot run unattended (it needs operator-supplied
paths) declares ``// fl-manual-driver: <reason>``. It is then reported as INFO
on every run rather than skipped: the gap stays visible, and the reason has to
survive code review. An empty reason does not count.

Standard library only. Run from the repository root:

    python3 tools/ci/check_ci_module_coverage.py
    python3 tools/ci/check_ci_module_coverage.py --root /path/to/checkout
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

INCLUDE_RE = re.compile(r'include\("(:runtime:[^"]+)"\)')
RUNTIME_CHECK_RE = re.compile(r":runtime:([A-Za-z0-9_-]+):check\b")
HARNESS_REGISTER_RE = re.compile(r'tasks\.register<JavaExec>\("([^"]+)"\)')
AVF_TASK_REF_RE = re.compile(r":runtime:engine-avf:([A-Za-z0-9_-]+)")
QUOTED_RE = re.compile(r'"([^"]+)"')
PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
MAIN_METHOD_RE = re.compile(r"\bpublic\s+static\s+void\s+main\s*\(")
JUNIT_TEST_RE = re.compile(r"@\s*Test\b")
MAIN_CLASS_SET_RE = re.compile(r'mainClass\.set\(\s*"([^"]+)"')
MAIN_CLASS_ASSIGN_RE = re.compile(r'mainClass\s*=\s*"([^"]+)"')
MANUAL_DRIVER_RE = re.compile(r"//[ \t]*fl-manual-driver:[ \t]*(\S[^\n]*)")

MANUAL_DRIVER_HELP = (
    "declare '// fl-manual-driver: <reason>' when the harness cannot run unattended, "
    "or register it as a JavaExec task"
)


class Result:
    def __init__(self) -> None:
        self.passed = 0
        self.failed = 0

    def check(self, name: str, ok: bool, detail: str = "") -> bool:
        if ok:
            self.passed += 1
            print(f"PASS  {name}")
        else:
            self.failed += 1
            print(f"FAIL  {name}" + (f": {detail}" if detail else ""))
        return ok

    def manual(self, name: str, reason: str) -> None:
        """A declared manual driver: surfaced on every run, never silently skipped."""
        self.passed += 1
        print(f"INFO  {name}: {reason}")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def runtime_modules(settings_text: str) -> list[str]:
    modules = []
    for include in INCLUDE_RE.findall(settings_text):
        name = include.split(":")[-1]
        if name and name not in modules:
            modules.append(name)
    return sorted(modules)


def checked_modules(workflow_text: str) -> set[str]:
    return set(RUNTIME_CHECK_RE.findall(workflow_text))


def registered_harnesses(build_text: str) -> list[str]:
    return HARNESS_REGISTER_RE.findall(build_text)


def harness_list(build_text: str) -> list[str]:
    marker = "val avfAllTestTasks = listOf("
    start = build_text.find(marker)
    if start < 0:
        return []
    end = build_text.find(")", start)
    if end < 0:
        return []
    return QUOTED_RE.findall(build_text[start:end])


def javaexec_main_classes(build_text: str) -> dict[str, str | None]:
    """Map each registered task to its ``mainClass`` (``None`` when unset).

    A task block runs from its ``tasks.register<JavaExec>`` call to the next
    one, which is enough because ``mainClass`` is always declared inline.
    """
    matches = list(HARNESS_REGISTER_RE.finditer(build_text))
    tasks: dict[str, str | None] = {}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(build_text)
        block = build_text[match.end() : end]
        found = MAIN_CLASS_SET_RE.search(block) or MAIN_CLASS_ASSIGN_RE.search(block)
        tasks[match.group(1)] = found.group(1) if found else None
    return tasks


def qualified_name(path: Path) -> str:
    """Fully qualified name of the top-level type in ``path``."""
    match = PACKAGE_RE.search(read(path))
    return f"{match.group(1)}.{path.stem}" if match else path.stem


def java_types(module_dir: Path) -> dict[str, Path]:
    """Every ``.java`` file in the module, indexed by fully qualified name."""
    index: dict[str, Path] = {}
    for source_set in sorted(module_dir.glob("src/*/java")):
        for path in sorted(source_set.rglob("*.java")):
            index.setdefault(qualified_name(path), path)
    return index


def is_harness(text: str) -> bool:
    """True for ``public static void main(String[])`` classes JUnit ignores."""
    return MAIN_METHOD_RE.search(text) is not None and JUNIT_TEST_RE.search(text) is None


def check_harness_sources(result: Result, root: Path, modules: list[str]) -> None:
    for module in modules:
        module_dir = root / "runtime" / module
        test_root = module_dir / "src" / "test" / "java"
        if not test_root.is_dir():
            continue
        build = module_dir / "build.gradle.kts"
        if not result.check(f"runtime/{module} build file exists", build.is_file()):
            continue
        build_text = read(build)
        main_classes = javaexec_main_classes(build_text)
        registered = {target for target in main_classes.values() if target}

        for path in sorted(test_root.rglob("*.java")):
            text = read(path)
            if not is_harness(text):
                continue
            fqn = qualified_name(path)
            relative = path.relative_to(root).as_posix()
            if fqn in registered:
                result.check(f"runtime/{module} registers harness {path.stem}", True)
                continue
            declared = MANUAL_DRIVER_RE.search(text)
            if declared is not None:
                result.manual(
                    f"runtime/{module} manual driver {path.stem}",
                    declared.group(1).strip(),
                )
                continue
            result.check(
                f"runtime/{module} registers harness {path.stem}",
                False,
                f"{relative} -> missing mainClass {fqn}; {MANUAL_DRIVER_HELP}",
            )

        types = java_types(module_dir)
        for task, target in main_classes.items():
            result.check(
                f"runtime/{module} JavaExec {task} mainClass exists",
                target is not None and target in types,
                f"{task} -> {target or '(no mainClass)'} has no .java file"
                f" under runtime/{module}/src",
            )


def check_workflow(result: Result, root: Path, name: str, modules: list[str]) -> None:
    path = root / ".github" / "workflows" / name
    if not result.check(f"{name} exists", path.is_file()):
        return
    text = read(path)
    covered = checked_modules(text)
    for module in modules:
        result.check(
            f"{name} runs :runtime:{module}:check",
            module in covered,
            f"missing :runtime:{module}:check",
        )
    result.check(
        f"{name} runs :app:testDebugUnitTest",
        ":app:testDebugUnitTest" in text,
        "missing :app:testDebugUnitTest",
    )


def check_avf_harnesses(result: Result, root: Path) -> None:
    path = root / "runtime" / "engine-avf" / "build.gradle.kts"
    if not result.check("engine-avf build file exists", path.is_file()):
        return
    text = read(path)
    registered = registered_harnesses(text)
    listed = harness_list(text)
    result.check(
        "engine-avf registers at least one JavaExec harness",
        bool(registered),
        "no tasks.register<JavaExec> found",
    )
    for task in registered:
        result.check(
            f"avfAllTestTasks includes {task}",
            task in listed,
            f"{task} is not wired into check",
        )
    result.check(
        "test depends on avfAllTestTasks",
        re.search(r'tasks\.named\("test"\)\s*\{[^}]*dependsOn\(avfAllTestTasks\)', text, re.S)
        is not None,
        "tasks.named(\"test\") does not dependOn(avfAllTestTasks)",
    )
    for workflow_name in ("ci.yml", "release.yml"):
        workflow = root / ".github" / "workflows" / workflow_name
        if not workflow.is_file():
            continue
        for reference in sorted(set(AVF_TASK_REF_RE.findall(read(workflow)))):
            result.check(
                f"{workflow_name} references a live engine-avf task: {reference}",
                reference == "check" or reference in registered,
                f":runtime:engine-avf:{reference} is not a registered task",
            )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="repository root (default: two levels above this script)",
    )
    args = parser.parse_args()
    root = args.root.resolve()

    settings = root / "settings.gradle.kts"
    if not settings.is_file():
        print(f"FAIL  settings.gradle.kts not found under {root}")
        return 1
    modules = runtime_modules(read(settings))
    result = Result()
    result.check(
        "settings.gradle.kts declares runtime modules",
        bool(modules),
        "no include(\":runtime:*\") entries",
    )
    for module in modules:
        print(f"INFO  runtime module: {module}")

    check_workflow(result, root, "ci.yml", modules)
    check_workflow(result, root, "release.yml", modules)
    check_avf_harnesses(result, root)
    check_harness_sources(result, root, modules)

    print(f"SUMMARY: {result.passed} passed, {result.failed} failed")
    return 0 if result.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
