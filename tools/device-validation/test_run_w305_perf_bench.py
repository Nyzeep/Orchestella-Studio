#!/usr/bin/env python3
"""Unit tests for tools/device-validation/run_w305_perf_bench.py."""

from __future__ import annotations

import contextlib
import io
import json
import os
import struct
import sys
import tempfile
import time
import unittest
import zipfile
from pathlib import Path
from typing import Any

sys.path.insert(0, os.path.dirname(__file__))

import run_w305_perf_bench as bench  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "tools" / "ci"))
import check_sensitive_data  # noqa: E402


def _make_pair_rows(
    *,
    host_page_size: int,
    bucket: str,
    count: int,
    uml_ms: int | list[int],
    qemu_ms: int | list[int],
    run_id: str = "run-0001",
    temp_before: float = 34.0,
    temp_after: float = 35.2,
) -> list[dict[str, Any]]:
    alias = bench.PAGE_SIZE_ALIASES[host_page_size]
    rows: list[dict[str, Any]] = []
    for pair_idx in range(1, count + 1):
        u_val = uml_ms[pair_idx - 1] if isinstance(uml_ms, list) else uml_ms
        q_val = qemu_ms[pair_idx - 1] if isinstance(qemu_ms, list) else qemu_ms
        order_label = bench.pair_order_label(pair_idx)
        for engine in bench.pair_engine_order(pair_idx):
            ms = u_val if engine == "uml" else q_val
            rows.append(
                {
                    "run_id": run_id,
                    "sample_id": bench.make_sample_id(host_page_size, bucket, pair_idx, engine),
                    "timestamp_utc": "2026-11-24T08:00:00Z",
                    "host_page_size": host_page_size,
                    "device_alias": alias,
                    "bucket": bucket,
                    "pair_index": pair_idx,
                    "engine": engine,
                    "order": order_label,
                    "status": "PASS",
                    "failure_reason": None,
                    "timeout_explained": False,
                    "t0_to_shell_ready_ms": ms,
                    "t0_to_bridge_ready_ms": ms + 250,
                    "residual_process_count": 0,
                    "temp_c_before": temp_before,
                    "temp_c_after": temp_after,
                    "host_duration_ms": ms + 400,
                    "detail": "ok",
                }
            )
    return rows


def _make_passing_page_size_rows(
    host_page_size: int,
    *,
    cold_pairs: int = 30,
    warm_pairs: int = 30,
    cold_uml_ms: int | list[int] = 4500,
    cold_qemu_ms: int | list[int] = 9000,
    warm_uml_ms: int | list[int] = 1400,
    warm_qemu_ms: int | list[int] = 3200,
) -> list[dict[str, Any]]:
    return _make_pair_rows(
        host_page_size=host_page_size,
        bucket="cold_provisioned",
        count=cold_pairs,
        uml_ms=cold_uml_ms,
        qemu_ms=cold_qemu_ms,
    ) + _make_pair_rows(
        host_page_size=host_page_size,
        bucket="warm_restart",
        count=warm_pairs,
        uml_ms=warm_uml_ms,
        qemu_ms=warm_qemu_ms,
    )


class TestPercentileAndSpeedupMath(unittest.TestCase):
    def test_nearest_rank_percentile_exact_values(self) -> None:
        self.assertIsNone(bench.nearest_rank_percentile([], 0.50))
        self.assertEqual(bench.nearest_rank_percentile([777], 0.50), 777)
        self.assertEqual(bench.nearest_rank_percentile([777], 0.95), 777)

        # Unsorted 1..20 -> p50 = ceil(10)-1 = idx 9 (10), p95 = ceil(19)-1 = idx 18 (19)
        vals20 = list(range(20, 0, -1))
        self.assertEqual(bench.nearest_rank_percentile(vals20, 0.50), 10)
        self.assertEqual(bench.nearest_rank_percentile(vals20, 0.95), 19)

        # 1..30 -> p50 = ceil(15)-1 = idx 14 (15), p95 = ceil(28.5)-1 = idx 28 (29)
        vals30 = list(range(1, 31))
        self.assertEqual(bench.nearest_rank_percentile(vals30, 0.50), 15)
        self.assertEqual(bench.nearest_rank_percentile(vals30, 0.95), 29)

        # 1..100 -> p50 = 50, p95 = 95
        vals100 = list(range(1, 101))
        self.assertEqual(bench.nearest_rank_percentile(vals100, 0.50), 50)
        self.assertEqual(bench.nearest_rank_percentile(vals100, 0.95), 95)

        with self.assertRaises(ValueError):
            bench.nearest_rank_percentile([1, 2], 0.0)
        with self.assertRaises(ValueError):
            bench.nearest_rank_percentile([1, 2], 1.1)

    def test_p95_speedup_ratio_formula_and_edge_cases(self) -> None:
        self.assertAlmostEqual(bench.compute_p95_speedup_ratio(8000, 10000), 0.20)
        self.assertAlmostEqual(bench.compute_p95_speedup_ratio(1500, 3000), 0.50)
        self.assertAlmostEqual(bench.compute_p95_speedup_ratio(4000, 4000), 0.0)
        self.assertAlmostEqual(bench.compute_p95_speedup_ratio(5000, 4000), -0.25)
        self.assertIsNone(bench.compute_p95_speedup_ratio(1000, 0))
        self.assertIsNone(bench.compute_p95_speedup_ratio(1000, -5))
        self.assertIsNone(bench.compute_p95_speedup_ratio(None, 5000))
        self.assertIsNone(bench.compute_p95_speedup_ratio(1000, None))

    def test_compute_bucket_stats_metrics_and_histograms(self) -> None:
        uml_vals = [1000 + i * 100 for i in range(30)]  # 1000..3900; p50=2400, p95=3800
        qemu_vals = [4000 + i * 100 for i in range(30)]  # 4000..6900; p50=5400, p95=6800
        rows = _make_pair_rows(
            host_page_size=4096,
            bucket="cold_provisioned",
            count=30,
            uml_ms=uml_vals,
            qemu_ms=qemu_vals,
            temp_before=33.5,
            temp_after=36.0,
        )
        # Add one FAIL on pair 31 uml and one TIMEOUT on pair 31 qemu
        rows.append(
            {
                "run_id": "run-0001",
                "host_page_size": 4096,
                "bucket": "cold_provisioned",
                "pair_index": 31,
                "engine": "uml",
                "status": "FAIL",
                "failure_reason": "bridge_handshake_error",
                "t0_to_shell_ready_ms": None,
                "temp_c_before": 36.1,
                "temp_c_after": 36.5,
            }
        )
        rows.append(
            {
                "run_id": "run-0001",
                "host_page_size": 4096,
                "bucket": "cold_provisioned",
                "pair_index": 31,
                "engine": "qemu",
                "status": "TIMEOUT",
                "failure_reason": "instrument_timeout",
                "timeout_explained": False,
                "t0_to_shell_ready_ms": None,
                "temp_c_before": 36.5,
                "temp_c_after": 37.0,
            }
        )

        stats = bench.compute_bucket_stats(rows, "cold_provisioned", host_page_size=4096)
        self.assertEqual(stats["paired_valid_count"], 30)
        self.assertEqual(stats["paired_uml_faster_count"], 30)
        self.assertEqual(stats["engines"]["uml"]["min_ms"], 1000)
        self.assertEqual(stats["engines"]["uml"]["max_ms"], 3900)
        self.assertEqual(stats["engines"]["uml"]["mean_ms"], 2450.0)
        self.assertEqual(stats["engines"]["uml"]["p50_ms"], 2400)
        self.assertEqual(stats["engines"]["uml"]["p95_ms"], 3800)
        self.assertEqual(stats["engines"]["qemu"]["p50_ms"], 5400)
        self.assertEqual(stats["engines"]["qemu"]["p95_ms"], 6800)
        self.assertAlmostEqual(stats["p95_speedup_ratio"], (6800 - 3800) / 6800)
        self.assertEqual(stats["pass_count"], 60)
        self.assertEqual(stats["fail_count"], 1)
        self.assertEqual(stats["timeout_count"], 1)
        self.assertEqual(stats["unexplained_timeout_count"], 1)
        self.assertAlmostEqual(stats["timeout_rate"], 1 / 62)
        self.assertEqual(
            stats["failure_reasons"],
            {"bridge_handshake_error": 1, "instrument_timeout": 1},
        )
        self.assertEqual(stats["thermal"]["min_c"], 33.5)
        self.assertEqual(stats["thermal"]["max_c"], 37.0)

    def test_paired_stats_exclude_unpaired_valid_sample_when_partner_timed_out(self) -> None:
        rows = _make_pair_rows(
            host_page_size=4096,
            bucket="cold_provisioned",
            count=2,
            uml_ms=[1000, 1200],
            qemu_ms=[5000, 6000],
        )
        # Pair 3 succeeds on UML (9999 ms) while QEMU times out: UML's unpaired 9999 ms
        # must not inflate or bias paired latency statistics.
        rows.append(
            {
                "run_id": "run-0001",
                "host_page_size": 4096,
                "bucket": "cold_provisioned",
                "pair_index": 3,
                "engine": "uml",
                "status": "PASS",
                "t0_to_shell_ready_ms": 9999,
            }
        )
        rows.append(
            {
                "run_id": "run-0001",
                "host_page_size": 4096,
                "bucket": "cold_provisioned",
                "pair_index": 3,
                "engine": "qemu",
                "status": "TIMEOUT",
                "failure_reason": "instrument_timeout",
                "timeout_explained": False,
                "t0_to_shell_ready_ms": None,
            }
        )
        stats = bench.compute_bucket_stats(rows, "cold_provisioned", host_page_size=4096)
        self.assertEqual(stats["paired_valid_count"], 2)
        self.assertEqual(stats["engines"]["uml"]["sample_count"], 2)
        self.assertEqual(stats["engines"]["uml"]["unpaired_valid_count"], 3)
        self.assertEqual(stats["engines"]["uml"]["max_ms"], 1200)
        self.assertEqual(stats["engines"]["uml"]["p95_ms"], 1200)
        self.assertEqual(stats["engines"]["qemu"]["p95_ms"], 6000)


class TestInterleavingAndBatching(unittest.TestCase):
    def test_interleaving_order_odd_uml_first_even_qemu_first(self) -> None:
        for odd in (1, 3, 5, 29):
            self.assertEqual(bench.pair_engine_order(odd), ("uml", "qemu"))
            self.assertEqual(bench.pair_order_label(odd), "uml_first")
        for even in (2, 4, 6, 30):
            self.assertEqual(bench.pair_engine_order(even), ("qemu", "uml"))
            self.assertEqual(bench.pair_order_label(even), "qemu_first")
        with self.assertRaises(ValueError):
            bench.pair_engine_order(0)
        with self.assertRaises(ValueError):
            bench.pair_order_label(-1)

    def test_contiguous_pair_batches(self) -> None:
        self.assertEqual(bench.contiguous_pair_batches([], 5), [])
        self.assertEqual(
            bench.contiguous_pair_batches(list(range(1, 13)), 5),
            [[1, 2, 3, 4, 5], [6, 7, 8, 9, 10], [11, 12]],
        )
        self.assertEqual(
            bench.contiguous_pair_batches([2, 3, 7, 8, 9, 10, 11, 12], 5),
            [[2, 3], [7, 8, 9, 10, 11], [12]],
        )
        with self.assertRaises(ValueError):
            bench.contiguous_pair_batches([1, 2], 0)


class TestW305GateEvaluation(unittest.TestCase):
    def test_negative_missing_all_data_is_incomplete(self) -> None:
        summary = bench.evaluate_w305_gate([])
        self.assertEqual(summary["verdict"], "INCOMPLETE")
        self.assertFalse(summary["signoff_allowed"])
        self.assertEqual(summary["by_page_size"]["4096"]["status"], "MISSING")
        self.assertEqual(summary["by_page_size"]["16384"]["status"], "MISSING")
        self.assertEqual(
            summary["by_page_size"]["16384"]["reason"], "no-physical-16k-device-connected"
        )
        self.assertEqual(bench.exit_code_for_summary(summary), 1)

    def test_negative_fewer_than_30_pairs_is_incomplete(self) -> None:
        rows_29_cold = _make_passing_page_size_rows(4096, cold_pairs=29, warm_pairs=30)
        s1 = bench.evaluate_w305_gate(rows_29_cold)
        self.assertEqual(s1["verdict"], "INCOMPLETE")
        self.assertFalse(s1["signoff_allowed"])

        rows_29_warm = _make_passing_page_size_rows(4096, cold_pairs=30, warm_pairs=29)
        s2 = bench.evaluate_w305_gate(rows_29_warm)
        self.assertEqual(s2["verdict"], "INCOMPLETE")
        self.assertFalse(s2["signoff_allowed"])

    def test_negative_cold_p95_exceeds_10s(self) -> None:
        rows = _make_passing_page_size_rows(4096, cold_uml_ms=10001, cold_qemu_ms=15000)
        summary = bench.evaluate_w305_gate(rows)
        self.assertEqual(summary["verdict"], "FAIL_THRESHOLD")
        self.assertFalse(summary["signoff_allowed"])
        self.assertEqual(
            summary["by_page_size"]["4096"]["gates"]["cold_provisioned"]["verdict"],
            "FAIL_THRESHOLD",
        )

    def test_negative_warm_p95_exceeds_3s(self) -> None:
        rows = _make_passing_page_size_rows(4096, warm_uml_ms=3001, warm_qemu_ms=5000)
        summary = bench.evaluate_w305_gate(rows)
        self.assertEqual(summary["verdict"], "FAIL_THRESHOLD")
        self.assertFalse(summary["signoff_allowed"])
        self.assertEqual(
            summary["by_page_size"]["4096"]["gates"]["warm_restart"]["verdict"],
            "FAIL_THRESHOLD",
        )

    def test_negative_speedup_below_20_percent(self) -> None:
        # Cold speedup = (10000 - 8050) / 10000 = 19.5% < 20%
        rows_cold = _make_passing_page_size_rows(4096, cold_uml_ms=8050, cold_qemu_ms=10000)
        s_cold = bench.evaluate_w305_gate(rows_cold)
        self.assertEqual(s_cold["verdict"], "FAIL_THRESHOLD")
        self.assertFalse(s_cold["signoff_allowed"])

        # Warm speedup = (3000 - 2401) / 3000 = 19.97% < 20%
        rows_warm = _make_passing_page_size_rows(4096, warm_uml_ms=2401, warm_qemu_ms=3000)
        s_warm = bench.evaluate_w305_gate(rows_warm)
        self.assertEqual(s_warm["verdict"], "FAIL_THRESHOLD")
        self.assertFalse(s_warm["signoff_allowed"])

    def test_negative_unexplained_timeout_fails_closed(self) -> None:
        rows = _make_passing_page_size_rows(4096)
        rows.append(
            {
                "run_id": "run-0001",
                "host_page_size": 4096,
                "device_alias": "device-4k",
                "bucket": "warm_restart",
                "pair_index": 30,
                "engine": "uml",
                "status": "TIMEOUT",
                "failure_reason": "instrument_timeout",
                "timeout_explained": False,
                "t0_to_shell_ready_ms": None,
            }
        )
        summary = bench.evaluate_w305_gate(rows)
        self.assertEqual(summary["verdict"], "FAIL_UNEXPLAINED_TIMEOUT")
        self.assertFalse(summary["signoff_allowed"])
        self.assertEqual(summary["historical_timeouts_preserved"], 1)

    def test_positive_4k_only_pending_16k_verdict_and_exit_codes(self) -> None:
        rows_4k = _make_passing_page_size_rows(4096)
        summary = bench.evaluate_w305_gate(rows_4k)
        self.assertEqual(summary["verdict"], "PASS_4K_ONLY_PENDING_16K")
        self.assertFalse(summary["signoff_allowed"])
        self.assertEqual(summary["by_page_size"]["4096"]["verdict"], "PASS")
        self.assertEqual(summary["by_page_size"]["16384"]["status"], "MISSING")
        self.assertEqual(
            summary["by_page_size"]["16384"]["reason"], "no-physical-16k-device-connected"
        )
        self.assertEqual(bench.exit_code_for_summary(summary, allow_pending_16k=False), 3)
        self.assertEqual(bench.exit_code_for_summary(summary, allow_pending_16k=True), 0)

    def test_positive_both_4k_and_16k_pass_exact_boundary(self) -> None:
        # Exact boundary: cold UML p95 = 10000 vs QEMU 12500 (20.0%); warm UML p95 = 3000 vs QEMU 3750 (20.0%)
        rows = _make_passing_page_size_rows(
            4096,
            cold_uml_ms=10000,
            cold_qemu_ms=12500,
            warm_uml_ms=3000,
            warm_qemu_ms=3750,
        ) + _make_passing_page_size_rows(
            16384,
            cold_uml_ms=9500,
            cold_qemu_ms=12500,
            warm_uml_ms=2800,
            warm_qemu_ms=3800,
        )
        summary = bench.evaluate_w305_gate(rows, apk_verification={"passed": True})
        self.assertEqual(summary["verdict"], "PASS")
        self.assertTrue(summary["signoff_allowed"])
        self.assertEqual(bench.exit_code_for_summary(summary), 0)

    def test_negative_emulator_compat_or_apk_failure_never_allows_signoff(self) -> None:
        rows = _make_passing_page_size_rows(4096) + _make_passing_page_size_rows(16384)
        emu = bench.evaluate_w305_gate(rows, is_emulator=True)
        self.assertEqual(emu["verdict"], "EMULATOR_PREFLIGHT_ONLY")
        self.assertFalse(emu["signoff_allowed"])

        compat = bench.evaluate_w305_gate(rows, compat_blocked_page_sizes={16384})
        self.assertEqual(compat["verdict"], "FAIL_THRESHOLD")
        self.assertFalse(compat["signoff_allowed"])

        bad_apk = bench.evaluate_w305_gate(rows, apk_verification={"passed": False})
        self.assertEqual(bad_apk["verdict"], "FAIL_THRESHOLD")
        self.assertFalse(bad_apk["signoff_allowed"])

        # 16K passing while 4K missing is INCOMPLETE (not PASS_4K_ONLY_PENDING_16K)
        only_16k = bench.evaluate_w305_gate(_make_passing_page_size_rows(16384))
        self.assertEqual(only_16k["verdict"], "INCOMPLETE")
        self.assertFalse(only_16k["signoff_allowed"])


class TestReadinessStatesNeverMixed(unittest.TestCase):
    def test_first_provision_and_warm_reconnect_cannot_satisfy_cold_or_warm_gates(self) -> None:
        rows = _make_pair_rows(
            host_page_size=4096,
            bucket="first_provision",
            count=30,
            uml_ms=4000,
            qemu_ms=9000,
        ) + _make_pair_rows(
            host_page_size=4096,
            bucket="warm_reconnect",
            count=30,
            uml_ms=15,
            qemu_ms=50,
        )
        summary = bench.evaluate_w305_gate(rows)
        self.assertEqual(summary["verdict"], "INCOMPLETE")
        self.assertFalse(summary["signoff_allowed"])
        p4k = summary["by_page_size"]["4096"]
        self.assertEqual(p4k["buckets"]["first_provision"]["paired_valid_count"], 30)
        self.assertEqual(p4k["buckets"]["warm_reconnect"]["paired_valid_count"], 30)
        self.assertEqual(p4k["buckets"]["cold_provisioned"]["paired_valid_count"], 0)
        self.assertEqual(p4k["buckets"]["warm_restart"]["paired_valid_count"], 0)

    def test_warm_reconnect_does_not_mask_slow_warm_restart_and_first_provision_does_not_pollute_cold(
        self,
    ) -> None:
        rows = (
            _make_pair_rows(
                host_page_size=4096,
                bucket="first_provision",
                count=5,
                uml_ms=25000,
                qemu_ms=30000,
            )
            + _make_pair_rows(
                host_page_size=4096,
                bucket="cold_provisioned",
                count=30,
                uml_ms=4500,
                qemu_ms=9000,
            )
            + _make_pair_rows(
                host_page_size=4096,
                bucket="warm_restart",
                count=30,
                uml_ms=3200,  # exceeds 3000ms limit!
                qemu_ms=6000,
            )
            + _make_pair_rows(
                host_page_size=4096,
                bucket="warm_reconnect",
                count=30,
                uml_ms=12,  # fast reconnect MUST NOT pull down warm_restart p95!
                qemu_ms=40,
            )
        )
        summary = bench.evaluate_w305_gate(rows)
        p4k = summary["by_page_size"]["4096"]
        # Cold stays 4500 (not polluted by 25000 first_provision) -> cold gate PASS
        self.assertEqual(p4k["gates"]["cold_provisioned"]["uml_p95_ms"], 4500)
        self.assertEqual(p4k["gates"]["cold_provisioned"]["verdict"], "PASS")
        # Warm stays 3200 (not diluted by 12ms warm_reconnect) -> warm gate FAIL_THRESHOLD
        self.assertEqual(p4k["gates"]["warm_restart"]["uml_p95_ms"], 3200)
        self.assertEqual(p4k["gates"]["warm_restart"]["verdict"], "FAIL_THRESHOLD")
        self.assertEqual(summary["verdict"], "FAIL_THRESHOLD")
        self.assertFalse(summary["signoff_allowed"])


class TestAppendOnlyLedgerAndResume(unittest.TestCase):
    def test_append_only_ledger_preserves_prior_timeouts_failures_and_missing_16k(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            ledger_path = Path(tmp) / "ledger.jsonl"
            run1 = bench.next_run_id(bench.load_ledger(ledger_path))
            self.assertEqual(run1, "run-0001")

            # Run 1: 29 passing cold pairs + pair 30 timeout (uml) & fail (qemu) + 30 passing warm pairs
            run1_rows = (
                _make_pair_rows(
                    host_page_size=4096,
                    bucket="cold_provisioned",
                    count=29,
                    uml_ms=4200,
                    qemu_ms=8500,
                    run_id=run1,
                )
                + [
                    {
                        "run_id": run1,
                        "sample_id": bench.make_sample_id(4096, "cold_provisioned", 30, "uml"),
                        "timestamp_utc": "2026-11-24T08:10:00Z",
                        "host_page_size": 4096,
                        "device_alias": "device-4k",
                        "bucket": "cold_provisioned",
                        "pair_index": 30,
                        "engine": "uml",
                        "order": "qemu_first",
                        "status": "TIMEOUT",
                        "failure_reason": "instrument_timeout",
                        "timeout_explained": False,
                        "t0_to_shell_ready_ms": None,
                        "residual_process_count": 0,
                        "temp_c_before": 35.0,
                        "temp_c_after": 36.2,
                    },
                    {
                        "run_id": run1,
                        "sample_id": bench.make_sample_id(4096, "cold_provisioned", 30, "qemu"),
                        "timestamp_utc": "2026-11-24T08:10:10Z",
                        "host_page_size": 4096,
                        "device_alias": "device-4k",
                        "bucket": "cold_provisioned",
                        "pair_index": 30,
                        "engine": "qemu",
                        "order": "qemu_first",
                        "status": "FAIL",
                        "failure_reason": "guest_exit_nonzero",
                        "timeout_explained": False,
                        "t0_to_shell_ready_ms": None,
                        "residual_process_count": 0,
                        "temp_c_before": 36.2,
                        "temp_c_after": 36.5,
                    },
                ]
                + _make_pair_rows(
                    host_page_size=4096,
                    bucket="warm_restart",
                    count=30,
                    uml_ms=1300,
                    qemu_ms=3100,
                    run_id=run1,
                )
            )
            bench.append_ledger_records(ledger_path, run1_rows)

            # Verify passed_sample_keys excludes pair 30 (TIMEOUT/FAIL) while keeping 1..29
            loaded1 = bench.load_ledger(ledger_path)
            passed_keys = bench.passed_sample_keys(loaded1)
            self.assertIn((4096, "cold_provisioned", 29, "uml"), passed_keys)
            self.assertNotIn((4096, "cold_provisioned", 30, "uml"), passed_keys)
            self.assertNotIn((4096, "cold_provisioned", 30, "qemu"), passed_keys)

            # Run 2 (--resume): appends passing pair 30 without erasing run-0001 TIMEOUT/FAIL
            run2 = bench.next_run_id(loaded1)
            self.assertEqual(run2, "run-0002")
            bench.append_ledger_records(
                ledger_path,
                [
                    {
                        "run_id": run2,
                        "sample_id": bench.make_sample_id(4096, "cold_provisioned", 30, "qemu"),
                        "timestamp_utc": "2026-11-24T08:20:00Z",
                        "host_page_size": 4096,
                        "device_alias": "device-4k",
                        "bucket": "cold_provisioned",
                        "pair_index": 30,
                        "engine": "qemu",
                        "order": "qemu_first",
                        "status": "PASS",
                        "failure_reason": None,
                        "timeout_explained": False,
                        "t0_to_shell_ready_ms": 8400,
                        "residual_process_count": 0,
                        "temp_c_before": 34.0,
                        "temp_c_after": 34.5,
                    },
                    {
                        "run_id": run2,
                        "sample_id": bench.make_sample_id(4096, "cold_provisioned", 30, "uml"),
                        "timestamp_utc": "2026-11-24T08:20:10Z",
                        "host_page_size": 4096,
                        "device_alias": "device-4k",
                        "bucket": "cold_provisioned",
                        "pair_index": 30,
                        "engine": "uml",
                        "order": "qemu_first",
                        "status": "PASS",
                        "failure_reason": None,
                        "timeout_explained": False,
                        "t0_to_shell_ready_ms": 4100,
                        "residual_process_count": 0,
                        "temp_c_before": 34.5,
                        "temp_c_after": 34.8,
                    },
                ],
            )

            # Run 3: explicitly record missing 16K device bucket
            missing_rows = bench.record_missing_device_bucket(
                ledger_path=ledger_path,
                page_size=16384,
                reason="no-physical-16k-device-connected",
                timestamp_utc="2026-11-24T08:25:00Z",
            )
            self.assertEqual(len(missing_rows), len(bench.BUCKETS))
            self.assertEqual(missing_rows[0]["run_id"], "run-0003")
            self.assertEqual(missing_rows[0]["status"], "MISSING")
            self.assertEqual(missing_rows[0]["reason"], "no-physical-16k-device-connected")

            all_rows = bench.load_ledger(ledger_path)
            self.assertEqual(len(all_rows), len(run1_rows) + 2 + len(bench.BUCKETS))
            self.assertEqual(sum(1 for r in all_rows if r["status"] == "TIMEOUT"), 1)
            self.assertEqual(sum(1 for r in all_rows if r["status"] == "FAIL"), 1)

            # Retrying MUST NOT hide the prior unexplained timeout!
            summary = bench.evaluate_w305_gate(all_rows)
            self.assertEqual(summary["verdict"], "FAIL_UNEXPLAINED_TIMEOUT")
            self.assertFalse(summary["signoff_allowed"])
            self.assertEqual(summary["historical_timeouts_preserved"], 1)
            self.assertEqual(summary["historical_failures_preserved"], 1)
            self.assertEqual(summary["by_page_size"]["16384"]["status"], "MISSING")
            self.assertEqual(
                summary["by_page_size"]["16384"]["reason"], "no-physical-16k-device-connected"
            )


class TestThermalCleanInstallAndHygiene(unittest.TestCase):
    def test_parse_battery_temperature_c(self) -> None:
        dumpsys = (
            "Current Battery Service state:\n"
            "  AC powered: false\n"
            "  USB powered: true\n"
            "  level: 82\n"
            "  scale: 100\n"
            "  voltage: 4120\n"
            "  temperature: 345\n"
            "  technology: Li-poly\n"
        )
        self.assertEqual(bench.parse_battery_temperature_c(dumpsys), 34.5)
        self.assertEqual(bench.parse_battery_temperature_c("  temperature: 420\n"), 42.0)
        self.assertEqual(bench.parse_battery_temperature_c("  temperature: 0\n"), 0.0)
        self.assertEqual(bench.parse_battery_temperature_c("  temperature: -15\n"), -1.5)
        self.assertIsNone(bench.parse_battery_temperature_c("level: 80\n"))
        self.assertIsNone(bench.parse_battery_temperature_c(""))

    def test_wait_for_thermal_cooldown(self) -> None:
        class _FakeResult:
            def __init__(self, stdout: str) -> None:
                self.stdout = stdout

        class _TempAdb:
            def __init__(self, readings: list[int]) -> None:
                self.readings = list(readings)
                self.idx = 0

            def run(self, *args: str, timeout: int = 15, check: bool = False) -> _FakeResult:
                val = self.readings[min(self.idx, len(self.readings) - 1)]
                self.idx += 1
                return _FakeResult(f"  temperature: {val}\n")

        now = [0.0]
        sleeps: list[float] = []

        def fake_sleep(sec: float) -> None:
            sleeps.append(sec)
            now[0] += sec

        adb_cool = _TempAdb([445, 430, 418])
        temp, waited = bench.wait_for_thermal_cooldown(
            adb_cool,
            max_temp_c=42.0,
            poll_sec=10.0,
            max_wait_sec=60.0,
            sleep_fn=fake_sleep,
            monotonic_fn=lambda: now[0],
        )
        self.assertEqual(temp, 41.8)
        self.assertTrue(waited)
        self.assertEqual(sleeps, [10.0, 10.0])

        # Never cools down -> exits cleanly at max_wait_sec
        now[0] = 0.0
        sleeps.clear()
        adb_hot = _TempAdb([450, 450, 450, 450])
        temp2, waited2 = bench.wait_for_thermal_cooldown(
            adb_hot,
            max_temp_c=42.0,
            poll_sec=10.0,
            max_wait_sec=25.0,
            sleep_fn=fake_sleep,
            monotonic_fn=lambda: now[0],
        )
        self.assertEqual(temp2, 45.0)
        self.assertTrue(waited2)
        self.assertEqual(sleeps, [10.0, 10.0, 5.0])

    def test_clean_uninstall_prefix_filtering_and_alias_validation(self) -> None:
        self.assertEqual(bench.validate_device_alias(" device-4k-api35 "), "device-4k-api35")
        for bad in ("", "   ", "../escape", "device with spaces", "a" * 49):
            with self.assertRaises(ValueError):
                bench.validate_device_alias(bad)

        class _FakeResult:
            def __init__(self, stdout: str, stderr: str = "", returncode: int = 0) -> None:
                self.stdout = stdout
                self.stderr = stderr
                self.returncode = returncode

        class _FakeAdb:
            def __init__(self) -> None:
                self.calls: list[tuple[str, ...]] = []
                self.installed = {
                    "io.github.nyzeep.orchestella",
                    "io.github.nyzeep.orchestella.qa.w304",
                    "io.github.nyzeep.orchestella.qa.w305",
                    "io.github.nyzeep.orchestella.qa.w305.test",
                    "com.unrelated.other",
                    "io.github.other.orchestella",
                }

            def run(self, *args: str, timeout: int = 120, check: bool = True) -> _FakeResult:
                self.calls.append(args)
                if args[:3] == ("shell", "pm", "path"):
                    pkg = args[3]
                    if pkg in self.installed:
                        return _FakeResult(f"package:/data/app/{pkg}/base.apk\n")
                    return _FakeResult("", returncode=1)
                return _FakeResult("Success\n")

        fake_adb = _FakeAdb()
        removed = bench.clean_uninstall_orchestella_packages(fake_adb)
        self.assertEqual(
            removed,
            [
                "io.github.nyzeep.orchestella.qa.w305.test",
                "io.github.nyzeep.orchestella.qa.w305",
            ],
        )
        self.assertEqual(
            [c for c in fake_adb.calls if c[0] == "uninstall"],
            [
                ("uninstall", "io.github.nyzeep.orchestella.qa.w305.test"),
                ("uninstall", "io.github.nyzeep.orchestella.qa.w305"),
            ],
        )
        with self.assertRaises(ValueError):
            bench.clean_uninstall_orchestella_packages(fake_adb, "io.github.nyzeep.orchestella")

    def test_residual_processes_detects_uml_and_qemu_and_sanitize_redacts_paths(self) -> None:
        clean_ps = (
            "  PID  PPID NAME ARGS\n"
            " 1234     1 zygote64 zygote64\n"
            " 5678  1234 io.github.nyzeep.orche io.github.nyzeep.orchestella.qa.w305\n"
        )
        self.assertEqual(bench.find_residual_processes(clean_ps), [])

        dirty_ps = (
            clean_ps
            + " 9901  5678 libuml_kernel.so /data/app/.../libuml_kernel.so\n"
            + " 9902     1 libdroidlayer_qemu.so /data/app/.../libdroidlayer_qemu.so\n"
        )
        residuals = bench.find_residual_processes(dirty_ps)
        self.assertEqual(
            {r["token"] for r in residuals}, {"libuml_kernel.so", "libdroidlayer_qemu.so"}
        )

        # Synthetic paths built via concatenation so this file never contains literal host paths
        win_msg = "failed opening " + "C" + ":\\Users\\fixture\\app.apk now"
        home_msg = "failed opening " + "/home/" + "fixture/app.apk now"
        self.assertEqual(bench.sanitize_text(win_msg), "failed opening <redacted-path> now")
        self.assertEqual(bench.sanitize_text(home_msg), "failed opening <redacted-path> now")


class TestParseInstrumentationOutput(unittest.TestCase):
    def test_parse_single_sample_and_batch_sample_json(self) -> None:
        single_out = (
            "INSTRUMENTATION_STATUS: w305_stage=cold_start\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            "INSTRUMENTATION_RESULT: w305_result=PASS\n"
            "INSTRUMENTATION_RESULT: w305_engine=uml\n"
            "INSTRUMENTATION_RESULT: w305_bucket=cold_provisioned\n"
            "INSTRUMENTATION_RESULT: w305_pair_index=3\n"
            "INSTRUMENTATION_RESULT: w305_t0_to_shell_ready_ms=3420\n"
            "INSTRUMENTATION_RESULT: w305_t0_to_bridge_ready_ms=3710\n"
            "INSTRUMENTATION_RESULT: w305_t0_to_assets_ms=95\n"
            "INSTRUMENTATION_RESULT: w305_t0_to_engine_started_ms=310\n"
            "INSTRUMENTATION_RESULT: w305_t0_to_session_registered_ms=3520\n"
            "INSTRUMENTATION_RESULT: w305_credentials_only_reason=EXT4_READY\n"
            "INSTRUMENTATION_RESULT: w305_stop_result=CLEAN\n"
            "INSTRUMENTATION_CODE: -1\n"
        )
        parsed = bench.parse_instrumentation_output(single_out)
        self.assertEqual(parsed["code"], -1)
        self.assertEqual(parsed["status_stages"], ["cold_start"])
        self.assertEqual(parsed["result"]["w305_result"], "PASS")
        norm = bench.normalize_sample_payload(parsed["result"])
        self.assertEqual(norm["bucket"], "cold_provisioned")
        self.assertEqual(norm["engine"], "uml")
        self.assertEqual(norm["pair_index"], 3)
        self.assertEqual(norm["order"], "uml_first")
        self.assertEqual(norm["t0_to_shell_ready_ms"], 3420)
        self.assertEqual(norm["t0_to_bridge_ready_ms"], 3710)
        self.assertEqual(norm["credentials_only_reason"], "EXT4_READY")

        # Batch output with w305_sample_json (both unprefixed and w305_-prefixed, plus '=' in detail)
        batch_out = (
            "INSTRUMENTATION_STATUS: w305_stage=warmup_done\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            'INSTRUMENTATION_STATUS: w305_sample_json={"bucket":"warm_restart","engine":"uml",'
            '"pair_index":1,"order":"uml_first","result":"PASS","t0_to_shell_ready_ms":1120,'
            '"t0_to_bridge_ready_ms":1350,"detail":"rc=0"}\n'
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            'INSTRUMENTATION_STATUS: w305_sample_json={"w305_bucket":"warm_restart",'
            '"w305_engine":"qemu","w305_pair_index":1,"w305_result":"PASS",'
            '"w305_t0_to_shell_ready_ms":2600,"w305_credentials_only_reason":"HIT"}\n'
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            "INSTRUMENTATION_STATUS: w305_sample_json={corrupt_json\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            "INSTRUMENTATION_RESULT: w305_result=PASS\n"
            "INSTRUMENTATION_CODE: -1\n"
        )
        b_parsed = bench.parse_instrumentation_output(batch_out)
        self.assertEqual(b_parsed["code"], -1)
        self.assertEqual(len(b_parsed["samples"]), 2)
        self.assertEqual(len(b_parsed["sample_json_errors"]), 1)
        self.assertEqual(b_parsed["samples"][0]["engine"], "uml")
        self.assertEqual(b_parsed["samples"][0]["t0_to_shell_ready_ms"], 1120)
        self.assertEqual(b_parsed["samples"][0]["detail"], "rc=0")
        self.assertEqual(b_parsed["samples"][1]["engine"], "qemu")
        self.assertEqual(b_parsed["samples"][1]["t0_to_shell_ready_ms"], 2600)
        self.assertEqual(b_parsed["samples"][1]["credentials_only_reason"], "HIT")

    def test_stream_command_lines_enforces_timeout(self) -> None:
        started = time.monotonic()
        with self.assertRaises(TimeoutError):
            bench._stream_command_lines(
                [sys.executable, "-c", "import time; time.sleep(30)"],
                timeout_s=0.35,
            )
        self.assertLess(time.monotonic() - started, 3.0)


class TestFrozenConfigApkAndEndToEndOrchestrator(unittest.TestCase):
    @staticmethod
    def _build_fixture_apk(apk_path: Path) -> None:
        so_name = "lib/arm64-v8a/libuml_kernel.so"
        payload = b"\x7fELF" + b"\x00" * 60
        base_header_len = 30 + len(so_name.encode("utf-8")) + 4
        pad_len = (16384 - (base_header_len % 16384)) % 16384
        extra = struct.pack("<HH", 0xFFFF, pad_len) + (b"\x00" * pad_len)

        zi = zipfile.ZipInfo(so_name)
        zi.compress_type = zipfile.ZIP_STORED
        zi.extra = extra
        with zipfile.ZipFile(apk_path, "w") as zf:
            zf.writestr(zi, payload)
            zf.writestr(
                "assets/uml-fast-profile-v1.sha256",
                "a" * 64 + "  libuml_kernel.so\n",
            )
            zf.writestr(
                "assets/uml-net-helper-v1.sha256",
                "b" * 64 + "  libuml_net.so\n",
            )
            zf.writestr(
                "assets/runtime-artifacts-v1.sha256",
                "c" * 64 + "  runtime.tar\n",
            )

    def test_extract_apk_payload_manifests_and_16kb_check(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            apk_path = Path(tmp) / "app-debug.apk"
            self._build_fixture_apk(apk_path)

            manifests = bench.extract_apk_payload_manifests(apk_path)
            self.assertEqual(
                sorted(manifests.keys()),
                sorted(bench.APK_PAYLOAD_MANIFESTS),
            )
            self.assertIn("a" * 64 + "  libuml_kernel.so", manifests["uml-fast-profile-v1.sha256"])

            # Fail-closed default requires readelf/zipalign; relaxed mode verifies zip alignment
            self.assertFalse(bench.verify_apk_16kb(apk_path)["passed"])
            res_relaxed = bench.verify_apk_16kb(apk_path, require_external_tools=False)
            self.assertTrue(res_relaxed["passed"])
            self.assertEqual(res_relaxed["apk_name"], "app-debug.apk")

    def test_end_to_end_orchestration_interleaving_force_stop_thermal_and_privacy(self) -> None:
        class _FakeResult:
            def __init__(self, stdout: str) -> None:
                self.stdout = stdout

        class _FakeAdb:
            def __init__(self) -> None:
                self.calls: list[tuple[str, ...]] = []
                self.temp_tenths = 340

            def run(self, *args: str, timeout: int = 120, check: bool = True) -> _FakeResult:
                self.calls.append(args)
                if args == ("shell", "dumpsys", "battery"):
                    self.temp_tenths += 1
                    return _FakeResult(f"  temperature: {self.temp_tenths}\n")
                if args == ("shell", "ps", "-A"):
                    return _FakeResult("PID PPID NAME\n1 0 init\n")
                return _FakeResult("")

        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp) / "evidence"
            apk_path = Path(tmp) / "app-debug.apk"
            self._build_fixture_apk(apk_path)

            fake_adb = _FakeAdb()
            device_meta = {
                "device_alias": "device-4k",
                "device_kind": "physical",
                "is_emulator": False,
                "host_page_size": 4096,
                "sdk_int": 35,
                "android_release": "15",
                "abi": "arm64-v8a",
                "kernel_release": "6.6.30-android15",
                "package_name": bench.DEFAULT_PACKAGE,
                "package_version_name": "0.7.0-qa",
                "package_version_code": 700,
                "compat_16kb_indicators": [],
                "apk_name": apk_path.name,
                "apk_payload_manifests": bench.extract_apk_payload_manifests(apk_path),
            }
            config = bench.build_benchmark_config(
                device_meta,
                cold_pairs_planned=2,
                warm_pairs_planned=2,
                first_provision_pairs_planned=1,
                warm_reconnect_pairs_planned=2,
                warm_batch_size=5,
                timeout_sec=120,
                max_temp_c=42.0,
            )

            recorded_actions: list[tuple[str, dict[str, str], tuple[str, ...] | None]] = []

            def fake_instrument(
                adb: Any,
                package_name: str,
                extras: dict[str, str],
                timeout_s: int,
                raw_log_path: Path,
            ) -> dict[str, Any]:
                last_adb = adb.calls[-1] if adb.calls else None
                recorded_actions.append((extras["action"], dict(extras), last_adb))
                action = extras["action"]
                if action == "prepare":
                    raw = (
                        "INSTRUMENTATION_RESULT: w305_result=PASS\n"
                        "INSTRUMENTATION_RESULT: w305_uml_instance_id=uml-inst-1\n"
                        "INSTRUMENTATION_RESULT: w305_qemu_instance_id=qemu-inst-1\n"
                        f"INSTRUMENTATION_RESULT: w305_alpine_sha256={bench.ROOTFS_SHA256}\n"
                        "INSTRUMENTATION_CODE: -1\n"
                    )
                elif action in ("first_provision", "cold_provisioned"):
                    eng = extras["engine"]
                    p_idx = int(extras["pair_index"])
                    ms = 4200 if eng == "uml" else 8800
                    if action == "first_provision":
                        ms += 2000
                    raw = (
                        "INSTRUMENTATION_RESULT: w305_result=PASS\n"
                        f"INSTRUMENTATION_RESULT: w305_engine={eng}\n"
                        f"INSTRUMENTATION_RESULT: w305_bucket={action}\n"
                        f"INSTRUMENTATION_RESULT: w305_pair_index={p_idx}\n"
                        f"INSTRUMENTATION_RESULT: w305_t0_to_shell_ready_ms={ms}\n"
                        f"INSTRUMENTATION_RESULT: w305_t0_to_bridge_ready_ms={ms + 200}\n"
                        "INSTRUMENTATION_RESULT: w305_stop_result=CLEAN\n"
                        "INSTRUMENTATION_CODE: -1\n"
                    )
                elif action == "warm_restart_batch":
                    start_p = int(extras["start_pair"])
                    cnt = int(extras["count"])
                    lines = []
                    for p in range(start_p, start_p + cnt):
                        for eng in bench.pair_engine_order(p):
                            ms = 1350 if eng == "uml" else 3100
                            payload = json.dumps(
                                {
                                    "bucket": "warm_restart",
                                    "engine": eng,
                                    "pair_index": p,
                                    "order": bench.pair_order_label(p),
                                    "result": "PASS",
                                    "t0_to_shell_ready_ms": ms,
                                    "t0_to_bridge_ready_ms": ms + 150,
                                    "stop_result": "CLEAN",
                                }
                            )
                            lines.append(f"INSTRUMENTATION_STATUS: w305_sample_json={payload}\n")
                    lines.append("INSTRUMENTATION_RESULT: w305_result=PASS\n")
                    lines.append("INSTRUMENTATION_CODE: -1\n")
                    raw = "".join(lines)
                elif action == "warm_reconnect_pair":
                    cnt = int(extras["count"])
                    lines = []
                    for p in range(1, cnt + 1):
                        for eng in bench.pair_engine_order(p):
                            payload = json.dumps(
                                {
                                    "bucket": "warm_reconnect",
                                    "engine": eng,
                                    "pair_index": p,
                                    "result": "PASS",
                                    "roundtrip_ms": 18 if eng == "uml" else 42,
                                }
                            )
                            lines.append(f"INSTRUMENTATION_STATUS: w305_sample_json={payload}\n")
                    lines.append("INSTRUMENTATION_RESULT: w305_result=PASS\n")
                    lines.append("INSTRUMENTATION_CODE: -1\n")
                    raw = "".join(lines)
                else:
                    raise AssertionError(f"Unexpected action: {action}")
                return {
                    "parsed": bench.parse_instrumentation_output(raw),
                    "timed_out": False,
                    "error": None,
                    "duration_ms": 1000,
                }

            bench.run_benchmark(
                adb=fake_adb,
                out_dir=out_dir,
                package_name=bench.DEFAULT_PACKAGE,
                selected_buckets=list(bench.BUCKETS),
                device_meta=device_meta,
                config=config,
                apk_verification={"passed": True},
                record_missing_page_sizes=[16384],
                missing_reason="no-physical-16k-device-connected",
                instrument_fn=fake_instrument,
            )

            # Verify cold_provisioned interleaving: pair 1 uml->qemu, pair 2 qemu->uml,
            # and EVERY cold invocation immediately preceded by `am force-stop`
            cold_calls = [a for a in recorded_actions if a[0] == "cold_provisioned"]
            self.assertEqual(
                [(c[1]["pair_index"], c[1]["engine"]) for c in cold_calls],
                [("1", "uml"), ("1", "qemu"), ("2", "qemu"), ("2", "uml")],
            )
            for _, _, prev_adb in cold_calls:
                self.assertEqual(
                    prev_adb, ("shell", "am", "force-stop", bench.DEFAULT_PACKAGE)
                )

            # Verify warm_restart used warm_restart_batch as primary
            warm_calls = [a for a in recorded_actions if a[0] == "warm_restart_batch"]
            self.assertEqual(len(warm_calls), 1)
            self.assertEqual(warm_calls[0][1]["start_pair"], "1")
            self.assertEqual(warm_calls[0][1]["count"], "2")

            # Verify every executed ledger row carries battery temp_c_before & temp_c_after
            rows = bench.load_ledger(out_dir / "ledger.jsonl")
            exec_rows = [r for r in rows if r["status"] != "MISSING"]
            self.assertEqual(len(exec_rows), 2 + 4 + 4 + 4)  # fp(2) + cold(4) + warm(4) + reconn(4)
            for r in exec_rows:
                self.assertIsNotNone(r["temp_c_before"])
                self.assertIsNotNone(r["temp_c_after"])

            # Verify second run with --resume skips all already-PASS samples!
            recorded_actions.clear()
            bench.run_benchmark(
                adb=fake_adb,
                out_dir=out_dir,
                package_name=bench.DEFAULT_PACKAGE,
                selected_buckets=list(bench.BUCKETS),
                device_meta=device_meta,
                config=config,
                apk_verification={"passed": True},
                resume=True,
                skip_prepare=True,
                instrument_fn=fake_instrument,
            )
            self.assertEqual(recorded_actions, [])

            # Verify generated artifacts are clean & desensitized (zero sensitive data findings!)
            for fname in ("benchmark-config.json", "ledger.jsonl", "summary.json", "summary.md"):
                text = (out_dir / fname).read_text(encoding="utf-8")
                self.assertNotIn("serial", text.lower(), f"'serial' leaked in {fname}")
                findings = check_sensitive_data.scan_text_lines(text.splitlines())
                self.assertEqual(findings, {}, f"Sensitive data found in {fname}: {findings}")

            md_text = (out_dir / "summary.md").read_text(encoding="utf-8")
            self.assertIn("OPEN", md_text)
            self.assertIn("no-physical-16k-device-connected", md_text)
            self.assertIn(bench.PERCENTILE_FORMULA, md_text)

    def test_mid_batch_timeout_preserves_emitted_samples_and_records_timeout(self) -> None:
        partial_raw = (
            'INSTRUMENTATION_STATUS: w305_sample_json={"bucket":"warm_restart","engine":"uml",'
            '"pair_index":1,"order":"uml_first","result":"PASS","t0_to_shell_ready_ms":1200}\n'
            "INSTRUMENTATION_STATUS_CODE: 0\n"
        )
        run_res = {
            "parsed": bench.parse_instrumentation_output(partial_raw),
            "timed_out": True,
            "error": "Instrumentation timed out after 120s",
            "duration_ms": 120000,
        }
        expected_slots = [(1, "uml"), (1, "qemu"), (2, "qemu"), (2, "uml")]
        rows = bench._record_batch_results(
            run_res=run_res,
            expected_slots=expected_slots,
            bucket="warm_restart",
            run_id="run-0001",
            host_page_size=4096,
            device_alias="device-4k",
            temp_c_before=35.0,
            temp_c_after=38.5,
            residuals=[],
        )
        self.assertEqual(len(rows), 2)
        self.assertEqual((rows[0]["pair_index"], rows[0]["engine"], rows[0]["status"]), (1, "uml", "PASS"))
        self.assertEqual(
            (rows[1]["pair_index"], rows[1]["engine"], rows[1]["status"], rows[1]["failure_reason"]),
            (1, "qemu", "TIMEOUT", "instrument_timeout"),
        )

    def test_batch_abort_records_fail_for_all_missing_slots(self) -> None:
        partial_raw = (
            'INSTRUMENTATION_STATUS: w305_sample_json={"bucket":"warm_restart","engine":"uml",'
            '"pair_index":1,"order":"uml_first","result":"PASS","t0_to_shell_ready_ms":1200}\n'
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            "INSTRUMENTATION_RESULT: w305_result=FAIL\n"
            "INSTRUMENTATION_RESULT: w305_error=batch_aborted\n"
            "INSTRUMENTATION_CODE: -1\n"
        )
        run_res = {
            "parsed": bench.parse_instrumentation_output(partial_raw),
            "timed_out": False,
            "error": None,
            "duration_ms": 5000,
        }
        expected_slots = [(1, "uml"), (1, "qemu"), (2, "qemu"), (2, "uml")]
        rows = bench._record_batch_results(
            run_res=run_res,
            expected_slots=expected_slots,
            bucket="warm_restart",
            run_id="run-0001",
            host_page_size=4096,
            device_alias="device-4k",
            temp_c_before=35.0,
            temp_c_after=36.0,
            residuals=[],
        )
        self.assertEqual(len(rows), 4)
        self.assertEqual(rows[0]["status"], "PASS")
        self.assertEqual(
            [(r["pair_index"], r["engine"], r["status"]) for r in rows[1:]],
            [(1, "qemu", "FAIL"), (2, "qemu", "FAIL"), (2, "uml", "FAIL")],
        )

    def test_warm_pair_mode_prepare_sha_guard_residual_and_summarize_only_cli(self) -> None:
        class _FakeResult:
            def __init__(self, stdout: str) -> None:
                self.stdout = stdout

        class _FakeAdb:
            def __init__(self, residual_once: bool = False) -> None:
                self.calls: list[tuple[str, ...]] = []
                self.residual_once = residual_once

            def run(self, *args: str, timeout: int = 120, check: bool = True) -> _FakeResult:
                self.calls.append(args)
                if args == ("shell", "dumpsys", "battery"):
                    return _FakeResult("  temperature: 345\n")
                if args == ("shell", "ps", "-A"):
                    if self.residual_once:
                        self.residual_once = False
                        return _FakeResult("999 1 libdroidlayer_qemu.so /data/app/x\n")
                    return _FakeResult("PID PPID NAME\n1 0 init\n")
                return _FakeResult("")

        device_meta = {
            "device_alias": "device-4k",
            "device_kind": "physical",
            "is_emulator": False,
            "host_page_size": 4096,
            "sdk_int": 35,
            "android_release": "15",
            "abi": "arm64-v8a",
            "kernel_release": "6.6.30-android15",
            "package_name": bench.DEFAULT_PACKAGE,
            "compat_16kb_indicators": [],
            "apk_name": "app-debug.apk",
            "apk_payload_manifests": {},
        }
        config = bench.build_benchmark_config(
            device_meta,
            cold_pairs_planned=1,
            warm_pairs_planned=2,
            first_provision_pairs_planned=0,
            warm_reconnect_pairs_planned=0,
            timeout_sec=60,
        )

        # 1. Rootfs SHA-256 mismatch during prepare fails closed
        with tempfile.TemporaryDirectory() as tmp:
            bad_sha = "0" * 64

            def bad_prepare(*_a: Any, **_kw: Any) -> dict[str, Any]:
                raw = (
                    "INSTRUMENTATION_RESULT: w305_result=PASS\n"
                    f"INSTRUMENTATION_RESULT: w305_alpine_sha256={bad_sha}\n"
                    "INSTRUMENTATION_CODE: -1\n"
                )
                return {"parsed": bench.parse_instrumentation_output(raw), "timed_out": False, "error": None, "duration_ms": 100}

            with self.assertRaises(RuntimeError):
                bench.run_benchmark(
                    adb=_FakeAdb(),
                    out_dir=Path(tmp),
                    package_name=bench.DEFAULT_PACKAGE,
                    selected_buckets=["cold_provisioned"],
                    device_meta=device_meta,
                    config=config,
                    instrument_fn=bad_prepare,
                )

        # 2. warm_mode="pair" uses warm_restart_pair with interleaved order + warmup=true,
        # and residual QEMU process marks sample FAIL and invokes am force-stop.
        with tempfile.TemporaryDirectory() as tmp:
            pair_extras: list[dict[str, str]] = []
            adb_res = _FakeAdb(residual_once=True)

            def pair_instrument(_adb: Any, _pkg: str, extras: dict[str, str], _t: int, _p: Path) -> dict[str, Any]:
                pair_extras.append(dict(extras))
                action = extras["action"]
                if action == "cold_provisioned":
                    raw = (
                        "INSTRUMENTATION_RESULT: w305_result=PASS\n"
                        f"INSTRUMENTATION_RESULT: w305_engine={extras['engine']}\n"
                        "INSTRUMENTATION_RESULT: w305_bucket=cold_provisioned\n"
                        f"INSTRUMENTATION_RESULT: w305_pair_index={extras['pair_index']}\n"
                        "INSTRUMENTATION_RESULT: w305_t0_to_shell_ready_ms=4000\n"
                        "INSTRUMENTATION_CODE: -1\n"
                    )
                else:
                    p_idx = int(extras["pair_index"])
                    raw = (
                        "INSTRUMENTATION_RESULT: w305_result=PASS\n"
                        "INSTRUMENTATION_RESULT: w305_uml_result=PASS\n"
                        "INSTRUMENTATION_RESULT: w305_uml_t0_to_shell_ready_ms=1200\n"
                        "INSTRUMENTATION_RESULT: w305_qemu_result=PASS\n"
                        "INSTRUMENTATION_RESULT: w305_qemu_t0_to_shell_ready_ms=2800\n"
                        f"INSTRUMENTATION_RESULT: w305_pair_index={p_idx}\n"
                        "INSTRUMENTATION_CODE: -1\n"
                    )
                return {"parsed": bench.parse_instrumentation_output(raw), "timed_out": False, "error": None, "duration_ms": 200}

            bench.run_benchmark(
                adb=adb_res,
                out_dir=Path(tmp),
                package_name=bench.DEFAULT_PACKAGE,
                selected_buckets=["cold_provisioned", "warm_restart"],
                device_meta=device_meta,
                config=config,
                skip_prepare=True,
                warm_mode="pair",
                instrument_fn=pair_instrument,
            )
            warm_calls = [e for e in pair_extras if e["action"] == "warm_restart_pair"]
            self.assertEqual(
                [(e["pair_index"], e["order"], e["warmup"]) for e in warm_calls],
                [("1", "uml_first", "true"), ("2", "qemu_first", "true")],
            )
            rows = bench.load_ledger(Path(tmp) / "ledger.jsonl")
            cold_uml = [r for r in rows if r["bucket"] == "cold_provisioned" and r["engine"] == "uml"][0]
            self.assertEqual(cold_uml["status"], "FAIL")
            self.assertEqual(cold_uml["failure_reason"], "residual_process_detected")
            self.assertEqual(cold_uml["residual_process_count"], 1)

        # 3. Exact unrounded speedup check: 0.199999999 rounds to 0.2 at 8 decimals yet MUST FAIL gate!
        rows_sub_boundary = _make_passing_page_size_rows(
            4096,
            cold_uml_ms=8000,
            cold_qemu_ms=10000,
            warm_uml_ms=2400,
            warm_qemu_ms=3000,
        )
        # Override cold with high-precision ratio = 0.199999999 < 0.20
        for r in rows_sub_boundary:
            if r["bucket"] == "cold_provisioned":
                r["t0_to_shell_ready_ms"] = 800000001 if r["engine"] == "uml" else 1000000000
        sub_summary = bench.evaluate_w305_gate(
            rows_sub_boundary,
            cold_p95_limit_ms=900000000,
        )
        self.assertEqual(sub_summary["verdict"], "FAIL_THRESHOLD")
        self.assertFalse(sub_summary["signoff_allowed"])

        # 4. --summarize-only CLI regenerates summary.json & summary.md with exit code 3 (or 0 with --allow-pending-16k)
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp)
            bench.append_ledger_records(out_dir / "ledger.jsonl", _make_passing_page_size_rows(4096))
            with contextlib.redirect_stdout(io.StringIO()):
                rc_strict = bench.main(
                    [
                        "--summarize-only",
                        "--output-dir",
                        str(out_dir),
                        "--record-missing-page-size",
                        "16384",
                    ]
                )
            self.assertEqual(rc_strict, 3)
            with contextlib.redirect_stdout(io.StringIO()):
                rc_allow = bench.main(
                    [
                        "--summarize-only",
                        "--output-dir",
                        str(out_dir),
                        "--allow-pending-16k",
                    ]
                )
            self.assertEqual(rc_allow, 0)
            self.assertTrue((out_dir / "summary.json").is_file())
            self.assertTrue((out_dir / "summary.md").is_file())


if __name__ == "__main__":
    unittest.main()
