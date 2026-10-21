#!/usr/bin/env python3
"""Policy gate for the Fast Lane zero-device GitHub workflow.

The workflow is the only CI entry point for this effort. A YAML typo, a
device-dependent step, a signing/publish command or a disabled gate must fail
here rather than silently pass in CI. PyYAML is a CI-only validation
dependency; it is not shipped in the APK.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - the CI job installs PyYAML
    yaml = None

REQUIRED_JOBS = {
    "toolbox-and-licenses",
    "jvm-distcc",
    "apk-bin-gate",
    "payload-build",
    "payload-host",
}
REQUIRED_PATH_FILTERS = {
    "protocol/**",
    "fastlane-toolbox/**",
    "runtime/fastlane-terminal/**",
    "runtime/fastlane-capability/**",
    "runtime/fastlane-proxy/**",
    "runtime/fastlane-storage/**",
    "runtime/fastlane-telemetry/**",
    "runtime/fastlane-distcc/**",
    "tools/fastlane-build/**",
    "tools/qemu-tuning/**",
    "app/build.gradle.kts",
    "settings.gradle.kts",
    "NOTICE-fastlane.md",
    ".github/workflows/fastlane-zero-device.yml",
}
GATE_SCRIPT = "tools/ci/run-fastlane-zero-device.sh"
FLN1_FIXTURE = "protocol/fln1-golden.json"
FASTLANE_MODULE_RE = re.compile(r'include\(":runtime:((?:fastlane-[^"]+|engine-fastlane))"\)')
MODULES_BLOCK_RE = re.compile(r"MODULES=\(([^)]*)\)", re.DOTALL)
FORBIDDEN_RUN_PATTERNS = {
    r"(?<![\w-])adb(?![\w-])": "adb/device command",
    r"connected(?:AndroidTest|Check)": "device test",
    r"assembleRelease|bundleRelease": "release build",
    r"apksigner|jarsigner|signingConfig": "signing step",
    r"git\s+tag": "tagging",
    r"git\s+push": "pushing",
    r"gh\s+release": "release publication",
    r"\bsecrets\.": "secret reference",
    r"--device": "device flag",
    r"\bemulator\b": "emulator",
}


def load_workflow(path: Path) -> dict:
    if yaml is None:
        raise ValueError(
            "PyYAML is required to validate the workflow: "
            "python3 -m pip install pyyaml==6.0.2"
        )
    try:
        document = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as failure:
        raise ValueError(f"cannot parse workflow {path}: {failure}") from failure
    if not isinstance(document, dict):
        raise ValueError("workflow is not a YAML mapping")
    return document


def _triggers(document: dict):
    # YAML 1.1 parsers read the key `on` as boolean True.
    return document.get("on", document.get(True))


def _walk_continue_on_error(value, path="workflow") -> list[str]:
    problems = []
    if isinstance(value, dict):
        for key, child in value.items():
            if str(key) == "continue-on-error" and child is True:
                problems.append(f"{path}: continue-on-error is not allowed")
            problems.extend(_walk_continue_on_error(child, f"{path}.{key}"))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            problems.extend(_walk_continue_on_error(child, f"{path}[{index}]"))
    return problems


def _steps_text(job: dict) -> str:
    chunks = []
    for step in job.get("steps") or []:
        if isinstance(step, dict) and isinstance(step.get("run"), str):
            chunks.append(step["run"])
    return "\n".join(chunks)


def gate_modules(root: Path) -> list[str]:
    """Modules the shared gate script promises to run."""
    path = root / GATE_SCRIPT
    if not path.is_file():
        return []
    match = MODULES_BLOCK_RE.search(path.read_text(encoding="utf-8", errors="replace"))
    if not match:
        return []
    return [line.strip() for line in match.group(1).splitlines() if line.strip()]


def fastlane_modules(root: Path) -> list[str]:
    settings = root / "settings.gradle.kts"
    if not settings.is_file():
        return []
    return sorted(set(FASTLANE_MODULE_RE.findall(
        settings.read_text(encoding="utf-8", errors="replace"))))


def check_workflow(document: dict, root: Path | None = None) -> list[str]:
    root = Path(".") if root is None else root
    problems: list[str] = []
    if not str(document.get("name", "")).strip():
        problems.append("workflow has no name")

    triggers = _triggers(document)
    if not isinstance(triggers, dict):
        problems.append("workflow `on` must be a mapping")
        triggers = {}
    for trigger in ("pull_request", "push", "workflow_dispatch"):
        if trigger not in triggers:
            problems.append(f"workflow is missing the {trigger} trigger")
    for trigger in ("pull_request", "push"):
        config = triggers.get(trigger) or {}
        paths = config.get("paths") if isinstance(config, dict) else None
        if not isinstance(paths, list):
            problems.append(f"{trigger} must declare a paths filter")
            continue
        missing = REQUIRED_PATH_FILTERS - set(map(str, paths))
        if missing:
            problems.append(f"{trigger}.paths is missing: {sorted(missing)}")

    permissions = document.get("permissions") or {}
    if not isinstance(permissions, dict) or permissions.get("contents") != "read":
        problems.append("workflow must declare permissions.contents: read")
    if isinstance(permissions, dict):
        for name, value in permissions.items():
            if "write" in str(value):
                problems.append(f"permission {name} must not be write")

    problems.extend(_walk_continue_on_error(document))

    jobs = document.get("jobs")
    if not isinstance(jobs, dict) or not jobs:
        return problems + ["workflow has no jobs"]
    missing_jobs = REQUIRED_JOBS - set(jobs)
    if missing_jobs:
        problems.append(f"workflow is missing jobs: {sorted(missing_jobs)}")

    all_run_text: dict[str, str] = {}
    for job_name, job in jobs.items():
        if not isinstance(job, dict):
            problems.append(f"job {job_name} is not a mapping")
            continue
        runs_on = str(job.get("runs-on", ""))
        if not runs_on.startswith("ubuntu-"):
            problems.append(f"job {job_name} must run on ubuntu-*, got {runs_on!r}")
        timeout = job.get("timeout-minutes")
        if not isinstance(timeout, int) or timeout <= 0 or timeout > 90:
            problems.append(f"job {job_name} needs timeout-minutes in 1..90")
        steps = job.get("steps")
        if not isinstance(steps, list) or not steps:
            problems.append(f"job {job_name} has no steps")
            continue
        for index, step in enumerate(steps):
            if not isinstance(step, dict):
                problems.append(f"job {job_name} step {index} is not a mapping")
                continue
            if not str(step.get("name", "")).strip():
                problems.append(f"job {job_name} step {index} has no name")
            if "run" not in step and "uses" not in step:
                problems.append(f"job {job_name} step {index} has neither run nor uses")
            if step.get("if") is False:
                problems.append(f"job {job_name} step {index} is disabled with if: false")
        all_run_text[job_name] = _steps_text(job)
        for pattern, label in FORBIDDEN_RUN_PATTERNS.items():
            if re.search(pattern, all_run_text[job_name], flags=re.IGNORECASE):
                problems.append(f"job {job_name} contains a forbidden {label}")
        if re.search(r"self-hosted", runs_on, flags=re.IGNORECASE):
            problems.append(f"job {job_name} must not use a self-hosted runner")

    toolbox = all_run_text.get("toolbox-and-licenses", "")
    for required in (
        "unittest discover",
        "tools/qemu-tuning",
        "fastlane_build.py build",
        "verify-toolbox",
        "prove_gate_blocks.py",
        "check-licenses",
        "reproduce.py",
        "check_zero_device_workflow.py",
    ):
        if required not in toolbox:
            problems.append(f"toolbox-and-licenses is missing: {required}")

    jvm = all_run_text.get("jvm-distcc", "")
    if f"{GATE_SCRIPT} jvm" not in jvm:
        problems.append(f"jvm-distcc must run {GATE_SCRIPT} jvm")
    scripted = set(gate_modules(root))
    for module in fastlane_modules(root):
        if module not in scripted:
            problems.append(f"{GATE_SCRIPT} MODULES is missing {module}")

    host_payload = all_run_text.get("payload-host", "")
    for required in (f"{GATE_SCRIPT} cpp", f"{GATE_SCRIPT} e2e", "junit-4.13.2.jar"):
        if required not in host_payload:
            problems.append(f"payload-host is missing: {required}")
    if not (root / FLN1_FIXTURE).is_file():
        problems.append(f"missing shared fixture: {FLN1_FIXTURE}")

    apk = all_run_text.get("apk-bin-gate", "")
    for required in (
        ":app:assembleDebug",
        ":app:verifyFastlaneToolboxApk",
        "check-apk-16kb.sh",
    ):
        if required not in apk:
            problems.append(f"apk-bin-gate is missing: {required}")

    payload = all_run_text.get("payload-build", "")
    if "prove_payload_fixture.py" not in payload:
        problems.append("payload-build is missing the host fixture proof")
    if "build-payload.sh" not in payload:
        problems.append("payload-build is missing the real payload build hook")

    if re.search(r"\bmain\b", str(triggers.get("push", {})), flags=re.IGNORECASE) is None:
        problems.append("push trigger must target main")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--workflow", default=".github/workflows/fastlane-zero-device.yml"
    )
    parser.add_argument("--root", default=".", help="repository root for script/module checks")
    args = parser.parse_args(argv)
    try:
        document = load_workflow(Path(args.workflow))
    except ValueError as failure:
        print(f"FAIL {failure}", file=sys.stderr)
        return 1
    problems = check_workflow(document, Path(args.root))
    if problems:
        for problem in problems:
            print(f"FAIL {problem}", file=sys.stderr)
        return 1
    print("WORKFLOW_OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
