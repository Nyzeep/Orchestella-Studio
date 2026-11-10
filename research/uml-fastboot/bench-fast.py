#!/usr/bin/env python3
"""U1 fast-shell UML boot bench (T0-T3).

Runs a JSON matrix of cases round-robin, captures the guest console, and
reports host-observed T0->T3 plus guest-clock T1/T2/T3.  Pure Python 3
standard library; no adb, no Android SDK.

Matrix schema (see bench-matrix.example.json):

    {
      "fixture": "x86_64-host-debian13-4k",
      "mem": "256M",
      "n": 5,
      "hold_ms": 200,
      "timeout_s": 30,
      "cases": [
        {"label": "initramfs-up", "kernel": ".../linux-fast",
         "mode": "initramfs", "ncpus": 1},
        {"label": "hostfs-up", "kernel": ".../linux-fast-noinitrd",
         "mode": "hostfs", "root": ".../rootfs-x86_64", "ncpus": 1},
        {"label": "ubd-up", "kernel": ".../linux-fast-noinitrd",
         "mode": "ubd", "root": ".../rootfs-x86_64.ext4", "ncpus": 1}
      ]
    }

Timing contract (spec §2):
  T0 = host monotonic clock immediately after Popen() returns (exec returned);
  T1 = guest kernel timestamp of the "Run /init as init process" line;
  T2 = guest /proc/uptime when the fast init finished the pseudo-fs mounts;
  T3 = guest /proc/uptime when /um-command.sh printed U1_READY, plus the
       host-observed T0->READY duration.
The two clocks are never mixed in one column.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path

T1_RE = re.compile(r"^\[\s*([0-9]+\.[0-9]+)\]\s+Run /\S+ as init process")
T2_RE = re.compile(r"^U1_T2_READY uptime=([0-9.]+)")
READY_RE = re.compile(r"^U1_READY uptime=([0-9.]+)")
INFO_RE = re.compile(r"^U1_INFO kernel=(\S+) ncpus=(\d+)")
ERR_RE = re.compile(r"^(UM_INIT_ERROR|U1_CMD_ERROR)\b")
PANIC_RE = re.compile(r"Kernel panic")
MODE_RE = re.compile(r"Userspace mode: (SECCOMP|ptrace)")


def resolve(base: Path, value: str | None) -> str | None:
    if value is None:
        return None
    p = Path(value)
    if not p.is_absolute():
        p = base / p
    return str(p)


def build_argv(case: dict, defaults: dict, base: Path) -> list[str]:
    kernel = resolve(base, case["kernel"])
    if not kernel or not os.path.isfile(kernel):
        raise SystemExit(f"kernel not found: {kernel!r}")
    argv = [
        kernel,
        f"mem={case.get('mem', defaults.get('mem', '256M'))}",
        "con=null",
        "con0=null,fd:1",
        f"seccomp={case.get('seccomp', defaults.get('seccomp', 'auto'))}",
        "panic=1",
        "printk.time=1",
        f"um.hold_ms={case.get('hold_ms', defaults.get('hold_ms', 200))}",
    ]
    stub = resolve(base, case.get("stub"))
    if stub:
        argv.append(f"stub_exe={stub}")
    mode = case["mode"]
    if mode == "hostfs":
        root = resolve(base, case.get("root"))
        if not root:
            raise SystemExit("hostfs case needs 'root'")
        argv += ["rw", "init=/init", "rootfstype=hostfs",
                 f"rootflags={root}"]
    elif mode == "ubd":
        root = resolve(base, case.get("root"))
        if not root:
            raise SystemExit("ubd case needs 'root'")
        argv += ["rw", f"ubd0={root}", "root=/dev/ubda",
                 "rootfstype=ext4", "init=/init"]
    elif mode == "hostfs-initramfs":
        root = resolve(base, case.get("root"))
        if not root:
            raise SystemExit("hostfs-initramfs case needs 'root'")
        argv += ["rw", f"umroot={root}", "umrootfstype=hostfs"]
    elif mode == "ubd-initramfs":
        root = resolve(base, case.get("root"))
        if not root:
            raise SystemExit("ubd-initramfs case needs 'root'")
        argv += ["rw", f"ubd0={root}", f"umroot=/dev/ubda",
                 "umrootfstype=ext4"]
    elif mode == "initramfs":
        pass
    else:
        raise SystemExit(f"unknown mode: {mode}")
    ncpus = int(case.get("ncpus", 1))
    if ncpus > 1:
        argv.append(f"ncpus={ncpus}")
    return argv


def run_once(case: dict, defaults: dict, base: Path, log_path: Path, tmpdir: Path) -> dict:
    argv = build_argv(case, defaults, base)
    t0_pre = time.monotonic_ns()
    with log_path.open("w", encoding="utf-8", errors="replace") as log:
        log.write("ARGV " + " ".join(argv) + "\n")
        log.flush()
        env = os.environ.copy()
        # UML's os_early_checks() maps guest RAM files PROT_EXEC from TMPDIR.
        # /dev/shm is noexec in this sandbox, so the bench must point TMPDIR at
        # a normal (exec-capable) directory.
        env["TMPDIR"] = str(tmpdir)
        proc = subprocess.Popen(
            argv,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            cwd=str(base),
            env=env,
        )
        t0 = time.monotonic_ns()
        ready_evt = threading.Event()
        state = {"t1": None, "t2": None, "ready": None,
                 "info": None, "errors": [], "panic": False,
                 "seccomp_mode": None}

        def reader() -> None:
            assert proc.stdout is not None
            for line in proc.stdout:
                now = time.monotonic_ns()
                line = line.rstrip("\n")
                log.write(line + "\n")
                if T1_RE.match(line) and state["t1"] is None:
                    state["t1"] = float(T1_RE.match(line).group(1))
                m = T2_RE.match(line)
                if m and state["t2"] is None:
                    state["t2"] = float(m.group(1))
                m = READY_RE.match(line)
                if m and state["ready"] is None:
                    state["ready"] = (now, float(m.group(1)))
                    ready_evt.set()
                m = INFO_RE.match(line)
                if m:
                    state["info"] = {"kernel": m.group(1),
                                     "ncpus": int(m.group(2))}
                if ERR_RE.match(line):
                    state["errors"].append(line)
                if PANIC_RE.search(line):
                    state["panic"] = True
                m = MODE_RE.search(line)
                if m:
                    state["seccomp_mode"] = m.group(1)
            log.flush()

        thread = threading.Thread(target=reader, daemon=True)
        thread.start()
        timeout = float(case.get("timeout_s", defaults.get("timeout_s", 30)))
        timed_out = False
        # The guest keeps running after U1_READY (um.hold_ms).  T3 is already
        # recorded, so stop the host process with SIGTERM instead of waiting
        # for the guest poweroff path.  On a 5.4 host the guest's reboot(2)
        # path crashes in os_dump_core() (SIGABRT, exit 134); SIGTERM is a
        # clean stop and is what research/uml-runner also uses.
        if not ready_evt.wait(timeout=timeout):
            timed_out = True
        if proc.poll() is None:
            time.sleep(0.05)
            proc.terminate()
        try:
            code = proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            code = proc.wait(timeout=5)
        thread.join(timeout=2)

    ready_host, ready_guest = state["ready"] if state["ready"] else (None, None)
    ok = (
        ready_host is not None
        and not state["errors"]
        and not state["panic"]
        and not timed_out
    )
    return {
        "ok": ok,
        "exit_code": code,
        "timed_out": timed_out,
        "t0_to_t3_ms": None if ready_host is None else (ready_host - t0) / 1e6,
        "t0_pre_to_t3_ms": None if ready_host is None else (ready_host - t0_pre) / 1e6,
        "guest_t3_ms": None if ready_guest is None else ready_guest * 1000.0,
        "guest_t1_ms": None if state["t1"] is None else state["t1"] * 1000.0,
        "guest_t2_ms": None if state["t2"] is None else state["t2"] * 1000.0,
        "info": state["info"],
        "seccomp_mode": state["seccomp_mode"],
        "errors": state["errors"],
        "panic": state["panic"],
        "log": str(log_path.relative_to(base)) if str(log_path).startswith(str(base)) else str(log_path),
    }


def stats(values: list[float]) -> dict | None:
    vals = [v for v in values if v is not None]
    if not vals:
        return None
    return {
        "n": len(vals),
        "median": statistics.median(vals),
        "min": min(vals),
        "max": max(vals),
        "values": vals,
    }


def fmt(v: float | None) -> str:
    return "—" if v is None else f"{v:.1f}"


def markdown(result: dict) -> str:
    lines = [
        f"# U1 fast-boot bench — {result['fixture']}",
        "",
        f"- host: {result['host']['uname']} / {result['host']['machine']} / "
        f"page {result['host']['pagesize']} / load1 {result['host']['load1']}",
        f"- kernel pin: {result['kernel_pin']}",
        f"- rounds: {result['n']} (interleaved across cases)",
        "",
        "| case | mode | ncpus | seccomp | n/ok | exit | T0→T3 ms median [min–max] | guest T3 ms median [min–max] | guest T1 ms | guest T2 ms |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for c in result["cases"]:
        t3 = c["t0_to_t3_ms"]
        g3 = c["guest_t3_ms"]
        g1 = c["guest_t1_ms"]
        g2 = c["guest_t2_ms"]
        rng = lambda s: "—" if not s else f"{s['median']:.1f} [{s['min']:.1f}–{s['max']:.1f}]"
        modes = sorted({r.get("seccomp_mode") for r in c["records"] if r.get("seccomp_mode")})
        exits = sorted({r["exit_code"] for r in c["records"]})
        actual_cpus = sorted({r["info"]["ncpus"] for r in c["records"]
                              if r.get("info") and "ncpus" in r["info"]})
        cpus = "/".join(map(str, actual_cpus)) if actual_cpus else "—"
        if actual_cpus and int(c.get("ncpus", 1)) not in actual_cpus:
            cpus += f" (req {c.get('ncpus', 1)})"
        lines.append(
            f"| {c['label']} | {c['mode']} | {cpus} | "
            f"{'/'.join(modes) if modes else '—'} | "
            f"{c['ok_runs']}/{c['runs']} | {'/'.join(map(str, exits))} | "
            f"{rng(t3)} | {rng(g3)} | "
            f"{fmt(g1['median'] if g1 else None)} | {fmt(g2['median'] if g2 else None)} |"
        )
    lines += ["", "## Per-run failures", ""]
    any_fail = False
    for c in result["cases"]:
        for i, r in enumerate(c["records"]):
            if not r["ok"]:
                any_fail = True
                lines.append(
                    f"- {c['label']} round {i}: exit={r['exit_code']} "
                    f"timeout={r['timed_out']} panic={r['panic']} "
                    f"errors={r['errors']} log={r['log']}"
                )
    if not any_fail:
        lines.append("- none")
    lines += [
        "",
        "T0→T3 is host `CLOCK_MONOTONIC` from `Popen()` return to the first "
        "`U1_READY` line; guest T1/T2/T3 are guest `/proc/uptime` / printk "
        "timestamps and are reported separately. Do not divide these columns.",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("matrix", help="matrix JSON path")
    ap.add_argument("--out", help="result JSON path (default: <matrix>.result.json)")
    ap.add_argument("--markdown", help="result Markdown path (default: <matrix>.result.md)")
    ap.add_argument("--log-dir", help="console log directory (default: <matrix-dir>/logs)")
    ap.add_argument("--tmpdir", help="UML TMPDIR (default: <matrix-dir>/tmp)")
    ap.add_argument("--fixture", help="override fixture id")
    ap.add_argument("--kernel-pin", default="169b17ff")
    args = ap.parse_args()

    matrix_path = Path(args.matrix).resolve()
    base = matrix_path.parent
    spec = json.loads(matrix_path.read_text())
    n = int(spec.get("n", 5))
    cases = spec["cases"]
    log_dir = Path(args.log_dir) if args.log_dir else base / "out" / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    tmpdir = Path(args.tmpdir) if args.tmpdir else base / "tmp"
    tmpdir.mkdir(parents=True, exist_ok=True)
    os.chmod(tmpdir, 0o1777)

    host = {
        "uname": os.uname().release,
        "machine": os.uname().machine,
        "pagesize": os.sysconf("SC_PAGE_SIZE"),
        "load1": os.getloadavg()[0],
    }
    result = {
        "fixture": args.fixture or spec.get("fixture", matrix_path.stem),
        "host": host,
        "kernel_pin": args.kernel_pin,
        "n": n,
        "cases": [],
    }

    records: dict[str, list[dict]] = {c["label"]: [] for c in cases}
    for r in range(n):
        for case in cases:
            label = case["label"]
            log_path = log_dir / f"{label}-r{r}.log"
            rec = run_once(case, spec, base, log_path, tmpdir)
            records[label].append(rec)
            status = "ok" if rec["ok"] else "FAIL"
            print(f"[{r + 1}/{n}] {label}: {status} "
                  f"T0->T3={fmt(rec['t0_to_t3_ms'])} ms "
                  f"guestT3={fmt(rec['guest_t3_ms'])} ms",
                  file=sys.stderr, flush=True)

    for case in cases:
        label = case["label"]
        recs = records[label]
        result["cases"].append({
            "label": label,
            "mode": case["mode"],
            "ncpus": int(case.get("ncpus", 1)),
            "kernel": case["kernel"],
            "root": case.get("root"),
            "runs": len(recs),
            "ok_runs": sum(1 for x in recs if x["ok"]),
            "t0_to_t3_ms": stats([x["t0_to_t3_ms"] for x in recs if x["ok"]]),
            "t0_pre_to_t3_ms": stats([x["t0_pre_to_t3_ms"] for x in recs if x["ok"]]),
            "guest_t3_ms": stats([x["guest_t3_ms"] for x in recs if x["ok"]]),
            "guest_t1_ms": stats([x["guest_t1_ms"] for x in recs if x["ok"]]),
            "guest_t2_ms": stats([x["guest_t2_ms"] for x in recs if x["ok"]]),
            "records": recs,
        })

    out_json = Path(args.out) if args.out else base / "out" / (matrix_path.stem + ".result.json")
    out_md = Path(args.markdown) if args.markdown else base / "out" / (matrix_path.stem + ".result.md")
    out_json.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    out_md.write_text(markdown(result).rstrip() + "\n")
    print(f"wrote {out_json} and {out_md}", file=sys.stderr)
    return 0 if all(c["ok_runs"] == c["runs"] for c in result["cases"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
