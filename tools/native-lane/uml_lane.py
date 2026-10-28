#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""UML research lane (session 20, D10): user-mode Linux as a research column.

This is NOT product code and NOT a measured result. The UML kernel has never
been booted for this round (no device attached); what lives here is the lane
plumbing so that D10 can produce a same-table column next to
``proot`` / ``pvm`` / ``qemu`` (with ``native`` as the baseline) the moment the
hardware is back.

Why an independent runner
-------------------------
``proot``/``bare`` are *host command prefix* lanes: ``measure.py`` wraps clang
in a single adb shell command. UML cannot be expressed that way -- the guest
kernel is a *persistent host process* and the workload is compiled by a shell
*inside* the guest. The host side therefore:

1. assembles the kernel launch argv (see ``build_kernel_argv``);
2. delivers the fixture and the batch script into the guest through the rootfs
   channel -- hostfs mode writes them straight into the hostfs directory (the
   directory *is* the guest ``/``); ubd0/ext4 mode would need the umnet/vec0
   network gate, which is not built in session 20 and fails loudly;
3. launches ``linux-bionic`` and reads the ``NL_*`` markers that flow back
   through ``con0=fd:0,fd:1``.

The row schema matches ``measure.run_batch`` rows (column/jobs/files/status/
wall_ms/files_per_min/objects_ok/command) so the four columns land in one
``native-lane.json`` table.

Methodology hard rules (aligned with the fork benchmark docs,
``evidence/uml-20261028/fork-docs-summary.md`` section F)
------------------------------------------------------------------------
1. Same-table conditions are interleaved (rotated per round, never run in a
   fixed order).
2. Seven rounds per condition; report the **median**, never the mean.
3. The compute checksum (``fixture_entry()`` sum) must match the expected
   value; deviation > 5% (or a missing checksum) discards the whole table
   (``DISCARD_TABLE``), not just the offending row.
4. Fault metrics are normalized by page bytes: native counts per-4K faults,
   the UML guest is built with 16K pages, so raw counts must never be
   compared -- ``faults * page_bytes`` (bytes faulted) is the comparable unit.
5. No cross-table horizontal comparison: UML numbers and proot/pVM/QEMU
   numbers from different tables must never be subtracted or ratioed.

Artifact paths
--------------
The release artifacts are an out-of-repo directory (仓外目录):
``<workspace>/evidence/uml-20261028/release-v0.3.0/``. The workspace root is
``ORCHESTELLA_WORKSPACE`` if set, else the parent of the repository root. No
absolute path is ever committed; everything is derived at run time.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shlex
import shutil
import statistics
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

LANE_ID = "uml"
SCHEMA_VERSION = 1
HERE = Path(__file__).resolve().parent
DEFAULT_DOT_I = HERE / "fixtures" / "fixture.i"
DEFAULT_RESULTS = HERE / "results"

# ------------------------------------------------------------- methodology
ROUNDS = 7                       # medians of 7 interleaved rounds, never means
COMPUTE_TOLERANCE = 0.05         # >5% deviation on the checksum -> discard table
JITTER_TOLERANCE = 0.08          # in-round jitter >8% -> discard table
BATCH_TIMEOUT_S = 1800           # a batch that hangs is not a data point

PAGE_BYTES_NATIVE = 4096         # host/native pages
PAGE_BYTES_UML_GUEST = 16384     # CONFIG_PAGE_SIZE_16KB=y in arm64_defconfig

# --------------------------------------------------- launch argv defaults
# Values follow the fork docs summary (boot.py / README lines quoted there).
DEFAULT_MEM = "512M"
DEFAULT_NCPUS = 1
DEFAULT_SECCOMP = "on"           # "on is roughly 5x faster per syscall"
DEFAULT_INIT = "/um-init"
DEFAULT_CON = "null"
DEFAULT_CON0 = "fd:0,fd:1"
DEFAULT_GUEST_CLANG = "clang"
SECCOMP_MODES = ("on", "auto", "off")

# ---------------------------------------------------------- artifact paths
WORKSPACE_ENV = "ORCHESTELLA_WORKSPACE"
RELEASE_SUBPATH = "evidence/uml-20261028/release-v0.3.0"
HOSTFS_SUBPATH = "evidence/uml-20261028/uml-hostfs"


def workspace_root() -> Path:
    """The host workspace root (parent of the repo). Overridable via env."""
    env = os.environ.get(WORKSPACE_ENV)
    if env:
        return Path(env)
    # HERE = <repo>/tools/native-lane -> parents[2] is the workspace root.
    return HERE.parents[2]


DEFAULT_RELEASE_DIR = workspace_root() / RELEASE_SUBPATH
DEFAULT_KERNEL = DEFAULT_RELEASE_DIR / "linux-bionic"
DEFAULT_STUB = DEFAULT_RELEASE_DIR / "stub_exe"
DEFAULT_HOSTFS_DIR = workspace_root() / HOSTFS_SUBPATH


class LaneError(RuntimeError):
    """A failure the operator can act on; carries a stable code."""

    def __init__(self, code: str, detail: str):
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


# ------------------------------------------------------------------ config


@dataclass(frozen=True)
class UmlConfig:
    """Everything the launch argv and the rootfs channel need."""

    kernel: Path = DEFAULT_KERNEL
    stub: Path = DEFAULT_STUB
    rootfs_mode: str = "hostfs"            # "hostfs" | "ubd0"
    hostfs_dir: Path | None = None         # hostfs: directory that becomes guest /
    ubd0_image: Path | None = None         # ubd0: ext4 image
    mem: str = DEFAULT_MEM
    ncpus: int = DEFAULT_NCPUS
    prefault: str | None = None            # e.g. "128K" / "512K"; omitted if None
    seccomp: str = DEFAULT_SECCOMP
    init: str = DEFAULT_INIT
    con: str = DEFAULT_CON
    con0: str = DEFAULT_CON0
    guest_clang: str = DEFAULT_GUEST_CLANG
    extra_args: tuple[str, ...] = ()


def _posix(path: Path | str) -> str:
    """Forward-slash form for kernel argv; UML runs on a POSIX host."""
    return str(path).replace(os.sep, "/")


def validate_config(cfg: UmlConfig) -> None:
    """Shape checks only -- artifact *existence* is a run-time concern."""
    if not str(cfg.stub).strip():
        raise LaneError(
            "STUB_REQUIRED",
            "Android refuses embedded memfd execution for the UML stub, so "
            "stub_exe= is mandatory (fork README:97); pass --uml-stub")
    if cfg.seccomp not in SECCOMP_MODES:
        raise LaneError("SECCOMP_INVALID",
                        f"{cfg.seccomp!r}; expected one of {SECCOMP_MODES}")
    if cfg.ncpus < 1:
        raise LaneError("NCPUS_INVALID", f"ncpus={cfg.ncpus}")
    if not re.match(r"^\d+[KkMmGg]?$", cfg.mem or ""):
        raise LaneError("MEM_INVALID", f"mem={cfg.mem!r}; expected e.g. 512M")
    if not cfg.con0:
        raise LaneError("CON0_REQUIRED",
                        "con0 carries the NL_ marker stream back to the host")
    if cfg.rootfs_mode == "hostfs":
        if cfg.hostfs_dir is None:
            raise LaneError("HOSTFS_DIR_REQUIRED", "hostfs mode needs a directory")
    elif cfg.rootfs_mode == "ubd0":
        if cfg.ubd0_image is None:
            raise LaneError("UBD0_IMAGE_REQUIRED", "ubd0 mode needs an ext4 image")
    else:
        raise LaneError("ROOTFS_MODE_INVALID",
                        f"{cfg.rootfs_mode!r}; expected 'hostfs' or 'ubd0'")


def build_kernel_argv(cfg: UmlConfig) -> list[str]:
    """Kernel command line, mirroring fork harness/boot.py:32-35 + README:146-153.

    Order follows boot.py: mem / rw / init / con / con0 / stub_exe / panic /
    seccomp / ncpus / prefault / extras, then the rootfs chunk:
    hostfs -> ``rootfstype=hostfs rootflags=<dir>`` (no mke2fs, preparable on
    any host); ubd0 -> ``ubd0=<img> root=/dev/ubda``.
    """
    validate_config(cfg)
    argv = [f"mem={cfg.mem}", "rw", f"init={cfg.init}"]
    if cfg.con:
        argv.append(f"con={cfg.con}")
    argv += [
        f"con0={cfg.con0}",
        f"stub_exe={_posix(cfg.stub)}",
        "panic=1",
        f"seccomp={cfg.seccomp}",
        f"ncpus={cfg.ncpus}",
    ]
    if cfg.prefault:
        argv.append(f"prefault={cfg.prefault}")
    argv += list(cfg.extra_args)
    if cfg.rootfs_mode == "hostfs":
        argv += ["rootfstype=hostfs", f"rootflags={_posix(cfg.hostfs_dir)}"]
    else:
        argv += [f"ubd0={_posix(cfg.ubd0_image)}", "root=/dev/ubda"]
    return argv


def build_launch_argv(cfg: UmlConfig) -> list[str]:
    """The full host-side launch command: kernel binary plus its argv."""
    return [str(cfg.kernel), *build_kernel_argv(cfg)]


def artifact_status(cfg: UmlConfig) -> dict:
    """Presence booleans for the out-of-repo artifacts; never raises."""
    rootfs_ready = False
    if cfg.rootfs_mode == "hostfs" and cfg.hostfs_dir is not None:
        # A bootable hostfs root needs a shell and the fork's um-init.
        rootfs_ready = ((cfg.hostfs_dir / "um-init").is_file()
                        and (cfg.hostfs_dir / "bin" / "sh").is_file())
    elif cfg.ubd0_image is not None:
        rootfs_ready = cfg.ubd0_image.is_file()
    return {
        "kernel_present": cfg.kernel.is_file(),
        "stub_present": cfg.stub.is_file(),
        "rootfs_ready": rootfs_ready,
    }


# ------------------------------------------------------------ guest script


def build_guest_script(jobs: int, files: int,
                       clang: str = DEFAULT_GUEST_CLANG,
                       fixture_guest_path: str = "/root/fixture.i",
                       workdir: str = "/root/work") -> str:
    """The measured region, executed by the guest shell via /um-command.sh.

    Structure mirrors ``measure.build_batch_script`` (same markers, same
    /proc/uptime centisecond clock, real concurrency through xargs) minus the
    proot prefix -- inside the guest there is nothing to wrap. The compute
    checksum is appended: one extra object is linked with a tiny driver that
    prints ``NL_COMPUTE=<sum>``; a missing or wrong sum discards the table.
    """
    clang_q = shlex.quote(clang)
    tmpdir = f"{workdir}/tmp"
    parts = [
        f"rm -rf {workdir}; mkdir -p {tmpdir}; ",
        f"i=0; while [ \"$i\" -lt {files} ]; do "
        f"cp {shlex.quote(fixture_guest_path)} {workdir}/f$i.i || exit 91; "
        "i=$((i+1)); done; ",
        f"cd {workdir} || exit 92; export TMPDIR={tmpdir}; ",
        "T0=$(cut -d' ' -f1 /proc/uptime); ",
        f"ls f*.i | xargs -P {jobs} -n 1 sh -c "
        f"'{clang_q} -c \"$1\" -o \"${{1%.i}}.o\"' _; RC=$?; ",
        "T1=$(cut -d' ' -f1 /proc/uptime); ",
        "OBJS=0; for f in *.o; do [ -s \"$f\" ] && OBJS=$((OBJS+1)); done; ",
        "cat > check.c <<'NL_EOF'\n"
        "#include <stdio.h>\n"
        "int fixture_entry(void);\n"
        "int main(void) { printf(\"NL_COMPUTE=%d\\n\", fixture_entry()); return 0; }\n"
        "NL_EOF\n",
        f"{clang_q} check.c f0.o -o check || echo NL_COMPUTE_RC=$?; ",
        "[ -x ./check ] && ./check; ",
        "printf 'NL_T0=%s\\nNL_T1=%s\\nNL_RC=%s\\nNL_OBJS=%s\\n' "
        "\"$T0\" \"$T1\" \"$RC\" \"$OBJS\"",
    ]
    return "".join(parts)


def _parse_markers(text: str) -> dict[str, str]:
    # Lazy import: measure.py owns the marker protocol; importing it lazily
    # keeps this module importable both standalone and from measure.py.
    import measure
    return measure.parse_markers(text)


# --------------------------------------------------------------- methodology


def expected_compute_sum(functions: int = 2000) -> int:
    """The value ``fixture_entry()`` must return for the kit's fixture.

    ``probe_fn_i(x)`` returns ``x + i`` and the entry calls each touched
    function with its own index, so the sum is ``sum(2i)`` over the touched
    set -- kept in lockstep with ``gen_fixture.build_fixture`` (asserted by
    the test suite).
    """
    touched = sorted({0, 1, 2, 3, functions // 2, functions - 1})
    return sum(2 * index for index in touched)


def judge_compute(expected: int, actual: int | None,
                  tolerance: float = COMPUTE_TOLERANCE) -> dict:
    """>5% deviation (or a missing checksum) discards the whole table."""
    if actual is None:
        return {"status": "NO_COMPUTE_CHECK", "deviation": None,
                "tolerance": tolerance}
    deviation = abs(actual - expected) / abs(expected)
    return {"status": "OK" if deviation <= tolerance else "DISCARD_TABLE",
            "deviation": round(deviation, 6), "tolerance": tolerance}


def round_jitter(samples_ms: list[int]) -> float | None:
    """In-round spread relative to the median; fork README:275-278."""
    usable = [s for s in samples_ms if s is not None]
    if len(usable) < 2:
        return None
    median = statistics.median(usable)
    if not median:
        return None
    return (max(usable) - min(usable)) / median


def normalize_faults(faults: int, page_bytes: int) -> float:
    """Fault counts to faulted MiB, so 4K-page and 16K-page counts compare.

    The trap from fork README:238-243: the same touched bytes produce 4x more
    fault events on a 4K-page kernel than on a 16K-page one. Normalizing by
    page size recovers the byte volume, which is the comparable quantity.
    """
    return faults * page_bytes / (1024 * 1024)


def interleaved_schedule(conditions: list[int], rounds: int = ROUNDS) -> list[list[int]]:
    """Rotated per-round order; kills position bias inside one table."""
    if not conditions:
        return [[] for _ in range(rounds)]
    return [conditions[r % len(conditions):] + conditions[:r % len(conditions)]
            for r in range(rounds)]


def faults_block() -> dict:
    """Honest absence: fault lanes need the fault-kernel line, not silence."""
    return {
        "status": "NOT_COLLECTED",
        "raw": None,
        "page_bytes": PAGE_BYTES_UML_GUEST,
        "normalized_mib": None,
        "note": "fault collection needs the fault-kernel verifybench line; raw "
                "counts must be normalized by page bytes (native 4K vs guest "
                "16K) before any comparison",
    }


# -------------------------------------------------------------- host provenance


def probe_local_env() -> tuple[dict, list[str]]:
    """Host-side provenance for a real UML run.

    A host has no dumpsys: battery temperature and screen state are missing
    by nature and must be granted ``--allow-incomplete-provenance`` explicitly
    (they stay ``n/a``, they are never faked).
    """
    env: dict = {"nproc": None, "cpuinfo_max_freq_khz": None,
                 "scaling_governor": None, "thermal_start": None,
                 "battery_temp_dc": None, "screen": None}
    missing: list[str] = []
    env["nproc"] = os.cpu_count()
    if not env["nproc"]:
        missing.append("nproc")
    freqs = []
    governors: set[str] = set()
    base = Path("/sys/devices/system/cpu")
    if base.is_dir():
        for cpu_dir in base.glob("cpu*/cpufreq"):
            try:
                freqs.append(int((cpu_dir / "cpuinfo_max_freq").read_text().strip()))
                governors.add((cpu_dir / "scaling_governor").read_text().strip())
            except (OSError, ValueError):
                continue
    if freqs:
        env["cpuinfo_max_freq_khz"] = max(freqs)
    else:
        missing.append("cpuinfo_max_freq_khz")
    if governors:
        env["scaling_governor"] = " ".join(sorted(governors))
    else:
        missing.append("scaling_governor")
    zones: dict[str, int] = {}
    thermal = Path("/sys/class/thermal")
    if thermal.is_dir():
        for zone in thermal.glob("thermal_zone*"):
            try:
                name = zone.name
                kind = (zone / "type").read_text().strip()
                value = int((zone / "temp").read_text().strip())
                zones[f"{name}:{kind}"] = value
            except (OSError, ValueError):
                continue
    if zones:
        env["thermal_start"] = dict(sorted(zones.items()))
    else:
        missing.append("thermal_start")
    for field in ("battery_temp_dc", "screen"):
        missing.append(field)
    return env, missing


# --------------------------------------------------------------- measurement


def launch_kernel(cfg: UmlConfig, timeout_s: int = BATCH_TIMEOUT_S):
    """Boot the guest, wait for the marker stream, return (rc, stdout, stderr).

    stdin is /dev/null: the console is wired with ``con0=fd:0,fd:1`` for
    *output*; the workload is driven by ``/um-command.sh`` inside the rootfs,
    not by typing at the console.
    """
    argv = build_launch_argv(cfg)
    try:
        completed = subprocess.run(
            argv, stdin=subprocess.DEVNULL, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=timeout_s)
    except subprocess.TimeoutExpired:
        return None, None, "timed out"
    except OSError as error:
        raise LaneError("UML_EXEC_FAILED", f"{argv[0]}: {error}") from error
    return completed.returncode, completed.stdout, completed.stderr


def _aggregate_compute(results: list[dict]) -> dict:
    overall = "OK"
    for result in results:
        if result["status"] != "OK":
            overall = result["status"]
            break
    return {"rounds": results, "status": overall}


def run_measured_rounds(cfg: UmlConfig, dot_i_path: Path, jobs_levels: list[int],
                        files: int, warnings: list[str]) -> list[dict]:
    """ROUNDS interleaved rounds per condition; medians; compute/jitter gates."""
    if platform.system() != "Linux":
        raise LaneError(
            "UML_HOST_UNSUPPORTED",
            f"the UML kernel runs as an ordinary Linux process; this host is "
            f"{platform.system()}. Assemble with --plan instead.")
    if cfg.rootfs_mode == "ubd0":
        # Honest refusal, not a silent downgrade: pushing a fixture into ext4
        # needs the umnet/vec0 gate (or debugfs), neither built in session 20.
        raise LaneError(
            "UBD0_DELIVERY_UNAVAILABLE",
            "session 20 delivers the fixture and um-command.sh through the "
            "hostfs directory; the ubd0/ext4 channel needs the umnet/vec0 gate")
    facts = artifact_status(cfg)
    if not facts["kernel_present"]:
        raise LaneError("UML_KERNEL_MISSING",
                        f"{cfg.kernel} not found; expected the out-of-repo "
                        f"release at {_posix(DEFAULT_RELEASE_DIR)}")
    if not facts["stub_present"]:
        raise LaneError("UML_STUB_MISSING", f"{cfg.stub} not found")
    if not facts["rootfs_ready"]:
        raise LaneError(
            "UML_ROOTFS_NOT_PREPARED",
            f"{_posix(cfg.hostfs_dir)} lacks um-init and/or bin/sh; prepare the "
            f"hostfs rootfs directory first (alpine minirootfs + clang + um-init)")

    root = cfg.hostfs_dir / "root"
    root.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(dot_i_path, root / "fixture.i")

    schedule = interleaved_schedule(sorted(jobs_levels))
    samples: dict[int, list[int | None]] = {level: [] for level in jobs_levels}
    objects: dict[int, list[int]] = {level: [] for level in jobs_levels}
    computes: dict[int, list[int | None]] = {level: [] for level in jobs_levels}
    stderr_tail: dict[int, str] = {level: "" for level in jobs_levels}
    last_script: dict[int, str] = {}

    for round_index, order in enumerate(schedule):
        for level in order:
            script = build_guest_script(level, files, clang=cfg.guest_clang)
            last_script[level] = script
            (cfg.hostfs_dir / "um-command.sh").write_text(
                script + "\n", encoding="utf-8", newline="\n")
            rc, out, err = launch_kernel(cfg)
            if out is None:
                samples[level].append(None)
                stderr_tail[level] = str(err)
                continue
            markers = _parse_markers(out)
            stderr_tail[level] = (err or "")[-400:]
            try:
                t0, t1 = markers["NL_T0"], markers["NL_T1"]
                samples[level].append(int(round((float(t1) - float(t0)) * 1000)))
            except (KeyError, ValueError):
                samples[level].append(None)
            try:
                objects[level].append(int(markers.get("NL_OBJS", "0") or 0))
            except ValueError:
                objects[level].append(0)
            try:
                computes[level].append(int(markers["NL_COMPUTE"]))
            except (KeyError, ValueError):
                computes[level].append(None)

    expected = expected_compute_sum()
    rows: list[dict] = []
    command = shlex.join(build_launch_argv(cfg))
    for level in sorted(jobs_levels):
        round_walls = samples[level]
        row: dict = {
            "column": LANE_ID, "jobs": level, "files": files,
            "command": command, "guest_script": last_script.get(level, ""),
            "samples_ms": round_walls, "rounds": ROUNDS,
            "schedule": "interleaved", "rootfs_mode": cfg.rootfs_mode,
        }
        if any(s is None for s in round_walls) or len(round_walls) != ROUNDS:
            row.update(status="TIMEOUT" if any(s is None for s in round_walls)
                       else "NO_TIMING", wall_ms=None, files_per_min=None,
                       objects_ok=min(objects[level] or [0]))
            rows.append(row)
            continue
        wall_ms = int(round(statistics.median(round_walls)))
        jitter = round_jitter(round_walls)
        compute_rounds = [
            {"round": index + 1, "actual": actual,
             **judge_compute(expected, actual)}
            for index, actual in enumerate(computes[level])
        ]
        compute = {**_aggregate_compute(compute_rounds), "expected": expected,
                   "tolerance": COMPUTE_TOLERANCE}
        status = "OK"
        if compute["status"] != "OK":
            status = "DISCARD_TABLE"
            warnings.append(
                f"UML_COMPUTE_DISCARD jobs={level}: compute gate said "
                f"{compute['status']} (expected {expected}); the whole UML "
                "table is void (methodology rule 3)")
        elif jitter is not None and jitter > JITTER_TOLERANCE:
            status = "DISCARD_TABLE"
            warnings.append(
                f"UML_JITTER_DISCARD jobs={level}: in-round jitter "
                f"{jitter:.3f} > {JITTER_TOLERANCE}; the whole UML table is void")
        if status == "OK" and min(objects[level]) < files:
            status = "MISSING_OBJECTS"
        row.update(status=status, wall_ms=wall_ms,
                   files_per_min=(round(files * 60000 / wall_ms, 2)
                                  if status == "OK" and wall_ms else None),
                   objects_ok=min(objects[level]), compute=compute,
                   jitter=None if jitter is None else round(jitter, 4),
                   faults=faults_block())
        if stderr_tail[level]:
            row["stderr_tail"] = stderr_tail[level]
        rows.append(row)
    return rows


def synthetic_samples(jobs: int, rounds: int = ROUNDS) -> list[int]:
    """Deliberately fake, deterministic wall clocks (same rule as fake_adb)."""
    base = {1: 4210, 7: 905}.get(jobs, 3000)
    return [base + ((index * 37) % 53) - 26 for index in range(rounds)]


def synthetic_rows(cfg: UmlConfig, jobs_levels: list[int], files: int) -> list[dict]:
    """Rows with the real schema and synthetic numbers, marked as such."""
    validate_config(cfg)
    expected = expected_compute_sum()
    command = shlex.join(build_launch_argv(cfg))
    rows: list[dict] = []
    for level in sorted(jobs_levels):
        samples = synthetic_samples(level)
        median = statistics.median(samples)
        rows.append({
            "column": LANE_ID, "jobs": level, "files": files,
            "command": command, "guest_script": build_guest_script(level, files),
            "status": "OK", "wall_ms": int(round(median)),
            "files_per_min": round(files * 60000 / median, 2) if median else None,
            "objects_ok": files, "samples_ms": samples, "rounds": ROUNDS,
            "schedule": "interleaved", "rootfs_mode": cfg.rootfs_mode,
            "compute": {"expected": expected, "actual": expected,
                        "deviation": 0.0, "status": "OK", "synthetic": True},
            "jitter": round((max(samples) - min(samples)) / median, 4),
            "faults": faults_block(),
        })
    return rows


SYNTHETIC_ENV = {
    "nproc": 8,
    "cpuinfo_max_freq_khz": 3148800,
    "scaling_governor": "schedutil",
    "thermal_start": {"thermal_zone0:cpu": 38000, "thermal_zone1:gpu": 36500,
                      "thermal_zone2:battery": 31200},
    "thermal_end": {"thermal_zone0:cpu": 38200, "thermal_zone1:gpu": 36700,
                    "thermal_zone2:battery": 31400},
    "battery_temp_dc": 312,
    "screen": "Awake",
}


# ------------------------------------------------------------------ plumbing


def config_from_args(args: argparse.Namespace, prefix: str = "uml") -> UmlConfig:
    def get(name: str):
        return getattr(args, f"{prefix}_{name}", None)

    ubd0 = get("ubd0")
    if ubd0:
        return UmlConfig(
            kernel=Path(get("kernel") or DEFAULT_KERNEL),
            stub=Path(get("stub") or DEFAULT_STUB),
            rootfs_mode="ubd0", ubd0_image=Path(ubd0),
            mem=get("mem") or DEFAULT_MEM, ncpus=int(get("ncpus") or DEFAULT_NCPUS),
            prefault=get("prefault"), seccomp=get("seccomp") or DEFAULT_SECCOMP,
            guest_clang=get("clang") or DEFAULT_GUEST_CLANG)
    return UmlConfig(
        kernel=Path(get("kernel") or DEFAULT_KERNEL),
        stub=Path(get("stub") or DEFAULT_STUB),
        rootfs_mode="hostfs",
        hostfs_dir=Path(get("hostfs_dir") or DEFAULT_HOSTFS_DIR),
        mem=get("mem") or DEFAULT_MEM, ncpus=int(get("ncpus") or DEFAULT_NCPUS),
        prefault=get("prefault"), seccomp=get("seccomp") or DEFAULT_SECCOMP,
        guest_clang=get("clang") or DEFAULT_GUEST_CLANG)


def add_uml_arguments(parser: argparse.ArgumentParser, prefix: str = "uml") -> None:
    """Register the UML launch options on any parser (measure.py reuses this)."""
    group = parser.add_argument_group("UML lane (uml_lane.py, session 20 D10)")
    group.add_argument(f"--{prefix}-kernel", default=None,
                       help=f"UML kernel image (default: out-of-repo "
                            f"{_posix(DEFAULT_KERNEL)})")
    group.add_argument(f"--{prefix}-stub", default=None,
                       help=f"stub_exe path, mandatory on Android "
                            f"(default: {_posix(DEFAULT_STUB)})")
    group.add_argument(f"--{prefix}-hostfs-dir", default=None,
                       help=f"hostfs rootfs directory (default: "
                            f"{_posix(DEFAULT_HOSTFS_DIR)})")
    group.add_argument(f"--{prefix}-ubd0", default=None,
                       help="ext4 image; switches the rootfs channel from "
                            "hostfs (default) to ubd0")
    group.add_argument(f"--{prefix}-mem", default=None,
                       help=f"guest memory (default: {DEFAULT_MEM})")
    group.add_argument(f"--{prefix}-ncpus", type=int, default=None,
                       help=f"guest CPUs (default: {DEFAULT_NCPUS}; ptrace "
                            "userspace is single-CPU anyway)")
    group.add_argument(f"--{prefix}-prefault", default=None,
                       help="prefault window, e.g. 128K or 512K (omitted "
                            "unless given)")
    group.add_argument(f"--{prefix}-seccomp", default=None,
                       choices=list(SECCOMP_MODES),
                       help=f"seccomp mode (default: {DEFAULT_SECCOMP})")
    group.add_argument(f"--{prefix}-clang", default=None,
                       help=f"clang path *inside the guest* "
                            f"(default: {DEFAULT_GUEST_CLANG})")


def run_column(args: argparse.Namespace, jobs_levels: list[int], dot_i_path: Path,
               out_dir: Path, synthetic: bool = False,
               preflight_only: bool = False) -> dict:
    """Integration entry called by measure.py for the ``uml`` column.

    Returns ``{"rows", "env", "incomplete", "preflight", "warnings"}`` so the
    lane lands in the same report table without touching the adb paths.
    """
    warnings: list[str] = []
    cfg = config_from_args(args)
    validate_config(cfg)
    facts = artifact_status(cfg)
    preflight = {
        "lane": LANE_ID, "kernel": _posix(cfg.kernel), **facts,
        "rootfs_mode": cfg.rootfs_mode,
        "hostfs_dir": _posix(cfg.hostfs_dir) if cfg.hostfs_dir else None,
        "ubd0_image": _posix(cfg.ubd0_image) if cfg.ubd0_image else None,
    }
    if synthetic:
        return {
            "rows": [] if preflight_only else synthetic_rows(cfg, jobs_levels,
                                                             args.files),
            "env": dict(SYNTHETIC_ENV), "incomplete": [],
            "preflight": preflight,
            "warnings": ["uml rows are synthetic (dry run); not a measurement"],
        }
    env, incomplete = probe_local_env()
    if preflight_only:
        return {"rows": [], "env": env, "incomplete": incomplete,
                "preflight": preflight, "warnings": warnings}
    rows = run_measured_rounds(cfg, dot_i_path, jobs_levels, args.files, warnings)
    return {"rows": rows, "env": env, "incomplete": incomplete,
            "preflight": preflight, "warnings": warnings}


# --------------------------------------------------------------------- main


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--plan", action="store_true",
                      help="assemble and print the launch argv + guest scripts; "
                           "no execution (default)")
    mode.add_argument("--run", action="store_true",
                      help="attempt a real measured run (Linux host + out-of-repo "
                           "release artifacts required)")
    parser.add_argument("--preflight-only", action="store_true",
                        help="probe artifacts/provenance and stop")
    parser.add_argument("--jobs", default="1,7", help="job levels, e.g. 1,7")
    parser.add_argument("--files", type=int, default=7,
                        help="fixture copies per job level (>= max(jobs))")
    parser.add_argument("--dot-i", default=str(DEFAULT_DOT_I))
    parser.add_argument("--out", default=str(DEFAULT_RESULTS))
    parser.add_argument("--allow-incomplete-provenance", action="store_true")
    add_uml_arguments(parser, prefix="uml")
    args = parser.parse_args(argv)

    jobs = [int(part) for part in args.jobs.split(",") if part.strip()]
    if not jobs or args.files < max(jobs):
        print(f"--files must be >= max(--jobs); got files={args.files} "
              f"jobs={args.jobs}", file=sys.stderr)
        return 2
    dot_i_path = Path(args.dot_i)
    if not dot_i_path.is_file():
        print(f"FIXTURE_MISSING: {dot_i_path}; run gen_fixture.py", file=sys.stderr)
        return 2

    cfg = config_from_args(args)
    try:
        validate_config(cfg)
    except LaneError as error:
        print(str(error), file=sys.stderr)
        return 2

    if not args.run:
        facts = artifact_status(cfg)
        print(f"UML lane plan (lane id: {LANE_ID})")
        print(f"kernel:      {_posix(cfg.kernel)}  "
              f"[{'present' if facts['kernel_present'] else 'MISSING (out-of-repo release)'}]")
        print(f"stub_exe:    {_posix(cfg.stub)}  "
              f"[{'present' if facts['stub_present'] else 'MISSING'}]")
        print(f"rootfs:      {cfg.rootfs_mode} -> "
              f"{_posix(cfg.hostfs_dir or cfg.ubd0_image)}  "
              f"[{'ready' if facts['rootfs_ready'] else 'not ready'}]")
        print("launch argv:")
        print(f"  {shlex.join(build_launch_argv(cfg))}")
        for level in jobs:
            print(f"guest batch script (-j{level}):")
            print("  " + build_guest_script(level, args.files,
                                             clang=cfg.guest_clang))
        print("methodology: interleaved same-table conditions; "
              f"{ROUNDS} rounds, medians never means; compute checksum "
              f"deviation >{COMPUTE_TOLERANCE:.0%} discards the table; fault "
              "counts normalized by page bytes; no cross-table comparison")
        return 0

    run_started = datetime.now(timezone.utc)
    out_dir = Path(args.out) / run_started.strftime("%Y%m%dT%H%M%SZ")
    out_dir.mkdir(parents=True, exist_ok=True)
    try:
        result = run_column(args, jobs, dot_i_path, out_dir,
                            preflight_only=args.preflight_only)
    except LaneError as error:
        print(str(error), file=sys.stderr)
        return 2
    if args.preflight_only:
        print(result["preflight"])
        return 0 if not result["incomplete"] else 3
    report = {
        "schema_version": SCHEMA_VERSION,
        "synthetic": False,
        "lane": LANE_ID,
        "run": {"started_utc": run_started.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "fixture": {"path": str(dot_i_path),
                            "bytes": dot_i_path.stat().st_size},
                "parameters": {"jobs": jobs, "files_per_batch": args.files,
                               "rounds": ROUNDS, "schedule": "interleaved",
                               "clock": "proc_uptime_cs"}},
        "env": {**result["env"], "incomplete": result["incomplete"]},
        "preflight": result["preflight"],
        "rows": result["rows"],
        "warnings": result["warnings"],
    }
    (out_dir / "uml-lane.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8")
    for row in result["rows"]:
        print(f"uml -j{row['jobs']}: {row['status']} wall_ms={row.get('wall_ms')} "
              f"files_per_min={row.get('files_per_min')}")
    if result["incomplete"] and not args.allow_incomplete_provenance:
        print(f"PROVENANCE_INCOMPLETE {result['incomplete']}: rows withheld; "
              "rerun with --allow-incomplete-provenance", file=sys.stderr)
        return 3
    failed = [row["jobs"] for row in result["rows"]
              if row["status"] not in ("OK", "PARTIAL_FAILURE")]
    if failed:
        print(f"ROWS_FAILED {failed}", file=sys.stderr)
        return 4
    print(f"results written to {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
