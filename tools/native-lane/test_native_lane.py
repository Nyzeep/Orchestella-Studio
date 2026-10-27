#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Zero-device tests for the native-lane kit.

Run from the repository root::

    python -m unittest discover -s tools/native-lane -p 'test_*.py'

Nothing here contacts a device: the dry-run tests drive ``measure.py`` against
``fake_adb.py``, and the rest read files. The point is that "it works" is a
statement about the pipeline, not about hardware.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
MEASURE = HERE / "measure.py"
GEN_FIXTURE = HERE / "gen_fixture.py"
PREPARE = HERE / "prepare_rootfs.sh"
EXPECTED = HERE / "tests" / "expected_dry_run.json"
LOCK = HERE / "tools.lock.json"

sys.path.insert(0, str(HERE))
import gen_fixture  # noqa: E402  (path set above on purpose)


def run(args: list[str], cwd: Path | None = None, env: dict | None = None,
        timeout: int = 180) -> subprocess.CompletedProcess:
    return subprocess.run(args, cwd=str(cwd or REPO), capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout, env=env)


def clean_env() -> dict:
    env = dict(os.environ)
    for variable in ("NATIVE_LANE_CLANG", "ANDROID_NDK_HOME", "ANDROID_NDK_ROOT",
                     "ANDROID_SDK_ROOT", "ANDROID_HOME"):
        env.pop(variable, None)
    return env


def posix_shell() -> str | None:
    """A bash that can actually run the script on this machine.

    On Windows ``bash`` on PATH is often the WSL stub, which cannot see this
    filesystem (and may be blocked outright). Portable Git ships a real one next
    to git.exe, so try that first and prove the candidate works before using it.
    """
    candidates: list[str] = []
    git = shutil.which("git")
    if git:
        sibling = Path(git).parent / "bash.exe"
        if sibling.is_file():
            candidates.append(str(sibling))
    for name in ("bash", "sh"):
        found = shutil.which(name)
        if found:
            candidates.append(found)
    for candidate in candidates:
        try:
            probe = subprocess.run([candidate, "-c", "echo ok"], capture_output=True,
                                   text=True, timeout=30)
        except (OSError, subprocess.TimeoutExpired):
            continue
        if probe.returncode == 0 and "ok" in probe.stdout:
            return candidate
    return None


def project(report: dict) -> dict:
    """The stable slice of a run report: what the expectation file pins down."""
    run_block = report["run"]
    return {
        "synthetic": report["synthetic"],
        "fixture": {"bytes": run_block["fixture"]["bytes"],
                    "sha256": run_block["fixture"]["sha256"]},
        "parameters": {key: run_block["parameters"][key]
                       for key in ("jobs", "files_per_batch", "clock")},
        "rows": [{key: row.get(key) for key in
                  ("column", "jobs", "status", "wall_ms", "files",
                   "files_per_min", "objects_ok")} for row in report["rows"]],
        "reused": {name: {"status": entry["status"], "value_ms": entry["value_ms"]}
                   for name, entry in report["reused"].items()},
        "env": {key: report["env"][key] for key in
                ("nproc", "cpuinfo_max_freq_khz", "scaling_governor", "battery_temp_dc",
                 "screen", "thermal_start", "thermal_end", "incomplete")},
        "preflight": {"clang_version": report["preflight"]["clang_version"]},
        "warnings": report["warnings"],
    }


def latest_run_dir(out: Path) -> Path:
    runs = sorted(path for path in out.iterdir() if path.is_dir())
    if not runs:
        raise AssertionError(f"no run directory under {out}")
    return runs[-1]


class FixtureContractTest(unittest.TestCase):
    """The fixture must stay the same translation unit the distcc line uses."""

    def test_generator_matches_dv_pvm_compiler(self) -> None:
        sys.path.insert(0, str(REPO / "tools" / "device-validation"))
        try:
            import dv_pvm_compiler  # noqa: PLC0415
        finally:
            sys.path.pop(0)
        self.assertEqual(gen_fixture.build_fixture(2000),
                         dv_pvm_compiler.build_fixture(2000),
                         "gen_fixture drifted from dv_pvm_compiler.build_fixture; the "
                         "native columns would no longer measure the same input as qemu/pvm")

    def test_manifest_digests_match_files_on_disk(self) -> None:
        manifest = json.loads((HERE / "fixtures" / "MANIFEST.json").read_text(encoding="utf-8"))
        import hashlib
        for name, facts in manifest["files"].items():
            data = (HERE / "fixtures" / name).read_bytes()
            self.assertEqual(len(data), facts["bytes"], f"{name} size drifted")
            self.assertEqual(hashlib.sha256(data).hexdigest(), facts["sha256"],
                             f"{name} digest drifted")

    def test_verify_mode_passes_without_clang(self) -> None:
        completed = run([sys.executable, str(GEN_FIXTURE), "--verify"], env=clean_env())
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("FIXTURE_VERIFY_OK", completed.stdout)


class DryRunTest(unittest.TestCase):
    """The pipeline end to end, with no device anywhere."""

    def test_dry_run_matches_handwritten_expectation(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            completed = run([sys.executable, str(MEASURE), "--dry-run", "--out", str(out)],
                            env=clean_env())
            self.assertEqual(completed.returncode, 0, completed.stderr)
            report = json.loads((latest_run_dir(out) / "native-lane.json")
                                .read_text(encoding="utf-8"))
            expected = json.loads(EXPECTED.read_text(encoding="utf-8"))
            expected.pop("_comment", None)
            self.assertEqual(project(report), expected)

    def test_markdown_renders_every_column_and_a_synthetic_banner(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            completed = run([sys.executable, str(MEASURE), "--dry-run", "--out", str(out)],
                            env=clean_env())
            self.assertEqual(completed.returncode, 0, completed.stderr)
            markdown = (latest_run_dir(out) / "native-lane.md").read_text(encoding="utf-8")
            self.assertIn("SYNTHETIC RUN — NOT A MEASUREMENT", markdown)
            for column in ("qemu", "pvm", "proot", "bare"):
                self.assertIn(f"| `{column}` |", markdown)
            self.assertIn("| column | -j1 wall ms |", markdown)

    def test_device_commands_never_leak_a_host_path(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            completed = run([sys.executable, str(MEASURE), "--dry-run", "--out", str(out)],
                            env=clean_env())
            self.assertEqual(completed.returncode, 0, completed.stderr)
            report = json.loads((latest_run_dir(out) / "native-lane.json")
                                .read_text(encoding="utf-8"))
            for row in report["rows"]:
                command = row["command"]
                self.assertNotRegex(command, r"(^|[\s'\"])[A-Za-z]:/", row["column"])
                self.assertNotRegex(command, r"/c/Users/", row["column"])
                self.assertNotIn("tools/native-lane/fixtures", command)
                # Every batch must actually run concurrently, not pretend to.
                self.assertIn(f"xargs -P {row['jobs']} -n 1", command)

    def test_job_levels_are_distinct_batches(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            run([sys.executable, str(MEASURE), "--dry-run", "--out", str(out)], env=clean_env())
            report = json.loads((latest_run_dir(out) / "native-lane.json")
                                .read_text(encoding="utf-8"))
            commands = {(row["column"], row["jobs"]): row["command"] for row in report["rows"]}
            self.assertEqual(len(set(commands.values())), len(commands),
                             "two rows share a command; job levels are not independent")
            self.assertIn("-P 7", commands[("proot", 7)])
            self.assertIn("-P 1", commands[("proot", 1)])

    def test_reused_columns_are_labelled_not_faked(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            completed = run([sys.executable, str(MEASURE), "--dry-run", "--out", str(out),
                             "--qemu-ms", "25781", "--pvm-ms", "1055"], env=clean_env())
            self.assertEqual(completed.returncode, 0, completed.stderr)
            report = json.loads((latest_run_dir(out) / "native-lane.json")
                                .read_text(encoding="utf-8"))
            self.assertEqual(report["reused"]["qemu"]["status"], "REUSED")
            self.assertEqual(report["reused"]["qemu"]["value_ms"], 25781)
            self.assertEqual(report["reused"]["pvm"]["value_ms"], 1055)

    def test_files_below_max_jobs_is_refused(self) -> None:
        completed = run([sys.executable, str(MEASURE), "--dry-run", "--jobs", "1,7",
                         "--files", "2"], env=clean_env())
        self.assertEqual(completed.returncode, 2)
        self.assertIn("--files", completed.stderr)


class ProvenanceTest(unittest.TestCase):
    """A sample missing a provenance field must not become a table row."""

    def test_missing_probe_withholds_the_table(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            env = clean_env()
            env["FAKE_ADB_DROP"] = "nproc"
            completed = run([sys.executable, str(MEASURE), "--dry-run", "--out", str(out)],
                            env=env)
            self.assertEqual(completed.returncode, 3, completed.stderr)
            report = json.loads((latest_run_dir(out) / "native-lane.json")
                                .read_text(encoding="utf-8"))
            self.assertIn("nproc", report["env"]["incomplete"])
            self.assertIsNone(report["env"]["nproc"])
            markdown = (latest_run_dir(out) / "native-lane.md").read_text(encoding="utf-8")
            self.assertIn("Incomplete provenance", markdown)

    def test_allow_incomplete_keeps_rows_but_exits_clean(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            env = clean_env()
            env["FAKE_ADB_DROP"] = "battery"
            completed = run([sys.executable, str(MEASURE), "--dry-run", "--out", str(out),
                             "--allow-incomplete-provenance"], env=env)
            self.assertEqual(completed.returncode, 0, completed.stderr)


class LockAndPlanTest(unittest.TestCase):
    def test_every_artifact_is_pinned(self) -> None:
        lock = json.loads(LOCK.read_text(encoding="utf-8"))
        self.assertTrue(lock["artifacts"], "no artifacts pinned")
        for name, artifact in lock["artifacts"].items():
            self.assertTrue(artifact["url"].startswith("https://"), name)
            self.assertRegex(artifact["sha256"], r"^[0-9a-f]{64}$", name)
            self.assertIsInstance(artifact["bytes"], int, name)
        for profile, facts in lock["profiles"].items():
            self.assertTrue(facts["repositories"], profile)
            self.assertTrue(facts["clang_binary"].startswith("/usr/bin/"), profile)

    def test_plan_prints_device_commands_without_host_paths(self) -> None:
        shell = posix_shell()
        if shell is None:
            self.skipTest("no usable POSIX shell on this machine (Git Bash not found)")
        completed = run([shell, str(PREPARE), "plan"])
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("/sbin/apk add", completed.stdout)
        self.assertIn("measure.py --device", completed.stdout)
        self.assertNotRegex(completed.stdout, r"(^|[\s'\"])[A-Za-z]:/")
        self.assertNotIn("tools/native-lane/fixtures/fixture.i  /data", completed.stdout)

    def test_status_reports_missing_artifacts_without_crashing(self) -> None:
        shell = posix_shell()
        if shell is None:
            self.skipTest("no usable POSIX shell on this machine (Git Bash not found)")
        completed = run([shell, str(PREPARE), "status"])
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("host os", completed.stdout)


if __name__ == "__main__":
    unittest.main()
