#!/usr/bin/env python3
"""Unit tests for tools/device-validation/run_w0105_full_validation.py (TDD)."""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

sys.path.insert(0, os.path.dirname(__file__))

import run_w0105_full_validation as w0105  # noqa: E402
import run_w305_perf_bench as bench  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "tools" / "ci"))
import check_sensitive_data  # noqa: E402


def _make_debian_bench_rows(
    *,
    bucket: str,
    count: int,
    shell_ms: int | list[int],
    host_page_size: int = 4096,
    run_id: str = "run-0001",
    temp_before: float = 28.0,
    temp_after: float = 29.0,
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for idx in range(1, count + 1):
        val = shell_ms[idx - 1] if isinstance(shell_ms, list) else shell_ms
        rows.append(
            {
                "record_type": "perf_sample",
                "run_id": run_id,
                "sample_id": f"p{host_page_size}-debian-{bucket}-{idx:03d}",
                "timestamp_utc": "2026-11-27T14:00:00Z",
                "host_page_size": host_page_size,
                "device_alias": w0105.PAGE_SIZE_ALIASES[host_page_size],
                "suite": "debian_full",
                "bucket": bucket,
                "engine": "uml",
                "profile": "full-smp-arm64",
                "distro": "debian",
                "distro_version": "13",
                "vcpu": 1,
                "ram_mb": 1024,
                "network_mode": "offline",
                "iteration": idx,
                "status": "PASS",
                "failure_reason": None,
                "timeout_explained": False,
                "t0_to_bridge_ready_ms": val - 300,
                "t0_to_systemd_ready_ms": val - 10,
                "t0_to_shell_ready_ms": val,
                "t0_to_first_cmd_echo_ms": val + 45,
                "t0_to_docker_daemon_ready_ms": val + 900,
                "t0_to_container_http_ready_ms": val + 1600,
                "residual_process_count": 0,
                "temp_c_before": temp_before,
                "temp_c_after": temp_after,
                "host_duration_ms": val + 1800,
            }
        )
    return rows


def _make_paired_alpine_rows(
    *,
    bucket: str,
    count: int,
    fast_ms: int | list[int] = 2200,
    full_ms: int | list[int] = 2600,
    host_page_size: int = 4096,
    run_id: str = "run-0001",
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for pair_idx in range(1, count + 1):
        f_val = fast_ms[pair_idx - 1] if isinstance(fast_ms, list) else fast_ms
        u_val = full_ms[pair_idx - 1] if isinstance(full_ms, list) else full_ms
        order_tuple = w0105.paired_profile_order(pair_idx)
        for profile_label in order_tuple:
            ms = f_val if profile_label == "fast" else u_val
            rows.append(
                {
                    "record_type": "paired_sample",
                    "run_id": run_id,
                    "sample_id": f"p{host_page_size}-alpine-{bucket}-pair{pair_idx:03d}-{profile_label}",
                    "timestamp_utc": "2026-11-27T14:10:00Z",
                    "host_page_size": host_page_size,
                    "device_alias": w0105.PAGE_SIZE_ALIASES[host_page_size],
                    "suite": "alpine_paired_fast_vs_full",
                    "bucket": bucket,
                    "pair_index": pair_idx,
                    "variant": profile_label,
                    "engine": "uml",
                    "profile": "fast-up-arm64" if profile_label == "fast" else "full-smp-arm64",
                    "distro": "alpine",
                    "distro_version": "3.22.2",
                    "vcpu": 1,
                    "ram_mb": 512,
                    "network_mode": "offline",
                    "status": "PASS",
                    "failure_reason": None,
                    "timeout_explained": False,
                    "t0_to_bridge_ready_ms": ms - 120,
                    "t0_to_shell_ready_ms": ms,
                    "t0_to_first_cmd_echo_ms": ms + 25,
                    "residual_process_count": 0,
                    "temp_c_before": 27.5,
                    "temp_c_after": 28.0,
                    "host_duration_ms": ms + 300,
                }
            )
    return rows


def _passing_functional_rows(host_page_size: int = 4096) -> list[dict[str, Any]]:
    return [
        {
            "record_type": "functional_check",
            "run_id": "run-0001",
            "sample_id": f"p{host_page_size}-func-{name}",
            "check_name": name,
            "host_page_size": host_page_size,
            "device_alias": w0105.PAGE_SIZE_ALIASES[host_page_size],
            "status": "PASS",
            "attempts": 1,
            "residual_process_count": 0,
            "timestamp_utc": "2026-11-27T13:50:00Z",
        }
        for name in w0105.REQUIRED_FUNCTIONAL_CHECKS
    ]


class TestRerunPreservesHistory(unittest.TestCase):
    def test_rerun_does_not_erase_prior_fail_or_timeout_from_ledger_or_stats(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            ledger = Path(tmp) / "ledger.jsonl"
            sid = "p4096-debian-cold_provisioned-001"
            fail_row = _make_debian_bench_rows(bucket="cold_provisioned", count=1, shell_ms=4200)[0]
            fail_row["sample_id"] = sid
            fail_row["status"] = "FAIL"
            fail_row["failure_reason"] = "transient_instrument_crash"
            fail_row["t0_to_shell_ready_ms"] = None

            timeout_row = dict(fail_row)
            timeout_row["run_id"] = "run-0002"
            timeout_row["status"] = "TIMEOUT"
            timeout_row["failure_reason"] = "boot_timeout"

            pass_row = _make_debian_bench_rows(bucket="cold_provisioned", count=1, shell_ms=4350, run_id="run-0003")[0]
            pass_row["sample_id"] = sid

            w0105.append_ledger_records(ledger, [fail_row, timeout_row])
            w0105.append_ledger_records(ledger, [pass_row])

            loaded = w0105.load_ledger(ledger)
            self.assertEqual(len(loaded), 3)

            stats = w0105.compute_debian_bucket_stats(loaded, "cold_provisioned", host_page_size=4096)
            self.assertEqual(stats["valid_pass_count"], 1)
            self.assertEqual(stats["total_attempts"], 3)
            self.assertEqual(stats["historical_fail_count"], 1)
            self.assertEqual(stats["historical_timeout_count"], 1)
            self.assertAlmostEqual(stats["failure_rate"], 2.0 / 3.0, places=4)
            self.assertEqual(stats["windows"]["shell_ready"]["p50_ms"], 4350)


class TestMissing16kbNeverReportedAs4kbFailure(unittest.TestCase):
    def test_missing_16k_yields_pass_4k_only_pending_16k_and_not_4k_failure(self) -> None:
        records = (
            _passing_functional_rows(4096)
            + _make_debian_bench_rows(bucket="first_provision", count=2, shell_ms=19000)
            + _make_debian_bench_rows(bucket="cold_provisioned", count=30, shell_ms=4200)
            + _make_debian_bench_rows(bucket="warm_restart", count=30, shell_ms=4100)
            + _make_debian_bench_rows(bucket="warm_reconnect", count=30, shell_ms=35)
            + _make_paired_alpine_rows(bucket="cold_provisioned", count=30)
            + _make_paired_alpine_rows(bucket="warm_restart", count=30)
            + _make_paired_alpine_rows(bucket="warm_reconnect", count=30)
        )
        apk_ok = {"passed": True, "so_count": 17, "zipalign_p16_ok": True, "all_pt_load_16k_aligned": True}
        summary = w0105.evaluate_w0105_gate(
            records,
            apk_verification=apk_ok,
            payload_gate_ok=True,
        )
        self.assertEqual(summary["by_page_size"]["4096"]["verdict"], "PASS")
        self.assertEqual(summary["by_page_size"]["16384"]["verdict"], "MISSING")
        self.assertEqual(summary["by_page_size"]["16384"]["reason"], "no-physical-16k-device-connected")
        self.assertEqual(summary["overall_verdict"], "PASS_4K_ONLY_PENDING_16K")
        self.assertFalse(summary["signoff_allowed"])
        self.assertEqual(summary["wave_receipt"]["counts"]["FAIL"], 0)
        self.assertGreater(summary["wave_receipt"]["counts"]["MISSING"], 0)


class TestEquivalentWorkloadGuard(unittest.TestCase):
    def test_refuses_speedup_calculation_for_non_equivalent_workloads(self) -> None:
        alpine_fast = _make_paired_alpine_rows(bucket="cold_provisioned", count=30, fast_ms=2200)
        alpine_fast = [r for r in alpine_fast if r["variant"] == "fast"]
        debian_full = _make_debian_bench_rows(bucket="cold_provisioned", count=30, shell_ms=4500)
        with self.assertRaises(ValueError):
            w0105.compute_equivalent_comparison(
                baseline_rows=debian_full,
                candidate_rows=alpine_fast,
                metric_key="t0_to_shell_ready_ms",
            )

    def test_computes_paired_speedup_for_equivalent_alpine_fast_vs_full(self) -> None:
        rows = _make_paired_alpine_rows(bucket="cold_provisioned", count=30, fast_ms=2200, full_ms=2500)
        comp = w0105.compute_paired_variant_stats(rows, "cold_provisioned", host_page_size=4096)
        self.assertTrue(comp["equivalent_workload"])
        self.assertEqual(comp["paired_valid_count"], 30)
        self.assertEqual(comp["variants"]["fast"]["windows"]["shell_ready"]["p95_ms"], 2200)
        self.assertEqual(comp["variants"]["full"]["windows"]["shell_ready"]["p95_ms"], 2500)


class TestInterleavingAndClockWindows(unittest.TestCase):
    def test_cold_warm_block_schedule_and_paired_order(self) -> None:
        blocks = w0105.build_cold_warm_block_schedule(10, block_size=5)
        self.assertEqual(
            blocks,
            [
                ("cold_provisioned", [1, 2, 3, 4, 5]),
                ("warm_restart_batch", [1, 2, 3, 4, 5]),
                ("warm_restart_batch", [6, 7, 8, 9, 10]),
                ("cold_provisioned", [6, 7, 8, 9, 10]),
            ],
        )
        self.assertEqual(w0105.paired_profile_order(1), ("fast", "full"))
        self.assertEqual(w0105.paired_profile_order(2), ("full", "fast"))

    def test_multi_window_percentiles_and_nearest_rank(self) -> None:
        vals = list(range(1000, 1030))
        rows = _make_debian_bench_rows(bucket="cold_provisioned", count=30, shell_ms=vals)
        stats = w0105.compute_debian_bucket_stats(rows, "cold_provisioned", host_page_size=4096)
        self.assertEqual(stats["windows"]["shell_ready"]["min_ms"], 1000)
        self.assertEqual(stats["windows"]["shell_ready"]["p50_ms"], 1014)
        self.assertEqual(stats["windows"]["shell_ready"]["p95_ms"], 1028)
        self.assertEqual(stats["windows"]["shell_ready"]["max_ms"], 1029)
        self.assertEqual(stats["windows"]["first_cmd_echo"]["p50_ms"], 1059)
        self.assertEqual(stats["windows"]["docker_daemon_ready"]["p95_ms"], 1928)
        self.assertEqual(stats["windows"]["container_http_ready"]["p95_ms"], 2628)


class TestWaveReceiptAndResidualTokens(unittest.TestCase):
    def test_residual_process_tokens_include_full_profile_binaries(self) -> None:
        ps_out = "u0_a123 4512 1 1024 512 S /data/app/lib/arm64/libuml_kernel_full.so"
        found = w0105.find_residual_uml_processes(ps_out)
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0]["token"], "libuml_kernel_full.so")

    def test_wave_receipt_distinguishes_pass_fail_missing_skipped_and_sanitizes(self) -> None:
        rows = _passing_functional_rows(4096)
        rows[0]["status"] = "FAIL"
        rows[0]["failure_reason"] = "error at " + "C" + ":\\Users\\dev\\secret\\log.txt"
        rows.append(
            {
                "record_type": "functional_check",
                "run_id": "run-0001",
                "sample_id": "p4096-func-qemu_debian_ref",
                "check_name": "qemu_debian_ref",
                "host_page_size": 4096,
                "device_alias": "device-4k",
                "status": "SKIPPED",
                "failure_reason": "optional_ref_skipped",
                "residual_process_count": 0,
            }
        )
        summary = w0105.evaluate_w0105_gate(
            rows,
            apk_verification={"passed": True},
            payload_gate_ok=True,
        )
        self.assertEqual(summary["by_page_size"]["4096"]["verdict"], "FAIL")
        self.assertEqual(summary["overall_verdict"], "FAIL")
        self.assertEqual(summary["wave_receipt"]["counts"]["FAIL"], 1)
        self.assertEqual(summary["wave_receipt"]["counts"]["SKIPPED"], 1)
        self.assertEqual(summary["wave_receipt"]["counts"]["MISSING"], 1)

        with tempfile.TemporaryDirectory() as tmp:
            out_file = Path(tmp) / "summary.json"
            out_file.write_text(json.dumps(summary, indent=2), encoding="utf-8")
            findings = check_sensitive_data.scan_text_lines(out_file.read_text(encoding="utf-8").splitlines())
            self.assertEqual(findings, {})

    def test_missing_unrun_functional_checks_yield_incomplete_not_fail(self) -> None:
        partial_rows = _passing_functional_rows(4096)[:2]
        summary = w0105.evaluate_w0105_gate(
            partial_rows,
            apk_verification={"passed": True},
            payload_gate_ok=True,
        )
        self.assertEqual(summary["by_page_size"]["4096"]["verdict"], "INCOMPLETE")
        self.assertEqual(summary["overall_verdict"], "INCOMPLETE")
        self.assertEqual(summary["wave_receipt"]["counts"]["FAIL"], 0)

    def test_prepare_with_single_retry_retries_once_and_fails_fast(self) -> None:
        calls: list[int] = []

        def flaky(attempt: int) -> dict[str, Any]:
            calls.append(attempt)
            return {"status": "FAIL" if attempt == 0 else "PASS"}

        self.assertTrue(w0105.prepare_with_single_retry(flaky))
        self.assertEqual(calls, [0, 1])

        calls_fail: list[int] = []

        def always_fail(attempt: int) -> dict[str, Any]:
            calls_fail.append(attempt)
            return {"status": "FAIL"}

        self.assertFalse(w0105.prepare_with_single_retry(always_fail))
        self.assertEqual(calls_fail, [0, 1])

    def test_optional_check_fail_forces_gate_fail_and_rerun_preserves_fail_attempts(self) -> None:
        rows = _passing_functional_rows(4096) + [
            {
                "record_type": "functional_check",
                "run_id": "run-0001",
                "sample_id": "p4096-func-debian_bench_prepare",
                "check_name": "debian_bench_prepare",
                "host_page_size": 4096,
                "device_alias": "device-4k",
                "status": "FAIL",
                "attempts": 1,
                "failure_reason": "Process crashed.",
                "residual_process_count": 0,
            }
        ]
        s_fail = w0105.evaluate_w0105_gate(rows, apk_verification={"passed": True}, payload_gate_ok=True)
        self.assertEqual(s_fail["by_page_size"]["4096"]["verdict"], "FAIL")
        self.assertEqual(s_fail["wave_receipt"]["counts"]["FAIL"], 1)

        rows.append(
            {
                "record_type": "functional_check",
                "run_id": "run-0002",
                "sample_id": "p4096-func-debian_bench_prepare",
                "check_name": "debian_bench_prepare",
                "host_page_size": 4096,
                "device_alias": "device-4k",
                "status": "PASS",
                "attempts": 1,
                "residual_process_count": 0,
            }
        )
        s_rerun = w0105.evaluate_w0105_gate(rows, apk_verification={"passed": True}, payload_gate_ok=True)
        prep_meta = s_rerun["by_page_size"]["4096"]["functional_checks"]["debian_bench_prepare"]
        self.assertEqual(prep_meta["attempts"], 2)
        self.assertEqual(prep_meta["fail_attempts"], 1)
        self.assertTrue(prep_meta["reran_after_fail"])
        self.assertEqual(s_rerun["wave_receipt"]["historical_fail_attempts"], 1)

    def test_residual_process_gating_unresolved_vs_superseded_and_paired_stats(self) -> None:
        base_records = (
            _passing_functional_rows(4096)
            + _make_debian_bench_rows(bucket="first_provision", count=2, shell_ms=19000)
            + _make_debian_bench_rows(bucket="cold_provisioned", count=30, shell_ms=4200)
            + _make_debian_bench_rows(bucket="warm_restart", count=30, shell_ms=4100)
            + _make_debian_bench_rows(bucket="warm_reconnect", count=30, shell_ms=35)
            + _make_paired_alpine_rows(bucket="cold_provisioned", count=30)
            + _make_paired_alpine_rows(bucket="warm_restart", count=30)
            + _make_paired_alpine_rows(bucket="warm_reconnect", count=30)
        )
        apk_ok = {"passed": True}

        # Case 1: PASS row leaking residual process -> FAIL even without other errors
        leaked_pass = [dict(r) for r in base_records]
        leaked_pass[0]["residual_process_count"] = 1
        s_leak = w0105.evaluate_w0105_gate(leaked_pass, apk_verification=apk_ok, payload_gate_ok=True)
        self.assertEqual(s_leak["by_page_size"]["4096"]["verdict"], "FAIL")
        self.assertEqual(s_leak["by_page_size"]["4096"]["unresolved_residual_process_events"], 1)

        # Case 2: Un-superseded FAIL row with residual=2 -> FAIL
        unsup = list(base_records)
        unsup.append(
            {
                "record_type": "perf_sample",
                "run_id": "run-0002",
                "sample_id": "p4096-debian-cold_provisioned-031",
                "host_page_size": 4096,
                "device_alias": "device-4k",
                "suite": "debian_full",
                "bucket": "cold_provisioned",
                "iteration": 31,
                "status": "FAIL",
                "residual_process_count": 2,
            }
        )
        s_unsup = w0105.evaluate_w0105_gate(unsup, apk_verification=apk_ok, payload_gate_ok=True)
        self.assertEqual(s_unsup["by_page_size"]["4096"]["verdict"], "FAIL")

        # Case 3: Interrupted FAIL with residual=2 later cleanly superseded by PASS (residual=0)
        interrupted = {
            "record_type": "perf_sample",
            "run_id": "run-0001",
            "sample_id": "p4096-debian-cold_provisioned-028",
            "host_page_size": 4096,
            "device_alias": "device-4k",
            "suite": "debian_full",
            "bucket": "cold_provisioned",
            "iteration": 28,
            "status": "FAIL",
            "failure_reason": "instrument_rc=0_code=None",
            "residual_process_count": 2,
        }
        remediated = [interrupted] + base_records
        s_ok = w0105.evaluate_w0105_gate(remediated, apk_verification=apk_ok, payload_gate_ok=True)
        self.assertEqual(s_ok["by_page_size"]["4096"]["verdict"], "PASS")
        self.assertEqual(s_ok["by_page_size"]["4096"]["unresolved_residual_process_events"], 0)
        self.assertEqual(s_ok["by_page_size"]["4096"]["historical_residual_process_events"], 1)

        # Verify paired stats include failure_rate & thermal fields
        paired_rows = _make_paired_alpine_rows(bucket="cold_provisioned", count=2)
        paired_fail = dict(paired_rows[0])
        paired_fail["status"] = "FAIL"
        paired_fail["failure_reason"] = "transient_fail"
        p_stats = w0105.compute_paired_variant_stats([paired_fail] + paired_rows, "cold_provisioned", host_page_size=4096)
        self.assertGreater(p_stats["variants"]["fast"]["failure_rate"], 0.0)
        self.assertEqual(p_stats["variants"]["fast"]["thermal"]["min_c"], 27.5)
        self.assertEqual(p_stats["variants"]["fast"]["failure_reasons"], {"transient_fail": 1})


if __name__ == "__main__":
    unittest.main()
