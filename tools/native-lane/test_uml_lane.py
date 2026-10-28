#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Zero-device tests for the UML research lane (session 20, D10).

Run from the repository root (or the X: subst drive)::

    python -m unittest discover -s tools/native-lane -p 'test_*.py'

Nothing here boots a kernel: the covered surface is parameter assembly,
command-line generation, result schema, the hostfs/ubd0 branch choice, and the
methodology judges (compute checksum, jitter, fault normalization,
interleaving). The launch path itself is only exercised to the point where it
refuses cleanly on a host without the out-of-repo artifacts.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
UML_LANE = HERE / "uml_lane.py"
MEASURE = HERE / "measure.py"

sys.path.insert(0, str(HERE))
import uml_lane  # noqa: E402  (path set above on purpose)
import gen_fixture  # noqa: E402


def run(args: list[str], cwd: Path | None = None, env: dict | None = None,
        timeout: int = 180) -> subprocess.CompletedProcess:
    return subprocess.run(args, cwd=str(cwd or REPO), capture_output=True,
                          text=True, encoding="utf-8", errors="replace",
                          timeout=timeout, env=env)


def bare_namespace(**kwargs) -> argparse.Namespace:
    """The UML options with everything left at its default."""
    return argparse.Namespace(**{
        "uml_kernel": None, "uml_stub": None, "uml_hostfs_dir": None,
        "uml_ubd0": None, "uml_mem": None, "uml_ncpus": None,
        "uml_prefault": None, "uml_seccomp": None, "uml_clang": None,
        "files": 7, **kwargs})


class KernelArgvTest(unittest.TestCase):
    """Launch argv assembly, fork-docs summary section A."""

    def test_stub_exe_is_required(self) -> None:
        cfg = uml_lane.UmlConfig(kernel=Path("k"), stub="")
        with self.assertRaises(uml_lane.LaneError) as caught:
            uml_lane.build_kernel_argv(cfg)
        self.assertEqual(caught.exception.code, "STUB_REQUIRED")

    def test_fork_summary_defaults(self) -> None:
        cfg = uml_lane.config_from_args(bare_namespace())
        argv = uml_lane.build_kernel_argv(cfg)
        self.assertIn("mem=512M", argv)
        self.assertIn("rw", argv)
        self.assertIn("init=/um-init", argv)
        self.assertIn("con=null", argv)
        self.assertIn("con0=fd:0,fd:1", argv)
        self.assertIn("panic=1", argv)
        self.assertIn("seccomp=on", argv)
        self.assertIn("ncpus=1", argv)
        self.assertIn("rootfstype=hostfs", argv)

    def test_hostfs_mode_is_default_and_omits_ubd(self) -> None:
        cfg = uml_lane.config_from_args(bare_namespace())
        self.assertEqual(cfg.rootfs_mode, "hostfs")
        argv = uml_lane.build_kernel_argv(cfg)
        self.assertIn(f"rootflags={cfg.hostfs_dir.as_posix()}", argv)
        self.assertFalse(any(arg.startswith("ubd0=") for arg in argv))
        self.assertFalse(any(arg.startswith("root=/dev/ubda") for arg in argv))

    def test_ubd0_mode_uses_root_dev_ubda(self) -> None:
        cfg = uml_lane.config_from_args(bare_namespace(uml_ubd0="alpine.ext4"))
        self.assertEqual(cfg.rootfs_mode, "ubd0")
        argv = uml_lane.build_kernel_argv(cfg)
        self.assertIn("ubd0=alpine.ext4", argv)
        self.assertIn("root=/dev/ubda", argv)
        self.assertNotIn("rootfstype=hostfs", argv)

    def test_prefault_is_optional(self) -> None:
        base = uml_lane.build_kernel_argv(uml_lane.config_from_args(bare_namespace()))
        self.assertFalse(any(arg.startswith("prefault=") for arg in base))
        with_prefault = uml_lane.build_kernel_argv(
            uml_lane.config_from_args(bare_namespace(uml_prefault="512K")))
        self.assertIn("prefault=512K", with_prefault)

    def test_seccomp_mode_is_validated(self) -> None:
        cfg = uml_lane.config_from_args(bare_namespace(uml_seccomp="fast"))
        with self.assertRaises(uml_lane.LaneError) as caught:
            uml_lane.build_kernel_argv(cfg)
        self.assertEqual(caught.exception.code, "SECCOMP_INVALID")

    def test_mem_format_is_validated(self) -> None:
        cfg = uml_lane.config_from_args(bare_namespace(uml_mem="lots"))
        with self.assertRaises(uml_lane.LaneError) as caught:
            uml_lane.build_kernel_argv(cfg)
        self.assertEqual(caught.exception.code, "MEM_INVALID")

    def test_launch_argv_starts_with_the_kernel_path(self) -> None:
        cfg = uml_lane.config_from_args(bare_namespace())
        argv = uml_lane.build_launch_argv(cfg)
        self.assertEqual(argv[0], str(cfg.kernel))
        self.assertIn("stub_exe=" + cfg.stub.as_posix(), argv)

    def test_default_paths_point_outside_the_repo(self) -> None:
        cfg = uml_lane.config_from_args(bare_namespace())
        for path in (cfg.kernel, cfg.stub, cfg.hostfs_dir):
            self.assertNotIn(str(REPO), str(path),
                             "artifact default drifted into the repo")
        self.assertIn("evidence/uml-20261028/release-v0.3.0",
                      cfg.kernel.as_posix().replace("\\", "/"))
        self.assertEqual(cfg.kernel.name, "linux-bionic")
        self.assertEqual(cfg.stub.name, "stub_exe")

    def test_posix_slashes_in_kernel_argv(self) -> None:
        argv = uml_lane.build_kernel_argv(uml_lane.config_from_args(bare_namespace()))
        for arg in argv:
            self.assertNotIn("\\", arg)


class GuestScriptTest(unittest.TestCase):
    """The script the guest shell runs via /um-command.sh."""

    def test_markers_present_including_compute(self) -> None:
        script = uml_lane.build_guest_script(7, 7)
        for marker in ("NL_T0=", "NL_T1=", "NL_RC=", "NL_OBJS=", "NL_COMPUTE="):
            self.assertIn(marker, script)

    def test_concurrency_is_real_and_per_level(self) -> None:
        one = uml_lane.build_guest_script(1, 7)
        seven = uml_lane.build_guest_script(7, 7)
        self.assertIn("xargs -P 1 -n 1", one)
        self.assertIn("xargs -P 7 -n 1", seven)
        self.assertNotEqual(one, seven)

    def test_no_proot_and_no_device_paths(self) -> None:
        script = uml_lane.build_guest_script(1, 7)
        self.assertNotIn("proot", script)
        self.assertNotIn("/data/local/tmp", script)

    def test_no_windows_paths(self) -> None:
        script = uml_lane.build_guest_script(1, 7)
        self.assertNotRegex(script, r"[A-Za-z]:[\\/]")

    def test_compute_driver_links_one_object(self) -> None:
        script = uml_lane.build_guest_script(1, 7)
        self.assertIn("check.c", script)
        self.assertIn("clang check.c f0.o -o check", script)
        self.assertIn("int fixture_entry(void);", script)


class MethodologyTest(unittest.TestCase):
    """The hard rules, mechanically enforced where they can be."""

    def test_rounds_constant_is_seven(self) -> None:
        self.assertEqual(uml_lane.ROUNDS, 7)

    def test_interleaved_schedule_rotates(self) -> None:
        schedule = uml_lane.interleaved_schedule([1, 7])
        self.assertEqual(len(schedule), uml_lane.ROUNDS)
        for round_order in schedule:
            self.assertEqual(sorted(round_order), [1, 7],
                             "a round lost or duplicated a condition")
        self.assertNotEqual(schedule[0], schedule[1],
                            "fixed order across rounds: position bias")

    def test_compute_judge_ok_and_discard(self) -> None:
        expected = uml_lane.expected_compute_sum()
        self.assertEqual(uml_lane.judge_compute(expected, expected)["status"], "OK")
        just_inside = expected + int(expected * 0.05)
        self.assertEqual(uml_lane.judge_compute(expected, just_inside)["status"], "OK")
        beyond = expected + int(expected * 0.05) + 1
        self.assertEqual(uml_lane.judge_compute(expected, beyond)["status"],
                         "DISCARD_TABLE")

    def test_missing_compute_check_is_not_ok(self) -> None:
        self.assertEqual(uml_lane.judge_compute(6010, None)["status"],
                         "NO_COMPUTE_CHECK")

    def test_round_jitter_threshold(self) -> None:
        self.assertLess(uml_lane.round_jitter([100, 104, 102]), 0.08)
        self.assertGreater(uml_lane.round_jitter([100, 130, 90]), 0.08)

    def test_fault_normalization_by_page_bytes(self) -> None:
        # 16 KiB touched: 4 faults on a 4K-page kernel, 1 on the 16K guest.
        native = uml_lane.normalize_faults(4, uml_lane.PAGE_BYTES_NATIVE)
        guest = uml_lane.normalize_faults(1, uml_lane.PAGE_BYTES_UML_GUEST)
        self.assertEqual(native, guest,
                         "raw fault counts compared across page sizes")
        self.assertEqual(uml_lane.PAGE_BYTES_UML_GUEST, 16384)

    def test_expected_compute_sum_matches_the_generator(self) -> None:
        fixture = gen_fixture.build_fixture(2000)
        calls = re.findall(r"probe_fn_(\d+)\((\d+)\)", fixture)
        self.assertTrue(calls, "generator layout drifted; re-derive the sum")
        actual = sum(int(name) + int(arg) for name, arg in calls)
        self.assertEqual(actual, uml_lane.expected_compute_sum(2000))


class ConfigAndSchemaTest(unittest.TestCase):
    """Config resolution and the synthetic row schema."""

    def test_config_defaults(self) -> None:
        cfg = uml_lane.config_from_args(bare_namespace())
        self.assertEqual(cfg.mem, "512M")
        self.assertEqual(cfg.ncpus, 1)
        self.assertEqual(cfg.seccomp, "on")
        self.assertIsNone(cfg.prefault)
        self.assertEqual(cfg.rootfs_mode, "hostfs")

    def test_artifact_status_reports_presence_without_raising(self) -> None:
        facts = uml_lane.artifact_status(uml_lane.config_from_args(bare_namespace()))
        self.assertEqual(set(facts), {"kernel_present", "stub_present",
                                      "rootfs_ready"})
        self.assertIsInstance(facts["kernel_present"], bool)

    def test_synthetic_rows_carry_the_run_batch_schema(self) -> None:
        cfg = uml_lane.config_from_args(bare_namespace())
        rows = uml_lane.synthetic_rows(cfg, [1, 7], files=7)
        self.assertEqual(len(rows), 2)
        for row in rows:
            for key in ("column", "jobs", "files", "status", "wall_ms",
                        "files_per_min", "objects_ok", "command", "samples_ms",
                        "rounds", "compute", "faults", "rootfs_mode"):
                self.assertIn(key, row)
            self.assertEqual(row["column"], "uml")
            self.assertEqual(row["rounds"], 7)
            self.assertEqual(len(row["samples_ms"]), 7)
            self.assertEqual(row["status"], "OK")
            self.assertEqual(row["compute"]["status"], "OK")
            self.assertEqual(row["faults"]["status"], "NOT_COLLECTED")

    def test_synthetic_wall_ms_is_the_median_of_seven(self) -> None:
        import statistics
        cfg = uml_lane.config_from_args(bare_namespace())
        row = uml_lane.synthetic_rows(cfg, [1], files=7)[0]
        self.assertEqual(row["wall_ms"],
                         int(round(statistics.median(row["samples_ms"]))))
        self.assertEqual(len(row["samples_ms"]), uml_lane.ROUNDS)


class RunnerCliTest(unittest.TestCase):
    """The standalone runner and the measure.py integration."""

    def test_plan_prints_launch_argv_and_methodology(self) -> None:
        completed = run([sys.executable, str(UML_LANE), "--plan"])
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("stub_exe=", completed.stdout)
        self.assertIn("rootfstype=hostfs", completed.stdout)
        self.assertIn("medians", completed.stdout)
        self.assertIn("xargs -P 7", completed.stdout)

    def test_run_refuses_cleanly_without_a_usable_host(self) -> None:
        completed = run([sys.executable, str(UML_LANE), "--run"])
        self.assertEqual(completed.returncode, 2, completed.stdout)
        self.assertTrue("UML_HOST_UNSUPPORTED" in completed.stderr
                        or "UML_KERNEL_MISSING" in completed.stderr,
                        completed.stderr)

    def test_measure_mixed_dry_run_includes_uml_rows(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            completed = run([sys.executable, str(MEASURE), "--dry-run",
                             "--columns", "proot,bare,uml", "--out", str(out)],
                            env={**os.environ, "ORCHESTELLA_WORKSPACE": str(tmp)})
            self.assertEqual(completed.returncode, 0, completed.stderr)
            report = json.loads((max(p for p in out.iterdir() if p.is_dir())
                                 / "native-lane.json").read_text(encoding="utf-8"))
            uml_rows = [row for row in report["rows"] if row["column"] == "uml"]
            self.assertEqual(len(uml_rows), 2)
            for row in uml_rows:
                self.assertEqual(row["status"], "OK")
                self.assertIsNotNone(row["wall_ms"])
                self.assertNotIn("tools/native-lane", row["command"])
            # the adb columns are untouched by the uml dispatch
            self.assertEqual(len([r for r in report["rows"]
                                  if r["column"] in ("proot", "bare")]), 4)

    def test_measure_pure_uml_dry_run_skips_adb(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            completed = run([sys.executable, str(MEASURE), "--dry-run",
                             "--columns", "uml", "--out", str(out)],
                            env={**os.environ, "ORCHESTELLA_WORKSPACE": str(tmp)})
            self.assertEqual(completed.returncode, 0, completed.stderr)
            run_dir = max(p for p in out.iterdir() if p.is_dir())
            report = json.loads((run_dir / "native-lane.json")
                                .read_text(encoding="utf-8"))
            self.assertEqual({row["column"] for row in report["rows"]}, {"uml"})
            self.assertEqual(report["env"]["incomplete"], [])
            markdown = (run_dir / "native-lane.md").read_text(encoding="utf-8")
            self.assertIn("| `uml` |", markdown)

    def test_measure_pure_uml_rejects_regen(self) -> None:
        completed = run([sys.executable, str(MEASURE), "--dry-run",
                         "--columns", "uml", "--regen-dot-i"])
        self.assertEqual(completed.returncode, 2)
        self.assertIn("--regen-dot-i", completed.stderr)

    def test_measure_rejects_unknown_column(self) -> None:
        completed = run([sys.executable, str(MEASURE), "--dry-run",
                         "--columns", "kvm"])
        self.assertEqual(completed.returncode, 2)
        self.assertIn("unknown column", completed.stderr)


if __name__ == "__main__":
    unittest.main()
