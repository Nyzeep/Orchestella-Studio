#!/usr/bin/env python3
"""Deterministic fixture generator for the VT benchmark.

Five byte-exact fixtures exercise the parser surfaces that matter for the
Kotlin/Rust comparison: printable ASCII, SGR colour, cursor movement, wide and
combining characters, and OSC control strings.

Properties:
  * python3 standard library only;
  * no wall-clock time, no os.urandom, no hash randomisation, fixed LCG seed;
  * repeated runs produce byte-identical files and therefore identical sha256.

1 MB below means 1 MiB = 1048576 bytes.  Every file is exactly the size listed
in SPECS; only the final record may be truncated (the `wide` fixture is padded
with ASCII 'x' instead, because it must stay valid UTF-8).

Usage:
  python3 fixtures/gen.py                 # write into fixtures/generated/
  python3 fixtures/gen.py --out DIR       # write into DIR/
  python3 fixtures/gen.py --verify        # check sha256 in DIR/manifest.json
"""

import argparse
import hashlib
import json
import os
import sys
from itertools import count

GENERATOR_VERSION = 1
MIB = 1048576
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

# (id, exact size, recipe).  Keep the order stable: it is part of the manifest.
SPECS = (
    (
        "plain",
        10 * MIB,
        "79 printable ASCII chars + LF; fixed sentence, incrementing counter",
    ),
    (
        "ansi_color",
        10 * MIB,
        "SGR 8 colours, bright block every 8 lines, 256-colour line every 100",
    ),
    (
        "cursor",
        5 * MIB,
        "alternating text/H/2J/row-col H/K/10;20H/A/B; seeded LCG positions",
    ),
    (
        "wide",
        5 * MIB,
        "UTF-8 CJK + emoji U+1F600..U+1F64F + e+U+0301 + fullwidth; x-padded",
    ),
    (
        "osc",
        2 * MIB,
        "OSC 0 title, OSC 8 hyperlink, OSC 10/11 queries; BEL and ST terminators",
    ),
)

PLAIN_SENTENCE = b"orchestella workspace build log line with deterministic ascii payload ok"
PLAIN_WIDTH = 79
ANSI_TEXT_WIDTH = 60
ANSI_FILLER = b"abcdefghijklmnopqrstuvwxyz0123456789" * 2
WIDE_EMOJI_FIRST = 0x1F600
WIDE_EMOJI_COUNT = 0x50
WIDE_FULLWIDTH_FIRST = 0xFF01
WIDE_FULLWIDTH_COUNT = 0xFF5E - 0xFF01 + 1
CURSOR_LCG_SEED = 0x13579BDF


def _fill(target, records, overflow="truncate"):
    """Concatenate `records` until the buffer is exactly `target` bytes.

    `overflow="truncate"` cuts the record that would cross the boundary, which
    SPECS allows for every fixture but `wide`.  `overflow="pad_ascii"` refuses
    to cut a record and pads the remaining bytes with b"x", keeping UTF-8 valid.
    """
    buf = bytearray()
    for record in records:
        room = target - len(buf)
        if room <= 0:
            break
        if len(record) <= room:
            buf += record
        elif overflow == "truncate":
            buf += record[:room]
            break
        else:
            buf += b"x" * room
            break
    if len(buf) != target:
        raise AssertionError("fixture size mismatch: %d != %d" % (len(buf), target))
    return buf


def gen_plain(target):
    def records():
        for n in count(1):
            line = b"%06d " % n + PLAIN_SENTENCE
            yield line[:PLAIN_WIDTH].ljust(PLAIN_WIDTH, b" ") + b"\n"

    return _fill(target, records())


def _ansi_text(index):
    body = b"tty color sample %07d " % index
    return (body + ANSI_FILLER)[:ANSI_TEXT_WIDTH]


def gen_ansi_color(target):
    def records():
        i = 0
        while True:
            bright = (i // 8) % 2 == 1
            yield (
                b"\x1b[%d%dm" % (9 if bright else 3, i % 8)
                + _ansi_text(i)
                + b"\x1b[0m\n"
            )
            i += 1
            if i % 100 == 0:
                yield (
                    b"\x1b[1;4;38;5;%dm" % ((i // 100) % 256)
                    + _ansi_text(i)
                    + b"\x1b[0m\n"
                )

    return _fill(target, records())


def gen_cursor(target):
    state = CURSOR_LCG_SEED

    def next_state():
        nonlocal state
        state = (1103515245 * state + 12345) & 0x7FFFFFFF
        return state

    def records():
        n = 0
        while True:
            yield b"cursor probe %06d\n" % n
            yield b"\x1b[H"
            yield b"\x1b[2J"
            value = next_state()
            yield b"\x1b[%d;%dH" % (1 + value % 24, 1 + (value >> 8) % 80)
            yield b"\x1b[K"
            yield b"\x1b[10;20H"
            yield b"\x1b[A"
            yield b"\x1b[B"
            n += 1

    return _fill(target, records())


def gen_wide(target):
    def records():
        for n in count(1):
            line = "".join(
                (
                    "终端渲染压力测试 中文宽字符 ",
                    "%06d " % n,
                    chr(WIDE_EMOJI_FIRST + n % WIDE_EMOJI_COUNT),
                    " e\u0301 ",
                    chr(WIDE_FULLWIDTH_FIRST + n % WIDE_FULLWIDTH_COUNT),
                    "\n",
                )
            )
            yield line.encode("utf-8")

    return _fill(target, records(), overflow="pad_ascii")


def gen_osc(target):
    def records():
        for n in count(1):
            num = b"%d" % n
            yield (
                b"\x1b]0;orchestella title " + num + b"\x07"
                + b"osc probe text line " + num + b"\n"
                + b"\x1b]8;;https://example.invalid/path/" + num + b"\x1b\\"
                + b"click"
                + b"\x1b]8;;\x1b\\"
                + b"\x1b]10;?\x07"
                + b"\x1b]11;?\x07"
                + b"\n"
            )

    return _fill(target, records())


BUILDERS = {
    "plain": gen_plain,
    "ansi_color": gen_ansi_color,
    "cursor": gen_cursor,
    "wide": gen_wide,
    "osc": gen_osc,
}


def _sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _write(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def generate(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    fixtures = []
    for fixture_id, size, recipe in SPECS:
        data = BUILDERS[fixture_id](size)
        digest = hashlib.sha256(data).hexdigest()
        with open(os.path.join(out_dir, fixture_id + ".bin"), "wb") as handle:
            handle.write(data)
        fixtures.append(
            {
                "id": fixture_id,
                "bytes": len(data),
                "sha256": digest,
                "recipe": recipe,
            }
        )
        print("%-10s %9d  %s" % (fixture_id, len(data), digest))

    manifest = {
        "generator_version": GENERATOR_VERSION,
        "mb_definition": "MiB",
        "fixtures": fixtures,
    }
    manifest_path = os.path.join(out_dir, "manifest.json")
    _write(manifest_path, json.dumps(manifest, indent=2, sort_keys=True) + "\n")

    checksums_path = os.path.join(out_dir, "SHA256SUMS")
    _write(
        checksums_path,
        "".join("%s  %s.bin\n" % (item["sha256"], item["id"]) for item in fixtures),
    )

    print("manifest  %s" % manifest_path)
    print("sums      %s" % checksums_path)
    return 0


def verify(out_dir):
    manifest_path = os.path.join(out_dir, "manifest.json")
    if not os.path.isfile(manifest_path):
        print("error: %s not found" % manifest_path, file=sys.stderr)
        return 2

    with open(manifest_path, "r", encoding="utf-8") as handle:
        manifest = json.load(handle)
    if manifest.get("generator_version") != GENERATOR_VERSION:
        print(
            "error: generator_version %r != %d"
            % (manifest.get("generator_version"), GENERATOR_VERSION),
            file=sys.stderr,
        )
        return 1

    entries = manifest.get("fixtures") or []
    expected_ids = [spec[0] for spec in SPECS]
    seen_ids = [entry.get("id") for entry in entries]
    if seen_ids != expected_ids:
        print(
            "error: manifest ids %r != expected %r" % (seen_ids, expected_ids),
            file=sys.stderr,
        )
        return 1

    failed = False
    for entry in entries:
        fixture_id = entry["id"]
        path = os.path.join(out_dir, fixture_id + ".bin")
        if not os.path.isfile(path):
            print("%-10s MISSING  %s" % (fixture_id, path))
            failed = True
            continue
        actual = _sha256_file(path)
        expected = entry["sha256"]
        if actual != expected:
            print("%-10s MISMATCH %s (expected %s)" % (fixture_id, actual, expected))
            failed = True
            continue
        size = os.path.getsize(path)
        if size != entry["bytes"]:
            print("%-10s SIZE %d != %d" % (fixture_id, size, entry["bytes"]))
            failed = True
            continue
        print("%-10s OK  %9d  %s" % (fixture_id, size, actual))

    if failed:
        print("verify: FAILED", file=sys.stderr)
        return 1
    print("verify: OK (5/5)")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Generate or verify deterministic VT benchmark fixtures."
    )
    parser.add_argument(
        "--out",
        default=os.path.join(SCRIPT_DIR, "generated"),
        help="output directory (default: %(default)s)",
    )
    parser.add_argument(
        "--verify",
        action="store_true",
        help="only re-check sha256 recorded in manifest.json; do not regenerate",
    )
    args = parser.parse_args(argv)
    out_dir = os.path.abspath(args.out)
    if args.verify:
        return verify(out_dir)
    return generate(out_dir)


if __name__ == "__main__":
    sys.exit(main())
