#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Deterministic adb stand-in for the native-lane dry run.

It answers the exact probes and batch scripts that ``measure.py`` emits, with
fixed values, so the whole pipeline can be exercised on a workstation with no
device attached. It never opens a socket, never runs a subprocess and never
touches a real adb server.

Two rules keep this honest:

1. Nothing here is a measurement. The wall-clock numbers come from
   ``DURATIONS_MS`` below, a hand-written table whose only purpose is to be
   deterministic and to differ by column and job level (so a parsing bug that
   mixes up rows is visible).
2. Every value is derived from the command text plus a call counter persisted in
   ``--state``. Same call sequence, same output, byte for byte -- which is what
   lets ``test_native_lane.py`` diff a dry run against a checked-in expectation.

Drop a probe with ``FAKE_ADB_DROP=nproc,thermal_start`` to simulate a device that
refuses to answer, which is how the "incomplete provenance withholds the table"
path gets tested.
"""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

# Synthetic wall clock per (column, jobs). Deliberately not realistic-looking:
# two clean ratios would invite someone to quote them.
DURATIONS_MS = {
    ("proot", 1): 3120,
    ("proot", 7): 1410,
    ("bare", 1): 2680,
    ("bare", 7): 1180,
}

THERMAL_START_MC = {"thermal_zone0:cpu": 38000, "thermal_zone1:gpu": 36500,
                    "thermal_zone2:battery": 31200}
THERMAL_RISE_MC = 200

CLANG_VERSION_LINE = "clang version 22.1.8 (Alpine 22.1.8-r1)"


def load_state(path: Path) -> dict:
    if path.is_file():
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            pass
    return {"calls": 0}


def save_state(path: Path, state: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state), encoding="utf-8")


def dropped() -> set[str]:
    raw = os.environ.get("FAKE_ADB_DROP", "")
    return {part.strip() for part in raw.split(",") if part.strip()}


def column_of(script: str) -> str | None:
    if "proot-static" in script:
        return "proot"
    if "ld-musl-aarch64.so.1" in script:
        return "bare"
    return None


def jobs_of(script: str) -> int | None:
    match = re.search(r"xargs\s+-P\s*(\d+)", script)
    return int(match.group(1)) if match else None


def files_of(script: str) -> int:
    match = re.search(r"-lt\s+(\d+)", script)
    return int(match.group(1)) if match else 1


def thermal_lines(offset_mc: int) -> str:
    return "\n".join(f"{name.split(':')[0]} {name.split(':')[1]} {value + offset_mc}"
                     for name, value in THERMAL_START_MC.items())


def answer_shell(script: str, state: dict, drop: set[str]) -> str:
    """Return the device-side stdout for one shell script."""

    if "NL_T0" in script:
        column = column_of(script) or "unknown"
        jobs = jobs_of(script) or 1
        files = files_of(script)
        duration = DURATIONS_MS.get((column, jobs), 5000)
        t0 = 1000.00 + state["calls"] * 10
        t1 = t0 + duration / 1000
        return (f"NL_T0={t0:.2f}\nNL_T1={t1:.2f}\nNL_RC=0\nNL_OBJS={files}\n")

    if "NL_REGEN_RC" in script:
        return CLANG_VERSION_LINE + "\nNL_REGEN_RC=0\n"

    if "--version" in script:
        return CLANG_VERSION_LINE

    if script.strip() in ("nproc", "nproc ", "getconf _NPROCESSORS_ONLN"):
        return "" if "nproc" in drop else "8\n"

    if "cpuinfo_max_freq" in script:
        return "" if "freq" in drop else "3148800\n"

    if "scaling_governor" in script:
        return "" if "governor" in drop else "schedutil\n"

    if "thermal_zone" in script:
        if "thermal_start" in drop:
            return ""
        # Dedicated counter: the first thermal probe is the before-snapshot, the
        # second the after. Counting every adb call would make the offset depend
        # on how many unrelated probes happen to run first.
        index = state.get("thermal_calls", 0)
        state["thermal_calls"] = index + 1
        return thermal_lines(0 if index == 0 else THERMAL_RISE_MC) + "\n"

    if "dumpsys battery" in script:
        return "" if "battery" in drop else "  level: 88\n  temperature: 312\n"

    if "dumpsys power" in script:
        return "" if "screen" in drop else "  mWakefulness=Awake\n"

    if ".native-lane-rootfs.json" in script:
        root = re.search(r"(/\S+)/\.native-lane-rootfs\.json", script)
        return (root.group(1) if root else "/data/local/tmp/native-lane/rootfs") + \
               "/.native-lane-rootfs.json\n"

    if "ld-musl-aarch64.so.1" in script and script.strip().startswith("ls"):
        root = re.search(r"(/\S+)/lib/ld-musl", script)
        return (root.group(1) if root else "/data/local/tmp/native-lane/rootfs") + \
               "/lib/ld-musl-aarch64.so.1\n"

    return ""


def main(argv: list[str]) -> int:
    state_path = Path(argv[1]) if len(argv) > 1 and not argv[1].startswith("-") else None
    rest = argv[2:] if state_path else argv[1:]

    # Tolerate the global options adb accepts before the subcommand.
    while rest and rest[0] in ("-s", "-d", "-e"):
        rest = rest[2:] if rest[0] == "-s" else rest[1:]

    if not rest:
        print("fake-adb: no command", file=sys.stderr)
        return 1

    state = load_state(state_path) if state_path else {"calls": 0}
    state["calls"] = state.get("calls", 0) + 1
    drop = dropped()

    command = rest[0]
    if command == "devices":
        print("List of devices attached")
        print("fake-device\tdevice product:fake model:fake")
        return 0
    if command == "shell":
        output = answer_shell(" ".join(rest[1:]), state, drop)
        if state_path:
            save_state(state_path, state)
        print(output, end="")
        return 0
    if state_path:
        save_state(state_path, state)
    if command == "push":
        return 0
    if command == "pull":
        local = Path(rest[2]) if len(rest) > 2 else Path("pulled.i")
        local.parent.mkdir(parents=True, exist_ok=True)
        local.write_text("# 1 \"fixture.c\"\n/* synthetic pull from fake adb */\n"
                         "int puts(const char*);\nint fixture_entry(void) { return 0; }\n",
                         encoding="utf-8", newline="\n")
        return 0
    if command in ("forward", "wait-for-device", "start-server", "kill-server"):
        return 0
    print(f"fake-adb: unsupported command {command}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
