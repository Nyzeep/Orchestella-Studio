#!/usr/bin/env python3
"""Build the pinned guest agent with an existing musl sysroot and Android NDK clang."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import re


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ndk-llvm", required=True, type=Path)
    parser.add_argument("--musl-sysroot", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--temporary", required=True, type=Path)
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--input-lock", required=True, type=Path)
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[2] / "guest-assets/agent/guest-agent.c"
    for directory in (args.output.parent, args.evidence, args.temporary):
        directory.mkdir(parents=True, exist_ok=True)
    llvm = args.ndk_llvm
    lib = args.musl_sysroot / "usr/lib"
    builtins = llvm / "lib/clang/18/lib/baremetal/libclang_rt.builtins-aarch64.a"
    compiler = llvm / "bin/clang.exe"
    before = sha(source)
    lock = json.loads(args.input_lock.read_text(encoding="utf-8"))
    if lock.get("sourceSha256") != before:
        raise SystemExit("Guest source does not match the production input lock")
    roots = {"ndk": llvm, "musl": args.musl_sysroot}
    inputs = []
    for item in lock["inputs"]:
        relative = Path(item["path"])
        if relative.is_absolute() or ".." in relative.parts or item["root"] not in roots:
            raise SystemExit("Invalid production input lock path")
        path = roots[item["root"]] / relative
        if not path.is_file() or path.stat().st_size != item["bytes"] or sha(path) != item["sha256"]:
            raise SystemExit("Production toolchain input does not match its lock: " + item["path"])
        inputs.append((path, item["sha256"]))
    required = {compiler.resolve(), builtins.resolve(), *(lib.joinpath(name).resolve() for name in ("crt1.o", "crti.o", "crtn.o", "libc.a"))}
    locked_paths = {path.resolve() for path, _ in inputs}
    if not required.issubset(locked_paths):
        raise SystemExit("Production input lock omits a required compiler or musl input")
    dependency_command = [str(compiler), "--target=aarch64-linux-musl",
            "--sysroot=" + str(args.musl_sysroot), "-static", "-nostdinc", "-isystem",
            str(args.musl_sysroot / "usr/include"), "-std=c11", "-Os", "-Wall", "-Wextra", "-Werror",
            "-fno-ident", "-M", "-MT", "guest-agent", str(source)]
    preflight = subprocess.run(dependency_command,
            capture_output=True, text=True, encoding="utf-8", errors="strict", check=True)
    dependencies = re.sub(r"\\\r?\n", "", preflight.stdout).split(": ", 1)[1]
    consumed = {Path(re.sub(r"\\(.)", r"\1", value)).resolve()
                for value in re.findall(r"(?:\\.|[^\\\s])+", dependencies)}
    if not consumed.issubset(locked_paths | {source.resolve()}):
        raise SystemExit("Production input lock omits a consumed header")
    flags = [str(compiler), "--target=aarch64-linux-musl", "--sysroot=" + str(args.musl_sysroot),
             "-static", "-fuse-ld=lld", "-nostdinc", "-isystem", str(args.musl_sysroot / "usr/include"),
             "-std=c11", "-Os", "-Wall", "-Wextra", "-Werror", "-fno-ident", "-Wl,--build-id=none", "-Wl,-z,noexecstack",
             "-nostdlib", "-Wl,-e,_start", str(lib / "crt1.o"), str(lib / "crti.o"), str(source),
             "-Wl,--start-group", str(lib / "libc.a"), str(builtins), "-Wl,--end-group",
             str(lib / "crtn.o"), "-o", str(args.output)]
    environment = os.environ.copy()
    environment.update(TEMP=str(args.temporary), TMP=str(args.temporary))
    result = subprocess.run(flags, capture_output=True, text=True, encoding="utf-8", errors="replace", env=environment)
    text = result.stdout + result.stderr
    (args.evidence / "static-agent-compile.log.txt").write_text(text, encoding="utf-8")
    preserved = args.evidence / "preserved"
    preserved.mkdir(exist_ok=True)
    (preserved / "static-agent-compile.log.txt").write_text(text, encoding="utf-8")
    if result.returncode:
        raise SystemExit(result.returncode)
    after_dependencies = subprocess.run(dependency_command,
            capture_output=True, text=True, encoding="utf-8", errors="strict", check=True)
    if (sha(source) != before or any(sha(path) != expected for path, expected in inputs)
            or after_dependencies.stdout != preflight.stdout):
        raise SystemExit("Guest source or production toolchain changed during compilation")
    data = args.output.read_bytes()
    if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
        raise SystemExit("Not a little-endian AArch64 ELF64")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if entry_size < 56 or offset + count * entry_size > len(data):
        raise SystemExit("Invalid ELF program table")
    kinds = [struct.unpack_from("<I", data, offset + index * entry_size)[0] for index in range(count)]
    if 2 in kinds or 3 in kinds:
        raise SystemExit("Guest binary contains an interpreter or dynamic segment")
    stacks = [offset + index * entry_size for index, kind in enumerate(kinds) if kind == 0x6474e551]
    if len(stacks) != 1 or struct.unpack_from("<I", data, stacks[0] + 4)[0] & 1:
        raise SystemExit("Guest binary must declare a non-executable stack")
    record = {"inputLockSha256": sha(args.input_lock), "stackExecutable": False, "sourceSha256": before, "agentSha256": sha(args.output), "agentBytes": len(data),
              "elf": "AArch64 static; no PT_INTERP or PT_DYNAMIC", "command": flags,
              "compilerSha256": sha(compiler), "builtinsSha256": sha(builtins),
              "crtSha256": {name: sha(lib / name) for name in ("crt1.o", "crti.o", "crtn.o", "libc.a")},
              "guestExecution": "NOT_RUN"}
    for base in (args.evidence, preserved):
        (base / "static-agent-build.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
    if args.bundle or args.manifest:
        if not args.bundle or not args.manifest:
            raise SystemExit("Bundle and manifest must be supplied together")
        repack = source.parents[1] / "scripts/repack-guest-agent.py"
        command = [os.sys.executable, str(repack), "--bundle", str(args.bundle), "--manifest", str(args.manifest),
                   "--agent", str(args.output), "--agent-sha256", record["agentSha256"], "--source", str(source),
                   "--source-sha256", before, "--evidence", str(args.evidence)]
        subprocess.run(command, check=True, env=environment)
    print(json.dumps({"agentSha256": record["agentSha256"], "sourceSha256": before, "bytes": len(data)}))


if __name__ == "__main__":
    main()
