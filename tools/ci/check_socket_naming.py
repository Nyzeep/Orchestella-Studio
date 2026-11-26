#!/usr/bin/env python3
"""Verify single-source abstract socket naming consistency across Java and Python.

Prevents regressions where Java launchers/activities and Python device-validation
probes diverge on abstract control, terminal, or per-instance audio socket names
or accidentally reintroduce legacy ``orchestella-<id>-ctrl/-term`` or shared
``@droidlayer-audio`` fallback names.

Standard library only. Run from the repository root:

    python3 tools/ci/check_socket_naming.py
    python3 tools/ci/check_socket_naming.py --root /path/to/checkout
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

CONTROL_PREFIX = "droidlayer-control-"
TERMINAL_PREFIX = "droidlayer-terminal-"
AUDIO_PREFIX = "orchestella-audio-"

LEGACY_CTRL_TERM_RE = re.compile(
    r"orchestella-[^\n\"']*-(?:ctrl|term)\b|orchestella-\"\s*\+[^\n;]+-\s*(?:ctrl|term)"
)

TARGET_FILES: dict[str, str] = {
    "QemuInstanceLauncher.java": "app/src/main/java/io/github/nyzeep/orchestella/QemuInstanceLauncher.java",
    "TerminalActivity.java": "app/src/main/java/io/github/nyzeep/orchestella/TerminalActivity.java",
    "DesktopPeripheralController.java": "app/src/main/java/io/github/nyzeep/orchestella/DesktopPeripheralController.java",
    "PvmProbeInstrumentation.java": "app/src/androidTest/java/io/github/nyzeep/orchestella/PvmProbeInstrumentation.java",
    "gui_desktop_acceptance.py": "tools/device-validation/gui_desktop_acceptance.py",
    "dv_pvm_crossfire.py": "tools/device-validation/dv_pvm_crossfire.py",
}

# Symbol-bound patterns so comments or unrelated literals cannot mask prefix drift.
PREFIX_CHECKS: tuple[tuple[str, str, str, str], ...] = (
    (
        "QemuInstanceLauncher.java",
        "control prefix",
        rf'controlSocket\s*=\s*"{re.escape(CONTROL_PREFIX)}"',
        f'expected controlSocket = "{CONTROL_PREFIX}"',
    ),
    (
        "QemuInstanceLauncher.java",
        "terminal prefix",
        rf'terminalSocket\s*=\s*"{re.escape(TERMINAL_PREFIX)}"',
        f'expected terminalSocket = "{TERMINAL_PREFIX}"',
    ),
    (
        "QemuInstanceLauncher.java",
        "audio prefix",
        rf'return\s+"{re.escape(AUDIO_PREFIX)}"\s*\+\s*shortAudioInstanceId\(',
        f'expected return "{AUDIO_PREFIX}" + shortAudioInstanceId(...)',
    ),
    (
        "TerminalActivity.java",
        "control prefix",
        rf'CONTROL_SOCKET_PREFIX\s*=\s*"{re.escape(CONTROL_PREFIX)}"',
        f'expected CONTROL_SOCKET_PREFIX = "{CONTROL_PREFIX}"',
    ),
    (
        "TerminalActivity.java",
        "terminal prefix",
        rf'TERMINAL_SOCKET_PREFIX\s*=\s*"{re.escape(TERMINAL_PREFIX)}"',
        f'expected TERMINAL_SOCKET_PREFIX = "{TERMINAL_PREFIX}"',
    ),
    (
        "PvmProbeInstrumentation.java",
        "terminal prefix",
        rf':\s*"{re.escape(TERMINAL_PREFIX)}"\s*\+\s*instance\.id\(\)',
        f'expected "{TERMINAL_PREFIX}" + instance.id()',
    ),
    (
        "gui_desktop_acceptance.py",
        "control prefix",
        rf'CONTROL_SOCKET_PREFIX\s*=\s*"{re.escape(CONTROL_PREFIX)}"',
        f'expected CONTROL_SOCKET_PREFIX = "{CONTROL_PREFIX}"',
    ),
    (
        "gui_desktop_acceptance.py",
        "terminal prefix",
        rf'TERMINAL_SOCKET_PREFIX\s*=\s*"{re.escape(TERMINAL_PREFIX)}"',
        f'expected TERMINAL_SOCKET_PREFIX = "{TERMINAL_PREFIX}"',
    ),
    (
        "gui_desktop_acceptance.py",
        "audio prefix",
        rf'AUDIO_SOCKET_PREFIX\s*=\s*"{re.escape(AUDIO_PREFIX)}"',
        f'expected AUDIO_SOCKET_PREFIX = "{AUDIO_PREFIX}"',
    ),
    (
        "dv_pvm_crossfire.py",
        "terminal prefix",
        rf'TERMINAL_SOCKET_PREFIX\s*=\s*"{re.escape(TERMINAL_PREFIX)}"',
        f'expected TERMINAL_SOCKET_PREFIX = "{TERMINAL_PREFIX}"',
    ),
)

LEGACY_CTRL_TERM_TARGETS = (
    "QemuInstanceLauncher.java",
    "TerminalActivity.java",
    "PvmProbeInstrumentation.java",
    "gui_desktop_acceptance.py",
    "dv_pvm_crossfire.py",
)

SHARED_AUDIO_BAN_TARGETS = (
    "QemuInstanceLauncher.java",
    "DesktopPeripheralController.java",
)


class Result:
    def __init__(self) -> None:
        self.passed = 0
        self.failed = 0

    def check(self, name: str, ok: bool, detail: str = "") -> bool:
        if ok:
            self.passed += 1
            print(f"PASS  {name}")
        else:
            self.failed += 1
            print(f"FAIL  {name}" + (f": {detail}" if detail else ""))
        return ok


def load_targets(result: Result, root: Path) -> dict[str, str]:
    contents: dict[str, str] = {}
    for label, rel_path in TARGET_FILES.items():
        path = root / rel_path
        if result.check(f"{label} exists", path.is_file(), rel_path):
            contents[label] = path.read_text(encoding="utf-8", errors="replace")
    return contents


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="repository root (default: two levels above this script)",
    )
    args = parser.parse_args()
    root = args.root.resolve()

    result = Result()
    contents = load_targets(result, root)

    for label, check_name, pattern, detail in PREFIX_CHECKS:
        text = contents.get(label)
        if text is None:
            continue
        result.check(f"{label} {check_name}", re.search(pattern, text) is not None, detail)

    for label in LEGACY_CTRL_TERM_TARGETS:
        text = contents.get(label)
        if text is None:
            continue
        result.check(
            f"{label} forbids legacy control/terminal socket pattern",
            LEGACY_CTRL_TERM_RE.search(text) is None,
            "found legacy orchestella-*-ctrl/-term reference",
        )

    for label in SHARED_AUDIO_BAN_TARGETS:
        text = contents.get(label)
        if text is None:
            continue
        result.check(
            f"{label} forbids shared @droidlayer-audio fallback",
            "droidlayer-audio" not in text,
            "found shared droidlayer-audio reference",
        )

    print(f"SUMMARY: {result.passed} passed, {result.failed} failed")
    return 0 if result.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
