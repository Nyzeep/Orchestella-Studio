#!/usr/bin/env python3
"""Canonical protected-lane failure vocabulary for host tools.

This mirrors the Java ProtectedFailure type. Reports and the device matrix
carry only a fixed code, a fixed stage and an optional errno; raw AVC text,
logcat lines, paths and tokens never enter an emitted value.

Run from the repository root or add tools/fastlane-device to sys.path.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

CODE_PLATFORM_EXEC_BLOCKED = "TOOLBOX_EXEC_BLOCKED_BY_PLATFORM"

FAILURE_CLASSES = (
    "PLATFORM_BLOCKED",
    "PERMISSION",
    "SCHEMA",
    "LIFECYCLE",
    "STOP",
    "INSTALL",
    "UNKNOWN",
)

CODE_CLASSES = {
    CODE_PLATFORM_EXEC_BLOCKED: "PLATFORM_BLOCKED",
    "TERMINAL_ERROR": "LIFECYCLE",
    "PERMISSION_DENIED": "PERMISSION",
    "HIDDEN_API_BLOCKED": "PERMISSION",
    "SCHEMA_INVALID": "SCHEMA",
    "CONFIG_INVALID": "SCHEMA",
    "VM_CREATE_FAILED": "LIFECYCLE",
    "READY_TIMEOUT": "LIFECYCLE",
    "HELLO_FAILED": "LIFECYCLE",
    "VSOCK_FAILED": "LIFECYCLE",
    "PROCESS_DIED": "LIFECYCLE",
    "STOP_UNCONFIRMED": "STOP",
    "DRAIN_TIMEOUT": "STOP",
    "PAYLOAD_VERIFY_FAILED": "INSTALL",
    "PROTECTED_UNAVAILABLE": "INSTALL",
    "FASTLANE_UNKNOWN": "UNKNOWN",
}

RUNNER_CODES = {
    CODE_PLATFORM_EXEC_BLOCKED: (CODE_PLATFORM_EXEC_BLOCKED, "execve", 13),
    "PERMISSION_DENIED": ("PERMISSION_DENIED", None, None),
    "HIDDEN_API_BLOCKED": ("HIDDEN_API_BLOCKED", None, None),
    "PAYLOAD_VERIFY_FAILED": ("PAYLOAD_VERIFY_FAILED", "install", None),
    "PROTECTED_UNAVAILABLE": ("PROTECTED_UNAVAILABLE", "preflight", None),
    "VSOCK_FAILED": ("VSOCK_FAILED", "terminal_pty", None),
    "STOP_UNCONFIRMED": ("STOP_UNCONFIRMED", "stop", None),
    "CONFIG_INVALID": ("SCHEMA_INVALID", "preflight", None),
    "SCHEMA_INVALID": ("SCHEMA_INVALID", "preflight", None),
    "VM_CREATE_FAILED": ("VM_CREATE_FAILED", "create", None),
    "READY_TIMEOUT": ("READY_TIMEOUT", "payload_ready", None),
    "HELLO_FAILED": ("HELLO_FAILED", "control_hello", None),
    "PROCESS_DIED": ("PROCESS_DIED", "run", None),
    "DRAIN_TIMEOUT": ("DRAIN_TIMEOUT", "stop", None),
}

STAGES = frozenset(
    {
        "preflight",
        "create",
        "run",
        "payload_ready",
        "control_hello",
        "terminal_pty",
        "execve",
        "open_pty",
        "fork",
        "connect",
        "hello",
        "read",
        "write",
        "stop",
        "delete",
        "install",
    }
)

DETAIL_STAGES = (
    "open_pty",
    "execve",
    "fork",
    "connect",
    "hello",
    "read",
    "write",
    "stop",
)

ERRNO_RE = re.compile(r"errno[=_-](\d+)")
MATRIX_ERRNO_RE = re.compile(r"errno=(\d+)")


@dataclass(frozen=True)
class Failure:
    code: str
    failure_class: str
    stage: str | None
    errno: int | None

    def __post_init__(self) -> None:
        if self.code not in CODE_CLASSES:
            raise ValueError("unrecognized code")
        if CODE_CLASSES[self.code] != self.failure_class:
            raise ValueError("code/class mismatch")
        if self.stage is not None and self.stage not in STAGES:
            raise ValueError("unrecognized stage")
        if self.errno is not None and self.errno < 0:
            raise ValueError("errno must be non-negative")

    def render(self) -> str:
        text = self.code
        if self.stage is not None:
            text += f" stage={self.stage}"
        if self.errno is not None:
            text += f" errno={self.errno}"
        return text


def _failure(code: str, stage: str | None = None, errno: int | None = None) -> Failure:
    return Failure(code, CODE_CLASSES[code], stage, errno)


def _detail_stage(detail: str) -> str | None:
    for candidate in DETAIL_STAGES:
        if detail.startswith(candidate):
            return candidate
    return None


def _errno(detail: str) -> int | None:
    match = ERRNO_RE.search(detail)
    return int(match.group(1)) if match else None


def from_terminal(fln1_code: int, detail: str | None) -> Failure:
    """Classify one failed terminal attempt without retaining its message."""
    normalized = (detail or "").lower()
    stage = _detail_stage(normalized)
    errno = _errno(normalized)
    if fln1_code == 7 and stage == "execve" and errno == 13:
        return _failure(CODE_PLATFORM_EXEC_BLOCKED, "execve", 13)
    if fln1_code >= 0:
        return _failure("TERMINAL_ERROR", stage, errno)
    return _failure("FASTLANE_UNKNOWN")


def from_runner(reason: str | None) -> Failure:
    if not reason:
        return _failure("FASTLANE_UNKNOWN")
    mapped = RUNNER_CODES.get(reason)
    if mapped is None:
        return _failure("FASTLANE_UNKNOWN")
    return _failure(*mapped)


def from_legacy(
    value: str | None, fln1: int | None = None, errno: int | None = None
) -> Failure:
    """Normalize a historical report or matrix spelling at the input edge."""
    if not value:
        return _failure("FASTLANE_UNKNOWN")
    text = value.strip()
    if not text or text.startswith("avc:") or "/" in text:
        return _failure("FASTLANE_UNKNOWN")
    if text.startswith(CODE_PLATFORM_EXEC_BLOCKED):
        parsed = errno if errno is not None else _matrix_errno(text)
        stage = "execve" if "execve" in text.lower() else None
        if parsed is None and stage == "execve":
            parsed = 13
        return _failure(CODE_PLATFORM_EXEC_BLOCKED, stage, parsed)
    if text.lower() == "execve_errno_13":
        return _failure(CODE_PLATFORM_EXEC_BLOCKED, "execve", 13)
    if text.startswith("TERMINAL_ERROR_FLN1_"):
        if text == "TERMINAL_ERROR_FLN1_7":
            return _failure(CODE_PLATFORM_EXEC_BLOCKED, "execve", 13)
        return _failure("TERMINAL_ERROR")
    if text == "PREFLIGHT_TERMINAL_BLOCKED":
        if fln1 == 7 and errno == 13:
            return _failure(CODE_PLATFORM_EXEC_BLOCKED, "execve", 13)
        return _failure("TERMINAL_ERROR", "terminal_pty")
    return from_runner(text)


def _matrix_errno(value: str) -> int | None:
    match = MATRIX_ERRNO_RE.search(value.lower())
    return int(match.group(1)) if match else None


def canonical_reason(value: str | None, fln1: int | None = None, errno: int | None = None) -> str:
    """Return the canonical code name for a report or matrix value."""
    return from_legacy(value, fln1, errno).code
