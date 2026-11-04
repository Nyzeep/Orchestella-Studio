# SPDX-License-Identifier: GPL-3.0-or-later
"""Behavior tests for the production stage trace structural validator (ticket 27).

Synthetic/host-only: no device, no receipts, no authenticity claim. The ticket
22 offline synthetic input is asserted to be explicitly rejected, and a success
group is asserted to require complete evidence.
"""
from pathlib import Path
import json
import sys
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

from avf_stage_trace_schema import (
    Rejected, SCHEMA_NAME, SCHEMA_VERSION, STAGES, STOP_FACTS,
    classify_success, validate_end, validate_event, validate_trace,
)

IDENTITY = {"owner_alias": "instance-0f3a", "session_id": "s-9c1f2b3a-1111-2222-3333-444455556666",
            "generation": "gen-1", "vm_alias": "qemu"}
HEADER = {"schema_version": SCHEMA_VERSION, "schema": SCHEMA_NAME, "kind": "file_header"}


def event(stage, t_ns, route="owner", identity=None):
    return {"schema_version": SCHEMA_VERSION, "schema": SCHEMA_NAME, "kind": "stage_event",
            "provenance": "production", "clock": "android_elapsed_realtime_ns",
            "identity": identity or IDENTITY, "stage": stage, "route": route, "t_ns": t_ns}


def end_record(t_ns, observed=None, missing=None, stop_facts=None, state="ENDED",
               reason="NONE", last_stage=None):
    observed = list(STAGES) if observed is None else observed
    if missing is None:
        missing = [stage for stage in STAGES if stage not in observed]
    return {"schema_version": SCHEMA_VERSION, "schema": SCHEMA_NAME, "kind": "trace_end",
            "provenance": "production", "clock": "android_elapsed_realtime_ns",
            "identity": IDENTITY, "state": state, "reason": reason,
            "last_stage": last_stage if last_stage is not None else (observed[-1] if observed else "not_observed"),
            "observed_gates": observed, "missing_gates": missing,
            "stop_facts": list(STOP_FACTS) if stop_facts is None else stop_facts,
            "t_ns": t_ns}


def full_success_events():
    """A complete 16-gate sample in the canonical chain order (structure only).

    The validator does not enforce this order; the complete success group just
    needs every gate with its correct route class and non-backwards time.
    """
    order = [
        ("launch_requested", "owner"), ("preflight_begin", "owner"),
        ("preflight_end", "owner"), ("inputs_begin", "owner"),
        ("inputs_verified", "owner"), ("manager_begin", "owner"),
        ("vm_created", "owner"), ("run_begin", "owner"), ("run_returned", "owner"),
        ("vm_running_observed", "owner"), ("vsock_connected", "owner"),
        ("agent_authenticated", "authenticated_single_reader"),
        ("guest_command_ok", "authenticated_single_reader"),
        ("services_ready", "authenticated_single_reader"),
        ("stop_requested", "owner"), ("stop_completed", "owner"),
    ]
    return [event(stage, 1000 + 100 * index, route) for index, (stage, route) in enumerate(order)]


def production_order_events():
    """The real QEMU production partial order (bootstrap before manager gates)."""
    order = [
        ("launch_requested", "owner"), ("inputs_begin", "owner"),
        ("inputs_verified", "owner"), ("run_begin", "owner"),
        ("vm_created", "owner"), ("run_returned", "owner"),
        ("vsock_connected", "owner"), ("agent_authenticated", "authenticated_single_reader"),
        ("guest_command_ok", "authenticated_single_reader"),
        ("services_ready", "authenticated_single_reader"),
        ("stop_requested", "owner"), ("stop_completed", "owner"),
    ]
    return [event(stage, 1000 + 100 * index, route) for index, (stage, route) in enumerate(order)]


def full_trace_lines():
    lines = [HEADER] + full_success_events() + [end_record(2700)]
    return [json_line(obj) for obj in lines]


def json_line(obj):
    return json.dumps(obj, separators=(",", ":"))


class ProductionStageTraceSchemaTests(unittest.TestCase):
    def test_valid_complete_trace_passes_and_classifies_success(self):
        lines = full_trace_lines()
        _header, events, end = validate_trace(lines)
        self.assertEqual(16, len(events))
        self.assertEqual("ENDED", end["state"])
        self.assertTrue(classify_success(lines))

    def test_real_partial_order_is_valid_structure_even_off_the_canonical_chain(self):
        lines = [HEADER] + production_order_events() + [
            end_record(2200, observed=[obj["stage"] for obj in production_order_events()])]
        as_lines = [json_line(obj) for obj in lines]
        _header, events, _end = validate_trace(as_lines)
        self.assertEqual(12, len(events))

    def test_missing_required_gate_rejects_the_success_group(self):
        lines = full_trace_lines()
        # Drop the services_ready event and fix the end record consistently:
        # the structure stays valid, but a success group needs every gate.
        parsed = [json.loads(line) for line in lines]
        events = [obj for obj in parsed if obj.get("kind") == "stage_event"
                  and obj["stage"] != "services_ready"]
        observed = [obj["stage"] for obj in events]
        end = end_record(2700, observed=observed)
        rebuilt = [HEADER] + events + [end]
        as_lines = [json_line(obj) for obj in rebuilt]
        validate_trace(as_lines)
        with self.assertRaises(Rejected) as raised:
            classify_success(as_lines)
        self.assertEqual("SUCCESS_GROUP_REJECTED", raised.exception.code)

    def test_missing_stop_fact_rejects_the_success_group(self):
        parsed = [json.loads(line) for line in full_trace_lines()]
        end = [obj for obj in parsed if obj.get("kind") == "trace_end"][0]
        end["stop_facts"] = ["owned_vm_stopped", "owned_resources_released"]
        as_lines = [json_line(obj) for obj in parsed]
        validate_trace(as_lines)
        with self.assertRaises(Rejected) as raised:
            classify_success(as_lines)
        self.assertEqual("SUCCESS_GROUP_REJECTED", raised.exception.code)

    def test_unterminated_trace_is_never_a_success_group(self):
        parsed = [json.loads(line) for line in full_trace_lines()]
        without_end = [obj for obj in parsed if obj.get("kind") != "trace_end"]
        as_lines = [json_line(obj) for obj in without_end]
        with self.assertRaises(Rejected) as raised:
            validate_trace(as_lines)
        self.assertEqual("TRACE_UNTERMINATED", raised.exception.code)

    def test_offline_synthetic_input_is_explicitly_rejected(self):
        synthetic = {"schema_version": 1, "kind": "offline_stage_trace",
                     "provenance": "synthetic_seam", "stage": "launch_requested"}
        for obj in (synthetic, dict(synthetic, kind="stage_event"),
                    dict(synthetic, provenance="synthetic_seam", kind="stage_event")):
            with self.subTest(kind=obj.get("kind"), provenance=obj.get("provenance")):
                with self.assertRaises(Rejected) as raised:
                    validate_event(obj)
                self.assertEqual("SYNTHETIC_INPUT_REJECTED", raised.exception.code)

    def test_wrong_schema_name_or_version_is_rejected_not_reinterpreted(self):
        for mutate in ({"schema": "something_else"}, {"schema_version": 2}):
            obj = event("launch_requested", 1000)
            obj.update(mutate)
            with self.subTest(mutate=mutate):
                with self.assertRaises(Rejected) as raised:
                    validate_event(obj)
                self.assertEqual("SCHEMA_REJECTED", raised.exception.code)

    def test_extra_or_missing_keys_are_rejected(self):
        obj = event("launch_requested", 1000)
        obj["message"] = "should never appear"
        with self.assertRaises(Rejected):
            validate_event(obj)
        slim = event("launch_requested", 1000)
        slim.pop("route")
        with self.assertRaises(Rejected):
            validate_event(slim)

    def test_route_enforcement_matches_the_offline_gate_classes(self):
        with self.assertRaises(Rejected) as raised:
            validate_event(event("agent_authenticated", 1000, route="owner"))
        self.assertEqual("ROUTE_REJECTED", raised.exception.code)
        with self.assertRaises(Rejected) as raised:
            validate_event(event("launch_requested", 1000,
                                 route="authenticated_single_reader"))
        self.assertEqual("ROUTE_REJECTED", raised.exception.code)
        validate_event(event("agent_authenticated", 1000,
                             route="authenticated_single_reader"))

    def test_observation_time_never_goes_backwards(self):
        lines = full_trace_lines()
        parsed = [__import__("json").loads(line) for line in lines]
        parsed[2]["t_ns"] = 50  # an event before its predecessor's time
        with self.assertRaises(Rejected) as raised:
            validate_trace([json_line(obj) for obj in parsed])
        self.assertEqual("ORDER_REJECTED", raised.exception.code)

    def test_duplicate_stage_is_rejected(self):
        parsed = [__import__("json").loads(line) for line in full_trace_lines()]
        parsed.insert(3, parsed[2])
        with self.assertRaises(Rejected) as raised:
            validate_trace([json_line(obj) for obj in parsed])
        self.assertEqual("DUPLICATE_REJECTED", raised.exception.code)

    def test_end_record_must_match_the_events_actually_present(self):
        parsed = [json.loads(line) for line in full_trace_lines()]
        parsed[-1]["observed_gates"] = ["launch_requested"]
        parsed[-1]["missing_gates"] = [s for s in STAGES if s != "launch_requested"]
        parsed[-1]["last_stage"] = "launch_requested"
        with self.assertRaises(Rejected) as raised:
            validate_trace([json_line(obj) for obj in parsed])
        self.assertEqual("INCONSISTENT_REJECTED", raised.exception.code)

    def test_token_or_path_shaped_identity_is_rejected(self):
        for bad in ("Sup3rSecret", "tok_en", "/etc/passwd", "DeviceSerial"):
            with self.subTest(value=bad):
                with self.assertRaises(Rejected) as raised:
                    validate_event(event("launch_requested", 1000,
                                         identity=dict(IDENTITY, owner_alias=bad)))
                self.assertEqual("IDENTITY_REJECTED", raised.exception.code)

    def test_free_text_reason_is_rejected(self):
        obj = end_record(2200, reason="boom: /var/log/secret")
        with self.assertRaises(Rejected) as raised:
            validate_end(obj)
        self.assertEqual("REASON_REJECTED", raised.exception.code)

    def test_failed_and_fallback_states_stay_valid_structures(self):
        for state, reason, observed in (
                ("FAILED", "CREATE_FAILED", ["launch_requested", "inputs_begin"]),
                ("FALLBACK", "PERMISSION_DENIED", ["launch_requested", "preflight_begin"]),
                ("CANCELLED", "CANCELLED", ["launch_requested"]),
        ):
            with self.subTest(state=state):
                missing = [s for s in STAGES if s not in observed]
                obj = end_record(2200, observed=observed, missing=missing, stop_facts=[],
                                 state=state, reason=reason)
                validate_end(obj)


if __name__ == "__main__":
    unittest.main()
