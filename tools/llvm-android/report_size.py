#!/usr/bin/env python3
"""PVM-04: libclang 交叉构建 spike 的尺寸/依赖报告生成器（零设备）。

用法（在仓库根目录）：
    python tools/llvm-android/report_size.py --out tools/llvm-android/SIZE_REPORT.md

输入约定（与 build.sh 对齐，可用参数覆盖）：
    --dist       build.sh 产物目录（strip 后，内含 arm64-v8a/*.so）
    --unstripped build.sh 保留的未 strip 产物目录（可选）
    --meta       build.sh 写出的 build-meta.json
    --readelf/--nm  NDK 的 llvm-readelf / llvm-nm（默认自动探测）

退出码：0 正常；2 = 16 KB LOAD 对齐断言失败；3 = strip 后合计超过 150 MB 阈值。
报告内不输出含用户名的本机绝对路径。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

SIZE_THRESHOLD_MB = 150.0
REQUIRED_ALIGN_BYTES = 16384

KNOWN_SONAME_ORDER = ["libclang-cpp.so", "libLLVM.so", "libc++_shared.so"]


def human(n: int) -> str:
    f = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if f < 1024 or unit == "GB":
            return f"{f:.1f} {unit}" if unit != "B" else f"{int(f)} B"
        f /= 1024
    return f"{f:.1f} GB"


def find_ndk_tool(tool: str) -> str | None:
    """在环境变量给出的 NDK 里探测 llvm-readelf / llvm-nm。

    只认环境变量（LLVM_ANDROID_NDK / ANDROID_NDK_HOME / ANDROID_NDK_ROOT /
    ANDROID_SDK_ROOT / ANDROID_HOME 下的 ndk/），避免把本机绝对路径写进代码。
    """
    exe = tool + (".exe" if os.name == "nt" else "")
    ndk_roots: list[str] = []
    for var in ("LLVM_ANDROID_NDK", "ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        value = os.environ.get(var)
        if value:
            ndk_roots.append(value)
    for var in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(var)
        if value and Path(value, "ndk").is_dir():
            ndk_roots.append(str(sorted(Path(value, "ndk").iterdir())[-1]))
    for ndk in ndk_roots:
        for host in ("windows-x86_64", "linux-x86_64"):
            candidate = Path(ndk) / "toolchains" / "llvm" / "prebuilt" / host / "bin" / exe
            if candidate.is_file():
                return str(candidate)
    hit = shutil.which(tool)
    return hit


def run(tool: str, args: list[str], what: str) -> str:
    proc = subprocess.run([tool, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        raise RuntimeError(f"{what} failed (rc={proc.returncode}): {proc.stderr.strip()[:400]}")
    return proc.stdout


def parse_needed(readelf_out: str) -> list[str]:
    return re.findall(r"\(NEEDED\)\s+Shared library:\s*\[([^\]]+)\]", readelf_out)


def parse_soname(readelf_out: str) -> str | None:
    m = re.search(r"\(SONAME\)\s+Library soname:\s*\[([^\]]+)\]", readelf_out)
    return m.group(1) if m else None


def parse_load_align(readelf_out: str) -> tuple[int, list[int]]:
    """返回 (最大 p_align, 全部 LOAD 段的 p_align 列表)。"""
    aligns: list[int] = []
    for line in readelf_out.splitlines():
        m = re.match(r"\s*LOAD\s+", line)
        if m:
            am = re.search(r"0x([0-9a-fA-F]+)\s*$", line.strip())
            if am:
                aligns.append(int(am.group(1), 16))
    return (max(aligns) if aligns else 0), aligns


def parse_rpath(readelf_out: str) -> list[str]:
    out = []
    for tag in ("RUNPATH", "RPATH"):
        m = re.search(rf"\({tag}\)\s+Library runpath:\s*\[([^\]]*)\]", readelf_out)
        if m:
            out.append(f"{tag}={m.group(1)}")
    return out


def aarch64_init_symbols(nm_out: str) -> list[str]:
    syms = set()
    for m in re.finditer(r"LLVMInitializeAArch64\w*", nm_out):
        syms.add(m.group(0))
    return sorted(syms)


def collect_so(dist: Path) -> list[Path]:
    if not dist.is_dir():
        raise SystemExit(f"dist 目录不存在: {dist}")
    return sorted(dist.glob("*.so"))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    here = Path(__file__).resolve().parent
    build_out = here.parent.parent / "build" / "llvm-android"
    ap.add_argument("--dist", default=str(build_out / "lib" / "arm64-v8a"))
    ap.add_argument("--unstripped", default=str(build_out / "unstripped" / "arm64-v8a"))
    ap.add_argument("--meta", default=str(build_out / "build-meta.json"))
    ap.add_argument("--readelf", default=None)
    ap.add_argument("--nm", default=None)
    ap.add_argument("--out", default=str(here / "SIZE_REPORT.md"))
    args = ap.parse_args()

    dist = Path(args.dist)
    unstripped = Path(args.unstripped)
    meta_path = Path(args.meta)
    meta: dict = {}
    if meta_path.is_file():
        meta = json.loads(meta_path.read_text(encoding="utf-8"))

    readelf = args.readelf or find_ndk_tool("llvm-readelf")
    nm = args.nm or find_ndk_tool("llvm-nm")
    if not readelf:
        raise SystemExit("找不到 llvm-readelf：请用 --readelf 指定 NDK 里的路径")

    sos = collect_so(dist)
    if not sos:
        raise SystemExit(f"dist 目录里没有 .so: {dist}")

    lines: list[str] = []
    add = lines.append
    gen = meta.get("generated_at") or ""
    add("# SIZE_REPORT：libclang 交叉构建 spike（PVM-04）")
    add("")
    if gen:
        add(f"- 生成时间：{gen}")
    add(f"- LLVM/clang 版本：`{meta.get('llvm_version', 'unknown')}`（`llvm-config --version`）")
    add(f"- 源码 tarball：`llvm-project-{meta.get('llvm_version', '?')}.src.tar.xz`，SHA-256 pin 见 `build.sh`")
    add(f"- Target：`{meta.get('abi', '?')}` / API `{meta.get('api_level', '?')}`；LLVM_TARGETS_TO_BUILD=`{meta.get('targets', '?')}`")
    add(f"- Host triple（LLVM 内部记录）：`{meta.get('llvm_host_triple', '?')}`")
    add(f"- Default target triple：`{meta.get('default_triple', '?')}`")
    add(f"- Build type：`{meta.get('build_type', '?')}`；NDK：`{meta.get('ndk_version', '?')}`")
    add(f"- 构建主机逻辑核：{meta.get('host_cores', '?')}；并行度 -j{meta.get('jobs', '?')}")
    add(f"- 墙钟：host tools {meta.get('wall_host_s', '?')} s + cross {meta.get('wall_cross_s', '?')} s（合计 {meta.get('wall_total_s', '?')} s）")
    add(f"- 构建树磁盘占用（结束值，≈峰值）：host {meta.get('disk_host_mb', '?')} MB + cross {meta.get('disk_cross_mb', '?')} MB")
    add("- 判定阈值：strip 后 `libLLVM.so` + `libclang-cpp.so` 合计 ≤ 150 MB")
    add("")
    add("## 尺寸与动态依赖")
    add("")
    add("| .so | strip 前 | strip 后 | SONAME | NEEDED |")
    add("| --- | --- | --- | --- | --- |")

    total_stripped_core = 0
    align_fail: list[str] = []
    detail: list[str] = []
    for so in sos:
        stripped_size = so.stat().st_size
        raw = unstripped / so.name
        raw_size = raw.stat().st_size if raw.is_file() else stripped_size
        needed = parse_needed(run(readelf, ["-d", str(so)], f"readelf -d {so.name}"))
        soname = parse_soname(run(readelf, ["-d", str(so)], f"readelf -d {so.name} (soname)"))
        add(f"| {so.name} | {human(raw_size)} | {human(stripped_size)} | `{soname or '-'}` | {', '.join(f'`{n}`' for n in needed) or '-'} |")
        if so.name in ("libLLVM.so", "libclang-cpp.so"):
            total_stripped_core += stripped_size
        max_align, aligns = parse_load_align(run(readelf, ["-lW", str(so)], f"readelf -lW {so.name}"))
        ok = max_align >= REQUIRED_ALIGN_BYTES and all(a >= REQUIRED_ALIGN_BYTES for a in aligns)
        if not ok:
            align_fail.append(so.name)
        extra = parse_rpath(run(readelf, ["-d", str(so)], f"readelf -d {so.name} (rpath)"))
        detail.append((so.name, max_align, aligns, ok, extra))

    add("")
    add(f"- 核心合计（libLLVM + libclang-cpp，strip 后）：**{human(total_stripped_core)}**"
        f" → {'尺寸可接受' if total_stripped_core <= SIZE_THRESHOLD_MB * 1024 * 1024 else '超过 150 MB 阈值，停止并给裁剪项'}")
    add("")
    add("## ELF LOAD 段对齐（16 KB 兼容）")
    add("")
    add("| .so | LOAD 段 p_align | 16 KB 断言 |")
    add("| --- | --- | --- |")
    for name, max_align, aligns, ok, extra in detail:
        vals = ", ".join(hex(a) for a in aligns)
        add(f"| {name} | {vals} | {'PASS' if ok else 'FAIL'} |")
    add("")
    rpath_lines = [f"- {n}: {'; '.join(e) if e else '无 RUNPATH/RPATH'}" for n, _, _, _, e in detail]
    lines.extend(rpath_lines)
    add("")
    add("## AArch64 初始化符号（`LLVMInitializeAArch64*`）")
    add("")
    if nm and (dist / "libLLVM.so").is_file():
        nm_out = run(nm, ["-D", str(dist / "libLLVM.so")], "nm -D libLLVM.so")
        syms = aarch64_init_symbols(nm_out)
        if syms:
            add("libLLVM.so 动态符号表含：" + ", ".join(f"`{s}`" for s in syms))
        else:
            add("未在动态符号表发现 `LLVMInitializeAArch64*`（payload 侧初始化入口缺失，需复核 LLVM_BUILD_LLVM_DYLIB 导出表）。")
    else:
        add("（llvm-nm 不可用或 libLLVM.so 缺失，未检查）")
    add("")
    add("## 备注")
    add("")
    add("- NEEDED 里的 `libc++_shared.so` 来自 NDK（与构建同一 NDK 版本），随包分发；" if any(
        "libc++_shared" in p for p in [str(x) for x in sos]) else "- 无 libc++_shared 依赖。")
    add("- 本报告不含本机绝对路径；产物目录等环境信息见 `README.zh-CN.md` 的默认值。")

    out = Path(args.out)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"written: {out.as_posix()}")
    print(f"core_stripped_bytes={total_stripped_core}")
    if align_fail:
        print(f"ALIGN_FAIL: {', '.join(align_fail)}", file=sys.stderr)
        return 2
    if total_stripped_core > SIZE_THRESHOLD_MB * 1024 * 1024:
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
