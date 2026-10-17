# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline recorder behavior only; no Android event source or device receipt."""
from pathlib import Path
import json
import os
import sys
import uuid
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

from avf_stage_recorder import CaptureIdentity, Route, Stage, StageRecorder, StopFact


class ManualClock:
    def __init__(self, values=None):
        self.calls = 0
        self.values = iter(values) if values is not None else None

    def __call__(self):
        self.calls += 1
        return next(self.values) if self.values is not None else self.calls * 100


class OfflineRecorderTests(unittest.TestCase):
    def setUp(self):
        self.identity = CaptureIdentity('fixture-owner', 'fixture-session', 1, 'fixture-vm')

    def test_enabled_marker_uses_local_clock_and_fixed_non_receipt_fields(self):
        clock, emitted = ManualClock([1234]), []
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=clock, sink=emitted.append)
        self.assertEqual(recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity), 'RECORDED')
        event = __import__('json').loads(emitted[0])
        self.assertEqual(event['stage'], 'launch_requested')
        self.assertEqual(event['t_ns'], 1234)
        self.assertEqual(event['identity'], {'owner_alias': 'fixture-owner', 'session_id': 'fixture-session',
                                             'generation': 1, 'vm_alias': 'fixture-vm'})
        self.assertEqual(event['clock'], 'injected_monotonic_ns')
        self.assertEqual(event['kind'], 'offline_stage_trace')
        self.assertEqual(event['provenance'], 'synthetic_seam')
        self.assertNotIn('receipt_sha256', event)
        self.assertEqual(recorder.snapshot()['event_count'], 1)
        self.assertEqual(recorder.snapshot()['pending_count'], 0)

    def test_wrong_identity_is_rejected_before_clock_or_sink(self):
        clock, emitted = ManualClock(), []
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=clock, sink=emitted.append)
        for identity in (CaptureIdentity('other-owner', 'fixture-session', 1, 'fixture-vm'),
                         CaptureIdentity('fixture-owner', 'other-session', 1, 'fixture-vm'),
                         CaptureIdentity('fixture-owner', 'fixture-session', 2, 'fixture-vm'),
                         CaptureIdentity('fixture-owner', 'fixture-session', 1, 'other-vm')):
            with self.subTest(identity=identity):
                self.assertEqual(recorder.mark(Stage.LAUNCH_REQUESTED, identity=identity), 'IDENTITY_MISMATCH')
        self.assertEqual(clock.calls, 0)
        self.assertEqual(emitted, [])
        self.assertEqual(recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity), 'RECORDED')

    def test_missing_gate_retires_capture_and_late_marker_cannot_complete_it(self):
        clock, emitted = ManualClock(), []
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=clock, sink=emitted.append)
        self.assertEqual(recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity), 'RECORDED')
        self.assertEqual(recorder.mark(Stage.RUN_RETURNED, identity=self.identity), 'EVENT_ORDER_REJECTED')
        self.assertEqual(recorder.mark(Stage.PREFLIGHT_BEGIN, identity=self.identity), 'RETIRED')
        self.assertEqual(recorder.finish(identity=self.identity), 'EVENT_ORDER_REJECTED')
        self.assertEqual(clock.calls, 1)
        self.assertEqual(len(emitted), 1)
        self.assertEqual(recorder.snapshot()['state'], 'FAILED')

    def test_invalid_or_regressing_clock_retires_without_publishing_bad_event(self):
        for value in (-1, True, 1.5, 'SYNTHETIC_SECRET', 2**63, 50):
            with self.subTest(value=value):
                emitted = []
                recorder = StageRecorder(enabled=True, identity=self.identity,
                                         clock=ManualClock([100, value]), sink=emitted.append)
                recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity)
                self.assertEqual(recorder.mark(Stage.PREFLIGHT_BEGIN, identity=self.identity), 'CLOCK_REJECTED')
                self.assertEqual(recorder.finish(identity=self.identity), 'CLOCK_REJECTED')
                self.assertEqual(len(emitted), 1)
                self.assertNotIn(b'SYNTHETIC_SECRET', b''.join(emitted))

    def test_agent_marker_requires_authenticated_single_reader_route(self):
        emitted = []
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=ManualClock(), sink=emitted.append)
        for stage in list(Stage)[:11]:
            self.assertEqual(recorder.mark(stage, identity=self.identity), 'RECORDED')
        self.assertEqual(recorder.mark(Stage.AGENT_AUTHENTICATED, identity=self.identity), 'ROUTE_REJECTED')
        self.assertEqual(recorder.finish(identity=self.identity), 'ROUTE_REJECTED')
        self.assertEqual(len(emitted), 11)

    def advance(self, recorder, stop_at=Stage.STOP_REQUESTED):
        for stage in Stage:
            if stage is Stage.STOP_COMPLETED:
                break
            route = Route.AUTHENTICATED_SINGLE_READER if stage in (
                Stage.AGENT_AUTHENTICATED, Stage.GUEST_COMMAND_OK, Stage.SERVICES_READY) else Route.OWNER
            self.assertEqual(recorder.mark(stage, identity=self.identity, route=route), 'RECORDED')
            if stage is stop_at:
                break

    def test_stop_marker_without_shutdown_stop_and_release_facts_is_rejected(self):
        emitted = []
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=ManualClock(), sink=emitted.append)
        self.advance(recorder)
        self.assertEqual(recorder.mark(Stage.STOP_COMPLETED, identity=self.identity), 'STOP_UNCONFIRMED')
        self.assertEqual(recorder.finish(identity=self.identity), 'STOP_UNCONFIRMED')
        self.assertEqual(len(emitted), 15)

    def test_complete_trace_is_offline_only_and_never_claims_kernel_or_device(self):
        emitted = []
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=ManualClock(), sink=emitted.append)
        self.advance(recorder)
        for fact in StopFact:
            route = Route.AUTHENTICATED_SINGLE_READER if fact is StopFact.GUEST_SHUTDOWN_ACKNOWLEDGED else Route.OWNER
            self.assertEqual(recorder.observe_stop(fact, identity=self.identity, route=route), 'OBSERVED')
        self.assertEqual(recorder.mark(Stage.STOP_COMPLETED, identity=self.identity), 'RECORDED')
        self.assertEqual(recorder.finish(identity=self.identity), 'COMPLETE_OFFLINE')
        self.assertEqual(recorder.snapshot()['state'], 'COMPLETE_OFFLINE')
        self.assertEqual(recorder.snapshot()['kernel_observation'], 'not_observed')
        self.assertEqual(recorder.snapshot()['payload_callback'], 'not_applicable')
        self.assertEqual(recorder.snapshot()['device_verdict'], 'NOT_DETERMINED')
        self.assertEqual(recorder.snapshot()['real_receipt_count'], 0)
        self.assertEqual(len(emitted), 16)
        self.assertEqual(recorder.mark(Stage.STOP_COMPLETED, identity=self.identity), 'RETIRED')
        self.assertEqual(len(emitted), 16)

    def test_failed_sink_retires_without_retry_or_foreign_error_text(self):
        clock, writes = ManualClock(), []
        def broken(data):
            writes.append(data)
            raise OSError('SYNTHETIC_SECRET_EXCEPTION')
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=clock, sink=broken)
        self.assertEqual(recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity), 'SINK_FAILED')
        self.assertEqual(recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity), 'RETIRED')
        self.assertEqual(recorder.finish(identity=self.identity), 'SINK_FAILED')
        self.assertEqual(clock.calls, 1)
        self.assertEqual(len(writes), 1)
        self.assertEqual(recorder.snapshot()['event_count'], 0)
        self.assertEqual(recorder.snapshot()['pending_count'], 0)
        self.assertNotIn('SYNTHETIC_SECRET', str(recorder.snapshot()))

    def test_cancellation_retires_capture_and_late_markers_do_not_resume(self):
        clock, emitted = ManualClock(), []
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=clock, sink=emitted.append)
        recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity)
        self.assertEqual(recorder.cancel(identity=self.identity), 'CANCELLED')
        self.assertEqual(recorder.cancel(identity=self.identity), 'RETIRED')
        self.assertEqual(recorder.mark(Stage.PREFLIGHT_BEGIN, identity=self.identity), 'RETIRED')
        self.assertEqual(recorder.finish(identity=self.identity), 'CANCELLED')
        self.assertEqual(recorder.snapshot()['state'], 'CANCELLED')
        self.assertEqual((clock.calls, len(emitted)), (1, 1))

    def test_fallback_keeps_attempt_out_of_complete_trace(self):
        clock, emitted = ManualClock(), []
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=clock, sink=emitted.append)
        recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity)
        self.assertEqual(recorder.fallback(identity=self.identity), 'FALLBACK_OBSERVED')
        self.assertEqual(recorder.mark(Stage.PREFLIGHT_BEGIN, identity=self.identity), 'RETIRED')
        self.assertEqual(recorder.finish(identity=self.identity), 'FALLBACK_OBSERVED')
        self.assertEqual(recorder.snapshot()['state'], 'FALLBACK')
        self.assertEqual((clock.calls, len(emitted)), (1, 1))

    def test_byte_budget_rejects_event_before_sink_without_pending_work(self):
        emitted = []
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=ManualClock(),
                                 sink=emitted.append, max_bytes=1)
        self.assertEqual(recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity), 'CAPTURE_LIMIT')
        self.assertEqual(recorder.finish(identity=self.identity), 'CAPTURE_LIMIT')
        self.assertEqual(emitted, [])
        self.assertEqual(recorder.snapshot()['bytes_written'], 0)
        self.assertEqual(recorder.snapshot()['pending_count'], 0)

    def test_enabled_capture_rejects_untyped_identity_and_unusable_boundaries(self):
        from avf_evidence_io import Rejected
        for changes in ({'identity': {'token': 'SYNTHETIC_SECRET'}}, {'sink': None}, {'clock': 7}):
            with self.subTest(changes=tuple(changes)):
                options = dict(enabled=True, identity=self.identity, clock=ManualClock(), sink=lambda _: None)
                options.update(changes)
                with self.assertRaisesRegex(Rejected, '^CONFIG_REJECTED$'):
                    StageRecorder(**options)

    def test_explicit_sink_rejection_is_failure_without_retries(self):
        writes = []
        def refused(data):
            writes.append(data)
            return False
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=ManualClock(), sink=refused)
        self.assertEqual(recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity), 'SINK_FAILED')
        self.assertEqual(recorder.finish(identity=self.identity), 'SINK_FAILED')
        self.assertEqual(len(writes), 1)

    def test_explicit_owner_failure_is_retained_without_raw_error_text(self):
        from avf_stage_recorder import Failure
        emitted = []
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=ManualClock(), sink=emitted.append)
        recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity)
        self.assertEqual(recorder.fail(Failure.API_BLOCKED, identity=self.identity), 'API_BLOCKED')
        self.assertEqual(recorder.finish(identity=self.identity), 'API_BLOCKED')
        self.assertEqual(recorder.snapshot()['reason'], 'API_BLOCKED')
        self.assertEqual(recorder.mark(Stage.PREFLIGHT_BEGIN, identity=self.identity), 'RETIRED')
        self.assertEqual(len(emitted), 1)

    def test_explicit_falsey_clock_still_supplies_its_timestamp(self):
        class FalseyClock:
            def __bool__(self):
                return False

            def __call__(self):
                return 4321
        emitted = []
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=FalseyClock(), sink=emitted.append)
        self.assertEqual(recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity), 'RECORDED')
        event = json.loads(emitted[0])
        self.assertEqual(event['clock'], 'injected_monotonic_ns')
        self.assertEqual(event['t_ns'], 4321)

    def test_default_clock_is_python_and_cannot_be_android_receipt(self):
        emitted = []
        recorder = StageRecorder(enabled=True, identity=self.identity, sink=emitted.append)
        self.assertEqual(recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity), 'RECORDED')
        event = json.loads(emitted[0])
        self.assertEqual(event['clock'], 'python_monotonic_ns')
        self.assertIs(type(event['t_ns']), int)
        self.assertNotIn('android_elapsed_realtime_nanos', emitted[0].decode('ascii'))

    def test_finish_with_any_missing_suffix_fails_and_cannot_be_resumed(self):
        for prefix in range(16):
            with self.subTest(prefix=prefix):
                recorder = StageRecorder(enabled=True, identity=self.identity, clock=ManualClock(), sink=lambda _: None)
                if prefix:
                    self.advance(recorder, list(Stage)[prefix - 1])
                self.assertEqual(recorder.finish(identity=self.identity), 'MISSING_GATE')
                self.assertEqual(recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity), 'RETIRED')

    def test_untyped_stage_and_foreign_failure_do_not_echo_payload(self):
        from avf_stage_recorder import Failure
        for operation in ('mark', 'fail'):
            recorder = StageRecorder(enabled=True, identity=self.identity, clock=ManualClock(), sink=lambda _: None)
            result = getattr(recorder, operation)('SYNTHETIC_SECRET_TOKEN', identity=self.identity)
            self.assertEqual(result, 'STAGE_REJECTED' if operation == 'mark' else 'FAILURE_REJECTED')
            self.assertNotIn('SYNTHETIC_SECRET', str(recorder.snapshot()))
        self.assertEqual(Failure.TIMEOUT.value, 'TIMEOUT')

    def test_each_guest_marker_rejects_owner_route(self):
        for stop_at in (Stage.AGENT_AUTHENTICATED, Stage.GUEST_COMMAND_OK, Stage.SERVICES_READY):
            with self.subTest(stage=stop_at):
                clock, emitted = ManualClock(), []
                recorder = StageRecorder(enabled=True, identity=self.identity, clock=clock, sink=emitted.append)
                self.advance(recorder, list(Stage)[list(Stage).index(stop_at) - 1])
                calls = clock.calls
                self.assertEqual(recorder.mark(stop_at, identity=self.identity), 'ROUTE_REJECTED')
                self.assertEqual(clock.calls, calls)

    def test_partial_stop_facts_never_certify_normal_stop(self):
        for omitted in StopFact:
            with self.subTest(omitted=omitted):
                recorder = StageRecorder(enabled=True, identity=self.identity, clock=ManualClock(), sink=lambda _: None)
                self.advance(recorder)
                for fact in StopFact:
                    if fact is omitted:
                        continue
                    route = Route.AUTHENTICATED_SINGLE_READER if fact is StopFact.GUEST_SHUTDOWN_ACKNOWLEDGED else Route.OWNER
                    self.assertEqual(recorder.observe_stop(fact, identity=self.identity, route=route), 'OBSERVED')
                self.assertEqual(recorder.mark(Stage.STOP_COMPLETED, identity=self.identity), 'STOP_UNCONFIRMED')

    def test_stop_fact_before_intent_or_wrong_route_fails_closed(self):
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=ManualClock(), sink=lambda _: None)
        self.assertEqual(recorder.observe_stop(StopFact.OWNED_VM_STOPPED, identity=self.identity), 'STOP_UNCONFIRMED')
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=ManualClock(), sink=lambda _: None)
        self.advance(recorder)
        self.assertEqual(recorder.observe_stop(StopFact.GUEST_SHUTDOWN_ACKNOWLEDGED, identity=self.identity), 'ROUTE_REJECTED')

    def test_wrong_generation_cannot_cancel_fail_finish_or_confirm_current_trace(self):
        from avf_stage_recorder import Failure
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=ManualClock(), sink=lambda _: None)
        wrong = CaptureIdentity('fixture-owner', 'fixture-session', 2, 'fixture-vm')
        for result in (recorder.cancel(identity=wrong), recorder.fallback(identity=wrong),
                       recorder.fail(Failure.UNKNOWN, identity=wrong), recorder.finish(identity=wrong),
                       recorder.observe_stop(StopFact.OWNED_VM_STOPPED, identity=wrong)):
            self.assertEqual(result, 'IDENTITY_MISMATCH')
        self.assertEqual(recorder.snapshot()['state'], 'ACTIVE')
        self.assertEqual(recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity), 'RECORDED')

    def test_clock_failure_redacts_foreign_message_and_does_not_write(self):
        def broken_clock():
            raise OSError('SYNTHETIC_SECRET_CLOCK')
        emitted = []
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=broken_clock, sink=emitted.append)
        self.assertEqual(recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity), 'CLOCK_REJECTED')
        self.assertEqual(emitted, [])
        self.assertNotIn('SYNTHETIC_SECRET', str(recorder.snapshot()))

    def test_existing_evidence_store_sink_publishes_independent_preservation(self):
        from avf_evidence_io import EvidenceStore
        root = Path(os.environ['AVF_VALIDATION_TEST_ROOT']) / ('recorder-' + uuid.uuid4().hex)
        store = EvidenceStore(root, root)
        def persist(data):
            store.save('traces/launch.json', data)
        recorder = StageRecorder(enabled=True, identity=self.identity, clock=ManualClock(), sink=persist)
        self.assertEqual(recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity), 'RECORDED')
        original, preserved = root / 'traces/launch.json', root / 'preservation/traces/launch.json'
        self.assertEqual(original.read_bytes(), preserved.read_bytes())
        self.assertNotEqual(original.stat().st_ino, preserved.stat().st_ino)
        self.assertEqual(json.loads(store.read_bytes('traces/launch.json'))['kind'], 'offline_stage_trace')
        # Reusing a fixed destination fails without replacing prior evidence.
        self.assertEqual(recorder.mark(Stage.PREFLIGHT_BEGIN, identity=self.identity), 'SINK_FAILED')
        self.assertEqual(json.loads(preserved.read_bytes())['stage'], 'launch_requested')
        self.assertEqual(recorder.snapshot()['event_count'], 1)

    def test_limits_identity_and_explicit_config_reject_invalid_types(self):
        from avf_evidence_io import Rejected
        for maximum in (0, -1, True, 32769, 1.5):
            with self.subTest(maximum=maximum):
                with self.assertRaisesRegex(Rejected, '^CONFIG_REJECTED$'):
                    StageRecorder(enabled=True, identity=self.identity, sink=lambda _: None, max_bytes=maximum)
        for generation in (0, True, -1, '1', 2**63):
            with self.assertRaisesRegex(Rejected, '^IDENTITY_REJECTED$'):
                CaptureIdentity('fixture-owner', 'fixture-session', generation, 'fixture-vm')

    def test_disabled_all_operations_leave_external_root_absent(self):
        from avf_evidence_io import EvidenceStore
        from avf_stage_recorder import Failure
        root = Path(os.environ['AVF_VALIDATION_TEST_ROOT']) / ('disabled-' + uuid.uuid4().hex)
        def forbidden_clock():
            self.fail('disabled capture read clock')
        def persist(data):
            EvidenceStore(root, root).save('trace.json', data)
        recorder = StageRecorder(enabled=False, identity=None, clock=forbidden_clock, sink=persist)
        for result in (recorder.mark(Stage.LAUNCH_REQUESTED, identity=self.identity),
                       recorder.cancel(identity=self.identity), recorder.fallback(identity=self.identity),
                       recorder.fail(Failure.UNKNOWN, identity=self.identity), recorder.finish(identity=self.identity),
                       recorder.observe_stop(StopFact.OWNED_VM_STOPPED, identity=self.identity)):
            self.assertEqual(result, 'DISABLED')
        self.assertFalse(root.exists())
        self.assertIsNone(recorder.snapshot()['identity'])
        self.assertEqual(recorder.snapshot()['pending_count'], 0)

    def test_disabled_capture_has_no_clock_sink_or_retained_identity(self):
        def forbidden(*args):
            self.fail('disabled capture touched the clock or sink')
        identity = CaptureIdentity('fixture-owner', 'fixture-session', 1, 'fixture-vm')
        recorder = StageRecorder(enabled=False, identity=identity, clock=forbidden, sink=forbidden)
        self.assertEqual(recorder.mark(Stage.LAUNCH_REQUESTED, identity=identity), 'DISABLED')
        self.assertEqual(recorder.finish(identity=identity), 'DISABLED')
        self.assertEqual(recorder.snapshot()['state'], 'DISABLED')
        self.assertEqual(recorder.snapshot()['event_count'], 0)
        self.assertEqual(recorder.snapshot()['pending_count'], 0)
        self.assertIsNone(recorder.snapshot()['identity'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
