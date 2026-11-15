#!/usr/bin/env python3
"""Compare Kotlin and Rust VT benchmark results and emit a Markdown table.

Input:  out/kotlin.json, out/rust.json  (override the directory with --out)
Output: out/compare.md and the same table on stdout.

Canonical schema (either side):

  {
    "schema": "orchestella.vtbench/1",
    "impl": "rust",
    "fixtures": [
      {
        "id": "plain",
        "bytes": 10485760,
        "sha256": "<64 hex>",
        "mb_per_s": 812.4,
        "alloc_bytes_per_mb": 1024.0,
        "snapshot_us": 11.2,      # Kotlin only
        "copy_rows_us": 8.4       # Rust only
      }
    ]
  }

`fixtures` may also be an object keyed by fixture id, and the root may be the
list itself.  A few obvious aliases are accepted (throughput_mb_s, alloc_per_mb,
median_ns + bytes, snapshot_copy_us, ...) so the two benches can land
independently.  Missing row metrics render as "—"; a missing sha256 or a
sha256 disagreement is a hard error (exit 2), because the whole comparison is
only meaningful on identical input bytes.
"""

import argparse
import json
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
FIXTURE_ORDER = ("plain", "ansi_color", "cursor", "wide", "osc")
MIB = 1048576

HEADER = (
    "| 夹具 | Kotlin MB/s | Rust MB/s | 提升 | Kotlin 分配/MB | Rust 分配/MB "
    "| 分配下降 | Kotlin snapshot µs | Rust copy_rows µs |"
)
SEPARATOR = "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"

ID_KEYS = ("id", "fixture", "name")
SHA_KEYS = ("sha256", "sha_256", "digest")
MBPS_KEYS = ("mb_per_s", "mb_s_median", "throughput_mb_s", "mbps", "mib_per_s")
ALLOC_KEYS = ("alloc_bytes_per_mb", "alloc_bytes_per_mb_median", "allocated_bytes_per_mb", "alloc_per_mb")
SNAPSHOT_KEYS = ("snapshot_us", "snapshot_copy_us", "copy_screen_us")
COPY_ROWS_KEYS = ("copy_rows_us", "copy_rows_us_median", "copyrows_us", "copy_rows")


def _pick(entry, names):
    for name in names:
        value = entry.get(name)
        if value is not None:
            return value
    return None


def _number(value):
    if value is None or isinstance(value, bool):
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _iter_entries(doc):
    if isinstance(doc, list):
        return list(doc)
    if not isinstance(doc, dict):
        raise ValueError("root must be an object or a list")
    raw = doc.get("fixtures")
    if raw is None:
        raw = doc.get("results")
    if raw is None:
        raise ValueError('missing "fixtures" array/object')
    if isinstance(raw, dict):
        entries = []
        for key, value in raw.items():
            if not isinstance(value, dict):
                raise ValueError("fixture %r must be an object" % key)
            if _pick(value, ID_KEYS) is None:
                value = dict(value, id=key)
            entries.append(value)
        return entries
    if isinstance(raw, list):
        return raw
    raise ValueError('"fixtures" must be an array or an object')


def load_results(path):
    with open(path, "r", encoding="utf-8") as handle:
        doc = json.load(handle)

    results = {}
    for entry in _iter_entries(doc):
        if not isinstance(entry, dict):
            raise ValueError("fixture entry must be an object")
        fixture_id = _pick(entry, ID_KEYS)
        if not isinstance(fixture_id, str) or not fixture_id:
            raise ValueError("fixture entry without a usable id")
        if fixture_id in results:
            raise ValueError("duplicate fixture id %r" % fixture_id)

        mbps = _number(_pick(entry, MBPS_KEYS))
        byte_count = _number(entry.get("bytes"))
        if mbps is None:
            elapsed_ns = _number(_pick(entry, ("median_ns", "elapsed_ns", "total_ns")))
            if byte_count and elapsed_ns:
                mbps = (byte_count / MIB) / (elapsed_ns / 1e9)

        alloc = _number(_pick(entry, ALLOC_KEYS))
        if alloc is None:
            alloc_bytes = _number(_pick(entry, ("alloc_bytes", "allocated_bytes")))
            if alloc_bytes is not None and byte_count:
                alloc = alloc_bytes / (byte_count / MIB)

        sha = _pick(entry, SHA_KEYS)
        results[fixture_id] = {
            "sha256": sha.strip().lower() if isinstance(sha, str) else None,
            "bytes": int(byte_count) if byte_count else None,
            "mbps": mbps,
            "alloc_per_mb": alloc,
            "snapshot_us": _number(_pick(entry, SNAPSHOT_KEYS)),
            "copy_rows_us": _number(_pick(entry, COPY_ROWS_KEYS)),
        }
    return results


def _fmt(value, unit):
    if value is None:
        return "—"
    return "{:,.1f} {}".format(value, unit)


def _pct(base, new, lower_is_better=False):
    if base is None or new is None or base == 0:
        return "—"
    change = (new - base) / base * 100.0
    if lower_is_better:
        change = -change
    if abs(round(change, 1)) < 0.05:
        return "0.0%"
    return "{:+.1f}%".format(change)


def _valid_sha(value):
    return isinstance(value, str) and len(value) == 64 and all(
        char in "0123456789abcdef" for char in value
    )


def build_table(kotlin, rust):
    lines = [HEADER, SEPARATOR]
    for fixture_id in FIXTURE_ORDER:
        kl = kotlin[fixture_id]
        rs = rust[fixture_id]
        lines.append(
            "| {} | {} | {} | {} | {} | {} | {} | {} | {} |".format(
                fixture_id,
                _fmt(kl["mbps"], "MB/s"),
                _fmt(rs["mbps"], "MB/s"),
                _pct(kl["mbps"], rs["mbps"]),
                _fmt(kl["alloc_per_mb"], "B"),
                _fmt(rs["alloc_per_mb"], "B"),
                _pct(kl["alloc_per_mb"], rs["alloc_per_mb"], lower_is_better=True),
                _fmt(kl["snapshot_us"], "µs"),
                _fmt(rs["copy_rows_us"], "µs"),
            )
        )
    return "\n".join(lines)


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Compare VT benchmark JSON results.")
    parser.add_argument(
        "--out",
        default=os.path.join(SCRIPT_DIR, "out"),
        help="directory holding kotlin.json/rust.json (default: %(default)s)",
    )
    args = parser.parse_args(argv)
    out_dir = os.path.abspath(args.out)

    paths = {
        "kotlin": os.path.join(out_dir, "kotlin.json"),
        "rust": os.path.join(out_dir, "rust.json"),
    }
    for name, path in paths.items():
        if not os.path.isfile(path):
            print("error: missing %s results: %s" % (name, path), file=sys.stderr)
            return 2

    try:
        kotlin = load_results(paths["kotlin"])
        rust = load_results(paths["rust"])
    except (OSError, ValueError) as exc:
        print("error: cannot read results: %s" % exc, file=sys.stderr)
        return 2

    problems = []
    for name, results in (("kotlin", kotlin), ("rust", rust)):
        for fixture_id in FIXTURE_ORDER:
            if fixture_id not in results:
                problems.append("{} missing fixture {!r}".format(name, fixture_id))
    if problems:
        for problem in problems:
            print("error: " + problem, file=sys.stderr)
        return 2

    mismatches = []
    for fixture_id in FIXTURE_ORDER:
        ksha = kotlin[fixture_id]["sha256"]
        rsha = rust[fixture_id]["sha256"]
        for name, sha in (("kotlin", ksha), ("rust", rsha)):
            if not _valid_sha(sha):
                problems.append(
                    "{} {!r} has no valid sha256 (got {!r})".format(
                        name, fixture_id, sha
                    )
                )
        if _valid_sha(ksha) and _valid_sha(rsha) and ksha != rsha:
            mismatches.append((fixture_id, ksha, rsha))
    if problems:
        for problem in problems:
            print("error: " + problem, file=sys.stderr)
        return 2
    if mismatches:
        print(
            "error: fixture sha256 mismatch — Kotlin and Rust did not read the "
            "same bytes:",
            file=sys.stderr,
        )
        for fixture_id, ksha, rsha in mismatches:
            print("  {:<10} kotlin={} rust={}".format(fixture_id, ksha, rsha),
                  file=sys.stderr)
        return 2

    table = build_table(kotlin, rust)
    document = "\n".join(
        (
            "# VT benchmark: Kotlin vs Rust",
            "",
            table,
            "",
            "输入夹具 sha256 一致（5/5）。1 MB = 1 MiB = 1048576 B；"
            "“提升”“分配下降”为 Rust 相对 Kotlin 的百分比，正数表示 Rust 更优。",
            "",
        )
    )

    compare_path = os.path.join(out_dir, "compare.md")
    with open(compare_path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(document)
    sys.stdout.write(document)
    return 0


if __name__ == "__main__":
    sys.exit(main())
