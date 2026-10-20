# SPDX-License-Identifier: GPL-3.0-or-later
"""QA-only staging for the AVF custom-Linux launch path.

The device-side QA probe reads exactly three app-private files:

    files/avf-qa/custom/kernel
    files/avf-qa/custom/initrd
    files/avf-qa/custom/root.img

This module prepares those files from an already verified QA batch plan. It
never downloads anything and never reads a user instance, root volume, export,
backup or credential. The kernel and embedded initramfs are the same bytes the
QEMU strategy consumes; their sizes and SHA-256 values come from the QA plan
(the kernel additionally has to match the QA APK's runtime-artifact manifest
when an APK is supplied). The root disk is a fresh sparse file created on the
device from a 4096-byte provisioning marker; no multi-gigabyte image is pushed.

The transport mirror is a fresh /data/local/tmp/avf-qa-stage-<token> directory
that is used only as an adb staging area. A single run-as invocation copies the
three files into the QA app's private storage with noclobber semantics, verifies
the hashes on the device, and only then reports success. A failure removes only
the files that invocation created; it never deletes an app-private disk, a
user file or a pre-existing artifact.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import re
import secrets
import shlex
import sys
import tempfile
from pathlib import Path, PurePosixPath
import zipfile

sys.dont_write_bytecode = True

from avf_evidence_io import EvidenceStore, Rejected, SafeParser, parse_json

MARKER = b"ORCHESTELLA_EMPTY_ROOT_VOLUME_V2\n"
SEED_BYTES = 4096
QA_CUSTOM_DIR = "files/avf-qa/custom"
TMP_PREFIX = "/data/local/tmp/avf-qa-stage-"
DEFAULT_ROOT_BYTES = 8 * 1024 * 1024 * 1024
MIN_ROOT_BYTES = 1 * 1024 * 1024 * 1024
MAX_ROOT_BYTES = 64 * 1024 * 1024 * 1024
MAX_INPUT_BYTES = 2 * 1024 * 1024
HEX64 = re.compile(r"[0-9a-f]{64}")
DISTRO_RE = re.compile(r"[a-z][a-z0-9-]{0,31}")
PACKAGE_RE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+")
ARTIFACT_NAME_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
MANIFEST_PATH = "assets/runtime-artifacts-v1.sha256"


@dataclass(frozen=True)
class Artifact:
    """One verified host artifact and its fixed device-side name."""

    name: str
    local: Path
    size: int
    sha256: str


@dataclass(frozen=True)
class StagingPlan:
    """Verified QA plan data. ``bridge_token`` is never serialized."""

    distro: str
    plan_sha256: str
    kernel: Artifact
    initrd: Artifact
    root_bytes: int
    bridge_token: str


def root_seed_bytes():
    return MARKER + b"\0" * (SEED_BYTES - len(MARKER))


def root_seed_sha256():
    return hashlib.sha256(root_seed_bytes()).hexdigest()


def _sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _no_links(path):
    path = Path(path)
    for cursor in (path, *path.parents):
        if cursor.is_symlink() or cursor.is_junction():
            raise Rejected("CUSTOM_ARTIFACT_SYMLINK_REJECTED")


def _regular_file(root, relative):
    if not isinstance(relative, str) or "\\" in relative or not relative:
        raise Rejected("CUSTOM_ARTIFACT_PATH_REJECTED")
    pure = PurePosixPath(relative)
    if pure.is_absolute() or any(part in ("", ".", "..") for part in relative.split("/")):
        raise Rejected("CUSTOM_ARTIFACT_PATH_REJECTED")
    path = Path(root).joinpath(*pure.parts)
    _no_links(path)
    try:
        resolved = path.resolve(strict=True)
        root_resolved = Path(root).resolve(strict=True)
    except OSError:
        raise Rejected("CUSTOM_ARTIFACTS_MISSING") from None
    if not resolved.is_relative_to(root_resolved) or not resolved.is_file():
        raise Rejected("CUSTOM_ARTIFACTS_MISSING")
    return resolved


def _load_json(path):
    _no_links(path)
    try:
        data = Path(path).read_bytes()
    except OSError:
        raise Rejected("CUSTOM_STAGING_PLAN_REJECTED") from None
    if len(data) > MAX_INPUT_BYTES:
        raise Rejected("CUSTOM_STAGING_PLAN_REJECTED")
    try:
        return parse_json(data)
    except Rejected:
        raise Rejected("CUSTOM_STAGING_PLAN_REJECTED") from None


def _artifact(root, info, name):
    if not isinstance(info, dict):
        raise Rejected("CUSTOM_STAGING_PLAN_REJECTED")
    relative = info.get("path")
    size = info.get("bytes")
    digest = info.get("sha256")
    if (not isinstance(relative, str) or type(size) is not int or size <= 0
            or not isinstance(digest, str) or not HEX64.fullmatch(digest)):
        raise Rejected("CUSTOM_STAGING_PLAN_REJECTED")
    local = _regular_file(root, relative)
    if local.stat().st_size != size or _sha256_file(local) != digest:
        raise Rejected("CUSTOM_ARTIFACT_HASH_MISMATCH")
    return Artifact(name=name, local=local, size=size, sha256=digest)


def _plan_row(plan, distro):
    if not isinstance(plan, dict) or plan.get("schemaVersion") != 1:
        raise Rejected("CUSTOM_STAGING_PLAN_REJECTED")
    rows = plan.get("instances")
    if not isinstance(rows, list):
        raise Rejected("CUSTOM_STAGING_PLAN_REJECTED")
    matches = [row for row in rows if isinstance(row, dict) and row.get("distro") == distro]
    if len(matches) != 1:
        raise Rejected("CUSTOM_STAGING_PLAN_REJECTED")
    return matches[0]


def _apk_image_hash(apk_path):
    apk = Path(apk_path)
    if not apk.is_absolute():
        raise Rejected("CUSTOM_APK_TRUST_ANCHOR_MISMATCH")
    _no_links(apk)
    if not apk.is_file():
        raise Rejected("CUSTOM_APK_TRUST_ANCHOR_MISMATCH")
    try:
        with zipfile.ZipFile(apk) as archive:
            names = [entry.filename for entry in archive.infolist()]
            if names.count(MANIFEST_PATH) != 1:
                raise Rejected("CUSTOM_APK_TRUST_ANCHOR_MISMATCH")
            trusted = None
            for line in archive.read(MANIFEST_PATH).decode("ascii").splitlines():
                match = re.fullmatch(r"([0-9a-f]{64})  ([^\r\n]+)", line)
                if match is None:
                    raise Rejected("CUSTOM_APK_TRUST_ANCHOR_MISMATCH")
                if match.group(2) == "Image":
                    trusted = match.group(1)
            if trusted is None or names.count("assets/Image") != 1:
                raise Rejected("CUSTOM_APK_TRUST_ANCHOR_MISMATCH")
            digest = hashlib.sha256()
            with archive.open("assets/Image") as stream:
                while True:
                    chunk = stream.read(1024 * 1024)
                    if not chunk:
                        break
                    digest.update(chunk)
            if digest.hexdigest() != trusted:
                raise Rejected("CUSTOM_APK_TRUST_ANCHOR_MISMATCH")
            return trusted
    except (OSError, zipfile.BadZipFile, UnicodeError):
        raise Rejected("CUSTOM_APK_TRUST_ANCHOR_MISMATCH") from None


def _bridge_token(plan_root, plan, row):
    relative = plan.get("privateCredentials")
    if relative != "private/credentials.json":
        raise Rejected("CUSTOM_BRIDGE_TOKEN_MISSING")
    credentials_path = _regular_file(plan_root, relative)
    credentials = _load_json(credentials_path)
    if not isinstance(credentials, dict) or credentials.get("schemaVersion") != 1:
        raise Rejected("CUSTOM_BRIDGE_TOKEN_MISSING")
    instances = credentials.get("instances")
    if not isinstance(instances, list):
        raise Rejected("CUSTOM_BRIDGE_TOKEN_MISSING")
    instance_id = row.get("instanceId")
    if not isinstance(instance_id, str):
        raise Rejected("CUSTOM_BRIDGE_TOKEN_MISSING")
    matches = [entry for entry in instances
               if isinstance(entry, dict) and entry.get("instanceId") == instance_id]
    if len(matches) != 1 or matches[0].get("distro") != row.get("distro"):
        raise Rejected("CUSTOM_BRIDGE_TOKEN_MISSING")
    token = matches[0].get("bridgeToken")
    if not isinstance(token, str) or not HEX64.fullmatch(token):
        raise Rejected("CUSTOM_BRIDGE_TOKEN_MISSING")
    return token


def load_plan(plan_path, distro, *, apk_path=None, root_bytes=None):
    """Loads and verifies one QA batch row; no device is contacted."""
    if not isinstance(distro, str) or not DISTRO_RE.fullmatch(distro):
        raise Rejected("CUSTOM_STAGING_PLAN_REJECTED")
    plan_file = Path(plan_path)
    if not plan_file.is_absolute():
        raise Rejected("CUSTOM_STAGING_PLAN_REJECTED")
    _no_links(plan_file)
    if not plan_file.is_file():
        raise Rejected("CUSTOM_STAGING_PLAN_REJECTED")
    plan_file = plan_file.resolve(strict=True)
    plan_root = plan_file.parent
    plan = _load_json(plan_file)
    row = _plan_row(plan, distro)
    kernel = _artifact(plan_root, row.get("kernel"), "kernel")
    initrd = _artifact(plan_root, row.get("initramfs"), "initrd")
    if apk_path is not None:
        trusted = _apk_image_hash(apk_path)
        if trusted != kernel.sha256:
            raise Rejected("CUSTOM_APK_TRUST_ANCHOR_MISMATCH")
    if root_bytes is None:
        root_bytes = row.get("rawBytes", DEFAULT_ROOT_BYTES)
    if (type(root_bytes) is not int or not MIN_ROOT_BYTES <= root_bytes <= MAX_ROOT_BYTES
            or root_bytes % 4096 != 0):
        raise Rejected("CUSTOM_ROOT_SIZE_REJECTED")
    token = _bridge_token(plan_root, plan, row)
    return StagingPlan(
        distro=distro,
        plan_sha256=_sha256_file(plan_file),
        kernel=kernel,
        initrd=initrd,
        root_bytes=root_bytes,
        bridge_token=token,
    )


def _announce(runner, argv):
    printer = getattr(runner, "_redacted_argv", None)
    if printer is not None:
        try:
            print("COMMAND: " + " ".join(printer(argv)), file=sys.stderr)
        except Exception:
            pass


def _run(runner, argv, *, timeout_seconds, allow_failure=False):
    _announce(runner, argv)
    return runner.run(argv, timeout_seconds=timeout_seconds, allow_failure=allow_failure)


def _q(value):
    return shlex.quote(str(value))


def _remote_hashes(runner, adb, serial, paths):
    command = "toybox sha256sum " + " ".join(_q(path) for path in paths)
    result = _run(runner, [adb, "-s", serial, "shell", command],
                  timeout_seconds=30, allow_failure=True)
    if result.returncode != 0:
        raise Rejected("CUSTOM_STAGING_HASH_QUERY_FAILED")
    seen = {}
    for line in result.stdout.splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})\s+\*?(.+)", line.strip())
        if match is not None:
            seen[Path(match.group(2)).name] = match.group(1)
    return seen


def _remote_size(runner, adb, serial, path):
    result = _run(runner, [adb, "-s", serial, "shell", "toybox stat -c %s " + _q(path)],
                  timeout_seconds=30, allow_failure=True)
    try:
        return int(result.stdout.strip())
    except ValueError:
        raise Rejected("CUSTOM_STAGING_HASH_QUERY_FAILED") from None


def _stage_script(plan, remote_dir):
    src = {
        "kernel": remote_dir + "/kernel",
        "initrd": remote_dir + "/initrd",
        "root.img": remote_dir + "/root.seed",
    }
    return f"""set -eu
umask 077
[ -d files ] && [ ! -L files ] || {{ echo QA_STAGE_ERROR FILES; exit 41; }}
if [ ! -e files/avf-qa ]; then mkdir files/avf-qa; fi
[ -d files/avf-qa ] && [ ! -L files/avf-qa ] || {{ echo QA_STAGE_ERROR QA_DIR; exit 41; }}
if [ ! -e files/avf-qa/custom ]; then mkdir files/avf-qa/custom; fi
[ -d files/avf-qa/custom ] && [ ! -L files/avf-qa/custom ] || {{ echo QA_STAGE_ERROR CUSTOM_DIR; exit 41; }}
created=''
cleanup() {{
  rc=$?
  if [ "$rc" -ne 0 ]; then
    for created_file in $created; do rm -f "$created_file"; done
  fi
}}
trap cleanup EXIT
copy_one() {{
  name=$1
  source=$2
  destination=files/avf-qa/custom/$1
  [ ! -e "$destination" ] && [ ! -L "$destination" ] || {{ echo QA_STAGE_ERROR EXISTS_$name; exit 42; }}
  (set -C; cat "$source" > "$destination") || {{ echo QA_STAGE_ERROR COPY_$name; exit 43; }}
  created="$created $destination"
  chmod 0600 "$destination"
}}
copy_one kernel {_q(src['kernel'])}
copy_one initrd {_q(src['initrd'])}
copy_one root.img {_q(src['root.img'])}
toybox truncate -s {plan.root_bytes} files/avf-qa/custom/root.img || {{ echo QA_STAGE_ERROR TRUNCATE; exit 44; }}
[ "$(toybox stat -c %s files/avf-qa/custom/root.img)" = "{plan.root_bytes}" ] || {{ echo QA_STAGE_ERROR ROOT_SIZE; exit 44; }}
[ "$(toybox sha256sum files/avf-qa/custom/kernel | cut -d ' ' -f 1)" = "{plan.kernel.sha256}" ] || {{ echo QA_STAGE_ERROR KERNEL_HASH; exit 45; }}
[ "$(toybox sha256sum files/avf-qa/custom/initrd | cut -d ' ' -f 1)" = "{plan.initrd.sha256}" ] || {{ echo QA_STAGE_ERROR INITRD_HASH; exit 45; }}
[ "$(dd if=files/avf-qa/custom/root.img bs=1 count=32 2>/dev/null)" = "ORCHESTELLA_EMPTY_ROOT_VOLUME_V2" ] || {{ echo QA_STAGE_ERROR ROOT_MARKER; exit 46; }}
echo QA_STAGE_OK
"""


def _map_staging_output(stdout):
    match = re.search(r"QA_STAGE_ERROR ([A-Z_]+)", stdout)
    if match is None:
        return "CUSTOM_STAGING_FAILED"
    token = match.group(1)
    if token.startswith("EXISTS"):
        return "CUSTOM_STAGING_DESTINATION_EXISTS"
    if token.endswith("HASH"):
        return "CUSTOM_STAGING_HASH_MISMATCH"
    if token in ("TRUNCATE", "ROOT_SIZE", "ROOT_MARKER"):
        return "CUSTOM_STAGING_ROOT_REJECTED"
    if token in ("FILES", "QA_DIR", "CUSTOM_DIR"):
        return "CUSTOM_STAGING_APP_DIRECTORY_REJECTED"
    if token.startswith("COPY"):
        return "CUSTOM_STAGING_COPY_FAILED"
    return "CUSTOM_STAGING_FAILED"


def stage(runner, adb, serial, qa_package, plan):
    """Copies the plan's artifacts to QA-private storage; returns a receipt.

    The receipt contains no serial, no bridge token and no local path.
    Raises Rejected with a fixed reason code on any failure.
    """
    if not isinstance(qa_package, str) or not PACKAGE_RE.fullmatch(qa_package) \
            or ".qa." not in qa_package:
        raise Rejected("QA_PACKAGE_REQUIRED")
    token = secrets.token_hex(8)
    remote_dir = TMP_PREFIX + token
    cleaned = False
    seed_path = None
    try:
        with tempfile.TemporaryDirectory(prefix="avf-qa-staging-") as work:
            seed_path = Path(work) / "root.seed"
            seed_path.write_bytes(root_seed_bytes())
            mkdir = _run(
                runner,
                [adb, "-s", serial, "shell",
                 "umask 077; mkdir " + _q(remote_dir) + " && chmod 0700 " + _q(remote_dir)],
                timeout_seconds=30, allow_failure=True)
            if mkdir.returncode != 0:
                raise Rejected("CUSTOM_STAGING_DIRECTORY_FAILED")
            uploads = (
                (plan.kernel.local, remote_dir + "/kernel", "kernel"),
                (plan.initrd.local, remote_dir + "/initrd", "initrd"),
                (seed_path, remote_dir + "/root.seed", "root.seed"),
            )
            for local, remote, _ in uploads:
                pushed = _run(runner, [adb, "-s", serial, "push", str(local), remote],
                              timeout_seconds=300, allow_failure=True)
                if pushed.returncode != 0:
                    raise Rejected("CUSTOM_STAGING_PUSH_FAILED")
            observed = _remote_hashes(
                runner, adb, serial, [remote for _, remote, _ in uploads])
            expected = {
                "kernel": plan.kernel.sha256,
                "initrd": plan.initrd.sha256,
                "root.seed": root_seed_sha256(),
            }
            if observed != expected:
                raise Rejected("CUSTOM_STAGING_HASH_MISMATCH")
            if _remote_size(runner, adb, serial, remote_dir + "/root.seed") != SEED_BYTES:
                raise Rejected("CUSTOM_STAGING_ROOT_REJECTED")
            script = _stage_script(plan, remote_dir)
            remote_command = shlex.join(
                ["run-as", qa_package, "/system/bin/sh", "-c", script])
            result = _run(runner, [adb, "-s", serial, "shell", remote_command],
                          timeout_seconds=120, allow_failure=True)
            if "QA_STAGE_OK" not in result.stdout or result.returncode != 0:
                if "QA_STAGE_ERROR" in result.stdout:
                    raise Rejected(_map_staging_output(result.stdout))
                raise Rejected("CUSTOM_STAGING_RUN_AS_UNAVAILABLE")
    finally:
        cleanup_command = (
            "rm -f " + " ".join(_q(remote_dir + "/" + name)
                                for name in ("kernel", "initrd", "root.seed"))
            + "; rmdir " + _q(remote_dir))
        try:
            result = _run(runner, [adb, "-s", serial, "shell", cleanup_command],
                          timeout_seconds=15, allow_failure=True)
            cleaned = result.returncode == 0
        except Exception:
            cleaned = False
    return {
        "schema_version": 1,
        "kind": "avf_qa_custom_staging",
        "status": "staged",
        "distro": plan.distro,
        "plan_sha256": plan.plan_sha256,
        "root_bytes": plan.root_bytes,
        "artifacts": {
            "kernel": {
                "size": plan.kernel.size,
                "sha256": plan.kernel.sha256,
                "destination": QA_CUSTOM_DIR + "/kernel",
            },
            "initrd": {
                "size": plan.initrd.size,
                "sha256": plan.initrd.sha256,
                "destination": QA_CUSTOM_DIR + "/initrd",
            },
            "root": {
                "seed_size": SEED_BYTES,
                "seed_sha256": root_seed_sha256(),
                "size": plan.root_bytes,
                "destination": QA_CUSTOM_DIR + "/root.img",
            },
        },
        "staging_token_sha256": hashlib.sha256(token.encode("ascii")).hexdigest(),
        "temporary_staging_cleaned": cleaned,
    }


def _now_stamp():
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")


def main(argv=None):
    parser = SafeParser(prog="avf_qa_staging", description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--device", required=True)
    parser.add_argument("--qa-package", required=True)
    parser.add_argument("--plan", required=True)
    parser.add_argument("--distro", required=True)
    parser.add_argument("--apk")
    parser.add_argument("--root-bytes", type=int)
    parser.add_argument("--out", required=True)
    parser.add_argument("--adb-timeout", type=float, default=30.0)
    parser.add_argument("--deadline-seconds", type=float, default=1800.0)
    args = parser.parse_args(argv)
    try:
        if not Path(args.out).is_absolute():
            raise Rejected("ROOT_REJECTED")
        if not 0 < args.adb_timeout <= 600 or not 0 < args.deadline_seconds <= 7200:
            raise Rejected("TIMEOUT_REJECTED")
        plan = load_plan(args.plan, args.distro, apk_path=args.apk,
                         root_bytes=args.root_bytes)
        import avf_device_mode as device
        adb = device.resolve_adb(args.adb)
        runner = device.AdbRunner(adb, args.device, timeout_seconds=args.adb_timeout,
                                  deadline_seconds=args.deadline_seconds)
        receipt = stage(runner, adb, args.device, args.qa_package, plan)
        store = EvidenceStore(args.out, args.out)
        saved = store.save_json("avf-qa-staging-" + _now_stamp() + ".json", receipt)
        print(json.dumps({
            "status": "STAGED",
            "distro": receipt["distro"],
            "kernel_sha256": receipt["artifacts"]["kernel"]["sha256"],
            "initrd_sha256": receipt["artifacts"]["initrd"]["sha256"],
            "root_bytes": receipt["root_bytes"],
            "receipt_json": str(store.output_path(saved["path"])),
        }, sort_keys=True))
        return 0
    except (Rejected, OSError) as error:
        print(json.dumps({"error": str(error) if isinstance(error, Rejected) else "IO_ERROR"}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
