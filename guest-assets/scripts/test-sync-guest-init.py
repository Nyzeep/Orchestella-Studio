#!/usr/bin/env python3
"""Exercise the guest-init CLI in isolated copies, never in the source checkout."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

assets = Path(__file__).resolve().parent.parent
work_value = os.environ.get("GUEST_RUNTIME_TEST_ROOT")
if not work_value or not Path(work_value).is_dir():
    raise SystemExit("GUEST_RUNTIME_TEST_ROOT must be an existing approved external evidence directory")
work = Path(work_value).resolve()
if work.is_relative_to(assets.parent):
    raise SystemExit("GUEST_RUNTIME_TEST_ROOT must be outside the source checkout")

# Only the renderer's explicit inputs are copied; no APK, volume, credentials or Git history.
inputs = [
    "scripts/sync-guest-init.py", "initramfs/init", "initramfs/runtime-functions.sh",
    "initramfs/runtime-entry.sh", "shell/20-orchestella-terminal-integration.sh",
    "shell/orchestella-prompt.sh", "scripts/orchestella-guest-provision.sh",
    "features/profilectl", "features/profilectl.py", "features/inputs.json",
    "features/bootstrap-python.sh", "features/feature-session.py", "features/rootless-probe.py",
    "display/display-relay.py",
]


class GuestInitCliTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="guest-init-sync-", dir=work))
        self.assets = self.root / "guest-assets"
        for relative in inputs:
            target = self.assets / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(assets / relative, target)
        self.canonical = self.assets / "initramfs/init"
        self.resource = self.root / "app/src/main/resources/guest_init"

    def snapshot_inputs(self):
        return {relative: (self.assets / relative).read_bytes() for relative in inputs}

    def snapshot_tree(self):
        return {str(path.relative_to(self.root)): path.read_bytes() if path.is_file() else None
                for path in self.root.rglob("*")}

    def cli(self, *arguments):
        return subprocess.run(
            [sys.executable, "-B", str(self.assets / "scripts/sync-guest-init.py"), *arguments],
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=15,
            env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"),
        )

    def test_prepare_clean_checkout_without_resource_parent(self):
        before = self.snapshot_inputs()
        self.assertFalse(self.resource.parent.exists())
        result = self.cli("--prepare-resource")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.resource.read_bytes(), before["initramfs/init"])
        self.assertEqual(self.snapshot_inputs(), before)
        checked = self.cli("--check")
        self.assertEqual(checked.returncode, 0, checked.stderr)

    def test_check_rejects_resource_crlf_without_writes(self):
        self.resource.parent.mkdir(parents=True)
        payload = self.canonical.read_bytes().replace(b"\n", b"\r\n")
        self.resource.write_bytes(payload)
        before = self.snapshot_inputs()
        result = self.cli("--check")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertEqual(self.resource.read_bytes(), payload)
        self.assertEqual(self.snapshot_inputs(), before)

    def test_invalid_modes_refuse_before_any_write(self):
        self.resource.parent.mkdir(parents=True)
        for arguments in [("--prepare-resource", "--check"), ("--prepare-resorce",)]:
            with self.subTest(arguments=arguments):
                self.resource.write_bytes(b"previous generated template must survive\n")
                before = self.snapshot_inputs()
                result = self.cli(*arguments)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertEqual(self.resource.read_bytes(), b"previous generated template must survive\n")
                self.assertEqual(self.snapshot_inputs(), before)

    def test_canonical_drift_refuses_prepare_and_check_without_any_write(self):
        original = self.canonical.read_bytes()
        marker = b"# ORCHESTELLA_RUNTIME_MODULES_BEGIN\n"
        drifted = original.replace(marker, marker + b"# synthetic embedded drift\n", 1)
        self.assertNotEqual(drifted, original)
        for payload in [drifted, original.replace(b"\n", b"\r\n")]:
            with self.subTest(crlf=payload != drifted):
                self.canonical.write_bytes(payload)
                before = self.snapshot_tree()
                for mode in ["--prepare-resource", "--check"]:
                    result = self.cli(mode)
                    self.assertNotEqual(result.returncode, 0, result.stdout)
                    self.assertEqual(self.snapshot_tree(), before)
                self.assertFalse(self.resource.parent.exists())

    def test_changed_or_missing_module_preserves_previous_template(self):
        self.resource.parent.mkdir(parents=True)
        self.resource.write_bytes(self.canonical.read_bytes())
        module = self.assets / "initramfs/runtime-entry.sh"
        original = module.read_bytes()
        module.write_bytes(original + b"\n# synthetic module update\n")
        before = self.snapshot_tree()
        result = self.cli("--prepare-resource")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertEqual(self.snapshot_tree(), before)
        module.unlink()  # Only this test's newly created input, never a source asset.
        before = self.snapshot_tree()
        result = self.cli("--prepare-resource")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertEqual(self.snapshot_tree(), before)

    def test_check_reports_missing_or_stale_resource_without_repair(self):
        before = self.snapshot_tree()
        result = self.cli("--check")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("APK template is missing", result.stderr)
        self.assertEqual(self.snapshot_tree(), before)
        self.resource.parent.mkdir(parents=True)
        self.resource.write_bytes(b"stale generated content\n")
        before = self.snapshot_tree()
        result = self.cli("--check")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.snapshot_tree(), before)

    def test_prepare_replaces_stale_template_and_is_byte_idempotent(self):
        self.resource.parent.mkdir(parents=True)
        self.resource.write_bytes(b"stale template\r\n")
        before = self.snapshot_inputs()
        first = self.cli("--prepare-resource")
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertEqual(self.resource.read_bytes(), before["initramfs/init"])
        tree = self.snapshot_tree()
        second = self.cli("--prepare-resource")
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(self.snapshot_tree(), tree)
        self.assertEqual(self.snapshot_inputs(), before)
        self.assertEqual(self.cli("--check").returncode, 0)

    def test_failed_publish_preserves_existing_output_and_removes_temporary(self):
        # A nonempty directory cannot be atomically replaced by a file on Linux or Windows.
        self.resource.mkdir(parents=True)
        (self.resource / "existing-output").write_bytes(b"must survive failed publish\n")
        before = self.snapshot_tree()
        result = self.cli("--prepare-resource")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.snapshot_tree(), before)

    def test_explicit_source_update_still_synchronizes_module_changes(self):
        self.resource.parent.mkdir(parents=True)
        original = self.canonical.read_bytes()
        module = self.assets / "initramfs/runtime-entry.sh"
        module.write_bytes(module.read_bytes() + b"\n# explicit source maintenance\n")
        result = self.cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotEqual(self.canonical.read_bytes(), original)
        self.assertEqual(self.resource.read_bytes(), self.canonical.read_bytes())
        checked = self.cli("--check")
        self.assertEqual(checked.returncode, 0, checked.stderr)

    def test_regression_requires_an_existing_external_work_root(self):
        absent = self.root / "must-not-be-created"
        for value in [None, str(absent), str(assets)]:
            with self.subTest(work_root=value):
                environment = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
                environment.pop("GUEST_RUNTIME_TEST_ROOT", None)
                if value is not None:
                    environment["GUEST_RUNTIME_TEST_ROOT"] = value
                result = subprocess.run(
                    [sys.executable, "-B", str(Path(__file__).resolve())],
                    capture_output=True, text=True, timeout=15, env=environment)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("GUEST_RUNTIME_TEST_ROOT", result.stderr)
                self.assertFalse(absent.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
