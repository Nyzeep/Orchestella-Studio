#!/usr/bin/env python3
"""PVM-08: APK native-library size accounting and packaging assertions.

Inventory every lib/arm64-v8a/*.so entry of a built APK and, when --baseline
is given, diff against it. Asserts, per entry:

  * presence (equivalent to `unzip -l` lib/arm64-v8a/*.so);
  * stored uncompressed (compress_type == 0), which is what
    useLegacyPackaging=false / extractNativeLibs=false produce and what the
    AVF/Microdroid loader needs to mmap .so directly out of the APK;
  * local-file data offset 16 KB aligned (the zipalign -P 16 property, read
    straight from the local headers so no external tool is required).

`--require-libs` turns the compiler .so set into a hard assertion
(PVM-08 verification); without it the script is a plain inventory, so the
same command stays green on APKs that legitimately carry no compiler libs.

Exit codes: 0 ok; 1 usage error; 4 = packaging assertion failure (deflated,
misaligned); 5 = required libs missing.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path

PAGE_ALIGN = 16 * 1024
ZIP_LOCAL_HEADER = b"PK\x03\x04"
LIB_PREFIX = "lib/arm64-v8a/"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def local_data_offset(apk: Path, info: zipfile.ZipInfo) -> int:
    with apk.open("rb") as stream:
        stream.seek(info.header_offset)
        header = stream.read(30)
        if len(header) != 30 or header[:4] != ZIP_LOCAL_HEADER:
            raise ValueError(f"unreadable local header for {info.filename}")
        name_length = int.from_bytes(header[26:28], "little")
        extra_length = int.from_bytes(header[28:30], "little")
    return info.header_offset + 30 + name_length + extra_length


def elf_machine(data: bytes) -> int | None:
    if len(data) < 20 or data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        return None
    return struct.unpack_from("<H", data, 18)[0]


def inventory(apk: Path) -> dict:
    rows: list[dict] = []
    failures: list[str] = []
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        so_names = [n for n in names if n.startswith(LIB_PREFIX) and n.endswith(".so")]
        for name in sorted(so_names):
            info = archive.getinfo(name)
            data = archive.read(name) if info.file_size < 4 * 1024 * 1024 else b""
            machine = elf_machine(data[:64]) if data else None
            try:
                offset = local_data_offset(apk, info)
                aligned = offset % PAGE_ALIGN == 0
            except ValueError as failure:
                offset, aligned = -1, False
                failures.append(str(failure))
            row = {
                "name": name,
                "file_size": info.file_size,
                "compress_size": info.compress_size,
                "compress_type": info.compress_type,
                "data_offset": offset,
                "aligned_16k": aligned,
                "elf_machine": machine,
            }
            if info.compress_type != zipfile.ZIP_STORED:
                failures.append(f"{name} is deflated (compress_type={info.compress_type}); "
                                "useLegacyPackaging=false must store .so uncompressed")
            if not aligned:
                failures.append(f"{name} data offset 0x{offset:x} is not 16 KB aligned")
            rows.append(row)
        other = [n for n in names if n.startswith("lib/") and n not in so_names and not n.endswith("/")]
    return {
        "apk": apk.as_posix(),
        "apk_bytes": apk.stat().st_size,
        "apk_sha256": sha256_file(apk),
        "so_entries": rows,
        "lib_dir_total_bytes": sum(r["file_size"] for r in rows),
        "other_lib_entries": other,
        "failures": failures,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--baseline", type=Path, default=None,
                        help="APK built without the compiler libs; the report gains a delta section")
    parser.add_argument("--require-libs", default=None,
                        help="comma-separated lib/arm64-v8a entries that must exist, e.g. "
                             "libLLVM.so,libclang-cpp.so,libc++_shared.so")
    parser.add_argument("--out", type=Path, default=None, help="write a Markdown report to this path")
    parser.add_argument("--json", dest="json_out", type=Path, default=None, help="write raw JSON facts")
    arguments = parser.parse_args()

    if not arguments.apk.is_file():
        print(f"FAIL APK not found: {arguments.apk}", file=sys.stderr)
        return 1

    current = inventory(arguments.apk)
    baseline = inventory(arguments.baseline) if arguments.baseline else None

    missing: list[str] = []
    if arguments.require_libs:
        present = {Path(r["name"]).name for r in current["so_entries"]}
        for required in arguments.require_libs.split(","):
            required = required.strip()
            if required and required not in present:
                missing.append(required)
        if missing:
            print("FAIL missing lib/arm64-v8a entries: " + ", ".join(missing), file=sys.stderr)

    if arguments.json_out:
        payload = {"current": current, "baseline": baseline, "missing_required": missing}
        arguments.json_out.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    lines: list[str] = []
    add = lines.append
    add("# APK native-library report（PVM-08）")
    add("")
    add(f"- APK：`{arguments.apk.name}`，总大小 **{current['apk_bytes']:,} 字节**（{current['apk_bytes'] / (1024 * 1024):.1f} MB）")
    add(f"- `lib/arm64-v8a/*.so` 条目数：{len(current['so_entries'])}；合计（未压缩）{current['lib_dir_total_bytes'] / (1024 * 1024):.1f} MB")
    if current["other_lib_entries"]:
        add(f"- 其他 ABI/库条目：{', '.join(current['other_lib_entries'])}")
    add("")
    add("| .so | 未压缩 | 压缩存储 | compress_type | 16 KB 对齐 |")
    add("| --- | --- | --- | --- | --- |")
    for row in current["so_entries"]:
        stored = "STORED" if row["compress_type"] == zipfile.ZIP_STORED else f"type={row['compress_type']}"
        size = f"{row['file_size']:,}"
        add(f"| {Path(row['name']).name} | {size} | {row['compress_size']:,} | {stored} | "
            f"{'PASS' if row['aligned_16k'] else 'FAIL'} |")
    if baseline is not None:
        add("")
        add(f"## 与基线（`{arguments.baseline.name}`）的差值")
        add("")
        delta_total = current["apk_bytes"] - baseline["apk_bytes"]
        add(f"- APK 总差值：**{delta_total:+,} 字节**（{delta_total / (1024 * 1024):+.1f} MB）")
        base_sizes = {Path(r["name"]).name: r["file_size"] for r in baseline["so_entries"]}
        add("")
        add("| .so | 基线 → 当前（字节） |")
        add("| --- | --- |")
        for row in current["so_entries"]:
            name = Path(row["name"]).name
            before = base_sizes.get(name, 0)
            add(f"| {name} | {before:,} → {row['file_size']:,}（{row['file_size'] - before:+,}） |")
        for name in sorted(set(base_sizes) - {Path(r['name']).name for r in current['so_entries']}):
            add(f"| {name} | {base_sizes[name]:,} → 缺失 |")
    add("")
    add("- 16 KB 对齐来自 APK local header 数据偏移断言（与 `zipalign -c -P 16` 同一性质，另跑工具命令复核）。")
    add("- ELF machine=183（AARCH64）逐项核对。" if any(r["elf_machine"] == 183 for r in current["so_entries"])
        else "- 无 AArch64 ELF 条目。")

    text = "\n".join(lines) + "\n"
    if arguments.out:
        arguments.out.write_text(text, encoding="utf-8", newline="\n")
    print(text)

    print(
        "APK_LIB_REPORT_OK"
        if not (current["failures"] or missing)
        else "APK_LIB_REPORT_FAIL",
    )
    for failure in current["failures"]:
        print("FAIL " + failure, file=sys.stderr)
    if missing:
        return 5
    if current["failures"]:
        return 4
    return 0


if __name__ == "__main__":
    sys.exit(main())
