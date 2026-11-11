#!/usr/bin/env python3
"""Parse U6 multi-instance bench outputs into the summary table.

Input is one or more run directories produced by bench.sh, each containing:
  manifest.json   fixture identity (kernel/rootfs sha256, host, mode)
  instances.csv   one row per launched instance (rc, ready, fail mode, cpu, ...)
  samples.csv     sampler rows (boot/steady ticks: rss, pss, cpu, host counters)

Aggregation semantics (documented in the U6 report):
  - steady window = samples.csv rows with phase=steady
  - per-instance rss median = median of rss_main_kb within the steady window
  - per-N rss_main_med = median over all ready instances of all rounds pooled
  - host rss sum = per tick sum over live instances of (rss_main + rss_other);
    per round take the median of tick sums, per N take the median over rounds
  - cpu seconds = cpu_ticks / clk_tck (manifest host.clk_tck, default 100)
  - ready_ms uses ready instances only; all_ready_ms per round = max
  - a round whose instances all carry fail_mode=host_mem_skip is counted as
    skipped and contributes no rss/cpu numbers
  - failures never silently dropped: fail counts and fail_modes are reported

Usage:
  python parse.py <run_dir> [more_run_dirs...] [--format md|json]
  python parse.py --self-test
"""
from __future__ import annotations

import argparse
import csv
import json
import statistics
import sys
import tempfile
from pathlib import Path

INST_HEADER = (
    "n,round,inst,pgid,rc,ready_ok,fail_mode,ready_host_ms,t3_guest_uptime_s,"
    "t2_guest_uptime_s,rss_hwm_kb,rss_end_kb,pss_end_kb,cpu_boot_ticks,"
    "cpu_steady_ticks,ram_apparent_bytes,ram_blocks_bytes,tasks_end"
)
SAMP_HEADER = (
    "n,round,phase,ts_rel_ms,inst,rss_main_kb,pss_main_kb,rss_other_kb,"
    "pss_other_kb,cpu_ticks_cum,tasks,host_memavail_kb,host_cpu_busy_cum,"
    "host_cpu_all_cum"
)


def _read_csv(path: Path) -> list[dict]:
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def _median(values):
    values = [v for v in values if v is not None]
    if not values:
        return None
    return statistics.median(values)


def _to_int(value, default=None):
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return default


class RunData:
    """One bench run directory."""

    def __init__(self, run_dir: Path):
        self.dir = run_dir
        manifest_path = run_dir / "manifest.json"
        inst_path = run_dir / "instances.csv"
        samp_path = run_dir / "samples.csv"
        missing = [p.name for p in (manifest_path, inst_path, samp_path) if not p.is_file()]
        if missing:
            raise SystemExit("parse.py: %s lacks %s" % (run_dir, ", ".join(missing)))
        self.manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.instances = _read_csv(inst_path)
        self.samples = _read_csv(samp_path)
        self.clk_tck = int(self.manifest.get("host", {}).get("clk_tck", 100) or 100)

    # -- grouping helpers ---------------------------------------------------

    def rounds(self):
        out = {}
        for row in self.instances:
            key = (int(row["n"]), int(row["round"]))
            out.setdefault(key, []).append(row)
        return out

    def steady_rows(self, n, rnd):
        return [r for r in self.samples
                if int(r["n"]) == n and int(r["round"]) == rnd and r["phase"] == "steady"]

    def round_is_skipped(self, rows):
        return bool(rows) and all(
            (r.get("fail_mode") or "") == "host_mem_skip" for r in rows
        )

    # -- aggregation ---------------------------------------------------------

    def aggregate(self):
        """Aggregate this run into per-N summaries."""
        per_n = {}
        for (n, rnd), rows in sorted(self.rounds().items()):
            slot = per_n.setdefault(n, {
                "n": n, "rounds": [], "skipped_rounds": 0,
                "instances": 0, "ready_ok": 0, "fails": 0, "fail_modes": {},
            })
            if self.round_is_skipped(rows):
                # resource stop condition: counted, but not a runtime failure
                slot["skipped_rounds"] += 1
                slot["instances"] += len(rows)
                slot["fail_modes"]["host_mem_skip"] = (
                    slot["fail_modes"].get("host_mem_skip", 0) + len(rows))
                continue
            ready_ms = []
            cpu_ticks = []
            ram_a = []
            for r in rows:
                slot["instances"] += 1
                if r.get("fail_mode") and r["fail_mode"] != "none":
                    slot["fails"] += 1
                    mode = r["fail_mode"]
                    slot["fail_modes"][mode] = slot["fail_modes"].get(mode, 0) + 1
                if _to_int(r.get("ready_ok"), 0) == 1:
                    slot["ready_ok"] += 1
                    ms = _to_int(r.get("ready_host_ms"), None)
                    if ms is not None and ms >= 0:
                        ready_ms.append(ms)
                    ct = _to_int(r.get("cpu_steady_ticks"), None)
                    if ct is not None:
                        cpu_ticks.append(ct)
                    ra = _to_int(r.get("ram_apparent_bytes"), None)
                    if ra is not None:
                        ram_a.append(ra)
            # per-instance steady medians
            inst_med_rss = []
            inst_med_pss = []
            srows = self.steady_rows(n, rnd)
            by_inst = {}
            for s in srows:
                by_inst.setdefault(int(s["inst"]), []).append(s)
            for inst, slist in sorted(by_inst.items()):
                inst_med_rss.append(_median([_to_int(s["rss_main_kb"], 0) for s in slist]))
                inst_med_pss.append(_median([_to_int(s["pss_main_kb"], 0) for s in slist]))
            # host sums: per tick, sum over live instances; median of tick sums
            tick_sums = {}
            tick_pss = {}
            for s in srows:
                key = _to_int(s["ts_rel_ms"], 0)
                acc = tick_sums.setdefault(key, 0)
                acc += _to_int(s["rss_main_kb"], 0) + _to_int(s["rss_other_kb"], 0)
                tick_sums[key] = acc
                accp = tick_pss.setdefault(key, 0)
                accp += _to_int(s["pss_main_kb"], 0) + _to_int(s["pss_other_kb"], 0)
                tick_pss[key] = accp
            host_rss = _median(list(tick_sums.values()))
            host_pss = _median(list(tick_pss.values()))
            all_ready_ms = max(ready_ms) if ready_ms else None
            slot["rounds"].append({
                "round": rnd,
                "instances": len(rows),
                "ready_ok": len(ready_ms),
                "fails": len(rows) - len(ready_ms),
                "ready_ms_median": _median(ready_ms),
                "all_ready_ms": all_ready_ms,
                "host_rss_sum_median_kb": host_rss,
                "host_pss_sum_median_kb": host_pss,
                "inst_rss_medians_kb": inst_med_rss,
                "inst_pss_medians_kb": inst_med_pss,
                "cpu_steady_ticks": cpu_ticks,
                "ram_apparent_bytes_median": _median(ram_a),
            })

        for slot in per_n.values():
            pooled = []
            pooled_pss = []
            pooled_ready = []
            pooled_all_ready = []
            pooled_cpu = []
            pooled_ram = []
            for rd in slot["rounds"]:
                pooled.extend(rd["inst_rss_medians_kb"])
                pooled_pss.extend(rd["inst_pss_medians_kb"])
                if rd["ready_ms_median"] is not None:
                    pooled_ready.append(rd["ready_ms_median"])
                if rd["all_ready_ms"] is not None:
                    pooled_all_ready.append(rd["all_ready_ms"])
                pooled_cpu.extend(rd["cpu_steady_ticks"])
                if rd["ram_apparent_bytes_median"] is not None:
                    pooled_ram.append(rd["ram_apparent_bytes_median"])
            host_rss = [rd["host_rss_sum_median_kb"] for rd in slot["rounds"]
                        if rd["host_rss_sum_median_kb"] is not None]
            host_pss = [rd["host_pss_sum_median_kb"] for rd in slot["rounds"]
                        if rd["host_pss_sum_median_kb"] is not None]
            slot["rss_main_med_kb"] = _median(pooled)
            slot["pss_main_med_kb"] = _median(pooled_pss)
            slot["host_rss_sum_med_kb"] = _median(host_rss)
            slot["host_pss_sum_med_kb"] = _median(host_pss)
            slot["ready_ms_med"] = _median(pooled_ready)
            slot["all_ready_ms_med"] = _median(pooled_all_ready)
            slot["cpu_steady_s_med"] = (
                _median([t / self.clk_tck for t in pooled_cpu])
                if pooled_cpu else None)
            slot["ram_file_apparent_med_b"] = _median(pooled_ram)
        return per_n


# ---- rendering ---------------------------------------------------------------

def _fmt(value, digits=1):
    if value is None:
        return "-"
    if isinstance(value, float):
        return "%.*f" % (digits, value)
    return str(value)


def render_md(runs):
    lines = ["# U6 UML multi-instance bench summary", ""]
    for run in runs:
        m = run.manifest
        ksha = (m.get("kernel", {}) or {}).get("sha256", "")[:12]
        rsha = (m.get("rootfs_tar", {}) or {}).get("sha256", "")[:12]
        mode = (m.get("uml", {}) or {}).get("userspace_mode", "?")
        host = (m.get("host", {}) or {}).get("uname_release", "?")
        lines.append("run: `%s`" % run.dir)
        lines.append("fixture: kernel=%s rootfs_tar=%s userspace_mode=%s host=%s"
                     % (ksha or "?", rsha or "?", mode, host))
        lines.append("")
        lines.append("| N | rounds | skipped | inst | ready_ok | fail | fail_modes"
                     " | rss_main_med kB | pss_main_med kB | host_rss_sum_med kB"
                     " | host_pss_sum_med kB | ready_ms_med | all_ready_ms_med"
                     " | cpu_steady_s_med | ram_file_B |")
        lines.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        agg = run.aggregate()
        for n in sorted(agg):
            s = agg[n]
            modes = ",".join("%s:%d" % (k, v)
                             for k, v in sorted(s["fail_modes"].items())) or "-"
            rounds_n = len(s["rounds"]) + s["skipped_rounds"]
            lines.append("| %d | %d | %d | %d | %d | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s |"
                         % (n, rounds_n, s["skipped_rounds"], s["instances"],
                            s["ready_ok"], s["fails"], modes,
                            _fmt(s["rss_main_med_kb"], 1),
                            _fmt(s["pss_main_med_kb"], 1),
                            _fmt(s["host_rss_sum_med_kb"], 1),
                            _fmt(s["host_pss_sum_med_kb"], 1),
                            _fmt(s["ready_ms_med"], 0),
                            _fmt(s["all_ready_ms_med"], 0),
                            _fmt(s["cpu_steady_s_med"], 3),
                            _fmt(s["ram_file_apparent_med_b"], 0)))
        lines.append("")
        lines.append("Per-round detail:")
        lines.append("")
        lines.append("| N | round | inst | ready_ok | fail | all_ready_ms"
                     " | host_rss_sum_med kB | ram_file_B |")
        lines.append("|---|---|---|---|---|---|---|---|")
        for (n, rnd), rows in sorted(run.rounds().items()):
            skipped = run.round_is_skipped(rows)
            ready = sum(1 for r in rows if _to_int(r.get("ready_ok"), 0) == 1)
            fails = len(rows) - ready
            modes = ",".join(sorted({(r.get("fail_mode") or "none") for r in rows
                                     if (r.get("fail_mode") or "none") != "none"})) or "-"
            agg_n = agg[n]
            rd = next((x for x in agg_n["rounds"] if x["round"] == rnd), None)
            if skipped:
                lines.append("| %d | %d | %d | 0 | %d | - | (skipped: host_mem_skip) | - |"
                             % (n, rnd, len(rows), fails))
            elif rd is None:
                lines.append("| %d | %d | %d | %d | %d | - | (no steady samples) | - |"
                             % (n, rnd, len(rows), ready, fails))
            else:
                lines.append("| %d | %d | %d | %d | %d | %s | %s | %s |"
                             % (n, rnd, len(rows), ready, fails,
                                _fmt(rd["all_ready_ms"], 0),
                                _fmt(rd["host_rss_sum_median_kb"], 1),
                                _fmt(rd["ram_apparent_bytes_median"], 0)))
        lines.append("")
    return "\n".join(lines)


def render_json(runs):
    out = []
    for run in runs:
        agg = run.aggregate()
        for n, s in agg.items():
            for rd in s["rounds"]:
                rd["cpu_steady_s_inst_median"] = (
                    _median([t / run.clk_tck for t in rd["cpu_steady_ticks"]])
                    if rd["cpu_steady_ticks"] else None)
        out.append({
            "run_dir": str(run.dir),
            "manifest": run.manifest,
            "clk_tck": run.clk_tck,
            "per_n": agg,
        })
    return json.dumps(out, indent=2, sort_keys=False)


# ---- self-test ---------------------------------------------------------------

SELF_TEST_MANIFEST = {
    "schema": 1,
    "kernel": {"sha256": "a" * 64},
    "rootfs_tar": {"sha256": "b" * 64},
    "uml": {"userspace_mode": "SECCOMP"},
    "host": {"uname_release": "self-test-host", "clk_tck": 100},
}


def _build_self_test_run(root: Path):
    """Deterministic fixture with hand-computed expectations."""
    (root / "manifest.json").write_text(
        json.dumps(SELF_TEST_MANIFEST), encoding="utf-8")
    inst_lines = [INST_HEADER]
    # round 1: inst0 ok, inst1 ok, inst2 early_exit(137)
    inst_lines.append("1,1,0,11,1,1,none,100,0.20,0.10,300,300,290,50,30,0,4096,2")
    inst_lines.append("1,1,1,12,1,1,none,200,0.22,0.11,1400,1400,1300,60,40,0,4096,2")
    inst_lines.append("1,1,2,13,137,0,early_exit,-1,-1,-1,0,0,0,10,0,0,0,0")
    # round 2: inst0 ok, inst1 ok
    inst_lines.append("1,2,0,21,1,1,none,300,0.21,0.12,300,300,290,50,30,0,4096,2")
    inst_lines.append("1,2,1,22,1,1,none,400,0.23,0.13,1400,1400,1300,60,40,0,4096,2")
    # round 3: skipped (host memory floor)
    inst_lines.append("1,3,0,31,-1,0,host_mem_skip,-1,-1,-1,0,0,0,0,0,0,0,0")
    (root / "instances.csv").write_text("\n".join(inst_lines) + "\n", encoding="utf-8")

    samp_lines = [SAMP_HEADER]
    # steady ticks for rounds 1 and 2; inst2 died before steady -> absent
    for rnd, base in ((1, 1000), (2, 2000)):
        for t, rss0 in ((0, 100), (100, 200), (200, 300)):
            tick = base + t
            # inst0: rss rises 100,200,300; other 0; cpu_cum 10,11,12
            samp_lines.append(
                "1,%d,steady,%d,0,%d,%d,0,0,%d,2,7000000,900,%d"
                % (rnd, tick, rss0, rss0 - 10, 10 + t // 100, tick))
            # inst1: rss constant 1000, other 5
            samp_lines.append(
                "1,%d,steady,%d,1,1000,990,5,4,%d,2,7000000,900,%d"
                % (rnd, tick, 10 + t // 100, tick))
    (root / "samples.csv").write_text("\n".join(samp_lines) + "\n", encoding="utf-8")


def self_test() -> int:
    checks = []

    def check(name, got, want):
        ok = got == want
        checks.append(ok)
        print("%-42s %s (got=%r want=%r)" % (
            name, "PASS" if ok else "FAIL", got, want))

    with tempfile.TemporaryDirectory() as tmp:
        run_dir = Path(tmp) / "fake-run"
        run_dir.mkdir()
        _build_self_test_run(run_dir)
        run = RunData(run_dir)
        agg = run.aggregate()
        slot = agg[1]

        check("rounds recorded", len(slot["rounds"]), 2)
        check("skipped rounds", slot["skipped_rounds"], 1)
        check("instances counted", slot["instances"], 6)
        check("ready_ok counted", slot["ready_ok"], 4)
        check("failures counted", slot["fails"], 1)
        check("fail_modes", slot["fail_modes"], {"early_exit": 1, "host_mem_skip": 1})
        # inst0 steady rss median 200 in both rounds, inst1 1000 -> pooled median 600
        check("rss_main_med_kb", slot["rss_main_med_kb"], 600.0)
        check("pss_main_med_kb", slot["pss_main_med_kb"], 590.0)
        # tick sums: 100+0+1000+5=1105, 1205, 1305 -> median 1205
        check("host_rss_sum_med_kb", slot["host_rss_sum_med_kb"], 1205.0)
        # pss tick sums: 90+990+4=1084, 1184, 1284 -> median 1184
        check("host_pss_sum_med_kb", slot["host_pss_sum_med_kb"], 1184.0)
        # ready insts 100,200,300,400 -> median 250
        check("ready_ms_med", slot["ready_ms_med"], 250.0)
        # all_ready per round 200, 400 -> median 300
        check("all_ready_ms_med", slot["all_ready_ms_med"], 300.0)
        # cpu_steady ticks 30,40,30,40 -> seconds 0.3,0.4,0.3,0.4 -> 0.35
        check("cpu_steady_s_med", slot["cpu_steady_s_med"], 0.35)
        check("ram_file_apparent_med_b", slot["ram_file_apparent_med_b"], 0.0)
        # round detail slots
        rd1 = slot["rounds"][0]
        check("round1 ready count", rd1["ready_ok"], 2)
        check("round1 fails", rd1["fails"], 1)
        check("round1 all_ready_ms", rd1["all_ready_ms"], 200)
        check("round1 inst rss medians", rd1["inst_rss_medians_kb"], [200.0, 1000.0])

        md = render_md([run])
        check("md renders",
              ("| 1 | 3 | 1 | 6 | 4 | 1 | early_exit:1,host_mem_skip:1 | 600.0" in md),
              True)
        js = json.loads(render_json([run]))
        check("json valid + per_n", (js[0]["per_n"]["1"]["rss_main_med_kb"]), 600.0)

    # missing-file error path
    with tempfile.TemporaryDirectory() as tmp:
        empty = Path(tmp) / "empty"
        empty.mkdir()
        try:
            RunData(empty)
            check("missing files raise", "no-error", "error")
        except SystemExit as exc:
            check("missing files raise", "manifest.json" in str(exc), True)

    if all(checks):
        print("self-test: ALL PASS (%d checks)" % len(checks))
        return 0
    print("self-test: FAILURES PRESENT")
    return 1


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("runs", nargs="*", help="bench.sh run directories")
    ap.add_argument("--format", choices=["md", "json"], default="md")
    ap.add_argument("--self-test", action="store_true",
                    help="run built-in assertions against a synthetic run")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()
    if not args.runs:
        ap.error("need at least one run dir (or --self-test)")
    runs = [RunData(Path(p)) for p in args.runs]
    if args.format == "json":
        print(render_json(runs))
    else:
        print(render_md(runs))
    return 0


if __name__ == "__main__":
    sys.exit(main())
