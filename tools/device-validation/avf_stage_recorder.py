# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline, single-caller recorder seam; no Android source or benchmark receipt.

The caller serializes operations and supplies a prompt synchronous sink. This module
has no worker, queue, stream reader, device, network, or interruptible-I/O machinery.
Routes and stop facts check wiring only; they cannot attest physical execution.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from enum import Enum
import time

from avf_benchmark import CHAIN, alias
from avf_evidence_io import Rejected, json_bytes


class Stage(str, Enum):
    LAUNCH_REQUESTED = 'launch_requested'
    PREFLIGHT_BEGIN = 'preflight_begin'
    PREFLIGHT_END = 'preflight_end'
    INPUTS_BEGIN = 'inputs_begin'
    INPUTS_VERIFIED = 'inputs_verified'
    MANAGER_BEGIN = 'manager_begin'
    VM_CREATED = 'vm_created'
    RUN_BEGIN = 'run_begin'
    RUN_RETURNED = 'run_returned'
    VM_RUNNING_OBSERVED = 'vm_running_observed'
    VSOCK_CONNECTED = 'vsock_connected'
    AGENT_AUTHENTICATED = 'agent_authenticated'
    GUEST_COMMAND_OK = 'guest_command_ok'
    SERVICES_READY = 'services_ready'
    STOP_REQUESTED = 'stop_requested'
    STOP_COMPLETED = 'stop_completed'


class Route(str, Enum):
    OWNER = 'owner'
    AUTHENTICATED_SINGLE_READER = 'authenticated_single_reader'


class Failure(str, Enum):
    PERMISSION_DENIED = 'PERMISSION_DENIED'
    API_BLOCKED = 'API_BLOCKED'
    CREATE_FAILED = 'CREATE_FAILED'
    RUN_FAILED = 'RUN_FAILED'
    READINESS_FAILED = 'READINESS_FAILED'
    GUEST_COMMAND_FAILED = 'GUEST_COMMAND_FAILED'
    STOP_FAILED = 'STOP_FAILED'
    TIMEOUT = 'TIMEOUT'
    UNKNOWN = 'UNKNOWN'


class StopFact(str, Enum):
    GUEST_SHUTDOWN_ACKNOWLEDGED = 'guest_shutdown_acknowledged'
    OWNED_VM_STOPPED = 'owned_vm_stopped'
    OWNED_RESOURCES_RELEASED = 'owned_resources_released'


@dataclass(frozen=True, slots=True)
class CaptureIdentity:
    owner_alias: str
    session_id: str
    generation: int
    vm_alias: str

    def __post_init__(self):
        for value in (self.owner_alias, self.session_id, self.vm_alias):
            alias(value)
        if type(self.generation) is not int or not 1 <= self.generation <= 2**63 - 1:
            raise Rejected('IDENTITY_REJECTED')


class StageRecorder:
    def __init__(self, *, enabled, identity, clock=None, sink, max_bytes=32768):
        if type(enabled) is not bool or type(max_bytes) is not int or not 1 <= max_bytes <= 32768:
            raise Rejected('CONFIG_REJECTED')
        if enabled and (type(identity) is not CaptureIdentity or not callable(sink)
                        or (clock is not None and not callable(clock))):
            raise Rejected('CONFIG_REJECTED')
        self._state = 'DISABLED' if not enabled else 'ACTIVE'
        self._identity = identity if enabled else None
        self._clock = (time.monotonic_ns if clock is None else clock) if enabled else None
        self._clock_name = 'injected_monotonic_ns' if clock is not None else 'python_monotonic_ns'
        self._sink = sink if enabled else None
        self._event_count = 0
        self._bytes_written = 0
        self._max_bytes = max_bytes
        self._reason = 'NONE'
        self._last_ns = -1
        self._stop_facts = set()

    def _reject(self, reason):
        self._state, self._reason = 'FAILED', reason
        return reason

    def mark(self, stage, *, identity, route=Route.OWNER):
        if self._state == 'DISABLED':
            return 'DISABLED'
        if type(identity) is not CaptureIdentity or identity != self._identity:
            return 'IDENTITY_MISMATCH'
        if self._state != 'ACTIVE':
            return 'RETIRED'
        if type(stage) is not Stage:
            return self._reject('STAGE_REJECTED')
        if self._event_count >= len(CHAIN) or stage.value != CHAIN[self._event_count]:
            return self._reject('EVENT_ORDER_REJECTED')
        expected_route = (Route.AUTHENTICATED_SINGLE_READER if stage in
                          (Stage.AGENT_AUTHENTICATED, Stage.GUEST_COMMAND_OK, Stage.SERVICES_READY)
                          else Route.OWNER)
        if type(route) is not Route or route is not expected_route:
            return self._reject('ROUTE_REJECTED')
        if stage is Stage.STOP_COMPLETED and self._stop_facts != set(StopFact):
            return self._reject('STOP_UNCONFIRMED')
        try:
            timestamp = self._clock()
        except Exception:
            return self._reject('CLOCK_REJECTED')
        if type(timestamp) is not int or not 0 <= timestamp <= 2**63 - 1 or timestamp < self._last_ns:
            return self._reject('CLOCK_REJECTED')
        event = {'schema_version': 1, 'kind': 'offline_stage_trace', 'provenance': 'synthetic_seam',
                 'clock': self._clock_name, 'identity': asdict(self._identity),
                 'stage': stage.value, 't_ns': timestamp}
        data = json_bytes(event)
        if self._bytes_written + len(data) > self._max_bytes:
            return self._reject('CAPTURE_LIMIT')
        try:
            if self._sink(data) is False:
                return self._reject('SINK_FAILED')
        except Exception:
            return self._reject('SINK_FAILED')
        self._event_count += 1
        self._bytes_written += len(data)
        self._last_ns = timestamp
        return 'RECORDED'

    def fail(self, failure, *, identity):
        if self._state == 'DISABLED':
            return 'DISABLED'
        if type(identity) is not CaptureIdentity or identity != self._identity:
            return 'IDENTITY_MISMATCH'
        if self._state != 'ACTIVE':
            return 'RETIRED'
        return self._reject(failure.value if type(failure) is Failure else 'FAILURE_REJECTED')

    def cancel(self, *, identity):
        if self._state == 'DISABLED':
            return 'DISABLED'
        if type(identity) is not CaptureIdentity or identity != self._identity:
            return 'IDENTITY_MISMATCH'
        if self._state != 'ACTIVE':
            return 'RETIRED'
        self._state, self._reason = 'CANCELLED', 'CANCELLED'
        return self._reason

    def fallback(self, *, identity):
        if self._state == 'DISABLED':
            return 'DISABLED'
        if type(identity) is not CaptureIdentity or identity != self._identity:
            return 'IDENTITY_MISMATCH'
        if self._state != 'ACTIVE':
            return 'RETIRED'
        self._state, self._reason = 'FALLBACK', 'FALLBACK_OBSERVED'
        return self._reason

    def observe_stop(self, fact, *, identity, route=Route.OWNER):
        if self._state == 'DISABLED':
            return 'DISABLED'
        if type(identity) is not CaptureIdentity or identity != self._identity:
            return 'IDENTITY_MISMATCH'
        if self._state != 'ACTIVE':
            return 'RETIRED'
        if self._event_count != len(CHAIN) - 1 or type(fact) is not StopFact:
            return self._reject('STOP_UNCONFIRMED')
        expected = (Route.AUTHENTICATED_SINGLE_READER if fact is StopFact.GUEST_SHUTDOWN_ACKNOWLEDGED
                    else Route.OWNER)
        if type(route) is not Route or route is not expected:
            return self._reject('ROUTE_REJECTED')
        self._stop_facts.add(fact)
        return 'OBSERVED'

    def finish(self, *, identity):
        if self._state == 'DISABLED':
            return 'DISABLED'
        if type(identity) is not CaptureIdentity or identity != self._identity:
            return 'IDENTITY_MISMATCH'
        if self._state != 'ACTIVE':
            return self._reason
        if self._event_count != len(CHAIN):
            return self._reject('MISSING_GATE')
        self._state, self._reason = 'COMPLETE_OFFLINE', 'COMPLETE_OFFLINE'
        return self._reason

    def snapshot(self):
        return {'state': self._state, 'event_count': self._event_count, 'pending_count': 0,
                'bytes_written': self._bytes_written, 'reason': self._reason,
                'identity': asdict(self._identity) if self._identity is not None else None,
                'kernel_observation': 'not_observed', 'payload_callback': 'not_applicable',
                'real_receipt_count': 0, 'device_verdict': 'NOT_DETERMINED'}
