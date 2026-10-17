# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline AVF receipt validator and phase summary prototype; no device execution."""
from __future__ import annotations

from collections import Counter
import hashlib
import json
import re
import statistics
import sys
sys.dont_write_bytecode = True

from avf_evidence_io import MAX_INPUT, Rejected, SafeParser, EvidenceStore, parse_json, require_supported_runtime
def alias(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-z][a-z0-9-]{2,39}", value):
        raise Rejected("ALIAS_REJECTED")
    return value

SOURCES = {
    "launch_requested": "ui_marker", "preflight_begin": "probe_marker", "preflight_end": "probe_marker",
    "inputs_begin": "asset_verifier", "inputs_verified": "asset_verifier", "manager_begin": "framework_receipt",
    "vm_created": "framework_receipt", "run_begin": "framework_receipt", "run_returned": "framework_receipt",
    "vm_running_observed": "framework_state", "vsock_connected": "channel_receipt",
    "agent_authenticated": "authenticated_agent", "guest_command_ok": "command_receipt",
    "services_ready": "service_receipt", "stop_requested": "stop_intent", "stop_completed": "normal_stop_receipt",
}
# Custom Linux has no generic VM-start or Microdroid payload callback gate.
CHAIN = list(SOURCES)
EDGES = list(zip(CHAIN, CHAIN[1:]))
PHASES = {
    "preflight": ("preflight_begin", "preflight_end"),
    "inputs": ("inputs_begin", "inputs_verified"),
    "manager_create": ("manager_begin", "vm_created"),
    "run_call": ("run_begin", "run_returned"),
    "running_state_observation": ("run_returned", "vm_running_observed"),
    "run_to_vsock": ("run_returned", "vsock_connected"),
    "agent_handshake": ("vsock_connected", "agent_authenticated"),
    "first_command": ("agent_authenticated", "guest_command_ok"),
    "native_services": ("guest_command_ok", "services_ready"),
    "launch_to_ready": ("launch_requested", "services_ready"),
    "normal_stop": ("stop_requested", "stop_completed"),
}
BINDING_KEYS = {
    "apk_sha256", "commit", "device_alias", "rom_build_id", "api", "abi", "page_bytes", "avf_module_version",
    "kernel_sha256", "base_initrd_sha256", "rootfs_sha256", "vcpu", "memory_mib", "disk_bytes", "boot_type",
    "qa_volume_alias", "owner_alias", "vm_alias", "execution_context", "cache_state", "power_mode", "thermal_state", "charging",
}
SAMPLE_KEYS = {"sample_id", "session_id", "generation", "owner_alias", "vm_alias", "binding", "actual_engine",
               "fallback_observed", "status", "failure_code", "ownership_verified", "single_reader_verified",
               "qa_only", "clock", "guest", "events"}
EVENT_KEYS = {"name", "t_ns", "session_id", "generation", "owner_alias", "vm_alias", "engine", "outcome", "source", "receipt_sha256"}
FAILURES = {"NONE", "PERMISSION_DENIED", "API_BLOCKED", "CREATE_FAILED", "RUN_FAILED", "READINESS_FAILED",
            "GUEST_COMMAND_FAILED", "STOP_FAILED", "CANCELLED", "UNKNOWN"}


def require(condition, code):
    if not condition:
        raise Rejected(code)


def exact(value, keys, code="SCHEMA_REJECTED"):
    require(type(value) is dict and set(value) == set(keys), code)


def integer(value, low=0, high=2**63 - 1):
    require(type(value) is int and low <= value <= high, "INTEGER_REJECTED")


def choice(value, values):
    require(type(value) is str and value in values, "ENUM_REJECTED")


def digest(value, length=64):
    require(type(value) is str and re.fullmatch(r"[0-9a-f]{" + str(length) + r"}", value) is not None,
            "DIGEST_REJECTED")
    require(value != "0" * length, "DIGEST_REJECTED")


def binding(value):
    exact(value, BINDING_KEYS, "BINDING_SCHEMA_REJECTED")
    for key in ("apk_sha256", "kernel_sha256", "base_initrd_sha256", "rootfs_sha256"):
        digest(value[key])
    digest(value["commit"], 40)
    alias(value["device_alias"])
    alias(value["qa_volume_alias"])
    alias(value["owner_alias"])
    alias(value["vm_alias"])
    choice(value["execution_context"], {"app_direct", "shizuku_userservice", "platform_client"})
    require(type(value["rom_build_id"]) is str and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,95}", value["rom_build_id"]), "ROM_REJECTED")
    integer(value["api"], 1, 1000)
    choice(value["abi"], {"arm64-v8a"})
    require(type(value["page_bytes"]) is int and value["page_bytes"] in (4096, 16384, 65536), "PAGE_REJECTED")
    integer(value["avf_module_version"], 1, 10**18)
    integer(value["vcpu"], 1, 256)
    integer(value["memory_mib"], 1, 1048576)
    integer(value["disk_bytes"], 1, 2**63 - 1)
    choice(value["boot_type"], {"first_provision", "ready_cold", "config_reuse_warm", "filesystem_cache_warm"})
    choice(value["cache_state"], {"cold", "warm", "uncontrolled"})
    choice(value["power_mode"], {"balanced", "performance", "unknown"})
    choice(value["thermal_state"], {"nominal", "light", "moderate", "severe", "unknown"})
    require(type(value["charging"]) is bool, "BOOLEAN_REJECTED")


def check_manifest(manifest, bundle, capture_sha256):
    exact(manifest, {"schema_version", "kind", "binding", "capture_sha256", "review"}, "MANIFEST_SCHEMA_REJECTED")
    exact(bundle, {"schema_version", "kind", "samples"}, "BUNDLE_SCHEMA_REJECTED")
    require(type(manifest["schema_version"]) is int and manifest["schema_version"] == 1 and
            type(bundle["schema_version"]) is int and bundle["schema_version"] == 1, "VERSION_REJECTED")
    choice(manifest["kind"], {"synthetic", "device"})
    require(bundle["kind"] == manifest["kind"], "PROVENANCE_MISMATCH")
    binding(manifest["binding"])
    digest(manifest["capture_sha256"])
    require(manifest["capture_sha256"] == capture_sha256, "CAPTURE_HASH_MISMATCH")
    exact(manifest["review"], {"source", "reviewer_alias", "reviewed"}, "REVIEW_SCHEMA_REJECTED")
    review = manifest["review"]
    alias(review["reviewer_alias"])
    require(type(review["reviewed"]) is bool, "BOOLEAN_REJECTED")
    require(review["source"] == ("fixture" if manifest["kind"] == "synthetic" else "owned_instrumentation"), "PROVENANCE_MISMATCH")
    require(type(bundle["samples"]) is list and len(bundle["samples"]) <= 500, "SAMPLE_LIMIT_REJECTED")


def binding_sha256(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode("ascii")).hexdigest()


def receipt_object(sample, event, kind):
    return {"schema_version": 1, "kind": kind, "sample_id": sample["sample_id"],
            "session_id": sample["session_id"], "generation": sample["generation"],
            "owner_alias": sample["owner_alias"], "vm_alias": sample["vm_alias"],
            "clock": sample["clock"], "binding_sha256": binding_sha256(sample["binding"]),
            "event": {key: event[key] for key in ("name", "t_ns", "engine", "outcome", "source")}}


def check_receipt(sample, event, kind, loader):
    require(loader is not None, "RECEIPT_UNAVAILABLE")
    try:
        data = loader(event["receipt_sha256"])
    except (OSError, Rejected, KeyError):
        raise Rejected("RECEIPT_UNAVAILABLE") from None
    require(type(data) is bytes and len(data) <= MAX_INPUT, "RECEIPT_REJECTED")
    require(hashlib.sha256(data).hexdigest() == event["receipt_sha256"], "RECEIPT_HASH_MISMATCH")
    receipt = parse_json(data)
    # Exact equality also rejects extra fields, terminal text, tokens, and arbitrary evidence paths.
    require(receipt == receipt_object(sample, event, kind), "RECEIPT_BINDING_MISMATCH")
    require(type(receipt.get("schema_version")) is int and type(receipt.get("generation")) is int and
            type(receipt.get("event", {}).get("t_ns")) is int, "RECEIPT_REJECTED")


def validate_sample(sample, expected, kind, loader):
    exact(sample, SAMPLE_KEYS, "SAMPLE_SCHEMA_REJECTED")
    for key in ("sample_id", "session_id", "owner_alias", "vm_alias"):
        alias(sample[key])
    integer(sample["generation"], 1)
    binding(sample["binding"])
    require(sample["binding"] == expected, "BINDING_MISMATCH")
    require(sample["owner_alias"] == expected["owner_alias"] and sample["vm_alias"] == expected["vm_alias"], "OWNER_BINDING_MISMATCH")
    choice(sample["actual_engine"], {"AVF", "QEMU", "UNKNOWN"})
    choice(sample["status"], {"SUCCESS", "FAILED", "UNKNOWN"})
    choice(sample["failure_code"], FAILURES)
    for key in ("fallback_observed", "ownership_verified", "single_reader_verified", "qa_only"):
        require(type(sample[key]) is bool, "BOOLEAN_REJECTED")
    require(sample["actual_engine"] == "AVF", "NOT_AVF")
    require(not sample["fallback_observed"], "FALLBACK_OBSERVED")
    require(sample["status"] == "SUCCESS" and sample["failure_code"] == "NONE", "UNSUCCESSFUL_SAMPLE")
    require(sample["ownership_verified"], "OWNERSHIP_UNVERIFIED")
    require(sample["single_reader_verified"], "SINGLE_READER_UNVERIFIED")
    require(sample["qa_only"], "NON_QA_REJECTED")
    require(sample["clock"] == "android_elapsed_realtime_nanos", "CLOCK_REJECTED")
    guest = sample["guest"]
    exact(guest, {"command_id", "executed", "exit_code", "architecture", "kernel_release", "online_cpus"}, "GUEST_SCHEMA_REJECTED")
    require(guest["command_id"] == "qa-uname-cpu-v1" and guest["executed"] is True, "GUEST_COMMAND_UNVERIFIED")
    integer(guest["exit_code"], 0, 255)
    require(guest["exit_code"] == 0 and guest["architecture"] == "aarch64", "GUEST_COMMAND_FAILED")
    require(type(guest["kernel_release"]) is str and re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+[A-Za-z0-9_.+-]{0,48}", guest["kernel_release"]), "GUEST_FIELD_REJECTED")
    integer(guest["online_cpus"], 1, 256)
    require(guest["online_cpus"] == expected["vcpu"], "GUEST_RESOURCE_MISMATCH")
    require(type(sample["events"]) is list and len(sample["events"]) <= len(SOURCES), "EVENTS_REJECTED")
    indexed = {}
    last_ns = -1
    for index, event in enumerate(sample["events"]):
        exact(event, EVENT_KEYS, "EVENT_SCHEMA_REJECTED")
        choice(event["name"], SOURCES)
        require(event["name"] not in indexed, "DUPLICATE_EVENT")
        integer(event["t_ns"])
        require(event["t_ns"] >= last_ns, "NONMONOTONIC_EVENT")
        last_ns = event["t_ns"]
        integer(event["generation"], 1)
        for key in ("session_id", "generation", "owner_alias", "vm_alias"):
            require(event[key] == sample[key], "EVENT_IDENTITY_MISMATCH")
        require(event["engine"] == "AVF", "EVENT_ENGINE_MISMATCH")
        require(event["outcome"] == "OK", "EVENT_UNSUCCESSFUL")
        require(event["source"] == SOURCES[event["name"]], "EVENT_SOURCE_REJECTED")
        digest(event["receipt_sha256"])
        indexed[event["name"]] = (index, event["t_ns"])
    require(set(indexed) == set(SOURCES), "MISSING_GATE")
    for start, end in EDGES:
        require(indexed[start][0] < indexed[end][0], "EVENT_ORDER_REJECTED")
    for event in sample["events"]:
        check_receipt(sample, event, kind, loader)
    return {phase: indexed[end][1] - indexed[start][1] for phase, (start, end) in PHASES.items()}


def phase_statistics(rows):
    if not rows:
        return {}
    return {phase: {"n": len(rows), "median_ns": statistics.median(r[phase] for r in rows),
                    "min_ns": min(r[phase] for r in rows), "max_ns": max(r[phase] for r in rows)}
            for phase in PHASES}


def evaluate(manifest, bundle, capture_sha256, receipt_loader=None):
    check_manifest(manifest, bundle, capture_sha256)
    # Reject every copy of a duplicated identity, including the first copy.
    counts = {key: Counter(s.get(key) for s in bundle["samples"] if type(s) is dict and type(s.get(key)) is str)
              for key in ("sample_id", "session_id")}
    results, candidates, previews = [], [], []
    for index, sample in enumerate(bundle["samples"]):
        row = {"sample_index": index, "classification": "EXCLUDED", "reason": "UNKNOWN"}
        try:
            stages = validate_sample(sample, manifest["binding"], manifest["kind"], receipt_loader)
            require(all(counts[key][sample[key]] == 1 for key in counts), "DUPLICATE_SAMPLE_IDENTITY")
            if manifest["kind"] == "device":
                require(manifest["review"]["reviewed"], "CAPTURE_NOT_REVIEWED")
                candidates.append(stages)
                row["classification"] = "DEVICE_RECEIPTS_STRUCTURALLY_VALID"
            else:
                previews.append(stages)
                row["classification"] = "SYNTHETIC_ONLY"
            row["reason"] = "VALIDATED_RECEIPT_STRUCTURE"
            row["stages_ns"] = stages
        except Rejected as error:
            row["reason"] = str(error)
        results.append(row)
    excluded = sum(r["classification"] == "EXCLUDED" for r in results)
    return {"schema_version": 1, "kind": manifest["kind"], "binding": manifest["binding"],
            "capture_sha256": capture_sha256, "input_count": len(results), "excluded_count": excluded,
            "avf_device_candidate_count": len(candidates), "avf_device_candidate_phases": phase_statistics(candidates),
            "synthetic_preview_count": len(previews), "synthetic_preview_phases": phase_statistics(previews),
            "tail_percentiles": "NOT_REPORTED", "authenticity": "NOT_AUTHENTICATED_BY_THIS_TOOL",
            "device_verdict": "NOT_DETERMINED",
            "samples": results}


def run(store, manifest_path, samples_path):
    # Parse the exact bytes that were hashed. Receipt names are built only from verified hex digests.
    capture = store.read_bytes(samples_path)
    return evaluate(store.load_json(manifest_path), parse_json(capture), hashlib.sha256(capture).hexdigest(),
                    receipt_loader=lambda sha: store.read_bytes("receipts/" + sha + ".json"))


def main(argv=None):
    parser = SafeParser(prog="avf_benchmark", description=__doc__)
    parser.add_argument("--evidence-root", required=True, help="explicit absolute approved input root")
    parser.add_argument("--output-root", required=True, help="explicit absolute approved output root")
    parser.add_argument("--manifest", required=True, help="relative reviewed expected manifest JSON")
    parser.add_argument("--samples", required=True, help="relative typed capture JSON")
    parser.add_argument("--output", required=True, help="new relative evidence/*.json")
    args = parser.parse_args(argv)
    try:
        require_supported_runtime()
        require(args.output.endswith(".json"), "OUTPUT_REJECTED")
        store = EvidenceStore(args.evidence_root, args.output_root)
        store.assert_new_output(args.output)
        result = run(store, args.manifest, args.samples)
        store.save_json(args.output, result)
        print(json.dumps({key: result[key] for key in ("kind", "input_count", "excluded_count", "avf_device_candidate_count", "synthetic_preview_count", "device_verdict")}, sort_keys=True))
        return 1 if result["excluded_count"] else 0
    except (Rejected, OSError) as error:
        print(json.dumps({"error": str(error) if isinstance(error, Rejected) else "IO_ERROR"}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
