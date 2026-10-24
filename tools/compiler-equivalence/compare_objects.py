#!/usr/bin/env python3
"""Object-file equivalence judgement (PVM-05).

Replaces the AX-06 SHA-256 criterion. Two object files count as equivalent
when they are indistinguishable in every way that matters for the linked
program:

1. LINK      both objects link against the same fixture into an executable;
2. BEHAVIOR  both executables agree on exit code and stdout;
3. STRUCTURE `nm -C --defined-only` symbol sets and `readelf -r` relocation
             (type, target) multisets agree;
4. WHITELIST remaining section bytes agree, except .comment/.debug_* and
             path-like strings (build directories, __FILE__ remnants).

Verdicts, printed on the first line of stdout:

    EQUIVALENT     all four levels passed
    DIFFERENT      a real difference; the detail line names the first one
    INCONCLUSIVE   a required host tool or fixture is missing; never guess

Exit codes: 0 EQUIVALENT, 1 DIFFERENT, 2 INCONCLUSIVE, 3 usage error.

Host tools only (cc/c++, nm, readelf). Missing tools yield INCONCLUSIVE;
this script never talks to a device. Non-ELF objects (e.g. Windows
PE/COFF) are INCONCLUSIVE at the structure level because readelf cannot
parse them.
"""

from __future__ import annotations

import argparse
import re
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

EQUIVALENT = "EQUIVALENT"
DIFFERENT = "DIFFERENT"
INCONCLUSIVE = "INCONCLUSIVE"

EXIT_CODES = {EQUIVALENT: 0, DIFFERENT: 1, INCONCLUSIVE: 2}

# Sections whose content is allowed to differ freely.
WHITELIST_SECTION_PREFIXES = (".comment", ".debug")

# A path-like string: contains a path separator, no control characters, and
# only characters that appear in filesystem paths (Unicode-aware so non-ASCII
# build directories match too).
_PATH_RE = re.compile(r"^[\w\-. +@()（）\\/:]+$", re.UNICODE)

RUN_TIMEOUT_SECONDS = 30


class ToolMissing(Exception):
    """A required host tool is not available."""


class ToolError(Exception):
    """A host tool failed in a way that blocks judgement."""


def find_tool(*names: str) -> str | None:
    """Resolve the first available tool; patchable in tests."""
    for name in names:
        found = shutil.which(name)
        if found:
            return found
    return None


def _run(command: list[str], timeout: int | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        command, capture_output=True, timeout=timeout, check=False
    )


# ---------------------------------------------------------------------------
# Level 1: link, level 2: behavior


def compile_fixture(fixture_dir: Path, workdir: Path, cc: str, cxx: str) -> Path:
    """Build fixtures/<dir>/main.c (or main.cpp) into main.o."""
    source = fixture_dir / "main.c"
    compiler = cc
    if not source.is_file():
        source = fixture_dir / "main.cpp"
        compiler = cxx
    if not source.is_file():
        raise ToolError(f"fixture {fixture_dir} has neither main.c nor main.cpp")
    obj = workdir / "fixture_main.o"
    result = _run([compiler, "-c", str(source), "-o", str(obj)])
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise ToolError(f"fixture main failed to compile: {detail[:400]}")
    return obj


def link_object(
    compiler: str, obj: Path, main_o: Path, workdir: Path, tag: str
) -> tuple[Path | None, str]:
    """Link one candidate object with the fixture; (exe, "") or (None, error)."""
    exe = workdir / f"linked_{tag}"
    result = _run([compiler, str(obj), str(main_o), "-o", str(exe)])
    if result.returncode != 0:
        return None, result.stderr.decode("utf-8", "replace").strip()[:400]
    return exe, ""


def run_program(exe: Path) -> tuple[str, str]:
    """(exit_code, stdout) with TIMEOUT as the exit code marker."""
    try:
        result = _run([str(exe)], timeout=RUN_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        return "TIMEOUT", ""
    stdout = result.stdout.decode("utf-8", "replace")
    return str(result.returncode), stdout


# ---------------------------------------------------------------------------
# Level 3: structure


def defined_symbols(nm: str, obj: Path) -> list[tuple[str, str]]:
    """Sorted (type, demangled name) pairs of defined symbols."""
    result = _run([nm, "-C", "--defined-only", str(obj)])
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise ToolError(f"nm failed on {obj.name}: {detail[:200]}")
    symbols = []
    for line in result.stdout.decode("utf-8", "replace").splitlines():
        match = re.match(r"^\s*[0-9A-Fa-f]+\s+(\S)\s+(.+?)\s*$", line)
        if match:
            symbols.append((match.group(1), match.group(2)))
    return sorted(symbols)


def relocations(readelf: str, obj: Path) -> list[tuple[str, str]]:
    """Sorted (relocation type, target field) pairs; empty if none."""
    result = _run([readelf, "-r", str(obj)])
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise ToolError(f"readelf failed on {obj.name}: {detail[:200]}")
    relocs = []
    for line in result.stdout.decode("utf-8", "replace").splitlines():
        match = re.match(
            r"^[0-9A-Fa-f]+\s+[0-9A-Fa-f]+\s+(\S+)\s+(\S.*?)\s*$", line
        )
        if match:
            relocs.append((match.group(1), match.group(2)))
    return sorted(relocs)


# ---------------------------------------------------------------------------
# Level 4: section whitelist

_ELF64_HEADER = "<Q"  # e_shoff at 0x28
_ELF32_HEADER = "<I"  # e_shoff at 0x20


def read_elf_sections(path: Path) -> list[tuple[str, bytes]]:
    """(section name, raw bytes) for every section, via stdlib struct only."""
    data = path.read_bytes()
    if len(data) < 0x40 or data[:4] != b"\x7fELF":
        raise ToolError(f"{path.name} is not an ELF file; structure comparison "
                        "needs ELF objects (host binutils readelf is ELF-only)")
    endian = "<" if data[5] == 1 else ">"
    if data[4] == 2:  # ELF64
        shoff = struct.unpack_from(endian + "Q", data, 0x28)[0]
        shentsize, shnum, shstrndx = struct.unpack_from(endian + "HHH", data, 0x3A)
    elif data[4] == 1:  # ELF32
        shoff = struct.unpack_from(endian + "I", data, 0x20)[0]
        shentsize, shnum, shstrndx = struct.unpack_from(endian + "HHH", data, 0x2E)
    else:
        raise ToolError(f"{path.name}: unknown ELF class")
    if shoff == 0 or shnum == 0 or shentsize == 0:
        raise ToolError(f"{path.name}: no section header table (stripped?)")

    SHT_NOBITS = 8  # .bss and friends occupy no file bytes

    def section_entry(index: int) -> tuple[int, int, int, int]:
        base = shoff + index * shentsize
        if data[4] == 2:  # ELF64: name, type, flags(Q), addr(Q), offset(Q), size(Q)
            name, sh_type, _, _, offset, size = struct.unpack_from(
                endian + "IIQQQQ", data, base)
        else:  # ELF32: six 32-bit fields
            name, sh_type, _, _, offset, size = struct.unpack_from(
                endian + "IIIIII", data, base)
        return name, sh_type, offset, size

    strtab_name, _, strtab_off, _ = section_entry(shstrndx)
    strtab = data[strtab_off:]

    def cstring_at(table: bytes, offset: int) -> str:
        end = table.find(b"\x00", offset)
        return table[offset:end].decode("utf-8", "replace")

    sections: list[tuple[str, bytes]] = []
    for index in range(shnum):
        name_off, sh_type, offset, size = section_entry(index)
        if sh_type == SHT_NOBITS:
            content = b""
        else:
            content = data[offset:offset + size]
        sections.append((cstring_at(strtab, name_off), content))
    return sections


def _token_is_path(token: bytes) -> bool:
    try:
        text = token.decode("utf-8")
    except UnicodeDecodeError:
        return False
    return bool(_PATH_RE.match(text)) and ("/" in text or "\\" in text)


def _all_tokens_are_paths(chunk: bytes) -> bool:
    """True when every NUL-separated token in chunk looks like a path string."""
    for token in chunk.split(b"\x00"):
        if token and not _token_is_path(token):
            return False
    return True


def _token_span(data: bytes, start: int, end: int) -> tuple[int, int]:
    """Expand [start, end) to full NUL-token boundaries inside data."""
    lo = data.rfind(b"\x00", 0, start)
    lo = lo + 1 if lo != -1 else 0
    hi = data.find(b"\x00", end)
    hi = hi if hi != -1 else len(data)
    return lo, hi


def sections_equivalent(a_sections: list[tuple[str, bytes]],
                        b_sections: list[tuple[str, bytes]]) -> tuple[bool, str]:
    """Compare section tables with the .comment/.debug_*/path whitelist."""
    kept_a = [(n, c) for n, c in a_sections if not n.startswith(WHITELIST_SECTION_PREFIXES)]
    kept_b = [(n, c) for n, c in b_sections if not n.startswith(WHITELIST_SECTION_PREFIXES)]
    names_a = sorted(n for n, _ in kept_a)
    names_b = sorted(n for n, _ in kept_b)
    if names_a != names_b:
        only_a = sorted(set(names_a) - set(names_b))
        only_b = sorted(set(names_b) - set(names_a))
        return False, f"section sets differ (only in a: {only_a}, only in b: {only_b})"

    contents_a = dict(kept_a)
    for name, content_b in kept_b:
        content_a = contents_a[name]
        if content_a == content_b:
            continue
        prefix = 0
        limit = min(len(content_a), len(content_b))
        while prefix < limit and content_a[prefix] == content_b[prefix]:
            prefix += 1
        suffix = 0
        while suffix < limit - prefix and \
                content_a[len(content_a) - 1 - suffix] == content_b[len(content_b) - 1 - suffix]:
            suffix += 1
        # Expand both differing middles to whole NUL tokens: a trimmed
        # one-byte fragment (e.g. "build-a" vs "build-b") would never look
        # path-like on its own.
        lo_a, hi_a = _token_span(content_a, prefix, len(content_a) - suffix)
        lo_b, hi_b = _token_span(content_b, prefix, len(content_b) - suffix)
        mid_a = content_a[lo_a:hi_a]
        mid_b = content_b[lo_b:hi_b]
        if _all_tokens_are_paths(mid_a) and _all_tokens_are_paths(mid_b):
            continue
        return False, (f"section {name} differs beyond the whitelist at offset "
                       f"{prefix}: {mid_a[:64]!r} vs {mid_b[:64]!r}")
    return True, ""


# ---------------------------------------------------------------------------
# The four-level judgement


def compare_objects(
    a_path: Path,
    b_path: Path,
    fixtures_dir: Path,
    cc: str | None = None,
    cxx: str | None = None,
    nm: str | None = None,
    readelf: str | None = None,
) -> tuple[str, str]:
    """Run the four levels; returns (verdict, first-difference detail)."""
    for label, path in (("a", a_path), ("b", b_path)):
        if not path.is_file():
            return INCONCLUSIVE, f"object {label} does not exist: {path}"
    if not fixtures_dir.is_dir():
        return INCONCLUSIVE, f"fixtures directory missing: {fixtures_dir}"

    cc = cc or find_tool("cc", "gcc", "clang")
    if cc is None:
        return INCONCLUSIVE, "no C compiler driver found (cc/gcc/clang); cannot link"

    with tempfile.TemporaryDirectory(prefix="pvm05-compare-") as tmp:
        workdir = Path(tmp)
        cxx = cxx or find_tool("c++", "g++", "clang++")

        # Level 1: link.
        try:
            main_o = compile_fixture(fixtures_dir, workdir, cc, cxx or cc)
        except ToolError as error:
            return INCONCLUSIVE, str(error)
        exe_a, error_a = link_object(cc, a_path, main_o, workdir, "a")
        exe_b, error_b = link_object(cc, b_path, main_o, workdir, "b")
        if exe_a is None and exe_b is None:
            return INCONCLUSIVE, (f"neither object links against the fixture; "
                                  f"a: {error_a} | b: {error_b}")
        if exe_a is None:
            return DIFFERENT, f"a fails to link while b succeeds: {error_a}"
        if exe_b is None:
            return DIFFERENT, f"b fails to link while a succeeds: {error_b}"

        # Level 2: behavior.
        code_a, out_a = run_program(exe_a)
        code_b, out_b = run_program(exe_b)
        if code_a == "TIMEOUT" and code_b == "TIMEOUT":
            return INCONCLUSIVE, "both executables hang; behavior unobservable"
        if code_a != code_b:
            return DIFFERENT, f"exit code differs: a={code_a} b={code_b}"
        if out_a != out_b:
            return DIFFERENT, "stdout differs: first mismatch at byte " + str(
                next((i for i, (x, y) in enumerate(zip(out_a, out_b)) if x != y),
                     min(len(out_a), len(out_b))))
        if code_a == "TIMEOUT":
            return DIFFERENT, "one executable hangs while the other exits"

        # Level 3: structure.
        nm = nm or find_tool("nm")
        readelf = readelf or find_tool("readelf")
        if nm is None or readelf is None:
            return INCONCLUSIVE, ("structure tools missing (nm/readelf); cannot "
                                  "judge beyond behavior")
        try:
            symbols_a = defined_symbols(nm, a_path)
            symbols_b = defined_symbols(nm, b_path)
            relocs_a = relocations(readelf, a_path)
            relocs_b = relocations(readelf, b_path)
        except ToolError as error:
            return INCONCLUSIVE, str(error)
        if symbols_a != symbols_b:
            return DIFFERENT, f"defined symbol sets differ: {symbols_a!r} vs {symbols_b!r}"
        if relocs_a != relocs_b:
            return DIFFERENT, f"relocation (type, target) sets differ: {relocs_a!r} vs {relocs_b!r}"

        # Level 4: whitelist.
        try:
            sections_a = read_elf_sections(a_path)
            sections_b = read_elf_sections(b_path)
        except ToolError as error:
            return INCONCLUSIVE, str(error)
        ok, detail = sections_equivalent(sections_a, sections_b)
        if not ok:
            return DIFFERENT, detail

    return EQUIVALENT, "all four levels agree"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Four-level object-file equivalence judgement (PVM-05)")
    parser.add_argument("--a", required=True, help="first candidate .o")
    parser.add_argument("--b", required=True, help="second candidate .o")
    parser.add_argument("--fixtures", required=True,
                        help="fixture directory with main.c (or main.cpp)")
    parser.add_argument("--cc", default=None, help="override the C linker driver")
    parser.add_argument("--cxx", default=None, help="override the C++ linker driver")
    parser.add_argument("--nm", default=None, help="override nm")
    parser.add_argument("--readelf", default=None, help="override readelf")
    args = parser.parse_args(argv)

    verdict, detail = compare_objects(
        Path(args.a), Path(args.b), Path(args.fixtures),
        cc=args.cc, cxx=args.cxx, nm=args.nm, readelf=args.readelf,
    )
    print(verdict)
    if detail:
        print(detail)
    return EXIT_CODES[verdict]


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
