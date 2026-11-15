#!/usr/bin/env python3
"""Host-side gate for the debug terminal frame benchmark (OE_BENCH).

One command: install the debug APK, launch ``TerminalBenchActivity``, collect the
``OE_BENCH`` logcat lines, compare them with the captured baseline bucket and
exit with a verdict. Only ``adb`` and the Python standard library are used.

Exit codes:

* ``0`` - every fixture is within the thresholds, or there is nothing to judge
  (missing baseline bucket / missing baseline fixture);
* ``1`` - at least one fixture regressed;
* ``2`` - environment or harness failure (no adb, no device, install failed,
  benchmark timeout, activity reported an error).

The measurements themselves are produced on a device by the debug activity.
This script never invents numbers. ``--dry-run`` needs neither adb nor an APK
and is the offline acceptance path.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

EXIT_OK = 0
EXIT_REGRESSION = 1
EXIT_ENV = 2

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
DEFAULT_BASELINE = SCRIPT_DIR / "baseline.json"
DEFAULT_APK = (
    REPO_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
)
DEFAULT_PACKAGE = "io.github.nyzeep.orchestella"
ACTIVITY_SUFFIX = ".bench.TerminalBenchActivity"
DEFAULT_TIMEOUT_SECONDS = 240.0
POLL_INTERVAL_SECONDS = 2.0
QUICK_COMMAND_TIMEOUT_SECONDS = 30.0
INSTALL_TIMEOUT_SECONDS = 300.0

SCHEMA = "terminal-bench-baseline-v1"
FIXTURES = ("flood_seq", "flood_color", "scroll_2000", "type_100", "resize_font")
FIXTURE_CHOICES = ("all",) + FIXTURES

DEFAULT_THRESHOLDS = {
    "p95MaxRegressionRatio": 0.15,
    "jankyMaxRegressionPoints": 5.0,
    "fpsMaxDrop": 3.0,
}

ANSI_RE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
BRIEF_RE = re.compile(
    r"^(?P<level>[VDIWEFA])/(?P<tag>[^(]+?)\s*\(\s*(?P<pid>\d+)\s*\):\s?(?P<message>.*)$"
)
# Underscore is a word character, so this never matches OE_BENCH_DONE / OE_BENCH_ERROR.
RESULT_TAG_RE = re.compile(r"\bOE_BENCH\b")
DONE_TAG = "OE_BENCH_DONE"
ERROR_TAG = "OE_BENCH_ERROR"

# Metrics written into a captured bucket, in diff order.
CAPTURE_FIELDS = (
    "frames",
    "janky",
    "p50",
    "p95",
    "p99",
    "max",
    "fps",
    "javaKb",
    "nativeKb",
)
# Extra diagnostics kept for humans; never used by the regression rules.
EXTRA_FIELDS = ("cols", "rows", "refreshHz", "durationMs", "drawP95", "layoutP95", "dropped")


class BenchError(Exception):
    """A failure that maps to exit code 2 with a stable ASCII token."""

    def __init__(self, token: str, detail: str | None = None) -> None:
        super().__init__(token)
        self.token = token
        self.detail = detail


# --- parsing -----------------------------------------------------------------


def parse_logcat(text: str) -> dict:
    """Parse ``adb logcat -d -v brief`` output into fixtures, errors and DONE.

    Tolerates CRLF (adb on Windows), CR-only line endings and ANSI color codes.
    Lines outside the brief format are ignored. ``OE_BENCH_DONE`` and
    ``OE_BENCH_ERROR`` are matched as whole tags, never as fixture results.
    """
    results: dict[str, dict] = {}
    errors: list[dict] = []
    done = False
    for raw_line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        line = ANSI_RE.sub("", raw_line).rstrip()
        if not line:
            continue
        match = BRIEF_RE.match(line)
        if match is None:
            continue
        tag = match.group("tag").strip()
        message = match.group("message").strip()
        if tag == DONE_TAG:
            done = True
            continue
        if tag == ERROR_TAG:
            errors.append(_parse_object(message) or {"error": message})
            continue
        if RESULT_TAG_RE.fullmatch(tag) is None:
            continue
        payload = _parse_object(message)
        if payload is None or not isinstance(payload.get("fixture"), str):
            continue
        if "error" in payload:
            errors.append(payload)
            continue
        results[payload["fixture"]] = payload
    return {"results": results, "errors": errors, "done": done}


def _parse_object(message: str) -> dict | None:
    try:
        payload = json.loads(message)
    except (json.JSONDecodeError, RecursionError):
        return None
    return payload if isinstance(payload, dict) else None


def is_result_tag(tag: str) -> bool:
    """True only for the exact ``OE_BENCH`` tag, not for its DONE/ERROR children."""
    return RESULT_TAG_RE.fullmatch(tag.strip()) is not None


# --- baseline ----------------------------------------------------------------


def default_baseline() -> dict:
    return {
        "schema": SCHEMA,
        "status": "pending-device",
        "capturedAt": None,
        "thresholds": dict(DEFAULT_THRESHOLDS),
        "buckets": {},
    }


def load_baseline(path: Path, *, create: bool) -> dict:
    if not path.is_file():
        if create:
            return default_baseline()
        raise BenchError("BASELINE_MISSING", f"baseline not found: {path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except OSError as error:
        raise BenchError("BASELINE_INVALID", f"{path}: {error}") from None
    except json.JSONDecodeError as error:
        raise BenchError("BASELINE_INVALID", f"{path}: {error}") from None
    if not isinstance(data, dict):
        raise BenchError("BASELINE_INVALID", f"{path}: top level is not an object")
    if data.get("schema") != SCHEMA:
        raise BenchError(
            "BASELINE_INVALID",
            f"{path}: schema is {data.get('schema')!r}, expected {SCHEMA!r}",
        )
    buckets = data.setdefault("buckets", {})
    if not isinstance(buckets, dict):
        raise BenchError("BASELINE_INVALID", f"{path}: buckets is not an object")
    data["thresholds"] = merged_thresholds(data.get("thresholds"))
    return data


def merged_thresholds(raw: object) -> dict:
    merged = dict(DEFAULT_THRESHOLDS)
    if isinstance(raw, dict):
        for key in DEFAULT_THRESHOLDS:
            value = _number(raw.get(key))
            if value is not None:
                merged[key] = value
    return merged


def bucket_key(device: str, api: int) -> str:
    return f"{str(device).replace('/', '_').strip()}/{int(api)}"


def measured_identity(results: dict) -> tuple[str, int] | None:
    for result in results.values():
        device = result.get("device")
        api = _number(result.get("api"))
        if isinstance(device, str) and device and api is not None:
            return device, int(api)
    return None


def utc_now() -> str:
    return (
        datetime.now(timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def write_json(path: Path, payload: dict, token: str) -> None:
    try:
        path.write_text(
            json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )
    except OSError as error:
        raise BenchError(token, f"{path}: {error}") from None


def update_baseline(
    baseline: dict, path: Path, measured: dict, model: str, api: int
) -> tuple[str, str]:
    """Merge the measured fixtures into ``<model>/<api>`` and write the file.

    Returns ``(bucket_key, captured_at)``. Fixtures already in that bucket but
    not measured this run are kept; other buckets are untouched.
    """
    key = bucket_key(model, api)
    buckets = baseline.setdefault("buckets", {})
    old_bucket = buckets.get(key)
    if not isinstance(old_bucket, dict):
        old_bucket = {}
    old_fixtures = old_bucket.get("fixtures")
    if not isinstance(old_fixtures, dict):
        old_fixtures = {}

    new_fixtures = dict(old_fixtures)
    written = 0
    for fixture, result in measured.items():
        entry = {
            field: result[field]
            for field in CAPTURE_FIELDS + EXTRA_FIELDS
            if _number(result.get(field)) is not None
        }
        if not entry:
            continue
        new_fixtures[fixture] = entry
        written += 1
    if written == 0:
        raise BenchError("BENCH_NO_RESULTS", "no numeric metrics to record")

    captured_at = utc_now()
    buckets[key] = {
        "device": str(model),
        "api": int(api),
        "capturedAt": captured_at,
        "fixtures": new_fixtures,
    }
    baseline["capturedAt"] = captured_at
    baseline["status"] = "captured"
    write_json(path, baseline, "BASELINE_WRITE_FAILED")
    return key, captured_at


def baseline_diff(old: dict, new: dict) -> list[str]:
    lines: list[str] = []
    for fixture in new:
        old_entry = old.get(fixture) if isinstance(old.get(fixture), dict) else {}
        new_entry = new[fixture]
        fields = [field for field in CAPTURE_FIELDS if field in old_entry or field in new_entry]
        fields += sorted((set(old_entry) | set(new_entry)) - set(CAPTURE_FIELDS))
        for field in fields:
            old_value = old_entry.get(field)
            new_value = new_entry.get(field)
            if old_value == new_value:
                continue
            suffix = ""
            if field == "p95":
                old_number = _number(old_value)
                new_number = _number(new_value)
                if old_number and new_number is not None:
                    suffix = f"  ({((new_number / old_number) - 1.0) * 100.0:+.1f}%)"
            lines.append(
                f"  {fixture}.{field}: {_show(old_value)} -> {_show(new_value)}{suffix}"
            )
    return lines


# --- comparison --------------------------------------------------------------


def evaluate(measured: dict, bucket: dict, thresholds: dict) -> dict:
    """Apply the regression rules and return per-fixture lines plus failures.

    A fixture or metric missing on the baseline side is reported but never
    fails; only a fixture with a numeric value on both sides is judged.
    """
    p95_ratio = _threshold(thresholds, "p95MaxRegressionRatio")
    janky_points = _threshold(thresholds, "jankyMaxRegressionPoints")
    fps_drop = _threshold(thresholds, "fpsMaxDrop")

    baseline_fixtures = bucket.get("fixtures") if isinstance(bucket, dict) else None
    if not isinstance(baseline_fixtures, dict):
        baseline_fixtures = {}

    failures: dict[str, list[str]] = {}
    lines: list[str] = []
    judged = 0
    for fixture, result in measured.items():
        base = baseline_fixtures.get(fixture)
        if not isinstance(base, dict) or not base:
            lines.append(
                f"SKIP {fixture}  {_metrics(result)} [no baseline fixture: report only]"
            )
            continue
        reasons: list[str] = []
        checked = 0

        measured_p95 = _number(result.get("p95"))
        baseline_p95 = _number(base.get("p95"))
        if measured_p95 is not None and baseline_p95 is not None:
            checked += 1
            limit = baseline_p95 * (1.0 + p95_ratio)
            if measured_p95 > limit:
                if baseline_p95 > 0:
                    change = f"{((measured_p95 / baseline_p95) - 1.0) * 100.0:+.1f}% of baseline"
                else:
                    change = "baseline is zero"
                reasons.append(
                    f"p95 {_fmt(measured_p95)} > {_fmt(limit)} "
                    f"({change}, allowed +{p95_ratio * 100.0:.1f}%)"
                )

        measured_janky = _number(result.get("janky"))
        measured_frames = _number(result.get("frames"))
        baseline_janky = _number(base.get("janky"))
        baseline_frames = _number(base.get("frames"))
        if (
            measured_janky is not None
            and measured_frames is not None
            and baseline_janky is not None
            and baseline_frames is not None
            and measured_frames > 0
            and baseline_frames > 0
        ):
            checked += 1
            measured_ratio = measured_janky / measured_frames * 100.0
            baseline_ratio = baseline_janky / baseline_frames * 100.0
            if measured_ratio > baseline_ratio + janky_points:
                reasons.append(
                    f"janky {measured_ratio:.1f}% > {baseline_ratio + janky_points:.1f}% "
                    f"(baseline {baseline_ratio:.1f}% +{janky_points:.1f}pp)"
                )

        measured_fps = _number(result.get("fps"))
        baseline_fps = _number(base.get("fps"))
        if measured_fps is not None and baseline_fps is not None:
            checked += 1
            limit = baseline_fps - fps_drop
            if measured_fps < limit:
                reasons.append(
                    f"fps {_fmt(measured_fps)} < {_fmt(limit)} "
                    f"(baseline {_fmt(baseline_fps)} -{_fmt(fps_drop)})"
                )

        if checked == 0:
            lines.append(
                f"SKIP {fixture}  {_metrics(result)} [no baseline metric: report only]"
            )
            continue

        judged += 1
        if reasons:
            failures[fixture] = reasons
            lines.append(f"FAIL {fixture}  " + "; ".join(reasons))
        else:
            lines.append(f"PASS {fixture}  {_metrics(result)}")

    return {"failures": failures, "lines": lines, "judged": judged}


def _threshold(thresholds: dict, key: str) -> float:
    value = _number(thresholds.get(key))
    return DEFAULT_THRESHOLDS[key] if value is None else value


# --- adb ---------------------------------------------------------------------


def resolve_adb(explicit: str | None) -> str:
    """``--adb`` first, then PATH, then the SDK environment roots.

    An explicit path is authoritative: a bad explicit path is an error, never a
    silent fallback. Mirrors tools/fastlane-device/run_dv01.py.
    """
    if explicit:
        candidate = Path(explicit).expanduser()
        if candidate.is_file():
            return str(candidate.resolve())
        raise BenchError("ADB_UNAVAILABLE", f"--adb is not a file: {explicit}")
    found = shutil.which("adb")
    if found:
        return found
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(variable)
        if not root:
            continue
        for executable in ("adb", "adb.exe"):
            candidate = Path(root).expanduser() / "platform-tools" / executable
            if candidate.is_file():
                return str(candidate)
    raise BenchError(
        "ADB_UNAVAILABLE", "adb not found on PATH, $ANDROID_HOME or $ANDROID_SDK_ROOT"
    )


def resolve_serial(adb: str, explicit: str | None) -> str:
    """Explicit ``--serial`` / ``$ANDROID_SERIAL`` first, else the single device."""
    serial = explicit or os.environ.get("ANDROID_SERIAL") or None
    if serial:
        proc = command(
            [adb, "-s", serial, "get-state"],
            QUICK_COMMAND_TIMEOUT_SECONDS,
            "ADB_UNAVAILABLE",
        )
        if proc.returncode != 0 or proc.stdout.strip() != "device":
            raise BenchError(
                "NO_DEVICE",
                f"device {serial!r} is not usable: {_combined(proc) or proc.stdout.strip()}",
            )
        return serial

    proc = command([adb, "devices"], QUICK_COMMAND_TIMEOUT_SECONDS, "ADB_UNAVAILABLE")
    if proc.returncode != 0:
        raise BenchError("ADB_UNAVAILABLE", _combined(proc))
    available: list[str] = []
    blocked: list[str] = []
    for line in proc.stdout.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        if parts[1] == "device":
            available.append(parts[0])
        elif parts[1] in {"unauthorized", "offline", "no"}:
            blocked.append(f"{parts[0]}({parts[1]})")
    if not available:
        detail = "no device in 'device' state"
        if blocked:
            detail += "; saw " + ", ".join(blocked)
        raise BenchError("NO_DEVICE", detail)
    if len(available) > 1:
        raise BenchError(
            "NO_DEVICE",
            "multiple devices: " + ", ".join(available) + "; pass --serial",
        )
    return available[0]


def command(cmd: list[str], timeout: float, token: str) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError:
        raise BenchError("ADB_UNAVAILABLE", f"cannot execute {cmd[0]}") from None
    except OSError as error:
        raise BenchError("ADB_UNAVAILABLE", f"cannot execute {cmd[0]}: {error}") from None
    except subprocess.TimeoutExpired:
        raise BenchError("BENCH_TIMEOUT", f"command timed out after {timeout:.0f}s")


def _device_args(adb: str, serial: str | None) -> list[str]:
    return [adb] + (["-s", serial] if serial else [])


def _combined(proc: subprocess.CompletedProcess) -> str:
    return "\n".join(part.strip() for part in (proc.stdout, proc.stderr) if part.strip())


def install_apk(adb: str, serial: str, apk: Path, timeout: float) -> None:
    if not apk.is_file():
        raise BenchError(
            "INSTALL_FAILED",
            f"APK not found: {apk} (build :app:assembleDebug first)",
        )
    proc = command(
        _device_args(adb, serial) + ["install", "-r", "-t", str(apk)],
        timeout,
        "INSTALL_FAILED",
    )
    if proc.returncode != 0 or "Success" not in proc.stdout:
        raise BenchError("INSTALL_FAILED", _combined(proc) or "adb install failed")
    print("INSTALL_OK")


def clear_logcat(adb: str, serial: str) -> None:
    proc = command(
        _device_args(adb, serial) + ["logcat", "-c"],
        QUICK_COMMAND_TIMEOUT_SECONDS,
        "BENCH_ERROR",
    )
    if proc.returncode != 0:
        proc = command(
            _device_args(adb, serial) + ["logcat", "-b", "all", "-c"],
            QUICK_COMMAND_TIMEOUT_SECONDS,
            "BENCH_ERROR",
        )
        if proc.returncode != 0:
            raise BenchError(
                "BENCH_ERROR", "could not clear logcat: " + (_combined(proc) or "adb logcat -c")
            )


def force_stop(adb: str, serial: str, package: str) -> None:
    """Start every run from a fresh process: JIT and cached views are part of the fixture.

    Best effort: some OEM builds refuse ``am force-stop``; a failure is reported but the
    launch that follows is still authoritative.
    """
    proc = command(
        _device_args(adb, serial) + ["shell", "am", "force-stop", package],
        QUICK_COMMAND_TIMEOUT_SECONDS,
        "BENCH_ERROR",
    )
    if proc.returncode != 0:
        print("WARN am force-stop failed: " + (_combined(proc) or "adb exit != 0"))


def launch_activity(adb: str, serial: str, package: str, fixture: str) -> None:
    component = f"{package}/{ACTIVITY_SUFFIX}"
    proc = command(
        _device_args(adb, serial)
        + ["shell", "am", "start", "-W", "-n", component, "--es", "fixture", fixture],
        QUICK_COMMAND_TIMEOUT_SECONDS,
        "BENCH_ERROR",
    )
    output = _combined(proc)
    if proc.returncode != 0 or "Error:" in output or "Exception" in output:
        raise BenchError("BENCH_ERROR", "activity launch failed: " + (output or "no output"))
    print("LAUNCH_OK")


def poll_logcat(adb: str, serial: str, timeout: float) -> dict:
    """Poll ``logcat -d`` every ~2 s until DONE (or an activity error) arrives."""
    deadline = time.monotonic() + timeout
    while True:
        proc = command(
            _device_args(adb, serial)
            + [
                "logcat",
                "-d",
                "-v",
                "brief",
                "-s",
                "OE_BENCH:V",
                "OE_BENCH_DONE:V",
                "OE_BENCH_ERROR:V",
            ],
            QUICK_COMMAND_TIMEOUT_SECONDS,
            "BENCH_ERROR",
        )
        if proc.returncode != 0:
            raise BenchError("BENCH_ERROR", "adb logcat -d failed: " + _combined(proc))
        parsed = parse_logcat(proc.stdout)
        if parsed["done"] or parsed["errors"]:
            return parsed
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise BenchError(
                "BENCH_TIMEOUT",
                f"no OE_BENCH_DONE within {timeout:.0f}s",
            )
        time.sleep(min(POLL_INTERVAL_SECONDS, remaining))


def capture(
    adb: str,
    serial: str,
    *,
    package: str,
    fixture: str,
    apk: Path,
    install: bool,
    timeout: float,
) -> dict:
    if install:
        install_apk(adb, serial, apk, max(INSTALL_TIMEOUT_SECONDS, timeout))
    force_stop(adb, serial, package)
    clear_logcat(adb, serial)
    launch_activity(adb, serial, package, fixture)
    return poll_logcat(adb, serial, timeout)


# --- cli ---------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Install the debug APK, run the terminal frame fixtures "
            "and gate on the baseline."
        )
    )
    parser.add_argument(
        "--serial",
        default=None,
        help="adb serial (default: $ANDROID_SERIAL or the single device)",
    )
    parser.add_argument("--fixture", choices=FIXTURE_CHOICES, default="all")
    parser.add_argument(
        "--baseline",
        default=None,
        help="baseline JSON (default: <script_dir>/baseline.json)",
    )
    parser.add_argument("--update-baseline", action="store_true")
    parser.add_argument(
        "--apk",
        default=None,
        help="debug APK (default: app/build/outputs/apk/debug/app-debug.apk)",
    )
    parser.add_argument(
        "--package",
        default=DEFAULT_PACKAGE,
        help="applicationId override for channel builds",
    )
    parser.add_argument("--adb", default=None, help="explicit adb path")
    parser.add_argument("--model", default=None, help="device model for --update-baseline")
    parser.add_argument("--api", type=int, default=None, help="API level for --update-baseline")
    parser.add_argument(
        "--timeout",
        type=float,
        default=None,
        help="seconds to wait for OE_BENCH_DONE (default 240)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="no adb, no APK; print the plan and validate the baseline",
    )
    parser.add_argument(
        "--logcat", default=None, help="parse a saved logcat instead of running adb"
    )
    parser.add_argument(
        "--json-out", default=None, help="write a result envelope for a release checklist"
    )
    parser.add_argument(
        "--no-install",
        action="store_true",
        help="skip adb install (APK already on the device)",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return execute(args)
    except BenchError as error:
        print(f"ERROR={error.token}", file=sys.stderr)
        if error.detail:
            print(error.detail, file=sys.stderr)
        return EXIT_ENV


def execute(args: argparse.Namespace) -> int:
    baseline_path = Path(args.baseline).expanduser() if args.baseline else DEFAULT_BASELINE
    apk_path = Path(args.apk).expanduser() if args.apk else DEFAULT_APK
    timeout = args.timeout if args.timeout is not None else DEFAULT_TIMEOUT_SECONDS
    if not math.isfinite(timeout) or timeout <= 0:
        raise BenchError("BAD_ARGUMENT", "--timeout must be a positive number")
    if args.update_baseline:
        if not args.model:
            raise BenchError("MODEL_REQUIRED", "--update-baseline requires --model")
        if args.api is None:
            raise BenchError("API_REQUIRED", "--update-baseline requires --api")
        if args.api <= 0:
            raise BenchError("BAD_ARGUMENT", "--api must be a positive integer")

    baseline = load_baseline(baseline_path, create=bool(args.update_baseline))
    thresholds = baseline["thresholds"]

    needs_device = not args.dry_run and not args.logcat
    adb: str | None = None
    serial: str | None = None
    if needs_device:
        adb = resolve_adb(args.adb)
        serial = resolve_serial(adb, args.serial)
        adb_display, serial_display = adb, serial
    elif args.dry_run:
        try:
            adb_display = resolve_adb(args.adb)
        except BenchError as error:
            adb_display = f"<{error.token}>"
        serial_display = args.serial or os.environ.get("ANDROID_SERIAL") or "<auto>"
    else:
        adb_display = serial_display = "not-used"

    print_plan(
        args,
        baseline_path,
        apk_path,
        baseline,
        thresholds,
        adb_display,
        serial_display,
        timeout,
    )

    if args.logcat:
        try:
            text = Path(args.logcat).expanduser().read_text(encoding="utf-8", errors="replace")
        except OSError as error:
            raise BenchError("LOG_READ_FAILED", f"{args.logcat}: {error}") from None
        parsed = parse_logcat(text)
        source = f"--logcat {args.logcat}"
        if not parsed["done"]:
            print("WARN logcat has no OE_BENCH_DONE; comparing whatever was parsed")
    elif args.dry_run:
        print("DRY_RUN no measurements (pass --logcat FILE to compare a saved capture)")
        return EXIT_OK
    else:
        assert adb is not None and serial is not None
        parsed = capture(
            adb,
            serial,
            package=args.package,
            fixture=args.fixture,
            apk=apk_path,
            install=not args.no_install,
            timeout=timeout,
        )
        source = f"device {serial}"

    if parsed["errors"]:
        for entry in parsed["errors"]:
            print("BENCH_ERROR " + json.dumps(entry, ensure_ascii=False))
        raise BenchError("BENCH_ERROR", f"the activity reported a failure ({source})")
    if not parsed["results"]:
        raise BenchError("BENCH_NO_RESULTS", f"no OE_BENCH result lines parsed from {source}")

    expected = FIXTURES if args.fixture == "all" else (args.fixture,)
    missing = [fixture for fixture in expected if fixture not in parsed["results"]]
    if missing:
        print("NOTE missing fixtures: " + ",".join(missing))

    identity = measured_identity(parsed["results"])
    if args.update_baseline:
        assert args.model is not None and args.api is not None
        if identity is not None and (identity[0] != args.model or identity[1] != args.api):
            print(
                f"WARN parsed device/api {identity[0]}/{identity[1]} differs from "
                f"--model/--api {args.model}/{args.api}; keeping the explicit values"
            )
        key = bucket_key(args.model, args.api)
        old_bucket = baseline["buckets"].get(key)
        old_fixtures = old_bucket.get("fixtures") if isinstance(old_bucket, dict) else {}
        if not isinstance(old_fixtures, dict):
            old_fixtures = {}

        if args.dry_run:
            print(f"DRY_RUN baseline update skipped for bucket={key}; remove --dry-run to write")
            return EXIT_OK

        key, captured_at = update_baseline(
            baseline, baseline_path, parsed["results"], args.model, args.api
        )
        new_fixtures = baseline["buckets"][key]["fixtures"]
        print(f"BASELINE_UPDATE bucket={key}")
        diff = baseline_diff(old_fixtures, new_fixtures)
        for line in diff or ["  (no metric changes)"]:
            print(line)
        print(f"BASE_STATUS status={baseline['status']} fixtures={len(new_fixtures)}")
        print(f"WROTE_BASELINE {baseline_path}")
        if args.json_out:
            write_json(
                Path(args.json_out).expanduser(),
                {"bucket": key, "capturedAt": captured_at, "results": parsed["results"]},
                "JSON_OUT_FAILED",
            )
        return EXIT_OK

    if identity is None:
        raise BenchError(
            "BENCH_NO_DEVICE_IDENTITY",
            "result lines carry no usable device/api; cannot pick a baseline bucket",
        )
    device, api = identity
    key = bucket_key(device, api)
    bucket = baseline["buckets"].get(key)
    print(f"BUCKET {key}")

    captured_at = bucket.get("capturedAt") if isinstance(bucket, dict) else None
    if captured_at is None:
        captured_at = baseline.get("capturedAt")

    if not isinstance(bucket, dict) or not bucket.get("fixtures"):
        print(f"NO_BASELINE bucket={key}")
        for fixture, result in parsed["results"].items():
            print(f"  {fixture} {_metrics(result)}")
        print("NOTE report only; no judgment")
        if args.json_out:
            write_json(
                Path(args.json_out).expanduser(),
                {
                    "bucket": key,
                    "capturedAt": captured_at,
                    "results": parsed["results"],
                },
                "JSON_OUT_FAILED",
            )
        return EXIT_OK

    report = evaluate(parsed["results"], bucket, thresholds)
    for line in report["lines"]:
        print(line)
    if args.json_out:
        write_json(
            Path(args.json_out).expanduser(),
            {
                "bucket": key,
                "capturedAt": captured_at,
                "results": parsed["results"],
            },
            "JSON_OUT_FAILED",
        )
    if report["failures"]:
        print(f"RESULT=REGRESSION failures={len(report['failures'])}")
        return EXIT_REGRESSION
    print(f"RESULT=PASS judged={report['judged']}")
    return EXIT_OK


def print_plan(
    args: argparse.Namespace,
    baseline_path: Path,
    apk_path: Path,
    baseline: dict,
    thresholds: dict,
    adb_display: str,
    serial_display: str,
    timeout: float,
) -> None:
    print(
        f"PLAN fixture={args.fixture} package={args.package} "
        f"serial={serial_display} adb={adb_display}"
    )
    print(f"PLAN apk={apk_path} exists={'yes' if apk_path.is_file() else 'no'}")
    print(
        f"PLAN baseline={baseline_path} status={baseline.get('status')} "
        f"buckets={len(baseline.get('buckets') or {})}"
    )
    print(
        "PLAN thresholds "
        f"p95MaxRegressionRatio={_fmt(thresholds['p95MaxRegressionRatio'])} "
        f"jankyMaxRegressionPoints={_fmt(thresholds['jankyMaxRegressionPoints'])} "
        f"fpsMaxDrop={_fmt(thresholds['fpsMaxDrop'])}"
    )
    mode = "dry-run" if args.dry_run else ("logcat" if args.logcat else "device")
    print(f"PLAN timeout={_fmt(timeout)}s mode={mode}")


# --- formatting --------------------------------------------------------------


def _number(value: object) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    if not math.isfinite(value):
        return None
    return float(value)


def _fmt(value: float) -> str:
    number = float(value)
    if number.is_integer():
        return str(int(number))
    return f"{number:.4f}".rstrip("0").rstrip(".")


def _show(value: object) -> str:
    number = _number(value)
    if number is not None:
        return _fmt(number)
    return "-" if value is None else str(value)


def _metrics(result: dict) -> str:
    parts = []
    for key in ("frames", "janky", "p95", "fps"):
        value = _number(result.get(key))
        if value is not None:
            parts.append(f"{key}={_fmt(value)}")
    return " ".join(parts) or "no metrics"


if __name__ == "__main__":
    sys.exit(main())
