#!/usr/bin/env python3
"""Fail when CI or release gates stop covering every runtime Gradle module.

The source of truth is ``settings.gradle.kts``: every ``include(":runtime:*")``
must appear as ``:runtime:<name>:check`` in both ``ci.yml`` and
``release.yml``, and both workflows must run ``:app:testDebugUnitTest``.
The AVF JavaExec harnesses are also checked: each registered harness must be
part of ``avfAllTestTasks`` so ``:runtime:engine-avf:check`` runs (and fails)
it, and no workflow may name a harness that no longer exists.

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

    print(f"SUMMARY: {result.passed} passed, {result.failed} failed")
    return 0 if result.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
