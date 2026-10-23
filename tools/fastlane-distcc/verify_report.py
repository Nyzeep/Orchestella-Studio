#!/usr/bin/env python3
"""Validate the AX-06 host-only distcc evidence report."""

from __future__ import annotations

import sys

from evidence import ReportError, load_report


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: verify_report.py <report.json>", file=sys.stderr)
        return 2
    try:
        entries = load_report(argv[1])
    except (OSError, ValueError, ReportError) as failure:
        print(f"DISTCC_E2E_REPORT_INVALID: {failure}", file=sys.stderr)
        return 1
    for entry in entries:
        print(
            "DISTCC_E2E "
            f"case={entry['case']} outcome={entry['outcome']} "
            f"failure={entry['failureCode']} hashEqual={entry['hashEqual']} "
            f"durationMs={entry['durationMs']}"
        )
    print(f"DISTCC_E2E_OK cases={len(entries)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
