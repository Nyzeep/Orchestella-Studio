#!/usr/bin/env python3
"""Host orchestrator for W01.05 4 KB full-chain regression & launch performance validation."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, os.path.dirname(__file__))
import evidence_provenance as prov  # noqa: E402
import qa_package_guard as qa_guard  # noqa: E402
import run_w305_perf_bench as bench  # noqa: E402

DEFAULT_QA_PURPOSE = "w0105"
INSTRUMENTATION_CLASS = "io.github.nyzeep.orchestella.W0105FullValidationProbe"
QA_PACKAGE = "io.github.nyzeep.orchestella.qa.w0105"
INSTRUMENTATION = f"{QA_PACKAGE}.test/{INSTRUMENTATION_CLASS}"

DEBIAN_ROOTFS_TAR = "debian-13-rootfs.tar.xz"
DEBIAN_ROOTFS_SHA256 = "e4d504e13e43a384227639b74391ef164a67d916aa8f8a9871ecd73dc577db91"

PAGE_SIZE_ALIASES = dict(bench.PAGE_SIZE_ALIASES)
DEFAULT_MAX_TEMP_C = 42.0
DEFAULT_DEBIAN_SAMPLES = 30
DEFAULT_ALPINE_PAIRS = 30
DEFAULT_RECONNECT_SAMPLES = 30
DEFAULT_QEMU_REF_SAMPLES = 3

RESIDUAL_PROCESS_TOKENS: tuple[str, ...] = bench.RESIDUAL_PROCESS_TOKENS + (
    "libuml_kernel_full.so",
    "libuml_stub_full.so",
)

REQUIRED_FUNCTIONAL_CHECKS: tuple[str, ...] = (
    "preflight",
    "normal_entry_check",
    "debian_full_chain",
    "fault_and_compat",
)

DEBIAN_CLOCK_WINDOWS: tuple[tuple[str, str], ...] = (
    ("bridge_ready", "t0_to_bridge_ready_ms"),
    ("systemd_ready", "t0_to_systemd_ready_ms"),
    ("shell_ready", "t0_to_shell_ready_ms"),
    ("first_cmd_echo", "t0_to_first_cmd_echo_ms"),
    ("docker_daemon_ready", "t0_to_docker_daemon_ready_ms"),
    ("container_http_ready", "t0_to_container_http_ready_ms"),
    ("reattach", "reattach_ms"),
    ("roundtrip", "roundtrip_ms"),
)

DEBIAN_BUCKETS: tuple[str, ...] = (
    "first_provision",
    "cold_provisioned",
    "warm_restart",
    "warm_reconnect",
)

ALPINE_PAIRED_BUCKETS: tuple[str, ...] = (
    "cold_provisioned",
    "warm_restart",
    "warm_reconnect",
)

# Re-export reused helpers for clean module seam.
utc_now_iso = bench.utc_now_iso
sanitize_text = bench.sanitize_text
validate_device_alias = bench.validate_device_alias
validate_host_page_size = bench.validate_host_page_size
load_ledger = prov.load_jsonl_strict
next_run_id = prov.next_run_id
append_ledger_records = prov.append_jsonl_atomic
nearest_rank_percentile = bench.nearest_rank_percentile
compute_p95_speedup_ratio = bench.compute_p95_speedup_ratio
clean_uninstall_orchestella_packages = qa_guard.clean_uninstall_qa_target
read_battery_temperature_c = bench.read_battery_temperature_c
wait_for_thermal_cooldown = bench.wait_for_thermal_cooldown
verify_apk_16kb = bench.verify_apk_16kb
parse_dumpsys_package_meta = bench.parse_dumpsys_package_meta
sha256_file = prov.sha256_file


def find_residual_uml_processes(ps_output: str) -> list[dict[str, Any]]:
    matches: list[dict[str, Any]] = []
    for raw in ps_output.splitlines():
        line = raw.strip()
        if not line:
            continue
        for token in RESIDUAL_PROCESS_TOKENS:
            if token in line:
                parts = line.split()
                pid = None
                for part in parts:
                    if part.isdigit():
                        pid = int(part)
                        break
                matches.append(
                    {
                        "pid": pid,
                        "token": token,
                        "summary": sanitize_text(" ".join(parts[:4]), max_len=120),
                    }
                )
                break
    return matches


def paired_profile_order(pair_index: int) -> tuple[str, str]:
    if pair_index < 1:
        raise ValueError(f"pair_index must be >= 1, got {pair_index}")
    return ("fast", "full") if pair_index % 2 == 1 else ("full", "fast")


def prepare_with_single_retry(run_once_fn: Any) -> bool:
    """Runs a preparation step up to two attempts; returns True on PASS."""
    for attempt in range(2):
        res = run_once_fn(attempt)
        if res.get("status") == "PASS":
            return True
    return False


def build_cold_warm_block_schedule(total_count: int, block_size: int = 5) -> list[tuple[str, list[int]]]:
    if total_count < 1 or block_size < 1:
        raise ValueError("total_count and block_size must be >= 1")
    schedule: list[tuple[str, list[int]]] = []
    block_idx = 0
    for start in range(1, total_count + 1, block_size):
        block_idx += 1
        end = min(total_count, start + block_size - 1)
        indices = list(range(start, end + 1))
        if block_idx % 2 == 1:
            schedule.append(("cold_provisioned", indices))
            schedule.append(("warm_restart_batch", indices))
        else:
            schedule.append(("warm_restart_batch", indices))
            schedule.append(("cold_provisioned", indices))
    return schedule


def _summarize_numeric_values(values: list[int | float]) -> dict[str, Any]:
    if not values:
        return {
            "count": 0,
            "min_ms": None,
            "p50_ms": None,
            "p95_ms": None,
            "max_ms": None,
            "mean_ms": None,
        }
    ordered = sorted(values)
    n = len(ordered)
    return {
        "count": n,
        "min_ms": ordered[0],
        "p50_ms": nearest_rank_percentile(ordered, 0.50),
        "p95_ms": nearest_rank_percentile(ordered, 0.95),
        "max_ms": ordered[-1],
        "mean_ms": round(sum(ordered) / n, 2),
    }


def _collect_window_stats(rows: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    windows: dict[str, dict[str, Any]] = {}
    for win_name, field_key in DEBIAN_CLOCK_WINDOWS:
        vals: list[int | float] = []
        for row in rows:
            v = row.get(field_key)
            if isinstance(v, (int, float)) and not isinstance(v, bool) and v >= 0:
                vals.append(v)
        windows[win_name] = _summarize_numeric_values(vals)
    return windows


def _extract_workload_signature(rows: list[dict[str, Any]]) -> tuple[str | None, str | None, int | None, int | None]:
    for row in rows:
        return (
            row.get("distro"),
            row.get("network_mode"),
            row.get("ram_mb"),
            row.get("vcpu"),
        )
    return (None, None, None, None)


def compute_equivalent_comparison(
    *,
    baseline_rows: list[dict[str, Any]],
    candidate_rows: list[dict[str, Any]],
    metric_key: str = "t0_to_shell_ready_ms",
) -> dict[str, Any]:
    base_sig = _extract_workload_signature(baseline_rows)
    cand_sig = _extract_workload_signature(candidate_rows)
    if base_sig != cand_sig:
        raise ValueError(
            f"Non-equivalent workload comparison refused: baseline={base_sig} vs candidate={cand_sig}"
        )
    base_vals = [
        r[metric_key]
        for r in baseline_rows
        if r.get("status") == "PASS"
        and isinstance(r.get(metric_key), (int, float))
        and not isinstance(r.get(metric_key), bool)
    ]
    cand_vals = [
        r[metric_key]
        for r in candidate_rows
        if r.get("status") == "PASS"
        and isinstance(r.get(metric_key), (int, float))
        and not isinstance(r.get(metric_key), bool)
    ]
    base_p95 = nearest_rank_percentile(base_vals, 0.95)
    cand_p95 = nearest_rank_percentile(cand_vals, 0.95)
    speedup = compute_p95_speedup_ratio(cand_p95, base_p95)
    return {
        "equivalent_workload": True,
        "signature": {
            "distro": base_sig[0],
            "network_mode": base_sig[1],
            "ram_mb": base_sig[2],
            "vcpu": base_sig[3],
        },
        "baseline_p95_ms": base_p95,
        "candidate_p95_ms": cand_p95,
        "p95_speedup_ratio": round(speedup, 8) if speedup is not None else None,
    }


def compute_debian_bucket_stats(
    records: list[dict[str, Any]],
    bucket: str,
    *,
    host_page_size: int | None = None,
    suite: str = "debian_full",
    require_provenance: bool = False,
) -> dict[str, Any]:
    selection = prov.validate_and_select_ledger(records, require_provenance=require_provenance)
    scoped = [
        r
        for r in records
        if r.get("record_type") == "perf_sample"
        and r.get("suite") == suite
        and r.get("bucket") == bucket
        and (host_page_size is None or r.get("host_page_size") == host_page_size)
    ]
    pass_count = 0
    fail_count = 0
    timeout_count = 0
    unexplained_timeout_count = 0
    failure_reasons: dict[str, int] = {}
    temps: list[float] = []
    residual_events = 0

    for row in scoped:
        st = row.get("status")
        if (row.get("residual_process_count") or 0) > 0:
            residual_events += 1
        for tk in ("temp_c_before", "temp_c_after"):
            tv = row.get(tk)
            if isinstance(tv, (int, float)) and not isinstance(tv, bool):
                temps.append(float(tv))

        if st == "PASS":
            pass_count += 1
        elif st == "TIMEOUT":
            timeout_count += 1
            if not bool(row.get("timeout_explained", False)):
                unexplained_timeout_count += 1
            reason = sanitize_text(row.get("failure_reason") or "timeout", max_len=120) or "timeout"
            failure_reasons[reason] = failure_reasons.get(reason, 0) + 1
        elif st == "FAIL":
            fail_count += 1
            reason = sanitize_text(row.get("failure_reason") or row.get("error") or "fail", max_len=120) or "fail"
            failure_reasons[reason] = failure_reasons.get(reason, 0) + 1

    valid_rows = [
        row
        for skey, row in selection["passed_by_key"].items()
        if skey[0] == "perf_sample"
        and skey[1] == suite
        and skey[2] == bucket
        and (host_page_size is None or row.get("host_page_size") == host_page_size)
    ]
    total_attempts = pass_count + fail_count + timeout_count
    failure_rate = ((fail_count + timeout_count) / total_attempts) if total_attempts > 0 else 0.0

    return {
        "suite": suite,
        "bucket": bucket,
        "host_page_size": host_page_size,
        "percentile_formula": bench.PERCENTILE_FORMULA,
        "valid_pass_count": len(valid_rows),
        "pass_count": pass_count,
        "historical_fail_count": fail_count,
        "historical_timeout_count": timeout_count,
        "unexplained_timeout_count": unexplained_timeout_count,
        "total_attempts": total_attempts,
        "failure_rate": round(failure_rate, 6),
        "failure_reasons": dict(sorted(failure_reasons.items())),
        "windows": _collect_window_stats(valid_rows),
        "residual_process_events": residual_events,
        "thermal": {
            "sample_count": len(temps),
            "min_c": min(temps) if temps else None,
            "max_c": max(temps) if temps else None,
        },
    }


def compute_paired_variant_stats(
    records: list[dict[str, Any]],
    bucket: str,
    *,
    host_page_size: int | None = None,
    require_provenance: bool = False,
) -> dict[str, Any]:
    selection = prov.validate_and_select_ledger(records, require_provenance=require_provenance)
    scoped = [
        r
        for r in records
        if r.get("record_type") == "paired_sample"
        and r.get("suite") == "alpine_paired_fast_vs_full"
        and r.get("bucket") == bucket
        and (host_page_size is None or r.get("host_page_size") == host_page_size)
    ]
    by_variant_pair: dict[str, dict[int, dict[str, Any]]] = {"fast": {}, "full": {}}
    attempts_by_variant: dict[str, dict[str, int]] = {
        v: {"pass": 0, "fail": 0, "timeout": 0} for v in ("fast", "full")
    }
    unexp_by_variant: dict[str, int] = {"fast": 0, "full": 0}
    reasons_by_variant: dict[str, dict[str, int]] = {"fast": {}, "full": {}}
    temps_by_variant: dict[str, list[float]] = {"fast": [], "full": []}

    for row in scoped:
        var = row.get("variant")
        if var not in by_variant_pair:
            continue
        for tk in ("temp_c_before", "temp_c_after"):
            tv = row.get(tk)
            if isinstance(tv, (int, float)) and not isinstance(tv, bool):
                temps_by_variant[var].append(float(tv))
        st = row.get("status")
        if st == "PASS":
            attempts_by_variant[var]["pass"] += 1
        elif st == "TIMEOUT":
            attempts_by_variant[var]["timeout"] += 1
            if not bool(row.get("timeout_explained", False)):
                unexp_by_variant[var] += 1
            reason = sanitize_text(row.get("failure_reason") or "timeout", max_len=120) or "timeout"
            reasons_by_variant[var][reason] = reasons_by_variant[var].get(reason, 0) + 1
        elif st == "FAIL":
            attempts_by_variant[var]["fail"] += 1
            reason = sanitize_text(row.get("failure_reason") or row.get("error") or "fail", max_len=120) or "fail"
            reasons_by_variant[var][reason] = reasons_by_variant[var].get(reason, 0) + 1

    for skey, row in selection["passed_by_key"].items():
        if (
            skey[0] == "paired_sample"
            and skey[1] == "alpine_paired_fast_vs_full"
            and skey[2] == bucket
            and (host_page_size is None or row.get("host_page_size") == host_page_size)
        ):
            var = row.get("variant")
            p_idx = row.get("pair_index")
            if var in by_variant_pair and isinstance(p_idx, int) and p_idx >= 1:
                by_variant_pair[var][p_idx] = row

    common_pairs = sorted(set(by_variant_pair["fast"]) & set(by_variant_pair["full"]))
    fast_rows = [by_variant_pair["fast"][p] for p in common_pairs]
    full_rows = [by_variant_pair["full"][p] for p in common_pairs]
    comparison = (
        compute_equivalent_comparison(
            baseline_rows=fast_rows,
            candidate_rows=full_rows,
            metric_key="t0_to_shell_ready_ms",
        )
        if common_pairs
        else {"equivalent_workload": True, "p95_speedup_ratio": None}
    )

    def _variant_payload(var: str, valid_rows: list[dict[str, Any]]) -> dict[str, Any]:
        att = attempts_by_variant[var]
        total = att["pass"] + att["fail"] + att["timeout"]
        fail_rate = ((att["fail"] + att["timeout"]) / total) if total > 0 else 0.0
        temps = temps_by_variant[var]
        return {
            "attempts": att,
            "total_attempts": total,
            "failure_rate": round(fail_rate, 6),
            "unexplained_timeout_count": unexp_by_variant[var],
            "failure_reasons": dict(sorted(reasons_by_variant[var].items())),
            "thermal": {
                "sample_count": len(temps),
                "min_c": min(temps) if temps else None,
                "max_c": max(temps) if temps else None,
            },
            "windows": _collect_window_stats(valid_rows),
        }

    return {
        "bucket": bucket,
        "host_page_size": host_page_size,
        "paired_valid_count": len(common_pairs),
        "equivalent_workload": comparison["equivalent_workload"],
        "p95_speedup_ratio_full_vs_fast": comparison.get("p95_speedup_ratio"),
        "unexplained_timeout_count": unexp_by_variant["fast"] + unexp_by_variant["full"],
        "variants": {
            "fast": _variant_payload("fast", fast_rows),
            "full": _variant_payload("full", full_rows),
        },
    }


def evaluate_w0105_gate(
    records: list[dict[str, Any]],
    *,
    apk_verification: dict[str, Any] | None = None,
    payload_gate_ok: bool | None = None,
    compat_blocked_page_sizes: set[int] | None = None,
    min_debian_cold_warm: int = DEFAULT_DEBIAN_SAMPLES,
    min_alpine_pairs: int = DEFAULT_ALPINE_PAIRS,
    missing_reasons_by_page_size: dict[int, str] | None = None,
    require_provenance: bool = False,
) -> dict[str, Any]:
    selection = prov.validate_and_select_ledger(records, require_provenance=require_provenance)
    compat_set = compat_blocked_page_sizes or set()
    missing_overrides = missing_reasons_by_page_size or {}

    receipt_items: list[dict[str, Any]] = []
    counts = {"PASS": 0, "FAIL": 0, "MISSING": 0, "SKIPPED": 0}
    by_page_size: dict[str, dict[str, Any]] = {}
    page_verdicts: dict[int, str] = {}
    total_historical_func_fails = 0

    for ps in (4096, 16384):
        ps_rows = [r for r in records if r.get("host_page_size") == ps]
        device_rows = [r for r in ps_rows if r.get("status") != "MISSING"]
        compat_blocked = ps in compat_set

        if not device_rows and not compat_blocked:
            reason = missing_overrides.get(
                ps,
                "no-physical-16k-device-connected" if ps == 16384 else "no-physical-4k-device-connected",
            )
            page_verdicts[ps] = "MISSING"
            by_page_size[str(ps)] = {
                "host_page_size": ps,
                "device_alias": PAGE_SIZE_ALIASES[ps],
                "verdict": "MISSING",
                "reason": reason,
                "functional_checks": {},
                "debian_buckets": {},
                "alpine_paired": {},
                "qemu_ref": {},
            }
            counts["MISSING"] += 1
            receipt_items.append(
                {
                    "scope": f"host_{ps}",
                    "name": f"physical_{ps}_matrix",
                    "status": "MISSING",
                    "reason": sanitize_text(reason, max_len=120),
                }
            )
            continue

        latest_func: dict[str, dict[str, Any]] = {}
        func_attempts: dict[str, dict[str, int]] = {}
        for skey, hist in selection["history_by_key"].items():
            if skey[0] != "functional_check":
                continue
            ps_hist = [r for r in hist if r.get("host_page_size") == ps]
            if not ps_hist:
                continue
            latest_row = ps_hist[-1]
            name = str(latest_row.get("check_name") or latest_row.get("sample_id") or "unknown")
            latest_func[name] = latest_row
            bucket_cnt = func_attempts.setdefault(name, {"attempts": 0, "pass": 0, "fail": 0, "skipped": 0})
            for row in ps_hist:
                bucket_cnt["attempts"] += int(row.get("attempts") or 1)
                st = str(row.get("status") or "FAIL").upper()
                if st == "PASS":
                    bucket_cnt["pass"] += 1
                elif st == "SKIPPED":
                    bucket_cnt["skipped"] += 1
                else:
                    bucket_cnt["fail"] += 1
                    total_historical_func_fails += 1

        func_summary: dict[str, Any] = {}
        func_failed = False
        func_incomplete = False
        for req_name in REQUIRED_FUNCTIONAL_CHECKS:
            row = latest_func.get(req_name)
            if row is None:
                func_incomplete = True
                func_summary[req_name] = {"status": "MISSING", "attempts": 0}
                counts["MISSING"] += 1
                receipt_items.append({"scope": f"host_{ps}", "name": req_name, "status": "MISSING"})
            else:
                st = str(row.get("status") or "FAIL").upper()
                fail_att = func_attempts[req_name]["fail"]
                reran_after_fail = st == "PASS" and fail_att > 0
                func_summary[req_name] = {
                    "status": st,
                    "attempts": func_attempts[req_name]["attempts"],
                    "fail_attempts": fail_att,
                    "reran_after_fail": reran_after_fail,
                    "detail": sanitize_text(row.get("failure_reason") or row.get("detail"), max_len=140),
                }
                if st == "PASS":
                    counts["PASS"] += 1
                elif st == "SKIPPED":
                    counts["SKIPPED"] += 1
                    func_incomplete = True
                else:
                    counts["FAIL"] += 1
                    func_failed = True
                receipt_items.append(
                    {
                        "scope": f"host_{ps}",
                        "name": req_name,
                        "status": st,
                        "fail_attempts": fail_att,
                        "reran_after_fail": reran_after_fail,
                        "detail": sanitize_text(row.get("failure_reason") or row.get("detail"), max_len=140),
                    }
                )

        # Optional functional / reference checks (e.g. debian_bench_prepare, qemu_debian_ref)
        for name, row in sorted(latest_func.items()):
            if name in REQUIRED_FUNCTIONAL_CHECKS:
                continue
            st = str(row.get("status") or "FAIL").upper()
            fail_att = func_attempts[name]["fail"]
            reran_after_fail = st == "PASS" and fail_att > 0
            func_summary[name] = {
                "status": st,
                "attempts": func_attempts[name]["attempts"],
                "fail_attempts": fail_att,
                "reran_after_fail": reran_after_fail,
                "detail": sanitize_text(row.get("failure_reason") or row.get("detail"), max_len=140),
            }
            counts[st if st in counts else "FAIL"] += 1
            if st != "PASS" and st != "SKIPPED":
                func_failed = True
            receipt_items.append(
                {
                    "scope": f"host_{ps}",
                    "name": name,
                    "status": st,
                    "fail_attempts": fail_att,
                    "reran_after_fail": reran_after_fail,
                    "detail": sanitize_text(row.get("failure_reason") or row.get("detail"), max_len=140),
                }
            )

        debian_stats = {
            b: compute_debian_bucket_stats(records, b, host_page_size=ps, suite="debian_full", require_provenance=require_provenance)
            for b in DEBIAN_BUCKETS
        }
        alpine_stats = {
            b: compute_paired_variant_stats(records, b, host_page_size=ps, require_provenance=require_provenance)
            for b in ALPINE_PAIRED_BUCKETS
        }
        qemu_ref_stats = {
            b: compute_debian_bucket_stats(records, b, host_page_size=ps, suite="qemu_debian_ref", require_provenance=require_provenance)
            for b in ("first_provision", "cold_provisioned", "warm_restart")
        }

        latest_ps_by_sid: dict[str, dict[str, Any]] = {
            str(r.get("sample_id") or f"_row_{idx}"): r
            for idx, r in enumerate(selection["latest_by_key"].values())
            if r.get("host_page_size") == ps
        }
        historical_residual_events = sum(1 for r in ps_rows if (r.get("residual_process_count") or 0) > 0)
        unresolved_residual_events = 0
        for idx, r in enumerate(ps_rows):
            if (r.get("residual_process_count") or 0) <= 0:
                continue
            if r.get("status") == "PASS":
                unresolved_residual_events += 1
                continue
            sid = str(r.get("sample_id") or f"_row_{idx}")
            latest_r = latest_ps_by_sid.get(sid)
            if latest_r is None or latest_r.get("status") != "PASS" or (latest_r.get("residual_process_count") or 0) > 0:
                unresolved_residual_events += 1

        unexplained_timeouts = sum(
            debian_stats[b]["unexplained_timeout_count"] for b in DEBIAN_BUCKETS
        ) + sum(
            alpine_stats[b]["unexplained_timeout_count"] for b in ALPINE_PAIRED_BUCKETS
        )
        min_fp_req = 2 if min_debian_cold_warm >= 2 else 0
        fp_ok = debian_stats["first_provision"]["valid_pass_count"] >= min_fp_req
        cold_ok = debian_stats["cold_provisioned"]["valid_pass_count"] >= min_debian_cold_warm
        warm_ok = debian_stats["warm_restart"]["valid_pass_count"] >= min_debian_cold_warm
        rec_ok = debian_stats["warm_reconnect"]["valid_pass_count"] >= min_debian_cold_warm
        alpine_cold_ok = alpine_stats["cold_provisioned"]["paired_valid_count"] >= min_alpine_pairs
        alpine_warm_ok = alpine_stats["warm_restart"]["paired_valid_count"] >= min_alpine_pairs
        alpine_rec_ok = alpine_stats["warm_reconnect"]["paired_valid_count"] >= min_alpine_pairs

        apk_failed = apk_verification is not None and not bool(apk_verification.get("passed"))
        payload_failed = payload_gate_ok is False

        if compat_blocked or func_failed or unresolved_residual_events > 0 or unexplained_timeouts > 0 or apk_failed or payload_failed:
            ps_verdict = "FAIL"
        elif func_incomplete or not (fp_ok and cold_ok and warm_ok and rec_ok and alpine_cold_ok and alpine_warm_ok and alpine_rec_ok):
            ps_verdict = "INCOMPLETE"
        else:
            ps_verdict = "PASS"

        page_verdicts[ps] = ps_verdict
        by_page_size[str(ps)] = {
            "host_page_size": ps,
            "device_alias": PAGE_SIZE_ALIASES[ps],
            "verdict": ps_verdict,
            "compat_16kb_blocked": compat_blocked,
            "residual_process_events": unresolved_residual_events,
            "unresolved_residual_process_events": unresolved_residual_events,
            "historical_residual_process_events": historical_residual_events,
            "functional_checks": func_summary,
            "debian_buckets": debian_stats,
            "alpine_paired": alpine_stats,
            "qemu_ref": qemu_ref_stats,
        }

    v4 = page_verdicts[4096]
    v16 = page_verdicts[16384]
    if "FAIL" in (v4, v16):
        overall = "FAIL"
    elif v4 == "PASS" and v16 == "PASS":
        overall = "PASS"
    elif v4 == "PASS" and v16 == "MISSING":
        overall = "PASS_4K_ONLY_PENDING_16K"
    else:
        overall = "INCOMPLETE"

    return {
        "schema_version": 2 if selection["provenance_digest"] else 1,
        "generated_at_utc": utc_now_iso(),
        "provenance_digest": selection["provenance_digest"],
        "overall_verdict": overall,
        "signoff_allowed": overall == "PASS",
        "apk_verification": apk_verification,
        "payload_gate_ok": payload_gate_ok,
        "by_page_size": by_page_size,
        "wave_receipt": {
            "counts": counts,
            "historical_fail_attempts": total_historical_func_fails,
            "items": receipt_items,
        },
    }


def _run_cmd(cmd: list[str], *, check: bool = True, timeout: int = 120) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, shell=False, text=True, capture_output=True, encoding="utf-8", errors="replace", check=check, timeout=timeout)


def _resolve_device_serial(requested: str | None, *, adb_path: str = "adb") -> str:
    return qa_guard.resolve_unique_device(adb_path, requested, run_cmd=_run_cmd)


class _AdbClient:
    def __init__(self, serial: str, *, adb_path: str = "adb") -> None:
        self.serial = serial
        self.adb_path = adb_path

    def run(self, *args: str, check: bool = True, timeout: int = 120) -> subprocess.CompletedProcess[str]:
        return _run_cmd([self.adb_path, "-s", self.serial, *args], check=check, timeout=timeout)


def _find_tool(explicit: str | None, env_var: str, candidates: list[str]) -> str | None:
    return qa_guard.find_sdk_tool(explicit, env_var, candidates)


def _parse_w0105_instrumentation(text: str) -> dict[str, Any]:
    code: int | None = None
    stages: list[str] = []
    samples: list[dict[str, Any]] = []
    result: dict[str, str] = {}
    failed_lines: list[str] = []
    short_msgs: list[str] = []
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("INSTRUMENTATION_STATUS:"):
            kv = line.split(":", 1)[1].strip()
            if "=" in kv:
                k, v = kv.split("=", 1)
                k = k.strip()
                v = v.strip()
                if k == "w0105_stage":
                    stages.append(v)
                elif k == "w0105_sample_json":
                    try:
                        obj = json.loads(v)
                        if isinstance(obj, dict):
                            samples.append(obj)
                    except json.JSONDecodeError:
                        pass
        elif line.startswith("INSTRUMENTATION_RESULT:"):
            kv = line.split(":", 1)[1].strip()
            if "=" in kv:
                k, v = kv.split("=", 1)
                k_clean = k.strip()
                v_clean = v.strip()
                result[k_clean] = v_clean
                if k_clean == "shortMsg":
                    short_msgs.append(v_clean)
        elif line.startswith("INSTRUMENTATION_CODE:"):
            try:
                code = int(line.split(":", 1)[1].strip())
            except ValueError:
                code = None
        elif line.startswith("INSTRUMENTATION_FAILED:"):
            failed_lines.append(sanitize_text(line.split(":", 1)[1].strip(), max_len=160) or "failed")
    return {
        "code": code,
        "stages": stages,
        "samples": samples,
        "result": result,
        "failed_lines": failed_lines,
        "short_msg": short_msgs[-1] if short_msgs else None,
    }


def _instrument_failure_detail(proc: subprocess.CompletedProcess[str], parsed: dict[str, Any]) -> str:
    err = parsed["result"].get("w0105_error") or parsed.get("short_msg")
    if not err and parsed.get("failed_lines"):
        err = parsed["failed_lines"][-1]
    if not err:
        err = f"instrument_rc={proc.returncode}_code={parsed.get('code')}"
    return sanitize_text(err, max_len=160) or "instrument_failed"


def _verify_fixtures(debian_tar: Path, fixtures_dir: Path) -> dict[str, Any]:
    if not debian_tar.is_file():
        raise SystemExit(f"Missing Debian tar: {debian_tar}")
    if sha256_file(debian_tar) != DEBIAN_ROOTFS_SHA256:
        raise SystemExit("Debian rootfs SHA-256 mismatch")
    lock_file = fixtures_dir / "w0104-lock.json"
    base_img = fixtures_dir / "base-image-arm64.tar"
    keyring = fixtures_dir / "apt-repo/qa-w0104-archive.gpg"
    inrelease = fixtures_dir / "apt-repo/dists/trixie/InRelease"
    for req in (lock_file, base_img, keyring, inrelease):
        if not req.is_file():
            raise SystemExit(f"Missing fixture file: {req}")
    lock = json.loads(lock_file.read_text(encoding="utf-8"))
    if sha256_file(base_img) != lock["base_image"]["tar_sha256"]:
        raise SystemExit("Base image tar SHA-256 mismatch")
    for pkg in lock["packages"]:
        deb = fixtures_dir / "apt-repo" / pkg["filename"]
        if not deb.is_file() or sha256_file(deb) != pkg["sha256"]:
            raise SystemExit(f"Corrupt or missing .deb: {pkg['filename']}")
    return lock


def _collect_device_facts(adb: _AdbClient, alias: str) -> dict[str, Any]:
    ps_raw = adb.run("shell", "getconf", "PAGESIZE", timeout=20).stdout
    page_size = validate_host_page_size(ps_raw)
    rel = adb.run("shell", "getprop", "ro.build.version.release", timeout=20).stdout.strip()
    sdk = adb.run("shell", "getprop", "ro.build.version.sdk", timeout=20).stdout.strip()
    abi = adb.run("shell", "getprop", "ro.product.cpu.abi", timeout=20).stdout.strip()
    kernel = adb.run("shell", "uname", "-r", timeout=20).stdout.strip()
    nproc = adb.run("shell", "nproc", timeout=20).stdout.strip()
    meminfo = adb.run("shell", "cat", "/proc/meminfo", timeout=20).stdout.splitlines()[:3]
    qemu_prop = adb.run("shell", "getprop", "ro.kernel.qemu", timeout=20).stdout.strip()
    boot_qemu_prop = adb.run("shell", "getprop", "ro.boot.qemu", timeout=20, check=False).stdout.strip()
    is_emulator = qemu_prop == "1" or boot_qemu_prop == "1"
    props: dict[str, str] = {}
    for key in bench._COMPAT_PROP_KEYS:
        props[key] = adb.run("shell", "getprop", key, timeout=15, check=False).stdout.strip()
    return {
        "device_alias": alias,
        "host_page_size": page_size,
        "android_release": rel,
        "android_sdk": int(sdk) if sdk.isdigit() else sdk,
        "abi": abi,
        "host_kernel_release": kernel,
        "host_cpus": int(nproc) if nproc.isdigit() else nproc,
        "mem_total_kb": meminfo[0] if meminfo else "",
        "is_emulator": is_emulator,
        "compat_props": props,
        "battery_temp_c": read_battery_temperature_c(adb),
    }


def _stage_inputs_on_device(
    adb: _AdbClient,
    debian_tar: Path,
    fixtures_dir: Path,
    *,
    qa_package: str = QA_PACKAGE,
) -> None:
    remote_tmp = "/data/local/tmp/w0105-stage"
    adb.run("shell", f"rm -rf {remote_tmp} && mkdir -p {remote_tmp}", timeout=60)
    try:
        adb.run("push", str(debian_tar), f"{remote_tmp}/{DEBIAN_ROOTFS_TAR}", timeout=300)
        adb.run("push", str(fixtures_dir), f"{remote_tmp}/w0104-fixtures", timeout=300)
        adb.run(
            "shell",
            f"run-as {qa_package} sh -c '"
            f"mkdir -p files/distro-inputs && rm -rf files/w0104-fixtures && "
            f"cp {remote_tmp}/{DEBIAN_ROOTFS_TAR} files/distro-inputs/{DEBIAN_ROOTFS_TAR} && "
            f"cp -r {remote_tmp}/w0104-fixtures files/w0104-fixtures && "
            f"chmod -R a+rX files/w0104-fixtures && "
            f"chmod 0644 files/distro-inputs/{DEBIAN_ROOTFS_TAR}"
            f"'",
            timeout=180,
        )
    finally:
        adb.run("shell", f"rm -rf {remote_tmp}", check=False, timeout=60)


def _run_instrument(
    adb: _AdbClient,
    action: str,
    *,
    extras: dict[str, str] | None = None,
    timeout: int = 1800,
    log_path: Path | None = None,
    instrumentation_component: str = INSTRUMENTATION,
) -> tuple[subprocess.CompletedProcess[str], dict[str, Any]]:
    cmd = ["am", "instrument", "-w", "-e", "action", action]
    if extras:
        for k, v in extras.items():
            cmd.extend(["-e", str(k), str(v)])
    cmd.append(instrumentation_component)
    proc = adb.run("shell", *cmd, check=False, timeout=timeout)
    raw = proc.stdout + ("\n" + proc.stderr if proc.stderr else "")
    if log_path is not None:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with open(log_path, "a", encoding="utf-8", newline="\n") as fh:
            fh.write(f"\n=== ACTION={action} EXTRAS={extras or {}} RC={proc.returncode} ===\n")
            fh.write(raw + "\n")
    return proc, _parse_w0105_instrumentation(raw)


def _check_residual_processes(
    adb: _AdbClient,
    *,
    cleanup: bool = True,
    qa_package: str = QA_PACKAGE,
) -> list[dict[str, Any]]:
    ps_out = adb.run("shell", "ps", "-A", "-o", "PID,NAME,ARGS", check=False, timeout=20).stdout
    found = find_residual_uml_processes(ps_out)
    if found and cleanup:
        adb.run("shell", "am", "force-stop", qa_package, check=False, timeout=20)
    return found


def _verify_apksigner(apksigner_path: str | None, apk_path: Path) -> dict[str, Any] | None:
    if not apksigner_path:
        return None
    sig_proc = _run_cmd([apksigner_path, "verify", "--verbose", "--print-certs", str(apk_path)], check=False)
    return {
        "passed": sig_proc.returncode == 0,
        "lines": [
            line.strip()
            for line in sig_proc.stdout.splitlines()
            if "SHA-256 digest" in line or "Verified using" in line
        ],
    }


def _build_functional_row(
    *,
    run_id: str,
    check_name: str,
    host_page_size: int,
    device_alias: str,
    status: str,
    detail: str | None,
    residual_count: int,
    extras: dict[str, Any] | None = None,
) -> dict[str, Any]:
    row: dict[str, Any] = {
        "record_type": "functional_check",
        "run_id": run_id,
        "sample_id": f"p{host_page_size}-func-{check_name}",
        "check_name": check_name,
        "host_page_size": host_page_size,
        "device_alias": device_alias,
        "status": status,
        "attempts": 1,
        "failure_reason": sanitize_text(detail, max_len=160) if status != "PASS" else None,
        "detail": sanitize_text(detail, max_len=160),
        "residual_process_count": residual_count,
        "timestamp_utc": utc_now_iso(),
    }
    if extras:
        row["metrics"] = extras
    return row


def _build_perf_row_from_sample(
    *,
    sample: dict[str, Any],
    run_id: str,
    record_type: str,
    suite: str,
    bucket: str,
    sample_id: str,
    host_page_size: int,
    device_alias: str,
    temp_before: float | None,
    temp_after: float | None,
    host_duration_ms: int,
    residual_count: int,
) -> dict[str, Any]:
    st = str(sample.get("status") or sample.get("result") or "FAIL").upper()
    if st not in ("PASS", "FAIL", "TIMEOUT"):
        st = "FAIL"
    return {
        "record_type": record_type,
        "run_id": run_id,
        "sample_id": sample_id,
        "timestamp_utc": utc_now_iso(),
        "host_page_size": host_page_size,
        "device_alias": device_alias,
        "suite": suite,
        "bucket": bucket,
        "pair_index": sample.get("pair_index"),
        "iteration": sample.get("iteration"),
        "variant": sample.get("variant"),
        "engine": sample.get("engine"),
        "profile": sample.get("profile"),
        "distro": sample.get("distro"),
        "distro_version": sample.get("distro_version"),
        "vcpu": sample.get("vcpu"),
        "ram_mb": sample.get("ram_mb"),
        "network_mode": sample.get("network_mode"),
        "status": st,
        "failure_reason": sanitize_text(sample.get("error") or sample.get("detail"), max_len=160) if st != "PASS" else None,
        "timeout_explained": bool(sample.get("timeout_explained", False)),
        "t0_to_bridge_ready_ms": sample.get("t0_to_bridge_ready_ms"),
        "t0_to_systemd_ready_ms": sample.get("t0_to_systemd_ready_ms"),
        "t0_to_shell_ready_ms": sample.get("t0_to_shell_ready_ms"),
        "t0_to_first_cmd_echo_ms": sample.get("t0_to_first_cmd_echo_ms"),
        "t0_to_docker_daemon_ready_ms": sample.get("t0_to_docker_daemon_ready_ms"),
        "t0_to_container_http_ready_ms": sample.get("t0_to_container_http_ready_ms"),
        "reattach_ms": sample.get("reattach_ms"),
        "roundtrip_ms": sample.get("roundtrip_ms"),
        "credentials_only_reason": sample.get("credentials_only_reason"),
        "stop_result": sample.get("stop_result"),
        "residual_process_count": residual_count,
        "temp_c_before": temp_before,
        "temp_c_after": temp_after,
        "host_duration_ms": host_duration_ms,
        "detail": sanitize_text(sample.get("detail"), max_len=160),
    }


def _passed_debian_iters(
    records: list[dict[str, Any]],
    suite: str,
    bucket: str,
    host_page_size: int,
    *,
    require_provenance: bool = False,
) -> set[int]:
    selection = prov.validate_and_select_ledger(records, require_provenance=require_provenance)
    done: set[int] = set()
    for skey, r in selection["passed_by_key"].items():
        if (
            skey[0] == "perf_sample"
            and skey[1] == suite
            and skey[2] == bucket
            and r.get("host_page_size") == host_page_size
            and isinstance(r.get("iteration"), int)
        ):
            done.add(r["iteration"])
    return done


def _passed_paired_indices(
    records: list[dict[str, Any]],
    bucket: str,
    host_page_size: int,
    *,
    require_provenance: bool = False,
) -> set[int]:
    selection = prov.validate_and_select_ledger(records, require_provenance=require_provenance)
    by_var: dict[str, set[int]] = {"fast": set(), "full": set()}
    for skey, r in selection["passed_by_key"].items():
        if (
            skey[0] == "paired_sample"
            and skey[1] == "alpine_paired_fast_vs_full"
            and skey[2] == bucket
            and r.get("host_page_size") == host_page_size
            and r.get("variant") in by_var
            and isinstance(r.get("pair_index"), int)
        ):
            by_var[r["variant"]].add(r["pair_index"])
    return by_var["fast"] & by_var["full"]


def _default_payload_gate_runner(repo_root: Path, app_apk: Path) -> tuple[bool, dict[str, Any], dict[str, Any]]:
    payload_tool = repo_root / "tools/release/check_uml_payload.py"
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        qa_path = Path(tmp) / "gate-uml-qa-full-profile.json"
        fullpkg_path = Path(tmp) / "gate-uml-fullpkg.json"
        qa_proc = _run_cmd([sys.executable, str(payload_tool), "--apk", str(app_apk), "--qa-full-profile", "--json", str(qa_path)], check=False)
        fullpkg_proc = _run_cmd([sys.executable, str(payload_tool), "--apk", str(app_apk), "--fullpkg", "--json", str(fullpkg_path)], check=False)
        qa_gate = json.loads(qa_path.read_text(encoding="utf-8")) if qa_path.is_file() else {"status": "error"}
        fullpkg_gate = json.loads(fullpkg_path.read_text(encoding="utf-8")) if fullpkg_path.is_file() else {"status": "error"}
    for gate_obj in (qa_gate, fullpkg_gate):
        if isinstance(gate_obj, dict) and "apk" in gate_obj:
            gate_obj["apk"] = app_apk.name
    payload_ok = qa_proc.returncode == 0 and fullpkg_proc.returncode == 0
    return payload_ok, qa_gate, fullpkg_gate


def _verify_candidate_host_gates(
    app_apk: Path,
    test_apk: Path,
    debian_tar: Path,
    fixtures_dir: Path,
    fixtures_lock: dict[str, Any],
    repo_root: Path,
    target: qa_guard.QaTargetSpec,
    readelf_path: str | None,
    zipalign_path: str | None,
    apkanalyzer_path: str | None,
    aapt2_path: str | None,
    apksigner_path: str | None,
    *,
    apk_inspector: Any = None,
    payload_gate_runner: Any = None,
    apk_16kb_verifier: Any = None,
) -> dict[str, Any]:
    app_info = apk_inspector(app_apk) if apk_inspector is not None else None
    test_info = apk_inspector(test_apk) if apk_inspector is not None else None
    apk_proof = qa_guard.verify_qa_apk_pair(
        app_apk,
        test_apk,
        target,
        app_info=app_info,
        test_info=test_info,
        apkanalyzer_path=apkanalyzer_path,
        aapt2_path=aapt2_path,
        apksigner_path=apksigner_path,
        run_cmd=_run_cmd,
    )

    verifier = apk_16kb_verifier or verify_apk_16kb
    apk_16kb = verifier(
        app_apk,
        readelf_path=readelf_path,
        zipalign_path=zipalign_path,
        require_external_tools=bool(readelf_path and zipalign_path),
    )

    gate_runner = payload_gate_runner or _default_payload_gate_runner
    payload_ok, qa_gate, fullpkg_gate = gate_runner(repo_root, app_apk)
    for gate_obj in (qa_gate, fullpkg_gate):
        if isinstance(gate_obj, dict) and "apk" in gate_obj:
            gate_obj["apk"] = app_apk.name

    builtin_payloads = prov.extract_apk_builtin_payload_digests(app_apk)
    inputs_provenance = prov.build_inputs_provenance(debian_tar, fixtures_dir, fixtures_lock)

    git_sha = _run_cmd(["git", "-C", str(repo_root), "rev-parse", "HEAD"], check=False).stdout.strip() or "unknown"
    git_dirty = bool(_run_cmd(["git", "-C", str(repo_root), "status", "--porcelain", "--untracked-files=no"], check=False).stdout.strip())

    return {
        "studio_commit": git_sha,
        "studio_dirty": git_dirty,
        "target": target,
        "apk_proof": apk_proof,
        "apk_16kb": apk_16kb,
        "payload_gates": {
            "passed": payload_ok,
            "qa_full_profile": qa_gate,
            "fullpkg": fullpkg_gate,
        },
        "builtin_payloads": builtin_payloads,
        "inputs_provenance": inputs_provenance,
    }


def _write_gate_summary(
    evidence_dir: Path,
    ledger_path: Path,
    *,
    apk_verification: dict[str, Any] | None,
    payload_gate_ok: bool | None,
    compat_blocked: set[int],
    min_debian_cold_warm: int,
    min_alpine_pairs: int,
    run_id: str,
    mode: str,
    device_facts: dict[str, Any],
    require_provenance: bool = True,
) -> dict[str, Any]:
    summary = evaluate_w0105_gate(
        load_ledger(ledger_path),
        apk_verification=apk_verification,
        payload_gate_ok=payload_gate_ok,
        compat_blocked_page_sizes=compat_blocked,
        min_debian_cold_warm=min_debian_cold_warm,
        min_alpine_pairs=min_alpine_pairs,
        require_provenance=require_provenance,
    )
    summary["run_id"] = run_id
    summary["mode"] = mode
    summary["device_facts"] = device_facts
    prov.atomic_write_json(evidence_dir / "summary.json", summary)
    return summary


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Host orchestrator for isolated QA validation (Wave 01.05 / Wave 02+). "
            "Default installation is incremental (no uninstall); --clean-install limits cleanup "
            "strictly to the verified QA app package and its .test instrumentation package."
        )
    )
    parser.add_argument("--adb", default=None, help="Path to adb executable")
    parser.add_argument("--serial", default=None, help="Explicit ADB device serial")
    parser.add_argument("--device-alias", default="device-4k", help="Stable sanitized device alias (e.g. device-4k)")
    parser.add_argument("--qa-purpose", default=None, help="Gradle QA buildPurpose ([a-z0-9]{1,32}, default: w0105)")
    parser.add_argument("--package", default=None, help="Explicit isolated QA package (io.github.nyzeep.orchestella.qa.<purpose>)")
    parser.add_argument("--app-apk", type=Path, default=None, help="Path to QA app APK")
    parser.add_argument("--test-apk", type=Path, default=None, help="Path to QA androidTest APK")
    parser.add_argument("--debian-tar", type=Path, default=None, help="Path to debian-13-rootfs.tar.xz")
    parser.add_argument("--fixtures-dir", type=Path, default=None, help="Path to w0104-fixtures directory")
    parser.add_argument("--evidence-dir", type=Path, required=True, help="External evidence directory")
    parser.add_argument(
        "--mode",
        default="all",
        choices=("smoke", "full_chain", "fault_and_compat", "debian_bench", "alpine_paired", "qemu_ref", "all"),
    )
    parser.add_argument("--resume", action="store_true", help="Resume an existing v2 evidence dir after strict provenance diff")
    parser.add_argument("--skip-install", action="store_true", help="Skip install/staging and verify on-device APKs, instrumentation, and inputs")
    parser.add_argument(
        "--clean-install",
        action="store_true",
        help="Uninstall ONLY the verified target QA app and .test packages before installing",
    )
    parser.add_argument("--dry-run", action="store_true", help="Run host preflight and read-only device/resume inspection with zero ADB writes")
    parser.add_argument("--audit-only", action="store_true", help="Read-only audit of --evidence-dir without ADB or APKs")
    parser.add_argument("--max-temp-c", type=float, default=DEFAULT_MAX_TEMP_C)
    parser.add_argument("--debian-cold-samples", type=int, default=DEFAULT_DEBIAN_SAMPLES)
    parser.add_argument("--debian-warm-samples", type=int, default=DEFAULT_DEBIAN_SAMPLES)
    parser.add_argument("--debian-reconnect-samples", type=int, default=DEFAULT_RECONNECT_SAMPLES)
    parser.add_argument("--alpine-pairs", type=int, default=DEFAULT_ALPINE_PAIRS)
    parser.add_argument("--qemu-ref-samples", type=int, default=DEFAULT_QEMU_REF_SAMPLES)
    parser.add_argument("--readelf", default=None)
    parser.add_argument("--zipalign", default=None)
    parser.add_argument("--apksigner", default=None)
    parser.add_argument("--apkanalyzer", default=None)
    parser.add_argument("--aapt2", default=None)
    return parser


def _emit_error(code: str, detail: str, *, diffs: list[dict[str, Any]] | None = None) -> int:
    payload: dict[str, Any] = {"error_code": code, "detail": detail}
    if diffs:
        payload["diffs"] = diffs
    print(json.dumps(payload, ensure_ascii=True), file=sys.stderr)
    return 2


def run_cli(
    argv: list[str] | None = None,
    *,
    adb_factory: Any = None,
    device_resolver: Any = None,
    apk_inspector: Any = None,
    payload_gate_runner: Any = None,
    apk_16kb_verifier: Any = None,
) -> int:
    parser = _build_arg_parser()
    args = parser.parse_args(argv)

    if args.audit_only:
        audit = prov.audit_evidence_dir_readonly(args.evidence_dir)
        print(json.dumps(audit, ensure_ascii=True))
        return 0 if audit["exists"] and not audit["errors"] else 1

    missing_required = [
        flag
        for flag, val in (
            ("--app-apk", args.app_apk),
            ("--test-apk", args.test_apk),
            ("--debian-tar", args.debian_tar),
            ("--fixtures-dir", args.fixtures_dir),
        )
        if val is None
    ]
    if missing_required:
        parser.error(f"the following arguments are required: {', '.join(missing_required)}")

    try:
        alias = validate_device_alias(args.device_alias)
        target = qa_guard.resolve_qa_target(
            purpose=args.qa_purpose,
            package=args.package,
            default_purpose=DEFAULT_QA_PURPOSE,
            instrumentation_class=INSTRUMENTATION_CLASS,
        )
    except (ValueError, qa_guard.QaGuardError) as exc:
        code = exc.code if isinstance(exc, qa_guard.QaGuardError) else "INVALID_ARGUMENT"
        detail = exc.detail if isinstance(exc, qa_guard.QaGuardError) else str(exc)
        return _emit_error(code, detail)

    for p in (args.app_apk, args.test_apk):
        if not p.is_file():
            return _emit_error("APK_MISSING", f"Missing APK: {p.name}")
    fixtures_lock = _verify_fixtures(args.debian_tar, args.fixtures_dir)
    repo_root = Path(__file__).resolve().parents[2]

    readelf_path = _find_tool(args.readelf, "READELF", ["llvm-readelf", "aarch64-linux-android-readelf", "readelf"])
    zipalign_path = _find_tool(args.zipalign, "ZIPALIGN", ["zipalign"])
    apksigner_path = _find_tool(args.apksigner, "APKSIGNER", ["apksigner"])
    apkanalyzer_path = _find_tool(args.apkanalyzer, "APKANALYZER", ["apkanalyzer"])
    aapt2_path = _find_tool(args.aapt2, "AAPT2", ["aapt2"])

    try:
        host_gates = _verify_candidate_host_gates(
            args.app_apk,
            args.test_apk,
            args.debian_tar,
            args.fixtures_dir,
            fixtures_lock,
            repo_root,
            target,
            readelf_path,
            zipalign_path,
            apkanalyzer_path,
            aapt2_path,
            apksigner_path,
            apk_inspector=apk_inspector,
            payload_gate_runner=payload_gate_runner,
            apk_16kb_verifier=apk_16kb_verifier,
        )
    except qa_guard.QaGuardError as exc:
        return _emit_error(exc.code, exc.detail)

    apk_16kb = host_gates["apk_16kb"]
    payload_ok = host_gates["payload_gates"]["passed"]
    adb_bin = _find_tool(args.adb, "ADB", ["adb"]) or "adb"

    try:
        serial = (
            device_resolver(adb_bin, args.serial)
            if device_resolver is not None
            else _resolve_device_serial(args.serial, adb_path=adb_bin)
        )
    except qa_guard.QaGuardError as exc:
        return _emit_error(exc.code, exc.detail)

    adb = adb_factory(serial) if adb_factory is not None else _AdbClient(serial, adb_path=adb_bin)
    device_facts = _collect_device_facts(adb, alias)
    ps = device_facts["host_page_size"]
    if device_facts["is_emulator"]:
        return _emit_error("EMULATOR_REJECTED", "Physical device required; emulator rejected fail-closed")

    helper_paths = [
        Path(__file__).with_name("qa_package_guard.py"),
        Path(__file__).with_name("evidence_provenance.py"),
        Path(__file__).with_name("run_w305_perf_bench.py"),
    ]
    provenance_bundle = prov.build_provenance(
        studio_commit=host_gates["studio_commit"],
        studio_dirty=host_gates["studio_dirty"],
        runner_path=Path(__file__),
        helper_paths=helper_paths,
        apk_proof=host_gates["apk_proof"],
        builtin_payloads=host_gates["builtin_payloads"],
        inputs_provenance=host_gates["inputs_provenance"],
        device_facts=device_facts,
    )

    if args.dry_run:
        try:
            session = prov.prepare_evidence_session(
                args.evidence_dir,
                resume=args.resume,
                expected_provenance_bundle=provenance_bundle,
            )
            skip_check = (
                qa_guard.verify_installed_qa_candidate(
                    adb,
                    target=target,
                    apk_proof=host_gates["apk_proof"],
                    device_alias=alias,
                    expected_staged_files=host_gates["inputs_provenance"]["expected_staged_files"],
                )
                if args.skip_install
                else None
            )
        except (prov.EvidenceProvenanceError, qa_guard.QaGuardError) as exc:
            diffs = exc.diffs if isinstance(exc, prov.EvidenceProvenanceError) else None
            return _emit_error(exc.code, exc.detail, diffs=diffs)

        all_installed = qa_guard.list_installed_orchestella_packages_readonly(adb)
        exact_targets = {target.app_package, target.test_package}
        preserved_other = [p for p in all_installed if p not in exact_targets]
        print(
            json.dumps(
                {
                    "dry_run": True,
                    "adb_mutations": 0,
                    "device_alias": alias,
                    "host_page_size": ps,
                    "qa_target": provenance_bundle["provenance"]["qa_target"],
                    "install_mode": (
                        "skip_install"
                        if args.skip_install
                        else ("clean_qa_only" if args.clean_install else "incremental")
                    ),
                    "exact_cleanup_targets": [target.test_package, target.app_package] if args.clean_install and not args.skip_install else [],
                    "preserved_other_packages": preserved_other,
                    "skip_install_verification": skip_check,
                    "resume": session["is_resume"],
                    "next_run_id": session["next_run_id"],
                    "provenance_digest": provenance_bundle["provenance_digest"],
                    "candidate_digest": provenance_bundle["candidate_digest"],
                },
                ensure_ascii=True,
            )
        )
        return 0

    args.evidence_dir.mkdir(parents=True, exist_ok=True)
    try:
        with prov.EvidenceDirLock(args.evidence_dir):
            session = prov.prepare_evidence_session(
                args.evidence_dir,
                resume=args.resume,
                expected_provenance_bundle=provenance_bundle,
            )
            all_ledger_rows: list[dict[str, Any]] = list(session["existing_records"])
            passed_sids: set[str] = set(session["selection"]["passed_sample_ids"])
            run_id = session["next_run_id"]
            ledger_path = args.evidence_dir / "ledger.jsonl"
            raw_log = args.evidence_dir / f"instrument-{args.mode}.log.txt"

            if args.skip_install:
                install_report = qa_guard.verify_installed_qa_candidate(
                    adb,
                    target=target,
                    apk_proof=host_gates["apk_proof"],
                    device_alias=alias,
                    expected_staged_files=host_gates["inputs_provenance"]["expected_staged_files"],
                )
            else:
                install_report = qa_guard.install_qa_candidate(
                    adb,
                    app_apk=args.app_apk,
                    test_apk=args.test_apk,
                    target=target,
                    apk_proof=host_gates["apk_proof"],
                    device_alias=alias,
                    clean_install=args.clean_install,
                )
                _stage_inputs_on_device(adb, args.debian_tar, args.fixtures_dir, qa_package=target.app_package)

            pkg_dumpsys = adb.run("shell", "dumpsys", "package", target.app_package, timeout=30, check=False).stdout
            pkg_meta = parse_dumpsys_package_meta(pkg_dumpsys, device_facts["compat_props"])
            compat_blocked = {ps} if pkg_meta["compat_16kb_indicators"] else set()
            device_facts["installed_pkg_meta"] = pkg_meta
            prov.atomic_write_json(args.evidence_dir / "device-facts.json", device_facts)

            if not session["is_resume"]:
                prov.atomic_write_json(
                    args.evidence_dir / "gate-uml-qa-full-profile.json",
                    host_gates["payload_gates"]["qa_full_profile"],
                )
                prov.atomic_write_json(
                    args.evidence_dir / "gate-uml-fullpkg.json",
                    host_gates["payload_gates"]["fullpkg"],
                )
                manifest_payload = {
                    "schema_version": prov.PROVENANCE_SCHEMA_VERSION,
                    "generated_at_utc": utc_now_iso(),
                    "studio_commit": host_gates["studio_commit"],
                    "qa_package": target.app_package,
                    "qa_purpose": target.purpose,
                    "instrumentation_component": target.instrumentation_component,
                    "candidate_digest": provenance_bundle["candidate_digest"],
                    "provenance_digest": provenance_bundle["provenance_digest"],
                    "provenance": provenance_bundle["provenance"],
                    "app_apk": {
                        **host_gates["apk_proof"]["app_apk"],
                        "verify_apk_16kb": apk_16kb,
                    },
                    "test_apk": host_gates["apk_proof"]["test_apk"],
                    "debian_rootfs": host_gates["inputs_provenance"]["debian_rootfs"],
                    "w0104_fixtures": host_gates["inputs_provenance"]["w0104_fixtures"],
                    "payload_gates": host_gates["payload_gates"],
                    "install_report": install_report,
                }
                prov.atomic_write_json(
                    args.evidence_dir / "candidate-manifest.json",
                    manifest_payload,
                    allow_overwrite=False,
                )

            prov.append_jsonl_atomic(
                args.evidence_dir / "run-actions.jsonl",
                [
                    {
                        "timestamp_utc": utc_now_iso(),
                        "run_id": run_id,
                        "action": "resume" if session["is_resume"] else "fresh",
                        "mode": args.mode,
                        "install_mode": (
                            "skip_install"
                            if args.skip_install
                            else ("clean_qa_only" if args.clean_install else "incremental")
                        ),
                        "device_alias": alias,
                        "host_page_size": ps,
                        "qa_package": target.app_package,
                        "provenance_digest": provenance_bundle["provenance_digest"],
                        "manifest_preserved": bool(session["is_resume"]),
                        "install_report": install_report,
                    }
                ],
            )

            def _record_rows(rows: list[dict[str, Any]]) -> None:
                stamped: list[dict[str, Any]] = []
                for row in rows:
                    r = dict(row)
                    r.update(provenance_bundle["row_attribution"])
                    r.update(prov.build_attempt_metadata(all_ledger_rows, r["sample_id"], run_id))
                    all_ledger_rows.append(r)
                    if r.get("status") == "PASS":
                        passed_sids.add(r["sample_id"])
                    stamped.append(r)
                append_ledger_records(ledger_path, stamped)

            def exec_functional(check_name: str, action_name: str, timeout: int = 1800) -> dict[str, Any]:
                sid = f"p{ps}-func-{check_name}"
                if args.resume and sid in passed_sids:
                    for existing_row in reversed(all_ledger_rows):
                        if existing_row.get("sample_id") == sid and existing_row.get("status") == "PASS":
                            return existing_row
                wait_for_thermal_cooldown(adb, args.max_temp_c)
                t0 = time.monotonic()
                proc, parsed = _run_instrument(
                    adb,
                    action_name,
                    timeout=timeout,
                    log_path=raw_log,
                    instrumentation_component=target.instrumentation_component,
                )
                dur = int((time.monotonic() - t0) * 1000)
                res_procs = _check_residual_processes(adb, qa_package=target.app_package)
                passed = (
                    proc.returncode == 0
                    and parsed["result"].get("w0105_status") == "PASS"
                    and len(res_procs) == 0
                )
                detail = parsed["result"].get("w0105_summary") if passed else _instrument_failure_detail(proc, parsed)
                if not detail:
                    detail = "ok" if passed else "instrument_failed"
                row = _build_functional_row(
                    run_id=run_id,
                    check_name=check_name,
                    host_page_size=ps,
                    device_alias=alias,
                    status="PASS" if passed else "FAIL",
                    detail=detail,
                    residual_count=len(res_procs),
                    extras={
                        "host_duration_ms": dur,
                        "result_fields": {k: sanitize_text(v, max_len=240) for k, v in parsed["result"].items()},
                    },
                )
                _record_rows([row])
                return row

            if args.mode == "smoke":
                exec_functional("preflight", "preflight", timeout=180)
                exec_functional("normal_entry_check", "normal_entry_check", timeout=180)
            else:
                if args.mode in ("full_chain", "all"):
                    exec_functional("preflight", "preflight", timeout=180)
                    exec_functional("normal_entry_check", "normal_entry_check", timeout=180)
                    exec_functional("debian_full_chain", "debian_full_chain", timeout=2400)
                if args.mode in ("fault_and_compat", "all"):
                    exec_functional("fault_and_compat", "fault_and_compat", timeout=1200)
                if args.mode in ("debian_bench", "all"):
                    def _run_deb_prep(_attempt: int) -> dict[str, Any]:
                        adb.run("shell", "am", "force-stop", target.app_package, check=False, timeout=20)
                        time.sleep(1.0)
                        return exec_functional("debian_bench_prepare", "debian_bench_prepare", timeout=1800)

                    if not prepare_with_single_retry(_run_deb_prep):
                        print(json.dumps({"error": "debian_bench_prepare_failed_fail_fast"}), file=sys.stderr)
                        _write_gate_summary(
                            args.evidence_dir, ledger_path,
                            apk_verification=apk_16kb, payload_gate_ok=payload_ok,
                            compat_blocked=compat_blocked, min_debian_cold_warm=args.debian_cold_samples,
                            min_alpine_pairs=args.alpine_pairs, run_id=run_id, mode=args.mode, device_facts=device_facts,
                        )
                        return 1
                    done_fp = _passed_debian_iters(all_ledger_rows, "debian_full", "first_provision", ps, require_provenance=True)
                    for fp_idx in (1, 2):
                        if args.resume and fp_idx in done_fp:
                            continue
                        tb, _ = wait_for_thermal_cooldown(adb, args.max_temp_c)
                        t0 = time.monotonic()
                        proc, parsed = _run_instrument(
                            adb, "debian_first_provision", extras={"iteration": str(fp_idx)}, timeout=900, log_path=raw_log,
                            instrumentation_component=target.instrumentation_component,
                        )
                        ta = read_battery_temperature_c(adb)
                        dur = int((time.monotonic() - t0) * 1000)
                        res_cnt = len(_check_residual_processes(adb, qa_package=target.app_package))
                        samp = parsed["samples"][0] if parsed["samples"] else {"status": "FAIL", "error": parsed["result"].get("w0105_error")}
                        _record_rows([
                            _build_perf_row_from_sample(
                                sample=samp,
                                run_id=run_id,
                                record_type="perf_sample",
                                suite="debian_full",
                                bucket="first_provision",
                                sample_id=f"p{ps}-debian-first_provision-{fp_idx:03d}",
                                host_page_size=ps,
                                device_alias=alias,
                                temp_before=tb,
                                temp_after=ta,
                                host_duration_ms=dur,
                                residual_count=res_cnt,
                            )
                        ])

                    done_cold = _passed_debian_iters(all_ledger_rows, "debian_full", "cold_provisioned", ps, require_provenance=True)
                    done_warm = _passed_debian_iters(all_ledger_rows, "debian_full", "warm_restart", ps, require_provenance=True)
                    sched_count = max(args.debian_cold_samples, args.debian_warm_samples)
                    for block_kind, indices in build_cold_warm_block_schedule(sched_count, block_size=5):
                        if block_kind == "cold_provisioned":
                            for idx in indices:
                                if idx > args.debian_cold_samples or (args.resume and idx in done_cold):
                                    continue
                                tb, _ = wait_for_thermal_cooldown(adb, args.max_temp_c)
                                adb.run("shell", "am", "force-stop", target.app_package, check=False, timeout=20)
                                t0 = time.monotonic()
                                proc, parsed = _run_instrument(
                                    adb, "debian_cold_boot", extras={"iteration": str(idx)}, timeout=300, log_path=raw_log,
                                    instrumentation_component=target.instrumentation_component,
                                )
                                ta = read_battery_temperature_c(adb)
                                dur = int((time.monotonic() - t0) * 1000)
                                res_cnt = len(_check_residual_processes(adb, qa_package=target.app_package))
                                samp = parsed["samples"][0] if parsed["samples"] else {"status": "FAIL", "error": _instrument_failure_detail(proc, parsed)}
                                _record_rows([
                                    _build_perf_row_from_sample(
                                        sample=samp,
                                        run_id=run_id,
                                        record_type="perf_sample",
                                        suite="debian_full",
                                        bucket="cold_provisioned",
                                        sample_id=f"p{ps}-debian-cold_provisioned-{idx:03d}",
                                        host_page_size=ps,
                                        device_alias=alias,
                                        temp_before=tb,
                                        temp_after=ta,
                                        host_duration_ms=dur,
                                        residual_count=res_cnt,
                                    )
                                ])
                        else:
                            pending_warm = [
                                idx for idx in indices
                                if idx <= args.debian_warm_samples and not (args.resume and idx in done_warm)
                            ]
                            if not pending_warm:
                                continue
                            for batch in bench.contiguous_pair_batches(pending_warm, 5):
                                tb, _ = wait_for_thermal_cooldown(adb, args.max_temp_c)
                                t0 = time.monotonic()
                                proc, parsed = _run_instrument(
                                    adb,
                                    "debian_warm_restart_batch",
                                    extras={
                                        "start_iter": str(batch[0]),
                                        "count": str(len(batch)),
                                        "warmup": "true",
                                    },
                                    timeout=1200,
                                    log_path=raw_log,
                                    instrumentation_component=target.instrumentation_component,
                                )
                                ta = read_battery_temperature_c(adb)
                                dur = int((time.monotonic() - t0) * 1000)
                                res_cnt = len(_check_residual_processes(adb, qa_package=target.app_package))
                                samples_by_iter = {
                                    int(s["iteration"]): s
                                    for s in parsed["samples"]
                                    if isinstance(s.get("iteration"), int)
                                }
                                rows_to_add = []
                                for idx in batch:
                                    samp = samples_by_iter.get(idx) or {
                                        "status": "FAIL",
                                        "iteration": idx,
                                        "error": _instrument_failure_detail(proc, parsed),
                                    }
                                    rows_to_add.append(
                                        _build_perf_row_from_sample(
                                            sample=samp,
                                            run_id=run_id,
                                            record_type="perf_sample",
                                            suite="debian_full",
                                            bucket="warm_restart",
                                            sample_id=f"p{ps}-debian-warm_restart-{idx:03d}",
                                            host_page_size=ps,
                                            device_alias=alias,
                                            temp_before=tb,
                                            temp_after=ta,
                                            host_duration_ms=dur,
                                            residual_count=res_cnt,
                                        )
                                    )
                                _record_rows(rows_to_add)

                    done_rec = _passed_debian_iters(all_ledger_rows, "debian_full", "warm_reconnect", ps, require_provenance=True)
                    if not (args.resume and len(done_rec) >= args.debian_reconnect_samples):
                        tb, _ = wait_for_thermal_cooldown(adb, args.max_temp_c)
                        t0 = time.monotonic()
                        proc, parsed = _run_instrument(
                            adb,
                            "debian_warm_reconnect",
                            extras={"count": str(args.debian_reconnect_samples)},
                            timeout=300,
                            log_path=raw_log,
                            instrumentation_component=target.instrumentation_component,
                        )
                        ta = read_battery_temperature_c(adb)
                        dur = int((time.monotonic() - t0) * 1000)
                        res_cnt = len(_check_residual_processes(adb, qa_package=target.app_package))
                        rows_to_add = [
                            _build_perf_row_from_sample(
                                sample=s,
                                run_id=run_id,
                                record_type="perf_sample",
                                suite="debian_full",
                                bucket="warm_reconnect",
                                sample_id=f"p{ps}-debian-warm_reconnect-{int(s.get('iteration') or (i + 1)):03d}",
                                host_page_size=ps,
                                device_alias=alias,
                                temp_before=tb,
                                temp_after=ta,
                                host_duration_ms=dur,
                                residual_count=res_cnt,
                            )
                            for i, s in enumerate(parsed["samples"])
                            if not (args.resume and int(s.get("iteration") or (i + 1)) in done_rec)
                        ]
                        if not parsed["samples"]:
                            first_missing = next(
                                (i for i in range(1, args.debian_reconnect_samples + 1) if i not in done_rec),
                                1,
                            )
                            if not (args.resume and first_missing in done_rec):
                                rows_to_add.append(
                                    _build_perf_row_from_sample(
                                        sample={"status": "FAIL", "iteration": first_missing, "error": parsed["result"].get("w0105_error") or "no_samples"},
                                        run_id=run_id,
                                        record_type="perf_sample",
                                        suite="debian_full",
                                        bucket="warm_reconnect",
                                        sample_id=f"p{ps}-debian-warm_reconnect-{first_missing:03d}",
                                        host_page_size=ps,
                                        device_alias=alias,
                                        temp_before=tb,
                                        temp_after=ta,
                                        host_duration_ms=dur,
                                        residual_count=res_cnt,
                                    )
                                )
                        _record_rows(rows_to_add)

                if args.mode in ("alpine_paired", "all"):
                    def _run_alp_prep(_attempt: int) -> dict[str, Any]:
                        adb.run("shell", "am", "force-stop", target.app_package, check=False, timeout=20)
                        time.sleep(1.0)
                        return exec_functional("alpine_paired_prepare", "alpine_paired_prepare", timeout=600)

                    if not prepare_with_single_retry(_run_alp_prep):
                        print(json.dumps({"error": "alpine_paired_prepare_failed_fail_fast"}), file=sys.stderr)
                        _write_gate_summary(
                            args.evidence_dir, ledger_path,
                            apk_verification=apk_16kb, payload_gate_ok=payload_ok,
                            compat_blocked=compat_blocked, min_debian_cold_warm=args.debian_cold_samples,
                            min_alpine_pairs=args.alpine_pairs, run_id=run_id, mode=args.mode, device_facts=device_facts,
                        )
                        return 1
                    done_acold = _passed_paired_indices(all_ledger_rows, "cold_provisioned", ps, require_provenance=True)
                    done_awarm = _passed_paired_indices(all_ledger_rows, "warm_restart", ps, require_provenance=True)
                    for block_kind, indices in build_cold_warm_block_schedule(args.alpine_pairs, block_size=5):
                        if block_kind == "cold_provisioned":
                            for pair_idx in indices:
                                if args.resume and pair_idx in done_acold:
                                    continue
                                for var in paired_profile_order(pair_idx):
                                    sid = f"p{ps}-alpine-cold_provisioned-pair{pair_idx:03d}-{var}"
                                    if args.resume and sid in passed_sids:
                                        continue
                                    tb, _ = wait_for_thermal_cooldown(adb, args.max_temp_c)
                                    adb.run("shell", "am", "force-stop", target.app_package, check=False, timeout=20)
                                    t0 = time.monotonic()
                                    proc, parsed = _run_instrument(
                                        adb,
                                        "alpine_paired_cold",
                                        extras={"variant": var, "pair_index": str(pair_idx)},
                                        timeout=240,
                                        log_path=raw_log,
                                        instrumentation_component=target.instrumentation_component,
                                    )
                                    ta = read_battery_temperature_c(adb)
                                    dur = int((time.monotonic() - t0) * 1000)
                                    res_cnt = len(_check_residual_processes(adb, qa_package=target.app_package))
                                    samp = parsed["samples"][0] if parsed["samples"] else {
                                        "status": "FAIL",
                                        "variant": var,
                                        "pair_index": pair_idx,
                                        "iteration": pair_idx,
                                        "error": _instrument_failure_detail(proc, parsed),
                                    }
                                    _record_rows([
                                        _build_perf_row_from_sample(
                                            sample=samp,
                                            run_id=run_id,
                                            record_type="paired_sample",
                                            suite="alpine_paired_fast_vs_full",
                                            bucket="cold_provisioned",
                                            sample_id=sid,
                                            host_page_size=ps,
                                            device_alias=alias,
                                            temp_before=tb,
                                            temp_after=ta,
                                            host_duration_ms=dur,
                                            residual_count=res_cnt,
                                        )
                                    ])
                        else:
                            pending_warm = [idx for idx in indices if not (args.resume and idx in done_awarm)]
                            if not pending_warm:
                                continue
                            for batch in bench.contiguous_pair_batches(pending_warm, 5):
                                tb, _ = wait_for_thermal_cooldown(adb, args.max_temp_c)
                                t0 = time.monotonic()
                                proc, parsed = _run_instrument(
                                    adb,
                                    "alpine_paired_warm_batch",
                                    extras={
                                        "start_iter": str(batch[0]),
                                        "count": str(len(batch)),
                                        "warmup": "true",
                                    },
                                    timeout=600,
                                    log_path=raw_log,
                                    instrumentation_component=target.instrumentation_component,
                                )
                                ta = read_battery_temperature_c(adb)
                                dur = int((time.monotonic() - t0) * 1000)
                                res_cnt = len(_check_residual_processes(adb, qa_package=target.app_package))
                                samples_by_key = {
                                    (int(s.get("pair_index") or 0), str(s.get("variant") or "")): s
                                    for s in parsed["samples"]
                                }
                                rows_to_add = []
                                err_detail = _instrument_failure_detail(proc, parsed)
                                for p_idx in batch:
                                    for var in paired_profile_order(p_idx):
                                        sid = f"p{ps}-alpine-warm_restart-pair{p_idx:03d}-{var}"
                                        if args.resume and sid in passed_sids:
                                            continue
                                        samp = samples_by_key.get((p_idx, var)) or {
                                            "status": "FAIL",
                                            "variant": var,
                                            "pair_index": p_idx,
                                            "iteration": p_idx,
                                            "error": err_detail,
                                        }
                                        rows_to_add.append(
                                            _build_perf_row_from_sample(
                                                sample=samp,
                                                run_id=run_id,
                                                record_type="paired_sample",
                                                suite="alpine_paired_fast_vs_full",
                                                bucket="warm_restart",
                                                sample_id=sid,
                                                host_page_size=ps,
                                                device_alias=alias,
                                                temp_before=tb,
                                                temp_after=ta,
                                                host_duration_ms=dur,
                                                residual_count=res_cnt,
                                            )
                                        )
                                _record_rows(rows_to_add)

                    done_arec = _passed_paired_indices(all_ledger_rows, "warm_reconnect", ps, require_provenance=True)
                    if not (args.resume and len(done_arec) >= args.alpine_pairs):
                        tb, _ = wait_for_thermal_cooldown(adb, args.max_temp_c)
                        t0 = time.monotonic()
                        proc, parsed = _run_instrument(
                            adb,
                            "alpine_paired_reconnect",
                            extras={"count": str(args.alpine_pairs)},
                            timeout=360,
                            log_path=raw_log,
                            instrumentation_component=target.instrumentation_component,
                        )
                        ta = read_battery_temperature_c(adb)
                        dur = int((time.monotonic() - t0) * 1000)
                        res_cnt = len(_check_residual_processes(adb, qa_package=target.app_package))
                        samples_by_key = {
                            (int(s.get("pair_index") or 0), str(s.get("variant") or "")): s
                            for s in parsed["samples"]
                        }
                        rows_to_add = []
                        err_detail = _instrument_failure_detail(proc, parsed)
                        for p_idx in range(1, args.alpine_pairs + 1):
                            for var in paired_profile_order(p_idx):
                                sid = f"p{ps}-alpine-warm_reconnect-pair{p_idx:03d}-{var}"
                                if args.resume and sid in passed_sids:
                                    continue
                                samp = samples_by_key.get((p_idx, var)) or {
                                    "status": "FAIL",
                                    "variant": var,
                                    "pair_index": p_idx,
                                    "iteration": p_idx,
                                    "error": err_detail,
                                }
                                rows_to_add.append(
                                    _build_perf_row_from_sample(
                                        sample=samp,
                                        run_id=run_id,
                                        record_type="paired_sample",
                                        suite="alpine_paired_fast_vs_full",
                                        bucket="warm_reconnect",
                                        sample_id=sid,
                                        host_page_size=ps,
                                        device_alias=alias,
                                        temp_before=tb,
                                        temp_after=ta,
                                        host_duration_ms=dur,
                                        residual_count=res_cnt,
                                    )
                                )
                        _record_rows(rows_to_add)

                if args.mode in ("qemu_ref", "all"):
                    qemu_func_sid = f"p{ps}-func-qemu_debian_ref"
                    if not (args.resume and qemu_func_sid in passed_sids):
                        tb, _ = wait_for_thermal_cooldown(adb, args.max_temp_c)
                        t0 = time.monotonic()
                        proc, parsed = _run_instrument(
                            adb,
                            "qemu_debian_ref",
                            extras={"count": str(args.qemu_ref_samples)},
                            timeout=1200,
                            log_path=raw_log,
                            instrumentation_component=target.instrumentation_component,
                        )
                        ta = read_battery_temperature_c(adb)
                        dur = int((time.monotonic() - t0) * 1000)
                        res_cnt = len(_check_residual_processes(adb, qa_package=target.app_package))
                        q_rows = []
                        for idx, samp in enumerate(parsed["samples"]):
                            b_name = str(samp.get("bucket") or "cold_provisioned")
                            it = int(samp.get("iteration") or (idx + 1))
                            sid = f"p{ps}-qemu-debian-{b_name}-{it:03d}"
                            if args.resume and sid in passed_sids:
                                continue
                            q_rows.append(
                                _build_perf_row_from_sample(
                                    sample=samp,
                                    run_id=run_id,
                                    record_type="perf_sample",
                                    suite="qemu_debian_ref",
                                    bucket=b_name,
                                    sample_id=sid,
                                    host_page_size=ps,
                                    device_alias=alias,
                                    temp_before=tb,
                                    temp_after=ta,
                                    host_duration_ms=dur,
                                    residual_count=res_cnt,
                                )
                            )
                        _record_rows(q_rows)
                        ref_passed = proc.returncode == 0 and parsed["result"].get("w0105_status") == "PASS" and res_cnt == 0
                        _record_rows([
                            _build_functional_row(
                                run_id=run_id,
                                check_name="qemu_debian_ref",
                                host_page_size=ps,
                                device_alias=alias,
                                status="PASS" if ref_passed else "FAIL",
                                detail=(
                                    parsed["result"].get("w0105_summary") or "ok"
                                    if ref_passed
                                    else _instrument_failure_detail(proc, parsed)
                                ),
                                residual_count=res_cnt,
                            )
                        ])

            summary = _write_gate_summary(
                args.evidence_dir,
                ledger_path,
                apk_verification=apk_16kb,
                payload_gate_ok=payload_ok,
                compat_blocked=compat_blocked,
                min_debian_cold_warm=args.debian_cold_samples,
                min_alpine_pairs=args.alpine_pairs,
                run_id=run_id,
                mode=args.mode,
                device_facts=device_facts,
            )
    except (prov.EvidenceProvenanceError, qa_guard.QaGuardError) as exc:
        diffs = exc.diffs if isinstance(exc, prov.EvidenceProvenanceError) else None
        return _emit_error(exc.code, exc.detail, diffs=diffs)

    print(json.dumps({
        "run_id": run_id,
        "mode": args.mode,
        "device_alias": alias,
        "qa_package": target.app_package,
        "install_report": {
            "mode": install_report.get("mode"),
            "clean_uninstall": install_report.get("clean_uninstall"),
            "verified": install_report.get("verified"),
        },
        "overall_verdict": summary["overall_verdict"],
        "signoff_allowed": summary["signoff_allowed"],
        "provenance_digest": summary.get("provenance_digest"),
        "wave_receipt_counts": summary["wave_receipt"]["counts"],
    }, ensure_ascii=True))
    return 0 if summary["overall_verdict"] in ("PASS", "PASS_4K_ONLY_PENDING_16K") else 1


def main() -> int:
    return run_cli()


if __name__ == "__main__":
    sys.exit(main())
