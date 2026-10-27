#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Native-lane measurement kit: four columns, two job levels, one fixture.

What it measures
----------------
The same preprocessed translation unit (``fixtures/fixture.i``) compiled on the
device by four different "lanes":

======================  ============================================================
column                  how it runs
======================  ============================================================
``qemu``                reused from ``dv_pvm_crossfire.py`` (``local_compile_ms``).
``pvm``                 reused from the same run (payload compile wall time).
``proot``               ``proot-static -r <rootfs> -b /dev -b /proc ... clang -c``
``bare``                no proot: ``ld-musl-aarch64.so.1 --library-path ... clang``
                        with ``-fintegrated-cc1`` so the driver does not re-exec
                        itself through a musl PT_INTERP the device cannot resolve.
======================  ============================================================

The ``bare`` column exists to separate two costs that are otherwise lumped
together: what ptrace-based path interception costs (proot minus bare) and what
CPU emulation costs (qemu/pvm minus bare). Without it there is no way to tell
whether a slow number is proot's fault or the emulator's.

Concurrency
-----------
``-j`` is *not* a flag passed to one compile. Each job level copies the fixture
N times and runs the compiles concurrently through ``xargs -P``; the number
reported is the batch wall clock and the derived throughput (files/min). A single
file pretending to be parallel would measure nothing.

Provenance
----------
Every run records nproc, cpuinfo_max_freq, scaling_governor, thermal zones before
and after, battery temperature and screen state. A run with any of those missing
does not get to produce a table row: the row is written as ``n/a`` and the process
exits 3 unless ``--allow-incomplete-provenance`` is passed.

Dry run
-------
``--dry-run`` swaps adb for ``fake_adb.py`` and walks the *same* code path. It
proves command assembly, parsing and rendering, and nothing about performance:
the numbers it prints are synthetic and the report says so in the JSON
(``"synthetic": true``) and in the markdown banner.

No numbers here are a performance conclusion. The device is not connected in this
round; see ``tools/native-lane/README.md``.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shlex
import statistics
import subprocess
import sys
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path

SCHEMA_VERSION = 1
HERE = Path(__file__).resolve().parent
DEFAULT_FIXTURE = HERE / "fixtures" / "fixture.i"
DEFAULT_LOCK = HERE / "tools.lock.json"
DEFAULT_RESULTS = HERE / "results"
FAKE_ADB = HERE / "fake_adb.py"

DEVICE_ROOT = "/data/local/tmp/native-lane"
DEVICE_LOADER = "lib/ld-musl-aarch64.so.1"
DEFAULT_CLANG = "/usr/bin/clang-22"

MEASURED_COLUMNS = ("proot", "bare")
REUSED_COLUMNS = ("qemu", "pvm")

MARKER_T0 = "NL_T0="
MARKER_PREFIX = "NL_"
CLOCK_NAME = "proc_uptime_cs"

# A batch that takes longer than this is treated as hung, not as a data point.
BATCH_TIMEOUT_S = 1800
PROBE_TIMEOUT_S = 120


class KitError(RuntimeError):
    """A failure the operator can act on; carries a stable code."""

    def __init__(self, code: str, detail: str):
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


# --------------------------------------------------------------------- adb I/O


class Adb:
    """Thin adb wrapper. Serials are used but never printed or persisted."""

    def __init__(self, base_argv: list[str], serial: str | None = None):
        self.base_argv = base_argv
        self.serial = serial
        self.commands: list[str] = []

    def _argv(self, args: list[str]) -> list[str]:
        argv = list(self.base_argv)
        if self.serial:
            argv += ["-s", self.serial]
        return argv + args

    def capture(self, args: list[str], timeout: int = PROBE_TIMEOUT_S) -> tuple[int, str, str]:
        """Run and return (rc, stdout, stderr).

        stderr matters: a dead lane and a slow lane look identical if only stdout
        is kept, which is how "0 objects" turns into an unexplainable number.
        """
        argv = self._argv(args)
        self.commands.append(" ".join(shlex.quote(part) for part in argv[1:]))
        completed = subprocess.run(argv, capture_output=True, text=True,
                                   encoding="utf-8", errors="replace", timeout=timeout)
        return completed.returncode, completed.stdout, completed.stderr

    def run(self, args: list[str], timeout: int = PROBE_TIMEOUT_S,
            check: bool = True) -> str:
        rc, out, err = self.capture(args, timeout)
        if check and rc != 0:
            raise KitError("ADB_FAILED",
                           f"{' '.join(args[:2])} rc={rc}: {(err or out).strip()[:400]}")
        return out

    def shell(self, script: str, timeout: int = PROBE_TIMEOUT_S,
              check: bool = True) -> str:
        return self.run(["shell", script], timeout=timeout, check=check)

    def shell_capture(self, script: str, timeout: int = PROBE_TIMEOUT_S) -> tuple[int, str, str]:
        return self.capture(["shell", script], timeout=timeout)

    def pull(self, remote: str, local: Path) -> None:
        self.run(["pull", remote, str(local)], timeout=300)


def redact(text: str) -> str:
    """Drop anything that looks like a device serial from operator-facing text."""
    return re.sub(r"\b([0-9A-Z]{4,}[\s:]+){2,}[0-9A-Z]{4,}\b", "<device>", text)


# ------------------------------------------------------------------- preflight


def _first_int(text: str) -> int | None:
    for token in re.findall(r"-?\d+", text):
        return int(token)
    return None


def probe_environment(adb: Adb) -> tuple[dict, list[str]]:
    """Collect the provenance a sample needs before it may appear in a table."""
    missing: list[str] = []
    env: dict = {}

    def probe(name: str, script: str, parser=_first_int):
        try:
            raw = adb.shell(script).strip()
        except (KitError, subprocess.TimeoutExpired):
            missing.append(name)
            env[name] = None
            return None
        value = parser(raw) if raw else None
        if value is None:
            missing.append(name)
        env[name] = value
        return value

    probe("nproc", "nproc", _first_int)
    probe("cpuinfo_max_freq_khz",
          'cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq 2>/dev/null '
          '| sort -n | tail -1', _first_int)
    probe("scaling_governor",
          'cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor 2>/dev/null '
          '| sort -u | tr "\\n" " "',
          lambda text: " ".join(text.split()) or None)
    env["thermal_start"] = probe_thermal(adb, missing, "thermal_start")
    env["battery_temp_dc"] = probe_battery(adb, missing)
    env["screen"] = probe_screen(adb, missing)
    return env, missing


def probe_thermal(adb: Adb, missing: list[str], field: str) -> dict | None:
    script = ('for z in /sys/class/thermal/thermal_zone*; do '
              'printf "%s %s %s\\n" "${z##*/}" "$(cat $z/type 2>/dev/null)" '
              '"$(cat $z/temp 2>/dev/null)"; done')
    try:
        raw = adb.shell(script)
    except (KitError, subprocess.TimeoutExpired):
        missing.append(field)
        return None
    zones: dict[str, int] = {}
    for line in raw.splitlines():
        parts = line.split()
        if len(parts) != 3:
            continue
        zone, kind, value = parts
        try:
            zones[f"{zone}:{kind}"] = int(value)
        except ValueError:
            continue
    if not zones:
        missing.append(field)
        return None
    return dict(sorted(zones.items()))


def probe_battery(adb: Adb, missing: list[str]) -> int | None:
    try:
        raw = adb.shell("dumpsys battery")
    except (KitError, subprocess.TimeoutExpired):
        missing.append("battery_temp_dc")
        return None
    match = re.search(r"temperature:\s*(-?\d+)", raw)
    if not match:
        missing.append("battery_temp_dc")
        return None
    return int(match.group(1))


def probe_screen(adb: Adb, missing: list[str]) -> str | None:
    try:
        raw = adb.shell("dumpsys power")
    except (KitError, subprocess.TimeoutExpired):
        missing.append("screen")
        return None
    match = re.search(r"mWakefulness=(\w+)", raw)
    if not match:
        missing.append("screen")
        return None
    return match.group(1)


def probe_rootfs(adb: Adb, rootfs: str, clang: str) -> dict:
    """Confirm the rootfs was unpacked and report the clang it actually carries."""
    facts: dict = {"rootfs": rootfs, "marker": None, "clang": None, "loader": None}
    try:
        facts["marker"] = adb.shell(
            f"ls {rootfs}/.native-lane-rootfs.json 2>/dev/null").strip() or None
    except (KitError, subprocess.TimeoutExpired):
        facts["marker"] = None
    if facts["marker"] is None:
        raise KitError(
            "ROOTFS_NOT_PREPARED",
            f"{rootfs}/.native-lane-rootfs.json is missing. Run "
            f"'bash tools/native-lane/prepare_rootfs.sh plan' and execute the device "
            f"sequence, or 'bash tools/native-lane/prepare_rootfs.sh all --where device "
            f"--adb <path>'.")
    try:
        facts["loader"] = adb.shell(
            f"ls {rootfs}/{DEVICE_LOADER} 2>/dev/null").strip() or None
    except (KitError, subprocess.TimeoutExpired):
        facts["loader"] = None
    # The bare column runs clang as a plain program under the device root, so it
    # addresses the interpreter explicitly; a missing loader there is fatal, not
    # a warning, because the failure mode is otherwise a silently empty column.
    if facts["loader"] is None:
        raise KitError("LOADER_MISSING",
                       f"{rootfs}/{DEVICE_LOADER} not found; the bare column cannot start")
    # A path that merely exists is not the fact the bare column depends on: the
    # program is resolved against the device root and started by the musl
    # interpreter, so the libLLVM/libc++ it links against must resolve too.
    # Ask it for its version through exactly the interpreter and library path the
    # bare column will use -- that also reports the clang the rootfs carries
    # instead of the one the profile assumes.
    libpath = f"{rootfs}/usr/lib/llvm22/lib:{rootfs}/usr/lib:{rootfs}/lib"
    program = rootfs.rstrip("/") + "/" + clang.lstrip("/")
    bare_gate = (f"{_quote(rootfs.rstrip('/') + '/' + DEVICE_LOADER)} "
                 f"--library-path {_quote(libpath)} {_quote(program)} --version")
    rc, out, err = adb.shell_capture(bare_gate)
    version_line = (out.strip().splitlines() or [""])[0].strip()
    if rc != 0 or not version_line:
        raise KitError(
            "CLANG_MISSING",
            f"{program} does not start under {rootfs}/{DEVICE_LOADER} (rc={rc}): "
            f"{(err or out).strip()[:200] or 'no output'}. Run the populate step of "
            f"prepare_rootfs.sh (profile pins the package name).")
    facts["clang"] = clang
    return facts


def probe_clang_version(adb: Adb, proot: str, rootfs: str, clang: str) -> str | None:
    script = f"{proot} -r {rootfs} -b /dev -b /proc -w /root {clang} --version 2>&1 | head -1"
    try:
        text = adb.shell(script).strip()
    except (KitError, subprocess.TimeoutExpired):
        return None
    return text or None


# --------------------------------------------------------------- batch running


def _quote(path: str) -> str:
    return shlex.quote(path)


def build_compile_entry(column: str, clang: str, rootfs: str, proot: str,
                        workdir: str, cflags: list[str] | None = None) -> str:
    """One compile command; ``$1`` is the input file, supplied by xargs.

    The work directory is bind-mounted at ``/work`` for the proot column, so a
    relative ``$1`` resolves inside the guest and the object lands back in the
    device directory -- no host path may ever appear in a device command.

    The two columns address clang in different path spaces, which is the single
    easiest thing to get wrong here:
    * proot  -> ``clang`` is a *guest* path, resolved inside ``-r <rootfs>``.
    * bare   -> there is no ``-r``; the loader and the program are both resolved
      against the *device* root, so the program must carry the rootfs prefix.
    """
    flags = " ".join(cflags or [])
    tail = f"{flags} -c \"$1\" -o \"${{1%.i}}.o\"".strip()
    if column == "proot":
        return (f"{_quote(proot)} -r {_quote(rootfs)} -b /dev -b /proc -b /sys "
                f"-b {_quote(workdir)}:/work -w /work {_quote(clang)} {tail}")
    if column == "bare":
        libpath = f"{rootfs}/usr/lib/llvm22/lib:{rootfs}/usr/lib:{rootfs}/lib"
        program = rootfs.rstrip("/") + "/" + clang.lstrip("/")
        return (f"{_quote(rootfs + '/' + DEVICE_LOADER)} --library-path {_quote(libpath)} "
                f"{_quote(program)} -fintegrated-cc1 {tail}")
    raise KitError("UNKNOWN_COLUMN", column)


def build_batch_script(column: str, clang: str, rootfs: str, proot: str,
                       workdir: str, device_dot_i: str, jobs: int, files: int,
                       cflags: list[str] | None = None) -> str:
    """The whole measured region, timed with /proc/uptime on the device.

    /proc/uptime gives centiseconds without needing a clock tool or an extra
    process; the alternative (``date +%s%N``) is not portable to toybox.

    The object count avoids ``find -size``: toybox's ``find`` support for
    ``-size`` is not guaranteed, and a silent 0 there would report a perfectly
    good batch as MISSING_OBJECTS.
    """
    entry = build_compile_entry(column, clang, rootfs, proot, workdir, cflags)
    tmpdir = (f"mkdir -p {_quote(workdir)}/tmp; export TMPDIR={_quote(workdir)}/tmp; "
              if column == "bare" else "")
    parts = [
        f"rm -rf {_quote(workdir)}; mkdir -p {_quote(workdir)}; ",
        f"i=0; while [ \"$i\" -lt {files} ]; do "
        f"cp {_quote(device_dot_i)} {_quote(workdir)}/f$i.i || exit 91; i=$((i+1)); done; ",
        f"cd {_quote(workdir)} || exit 92; {tmpdir}",
        "T0=$(cut -d' ' -f1 /proc/uptime); ",
        f"ls f*.i | xargs -P {jobs} -n 1 sh -c '{entry}' _; RC=$?; ",
        "T1=$(cut -d' ' -f1 /proc/uptime); ",
        "OBJS=0; for f in *.o; do [ -s \"$f\" ] && OBJS=$((OBJS+1)); done; ",
        "printf 'NL_T0=%s\\nNL_T1=%s\\nNL_RC=%s\\nNL_OBJS=%s\\n' \"$T0\" \"$T1\" \"$RC\" \"$OBJS\"",
    ]
    return "".join(parts)


def parse_markers(text: str) -> dict[str, str]:
    markers: dict[str, str] = {}
    for line in text.splitlines():
        if MARKER_PREFIX not in line:
            continue
        for chunk in line.split():
            if chunk.startswith(MARKER_PREFIX) and "=" in chunk:
                key, _, value = chunk.partition("=")
                markers[key] = value
    return markers


def run_batch(adb: Adb, column: str, jobs: int, files: int, clang: str,
              rootfs: str, proot: str, workdir: str, device_dot_i: str,
              cflags: list[str] | None = None) -> dict:
    script = build_batch_script(column, clang, rootfs, proot, workdir,
                                device_dot_i, jobs, files, cflags)
    row: dict = {"column": column, "jobs": jobs, "files": files, "command": script}
    try:
        adb_rc, raw, err = adb.shell_capture(script, timeout=BATCH_TIMEOUT_S)
    except subprocess.TimeoutExpired:
        row.update(status="TIMEOUT", wall_ms=None, files_per_min=None,
                   objects_ok=None, markers={})
        return row
    markers = parse_markers(raw)
    row["markers"] = markers
    if err.strip():
        row["stderr_tail"] = redact(err.strip()[-400:])
    try:
        t0 = Decimal(markers["NL_T0"])
        t1 = Decimal(markers["NL_T1"])
    except (KeyError, InvalidOperation):
        row.update(status="NO_TIMING", wall_ms=None, files_per_min=None,
                   objects_ok=None)
        if "stderr_tail" not in row:
            row["stderr_tail"] = redact((err or raw).strip()[-400:])
        return row

    wall_ms = int((t1 - t0) * 1000)
    objects_ok = int(markers.get("NL_OBJS", "0") or 0)
    rc = int(markers.get("NL_RC", "1") or 1)
    row.update(wall_ms=wall_ms, objects_ok=objects_ok, rc=rc)
    if objects_ok < files:
        # No objects means no work happened; a throughput figure here would be
        # a fabricated number.
        row["status"] = "MISSING_OBJECTS"
        row["files_per_min"] = None
    elif rc != 0 or adb_rc != 0:
        # Every object exists, but at least one invocation returned non-zero.
        # Record the timing as raw data and withhold the throughput.
        row["status"] = "PARTIAL_FAILURE"
        row["files_per_min"] = None
    else:
        row["status"] = "OK"
        row["files_per_min"] = round(files * 60000 / wall_ms, 2) if wall_ms else None
    return row


# ------------------------------------------------------------ crossfire import


def import_crossfire(path: Path) -> dict:
    """Reuse one crossfire summary instead of re-running the QEMU/pVM lanes."""
    data = json.loads(path.read_text(encoding="utf-8"))
    rounds = data.get("rounds") or []
    local = [r["local_compile_ms"] for r in rounds
             if isinstance(r.get("local_compile_ms"), int)]
    payload = [r["distcc_ms"] for r in rounds if isinstance(r.get("distcc_ms"), int)]
    result = {
        "source": path.name,
        "source_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "fixture_bytes": data.get("fixture_bytes"),
        "fixture_functions": data.get("fixture_functions"),
        "rounds": len(rounds),
        "qemu": None,
        "pvm": None,
        "warnings": [],
    }
    if local:
        result["qemu"] = {"status": "REUSED", "local_compile_ms_p50": statistics.median(local),
                          "samples_ms": local}
    if payload:
        result["pvm"] = {"status": "REUSED", "payload_compile_ms_p50": statistics.median(payload),
                         "samples_ms": payload}
    if not local:
        result["warnings"].append("crossfire summary has no local_compile_ms rounds")
    return result


def reused_rows(imported: dict | None, qemu_ms: int | None, pvm_ms: int | None) -> dict:
    """The two columns this round never re-measures; absence is stated, not faked."""
    rows: dict = {}
    for column in REUSED_COLUMNS:
        entry = {"column": column, "status": "NOT_MEASURED", "value_ms": None, "note": None}
        if column == "qemu":
            if qemu_ms is not None:
                entry.update(value_ms=qemu_ms, note="passed on the command line")
            elif imported and imported.get("qemu"):
                entry.update(value_ms=int(imported["qemu"]["local_compile_ms_p50"]),
                             note=f"reused from {imported['source']}")
            else:
                entry["note"] = "no crossfire summary imported and no --qemu-ms given"
        else:
            if pvm_ms is not None:
                entry.update(value_ms=pvm_ms, note="passed on the command line")
            elif imported and imported.get("pvm"):
                entry.update(value_ms=int(imported["pvm"]["payload_compile_ms_p50"]),
                             note=f"reused from {imported['source']}")
            else:
                entry["note"] = "no crossfire summary imported and no --pvm-ms given"
        if entry["value_ms"] is not None:
            entry["status"] = "REUSED"
        rows[column] = entry
    return rows


# ------------------------------------------------------------------- rendering


def render_markdown(report: dict) -> str:
    run = report["run"]
    env = report["env"]
    fixture = run["fixture"]
    lines: list[str] = []
    if report.get("synthetic"):
        lines += [
            "> **SYNTHETIC RUN — NOT A MEASUREMENT.** The numbers below come from",
            "> `fake_adb.py` and exist only to prove the pipeline works. Do not quote them.",
            "",
        ]
    lines.append("# Native-lane measurement")
    lines.append("")
    lines.append(f"- run: `{run['run_id']}` ({run['mode']})")
    lines.append(f"- fixture: `{fixture['name']}` {fixture['bytes']} B "
                 f"sha256 `{fixture['sha256']}`")
    lines.append(f"- jobs: {', '.join(str(j) for j in run['parameters']['jobs'])}; "
                 f"files per batch: {run['parameters']['files_per_batch']}")
    lines.append(f"- clock: `{run['parameters']['clock']}`")
    lines.append("")
    lines.append("| column | -j1 wall ms | -j1 files/min | -j7 wall ms | -j7 files/min | status |")
    lines.append("|---|---|---|---|---|---|")

    by_key = {(row["column"], row["jobs"]): row for row in report["rows"]}
    for column in ("qemu", "pvm", "proot", "bare"):
        if column in report["reused"]:
            reused = report["reused"][column]
            value = reused["value_ms"]
            value_cell = "—" if value is None else str(value)
            status = reused["status"]
            note = reused["note"] or ""
            lines.append(f"| `{column}` | {value_cell} |  |  |  | {status} — {note} |")
            continue
        cells = []
        statuses = []
        for jobs in (1, 7):
            row = by_key.get((column, jobs))
            if row is None:
                cells += ["—", "—"]
                statuses.append(f"j{jobs}: absent")
                continue
            if row.get("wall_ms") is None:
                cells += ["—", "—"]
            else:
                cells += [str(row["wall_ms"]),
                          "—" if row.get("files_per_min") is None else f"{row['files_per_min']:.2f}"]
            statuses.append(f"j{jobs}: {row['status']}")
        lines.append(f"| `{column}` | {cells[0]} | {cells[1]} | {cells[2]} | {cells[3]} | "
                     f"{'; '.join(statuses)} |")

    lines.append("")
    lines.append("## Provenance")
    lines.append("")
    lines.append("| field | value |")
    lines.append("|---|---|")
    for key in ("nproc", "cpuinfo_max_freq_khz", "scaling_governor",
                "battery_temp_dc", "screen"):
        lines.append(f"| {key} | {env.get(key)} |")
    for key in ("thermal_start", "thermal_end"):
        zones = env.get(key) or {}
        summary = ", ".join(f"{name}={value}" for name, value in list(zones.items())[:4])
        lines.append(f"| {key} | {summary or 'n/a'} |")
    if env.get("incomplete"):
        lines.append("")
        lines.append(f"**Incomplete provenance: {', '.join(env['incomplete'])}.** "
                     "Rows above are `n/a` for that reason; they must not be quoted.")
    if report.get("warnings"):
        lines.append("")
        lines.append("## Warnings")
        lines.append("")
        for warning in report["warnings"]:
            lines.append(f"- {warning}")
    lines.append("")
    return "\n".join(lines)


# ------------------------------------------------------------------------ main


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--device", action="store_true",
                        help="measure a connected device (unused with --dry-run)")
    parser.add_argument("--dry-run", action="store_true",
                        help="walk the full pipeline against fake_adb.py; no device, "
                             "no real numbers")
    parser.add_argument("--adb", default="adb", help="adb executable")
    parser.add_argument("--serial", default=None,
                        help="device serial; never written to results")
    parser.add_argument("--columns", default=",".join(MEASURED_COLUMNS),
                        help="comma-separated measured columns (proot,bare)")
    parser.add_argument("--jobs", default="1,7", help="job levels, e.g. 1,7")
    parser.add_argument("--files", type=int, default=7,
                        help="fixture copies per job level (>= max(jobs))")
    parser.add_argument("--dot-i", default=str(DEFAULT_FIXTURE),
                        help="preprocessed fixture to compile")
    parser.add_argument("--regen-dot-i", action="store_true",
                        help="re-preprocess on the device with the device clang and "
                             "use that translation unit for every column")
    parser.add_argument("--rootfs", default=f"{DEVICE_ROOT}/rootfs")
    parser.add_argument("--work", default=f"{DEVICE_ROOT}/work")
    parser.add_argument("--proot", default=f"{DEVICE_ROOT}/bin/proot-static")
    parser.add_argument("--clang", default=DEFAULT_CLANG)
    parser.add_argument("--import-crossfire", default=None,
                        help="summary JSON from dv_pvm_crossfire.py to reuse qemu/pvm")
    parser.add_argument("--qemu-ms", type=int, default=None)
    parser.add_argument("--pvm-ms", type=int, default=None)
    parser.add_argument("--allow-incomplete-provenance", action="store_true",
                        help="write the report even when a provenance field is missing")
    parser.add_argument("--out", default=str(DEFAULT_RESULTS))
    parser.add_argument("--preflight-only", action="store_true",
                        help="probe the device and stop (first command after the "
                             "device comes back)")
    return parser.parse_args(argv)


def resolve_adb(args: argparse.Namespace, out_dir: Path) -> Adb:
    if args.dry_run:
        if not FAKE_ADB.is_file():
            raise KitError("FAKE_ADB_MISSING", str(FAKE_ADB))
        # The state file keeps the fake's call counter inside this run's directory,
        # so repeated dry runs produce identical bytes.
        return Adb([sys.executable, str(FAKE_ADB), str(out_dir / "fake-adb.state")], None)
    if not args.device:
        raise KitError("NO_DEVICE_FLAG",
                       "pass --device to measure hardware, or --dry-run to test the pipeline")
    return Adb([args.adb], args.serial)


def fixture_facts(path: Path) -> dict:
    if not path.is_file():
        raise KitError("FIXTURE_MISSING", f"{path}; run gen_fixture.py")
    data = path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    manifest_path = path.parent / "MANIFEST.json"
    recorded = None
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        recorded = (manifest.get("files", {}).get(path.name) or {}).get("sha256")
    warnings = []
    if recorded and recorded != digest:
        warnings.append(f"fixture sha256 differs from MANIFEST.json ({recorded} != {digest}); "
                        "the four columns still share one file, but it is not the reference")
    return {"name": path.name, "bytes": len(data), "sha256": digest,
            "manifest_sha256": recorded, "warnings": warnings}


def regenerate_dot_i(adb: Adb, args: argparse.Namespace, out_dir: Path,
                     fixture: dict) -> dict:
    """Preprocess on the device with the clang the columns will actually use.

    This is also the first real test of the proot executable gate: if proot
    cannot exec on this ROM, it fails here with PROOT_EXEC_FAILED before any
    timing loop starts.
    """
    device_src = f"{args.work}/fixture.c"
    device_dot_i = f"{args.work}/fixture.device.i"
    source = (Path(args.dot_i).parent / "fixture.c")
    if not source.is_file():
        raise KitError("FIXTURE_SOURCE_MISSING", str(source))
    adb.run(["push", str(source), f"{args.work}/fixture.c"], timeout=300)
    log_path = f"{args.work}/regen.log"
    script = (f"{args.proot} -r {args.rootfs} -b /dev -b /proc "
              f"-b {args.work}:/work -w /work {args.clang} -E -x c /work/fixture.c "
              f"-o /work/fixture.device.i >{log_path} 2>&1; "
              f"RC=$?; tail -3 {log_path}; echo NL_REGEN_RC=$RC")
    raw = adb.shell(script, timeout=600, check=False)
    markers = parse_markers(raw)
    if markers.get("NL_REGEN_RC", "1") != "0":
        raise KitError("PROOT_EXEC_FAILED",
                       "device-side preprocessing failed; the proot executable gate is the "
                       f"first thing to check. Output: {redact(raw.strip()[:300])}")
    local = out_dir / "fixture.device.i"
    local.parent.mkdir(parents=True, exist_ok=True)
    adb.pull(f"{args.work}/fixture.device.i", local)
    data = local.read_bytes()
    return {"name": local.name, "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            "path": str(local), "regenerated_on_device": True,
            "reference_sha256": fixture["sha256"]}


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    jobs = [int(part) for part in args.jobs.split(",") if part.strip()]
    columns = [part.strip() for part in args.columns.split(",") if part.strip()]
    for column in columns:
        if column not in MEASURED_COLUMNS:
            print(f"unknown column: {column}", file=sys.stderr)
            return 2
    if args.files < max(jobs):
        print(f"--files must be >= max(--jobs) = {max(jobs)}", file=sys.stderr)
        return 2

    run_started = datetime.now(timezone.utc)
    run_id = run_started.strftime("%Y%m%dT%H%M%SZ")
    out_dir = Path(args.out) / run_id
    out_dir.mkdir(parents=True, exist_ok=True)
    warnings: list[str] = []

    try:
        adb = resolve_adb(args, out_dir)
        dot_i_path = Path(args.dot_i)
        fixture = fixture_facts(dot_i_path)
        warnings += fixture["warnings"]
    except KitError as error:
        print(str(error), file=sys.stderr)
        return 2

    try:
        env_start, missing = probe_environment(adb)
        rootfs_facts = probe_rootfs(adb, args.rootfs, args.clang)
        clang_version = probe_clang_version(adb, args.proot, args.rootfs, args.clang)
        if clang_version is None:
            missing.append("clang_version")
            warnings.append("proot could not run clang --version; the proot column may be dead "
                            "on this ROM (see README, 'proot exec gate')")
    except KitError as error:
        print(str(error), file=sys.stderr)
        return 2
    except subprocess.TimeoutExpired:
        print("PREFLIGHT_TIMEOUT: device did not answer within the probe budget",
              file=sys.stderr)
        return 2

    synthetic = bool(args.dry_run)
    if synthetic:
        env_start.pop("thermal_end", None)

    preflight = {
        "rootfs_marker": rootfs_facts.get("marker"),
        "loader": rootfs_facts.get("loader"),
        "clang_version": clang_version,
        "incomplete": missing,
    }
    if args.preflight_only:
        print(json.dumps(preflight, indent=2, ensure_ascii=False))
        return 0 if not missing else 3

    # The fixture lives on the device before any timing starts; copying it into
    # each batch directory is device-local, so no host path reaches a device
    # command and the copy cost stays outside the measured region.
    device_dot_i = f"{args.work}/fixture.i"
    if args.regen_dot_i:
        try:
            fixture = regenerate_dot_i(adb, args, out_dir, fixture)
            dot_i_path = Path(fixture["path"])
            device_dot_i = f"{args.work}/fixture.device.i"
        except KitError as error:
            print(str(error), file=sys.stderr)
            return 2
    else:
        try:
            adb.run(["push", str(dot_i_path.resolve()), device_dot_i], timeout=300)
        except KitError as error:
            print(str(error), file=sys.stderr)
            return 2

    rows = []
    for column in columns:
        for level in jobs:
            workdir = f"{args.work}/{column}-j{level}"
            row = run_batch(adb, column, level, args.files, args.clang, args.rootfs,
                            args.proot, workdir, device_dot_i)
            rows.append(row)

    env_end_thermal = probe_thermal(adb, [], "thermal_end")
    env_start["thermal_end"] = env_end_thermal

    imported = None
    if args.import_crossfire:
        try:
            imported = import_crossfire(Path(args.import_crossfire))
            warnings += imported["warnings"]
            if (imported.get("fixture_bytes") is not None
                    and imported["fixture_bytes"] != fixture["bytes"]):
                warnings.append(
                    f"reused crossfire summary compiled {imported['fixture_bytes']} B, "
                    f"this run uses {fixture['bytes']} B: not the same input")
        except (OSError, json.JSONDecodeError) as error:
            warnings.append(f"crossfire summary unreadable, qemu/pvm stay NOT_MEASURED: {error}")

    reused = reused_rows(imported, args.qemu_ms, args.pvm_ms)

    report = {
        "schema_version": SCHEMA_VERSION,
        "synthetic": synthetic,
        "run": {
            "run_id": run_id,
            "started_utc": run_started.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "mode": "dry_run" if synthetic else "device",
            "fixture": fixture,
            "device_fixture": device_dot_i,
            "parameters": {"jobs": jobs, "files_per_batch": args.files,
                           "columns": columns, "clock": CLOCK_NAME,
                           "regen_dot_i": bool(args.regen_dot_i)},
        },
        "env": {**env_start, "incomplete": missing},
        "preflight": preflight,
        "rows": rows,
        "reused": reused,
        "warnings": warnings,
    }

    (out_dir / "native-lane.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    markdown = render_markdown(report)
    (out_dir / "native-lane.md").write_text(markdown + "\n", encoding="utf-8")
    print(markdown)

    failed = [row["column"] + f"-j{row['jobs']}" for row in rows
              if row["status"] not in ("OK", "PARTIAL_FAILURE")]
    if missing and not args.allow_incomplete_provenance:
        print(f"PROVENANCE_INCOMPLETE {missing}: rows withheld; "
              "rerun with --allow-incomplete-provenance to keep them as n/a",
              file=sys.stderr)
        return 3
    if failed:
        print(f"COLUMNS_FAILED {failed}", file=sys.stderr)
        return 4
    print(f"results written to {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
