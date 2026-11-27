#!/usr/bin/env python3
"""One-click 4 KB device runner for W01-04 Docker workload & persistence probe (handoff to W01-05)."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

DEBIAN_ROOTFS_TAR = "debian-13-rootfs.tar.xz"
DEBIAN_ROOTFS_SHA256 = "e4d504e13e43a384227639b74391ef164a67d916aa8f8a9871ecd73dc577db91"
QA_PACKAGE = "io.github.nyzeep.orchestella.qa.w0104"
OLD_W0103_PACKAGE = "io.github.nyzeep.orchestella.qa.w0103"
INSTRUMENTATION = f"{QA_PACKAGE}.test/io.github.nyzeep.orchestella.W0104DockerWorkloadProbe"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(
    cmd: list[str], *, check: bool = True, timeout: int = 120
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, text=True, capture_output=True, check=check, timeout=timeout)


def adb(
    serial: str | None, *args: str, check: bool = True, timeout: int = 120
) -> subprocess.CompletedProcess[str]:
    prefix = ["adb", "-s", serial] if serial else ["adb"]
    return run([*prefix, *args], check=check, timeout=timeout)


def resolve_device_serial(requested: str | None) -> str:
    if requested:
        return requested
    out = run(["adb", "devices"]).stdout
    devices = [
        line.split()[0]
        for line in out.splitlines()[1:]
        if line.strip() and line.split()[-1] == "device"
    ]
    if len(devices) != 1:
        raise SystemExit(f"Expected exactly 1 connected authorized ADB device, got: {devices}")
    return devices[0]


def verify_inputs(debian_tar: Path, fixtures_dir: Path) -> dict:
    if not debian_tar.is_file():
        raise SystemExit(f"Missing Debian rootfs tar: {debian_tar}")
    actual_rootfs_sha = sha256_file(debian_tar)
    if actual_rootfs_sha != DEBIAN_ROOTFS_SHA256:
        raise SystemExit(
            f"Debian rootfs SHA-256 mismatch: expected={DEBIAN_ROOTFS_SHA256}, actual={actual_rootfs_sha}"
        )
    lock_file = fixtures_dir / "w0104-lock.json"
    base_img = fixtures_dir / "base-image-arm64.tar"
    keyring = fixtures_dir / "apt-repo/qa-w0104-archive.gpg"
    inrelease = fixtures_dir / "apt-repo/dists/trixie/InRelease"
    for required in (lock_file, base_img, keyring, inrelease):
        if not required.is_file():
            raise SystemExit(f"Missing W01.04 fixture file: {required}")
    lock = json.loads(lock_file.read_text(encoding="utf-8"))
    actual_img_sha = sha256_file(base_img)
    expected_img_sha = lock["base_image"]["tar_sha256"]
    if actual_img_sha != expected_img_sha:
        raise SystemExit(
            f"Base image tar SHA-256 mismatch: expected={expected_img_sha}, actual={actual_img_sha}"
        )
    for pkg in lock["packages"]:
        deb = fixtures_dir / "apt-repo" / pkg["filename"]
        if not deb.is_file() or sha256_file(deb) != pkg["sha256"]:
            raise SystemExit(f"Corrupt or missing .deb in fixtures: {pkg['filename']}")
    return lock


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=None, help="ADB device serial (optional when single device)")
    parser.add_argument("--app-apk", type=Path, required=True, help="Path to qa.w0104 app APK")
    parser.add_argument("--test-apk", type=Path, required=True, help="Path to qa.w0104 androidTest APK")
    parser.add_argument("--debian-tar", type=Path, required=True, help="Path to debian-13-rootfs.tar.xz")
    parser.add_argument("--fixtures-dir", type=Path, required=True, help="Path to w0104-fixtures directory")
    parser.add_argument("--evidence-dir", type=Path, required=True, help="External directory for probe logs")
    parser.add_argument("--action", default="all", choices=("all", "preflight", "workload"),
                        help="Probe action subset")
    parser.add_argument("--keep-old-w0103", action="store_true",
                        help="Skip uninstalling old qa.w0103 packages")
    args = parser.parse_args()

    for apk in (args.app_apk, args.test_apk):
        if not apk.is_file():
            raise SystemExit(f"APK not found: {apk}")
    lock = verify_inputs(args.debian_tar, args.fixtures_dir)
    args.evidence_dir.mkdir(parents=True, exist_ok=True)

    serial = resolve_device_serial(args.serial)
    page_size = int(adb(serial, "shell", "getconf", "PAGESIZE").stdout.strip())
    if page_size != 4096:
        raise SystemExit(f"Expected 4 KB physical host page size (4096), got {page_size}")

    if not args.keep_old_w0103:
        for old_pkg in (f"{OLD_W0103_PACKAGE}.test", OLD_W0103_PACKAGE):
            adb(serial, "uninstall", old_pkg, check=False)

    for pkg in (f"{QA_PACKAGE}.test", QA_PACKAGE):
        adb(serial, "uninstall", pkg, check=False)

    adb(serial, "install", "-r", "-t", str(args.app_apk), timeout=300)
    adb(serial, "install", "-r", "-t", str(args.test_apk), timeout=120)

    # Stage Debian rootfs tarball and W01-04 offline fixtures into app-private filesDir
    remote_tmp = "/data/local/tmp/w0104-stage"
    proc: subprocess.CompletedProcess[str]
    try:
        adb(serial, "shell", f"rm -rf {remote_tmp} && mkdir -p {remote_tmp}", timeout=60)
        adb(serial, "push", str(args.debian_tar), f"{remote_tmp}/{DEBIAN_ROOTFS_TAR}", timeout=300)
        adb(serial, "push", str(args.fixtures_dir), f"{remote_tmp}/w0104-fixtures", timeout=300)
        adb(
            serial,
            "shell",
            f"run-as {QA_PACKAGE} sh -c '"
            f"mkdir -p files/distro-inputs && rm -rf files/w0104-fixtures && "
            f"cp {remote_tmp}/{DEBIAN_ROOTFS_TAR} files/distro-inputs/{DEBIAN_ROOTFS_TAR} && "
            f"cp -r {remote_tmp}/w0104-fixtures files/w0104-fixtures && "
            f"chmod -R a+rX files/w0104-fixtures && "
            f"chmod 0644 files/distro-inputs/{DEBIAN_ROOTFS_TAR}"
            f"'",
            timeout=180,
        )

        proc = run(
            [
                "adb", "-s", serial, "shell", "am", "instrument", "-w",
                "-e", "action", args.action,
                INSTRUMENTATION,
            ],
            check=False,
            timeout=1800,
        )
    finally:
        # Always reclaim staging and temporary probe storage on device.
        adb(serial, "shell", f"rm -rf {remote_tmp}", check=False, timeout=60)
        adb(serial, "shell", f"run-as {QA_PACKAGE} rm -rf files/w0104-probe", check=False, timeout=60)

    raw_log = proc.stdout + ("\n" + proc.stderr if proc.stderr else "")
    (args.evidence_dir / f"probe-{args.action}.log").write_text(raw_log, encoding="utf-8")

    summary = {
        "device_alias": "device-4k",
        "host_page_size": page_size,
        "package": QA_PACKAGE,
        "action": args.action,
        "returncode": proc.returncode,
        "passed": "INSTRUMENTATION_RESULT: w0104_status=PASS" in raw_log and proc.returncode == 0,
        "base_image_tar_sha256": lock["base_image"]["tar_sha256"],
        "package_count": len(lock["packages"]),
    }
    (args.evidence_dir / f"probe-{args.action}-summary.json").write_text(
        json.dumps(summary, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=True))
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
