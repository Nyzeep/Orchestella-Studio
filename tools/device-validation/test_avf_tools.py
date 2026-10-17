# SPDX-License-Identifier: GPL-3.0-or-later
"""Source-only AVF tool tests; explicit external scratch root, never a device."""
from __future__ import annotations

from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import sys
import subprocess
import unittest
import uuid

sys.dont_write_bytecode = True
MODULE_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(MODULE_DIR))

from avf_evidence_io import EvidenceStore, Rejected, json_bytes
import avf_test_fixtures as fixtures


class AvfRuntimeTests(unittest.TestCase):
    def test_unsupported_runtime_rejected_at_public_entries_without_writes(self):
        configured = Path(os.environ["AVF_VALIDATION_TEST_ROOT"])
        evidence = EvidenceStore(configured, configured)
        bootstrap = r'''import pathlib, runpy, sys
mode, entry, *arguments = sys.argv[1:]
if mode == "missing-junction":
    delattr(pathlib.Path, "is_junction")
else:
    sys.version_info = (3, 11, 9, "final", 0)
sys.path.insert(0, str(pathlib.Path(entry).resolve().parent))
sys.argv = [entry, *arguments]
if pathlib.Path(entry).name == "avf_evidence_io.py":
    from avf_evidence_io import EvidenceStore, Rejected
    import json
    try:
        EvidenceStore(arguments[0], arguments[1]).save_json("result.json", {"kind": "synthetic"})
    except Rejected as error:
        print(json.dumps({"error": str(error)}))
        sys.exit(2)
else:
    runpy.run_path(entry, run_name="__main__")
'''
        for mode in ("missing-junction", "old-python"):
            for entry in ("avf_evidence_io.py", "avf_benchmark.py", "avf_preflight.py"):
                with self.subTest(mode=mode, entry=entry):
                    attempt = uuid.uuid4().hex
                    blocked = configured / ("blocked-" + attempt)
                    inputs, outputs = blocked / "inputs", blocked / "outputs"
                    if entry == "avf_evidence_io.py":
                        arguments = [str(inputs), str(outputs)]
                    elif entry == "avf_benchmark.py":
                        arguments = ["--evidence-root", str(inputs), "--output-root", str(outputs),
                                     "--manifest", "manifest.json", "--samples", "samples.json", "--output", "result.json"]
                    else:
                        arguments = ["discover", "--adb", "not-an-adb-path", "--output-root", str(outputs), "--output", "result.json"]
                    process = subprocess.run([sys.executable, "-B", "-c", bootstrap, mode,
                                              str(MODULE_DIR / entry), *arguments], stdin=subprocess.DEVNULL,
                                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10, shell=False)
                    prefix = "runtime-checks/" + attempt + "/"
                    evidence.save(prefix + "stdout.log.txt", process.stdout)
                    evidence.save(prefix + "stderr.log.txt", process.stderr)
                    evidence.save_json(prefix + "execution.json", {"mode": mode, "entry": entry,
                                       "actual_exit": process.returncode, "expected_exit": 2,
                                       "blocked_root_exists": blocked.exists(), "source_only": True})
                    self.assertEqual(process.returncode, 2)
                    self.assertEqual(json.loads(process.stdout), {"error": "RUNTIME_UNSUPPORTED"})
                    self.assertEqual(process.stderr, b"")
                    self.assertFalse(blocked.exists())


class AvfOutputTests(unittest.TestCase):
    def test_external_roots_publish_preserved_json(self):
        configured = os.environ.get("AVF_VALIDATION_TEST_ROOT")
        if not configured or not Path(configured).is_absolute():
            self.fail("AVF_VALIDATION_TEST_ROOT must be an explicit absolute external directory")
        root = Path(configured) / uuid.uuid4().hex
        store = EvidenceStore(root / "inputs", root / "outputs")
        receipt = store.save_json("evidence/result.json", {"kind": "synthetic"})
        original = root / "outputs/evidence/result.json"
        twin = root / "outputs/preservation/evidence/result.json"
        self.assertEqual(json.loads(original.read_bytes()), {"kind": "synthetic"})
        self.assertEqual(original.read_bytes(), twin.read_bytes())
        self.assertTrue(receipt["preserved"])

    def test_preservation_has_independent_bytes(self):
        root = Path(os.environ["AVF_VALIDATION_TEST_ROOT"]) / uuid.uuid4().hex
        store = EvidenceStore(root / "inputs", root / "outputs")
        store.save_json("result.json", {"kind": "synthetic"})
        (root / "outputs/result.json").write_bytes(b"changed synthetic file")
        self.assertEqual(json.loads((root / "outputs/preservation/result.json").read_bytes()), {"kind": "synthetic"})


class AvfCliTests(unittest.TestCase):
    def test_benchmark_rejects_malformed_manifest_without_echo(self):
        root = Path(os.environ["AVF_VALIDATION_TEST_ROOT"]) / uuid.uuid4().hex
        inputs, outputs = root / "inputs", root / "outputs"
        store = EvidenceStore(inputs, inputs)
        store.save_json("manifest.json", {"raw_logcat": "SYNTHETIC_SECRET_SENTINEL"})
        store.save_json("samples.json", {})
        result = subprocess.run([sys.executable, "-B", str(MODULE_DIR / "avf_benchmark.py"),
                                 "--evidence-root", str(inputs), "--output-root", str(outputs),
                                 "--manifest", "manifest.json", "--samples", "samples.json", "--output", "result.json"],
                                cwd=root, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=10, shell=False)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(json.loads(result.stdout), {"error": "MANIFEST_SCHEMA_REJECTED"})
        self.assertNotIn(b"SYNTHETIC_SECRET_SENTINEL", result.stdout + result.stderr)
        self.assertFalse((outputs / "result.json").exists())


class AvfEvidenceCliTests(unittest.TestCase):
    def setUp(self):
        configured = os.environ.get("AVF_VALIDATION_TEST_ROOT")
        if not configured or not Path(configured).is_absolute():
            self.fail("AVF_VALIDATION_TEST_ROOT must be an explicit absolute external directory")
        self.root = Path(configured) / uuid.uuid4().hex
        self.inputs, self.outputs = self.root / "inputs", self.root / "outputs"
        self.store = EvidenceStore(self.inputs, self.inputs)
        self.results = EvidenceStore(self.outputs, self.outputs)

    def cli(self, extra=None):
        command = [sys.executable, "-B", str(MODULE_DIR / "avf_benchmark.py"),
                   "--evidence-root", str(self.inputs), "--output-root", str(self.outputs),
                   "--manifest", "manifest.json", "--samples", "samples.json", "--output", "result.json"]
        if extra:
            command.extend(extra)
        process = subprocess.run(command, cwd=self.root, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                 stderr=subprocess.PIPE, timeout=10, shell=False)
        self.results.save("cli.stdout.log.txt", process.stdout)
        self.results.save("cli.stderr.log.txt", process.stderr)
        self.results.save_json("cli-exit.json", {"exit_code": process.returncode, "source_only": True})
        self.assertNotIn(fixtures.SENTINEL.encode(), process.stdout + process.stderr)
        result_path = self.outputs / "result.json"
        return process, json.loads(result_path.read_bytes()) if result_path.is_file() else None

    def capture(self, samples, receipts, *, kind="synthetic", reviewed=False, raw_samples=None, manifest_edit=None):
        manifest, bundle, _ = fixtures.envelope(samples, kind=kind, reviewed=reviewed)
        data = raw_samples if raw_samples is not None else json_bytes(bundle)
        manifest["capture_sha256"] = hashlib.sha256(data).hexdigest()
        if manifest_edit:
            manifest_edit(manifest)
        for digest, receipt in receipts.items():
            self.store.save("receipts/" + digest + ".json", receipt)
        self.store.save("samples.json", data)
        self.store.save_json("manifest.json", manifest)
        return self.cli()

    def test_positive_stays_synthetic_and_reports_literal_median_range(self):
        samples, receipts = [], {}
        for seed in (1, 2, 3):
            sample, owned = fixtures.make_sample(seed, seed)
            samples.append(sample)
            receipts.update(owned)
        process, result = self.capture(samples, receipts)
        self.assertEqual(process.returncode, 0)
        self.assertEqual(result["synthetic_preview_phases"]["preflight"],
                         {"n": 3, "median_ns": 2000000, "min_ns": 1000000, "max_ns": 3000000})
        self.assertEqual(len(result["synthetic_preview_phases"]), 11)
        self.assertEqual(result["avf_device_candidate_count"], 0)
        self.assertEqual(result["device_verdict"], "NOT_DETERMINED")
        self.assertEqual(result["authenticity"], "NOT_AUTHENTICATED_BY_THIS_TOOL")
        self.assertEqual(result["tail_percentiles"], "NOT_REPORTED")
        self.assertNotIn("p95", json.dumps(result))
        self.assertNotIn("p99", json.dumps(result))

    def test_complete_mixed_fixture_excludes_every_negative(self):
        manifest, bundle, receipts, expected = fixtures.build_fixture()
        process, result = self.capture(bundle["samples"], receipts)
        self.assertEqual(process.returncode, 1)
        self.assertEqual([row["reason"] for row in result["samples"]], expected)
        self.assertEqual(result["synthetic_preview_count"], 3)
        self.assertEqual(result["avf_device_candidate_count"], 0)

    def test_missing_receipts_rejected(self):
        sample, _ = fixtures.make_sample()
        process, result = self.capture([sample], {})
        self.assertEqual(process.returncode, 1)
        self.assertEqual(result["samples"][0]["reason"], "RECEIPT_UNAVAILABLE")

    def test_receipt_hash_mismatch_rejected(self):
        sample, receipts = fixtures.make_sample()
        receipts[sample["events"][0]["receipt_sha256"]] = b"{}"
        process, result = self.capture([sample], receipts)
        self.assertEqual(result["samples"][0]["reason"], "RECEIPT_HASH_MISMATCH")

    def test_extra_secret_receipt_rejected_even_with_matching_hash(self):
        sample, receipts = fixtures.make_sample()
        event = sample["events"][0]
        payload = json.loads(receipts[event["receipt_sha256"]])
        payload["token"] = fixtures.SENTINEL
        data = json_bytes(payload)
        event["receipt_sha256"] = hashlib.sha256(data).hexdigest()
        receipts[event["receipt_sha256"]] = data
        _, result = self.capture([sample], receipts)
        self.assertEqual(result["samples"][0]["reason"], "RECEIPT_BINDING_MISMATCH")
        self.assertNotIn(fixtures.SENTINEL, json.dumps(result))

    def test_relabelled_synthetic_not_device_evidence(self):
        sample, receipts = fixtures.make_sample()
        _, result = self.capture([sample], receipts, kind="device", reviewed=True)
        self.assertEqual(result["samples"][0]["reason"], "RECEIPT_BINDING_MISMATCH")
        self.assertEqual(result["avf_device_candidate_count"], 0)

    def test_unreviewed_device_envelope_rejected_with_synthetic_inputs(self):
        sample, receipts = fixtures.make_sample(kind="device")
        _, result = self.capture([sample], receipts, kind="device", reviewed=False)
        self.assertEqual(result["samples"][0]["reason"], "CAPTURE_NOT_REVIEWED")
        self.assertEqual(result["avf_device_candidate_count"], 0)

    def test_duplicate_sessions_exclude_all_copies(self):
        sample, receipts = fixtures.make_sample()
        _, result = self.capture([sample, deepcopy(sample)], receipts)
        self.assertEqual([row["reason"] for row in result["samples"]], ["DUPLICATE_SAMPLE_IDENTITY"] * 2)
        self.assertEqual(result["synthetic_preview_count"], 0)

    def test_review_boolean_and_complete_shape_still_cannot_certify_device(self):
        # Deliberately forged device labels, all bytes produced by the synthetic fixture.
        sample, receipts = fixtures.make_sample(kind="device")
        process, result = self.capture([sample], receipts, kind="device", reviewed=True)
        self.assertEqual(process.returncode, 0)
        self.assertEqual(result["samples"][0]["classification"], "DEVICE_RECEIPTS_STRUCTURALLY_VALID")
        self.assertEqual(result["device_verdict"], "NOT_DETERMINED")
        self.assertEqual(result["authenticity"], "NOT_AUTHENTICATED_BY_THIS_TOOL")

    def test_capture_hash_mismatch_rejected_without_output(self):
        sample, receipts = fixtures.make_sample()
        process, result = self.capture([sample], receipts,
                                       manifest_edit=lambda m: m.update(capture_sha256=fixtures.sha("wrong")))
        self.assertEqual(process.returncode, 2)
        self.assertEqual(json.loads(process.stdout), {"error": "CAPTURE_HASH_MISMATCH"})
        self.assertIsNone(result)

    def test_duplicate_json_key_rejected(self):
        process, result = self.capture([], {}, raw_samples=b'{"schema_version":1,"schema_version":1}')
        self.assertEqual(process.returncode, 2)
        self.assertEqual(json.loads(process.stdout), {"error": "DUPLICATE_JSON_KEY"})
        self.assertIsNone(result)

    def test_nonfinite_json_rejected(self):
        process, _ = self.capture([], {}, raw_samples=b'{"value":NaN}')
        self.assertEqual(json.loads(process.stdout), {"error": "NONFINITE_JSON"})

    def test_existing_valid_output_is_preserved(self):
        self.store.save_json("manifest.json", {})
        self.store.save_json("samples.json", {})
        self.results.save_json("result.json", {"old_valid_synthetic": True})
        process, result = self.cli()
        self.assertEqual(process.returncode, 2)
        self.assertEqual(json.loads(process.stdout), {"error": "OUTPUT_EXISTS"})
        self.assertEqual(result, {"old_valid_synthetic": True})

    def test_path_escape_rejected_without_reading_outside(self):
        self.store.save_json("manifest.json", {})
        self.store.save_json("samples.json", {})
        process, result = self.cli(["--manifest", "../outside.json"])
        self.assertEqual(process.returncode, 2)
        self.assertEqual(json.loads(process.stdout), {"error": "PATH_REJECTED"})
        self.assertIsNone(result)

    def test_symlink_or_junction_rejected(self):
        self.inputs.mkdir(parents=True)
        outside, link = self.root / "outside", self.inputs / "link"
        outside.mkdir()
        try:
            os.symlink(outside, link, target_is_directory=True)
        except OSError:
            if os.name != "nt":
                self.skipTest("HOST_LINK_CREATION_UNAVAILABLE")
            made = subprocess.run(["cmd.exe", "/d", "/c", "mklink", "/J", str(link), str(outside)],
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
            if made.returncode != 0:
                self.skipTest("HOST_LINK_CREATION_UNAVAILABLE")
        with self.assertRaisesRegex(Rejected, "LINK_REJECTED"):
            self.store.load_json("link/source.json")


for _name, _mutate, _reason in fixtures.negative_cases():
    def case(self, mutate=_mutate, reason=_reason):
        sample, receipts = fixtures.make_sample()
        mutate(sample)
        process, result = self.capture([sample], receipts)
        self.assertEqual(process.returncode, 1)
        self.assertEqual(result["samples"][0]["reason"], reason)
        self.assertEqual(result["avf_device_candidate_count"], 0)
    setattr(AvfEvidenceCliTests, "test_negative_" + _name, case)


class AvfPathAndBoundTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(os.environ["AVF_VALIDATION_TEST_ROOT"]) / uuid.uuid4().hex
        self.store = EvidenceStore(self.root, self.root)

    def test_explicit_absolute_root_required(self):
        with self.assertRaisesRegex(Rejected, "ROOT_REJECTED"):
            EvidenceStore("relative-input", self.root)

    def test_hidden_dynamic_and_extension_paths_rejected_without_writes(self):
        for relative in ("../out.json", "/out.json", "D:/out.json", "part/../../out.json", ".git/out.json",
                         ".env", "preservation/out.json", "part\\out.json", "part/$(x).json", "part/out.log"):
            with self.assertRaises(Rejected):
                self.store.save_json(relative, {"kind": "synthetic"})
        self.assertFalse(self.root.exists())

    def test_large_json_input_rejected(self):
        self.store.save("large.json", b" " * (2 * 1024 * 1024 + 1))
        with self.assertRaisesRegex(Rejected, "INPUT_REJECTED"):
            self.store.load_json("large.json")

    def test_old_preservation_prevents_new_overwrite(self):
        self.store.save_json("result.json", {"old_synthetic": True})
        with self.assertRaisesRegex(Rejected, "OUTPUT_EXISTS"):
            self.store.save_json("result.json", {"new_synthetic": True})
        self.assertEqual(self.store.load_json("result.json"), {"old_synthetic": True})


FAKE_ADB = r'''import hashlib, json, os, sys, time
from pathlib import Path
mode, trace = sys.argv[1], Path(sys.argv[2])
args = sys.argv[3:]
assert os.environ.get("MSYS_NO_PATHCONV") == "1"
data = (json.dumps(args) + "\n").encode()
name = hashlib.sha256(data).hexdigest() + ".json"
for path in (trace / name, trace / "preservation" / name):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as stream:
        stream.write(data)
if mode == "timeout":
    time.sleep(2)
if mode in ("fail", "revoked"):
    print("SYNTHETIC_SECRET_SENTINEL")
    sys.exit(9)
if args == ["devices", "-l"]:
    print("List of devices attached")
    if mode == "connected":
        print("fixture-serial device model:SYNTHETIC_MODEL")
    sys.exit(0)
if args[:3] != ["-s", "fixture-serial", "shell"]:
    sys.exit(7)
responses = {
    ("getprop", "ro.build.id"): "FIXTURE_ONLY",
    ("getprop", "ro.build.version.release"): "16",
    ("getprop", "ro.build.fingerprint"): "fixture/device/build:16/ID/1:user/test-keys",
    ("getprop", "ro.build.version.sdk"): "36",
    ("getprop", "ro.product.cpu.abilist"): "arm64-v8a,armeabi-v7a",
    ("getconf", "PAGE_SIZE"): "16384",
    ("cmd", "package", "list", "packages", "--apex-only", "--show-versioncode", "com.android.virt"): "package:com.android.virt versionCode:360000000",
    ("cmd", "package", "list", "packages", "--apex-only", "--show-versioncode", "com.google.android.virt"): "",
    ("cmd", "package", "has-feature", "android.software.virtualization_framework"): "true",
    ("getprop", "ro.boot.hypervisor.vm.supported"): "1",
    ("getprop", "ro.boot.hypervisor.protected_vm.supported"): "1",
}
key = tuple(args[3:])
if key not in responses:
    sys.exit(8)
print("bad\x00SYNTHETIC_SECRET_SENTINEL" if mode == "malformed" else responses[key])
'''


class AvfPreflightTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(os.environ["AVF_VALIDATION_TEST_ROOT"]) / uuid.uuid4().hex
        self.store = EvidenceStore(self.root, self.root)
        self.store.save("fake_adb.py", FAKE_ADB.encode("ascii"))

    def fake_runner(self, mode="normal"):
        import avf_preflight as pre
        def runner(args):
            return pre.run_fixed([sys.executable, "-B", str(self.root / "fake_adb.py"), mode,
                                  str(self.root / "fake-calls"), *args[1:]],
                                 timeout_seconds=0.08 if mode == "timeout" else 5)
        return runner

    def test_fake_adb_exact_serial_argv_and_unknown_permission_api(self):
        import avf_preflight as pre
        result = pre.collect("fake-adb", "fixture-serial", "fixture-device", self.fake_runner())
        self.assertEqual(result["queries_completed"], 11)
        self.assertEqual(result["status"], "PREFLIGHT_ONLY")
        self.assertEqual(result["fields"]["api"]["value"], 36)
        self.assertEqual(result["fields"]["page_bytes"]["value"], 16384)
        self.assertEqual(result["permissions"], {"MANAGE_VIRTUAL_MACHINE": "unknown", "USE_CUSTOM_VIRTUAL_MACHINE": "unknown"})
        self.assertEqual(result["capabilities"], {"nonprotected_api": "unknown", "protected_api": "unknown"})
        self.assertEqual(result["avf_execution"], "NOT_TESTED")
        self.assertNotIn("fixture-serial", json.dumps(result))
        self.assertNotIn("fixture/device/build", json.dumps(result))
        trace = [json.loads(p.read_bytes()) for p in (self.root / "fake-calls").glob("*.json")]
        self.assertEqual(len(trace), 11)
        self.assertTrue(all(row[:3] == ["-s", "fixture-serial", "shell"] for row in trace))
        forbidden = {"grant", "install", "start", "stop", "settings", "su", "dumpsys", "logcat", "run-as", "cat", "input"}
        self.assertFalse(any(set(row) & forbidden for row in trace))

    def test_fake_adb_failure_no_retry_or_success(self):
        import avf_preflight as pre
        result = pre.collect("fake-adb", "fixture-serial", "fixture-device", self.fake_runner("fail"))
        self.assertEqual(result["status"], "PARTIAL_PREFLIGHT")
        self.assertEqual(result["error"], "ADB_QUERY_FAILED")
        self.assertEqual(len(list((self.root / "fake-calls").glob("*.json"))), 1)
        self.assertNotIn(fixtures.SENTINEL, json.dumps(result))

    def test_fake_adb_timeout_no_success(self):
        import avf_preflight as pre
        result = pre.collect("fake-adb", "fixture-serial", "fixture-device", self.fake_runner("timeout"))
        self.assertEqual(result["status"], "PARTIAL_PREFLIGHT")
        self.assertEqual(result["error"], "ADB_TIMEOUT")
        self.assertEqual(result["queries_completed"], 0)

    def test_fake_adb_revocation_preserved_as_partial(self):
        import avf_preflight as pre
        result = pre.collect("fake-adb", "fixture-serial", "fixture-device", self.fake_runner("revoked"))
        self.assertEqual(result["status"], "PARTIAL_PREFLIGHT")
        self.assertEqual(result["avf_execution"], "NOT_TESTED")

    def test_fake_zero_device_discovery(self):
        import avf_preflight as pre
        result = pre.discover("fake-adb", self.fake_runner())
        self.assertEqual(result["status"], "NO_DEVICE")
        self.assertEqual(result["counts"], {"device": 0, "offline": 0, "unauthorized": 0, "other": 0})

    def test_fake_connected_discovery_does_not_select_or_log_serial(self):
        import avf_preflight as pre
        result = pre.discover("fake-adb", self.fake_runner("connected"))
        self.assertEqual(result["status"], "EXPLICIT_SERIAL_REQUIRED")
        self.assertNotIn("fixture-serial", json.dumps(result))
        self.assertNotIn("SYNTHETIC_MODEL", json.dumps(result))
        self.assertEqual(len(list((self.root / "fake-calls").glob("*.json"))), 1)

    def test_fake_malicious_output_redacted(self):
        import avf_preflight as pre
        result = pre.collect("fake-adb", "fixture-serial", "fixture-device", self.fake_runner("malformed"))
        self.assertNotIn(fixtures.SENTINEL, json.dumps(result))
        self.assertTrue(all(field["state"] == "unknown" for field in result["fields"].values()))

    def test_serial_injection_rejected_before_process(self):
        import avf_preflight as pre
        with self.assertRaisesRegex(Rejected, "SERIAL_REJECTED"):
            pre.collect("fake-adb", "fixture; settings put x y", "fixture-device", self.fake_runner())
        self.assertFalse((self.root / "fake-calls").exists())

    def test_cli_requires_explicit_serial_and_output_root(self):
        process = subprocess.run([sys.executable, "-B", str(MODULE_DIR / "avf_preflight.py"),
                                  "collect", "--adb", "SYNTHETIC_SECRET_SENTINEL", "--output", "result.json"],
                                 stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        self.assertEqual(process.returncode, 2)
        self.assertEqual(process.stderr.decode("ascii").splitlines(), ["ARGUMENTS_REJECTED"])
        self.assertNotIn(fixtures.SENTINEL.encode(), process.stdout + process.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
