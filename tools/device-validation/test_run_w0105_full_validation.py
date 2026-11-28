#!/usr/bin/env python3
"""Unit tests for tools/device-validation/run_w0105_full_validation.py (TDD)."""

from __future__ import annotations

import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from typing import Any

sys.path.insert(0, os.path.dirname(__file__))

import run_w0105_full_validation as w0105  # noqa: E402
import run_w305_perf_bench as bench  # noqa: E402
import evidence_provenance as prov  # noqa: E402
import qa_package_guard as guard  # noqa: E402

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


class TestEvidenceProvenanceAndResume(unittest.TestCase):
    @staticmethod
    def _make_sample_provenance(
        *,
        app_sha: str = "a" * 64,
        test_sha: str = "b" * 64,
        debian_sha: str = "c" * 64,
        lock_sha: str = "d" * 64,
        host_page_size: int = 4096,
        device_alias: str = "device-4k",
        qa_purpose: str = "w0105",
    ) -> dict[str, Any]:
        apk_proof = {
            "qa_target": {
                "purpose": qa_purpose,
                "app_package": f"io.github.nyzeep.orchestella.qa.{qa_purpose}",
                "test_package": f"io.github.nyzeep.orchestella.qa.{qa_purpose}.test",
                "instrumentation_component": f"io.github.nyzeep.orchestella.qa.{qa_purpose}.test/io.github.nyzeep.orchestella.W0105FullValidationProbe",
                "expected_abi": "arm64-v8a",
            },
            "app_apk": {
                "name": "app-debug-fullpkg.apk",
                "size_bytes": 123456,
                "sha256": app_sha,
                "version_name": f"0.6.3-qa.{qa_purpose}.4cb8081",
                "version_code": 603,
                "apksigner": {"signer_cert_sha256s": ["3" * 64]},
            },
            "test_apk": {
                "name": "app-debug-androidTest.apk",
                "size_bytes": 12345,
                "sha256": test_sha,
                "apksigner": {"signer_cert_sha256s": ["3" * 64]},
            },
            "signing_cert_sha256s": ["3" * 64],
        }
        builtin_payloads = {
            "lib/arm64-v8a/libuml_kernel_full.so": "e" * 64,
            "lib/arm64-v8a/libuml_stub_full.so": "f" * 64,
            "initramfs.cpio.gz": "1" * 64,
            "alpine-minirootfs-3.22.2-aarch64.tar.gz": "2" * 64,
        }
        inputs_prov = {
            "debian_rootfs": {"name": "debian-13-rootfs.tar.xz", "size_bytes": 9000, "sha256": debian_sha},
            "w0104_fixtures": {
                "lock_sha256": lock_sha,
                "base_image_tar_sha256": "4" * 64,
                "keyring_sha256": "5" * 64,
                "inrelease_sha256": "6" * 64,
                "packages_manifest_sha256": "7" * 64,
                "package_count": 25,
            },
            "expected_staged_files": {},
        }
        device_facts = {
            "device_alias": device_alias,
            "host_page_size": host_page_size,
            "android_release": "15",
            "android_sdk": 35,
            "abi": "arm64-v8a",
            "host_kernel_release": "5.10.236",
            "host_cpus": 6,
        }
        return prov.build_provenance(
            studio_commit="4cb8081f" * 5,
            studio_dirty=False,
            runner_path=Path(__file__).with_name("run_w0105_full_validation.py"),
            helper_paths=[],
            apk_proof=apk_proof,
            builtin_payloads=builtin_payloads,
            inputs_provenance=inputs_prov,
            device_facts=device_facts,
        )

    def test_fresh_run_refuses_existing_dir_and_resume_verifies_same_identity(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            ev_dir = Path(tmp) / "ev"
            ev_dir.mkdir()
            bundle = self._make_sample_provenance()
            manifest = {
                "schema_version": prov.PROVENANCE_SCHEMA_VERSION,
                "generated_at_utc": "2026-11-28T00:00:00Z",
                "provenance": bundle["provenance"],
                "provenance_digest": bundle["provenance_digest"],
                "candidate_digest": bundle["candidate_digest"],
            }
            manifest_path = ev_dir / "candidate-manifest.json"
            prov.atomic_write_json(manifest_path, manifest)
            orig_manifest_bytes = manifest_path.read_bytes()

            row = _make_debian_bench_rows(bucket="cold_provisioned", count=1, shell_ms=4200)[0]
            row.update(bundle["row_attribution"])
            row["attempt_index"] = 1
            row["attempt_key"] = f"{row['sample_id']}@run-0001#1"
            prov.append_jsonl_atomic(ev_dir / "ledger.jsonl", [row])

            with self.assertRaises(prov.EvidenceProvenanceError) as fresh_ctx:
                prov.prepare_evidence_session(ev_dir, resume=False, expected_provenance_bundle=bundle)
            self.assertEqual(fresh_ctx.exception.code, "EXISTING_EVIDENCE_DIR_REQUIRES_RESUME_OR_NEW_DIR")

            resumed = prov.prepare_evidence_session(ev_dir, resume=True, expected_provenance_bundle=bundle)
            self.assertTrue(resumed["is_resume"])
            self.assertEqual(resumed["next_run_id"], "run-0002")
            self.assertIn(row["sample_id"], resumed["selection"]["passed_sample_ids"])
            self.assertEqual(manifest_path.read_bytes(), orig_manifest_bytes)

    def test_resume_rejects_test_apk_change_seed_change_page_size_change_and_corrupt_line(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            ev_dir = Path(tmp) / "ev"
            ev_dir.mkdir()
            base_bundle = self._make_sample_provenance()
            prov.atomic_write_json(
                ev_dir / "candidate-manifest.json",
                {
                    "schema_version": prov.PROVENANCE_SCHEMA_VERSION,
                    "provenance": base_bundle["provenance"],
                    "provenance_digest": base_bundle["provenance_digest"],
                    "candidate_digest": base_bundle["candidate_digest"],
                },
            )
            row = _make_debian_bench_rows(bucket="cold_provisioned", count=1, shell_ms=4200)[0]
            row.update(base_bundle["row_attribution"])
            row["attempt_index"] = 1
            row["attempt_key"] = f"{row['sample_id']}@run-0001#1"
            prov.append_jsonl_atomic(ev_dir / "ledger.jsonl", [row])

            # Main APK unchanged, test APK changed
            changed_test = self._make_sample_provenance(test_sha="9" * 64)
            with self.assertRaises(prov.EvidenceProvenanceError) as test_ctx:
                prov.prepare_evidence_session(ev_dir, resume=True, expected_provenance_bundle=changed_test)
            self.assertEqual(test_ctx.exception.code, "RESUME_PROVENANCE_MISMATCH")
            self.assertTrue(any("test_apk.sha256" in d["field"] for d in test_ctx.exception.diffs))

            # Rootfs seed / fixture changed
            changed_seed = self._make_sample_provenance(debian_sha="8" * 64)
            with self.assertRaises(prov.EvidenceProvenanceError) as seed_ctx:
                prov.prepare_evidence_session(ev_dir, resume=True, expected_provenance_bundle=changed_seed)
            self.assertEqual(seed_ctx.exception.code, "RESUME_PROVENANCE_MISMATCH")
            self.assertTrue(any("inputs.debian_rootfs.sha256" in d["field"] for d in seed_ctx.exception.diffs))

            # Cross-device host page size change
            changed_ps = self._make_sample_provenance(host_page_size=16384, device_alias="device-16k")
            with self.assertRaises(prov.EvidenceProvenanceError) as ps_ctx:
                prov.prepare_evidence_session(ev_dir, resume=True, expected_provenance_bundle=changed_ps)
            self.assertEqual(ps_ctx.exception.code, "RESUME_PROVENANCE_MISMATCH")
            self.assertTrue(any("device_condition.host_page_size" in d["field"] for d in ps_ctx.exception.diffs))

            # Half-written JSONL line
            with (ev_dir / "ledger.jsonl").open("a", encoding="utf-8") as fh:
                fh.write('{"record_type":"perf_sample","status":"PA')
            with self.assertRaises(prov.EvidenceProvenanceError) as corrupt_ctx:
                prov.prepare_evidence_session(ev_dir, resume=True, expected_provenance_bundle=base_bundle)
            self.assertEqual(corrupt_ctx.exception.code, "CORRUPT_LEDGER_LINE")
            self.assertIn("line 2", corrupt_ctx.exception.detail)

    def test_legacy_ledger_is_audited_readonly_and_refused_for_resume_and_v2_summary(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            ev_dir = Path(tmp) / "legacy"
            ev_dir.mkdir()
            legacy_manifest = ev_dir / "candidate-manifest.json"
            legacy_manifest.write_text('{"studio_commit":"fad2fc2f","qa_package":"io.github.nyzeep.orchestella.qa.w0105"}\n', encoding="utf-8")
            legacy_ledger = ev_dir / "ledger.jsonl"
            legacy_row = _passing_functional_rows(4096)[0]
            legacy_ledger.write_text(json.dumps(legacy_row) + "\n", encoding="utf-8")
            before_bytes = (legacy_manifest.read_bytes(), legacy_ledger.read_bytes())

            audit = prov.audit_evidence_dir_readonly(ev_dir)
            self.assertTrue(audit["read_only_historical"])
            self.assertEqual(audit["schema_version"], 1)
            self.assertEqual((legacy_manifest.read_bytes(), legacy_ledger.read_bytes()), before_bytes)

            bundle = self._make_sample_provenance()
            with self.assertRaises(prov.EvidenceProvenanceError) as ctx:
                prov.prepare_evidence_session(ev_dir, resume=True, expected_provenance_bundle=bundle)
            self.assertEqual(ctx.exception.code, "LEGACY_SCHEMA_NO_PROVENANCE")
            self.assertEqual((legacy_manifest.read_bytes(), legacy_ledger.read_bytes()), before_bytes)

            with self.assertRaises(prov.EvidenceProvenanceError) as sum_ctx:
                prov.validate_and_select_ledger([legacy_row], require_provenance=True)
            self.assertEqual(sum_ctx.exception.code, "LEGACY_OR_MISSING_ROW_PROVENANCE")

    def test_mixed_provenance_duplicate_pass_key_retry_supersession_and_dir_lock(self) -> None:
        bundle_a = self._make_sample_provenance(app_sha="a" * 64)
        bundle_b = self._make_sample_provenance(app_sha="f" * 64)

        fail_row = _make_debian_bench_rows(bucket="cold_provisioned", count=1, shell_ms=4200, run_id="run-0001")[0]
        fail_row.update(bundle_a["row_attribution"])
        fail_row["status"] = "FAIL"
        fail_row["t0_to_shell_ready_ms"] = None
        fail_row.update(prov.build_attempt_metadata([], fail_row["sample_id"], "run-0001"))

        pass_row = _make_debian_bench_rows(bucket="cold_provisioned", count=1, shell_ms=4100, run_id="run-0002")[0]
        pass_row.update(bundle_a["row_attribution"])
        pass_row.update(prov.build_attempt_metadata([fail_row], pass_row["sample_id"], "run-0002"))
        self.assertEqual(pass_row["attempt_index"], 2)
        self.assertEqual(pass_row["supersedes_run_id"], "run-0001")
        self.assertEqual(pass_row["supersedes_row_index"], 0)

        sel = prov.validate_and_select_ledger(
            [fail_row, pass_row],
            expected_provenance=bundle_a["provenance"],
            expected_provenance_digest=bundle_a["provenance_digest"],
            require_provenance=True,
        )
        self.assertIn(pass_row["sample_id"], sel["passed_sample_ids"])

        # Mixed provenance rejected
        foreign_row = _make_debian_bench_rows(bucket="cold_provisioned", count=2, shell_ms=4100, run_id="run-0002")[1]
        foreign_row.update(bundle_b["row_attribution"])
        foreign_row["attempt_index"] = 1
        foreign_row["attempt_key"] = f"{foreign_row['sample_id']}@run-0002#1"
        with self.assertRaises(prov.EvidenceProvenanceError) as mix_ctx:
            prov.validate_and_select_ledger([fail_row, pass_row, foreign_row], require_provenance=True)
        self.assertEqual(mix_ctx.exception.code, "MIXED_PROVENANCE_ROW")

        # Duplicate PASS sample key rejected
        dup_pass = dict(pass_row)
        dup_pass["run_id"] = "run-0003"
        dup_pass["attempt_index"] = 3
        dup_pass["attempt_key"] = f"{pass_row['sample_id']}@run-0003#3"
        with self.assertRaises(prov.EvidenceProvenanceError) as dup_ctx:
            prov.validate_and_select_ledger([fail_row, pass_row, dup_pass], require_provenance=True)
        self.assertEqual(dup_ctx.exception.code, "DUPLICATE_SAMPLE_KEY")

        # Concurrent lock refusal
        with tempfile.TemporaryDirectory() as tmp:
            ev_dir = Path(tmp) / "locked"
            ev_dir.mkdir()
            with prov.EvidenceDirLock(ev_dir, run_id="run-0001"):
                with self.assertRaises(prov.EvidenceProvenanceError) as lock_ctx:
                    with prov.EvidenceDirLock(ev_dir, run_id="run-0002"):
                        pass
                self.assertEqual(lock_ctx.exception.code, "EVIDENCE_DIR_LOCKED")


class _EndToEndFakeResult:
    def __init__(self, stdout: str = "", stderr: str = "", returncode: int = 0) -> None:
        self.stdout = stdout
        self.stderr = stderr
        self.returncode = returncode


class _EndToEndFakeAdb:
    """Fake ADB client exercising full run_w0105_full_validation.run_cli call order."""

    def __init__(
        self,
        *,
        app_sha: str,
        test_sha: str,
        staged_files: dict[str, str],
        qa_package: str = "io.github.nyzeep.orchestella.qa.w0105",
        signature_conflict: bool = False,
    ) -> None:
        self.calls: list[tuple[str, ...]] = []
        self.qa_package = qa_package
        self.test_package = f"{qa_package}.test"
        self.staged_files = dict(staged_files)
        self.signature_conflict = signature_conflict
        self.packages: dict[str, dict[str, str]] = {
            "io.github.nyzeep.orchestella": {
                "path": "/data/app/~~rel/io.github.nyzeep.orchestella/base.apk",
                "sha256": "1" * 64,
                "version_name": "0.6.3",
            },
            "io.github.nyzeep.orchestella.dev": {
                "path": "/data/app/~~dev/io.github.nyzeep.orchestella.dev/base.apk",
                "sha256": "2" * 64,
                "version_name": "0.6.3-dev.4cb8081",
            },
            "io.github.nyzeep.orchestella.qa.w305": {
                "path": "/data/app/~~w305/io.github.nyzeep.orchestella.qa.w305/base.apk",
                "sha256": "3" * 64,
                "version_name": "0.6.3-qa.w305.4cb8081",
            },
            qa_package: {
                "path": f"/data/app/~~qa/{qa_package}/base.apk",
                "sha256": app_sha,
                "version_name": f"0.6.3-qa.{qa_package.split('.')[-1]}.4cb8081",
            },
            self.test_package: {
                "path": f"/data/app/~~qatest/{self.test_package}/base.apk",
                "sha256": test_sha,
                "version_name": "",
            },
        }

    def write_calls(self) -> list[tuple[str, ...]]:
        out: list[tuple[str, ...]] = []
        for c in self.calls:
            if c[0] in ("install", "uninstall", "push"):
                out.append(c)
            elif c[0] == "shell" and len(c) > 1:
                joined = " ".join(c[1:])
                if any(tok in joined for tok in ("rm ", "mkdir ", "cp ", "chmod ", "am instrument", "force-stop")):
                    out.append(c)
        return out

    def run(self, *args: str, check: bool = True, timeout: int = 120) -> _EndToEndFakeResult:
        self.calls.append(args)
        if args == ("shell", "getconf", "PAGESIZE"):
            return _EndToEndFakeResult("4096\n")
        if args[:2] == ("shell", "getprop"):
            prop = args[2]
            vals = {
                "ro.build.version.release": "15",
                "ro.build.version.sdk": "35",
                "ro.product.cpu.abi": "arm64-v8a",
                "ro.kernel.qemu": "0",
                "ro.boot.qemu": "0",
            }
            return _EndToEndFakeResult(vals.get(prop, "") + "\n")
        if args == ("shell", "uname", "-r"):
            return _EndToEndFakeResult("5.10.236\n")
        if args == ("shell", "nproc"):
            return _EndToEndFakeResult("6\n")
        if args == ("shell", "cat", "/proc/meminfo"):
            return _EndToEndFakeResult("MemTotal: 11567512 kB\n")
        if args == ("shell", "dumpsys", "battery"):
            return _EndToEndFakeResult("temperature: 290\n")
        if args[:3] == ("shell", "pm", "list") and args[3] == "packages":
            return _EndToEndFakeResult("\n".join(f"package:{p}" for p in sorted(self.packages)) + "\n")
        if args[:3] == ("shell", "pm", "path"):
            pkg = args[3]
            if pkg in self.packages:
                return _EndToEndFakeResult(f"package:{self.packages[pkg]['path']}\n")
            return _EndToEndFakeResult("", returncode=1)
        if args[:3] == ("shell", "dumpsys", "package"):
            pkg = args[3]
            if pkg in self.packages:
                vn = self.packages[pkg]["version_name"]
                return _EndToEndFakeResult(f"Package [{pkg}] (abc):\n  versionName={vn}\n  versionCode=603 minSdk=26 targetSdk=36\n")
            return _EndToEndFakeResult("")
        if args[:2] == ("shell", "sha256sum"):
            p = args[2]
            for info in self.packages.values():
                if info["path"] == p:
                    return _EndToEndFakeResult(f"{info['sha256']}  {p}\n")
            return _EndToEndFakeResult("No such file", returncode=1)
        if args == ("shell", "pm", "list", "instrumentation"):
            return _EndToEndFakeResult(
                f"instrumentation:{self.test_package}/io.github.nyzeep.orchestella.W0105FullValidationProbe (target={self.qa_package})\n"
            )
        if args[:2] == ("shell", "run-as") and len(args) >= 4 and args[3] == "sha256sum":
            rel = args[4]
            if rel in self.staged_files:
                return _EndToEndFakeResult(f"{self.staged_files[rel]}  {rel}\n")
            return _EndToEndFakeResult("No such file", returncode=1)
        if args[0] == "uninstall":
            pkg = args[1]
            if pkg in self.packages:
                del self.packages[pkg]
                return _EndToEndFakeResult("Success\n")
            return _EndToEndFakeResult("Failure\n", returncode=1)
        if args[:3] == ("install", "-r", "-t"):
            if self.signature_conflict:
                return _EndToEndFakeResult("", "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]", returncode=1)
            p = Path(args[3])
            digest = prov.sha256_file(p)
            is_test = "test" in p.name.lower()
            pkg = self.test_package if is_test else self.qa_package
            self.packages[pkg] = {
                "path": f"/data/app/~~new/{pkg}/base.apk",
                "sha256": digest,
                "version_name": "" if is_test else f"0.6.3-qa.{self.qa_package.split('.')[-1]}.4cb8081",
            }
            return _EndToEndFakeResult("Success\n")
        if args[:2] == ("shell", "ps"):
            return _EndToEndFakeResult("PID NAME ARGS\n")
        if args[:3] == ("shell", "am", "instrument"):
            action = args[args.index("action") + 1]
            return _EndToEndFakeResult(
                f"INSTRUMENTATION_STATUS: w0105_stage={action}\n"
                f"INSTRUMENTATION_RESULT: w0105_status=PASS\n"
                f"INSTRUMENTATION_RESULT: w0105_summary={action}_ok\n"
                "INSTRUMENTATION_CODE: -1\n"
            )
        return _EndToEndFakeResult("")


class TestRunnerEndToEndAndGateIntegrity(unittest.TestCase):
    def _create_workspace_fixtures(self, tmp_path: Path, *, purpose: str = "w0105") -> dict[str, Any]:
        app_apk = tmp_path / "app-debug-fullpkg.apk"
        test_apk = tmp_path / "app-debug-androidTest.apk"
        with zipfile.ZipFile(app_apk, "w") as zf:
            zf.writestr("lib/arm64-v8a/libuml_kernel_full.so", b"\x7fELF" + b"\x00" * 16)
            zf.writestr("lib/arm64-v8a/libuml_stub_full.so", b"\x7fELF" + b"\x01" * 16)
            zf.writestr("lib/arm64-v8a/libuml_net.so", b"\x7fELF" + b"\x02" * 16)
            zf.writestr("lib/arm64-v8a/libuml_passt.so", b"\x7fELF" + b"\x03" * 16)
            zf.writestr("initramfs.cpio.gz", b"initramfs")
        with zipfile.ZipFile(test_apk, "w") as zf:
            zf.writestr("AndroidManifest.xml", b"test-manifest")

        debian_tar = tmp_path / "debian-13-rootfs.tar.xz"
        debian_tar.write_bytes(b"debian-rootfs-bytes")
        orig_sha = w0105.DEBIAN_ROOTFS_SHA256
        w0105.DEBIAN_ROOTFS_SHA256 = prov.sha256_file(debian_tar)

        fixtures_dir = tmp_path / "fixtures"
        apt_dir = fixtures_dir / "apt-repo" / "dists" / "trixie"
        apt_dir.mkdir(parents=True)
        base_img = fixtures_dir / "base-image-arm64.tar"
        base_img.write_bytes(b"base-image-bytes")
        deb_file = fixtures_dir / "apt-repo" / "sample.deb"
        deb_file.write_bytes(b"deb-bytes")
        (fixtures_dir / "apt-repo" / "qa-w0104-archive.gpg").write_bytes(b"gpg")
        (apt_dir / "InRelease").write_bytes(b"inrelease")
        lock_obj = {
            "base_image": {"tar_sha256": prov.sha256_file(base_img)},
            "packages": [{"filename": "sample.deb", "sha256": prov.sha256_file(deb_file)}],
        }
        (fixtures_dir / "w0104-lock.json").write_text(json.dumps(lock_obj), encoding="utf-8")

        app_sha = prov.sha256_file(app_apk)
        test_sha = prov.sha256_file(test_apk)
        app_info = {
            "name": app_apk.name,
            "size_bytes": app_apk.stat().st_size,
            "sha256": app_sha,
            "application_id": f"io.github.nyzeep.orchestella.qa.{purpose}",
            "version_name": f"0.6.3-qa.{purpose}.4cb8081",
            "version_code": 603,
            "min_sdk": 26,
            "target_sdk": 36,
            "debuggable": True,
            "abis": ["arm64-v8a"],
            "instrumentations": [],
            "apksigner": {
                "passed": True,
                "signer_count": 1,
                "signer_cert_sha256s": ["3" * 64],
                "lines": [],
            },
        }
        test_info = {
            "name": test_apk.name,
            "size_bytes": test_apk.stat().st_size,
            "sha256": test_sha,
            "application_id": f"io.github.nyzeep.orchestella.qa.{purpose}.test",
            "version_name": "",
            "version_code": None,
            "min_sdk": 26,
            "target_sdk": 36,
            "debuggable": True,
            "abis": [],
            "instrumentations": [{
                "name": "io.github.nyzeep.orchestella.W0105FullValidationProbe",
                "target_package": f"io.github.nyzeep.orchestella.qa.{purpose}",
            }],
            "apksigner": {
                "passed": True,
                "signer_count": 1,
                "signer_cert_sha256s": ["3" * 64],
                "lines": [],
            },
        }
        inputs_prov = prov.build_inputs_provenance(debian_tar, fixtures_dir, lock_obj)
        return {
            "app_apk": app_apk,
            "test_apk": test_apk,
            "debian_tar": debian_tar,
            "fixtures_dir": fixtures_dir,
            "app_info": app_info,
            "test_info": test_info,
            "staged_files": inputs_prov["expected_staged_files"],
            "orig_debian_sha": orig_sha,
        }

    def test_preflight_apk_mismatch_causes_zero_adb_writes_and_dry_run_preserves_packages(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            fx = self._create_workspace_fixtures(tmp_path)
            try:
                ev_dir = tmp_path / "ev"
                fake_adb = _EndToEndFakeAdb(
                    app_sha=fx["app_info"]["sha256"],
                    test_sha=fx["test_info"]["sha256"],
                    staged_files=fx["staged_files"],
                )
                bad_app = {**fx["app_info"], "application_id": "io.github.nyzeep.orchestella"}
                stdout_buf = io.StringIO()
                stderr_buf = io.StringIO()
                with contextlib.redirect_stdout(stdout_buf), contextlib.redirect_stderr(stderr_buf):
                    rc = w0105.run_cli(
                        [
                            "--app-apk", str(fx["app_apk"]),
                            "--test-apk", str(fx["test_apk"]),
                            "--debian-tar", str(fx["debian_tar"]),
                            "--fixtures-dir", str(fx["fixtures_dir"]),
                            "--evidence-dir", str(ev_dir),
                            "--mode", "smoke",
                        ],
                        adb_factory=lambda _serial: fake_adb,
                        device_resolver=lambda _adb, _req: "fake-serial",
                        apk_inspector=lambda p, **_: bad_app if p == fx["app_apk"] else fx["test_info"],
                        payload_gate_runner=lambda *_: (True, {"status": "ok"}, {"status": "ok"}),
                        apk_16kb_verifier=lambda *_a, **_k: {"passed": True, "so_count": 4},
                    )
                self.assertNotEqual(rc, 0)
                self.assertEqual(fake_adb.calls, [])

                # Dry-run on multi-package fake ADB writes zero times and prints preserved packages
                stdout_buf = io.StringIO()
                with contextlib.redirect_stdout(stdout_buf):
                    rc_dry = w0105.run_cli(
                        [
                            "--app-apk", str(fx["app_apk"]),
                            "--test-apk", str(fx["test_apk"]),
                            "--debian-tar", str(fx["debian_tar"]),
                            "--fixtures-dir", str(fx["fixtures_dir"]),
                            "--evidence-dir", str(ev_dir),
                            "--mode", "smoke",
                            "--dry-run",
                        ],
                        adb_factory=lambda _serial: fake_adb,
                        device_resolver=lambda _adb, _req: "fake-serial",
                        apk_inspector=lambda p, **_: fx["app_info"] if p == fx["app_apk"] else fx["test_info"],
                        payload_gate_runner=lambda *_: (True, {"status": "ok"}, {"status": "ok"}),
                        apk_16kb_verifier=lambda *_a, **_k: {"passed": True, "so_count": 4},
                    )
                self.assertEqual(rc_dry, 0)
                self.assertEqual(fake_adb.write_calls(), [])
                dry_out = json.loads(stdout_buf.getvalue())
                self.assertTrue(dry_out["dry_run"])
                self.assertIn("io.github.nyzeep.orchestella", dry_out["preserved_other_packages"])
                self.assertFalse((ev_dir / "ledger.jsonl").exists())
            finally:
                w0105.DEBIAN_ROOTFS_SHA256 = fx["orig_debian_sha"]

    def test_end_to_end_smoke_install_resume_and_manifest_preservation(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            fx = self._create_workspace_fixtures(tmp_path)
            try:
                ev_dir = tmp_path / "ev"
                fake_adb = _EndToEndFakeAdb(
                    app_sha="0" * 64,
                    test_sha="0" * 64,
                    staged_files=fx["staged_files"],
                )
                stdout_buf = io.StringIO()
                with contextlib.redirect_stdout(stdout_buf):
                    rc = w0105.run_cli(
                        [
                            "--app-apk", str(fx["app_apk"]),
                            "--test-apk", str(fx["test_apk"]),
                            "--debian-tar", str(fx["debian_tar"]),
                            "--fixtures-dir", str(fx["fixtures_dir"]),
                            "--evidence-dir", str(ev_dir),
                            "--mode", "smoke",
                        ],
                        adb_factory=lambda _serial: fake_adb,
                        device_resolver=lambda _adb, _req: "fake-serial",
                        apk_inspector=lambda p, **_: fx["app_info"] if p == fx["app_apk"] else fx["test_info"],
                        payload_gate_runner=lambda *_: (True, {"status": "ok"}, {"status": "ok"}),
                        apk_16kb_verifier=lambda *_a, **_k: {"passed": True, "so_count": 4},
                    )
                self.assertEqual(rc, 1)  # smoke only -> INCOMPLETE (debian_full_chain/fault missing)
                self.assertFalse(any(c[0] == "uninstall" for c in fake_adb.calls))
                manifest_path = ev_dir / "candidate-manifest.json"
                manifest_bytes = manifest_path.read_bytes()

                # Resume with --skip-install: preserves candidate-manifest.json bytes and logs action
                fake_adb_skip = _EndToEndFakeAdb(
                    app_sha=fx["app_info"]["sha256"],
                    test_sha=fx["test_info"]["sha256"],
                    staged_files=fx["staged_files"],
                )
                stdout_buf = io.StringIO()
                with contextlib.redirect_stdout(stdout_buf):
                    rc_res = w0105.run_cli(
                        [
                            "--app-apk", str(fx["app_apk"]),
                            "--test-apk", str(fx["test_apk"]),
                            "--debian-tar", str(fx["debian_tar"]),
                            "--fixtures-dir", str(fx["fixtures_dir"]),
                            "--evidence-dir", str(ev_dir),
                            "--mode", "smoke",
                            "--resume",
                            "--skip-install",
                        ],
                        adb_factory=lambda _serial: fake_adb_skip,
                        device_resolver=lambda _adb, _req: "fake-serial",
                        apk_inspector=lambda p, **_: fx["app_info"] if p == fx["app_apk"] else fx["test_info"],
                        payload_gate_runner=lambda *_: (True, {"status": "ok"}, {"status": "ok"}),
                        apk_16kb_verifier=lambda *_a, **_k: {"passed": True, "so_count": 4},
                    )
                self.assertEqual(rc_res, 1)
                self.assertEqual(manifest_path.read_bytes(), manifest_bytes)
                self.assertFalse(any(c[0] in ("install", "uninstall", "push") for c in fake_adb_skip.calls))
                actions = prov.load_jsonl_strict(ev_dir / "run-actions.jsonl")
                self.assertEqual([a["action"] for a in actions], ["fresh", "resume"])
                findings = check_sensitive_data.scan_text_lines(manifest_path.read_text(encoding="utf-8").splitlines())
                self.assertEqual(findings, {})
            finally:
                w0105.DEBIAN_ROOTFS_SHA256 = fx["orig_debian_sha"]

    def test_signature_conflict_clean_install_and_retry_supersession_e2e(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            fx = self._create_workspace_fixtures(tmp_path, purpose="w0203")
            try:
                ev_dir = tmp_path / "ev_retry"
                # 1. Signature conflict aborts incremental install with zero uninstalls
                adb_conflict = _EndToEndFakeAdb(
                    app_sha="0" * 64,
                    test_sha="0" * 64,
                    staged_files=fx["staged_files"],
                    qa_package="io.github.nyzeep.orchestella.qa.w0203",
                    signature_conflict=True,
                )
                stderr_buf = io.StringIO()
                with contextlib.redirect_stderr(stderr_buf):
                    rc_conf = w0105.run_cli(
                        [
                            "--qa-purpose", "w0203",
                            "--app-apk", str(fx["app_apk"]),
                            "--test-apk", str(fx["test_apk"]),
                            "--debian-tar", str(fx["debian_tar"]),
                            "--fixtures-dir", str(fx["fixtures_dir"]),
                            "--evidence-dir", str(ev_dir),
                            "--mode", "smoke",
                        ],
                        adb_factory=lambda _serial: adb_conflict,
                        device_resolver=lambda _adb, _req: "fake-serial",
                        apk_inspector=lambda p, **_: fx["app_info"] if p == fx["app_apk"] else fx["test_info"],
                        payload_gate_runner=lambda *_: (True, {"status": "ok"}, {"status": "ok"}),
                        apk_16kb_verifier=lambda *_a, **_k: {"passed": True, "so_count": 4},
                    )
                self.assertEqual(rc_conf, 2)
                self.assertIn("INSTALL_SIGNATURE_CONFLICT", stderr_buf.getvalue())
                self.assertFalse(any(c[0] == "uninstall" for c in adb_conflict.calls))

                # 2. Explicit --clean-install on fresh subdir uninstalls only exact w0203 pair,
                #    and first run fails normal_entry_check; second run --resume retries and supersedes it.
                ev_dir2 = tmp_path / "ev_retry2"

                class _FlakyInstrumentFakeAdb(_EndToEndFakeAdb):
                    fail_normal_entry = True

                    def run(self, *args: str, check: bool = True, timeout: int = 120) -> _EndToEndFakeResult:
                        if args[:3] == ("shell", "am", "instrument"):
                            self.calls.append(args)
                            action = args[args.index("action") + 1]
                            if action == "normal_entry_check" and self.fail_normal_entry:
                                return _EndToEndFakeResult(
                                    "INSTRUMENTATION_STATUS: w0105_stage=normal_entry_check\n"
                                    "INSTRUMENTATION_RESULT: w0105_status=FAIL\n"
                                    "INSTRUMENTATION_RESULT: w0105_error=transient_ui_timeout\n"
                                    "INSTRUMENTATION_CODE: 0\n"
                                )
                            return _EndToEndFakeResult(
                                f"INSTRUMENTATION_STATUS: w0105_stage={action}\n"
                                f"INSTRUMENTATION_RESULT: w0105_status=PASS\n"
                                f"INSTRUMENTATION_RESULT: w0105_summary={action}_ok\n"
                                "INSTRUMENTATION_CODE: -1\n"
                            )
                        return super().run(*args, check=check, timeout=timeout)

                adb_flaky = _FlakyInstrumentFakeAdb(
                    app_sha="0" * 64,
                    test_sha="0" * 64,
                    staged_files=fx["staged_files"],
                    qa_package="io.github.nyzeep.orchestella.qa.w0203",
                )
                with contextlib.redirect_stdout(io.StringIO()):
                    rc1 = w0105.run_cli(
                        [
                            "--qa-purpose", "w0203",
                            "--clean-install",
                            "--app-apk", str(fx["app_apk"]),
                            "--test-apk", str(fx["test_apk"]),
                            "--debian-tar", str(fx["debian_tar"]),
                            "--fixtures-dir", str(fx["fixtures_dir"]),
                            "--evidence-dir", str(ev_dir2),
                            "--mode", "smoke",
                        ],
                        adb_factory=lambda _serial: adb_flaky,
                        device_resolver=lambda _adb, _req: "fake-serial",
                        apk_inspector=lambda p, **_: fx["app_info"] if p == fx["app_apk"] else fx["test_info"],
                        payload_gate_runner=lambda *_: (True, {"status": "ok"}, {"status": "ok"}),
                        apk_16kb_verifier=lambda *_a, **_k: {"passed": True, "so_count": 4},
                    )
                self.assertEqual(rc1, 1)
                self.assertEqual(
                    [c for c in adb_flaky.calls if c[0] == "uninstall"],
                    [
                        ("uninstall", "io.github.nyzeep.orchestella.qa.w0203.test"),
                        ("uninstall", "io.github.nyzeep.orchestella.qa.w0203"),
                    ],
                )
                self.assertIn("io.github.nyzeep.orchestella", adb_flaky.packages)
                self.assertIn("io.github.nyzeep.orchestella.dev", adb_flaky.packages)
                self.assertIn("io.github.nyzeep.orchestella.qa.w305", adb_flaky.packages)

                adb_flaky.fail_normal_entry = False
                with contextlib.redirect_stdout(io.StringIO()):
                    rc2 = w0105.run_cli(
                        [
                            "--qa-purpose", "w0203",
                            "--skip-install",
                            "--resume",
                            "--app-apk", str(fx["app_apk"]),
                            "--test-apk", str(fx["test_apk"]),
                            "--debian-tar", str(fx["debian_tar"]),
                            "--fixtures-dir", str(fx["fixtures_dir"]),
                            "--evidence-dir", str(ev_dir2),
                            "--mode", "smoke",
                        ],
                        adb_factory=lambda _serial: adb_flaky,
                        device_resolver=lambda _adb, _req: "fake-serial",
                        apk_inspector=lambda p, **_: fx["app_info"] if p == fx["app_apk"] else fx["test_info"],
                        payload_gate_runner=lambda *_: (True, {"status": "ok"}, {"status": "ok"}),
                        apk_16kb_verifier=lambda *_a, **_k: {"passed": True, "so_count": 4},
                    )
                self.assertEqual(rc2, 1)
                rows = prov.load_jsonl_strict(ev_dir2 / "ledger.jsonl")
                self.assertEqual(len(rows), 3)
                self.assertEqual(rows[1]["status"], "FAIL")
                self.assertEqual(rows[2]["status"], "PASS")
                self.assertEqual(rows[2]["attempt_index"], 2)
                self.assertEqual(rows[2]["supersedes_run_id"], "run-0001")
                self.assertEqual(rows[2]["supersedes_row_index"], 1)
                summary = json.loads((ev_dir2 / "summary.json").read_text(encoding="utf-8"))
                ne = summary["by_page_size"]["4096"]["functional_checks"]["normal_entry_check"]
                self.assertEqual(ne["status"], "PASS")
                self.assertEqual(ne["fail_attempts"], 1)
                self.assertTrue(ne["reran_after_fail"])
            finally:
                w0105.DEBIAN_ROOTFS_SHA256 = fx["orig_debian_sha"]

    def test_evaluate_w0105_gate_handles_missing_only_rows_and_cross_page_func_fails(self) -> None:
        missing_only = [{"record_type": "perf_sample", "host_page_size": 4096, "status": "MISSING"}]
        s_miss = w0105.evaluate_w0105_gate(missing_only, apk_verification={"passed": True}, payload_gate_ok=True)
        self.assertEqual(s_miss["wave_receipt"]["historical_fail_attempts"], 0)

        rows = (
            [{"record_type": "functional_check", "run_id": "run-0001", "sample_id": "p4096-func-preflight", "check_name": "preflight", "host_page_size": 4096, "device_alias": "device-4k", "status": "FAIL", "attempts": 1, "residual_process_count": 0}]
            + _passing_functional_rows(4096)
            + [{"record_type": "functional_check", "run_id": "run-0001", "sample_id": "p16384-func-preflight", "check_name": "preflight", "host_page_size": 16384, "device_alias": "device-16k", "status": "FAIL", "attempts": 1, "residual_process_count": 0}]
            + _passing_functional_rows(16384)
        )
        s_both = w0105.evaluate_w0105_gate(rows, apk_verification={"passed": True}, payload_gate_ok=True)
        self.assertEqual(s_both["wave_receipt"]["historical_fail_attempts"], 2)

    def test_dry_run_resume_on_missing_dir_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            fx = self._create_workspace_fixtures(tmp_path)
            try:
                fake_adb = _EndToEndFakeAdb(
                    app_sha=fx["app_info"]["sha256"],
                    test_sha=fx["test_info"]["sha256"],
                    staged_files=fx["staged_files"],
                )
                missing_ev = tmp_path / "does_not_exist"
                stderr_buf = io.StringIO()
                with contextlib.redirect_stderr(stderr_buf):
                    rc = w0105.run_cli(
                        [
                            "--app-apk", str(fx["app_apk"]),
                            "--test-apk", str(fx["test_apk"]),
                            "--debian-tar", str(fx["debian_tar"]),
                            "--fixtures-dir", str(fx["fixtures_dir"]),
                            "--evidence-dir", str(missing_ev),
                            "--mode", "smoke",
                            "--resume",
                            "--dry-run",
                        ],
                        adb_factory=lambda _serial: fake_adb,
                        device_resolver=lambda _adb, _req: "fake-serial",
                        apk_inspector=lambda p, **_: fx["app_info"] if p == fx["app_apk"] else fx["test_info"],
                        payload_gate_runner=lambda *_: (True, {"status": "ok"}, {"status": "ok"}),
                        apk_16kb_verifier=lambda *_a, **_k: {"passed": True, "so_count": 4},
                    )
                self.assertEqual(rc, 2)
                self.assertIn("RESUME_MANIFEST_MISSING", stderr_buf.getvalue())
                self.assertFalse(missing_ev.exists())
            finally:
                w0105.DEBIAN_ROOTFS_SHA256 = fx["orig_debian_sha"]


if __name__ == "__main__":
    unittest.main()
