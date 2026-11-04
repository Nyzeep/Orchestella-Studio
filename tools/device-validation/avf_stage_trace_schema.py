# SPDX-License-Identifier: GPL-3.0-or-later
"""Structural validator for the production stage trace schema v1 (ticket 27).

Structure only: no device, no network, no receipts, no authenticity claim. The
ticket 22 offline synthetic traces (`kind=offline_stage_trace`) are permanently
a separate kind and are explicitly rejected here instead of being silently
reinterpreted. A success classification requires the complete 16-gate chain,
all three independent stop facts and one terminated end record; a sample
missing any required evidence is rejected as a success group.
"""
from __future__ import annotations

import json
import re
import sys

SCHEMA_NAME = "orchestella_stage_trace"
SCHEMA_VERSION = 1
PROVENANCE = "production"

STAGES = (
    "launch_requested", "preflight_begin", "preflight_end", "inputs_begin",
    "inputs_verified", "manager_begin", "vm_created", "run_begin", "run_returned",
    "vm_running_observed", "vsock_connected", "agent_authenticated",
    "guest_command_ok", "services_ready", "stop_requested", "stop_completed",
)
ROUTES = ("owner", "authenticated_single_reader")
AUTHENTICATED_GATES = frozenset({"agent_authenticated", "guest_command_ok", "services_ready"})
STOP_FACTS = (
    "guest_shutdown_acknowledged", "owned_vm_stopped", "owned_resources_released",
)
AUTHENTICATED_STOP_FACTS = frozenset({"guest_shutdown_acknowledged"})
END_STATES = ("ENDED", "FAILED", "CANCELLED", "FALLBACK")
REASONS = frozenset({
    "NONE", "CANCELLED",
    "PERMISSION_DENIED", "API_BLOCKED", "CREATE_FAILED", "RUN_FAILED",
    "READINESS_FAILED", "GUEST_COMMAND_FAILED", "STOP_FAILED", "TIMEOUT", "UNKNOWN",
    "SINK_FAILED", "CAPTURE_LIMIT", "CLOCK_REJECTED", "STAGE_REJECTED",
    "ROUTE_REJECTED", "STOP_UNCONFIRMED",
})

IDENTITY_KEYS = frozenset({"owner_alias", "session_id", "generation", "vm_alias"})
EVENT_KEYS = frozenset({
    "schema_version", "schema", "kind", "provenance", "clock", "identity",
    "stage", "route", "t_ns",
})
END_KEYS = frozenset({
    "schema_version", "schema", "kind", "provenance", "clock", "identity",
    "state", "reason", "last_stage", "observed_gates", "missing_gates",
    "stop_facts", "t_ns",
})
HEADER_KEYS = frozenset({"schema_version", "schema", "kind"})

_FIELD = re.compile(r"[a-z0-9-]{1,64}")
_CLOCK = re.compile(r"[a-z_]{1,32}")
_REASON = re.compile(r"[A-Z_]{1,32}")


class Rejected(Exception):
    """Explicit, typed structural rejection; never a silent reinterpretation."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


def require(condition, code):
    if not condition:
        raise Rejected(code)


def field(value):
    require(type(value) is str and _FIELD.fullmatch(value) is not None, "IDENTITY_REJECTED")
    return value


def identity(value):
    require(type(value) is dict and set(value) == IDENTITY_KEYS, "IDENTITY_SCHEMA_REJECTED")
    for key in ("owner_alias", "session_id", "generation", "vm_alias"):
        field(value[key])
    return value


def integer(value, low=0, high=2**63 - 1):
    require(type(value) is int and not isinstance(value, bool) and low <= value <= high,
            "INTEGER_REJECTED")
    return value


def reject_synthetic(obj):
    """Ticket 22 synthetic traces stay permanently single-listed."""
    require(type(obj) is dict, "SCHEMA_REJECTED")
    require(obj.get("kind") != "offline_stage_trace", "SYNTHETIC_INPUT_REJECTED")
    require(obj.get("provenance") != "synthetic_seam", "SYNTHETIC_INPUT_REJECTED")
    return obj


def validate_header(obj):
    reject_synthetic(obj)
    require(set(obj) == HEADER_KEYS, "SCHEMA_REJECTED")
    require(obj.get("kind") == "file_header", "SCHEMA_REJECTED")
    require(obj.get("schema") == SCHEMA_NAME, "SCHEMA_REJECTED")
    require(obj.get("schema_version") == SCHEMA_VERSION, "SCHEMA_REJECTED")


def validate_event(obj):
    reject_synthetic(obj)
    require(set(obj) == EVENT_KEYS, "SCHEMA_REJECTED")
    require(obj.get("kind") == "stage_event", "SCHEMA_REJECTED")
    require(obj.get("schema") == SCHEMA_NAME, "SCHEMA_REJECTED")
    require(obj.get("schema_version") == SCHEMA_VERSION, "SCHEMA_REJECTED")
    require(obj.get("provenance") == PROVENANCE, "PROVENANCE_REJECTED")
    require(type(obj.get("clock")) is str and _CLOCK.fullmatch(obj["clock"]) is not None,
            "CLOCK_REJECTED")
    identity(obj.get("identity"))
    stage = obj.get("stage")
    require(stage in STAGES, "STAGE_REJECTED")
    route = obj.get("route")
    require(route in ROUTES, "ROUTE_REJECTED")
    expected_route = "authenticated_single_reader" if stage in AUTHENTICATED_GATES else "owner"
    require(route == expected_route, "ROUTE_REJECTED")
    integer(obj.get("t_ns"))
    return obj


def validate_end(obj):
    reject_synthetic(obj)
    require(set(obj) == END_KEYS, "SCHEMA_REJECTED")
    require(obj.get("kind") == "trace_end", "SCHEMA_REJECTED")
    require(obj.get("schema") == SCHEMA_NAME, "SCHEMA_REJECTED")
    require(obj.get("schema_version") == SCHEMA_VERSION, "SCHEMA_REJECTED")
    require(obj.get("provenance") == PROVENANCE, "PROVENANCE_REJECTED")
    require(type(obj.get("clock")) is str and _CLOCK.fullmatch(obj["clock"]) is not None,
            "CLOCK_REJECTED")
    identity(obj.get("identity"))
    require(obj.get("state") in END_STATES, "STATE_REJECTED")
    require(type(obj.get("reason")) is str and _REASON.fullmatch(obj["reason"]) is not None,
            "REASON_REJECTED")
    last_stage = obj.get("last_stage")
    require(last_stage == "not_observed" or last_stage in STAGES, "STAGE_REJECTED")
    observed = obj.get("observed_gates")
    missing = obj.get("missing_gates")
    require(type(observed) is list and type(missing) is list, "SCHEMA_REJECTED")
    require(all(stage in STAGES for stage in observed), "STAGE_REJECTED")
    require(len(set(observed)) == len(observed), "DUPLICATE_REJECTED")
    require(all(stage in STAGES for stage in missing), "STAGE_REJECTED")
    require(len(set(missing)) == len(missing), "DUPLICATE_REJECTED")
    require(set(observed).isdisjoint(missing), "SCHEMA_REJECTED")
    require(set(observed) | set(missing) == set(STAGES), "SCHEMA_REJECTED")
    if observed:
        require(last_stage == observed[-1], "SCHEMA_REJECTED")
    else:
        require(last_stage == "not_observed", "SCHEMA_REJECTED")
    facts = obj.get("stop_facts")
    require(type(facts) is list and len(set(facts)) == len(facts), "SCHEMA_REJECTED")
    require(all(fact in STOP_FACTS for fact in facts), "STOP_FACT_REJECTED")
    integer(obj.get("t_ns"))
    return obj


def parse_line(line):
    require(type(line) is str and line.strip() != "", "BLANK_LINE_REJECTED")
    try:
        obj = json.loads(line)
    except (ValueError, TypeError):
        raise Rejected("JSON_REJECTED") from None
    reject_synthetic(obj)
    return obj


def validate_trace(lines):
    """Validates one file's lines: header, bounded events, exactly one end line.

    Returns the parsed (header, events, end) tuple. Enforces the honest partial
    order: observation time never goes backwards, and the end record's gate
    list must match the events actually present.
    """
    require(len(lines) >= 3, "TRACE_TOO_SHORT")
    header = parse_line(lines[0])
    validate_header(header)
    events = []
    end = None
    last_t_ns = -1
    seen_stages = set()
    for line in lines[1:]:
        obj = parse_line(line)
        kind = obj.get("kind")
        if kind == "stage_event":
            require(end is None, "LINE_AFTER_END")
            validate_event(obj)
            stage = obj["stage"]
            require(stage not in seen_stages, "DUPLICATE_REJECTED")
            seen_stages.add(stage)
            require(obj["t_ns"] >= last_t_ns, "ORDER_REJECTED")
            last_t_ns = obj["t_ns"]
            events.append(obj)
        elif kind == "trace_end":
            require(end is None, "DUPLICATE_END_REJECTED")
            validate_end(obj)
            require(obj["t_ns"] >= last_t_ns, "ORDER_REJECTED")
            last_t_ns = obj["t_ns"]
            end = obj
        else:
            raise Rejected("KIND_REJECTED")
    require(end is not None, "TRACE_UNTERMINATED")
    observed = [event["stage"] for event in events]
    require(end["observed_gates"] == observed, "INCONSISTENT_REJECTED")
    require(set(end["stop_facts"]) <= set(STOP_FACTS), "STOP_FACT_REJECTED")
    return header, events, end


def classify_success(lines):
    """Success group: complete chain, all stop facts, terminated, production.

    Any missing required evidence is an explicit rejection, never a pass.
    """
    _header, events, end = validate_trace(lines)
    require(end["state"] == "ENDED", "SUCCESS_GROUP_REJECTED")
    observed = set(end["observed_gates"])
    require(observed == set(STAGES), "SUCCESS_GROUP_REJECTED")
    require(set(end["stop_facts"]) == set(STOP_FACTS), "SUCCESS_GROUP_REJECTED")
    require(len(events) == len(STAGES), "SUCCESS_GROUP_REJECTED")
    return True


def validate_file(path):
    """Validates a UTF-8 JSONL file; returns classify result and the trace."""
    with open(path, "r", encoding="utf-8") as handle:
        lines = handle.read().splitlines()
    trace = validate_trace(lines)
    try:
        success = classify_success(lines)
    except Rejected:
        success = False
    return success, trace


def main(argv):
    if len(argv) != 2:
        print("usage: avf_stage_trace_schema.py <trace.jsonl>", file=sys.stderr)
        return 2
    try:
        success, (_header, events, end) = validate_file(argv[1])
    except Rejected as rejection:
        print(f"REJECTED {rejection.code}")
        return 1
    verdict = "SUCCESS_GROUP" if success else "INCOMPLETE_TRACE"
    print(f"VALID {verdict} events={len(events)} state={end['state']} "
          f"stop_facts={len(end['stop_facts'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
