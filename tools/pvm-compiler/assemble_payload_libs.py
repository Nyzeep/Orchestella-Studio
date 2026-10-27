#!/usr/bin/env python3
"""PVM-08: assemble the compiler-side shared-library staging directory.

Gradle consumes two directories whose arm64-v8a/*.so contents are merged into
the APK's lib/arm64-v8a/: orchestella.fastlane.payloadLibDir (payload side,
built by tools/fastlane-build/build-payload.sh) and
orchestella.pvmCompiler.libDir (compiler side, staged by this script). The
two must not overlap: the compiler staging directory therefore contains only

  * real mode: libLLVM.so / libclang-cpp.so copied from the PVM-04 build
    output, plus libc++_shared.so when present;
  * placeholder mode (--placeholder): a few-KB same-ABI stub pair built with
    the NDK so the packaging/alignment/size pipeline can be rehearsed before
    the real PVM-04 artifacts exist. Stubs carry a PVM04_PLACEHOLDER symbol
    and must never be mistaken for libclang.

Every staged .so is checked for EM_AARCH64 and >= 16 KB PT_LOAD alignment
(pure-Python ELF parse, no readelf dependency). --payload-dir is validation
only: the payload must already exist before the APK build makes sense, but it
is never copied or modified.

PVM-09 adds the compiler-service manifest: generation writes
<out>/assets/PVM-COMPILER-MANIFEST (schema 1) describing the staged libraries so
the app can report what it actually ships, and --verify-manifest re-checks a
staging directory against that file as a build-time gate. The manifest is a
build product and is never committed.

PVM-12 adds the engine attestation to that manifest. The payload's compile
engine is a CMake cache variable that defaults to FAKE, and a FAKE build answers
every distcc request with a fixed 608-byte x86-64 stub object while STAT, DOTO,
the exit code and the object landing on disk all look successful. --engine
records the engine in the manifest, and when --payload-dir is given the
declaration is cross-checked against the payload's DT_NEEDED list: only the
CLANG payload links libclang-cpp.so, so a mislabelled build fails here instead
of on a device three hours later.

Exit codes: 0 ok; 1 usage/validation failure; 2 ELF/alignment failure.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

PAGE_ALIGN = 16 * 1024
EM_AARCH64 = 183
REQUIRED_COMPILER_SOS = ("libLLVM.so", "libclang-cpp.so")
PLACEHOLDER_SYMBOL = "PVM04_PLACEHOLDER"

# PVM-12 engine attestation. The names match the CMake values
# (FL_PVM_COMPILER_ENGINE=FAKE|CLANG), lower-cased for on-disk use.
ENGINE_FAKE = "fake"
ENGINE_CLANG = "clang"
ENGINES = (ENGINE_FAKE, ENGINE_CLANG)
# The CLANG payload links this; the FAKE payload links neither it nor LLVM.
ENGINE_LINKED_LIBRARY = "libclang-cpp.so"

# PVM-09 manifest contract (schema 1). Field names are on-disk compatibility:
# the Java reader in runtime/fastlane-capability parses exactly these keys.
MANIFEST_RELATIVE = Path("assets") / "PVM-COMPILER-MANIFEST"
MANIFEST_SCHEMA = 1
VCPU_TOPOLOGY_ONE_CPU = "one_cpu"
DEFAULT_PAYLOAD_BINARY = "libpvm_compiler_payload.so"
DEFAULT_MEMORY_MIB = 2048
MEMORY_MIB_MIN = 1
MEMORY_MIB_MAX = 16384
PAYLOAD_BINARY_PATTERN = re.compile(r"[A-Za-z0-9._-]+\.so")
SHA256_HEX_PATTERN = re.compile(r"[0-9a-f]{64}")
# The Fast Lane payload is the APK-side entry point the compiler libraries sit
# next to, so a payload directory must always carry it, whatever payload binary
# the manifest names for the compiler lane.
REQUIRED_PAYLOAD_SO = "libfastlane_payload.so"

STUB_C = """\
// PVM-08 placeholder shared object: exercises APK packaging, 16 KB alignment
// and size accounting only. It is not LLVM/clang and exports no compiler API.
__attribute__((visibility("default")))
int {entry}(void) {{ return 0; }}
"""


def fail(message: str) -> "SystemExit":
    print("FAIL " + message, file=sys.stderr)
    return SystemExit(1)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def manifest_fail(detail: str) -> "SystemExit":
    print("FAIL manifest: " + detail, file=sys.stderr)
    return SystemExit(1)


def is_payload_binary_name(name: str) -> bool:
    return PAYLOAD_BINARY_PATTERN.fullmatch(name) is not None


def staged_shared_objects(abi_dir: Path) -> list[Path]:
    """Every .so in the ABI directory, ordered by name (manifest order is fixed)."""
    return sorted(
        (path for path in abi_dir.iterdir() if path.is_file() and path.name.endswith(".so")),
        key=lambda path: path.name,
    )


def build_manifest(
    abi_dir: Path,
    compiler_version: str,
    target_triple: str,
    engine: str,
    payload_binary: str = DEFAULT_PAYLOAD_BINARY,
    memory_mib: int = DEFAULT_MEMORY_MIB,
    vcpu_topology: str = VCPU_TOPOLOGY_ONE_CPU,
) -> dict:
    """Build the schema-1 manifest for the .so files currently in <abi_dir>.

    Split out from the CLI so the structure can be checked without staging a
    whole APK; the caller validates the scalar arguments before calling this.
    """
    libs = [
        {"name": path.name, "size": path.stat().st_size, "sha256": sha256_file(path)}
        for path in staged_shared_objects(abi_dir)
    ]
    return {
        "schema": MANIFEST_SCHEMA,
        "engine": engine,
        "compiler_version": compiler_version,
        "target_triple": target_triple,
        "vcpu_topology": vcpu_topology,
        "memory_mib": memory_mib,
        "payload_binary": payload_binary,
        "libs": libs,
    }


def write_manifest(manifest: dict, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def manifest_label(path: Path, staging: Path) -> str:
    """Staging-relative label for logs: build output must not print machine paths."""
    try:
        return path.resolve().relative_to(staging.resolve()).as_posix()
    except ValueError:
        return path.name


def manifest_text(manifest: dict, key: str) -> str:
    value = manifest.get(key)
    if not isinstance(value, str) or not value.strip():
        raise manifest_fail(f"field '{key}' is missing or empty")
    return value.strip()


def verify_manifest(staging: Path) -> dict:
    """Check a staging directory against its manifest; raise SystemExit(1) on any defect."""
    manifest_path = staging / MANIFEST_RELATIVE
    if not manifest_path.is_file():
        raise manifest_fail("assets/PVM-COMPILER-MANIFEST is missing (run the generator first)")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise manifest_fail("assets/PVM-COMPILER-MANIFEST is not valid UTF-8 JSON")
    if not isinstance(manifest, dict):
        raise manifest_fail("assets/PVM-COMPILER-MANIFEST must be a JSON object")

    schema = manifest.get("schema")
    if isinstance(schema, bool) or not isinstance(schema, int) or schema != MANIFEST_SCHEMA:
        raise manifest_fail(f"schema must be the integer {MANIFEST_SCHEMA}")

    compiler_version = manifest_text(manifest, "compiler_version")
    target_triple = manifest_text(manifest, "target_triple")
    payload_binary = manifest_text(manifest, "payload_binary")
    topology = manifest_text(manifest, "vcpu_topology")
    engine = manifest_text(manifest, "engine")

    if engine not in ENGINES:
        raise manifest_fail("engine must be one of " + ", ".join(ENGINES))
    if not is_payload_binary_name(payload_binary):
        raise manifest_fail("payload_binary must be a bare file name matching [A-Za-z0-9._-]+.so")
    if topology != VCPU_TOPOLOGY_ONE_CPU:
        raise manifest_fail(f"vcpu_topology must be {VCPU_TOPOLOGY_ONE_CPU}")

    memory_mib = manifest.get("memory_mib")
    if isinstance(memory_mib, bool) or not isinstance(memory_mib, int):
        raise manifest_fail("memory_mib must be an integer")
    if not MEMORY_MIB_MIN <= memory_mib <= MEMORY_MIB_MAX:
        raise manifest_fail(f"memory_mib must be in {MEMORY_MIB_MIN}..{MEMORY_MIB_MAX}")

    abi_dir = staging / "arm64-v8a"
    if not abi_dir.is_dir():
        raise manifest_fail("arm64-v8a/ is missing next to the manifest")

    declared = manifest.get("libs")
    if not isinstance(declared, list) or not declared:
        raise manifest_fail("field 'libs' must be a non-empty array")

    seen: list[str] = []
    for entry in declared:
        if not isinstance(entry, dict):
            raise manifest_fail("every libs entry must be a JSON object")
        name = entry.get("name")
        if not isinstance(name, str) or not name.endswith(".so") or Path(name).name != name:
            raise manifest_fail("libs entry 'name' must be a bare .so file name")
        if name in seen:
            raise manifest_fail(f"{name}: duplicate libs entry")
        size = entry.get("size")
        if isinstance(size, bool) or not isinstance(size, int) or size <= 0:
            raise manifest_fail(f"{name}: 'size' must be a positive integer")
        digest = entry.get("sha256")
        if not isinstance(digest, str) or SHA256_HEX_PATTERN.fullmatch(digest) is None:
            raise manifest_fail(f"{name}: 'sha256' must be 64 lowercase hex characters")
        staged = abi_dir / name
        if not staged.is_file():
            raise manifest_fail(f"{name}: declared in manifest but missing from arm64-v8a/")
        actual_size = staged.stat().st_size
        if actual_size != size:
            raise manifest_fail(f"{name}: size mismatch (manifest {size}, file {actual_size})")
        if sha256_file(staged) != digest:
            raise manifest_fail(f"{name}: sha256 mismatch against the staged file")
        seen.append(name)

    undeclared = [path.name for path in staged_shared_objects(abi_dir) if path.name not in seen]
    if undeclared:
        raise manifest_fail("arm64-v8a/ contains .so files the manifest does not declare: " + ", ".join(undeclared))

    return {
        "schema": MANIFEST_SCHEMA,
        "engine": engine,
        "compiler_version": compiler_version,
        "target_triple": target_triple,
        "payload_binary": payload_binary,
        "vcpu_topology": topology,
        "memory_mib": memory_mib,
        "libs": len(seen),
    }


def elf_machine_and_load_aligns(path: Path) -> tuple[int, list[int]]:
    """Return (e_machine, [p_align of every PT_LOAD]) for a 64-bit little-endian ELF."""
    data = path.read_bytes()
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    if data[4] != 2 or data[5] != 1:
        raise ValueError("not a 64-bit little-endian ELF")
    (_e_type, e_machine) = struct.unpack_from("<HH", data, 16)
    e_phoff = struct.unpack_from("<Q", data, 32)[0]
    e_phentsize = struct.unpack_from("<H", data, 54)[0]
    e_phnum = struct.unpack_from("<H", data, 56)[0]
    aligns: list[int] = []
    if e_phoff and e_phnum:
        for index in range(e_phnum):
            base = e_phoff + index * e_phentsize
            if base + 56 > len(data):
                break
            p_type, _p_flags = struct.unpack_from("<II", data, base)
            if p_type != 1:  # PT_LOAD
                continue
            p_align = struct.unpack_from("<Q", data, base + 48)[0]
            aligns.append(p_align)
    return e_machine, aligns


def elf_load_segments(data: bytes) -> list[tuple[int, int, int]]:
    """(p_offset, p_vaddr, p_filesz) for every PT_LOAD of a 64-bit LE ELF."""
    e_phoff = struct.unpack_from("<Q", data, 32)[0]
    e_phentsize = struct.unpack_from("<H", data, 54)[0]
    e_phnum = struct.unpack_from("<H", data, 56)[0]
    segments: list[tuple[int, int, int]] = []
    for index in range(e_phnum):
        base = e_phoff + index * e_phentsize
        if base + 56 > len(data):
            break
        p_type = struct.unpack_from("<I", data, base)[0]
        if p_type != 1:  # PT_LOAD
            continue
        p_offset = struct.unpack_from("<Q", data, base + 8)[0]
        p_vaddr = struct.unpack_from("<Q", data, base + 16)[0]
        p_filesz = struct.unpack_from("<Q", data, base + 32)[0]
        segments.append((p_offset, p_vaddr, p_filesz))
    return segments


def file_offset_of_vaddr(segments: list[tuple[int, int, int]], vaddr: int) -> int | None:
    for p_offset, p_vaddr, p_filesz in segments:
        if p_vaddr <= vaddr < p_vaddr + p_filesz:
            return p_offset + (vaddr - p_vaddr)
    return None


def elf_needed_libraries(path: Path) -> list[str]:
    """DT_NEEDED sonames recorded in PT_DYNAMIC ([] when there is no such segment).

    Raises ValueError for anything that is not a 64-bit little-endian ELF, so a
    caller can tell "links nothing" apart from "not a shared object at all".
    """
    data = path.read_bytes()
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    if data[4] != 2 or data[5] != 1:
        raise ValueError("not a 64-bit little-endian ELF")
    e_phoff = struct.unpack_from("<Q", data, 32)[0]
    e_phentsize = struct.unpack_from("<H", data, 54)[0]
    e_phnum = struct.unpack_from("<H", data, 56)[0]
    dynamic: tuple[int, int] | None = None
    for index in range(e_phnum):
        base = e_phoff + index * e_phentsize
        if base + 56 > len(data):
            break
        p_type = struct.unpack_from("<I", data, base)[0]
        if p_type == 2:  # PT_DYNAMIC
            dynamic = struct.unpack_from("<QQ", data, base + 8)
    if dynamic is None:
        return []
    offset, size = dynamic
    tags: list[tuple[int, int]] = []
    for index in range(size // 16):
        entry = offset + index * 16
        if entry + 16 > len(data):
            break
        tag, value = struct.unpack_from("<QQ", data, entry)
        if tag == 0:  # DT_NULL
            break
        tags.append((tag, value))
    if not any(tag == 1 for tag, _value in tags):  # no DT_NEEDED at all
        return []
    string_table = next((value for tag, value in tags if tag == 5), None)  # DT_STRTAB
    if string_table is None:
        return []
    strtab_offset = file_offset_of_vaddr(elf_load_segments(data), string_table)
    if strtab_offset is None:
        raise ValueError("DT_STRTAB is outside every PT_LOAD segment")
    names: list[str] = []
    for tag, value in tags:
        if tag != 1:  # DT_NEEDED
            continue
        start = strtab_offset + value
        if start >= len(data):
            raise ValueError("DT_NEEDED string offset is outside the file")
        end = data.find(b"\0", start)
        if end < 0:
            raise ValueError("unterminated DT_NEEDED string")
        names.append(data[start:end].decode("utf-8", "replace"))
    return names


def check_payload_engine(payload: Path, engine: str) -> None:
    """Cross-check a declared engine against the payload's DT_NEEDED list.

    The flag alone is a claim; this is the only host-side evidence with teeth.
    A payload that cannot be read as an ELF is accepted for engine=fake (there
    is nothing to contradict) but refused for engine=clang, because "clang" is
    a claim that must be provable.
    """
    try:
        needed = elf_needed_libraries(payload)
    except ValueError as failure:
        if engine == ENGINE_CLANG:
            raise fail(f"{payload.name}: engine=clang declared but the payload is not a readable ELF ({failure})")
        return
    links_clang = ENGINE_LINKED_LIBRARY in needed
    if engine == ENGINE_CLANG and not links_clang:
        raise fail(
            f"{payload.name}: engine=clang declared but the payload does not link "
            f"{ENGINE_LINKED_LIBRARY} (a FAKE build does not; rebuild with "
            "-DFL_PVM_COMPILER_ENGINE=CLANG)")
    if engine == ENGINE_FAKE and links_clang:
        raise fail(
            f"{payload.name}: engine=fake declared but the payload links "
            f"{ENGINE_LINKED_LIBRARY} (it was built with -DFL_PVM_COMPILER_ENGINE=CLANG)")


def verify_so(path: Path) -> dict:
    try:
        machine, aligns = elf_machine_and_load_aligns(path)
    except ValueError as failure:
        print(f"FAIL {path.name}: {failure}", file=sys.stderr)
        raise SystemExit(2)
    if machine != EM_AARCH64:
        print(f"FAIL {path.name}: e_machine={machine}, expected EM_AARCH64 (183)", file=sys.stderr)
        raise SystemExit(2)
    bad = [hex(a) for a in aligns if a < PAGE_ALIGN]
    if bad:
        print(
            f"FAIL {path.name}: PT_LOAD p_align below 16 KB: {', '.join(bad)}. "
            "Rebuild with -Wl,-z,max-page-size=16384.",
            file=sys.stderr,
        )
        raise SystemExit(2)
    return {"name": path.name, "bytes": path.stat().st_size, "sha256": sha256_file(path), "load_aligns": [hex(a) for a in aligns]}


def find_ndk_clang(ndk: Path | None) -> Path:
    if ndk is None:
        raise fail("--placeholder requires --ndk (or set ANDROID_NDK_HOME)")
    for host in ("windows-x86_64", "linux-x86_64"):
        for name in ("clang.exe", "clang"):
            candidate = ndk / "toolchains" / "llvm" / "prebuilt" / host / "bin" / name
            if candidate.is_file():
                return candidate
    raise fail(f"clang not found under {ndk}")


def run_clang(cmd: list[str]) -> None:
    proc = subprocess.run(cmd, check=False, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        raise fail(f"stub compile failed (rc={proc.returncode}): {' '.join(cmd)}\n"
                   f"{proc.stderr.strip()[-800:]}")


def build_placeholders(out_dir: Path, ndk: Path | None, api: int) -> list[Path]:
    clang = find_ndk_clang(ndk)
    produced: list[Path] = []
    with tempfile.TemporaryDirectory(prefix="pvm08-placeholder-") as temporary:
        work = Path(temporary)
        # libLLVM.so: plain stub, forced soname.
        llvm_c = work / "llvm_stub.c"
        llvm_c.write_text(STUB_C.format(entry="pvm_llvm_stub_entry"), encoding="ascii")
        llvm_so = out_dir / "libLLVM.so"
        run_clang([
            str(clang), f"--target=aarch64-linux-android{api}",
            "-shared", "-fPIC", "-Oz", "-nostdlib",
            "-Wl,-soname,libLLVM.so", "-Wl,-z,max-page-size=16384",
            "-o", str(llvm_so), str(llvm_c),
        ])
        produced.append(llvm_so)
        # libclang-cpp.so: stub that references the libLLVM stub so the link
        # records a NEEDED libLLVM.so dependency (soname resolution rehearsal).
        cpp_c = work / "clang_stub.c"
        cpp_c.write_text(
            STUB_C.format(entry="pvm_clang_stub_entry")
            + "\nextern int pvm_llvm_stub_entry(void);\n"
            + "int pvm_clang_stub_calls_llvm(void) { return pvm_llvm_stub_entry(); }\n",
            encoding="ascii",
        )
        cpp_so = out_dir / "libclang-cpp.so"
        run_clang([
            str(clang), f"--target=aarch64-linux-android{api}",
            "-shared", "-fPIC", "-Oz", "-nostdlib",
            "-Wl,-soname,libclang-cpp.so", "-Wl,-z,max-page-size=16384",
            "-Wl,--no-as-needed", str(llvm_so),
            "-o", str(cpp_so), str(cpp_c),
        ])
        produced.append(cpp_so)
    return produced


def verify_only(arguments: argparse.Namespace) -> int:
    """--verify-manifest branch: a pure gate, nothing is staged or written."""
    conflicts = [
        flag for flag, enabled in (
            ("--compiler-dir", arguments.compiler_dir is not None),
            ("--placeholder", arguments.placeholder),
            ("--payload-dir", arguments.payload_dir is not None),
            ("--manifest-out", arguments.manifest_out is not None),
            ("--engine", arguments.engine is not None),
        ) if enabled
    ]
    if conflicts:
        raise fail("--verify-manifest is mutually exclusive with generation arguments: " + ", ".join(conflicts))
    summary = verify_manifest(arguments.verify_manifest)
    print("PVM_COMPILER_MANIFEST_OK " + json.dumps(summary, sort_keys=True))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    here = Path(__file__).resolve().parent
    parser.add_argument("--payload-dir", type=Path, default=None,
                        help="directory containing arm64-v8a/libfastlane_payload.so (validated, not copied)")
    parser.add_argument("--compiler-dir", type=Path, default=None,
                        help="directory containing arm64-v8a/libLLVM.so and libclang-cpp.so (tools/llvm-android out); "
                             "mutually exclusive with --placeholder")
    parser.add_argument("--placeholder", action="store_true",
                        help="generate stub compiler .so files instead of copying a real build")
    parser.add_argument("--ndk", type=Path, default=None,
                        help="NDK root for --placeholder stub compilation and the real libc++_shared.so copy")
    parser.add_argument("--api", type=int, default=26, help="Android API level for placeholder stubs (default 26)")
    parser.add_argument("--out", type=Path, default=here.parent.parent / "build" / "pvm-compiler" / "staging")
    parser.add_argument("--compiler-version", default=None,
                        help="compiler version written into the manifest (required when generating)")
    parser.add_argument("--target-triple", default=None,
                        help="target triple the payload was built for, e.g. aarch64-unknown-linux-android "
                             "(required when generating; never guessed)")
    parser.add_argument("--engine", default=None,
                        help="compile engine the payload was built with (required when generating): "
                             f"{ENGINE_FAKE} for the CI stub, {ENGINE_CLANG} for the real libclang engine")
    parser.add_argument("--payload-binary", default=DEFAULT_PAYLOAD_BINARY,
                        help=f"payload .so name written into the manifest (default {DEFAULT_PAYLOAD_BINARY})")
    parser.add_argument("--memory-mib", type=int, default=DEFAULT_MEMORY_MIB,
                        help=f"VM memory in MiB, {MEMORY_MIB_MIN}..{MEMORY_MIB_MAX} (default {DEFAULT_MEMORY_MIB})")
    parser.add_argument("--manifest-out", type=Path, default=None,
                        help=f"manifest path override (default <out>/{MANIFEST_RELATIVE.as_posix()})")
    parser.add_argument("--verify-manifest", type=Path, default=None, metavar="DIR",
                        help="verify <DIR>/assets/PVM-COMPILER-MANIFEST against <DIR>/arm64-v8a/*.so and exit; "
                             "mutually exclusive with the generation arguments")
    arguments = parser.parse_args()

    if arguments.verify_manifest is not None:
        return verify_only(arguments)

    for flag, value in (("--compiler-version", arguments.compiler_version),
                        ("--target-triple", arguments.target_triple),
                        ("--engine", arguments.engine)):
        if value is None or not value.strip():
            raise fail(f"{flag} is required to write {MANIFEST_RELATIVE.as_posix()}")

    compiler_version = arguments.compiler_version.strip()
    target_triple = arguments.target_triple.strip()
    engine = arguments.engine.strip()
    if engine not in ENGINES:
        raise fail("--engine must be one of " + ", ".join(ENGINES))
    payload_binary = arguments.payload_binary.strip()
    if not is_payload_binary_name(payload_binary):
        raise fail("--payload-binary must be a bare file name matching [A-Za-z0-9._-]+.so")
    if not MEMORY_MIB_MIN <= arguments.memory_mib <= MEMORY_MIB_MAX:
        raise fail(f"--memory-mib must be in {MEMORY_MIB_MIN}..{MEMORY_MIB_MAX}")

    if arguments.compiler_dir and arguments.placeholder:
        raise fail("--compiler-dir and --placeholder are mutually exclusive")
    if not arguments.compiler_dir and not arguments.placeholder:
        raise fail("one of --compiler-dir or --placeholder is required")

    if arguments.payload_dir is not None:
        # The payload directory is validated, never copied. It must carry the
        # Fast Lane payload the compiler libraries are resolved next to, plus
        # whichever payload binary the manifest names for the compiler lane.
        for name in dict.fromkeys((REQUIRED_PAYLOAD_SO, payload_binary)):
            if not (arguments.payload_dir / "arm64-v8a" / name).is_file():
                raise fail(f"--payload-dir is missing arm64-v8a/{name}")
        # PVM-12: the declared engine must match the payload's own DT_NEEDED
        # list, otherwise a FAKE build can be shipped under engine=clang.
        check_payload_engine(arguments.payload_dir / "arm64-v8a" / payload_binary, engine)

    out = arguments.out
    abi_dir = out / "arm64-v8a"
    if abi_dir.exists():
        shutil.rmtree(out)
    abi_dir.mkdir(parents=True)

    staged: list[Path] = []

    placeholder = arguments.placeholder
    if placeholder:
        staged.extend(build_placeholders(abi_dir, arguments.ndk, arguments.api))
    else:
        compiler_root = arguments.compiler_dir / "arm64-v8a"
        if not compiler_root.is_dir():
            raise fail(f"compiler dir is missing arm64-v8a/: {arguments.compiler_dir}")
        for name in REQUIRED_COMPILER_SOS:
            source = compiler_root / name
            if not source.is_file():
                raise fail(f"compiler dir is missing arm64-v8a/{name}: {arguments.compiler_dir}")
            destination = abi_dir / name
            shutil.copy2(source, destination)
            staged.append(destination)
        libcxx = compiler_root / "libc++_shared.so"
        if libcxx.is_file():
            destination = abi_dir / "libc++_shared.so"
            shutil.copy2(libcxx, destination)
            staged.append(destination)

    if placeholder and arguments.ndk is not None:
        libcxx = (arguments.ndk / "toolchains" / "llvm" / "prebuilt" / "windows-x86_64"
                  / "sysroot" / "usr" / "lib" / "aarch64-linux-android" / "libc++_shared.so")
        if not libcxx.is_file():
            libcxx = (arguments.ndk / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64"
                      / "sysroot" / "usr" / "lib" / "aarch64-linux-android" / "libc++_shared.so")
        if libcxx.is_file():
            destination = abi_dir / "libc++_shared.so"
            shutil.copy2(libcxx, destination)
            staged.append(destination)

    facts = [verify_so(path) for path in staged]
    manifest = build_manifest(
        abi_dir,
        compiler_version=compiler_version,
        target_triple=target_triple,
        engine=engine,
        payload_binary=payload_binary,
        memory_mib=arguments.memory_mib,
    )
    manifest_path = arguments.manifest_out or (out / MANIFEST_RELATIVE)
    write_manifest(manifest, manifest_path)
    summary = {
        "staging": out.as_posix(),
        "placeholder": placeholder,
        "engine": engine,
        "files": facts,
        "manifest": {"path": manifest_label(manifest_path, out), "libs": len(manifest["libs"])},
    }
    print("PVM_COMPILER_STAGE_OK " + json.dumps(summary, sort_keys=True))
    if placeholder:
        print(
            "NOTE placeholder compiler .so in staging: dimensions are NOT libclang "
            f"(distinguishable via llvm-nm -D {PLACEHOLDER_SYMBOL})",
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
