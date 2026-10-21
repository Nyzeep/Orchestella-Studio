#!/usr/bin/env python3
"""Zero-device build and verification tools for the Fast Lane sealed toolbox.

The toolbox is assembled from pinned upstream artifacts into an APK-root
``bin/`` tree. It is never fetched at runtime and never installed by the user.
The Gradle build packages this tree as Java resources before AGP aligns and
signs the APK; this script also provides a synthetic-APK injector so the zip
layout, SHA256 values and failure gates can be tested without an Android SDK.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import struct
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

SCHEMA_VERSION = 1
MAX_TOOLBOX_BYTES = 80 * 1024 * 1024
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
SAFE_PATH_RE = re.compile(r"^bin/[A-Za-z0-9._+@-]+$")
SAFE_APPLET_RE = re.compile(r"^[A-Za-z0-9._+-]+$")
ELF_PAGE_16K = 16 * 1024

FIXTURE_BUSYBOX = b"""#!/bin/sh
# Test fixture for the sealed toolbox. Never shipped in an APK.
if [ "$1" = "-c" ]; then
  exec /bin/sh "$@"
fi
applet="$1"
shift || true
exec "$applet" "$@"
"""


class BuildError(RuntimeError):
    """A gate failure that must block packaging."""


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict:
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        raise BuildError(f"cannot read JSON {path}: {failure}") from failure


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise BuildError(message)


def validate_source_manifest(document: dict) -> None:
    _require(document.get("schemaVersion") == SCHEMA_VERSION, "unsupported manifest schemaVersion")
    for field in ("name", "version", "profile", "target", "install", "artifacts", "entries"):
        _require(field in document, f"manifest is missing {field}")

    artifacts = document["artifacts"]
    _require(isinstance(artifacts, list) and artifacts, "manifest has no artifacts")
    artifact_ids = set()
    for artifact in artifacts:
        for field in ("id", "name", "version", "license", "source", "sha256", "target", "deps"):
            _require(field in artifact, f"artifact is missing {field}: {artifact}")
        _require(artifact["id"] not in artifact_ids, f"duplicate artifact id {artifact['id']}")
        artifact_ids.add(artifact["id"])
        _require(bool(SHA256_RE.match(str(artifact["sha256"]))), f"bad sha256 for {artifact['id']}")
        if artifact.get("member"):
            _require(
                bool(SHA256_RE.match(str(artifact.get("binarySha256", "")))),
                f"artifact {artifact['id']} has a member but no binarySha256",
            )

    entries = document["entries"]
    _require(isinstance(entries, list) and entries, "manifest has no entries")
    seen = set()
    for entry in entries:
        path = entry.get("path", "")
        _require(bool(SAFE_PATH_RE.match(path)), f"unsafe entry path: {path!r}")
        _require(path != "bin/manifest.json", "bin/manifest.json is reserved")
        _require(path not in seen, f"duplicate entry path: {path}")
        seen.add(path)
        _require(entry.get("mode") == "0755", f"entry {path} must be mode 0755")
        kind = entry.get("type")
        if kind in {"artifact", "copy"}:
            _require(entry.get("artifact") in artifact_ids, f"entry {path} references an unknown artifact")
        elif kind == "applet":
            _require(bool(SAFE_APPLET_RE.match(str(entry.get("applet", "")))), f"bad applet for {path}")
        else:
            raise BuildError(f"unsupported entry type for {path}: {kind!r}")

    if "bin/sh" not in seen:
        raise BuildError("manifest must provide bin/sh")


def _cache_name(artifact: dict) -> str:
    return f"{artifact['sha256']}-{Path(artifact['source']).name}"


def _download(url: str, destination: Path) -> None:
    request = urllib.request.Request(
        url, headers={"User-Agent": "orchestella-fastlane-build/0.1"}
    )
    partial = destination.with_suffix(destination.suffix + ".part")
    try:
        with urllib.request.urlopen(request, timeout=120) as response, open(partial, "wb") as output:
            shutil.copyfileobj(response, output, length=1024 * 1024)
        os.replace(partial, destination)
    finally:
        if partial.exists():
            partial.unlink()


def fetch_package(artifact: dict, cache_dir: Path, offline: bool) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    package = cache_dir / _cache_name(artifact)
    if package.is_file() and sha256_file(package) == artifact["sha256"]:
        return package
    if package.exists():
        package.unlink()
    if offline:
        raise BuildError(f"artifact is not cached and --offline was set: {artifact['id']}")
    _download(artifact["source"], package)
    actual = sha256_file(package)
    if actual != artifact["sha256"]:
        package.unlink()
        raise BuildError(
            f"SHA256 mismatch for {artifact['id']}: expected {artifact['sha256']}, got {actual}"
        )
    return package


def extract_member(package: Path, member: str) -> bytes:
    try:
        with tarfile.open(package, "r:*") as archive:
            info = archive.getmember(member)
            _require(info.isfile(), f"artifact member is not a regular file: {member}")
            handle = archive.extractfile(info)
            _require(handle is not None, f"cannot extract {member}")
            return handle.read()
    except (OSError, tarfile.TarError, KeyError) as failure:
        raise BuildError(f"cannot read {member} from {package}: {failure}") from failure


def resolve_artifact_bytes(
    artifact: dict, cache_dir: Path, offline: bool, fixture: bool
) -> bytes:
    if fixture:
        _require(artifact["id"] == "busybox-static", "fixture mode only supports busybox-static")
        return FIXTURE_BUSYBOX
    package = fetch_package(artifact, cache_dir, offline)
    data = extract_member(package, artifact["member"])
    actual = sha256_bytes(data)
    _require(
        actual == artifact["binarySha256"],
        f"binary SHA256 mismatch for {artifact['id']}: expected "
        f"{artifact['binarySha256']}, got {actual}",
    )
    return data


def _applet_script(applet: str) -> bytes:
    return (
        "#!/mnt/apk/bin/busybox sh\n"
        f'exec /mnt/apk/bin/busybox {applet} "$@"\n'
    ).encode("utf-8")


def elf_load_alignments(data: bytes) -> list[int]:
    """Return p_align for every PT_LOAD in a little-endian ELF64 image.

    Returns an empty list for short, non-ELF or non-ELF64 input. This is enough
    for the arm64 toolbox; it deliberately does not try to be readelf.
    """
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        return []
    try:
        phoff = struct.unpack_from("<Q", data, 32)[0]
        phentsize, phnum = struct.unpack_from("<HH", data, 54)
    except struct.error:
        return []
    if phentsize < 56 or phoff + phentsize * phnum > len(data):
        return []
    alignments = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        p_type = struct.unpack_from("<I", data, offset)[0]
        if p_type != 1:
            continue
        p_align = struct.unpack_from("<Q", data, offset + 48)[0]
        alignments.append(p_align)
    return alignments


def _elf_check(data: bytes, label: str) -> int | None:
    alignments = elf_load_alignments(data)
    if not alignments:
        return None
    smallest = min(alignments)
    _require(
        smallest >= ELF_PAGE_16K,
        f"{label} is not 16 KB ELF-aligned: smallest PT_LOAD p_align=0x{smallest:x}",
    )
    return smallest


def build_toolbox(
    manifest_path: Path,
    out_dir: Path,
    cache_dir: Path | None = None,
    fixture: bool = False,
    offline: bool = False,
) -> dict:
    manifest_path = Path(manifest_path).resolve()
    out_dir = Path(out_dir).resolve()
    cache_dir = Path(cache_dir).resolve() if cache_dir else out_dir.parent / "cache"
    document = load_json(manifest_path)
    validate_source_manifest(document)
    if out_dir == Path("/"):
        raise BuildError("refusing to use / as the toolbox output")
    if out_dir.exists():
        shutil.rmtree(out_dir)
    (out_dir / "bin").mkdir(parents=True)

    used_artifacts: dict[str, dict] = {
        artifact["id"]: artifact for artifact in document["artifacts"]
    }
    artifact_bytes: dict[str, bytes] = {}
    entries = []
    for entry in sorted(document["entries"], key=lambda item: item["path"]):
        path = entry["path"]
        target = out_dir / path
        if entry["type"] == "applet":
            data = _applet_script(entry["applet"])
        else:
            artifact_id = entry["artifact"]
            if artifact_id not in artifact_bytes:
                artifact_bytes[artifact_id] = resolve_artifact_bytes(
                    used_artifacts[artifact_id], cache_dir, offline, fixture
                )
            data = artifact_bytes[artifact_id]
        target.write_bytes(data)
        os.chmod(target, 0o755)
        _elf_check(data, path)
        entries.append(
            {
                "path": path,
                "type": entry["type"],
                "mode": "0755",
                "size": len(data),
                "sha256": sha256_bytes(data),
            }
        )

    used = sorted({entry.get("artifact") for entry in document["entries"] if entry.get("artifact")})
    tools = []
    for artifact_id in used:
        artifact = used_artifacts[artifact_id]
        if fixture:
            binary_sha = sha256_bytes(artifact_bytes[artifact_id])
        else:
            binary_sha = artifact["binarySha256"]
        tool = {
            "name": artifact["name"],
            "version": artifact["version"],
            "sha256": binary_sha,
            "packageSha256": artifact["sha256"],
            "license": artifact["license"],
            "source": artifact["source"],
            "target": artifact["target"],
            "deps": artifact["deps"],
        }
        if artifact.get("correspondingSource"):
            tool["correspondingSource"] = artifact["correspondingSource"]
        tools.append(tool)

    runtime = {
        "schemaVersion": SCHEMA_VERSION,
        "name": document["name"],
        "version": document["version"],
        "profile": document["profile"],
        "target": document["target"],
        "install": document["install"],
        "fixture": fixture,
        "tools": tools,
        "entries": entries,
        "totalSize": sum(entry["size"] for entry in entries),
    }
    (out_dir / "bin" / "manifest.json").write_text(
        json.dumps(runtime, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return runtime


def verify_toolbox(toolbox_dir: Path, budget_mb: float = 80.0) -> dict:
    toolbox_dir = Path(toolbox_dir).resolve()
    manifest_path = toolbox_dir / "bin" / "manifest.json"
    _require(manifest_path.is_file(), f"toolbox manifest missing: {manifest_path}")
    manifest = load_json(manifest_path)
    entries = manifest.get("entries") or []
    _require(bool(entries), "toolbox manifest has no entries")
    listed = {entry["path"] for entry in entries}
    _require("bin/sh" in listed, "toolbox does not provide bin/sh")
    total = 0
    elf_checked = 0
    for entry in entries:
        path = toolbox_dir / entry["path"]
        _require(path.exists(), f"toolbox entry missing: {entry['path']}")
        _require(not path.is_symlink(), f"toolbox entry is a symlink: {entry['path']}")
        _require(path.is_file(), f"toolbox entry is not a regular file: {entry['path']}")
        mode = stat.S_IMODE(path.stat().st_mode)
        _require(mode & 0o111 != 0, f"toolbox entry is not executable: {entry['path']} ({mode:o})")
        _require(path.stat().st_size == entry["size"], f"size mismatch: {entry['path']}")
        _require(sha256_file(path) == entry["sha256"], f"SHA256 mismatch: {entry['path']}")
        data = path.read_bytes()
        if elf_load_alignments(data):
            _elf_check(data, entry["path"])
            elf_checked += 1
        total += entry["size"]

    for path in (toolbox_dir / "bin").rglob("*"):
        if path.is_dir() and not path.is_symlink():
            continue
        relative = path.relative_to(toolbox_dir).as_posix()
        _require(relative == "bin/manifest.json" or relative in listed, f"unlisted toolbox file: {relative}")
        _require(not path.is_symlink(), f"toolbox contains a symlink: {relative}")

    _require(total == manifest["totalSize"], "toolbox totalSize does not match the files")
    _require(total <= budget_mb * 1024 * 1024, f"toolbox exceeds {budget_mb} MiB: {total} bytes")
    return {
        "ok": True,
        "entries": len(entries),
        "totalSize": total,
        "elf16k": "ok" if elf_checked else "none",
        "elfChecked": elf_checked,
        "symlinks": 0,
        "manifest": str(manifest_path),
    }


def is_apk_signed(apk_path: Path) -> bool:
    apk_path = Path(apk_path)
    try:
        with zipfile.ZipFile(apk_path) as archive:
            for name in archive.namelist():
                if re.match(r"^META-INF/.*\.(RSA|DSA|EC|SF)$", name, re.IGNORECASE):
                    return True
    except (OSError, zipfile.BadZipFile) as failure:
        raise BuildError(f"cannot inspect APK {apk_path}: {failure}") from failure
    with open(apk_path, "rb") as handle:
        handle.seek(0, os.SEEK_END)
        if handle.tell() >= 16:
            handle.seek(-16, os.SEEK_END)
            return handle.read() == b"APK Sig Block 42"
    return False


def inject_apk(apk_in: Path, toolbox_dir: Path, apk_out: Path) -> int:
    """Inject bin/ into an unsigned APK for tests and fallback packaging.

    Production builds use the Gradle resource source set so AGP packages bin/
    before zipalign and signing. This function refuses to touch an already
    signed APK; rewriting a signed APK would invalidate its signature.
    """
    apk_in = Path(apk_in).resolve()
    apk_out = Path(apk_out).resolve()
    toolbox_dir = Path(toolbox_dir).resolve()
    verify_toolbox(toolbox_dir)
    if is_apk_signed(apk_in):
        raise BuildError("refusing to inject bin/ into an already signed APK")
    manifest = load_json(toolbox_dir / "bin" / "manifest.json")
    toolbox_files = []
    for entry in manifest["entries"]:
        path = toolbox_dir / entry["path"]
        _require(path.is_file() and not path.is_symlink(), f"bad toolbox entry: {entry['path']}")
        toolbox_files.append(path)
    toolbox_files.append(toolbox_dir / "bin" / "manifest.json")
    toolbox_files.sort(key=lambda item: item.relative_to(toolbox_dir).as_posix())

    temporary = apk_out.with_suffix(apk_out.suffix + ".tmp")
    with zipfile.ZipFile(apk_in) as source, zipfile.ZipFile(
        temporary, "w", zipfile.ZIP_DEFLATED
    ) as target:
        for info in source.infolist():
            name = info.filename
            _require(not name.startswith("bin/"), f"APK already contains bin/: {name}")
            _require(not name.startswith("/") and ".." not in Path(name).parts, f"unsafe APK path: {name}")
            target.writestr(info, source.read(name))
        for path in toolbox_files:
            relative = path.relative_to(toolbox_dir).as_posix()
            _require(relative.startswith("bin/"), f"toolbox file outside bin/: {relative}")
            info = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o755) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            target.writestr(info, path.read_bytes())
    os.replace(temporary, apk_out)
    return len(toolbox_files)


def verify_apk(
    apk_path: Path,
    runtime_manifest_path: Path,
    budget_mb: float = 80.0,
    zipalign: str | None = None,
    allow_fixture: bool = False,
) -> dict:
    apk_path = Path(apk_path).resolve()
    runtime_manifest_path = Path(runtime_manifest_path).resolve()
    expected = load_json(runtime_manifest_path)
    _require(
        allow_fixture or not expected.get("fixture"),
        "APK was built from the fixture toolbox; a real toolbox is required for this gate",
    )
    entries = expected.get("entries") or []
    _require(bool(entries), "runtime manifest has no entries")
    _require(expected.get("totalSize") == sum(entry["size"] for entry in entries), "runtime manifest totalSize is inconsistent")
    try:
        archive = zipfile.ZipFile(apk_path)
    except (OSError, zipfile.BadZipFile) as failure:
        raise BuildError(f"cannot open APK {apk_path}: {failure}") from failure
    with archive:
        names = set(archive.namelist())
        _require("bin/manifest.json" in names, "APK has no bin/manifest.json")
        expected_bin = {entry["path"] for entry in entries} | {"bin/manifest.json"}
        unexpected = {
            name
            for name in names
            if name.startswith("bin/") and not name.endswith("/") and name not in expected_bin
        }
        _require(not unexpected, f"APK has unexpected bin/ entries: {sorted(unexpected)}")
        embedded = json.loads(archive.read("bin/manifest.json"))
        _require(embedded == expected, "APK bin/manifest.json differs from the expected runtime manifest")
        total = 0
        elf_checked = 0
        zipfuse_default_mode = 0
        for entry in entries:
            path = entry["path"]
            _require(path in names, f"APK is missing {path}")
            info = archive.getinfo(path)
            data = archive.read(path)
            _require(len(data) == entry["size"], f"APK size mismatch: {path}")
            _require(sha256_bytes(data) == entry["sha256"], f"APK SHA256 mismatch: {path}")
            mode = (info.external_attr >> 16) & 0o777
            if mode == 0:
                zipfuse_default_mode += 1
            else:
                _require(mode & 0o111 != 0, f"APK entry is not executable: {path} ({mode:o})")
            alignments = elf_load_alignments(data)
            if alignments:
                _require(
                    min(alignments) >= ELF_PAGE_16K,
                    f"APK ELF is not 16 KB aligned: {path} p_align=0x{min(alignments):x}",
                )
                elf_checked += 1
            total += len(data)
        _require(total <= budget_mb * 1024 * 1024, f"APK bin/ exceeds {budget_mb} MiB: {total} bytes")

    if zipalign:
        executable = Path(zipalign)
        _require(executable.is_file() and os.access(executable, os.X_OK), f"zipalign not executable: {zipalign}")
        result = subprocess.run(
            [str(executable), "-c", "-P", "16", "-v", "4", str(apk_path)],
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
        _require(result.returncode == 0, f"zipalign -P 16 failed: {result.stdout}{result.stderr}")
        zipalign_status = "ok"
    else:
        zipalign_status = "SKIP"

    return {
        "ok": True,
        "entries": len(entries),
        "totalSize": total,
        "elf16k": "ok" if elf_checked else "none",
        "elfChecked": elf_checked,
        "zipfuseDefaultMode": zipfuse_default_mode,
        "zipalign": zipalign_status,
        "signed": is_apk_signed(apk_path),
    }


def _command_build(args: argparse.Namespace) -> int:
    runtime = build_toolbox(
        Path(args.manifest),
        Path(args.out),
        cache_dir=Path(args.cache) if args.cache else None,
        fixture=args.fixture,
        offline=args.offline,
    )
    print(json.dumps(runtime, indent=2, sort_keys=True))
    return 0


def _command_verify_toolbox(args: argparse.Namespace) -> int:
    print(json.dumps(verify_toolbox(Path(args.toolbox), args.budget_mb), indent=2, sort_keys=True))
    return 0


def _command_inject(args: argparse.Namespace) -> int:
    count = inject_apk(Path(args.apk_in), Path(args.toolbox), Path(args.apk_out))
    print(f"INJECTED {count} files into {args.apk_out}")
    return 0


def _command_verify_apk(args: argparse.Namespace) -> int:
    report = verify_apk(
        Path(args.apk),
        Path(args.runtime_manifest),
        budget_mb=args.budget_mb,
        zipalign=args.zipalign,
        allow_fixture=args.allow_fixture,
    )
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


def _command_check_licenses(args: argparse.Namespace) -> int:
    import check_licenses

    problems = check_licenses.check(
        Path(args.manifest), Path(args.notice), Path(args.sbom)
    )
    if problems:
        for problem in problems:
            print(f"FAIL {problem}", file=sys.stderr)
        return 1
    print("LICENSE_OK")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    build = subparsers.add_parser("build", help="assemble a toolbox from pinned artifacts")
    build.add_argument("--manifest", default="fastlane-toolbox/manifest.json")
    build.add_argument("--out", required=True)
    build.add_argument("--cache")
    build.add_argument("--fixture", action="store_true", help="use a local shell fixture (tests only)")
    build.add_argument("--offline", action="store_true")
    build.set_defaults(handler=_command_build)

    verify_toolbox_parser = subparsers.add_parser("verify-toolbox")
    verify_toolbox_parser.add_argument("--toolbox", required=True)
    verify_toolbox_parser.add_argument("--budget-mb", type=float, default=80.0)
    verify_toolbox_parser.set_defaults(handler=_command_verify_toolbox)

    inject = subparsers.add_parser("inject-apk")
    inject.add_argument("--apk-in", required=True)
    inject.add_argument("--toolbox", required=True)
    inject.add_argument("--apk-out", required=True)
    inject.set_defaults(handler=_command_inject)

    verify_apk_parser = subparsers.add_parser("verify-apk")
    verify_apk_parser.add_argument("--apk", required=True)
    verify_apk_parser.add_argument("--runtime-manifest", required=True)
    verify_apk_parser.add_argument("--budget-mb", type=float, default=80.0)
    verify_apk_parser.add_argument("--zipalign")
    verify_apk_parser.add_argument("--allow-fixture", action="store_true")
    verify_apk_parser.set_defaults(handler=_command_verify_apk)

    licenses = subparsers.add_parser("check-licenses")
    licenses.add_argument("--manifest", default="fastlane-toolbox/manifest.json")
    licenses.add_argument("--notice", default="NOTICE-fastlane.md")
    licenses.add_argument("--sbom", default="fastlane-toolbox/SBOM.spdx.json")
    licenses.set_defaults(handler=_command_check_licenses)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.handler(args)
    except BuildError as failure:
        print(f"FAIL {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
