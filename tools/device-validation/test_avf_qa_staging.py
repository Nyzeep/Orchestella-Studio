# SPDX-License-Identifier: GPL-3.0-or-later
"""Zero-device tests for QA custom-artifact staging.

Every test runs against an external scratch root and a fake runner; no adb
server, device, VM or user data is touched. The fake runner simulates only the
fixed argv shapes the staging module uses (push, toybox hash/stat, run-as and
exact-name cleanup).
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import unittest
import uuid
import zipfile

sys.dont_write_bytecode = True
MODULE_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(MODULE_DIR))

import avf_qa_staging as staging
from avf_evidence_io import Rejected

SERIAL = "FIXTURESERIAL123"
QA_PACKAGE = "io.github.nyzeep.orchestella.qa.unittest"
INSTANCE_ID = "11111111-2222-4333-8444-555555555555"
TOKEN = "a" * 64


@dataclass
class Result:
    returncode: int
    stdout: str
    stderr: str = ""


class FakeRunner:
    """Executes only the staging argv shapes against a scratch device mirror."""

    def __init__(self, root, scenario="ok"):
        self.root = root
        self.scenario = scenario
        self.remote = root / "remote"
        self.remote.mkdir()
        self.app = root / "app"
        (self.app / "files/avf-qa/custom").mkdir(parents=True, exist_ok=True)
        self.commands = []

    def _redacted_argv(self, argv):
        return [str(value) for value in argv]

    def run(self, argv, *, timeout_seconds=None, allow_failure=False):
        argv = [str(value) for value in argv]
        self.commands.append(argv)
        rest = argv[3:] if len(argv) > 3 and argv[1] == "-s" else argv[1:]
        if rest[:1] == ["push"]:
            if self.scenario == "push-fail":
                return Result(1, "", "")
            local = Path(rest[1])
            remote = Path(rest[2])
            shutil.copyfile(local, self.remote / remote.name)
            return Result(0, "")
        if rest[:1] != ["shell"]:
            return Result(0, "")
        command = rest[1]
        if command.startswith("umask 077; mkdir"):
            return Result(0, "")
        if command.startswith("toybox sha256sum"):
            output = []
            for token in command.split()[2:]:
                path = self.remote / Path(token).name
                if path.is_file():
                    output.append(
                        hashlib.sha256(path.read_bytes()).hexdigest()
                        + "  " + token)
            return Result(0, "\n".join(output))
        if command.startswith("toybox stat"):
            if self.scenario == "seed-size":
                return Result(0, "4095\n")
            return Result(0, "4096\n")
        if "run-as" in command:
            if self.scenario == "run-as-unavailable":
                return Result(1, "", "run-as: package not debuggable")
            if self.scenario == "exists":
                return Result(42, "QA_STAGE_ERROR EXISTS_kernel\n")
            if self.scenario == "hash-mismatch":
                return Result(45, "QA_STAGE_ERROR KERNEL_HASH\n")
            if self.scenario == "root-marker":
                return Result(46, "QA_STAGE_ERROR ROOT_MARKER\n")
            if self.scenario == "copy-fail":
                return Result(43, "QA_STAGE_ERROR COPY_kernel\n")
            destination = self.app / "files/avf-qa/custom"
            shutil.copyfile(self.remote / "kernel", destination / "kernel")
            shutil.copyfile(self.remote / "initrd", destination / "initrd")
            (destination / "root.img").write_bytes(staging.root_seed_bytes())
            return Result(0, "QA_STAGE_OK\n")
        if command.startswith("rm -f"):
            for token in command.replace("rmdir", "").split():
                if token in ("rm", "-f", ";"):
                    continue
                path = Path(token)
                if path.name in ("kernel", "initrd", "root.seed"):
                    (self.remote / path.name).unlink(missing_ok=True)
            return Result(0, "")
        return Result(0, "")


def require_root():
    configured = os.environ.get("AVF_VALIDATION_TEST_ROOT")
    if not configured or not Path(configured).is_absolute():
        raise AssertionError(
            "AVF_VALIDATION_TEST_ROOT must be an explicit absolute external directory")
    return Path(configured) / uuid.uuid4().hex


class StagingTests(unittest.TestCase):
    def setUp(self):
        self.root = require_root()
        self.root.mkdir(parents=True, exist_ok=True)
        self.plan_root = self.root / "plan"
        (self.plan_root / "debian/runtime").mkdir(parents=True)
        (self.plan_root / "private").mkdir()
        self.kernel = b"kernel-fixture-" * 100
        self.initrd = b"initrd-fixture-" * 200
        self.kernel_path = self.plan_root / "debian/runtime/Image"
        self.initrd_path = self.plan_root / "debian/runtime/initramfs-embedded.cpio.gz"
        self.kernel_path.write_bytes(self.kernel)
        self.initrd_path.write_bytes(self.initrd)
        self.plan_path = self.plan_root / "plan.json"
        self.write_plan()
        (self.plan_root / "private/credentials.json").write_text(json.dumps({
            "schemaVersion": 1,
            "instances": [{
                "distro": "debian",
                "instanceId": INSTANCE_ID,
                "bridgeToken": TOKEN,
            }],
        }), encoding="utf-8")

    def write_plan(self, kernel_path="debian/runtime/Image", kernel_hash=None):
        self.plan_path.write_text(json.dumps({
            "schemaVersion": 1,
            "privateCredentials": "private/credentials.json",
            "instances": [{
                "distro": "debian",
                "instanceId": INSTANCE_ID,
                "kernel": {
                    "path": kernel_path,
                    "bytes": len(self.kernel),
                    "sha256": kernel_hash or hashlib.sha256(self.kernel).hexdigest(),
                },
                "initramfs": {
                    "path": "debian/runtime/initramfs-embedded.cpio.gz",
                    "bytes": len(self.initrd),
                    "sha256": hashlib.sha256(self.initrd).hexdigest(),
                },
                "rawBytes": 1 << 30,
            }],
        }), encoding="utf-8")

    def stage(self, scenario="ok", root_bytes=None):
        plan = staging.load_plan(self.plan_path, "debian", root_bytes=root_bytes)
        runner = FakeRunner(self.root, scenario)
        receipt = staging.stage(runner, "adb", SERIAL, QA_PACKAGE, plan)
        return runner, receipt

    def test_success_records_sizes_and_hashes_without_secrets(self):
        runner, receipt = self.stage()
        self.assertEqual(receipt["status"], "staged")
        self.assertEqual(receipt["artifacts"]["kernel"]["sha256"],
                         hashlib.sha256(self.kernel).hexdigest())
        self.assertEqual(receipt["artifacts"]["initrd"]["sha256"],
                         hashlib.sha256(self.initrd).hexdigest())
        self.assertEqual(receipt["artifacts"]["root"]["size"], 1 << 30)
        self.assertEqual(receipt["artifacts"]["root"]["seed_size"], 4096)
        self.assertTrue(receipt["temporary_staging_cleaned"])
        serialized = json.dumps(receipt)
        self.assertNotIn(TOKEN, serialized)
        self.assertNotIn(SERIAL, serialized)
        self.assertNotIn(str(self.plan_root), serialized)
        run_as = [command for command in runner.commands if "run-as" in " ".join(command)]
        self.assertEqual(len(run_as), 1)
        script = run_as[0][-1]
        self.assertIn("set -C", script)
        self.assertIn("toybox truncate -s 1073741824", script)
        self.assertIn("QA_STAGE_OK", script)
        self.assertIn("ROOT_MARKER", script)

    def test_missing_artifact_is_rejected_before_any_push(self):
        self.kernel_path.unlink()
        with self.assertRaises(Rejected) as caught:
            staging.load_plan(self.plan_path, "debian")
        self.assertEqual(str(caught.exception), "CUSTOM_ARTIFACTS_MISSING")

    def test_hash_mismatch_is_rejected(self):
        self.write_plan(kernel_hash="0" * 64)
        with self.assertRaises(Rejected) as caught:
            staging.load_plan(self.plan_path, "debian")
        self.assertEqual(str(caught.exception), "CUSTOM_ARTIFACT_HASH_MISMATCH")

    def test_path_escape_is_rejected(self):
        self.write_plan(kernel_path="../Image")
        with self.assertRaises(Rejected) as caught:
            staging.load_plan(self.plan_path, "debian")
        self.assertEqual(str(caught.exception), "CUSTOM_ARTIFACT_PATH_REJECTED")

    def test_symlink_or_junction_is_rejected(self):
        target = self.root / "junction-target"
        target.mkdir()
        shutil.copyfile(self.kernel_path, target / "Image")
        link = self.plan_root / "linked"
        created = False
        try:
            os.symlink(target, link, target_is_directory=True)
            created = True
        except (OSError, NotImplementedError):
            if os.name == "nt":
                result = subprocess.run(
                    ["cmd", "/c", "mklink", "/J", str(link), str(target)],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    shell=False)
                created = result.returncode == 0
        if not created:
            self.skipTest("symlink and junction creation are unavailable")
        self.write_plan(kernel_path="linked/Image")
        with self.assertRaises(Rejected) as caught:
            staging.load_plan(self.plan_path, "debian")
        self.assertEqual(str(caught.exception), "CUSTOM_ARTIFACT_SYMLINK_REJECTED")

    def test_existing_destination_is_never_overwritten(self):
        destination = self.root / "app/files/avf-qa/custom/kernel"
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(b"pre-existing")
        with self.assertRaises(Rejected) as caught:
            self.stage("exists")
        self.assertEqual(str(caught.exception), "CUSTOM_STAGING_DESTINATION_EXISTS")
        self.assertEqual(destination.read_bytes(), b"pre-existing")

    def test_run_as_unavailable_is_typed(self):
        with self.assertRaises(Rejected) as caught:
            self.stage("run-as-unavailable")
        self.assertEqual(str(caught.exception), "CUSTOM_STAGING_RUN_AS_UNAVAILABLE")

    def test_device_hash_mismatch_is_typed(self):
        with self.assertRaises(Rejected) as caught:
            self.stage("hash-mismatch")
        self.assertEqual(str(caught.exception), "CUSTOM_STAGING_HASH_MISMATCH")

    def test_root_marker_failure_is_typed(self):
        with self.assertRaises(Rejected) as caught:
            self.stage("root-marker")
        self.assertEqual(str(caught.exception), "CUSTOM_STAGING_ROOT_REJECTED")

    def test_cleanup_is_exact_and_keeps_other_app_files(self):
        keep = self.root / "app/files/avf-qa/custom/keep.me"
        keep.parent.mkdir(parents=True, exist_ok=True)
        keep.write_bytes(b"keep")
        runner, _ = self.stage()
        cleanup = runner.commands[-1][-1]
        self.assertNotIn("rm -rf", cleanup)
        self.assertIn("kernel", cleanup)
        self.assertIn("initrd", cleanup)
        self.assertIn("root.seed", cleanup)
        self.assertIn("rmdir", cleanup)
        self.assertTrue(keep.is_file())

    def test_root_size_override_is_recorded(self):
        _, receipt = self.stage(root_bytes=2 << 30)
        self.assertEqual(receipt["root_bytes"], 2 << 30)
        self.assertEqual(receipt["artifacts"]["root"]["size"], 2 << 30)

    def test_apk_trust_anchor_must_match_kernel(self):
        good = self.root / "qa.apk"
        with zipfile.ZipFile(good, "w") as archive:
            archive.writestr(
                "assets/runtime-artifacts-v1.sha256",
                hashlib.sha256(self.kernel).hexdigest() + "  Image\n")
            archive.writestr("assets/Image", self.kernel)
        staging.load_plan(self.plan_path, "debian", apk_path=good)
        bad = self.root / "bad.apk"
        with zipfile.ZipFile(bad, "w") as archive:
            archive.writestr(
                "assets/runtime-artifacts-v1.sha256", "0" * 64 + "  Image\n")
            archive.writestr("assets/Image", self.kernel)
        with self.assertRaises(Rejected) as caught:
            staging.load_plan(self.plan_path, "debian", apk_path=bad)
        self.assertEqual(str(caught.exception), "CUSTOM_APK_TRUST_ANCHOR_MISMATCH")

    def test_bridge_token_missing_is_rejected(self):
        (self.plan_root / "private/credentials.json").write_text(json.dumps({
            "schemaVersion": 1,
            "instances": [{"distro": "debian", "instanceId": INSTANCE_ID,
                           "bridgeToken": "short"}],
        }), encoding="utf-8")
        with self.assertRaises(Rejected) as caught:
            staging.load_plan(self.plan_path, "debian")
        self.assertEqual(str(caught.exception), "CUSTOM_BRIDGE_TOKEN_MISSING")

    def test_root_seed_is_the_provisioning_marker(self):
        seed = staging.root_seed_bytes()
        self.assertEqual(len(seed), 4096)
        self.assertEqual(seed[:32], b"ORCHESTELLA_EMPTY_ROOT_VOLUME_V2")
        self.assertEqual(staging.root_seed_sha256(),
                         hashlib.sha256(seed).hexdigest())


if __name__ == "__main__":
    unittest.main(verbosity=2)
