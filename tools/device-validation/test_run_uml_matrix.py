#!/usr/bin/env python3
"""Unit tests for tools/device-validation/run_uml_matrix.py."""

from __future__ import annotations

import json
import os
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))

import run_uml_matrix  # noqa: E402


class TestFrozenSamplePlan(unittest.TestCase):
    def test_frozen_300_cycle_sample_plan_structure(self) -> None:
        plan = run_uml_matrix.build_frozen_sample_plan()
        self.assertEqual(plan["schema_version"], 1)
        self.assertEqual(plan["total_cycles"], 300)
        self.assertEqual(plan["cycles_per_bucket_per_page_size"], 30)
        self.assertEqual(plan["cycles_per_page_size"], {"4096": 150, "16384": 150})
        self.assertEqual(
            plan["buckets"],
            [
                "C1_COLD_BOOT",
                "C2_WARM_RECONNECT",
                "C3_BG_KEEPALIVE",
                "C4_FAULT_CONTENTION",
                "C5_DUAL_AND_RIDEALONG",
            ],
        )
        entries = plan["entries"]
        self.assertEqual(len(entries), 300)

        counts_4k: dict[str, int] = {}
        counts_16k: dict[str, int] = {}
        for entry in entries:
            ps = entry["host_page_size"]
            bucket = entry["bucket"]
            if ps == 4096:
                self.assertEqual(entry["device_alias"], "device-4k")
                counts_4k[bucket] = counts_4k.get(bucket, 0) + 1
            elif ps == 16384:
                self.assertEqual(entry["device_alias"], "device-16k")
                counts_16k[bucket] = counts_16k.get(bucket, 0) + 1
            else:
                self.fail(f"Unexpected page size: {ps}")

        for bucket in plan["buckets"]:
            self.assertEqual(counts_4k.get(bucket), 30)
            self.assertEqual(counts_16k.get(bucket), 30)


class TestHostPageAndCompatChecks(unittest.TestCase):
    def test_validate_page_size_rejects_unknown_values(self) -> None:
        self.assertEqual(run_uml_matrix.validate_host_page_size("4096\n"), 4096)
        self.assertEqual(run_uml_matrix.validate_host_page_size("16384\r\n"), 16384)
        with self.assertRaises(ValueError):
            run_uml_matrix.validate_host_page_size("8192")
        with self.assertRaises(ValueError):
            run_uml_matrix.validate_host_page_size("not-an-int")

    def test_compat_16kb_mode_is_fail_closed(self) -> None:
        clean_dumpsys = """
        Package [io.github.nyzeep.orchestella.qa.w304] (dead01):
          versionName=0.6.3-qa
          versionCode=603 minSdk=29 targetSdk=35
          usesPageSizeCompat=false
        """
        self.assertEqual(run_uml_matrix.detect_compat_16kb_indicators(clean_dumpsys, {}), [])
        meta = run_uml_matrix.parse_dumpsys_package_meta(clean_dumpsys)
        self.assertEqual(meta["version_name"], "0.6.3-qa")
        self.assertEqual(meta["version_code"], 603)
        self.assertEqual(meta["compat_16kb_indicators"], [])

        compat_dumpsys = """
        Package [io.github.nyzeep.orchestella.qa.w304] (dead02):
          versionName=0.6.3-qa
          versionCode=603 minSdk=29 targetSdk=35
          pageSizeCompat=true
        """
        indicators = run_uml_matrix.detect_compat_16kb_indicators(compat_dumpsys, {})
        self.assertTrue(len(indicators) >= 1)

        prop_indicators = run_uml_matrix.detect_compat_16kb_indicators(
            clean_dumpsys, {"debug.bionic.page_size_compat": "1"}
        )
        self.assertTrue(len(prop_indicators) >= 1)


class TestInstrumentationAndDeviceHygiene(unittest.TestCase):
    def test_parse_instrumentation_and_fgs_and_residuals(self) -> None:
        raw_out = (
            "INSTRUMENTATION_STATUS: w304_stage=c3-start\n"
            "INSTRUMENTATION_STATUS: w304_bucket=C3_BG_KEEPALIVE\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            "INSTRUMENTATION_STATUS: w304_stage=bg_hold_ready\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            "INSTRUMENTATION_RESULT: w304_result=PASS\n"
            "INSTRUMENTATION_RESULT: w304_bucket=C3_BG_KEEPALIVE\n"
            "INSTRUMENTATION_RESULT: w304_cycle=1\n"
            "INSTRUMENTATION_RESULT: w304_summary=bg_keepalive_ok\n"
            "INSTRUMENTATION_CODE: -1\n"
        )
        parsed = run_uml_matrix.parse_instrumentation_output(raw_out)
        self.assertEqual(parsed["code"], -1)
        self.assertEqual(parsed["status_stages"], ["c3-start", "bg_hold_ready"])
        self.assertEqual(parsed["result"]["w304_result"], "PASS")
        self.assertEqual(parsed["result"]["w304_summary"], "bg_keepalive_ok")

        fgs_dump = (
            "  * ServiceRecord{abc123 u0 io.github.nyzeep.orchestella.qa.w304/"
            "io.github.nyzeep.orchestella.OrchestellaForegroundService}\n"
            "    isForeground=true foregroundId=1001\n"
        )
        self.assertTrue(
            run_uml_matrix.parse_fgs_active_for_package(
                fgs_dump, "io.github.nyzeep.orchestella.qa.w304"
            )
        )
        self.assertFalse(
            run_uml_matrix.parse_fgs_active_for_package(
                "  (nothing)\n", "io.github.nyzeep.orchestella.qa.w304"
            )
        )

        clean_ps = (
            "  PID  PPID NAME ARGS\n"
            " 1234     1 zygote64 zygote64\n"
            " 5678  1234 io.github.nyzeep.orche io.github.nyzeep.orchestella.qa.w304\n"
        )
        self.assertEqual(run_uml_matrix.find_residual_uml_processes(clean_ps), [])

        dirty_ps = (
            clean_ps
            + " 9901  5678 libuml_kernel.so /data/app/.../libuml_kernel.so ubd0=...\n"
            + " 9902     1 libuml_passt.so /data/app/.../libuml_passt.so -s ...\n"
        )
        residuals = run_uml_matrix.find_residual_uml_processes(dirty_ps)
        self.assertEqual(len(residuals), 2)
        self.assertEqual({r["token"] for r in residuals}, {"libuml_kernel.so", "libuml_passt.so"})

    def test_stream_command_lines_enforces_watchdog_on_silent_hang(self) -> None:
        import time

        started = time.monotonic()
        with self.assertRaises(TimeoutError):
            run_uml_matrix._stream_command_lines(
                [sys.executable, "-c", "import time; time.sleep(30)"],
                timeout_s=0.35,
            )
        elapsed = time.monotonic() - started
        self.assertLess(elapsed, 3.0)


class TestApk16KbVerifier(unittest.TestCase):
    @staticmethod
    def _write_aligned_zip(path: Path, so_name: str, align_to: int, compress_type: int) -> None:
        payload = b"\x7fELF" + b"\x00" * 60
        name_bytes = so_name.encode("utf-8")
        if compress_type == zipfile.ZIP_STORED and align_to > 0:
            # Compute padding needed in local extra field so payload data starts on align_to boundary
            base_header_len = 30 + len(name_bytes) + 4  # 4-byte dummy extra header
            rem = base_header_len % align_to
            pad_len = (align_to - rem) % align_to
            extra = struct.pack("<HH", 0xFFFF, pad_len) + (b"\x00" * pad_len)
        else:
            extra = b""

        zi = zipfile.ZipInfo(so_name)
        zi.compress_type = compress_type
        zi.extra = extra
        with zipfile.ZipFile(path, "w") as zf:
            zf.writestr(zi, payload)

    def test_verify_apk_zip_16k_alignment_and_failure_detection(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            good_apk = Path(tmp) / "aligned.apk"
            self._write_aligned_zip(
                good_apk,
                "lib/arm64-v8a/libuml_kernel.so",
                align_to=16384,
                compress_type=zipfile.ZIP_STORED,
            )
            res_ok_without_tools = run_uml_matrix.verify_apk_16kb(
                good_apk, require_external_tools=False
            )
            self.assertTrue(res_ok_without_tools["passed"])
            self.assertEqual(res_ok_without_tools["packaging_mode"], "uncompressed_stored")
            self.assertEqual(res_ok_without_tools["stored_so_count"], 1)
            self.assertEqual(res_ok_without_tools["deflated_so_count"], 0)
            self.assertTrue(res_ok_without_tools["all_stored_zip_offset_16k_aligned"])
            self.assertEqual(res_ok_without_tools["so_count"], 1)

            # Default fail-closed mode must reject when readelf/zipalign were omitted.
            res_missing_tools = run_uml_matrix.verify_apk_16kb(good_apk)
            self.assertFalse(res_missing_tools["passed"])

            bad_offset_apk = Path(tmp) / "misaligned.apk"
            self._write_aligned_zip(
                bad_offset_apk,
                "lib/arm64-v8a/libuml_kernel.so",
                align_to=0,
                compress_type=zipfile.ZIP_STORED,
            )
            res_bad_offset = run_uml_matrix.verify_apk_16kb(
                bad_offset_apk, require_external_tools=False
            )
            self.assertFalse(res_bad_offset["passed"])
            self.assertFalse(res_bad_offset["all_stored_zip_offset_16k_aligned"])

            compressed_apk = Path(tmp) / "compressed.apk"
            self._write_aligned_zip(
                compressed_apk,
                "lib/arm64-v8a/libuml_kernel.so",
                align_to=0,
                compress_type=zipfile.ZIP_DEFLATED,
            )
            res_compressed = run_uml_matrix.verify_apk_16kb(
                compressed_apk, require_external_tools=False
            )
            self.assertTrue(res_compressed["passed"])
            self.assertEqual(res_compressed["packaging_mode"], "extracted_deflated")
            self.assertEqual(res_compressed["deflated_so_count"], 1)
            self.assertIsNone(res_compressed["entries"][0]["zip_offset_16k_aligned"])


class TestIncrementalLedgerAndSummary(unittest.TestCase):
    def test_incremental_ledger_preserves_failures_and_missing_16k_blocks_pass(self) -> None:
        plan = run_uml_matrix.build_frozen_sample_plan(cycles_per_bucket=2)
        self.assertEqual(plan["total_cycles"], 20)

        with tempfile.TemporaryDirectory() as tmp:
            ledger_path = Path(tmp) / "ledger.jsonl"

            run1 = run_uml_matrix.next_run_id(run_uml_matrix.load_ledger(ledger_path))
            self.assertEqual(run1, "run-0001")

            # Record a failure in run-0001 for the first 4096 sample, pass the rest of 4096
            run1_rows = []
            for entry in plan["entries"]:
                if entry["host_page_size"] != 4096:
                    continue
                is_first = entry["bucket"] == "C1_COLD_BOOT" and entry["cycle_index"] == 1
                run1_rows.append(
                    {
                        "run_id": run1,
                        "sample_id": entry["sample_id"],
                        "timestamp_utc": "2026-11-24T01:00:00Z",
                        "host_page_size": 4096,
                        "device_alias": "device-4k",
                        "bucket": entry["bucket"],
                        "cycle_index": entry["cycle_index"],
                        "status": "FAIL" if is_first else "PASS",
                        "failure_code": "SYNTHETIC_FAIL" if is_first else None,
                        "stage": "c1-start" if is_first else "done",
                        "duration_ms": 500,
                        "fgs_verified_in_bg": True,
                        "residual_process_count": 0,
                        "bad_disk_count": 0,
                        "summary": "fail" if is_first else "ok",
                    }
                )
            run_uml_matrix.append_ledger_records(ledger_path, run1_rows)

            # Second run appends run-0002 passing the previously failed sample without erasing run-0001 FAIL
            existing = run_uml_matrix.load_ledger(ledger_path)
            run2 = run_uml_matrix.next_run_id(existing)
            self.assertEqual(run2, "run-0002")
            run_uml_matrix.append_ledger_records(
                ledger_path,
                [
                    {
                        "run_id": run2,
                        "sample_id": "p4096-C1_COLD_BOOT-001",
                        "timestamp_utc": "2026-11-24T01:05:00Z",
                        "host_page_size": 4096,
                        "device_alias": "device-4k",
                        "bucket": "C1_COLD_BOOT",
                        "cycle_index": 1,
                        "status": "PASS",
                        "failure_code": None,
                        "stage": "done",
                        "duration_ms": 480,
                        "fgs_verified_in_bg": True,
                        "residual_process_count": 0,
                        "bad_disk_count": 0,
                        "summary": "ok",
                    }
                ],
            )

            # Record missing 16384 samples
            missing_rows = run_uml_matrix.record_missing_samples(
                plan=plan,
                ledger_path=ledger_path,
                page_size=16384,
                reason="no-physical-16k-device-connected",
                timestamp_utc="2026-11-24T01:06:00Z",
            )
            self.assertEqual(len(missing_rows), 10)
            self.assertEqual(missing_rows[0]["run_id"], "run-0003")

            all_rows = run_uml_matrix.load_ledger(ledger_path)
            # Original FAIL row from run-0001 must still be intact!
            self.assertEqual(len(all_rows), 21)
            fail_rows = [r for r in all_rows if r["status"] == "FAIL"]
            self.assertEqual(len(fail_rows), 1)
            self.assertEqual(fail_rows[0]["run_id"], "run-0001")

            summary = run_uml_matrix.summarize_ledger(
                plan=plan,
                records=all_rows,
                apk_verification={"passed": True},
                qemu_regression={"passed": True},
                compat_blocked=False,
            )
            self.assertEqual(summary["verdict"], "BLOCKED_PENDING_16K_DEVICE")
            self.assertFalse(summary["signoff_allowed"])
            self.assertEqual(run_uml_matrix.exit_code_for_summary(summary, allow_pending_16k=False), 3)
            self.assertEqual(run_uml_matrix.exit_code_for_summary(summary, allow_pending_16k=True), 0)
            self.assertEqual(summary["historical_failures_preserved"], 1)
            self.assertEqual(summary["by_page_size"]["4096"]["latest_passed"], 10)
            self.assertEqual(summary["by_page_size"]["4096"]["latest_failed"], 0)
            self.assertEqual(summary["by_page_size"]["16384"]["latest_missing"], 10)
            # Summary JSON must not contain any sensitive patterns
            summary_json = json.dumps(summary)
            self.assertNotIn("serial", summary_json.lower())

    def test_missing_gates_or_emulator_never_allow_signoff(self) -> None:
        plan = run_uml_matrix.build_frozen_sample_plan(cycles_per_bucket=1)
        all_pass_rows = [
            {
                "run_id": "run-0001",
                "sample_id": entry["sample_id"],
                "host_page_size": entry["host_page_size"],
                "bucket": entry["bucket"],
                "cycle_index": entry["cycle_index"],
                "status": "PASS",
                "residual_process_count": 0,
                "bad_disk_count": 0,
            }
            for entry in plan["entries"]
        ]
        # Missing apk_verification must fail closed (INCOMPLETE, signoff_allowed=False).
        no_apk = run_uml_matrix.summarize_ledger(
            plan, all_pass_rows, apk_verification=None, qemu_regression={"passed": True}
        )
        self.assertEqual(no_apk["verdict"], "INCOMPLETE")
        self.assertFalse(no_apk["signoff_allowed"])

        # Missing qemu_regression must fail closed.
        no_qemu = run_uml_matrix.summarize_ledger(
            plan, all_pass_rows, apk_verification={"passed": True}, qemu_regression=None
        )
        self.assertEqual(no_qemu["verdict"], "INCOMPLETE")
        self.assertFalse(no_qemu["signoff_allowed"])

        # Emulator run never grants final physical signoff.
        emu = run_uml_matrix.summarize_ledger(
            plan,
            all_pass_rows,
            apk_verification={"passed": True},
            qemu_regression={"passed": True},
            is_emulator=True,
        )
        self.assertEqual(emu["verdict"], "EMULATOR_PREFLIGHT_ONLY")
        self.assertFalse(emu["signoff_allowed"])

        # Physical device with both gates and 10/10 passes grants PASS.
        phys = run_uml_matrix.summarize_ledger(
            plan,
            all_pass_rows,
            apk_verification={"passed": True},
            qemu_regression={"passed": True},
            is_emulator=False,
        )
        self.assertEqual(phys["verdict"], "PASS")
        self.assertTrue(phys["signoff_allowed"])


if __name__ == "__main__":
    unittest.main()
