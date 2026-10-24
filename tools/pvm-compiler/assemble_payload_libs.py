#!/usr/bin/env python3
"""PVM-08: assemble the payload + compiler shared-library staging directory.

The Gradle payload property (orchestella.fastlane.payloadLibDir) and the
compiler property (orchestella.pvmCompiler.libDir) point at two directories
whose arm64-v8a/*.so contents AGP merges into the APK's lib/arm64-v8a/. This
script stages the compiler side:

  * real mode: copy libLLVM.so / libclang-cpp.so (PVM-04 build output) plus
    libc++_shared.so when present into the staging arm64-v8a/;
  * placeholder mode (--placeholder): build a few-KB same-ABI stub pair with
    the NDK so the packaging/alignment/size pipeline can be rehearsed before
    the real PVM-04 artifacts exist. Stubs carry a PVM04_PLACEHOLDER symbol
    and must never be mistaken for libclang.

Every staged .so is checked for EM_AARCH64 and >= 16 KB PT_LOAD alignment
(pure-Python ELF parse, no readelf dependency).

Exit codes: 0 ok; 1 usage/validation failure; 2 ELF/alignment failure.
"""

from __future__ import annotations

import argparse
import hashlib
import json
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


def build_placeholders(out_dir: Path, ndk: Path | None, api: int) -> list[Path]:
    clang = find_ndk_clang(ndk)
    produced: list[Path] = []
    with tempfile.TemporaryDirectory(prefix="pvm08-placeholder-") as temporary:
        work = Path(temporary)
        # libLLVM.so: plain stub, forced soname.
        llvm_c = work / "llvm_stub.c"
        llvm_c.write_text(STUB_C.format(entry="pvm_llvm_stub_entry"), encoding="ascii")
        llvm_so = out_dir / "libLLVM.so"
        cmd = [
            str(clang), f"--target=aarch64-linux-android{api}",
            "-shared", "-fPIC", "-Oz", "-nostdlib",
            "-Wl,-soname,libLLVM.so", "-Wl,-z,max-page-size=16384",
            "-o", str(llvm_so), str(llvm_c),
        ]
        subprocess.run(cmd, check=True, capture_output=True, text=True)
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
        cmd = [
            str(clang), f"--target=aarch64-linux-android{api}",
            "-shared", "-fPIC", "-Oz", "-nostdlib",
            "-Wl,-soname,libclang-cpp.so", "-Wl,-z,max-page-size=16384",
            "-Wl,--no-as-needed", str(llvm_so),
            "-o", str(cpp_so), str(cpp_c),
        ]
        subprocess.run(cmd, check=True, capture_output=True, text=True)
        produced.append(cpp_so)
    return produced


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    here = Path(__file__).resolve().parent
    parser.add_argument("--payload-dir", required=True, type=Path,
                        help="directory containing arm64-v8a/libfastlane_payload.so (build/fastlane-payload)")
    parser.add_argument("--compiler-dir", type=Path, default=None,
                        help="directory containing arm64-v8a/libLLVM.so and libclang-cpp.so (tools/llvm-android out); "
                             "mutually exclusive with --placeholder")
    parser.add_argument("--placeholder", action="store_true",
                        help="generate stub compiler .so files instead of copying a real build")
    parser.add_argument("--ndk", type=Path, default=None,
                        help="NDK root for --placeholder stub compilation and the real libc++_shared.so copy")
    parser.add_argument("--api", type=int, default=26, help="Android API level for placeholder stubs (default 26)")
    parser.add_argument("--out", type=Path, default=here.parent.parent / "build" / "pvm-compiler" / "staging")
    arguments = parser.parse_args()

    if arguments.compiler_dir and arguments.placeholder:
        raise fail("--compiler-dir and --placeholder are mutually exclusive")
    if not arguments.compiler_dir and not arguments.placeholder:
        raise fail("one of --compiler-dir or --placeholder is required")

    payload_so = arguments.payload_dir / "arm64-v8a" / "libfastlane_payload.so"
    if not payload_so.is_file():
        raise fail(f"payload dir is missing arm64-v8a/libfastlane_payload.so: {arguments.payload_dir}")

    out = arguments.out
    abi_dir = out / "arm64-v8a"
    if abi_dir.exists():
        shutil.rmtree(out)
    abi_dir.mkdir(parents=True)

    staged: list[Path] = []
    payload_copy = abi_dir / "libfastlane_payload.so"
    shutil.copy2(payload_so, payload_copy)
    staged.append(payload_copy)

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
    summary = {
        "staging": out.as_posix(),
        "placeholder": placeholder,
        "files": facts,
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
