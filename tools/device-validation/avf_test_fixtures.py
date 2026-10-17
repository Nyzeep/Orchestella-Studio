# SPDX-License-Identifier: GPL-3.0-or-later
"""All data here are synthetic. No tests contact adb or execute guest workloads."""
from __future__ import annotations

import hashlib
import json

import avf_benchmark as bench
import avf_evidence_io as eio

SENTINEL = "SYNTHETIC_SECRET_SENTINEL"


def sha(label):
    return hashlib.sha256(("fixture-only:" + label).encode("ascii")).hexdigest()


def expected_binding():
    return {"apk_sha256": sha("apk"), "commit": "1" * 40, "device_alias": "fixture-only",
            "rom_build_id": "FIXTURE_ONLY", "api": 36, "abi": "arm64-v8a", "page_bytes": 16384,
            "avf_module_version": 1, "kernel_sha256": sha("kernel"), "base_initrd_sha256": sha("base-initrd"),
            "rootfs_sha256": sha("rootfs"), "vcpu": 1, "memory_mib": 4096, "disk_bytes": 8589934592,
            "boot_type": "ready_cold", "qa_volume_alias": "fixture-qa-volume", "owner_alias": "fixture-owner",
            "vm_alias": "fixture-qa-vm", "execution_context": "app_direct", "cache_state": "uncontrolled",
            "power_mode": "balanced", "thermal_state": "nominal", "charging": False}


def make_sample(seed=1, scale=1, kind="synthetic"):
    sample = {"sample_id": f"sample-{seed:04d}", "session_id": f"session-{seed:04d}", "generation": 1,
              "owner_alias": "fixture-owner", "vm_alias": "fixture-qa-vm", "binding": expected_binding(),
              "actual_engine": "AVF", "fallback_observed": False, "status": "SUCCESS", "failure_code": "NONE",
              "ownership_verified": True, "single_reader_verified": True, "qa_only": True,
              "clock": "android_elapsed_realtime_nanos",
              "guest": {"command_id": "qa-uname-cpu-v1", "executed": True, "exit_code": 0,
                        "architecture": "aarch64", "kernel_release": "7.2.3-fixture", "online_cpus": 1}, "events": []}
    receipts = {}
    for index, (name, source) in enumerate(bench.SOURCES.items()):
        event = {"name": name, "t_ns": (100 + index * scale) * 1000000,
                 **{key: sample[key] for key in ("session_id", "generation", "owner_alias", "vm_alias")},
                 "engine": "AVF", "outcome": "OK", "source": source, "receipt_sha256": ""}
        data = eio.json_bytes(bench.receipt_object(sample, event, kind))
        event["receipt_sha256"] = hashlib.sha256(data).hexdigest()
        receipts[event["receipt_sha256"]] = data
        sample["events"].append(event)
    return sample, receipts


def envelope(samples, kind="synthetic", reviewed=False):
    bundle = {"schema_version": 1, "kind": kind, "samples": samples}
    capture_hash = hashlib.sha256(eio.json_bytes(bundle)).hexdigest()
    manifest = {"schema_version": 1, "kind": kind, "binding": expected_binding(), "capture_sha256": capture_hash,
                "review": {"source": "fixture" if kind == "synthetic" else "owned_instrumentation",
                           "reviewer_alias": "fixture-reviewer", "reviewed": reviewed}}
    return manifest, bundle, capture_hash


def result_for(sample, receipts, kind="synthetic", reviewed=False):
    manifest, bundle, capture_hash = envelope([sample], kind, reviewed)
    return bench.evaluate(manifest, bundle, capture_hash, receipts.__getitem__)


def change(path, value):
    def mutate(sample):
        target = sample
        for part in path[:-1]:
            target = target[part]
        target[path[-1]] = value
    return mutate


def bad_order(sample):
    sample["events"][3], sample["events"][4] = sample["events"][4], sample["events"][3]
    for index, event in enumerate(sample["events"]):
        event["t_ns"] = (100 + index) * 1000000


def negative_cases():
    cases = []
    for gate in bench.SOURCES:
        def remove(sample, name=gate):
            sample["events"] = [e for e in sample["events"] if e["name"] != name]
        cases.append(("missing_" + gate, remove, "MISSING_GATE"))
    cases += [
        ("logical_order", bad_order, "EVENT_ORDER_REJECTED"),
        ("nonmonotonic", change(("events", 1, "t_ns"), 0), "NONMONOTONIC_EVENT"),
        ("wrong_session", change(("events", 3, "session_id"), "session-other"), "EVENT_IDENTITY_MISMATCH"),
        ("stale_generation", change(("events", 3, "generation"), 2), "EVENT_IDENTITY_MISMATCH"),
        ("other_vm", change(("events", 3, "vm_alias"), "unrelated-crosvm"), "EVENT_IDENTITY_MISMATCH"),
        ("other_owner", change(("events", 3, "owner_alias"), "unrelated-owner"), "EVENT_IDENTITY_MISMATCH"),
        ("wrong_sample_owner", change(("owner_alias",), "unrelated-owner"), "OWNER_BINDING_MISMATCH"),
        ("wrong_sample_vm", change(("vm_alias",), "unrelated-vm"), "OWNER_BINDING_MISMATCH"),
        ("fallback", change(("fallback_observed",), True), "FALLBACK_OBSERVED"),
        ("qemu", change(("actual_engine",), "QEMU"), "NOT_AVF"),
        ("unknown_engine", change(("actual_engine",), "UNKNOWN"), "NOT_AVF"),
        ("failure", change(("status",), "FAILED"), "UNSUCCESSFUL_SAMPLE"),
        ("unknown_status", change(("status",), "UNKNOWN"), "UNSUCCESSFUL_SAMPLE"),
        ("failure_code", change(("failure_code",), "RUN_FAILED"), "UNSUCCESSFUL_SAMPLE"),
        ("ownership", change(("ownership_verified",), False), "OWNERSHIP_UNVERIFIED"),
        ("second_reader", change(("single_reader_verified",), False), "SINGLE_READER_UNVERIFIED"),
        ("user_disk", change(("qa_only",), False), "NON_QA_REJECTED"),
        ("wall_clock", change(("clock",), "wall_clock"), "CLOCK_REJECTED"),
        ("bool_timestamp", change(("events", 0, "t_ns"), True), "INTEGER_REJECTED"),
        ("float_timestamp", change(("events", 0, "t_ns"), 1.5), "INTEGER_REJECTED"),
        ("negative_timestamp", change(("events", 0, "t_ns"), -1), "INTEGER_REJECTED"),
        ("wrong_event_engine", change(("events", 2, "engine"), "QEMU"), "EVENT_ENGINE_MISMATCH"),
        ("failed_stop_receipt", change(("events", -1, "outcome"), "FAILED"), "EVENT_UNSUCCESSFUL"),
        ("terminal_text_source", change(("events", 2, "source"), "terminal_text"), "EVENT_SOURCE_REJECTED"),
        ("missing_receipt", change(("events", 2, "receipt_sha256"), sha("missing")), "RECEIPT_UNAVAILABLE"),
        ("receipt_path_injection", change(("events", 2, "receipt_sha256"), "../../.env"), "DIGEST_REJECTED"),
        ("arbitrary_guest_command", change(("guest", "command_id"), "su -c " + SENTINEL), "GUEST_COMMAND_UNVERIFIED"),
        ("guest_not_executed", change(("guest", "executed"), False), "GUEST_COMMAND_UNVERIFIED"),
        ("guest_failed", change(("guest", "exit_code"), 1), "GUEST_COMMAND_FAILED"),
        ("guest_cpu_mismatch", change(("guest", "online_cpus"), 4), "GUEST_RESOURCE_MISMATCH"),
        ("guest_malicious_kernel", change(("guest", "kernel_release"), "7.2.3\n" + SENTINEL), "GUEST_FIELD_REJECTED"),
        ("sample_extra_token", change(("token",), SENTINEL), "SAMPLE_SCHEMA_REJECTED"),
        ("event_extra_text", change(("events", 0, "terminal_text"), SENTINEL), "EVENT_SCHEMA_REJECTED"),
        ("guest_extra_text", change(("guest", "stdout"), SENTINEL), "GUEST_SCHEMA_REJECTED"),
        ("alias_injection", change(("owner_alias",), "owner;" + SENTINEL), "ALIAS_REJECTED"),
        ("boolean_resource", change(("binding", "vcpu"), True), "INTEGER_REJECTED"),
    ]
    for field, value in {"vcpu": 2, "memory_mib": 8192, "disk_bytes": 17179869184,
                         "apk_sha256": sha("other-apk"), "commit": "2" * 40,
                         "kernel_sha256": sha("other-kernel"), "rootfs_sha256": sha("other-rootfs"),
                         "base_initrd_sha256": sha("other-initrd"), "device_alias": "other-device",
                         "rom_build_id": "OTHER_ROM", "avf_module_version": 2, "page_bytes": 4096,
                         "boot_type": "first_provision", "cache_state": "warm", "charging": True,
                         "execution_context": "shizuku_userservice"}.items():
        cases.append(("binding_" + field, change(("binding", field), value), "BINDING_MISMATCH"))
    return cases


def build_fixture():
    samples, receipts, expected = [], {}, []
    for seed in (1, 2, 3):
        sample, owned = make_sample(seed, seed)
        samples.append(sample)
        receipts.update(owned)
        expected.append("VALIDATED_RECEIPT_STRUCTURE")
    for seed, (_, mutate, reason) in enumerate(negative_cases(), 10):
        sample, _ = make_sample(seed)
        mutate(sample)
        samples.append(sample)
        expected.append(reason)
    manifest, bundle, _ = envelope(samples)
    return manifest, bundle, receipts, expected
