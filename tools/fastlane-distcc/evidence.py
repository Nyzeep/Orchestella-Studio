"""AX-06 evidence checks and fixed-field logging.

The real e2e test writes only hashes, counters, durations and fixed codes. This
module rejects any report that grows a path, token, source or object body, and
provides the only log-line helper used by the host script.
"""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

HEX64 = re.compile(r"^[0-9a-f]{64}$")
TOKEN32 = re.compile(r"[0-9a-f]{32}")
ALLOWED_KEYS = {
    "case",
    "outcome",
    "failureCode",
    "objectSha256",
    "localSha256",
    "hashEqual",
    "durationMs",
    "bytesFromClient",
    "bytesToClient",
}
EXPECTED_CASES = {
    "success": ("COMPLETED", "NONE"),
    "wrong-token": ("CHANNEL_FAILED", "AXP1_AUTH_FAILED"),
    "handshake-timeout": ("CHANNEL_FAILED", "AXP1_HANDSHAKE_TIMEOUT"),
    "compiler-mismatch": ("CHANNEL_FAILED", "COMPILER_MISMATCH"),
    "accelerator-exit": ("CHANNEL_FAILED", "AXP1_CLOSED"),
}
LOG_FIELDS = ("jobs", "bytes_in", "bytes_out", "duration_ms", "code")


class ReportError(ValueError):
    """A report is missing evidence or contains a forbidden field."""


def load_report(path: str | Path) -> list[dict]:
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    validate_report(data)
    return data


def validate_report(data: object) -> None:
    if not isinstance(data, list) or not data:
        raise ReportError("report must be a non-empty JSON array")
    seen: dict[str, dict] = {}
    for entry in data:
        if not isinstance(entry, dict):
            raise ReportError("each report entry must be an object")
        if set(entry) != ALLOWED_KEYS:
            raise ReportError("report entry keys are not the fixed evidence set")
        case = entry["case"]
        if case not in EXPECTED_CASES:
            raise ReportError(f"unknown case: {case}")
        if case in seen:
            raise ReportError(f"duplicate case: {case}")
        seen[case] = entry
        outcome, failure = EXPECTED_CASES[case]
        if entry["outcome"] != outcome or entry["failureCode"] != failure:
            raise ReportError(f"unexpected outcome for {case}")
        _require_hash(entry["objectSha256"], "objectSha256")
        _require_hash(entry["localSha256"], "localSha256")
        if not isinstance(entry["hashEqual"], bool):
            raise ReportError("hashEqual must be boolean")
        for key in ("durationMs", "bytesFromClient", "bytesToClient"):
            if not isinstance(entry[key], int) or entry[key] < 0:
                raise ReportError(f"{key} must be a non-negative integer")
        for value in entry.values():
            if isinstance(value, str) and (
                "/" in value or "\\" in value or "@" in value
            ):
                raise ReportError("report contains a path-like or identity field")
        if any(
            isinstance(value, str) and TOKEN32.fullmatch(value)
            for value in entry.values()
        ):
            raise ReportError("report looks like it contains a token")
    missing = set(EXPECTED_CASES) - set(seen)
    if missing:
        raise ReportError("missing cases: " + ",".join(sorted(missing)))


def safe_log_line(
    jobs: int, bytes_in: int, bytes_out: int, duration_ms: int, code: str
) -> str:
    """Fixed-field JSON log; no argv, source, token or object bytes."""
    values = {
        "jobs": jobs,
        "bytes_in": bytes_in,
        "bytes_out": bytes_out,
        "duration_ms": duration_ms,
        "code": code,
    }
    for key, value in values.items():
        if key == "code":
            if not isinstance(value, str) or not re.fullmatch(r"[A-Z0-9_]{1,64}", value):
                raise ValueError("code must be a fixed uppercase token")
        elif not isinstance(value, int) or value < 0:
            raise ValueError(f"{key} must be a non-negative integer")
    return json.dumps(values, separators=(",", ":"), sort_keys=True)


def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _require_hash(value: object, name: str) -> None:
    if not isinstance(value, str) or not HEX64.fullmatch(value):
        raise ReportError(f"{name} must be a lowercase SHA-256")
