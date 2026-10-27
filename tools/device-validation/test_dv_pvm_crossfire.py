"""Tests for the DV-PVM-02b crossfire driver's perf-attribution seam.

Zero-device by design: the probes need a guest, the aggregation and the ledger
do not — and that is exactly where a mis-signed segment or a `None` read as 0
would hide. Every value here is a plain number: `None` is "not measured".
"""

import hashlib
import itertools
import unittest

import dv_pvm_crossfire


class FakeConsole:
    """Scripted GuestConsole double: pops (rc, text) per run(), keeps the
    commands so tests can assert which shell work did (or did not) happen."""

    def __init__(self, script):
        self._script = list(script)
        self.commands = []

    def run(self, command, index, timeout_s):
        self.commands.append(command)
        rc, text, elapsed = self._script.pop(0)
        return rc, text, elapsed


def segment_round(index=0, preprocess=20_000, assemble=9_000,
                  transport_up=1_200, transport_down=800, distcc=85_000,
                  local_compile=30_000, dot_i_bytes=94_000):
    """One round result with the fields `--profile-segments` would fill in."""
    transport_known = None not in (transport_up, transport_down)
    result = {"round": index, **dv_pvm_crossfire.blank_segment_fields()}
    result.update({
        "preprocess_ms_cold": preprocess,
        "preprocess_ms_warm": preprocess,
        "preprocess_ms": preprocess,
        "preprocess_ms_samples": [preprocess] * 3,
        "dot_i_bytes": dot_i_bytes,
        "assemble_ms": assemble,
        "assemble_ms_samples": [assemble] * 2,
        "transport_up_ms": transport_up,
        "transport_up_bytes": dot_i_bytes,
        "transport_down_ms": transport_down,
        "transport_down_bytes": 180 * 1024,
        "transport_ms": (transport_up + transport_down) if transport_known else None,
        "transport_bytes": (dot_i_bytes + 180 * 1024) if transport_known else None,
        "local_compile_ms": local_compile,
        "distcc_ms": distcc,
        "perf_probe_errors": [],
    })
    return result


class BlankSegmentFieldsTest(unittest.TestCase):
    def test_every_segment_field_starts_as_none(self):
        fields = dv_pvm_crossfire.blank_segment_fields()
        self.assertEqual(set(dv_pvm_crossfire.SEGMENT_ROUND_FIELDS), set(fields))
        for name, value in fields.items():
            self.assertIsNone(value, f"{name} must start unmeasured, not 0")

    def test_keeps_the_02b_placeholder_fields(self):
        # The pre-existing stub keys must keep their meaning: present, None.
        fields = dv_pvm_crossfire.blank_segment_fields()
        self.assertIn("preprocess_ms", fields)
        self.assertIn("transport_ms", fields)
        self.assertIn("perf_probe_errors", fields)


class ComputeLedgerTest(unittest.TestCase):
    def test_all_fields_present_does_the_arithmetic(self):
        ledger = dv_pvm_crossfire.compute_ledger(
            distcc_ms=85_000, preprocess_ms=20_000, transport_ms=2_000,
            local_compile_ms=30_000, assemble_ms=9_000)
        baseline = dv_pvm_crossfire.PAYLOAD_COMPILE_BASELINE_MS
        self.assertEqual(85_000 - (20_000 + 2_000 + baseline),
                         ledger["unexplained_ms"])
        self.assertEqual(30_000 - (20_000 + 9_000), ledger["local_gap_ms"])

    def test_missing_transport_zeroes_nothing(self):
        ledger = dv_pvm_crossfire.compute_ledger(
            distcc_ms=85_000, preprocess_ms=20_000, transport_ms=None,
            local_compile_ms=30_000, assemble_ms=9_000)
        self.assertIsNone(ledger["unexplained_ms"])
        self.assertNotEqual(0, ledger["unexplained_ms"])
        # The calibration check uses a different set of inputs, so it lives.
        self.assertEqual(1_000, ledger["local_gap_ms"])

    def test_missing_preprocess_fails_both_sides(self):
        ledger = dv_pvm_crossfire.compute_ledger(
            distcc_ms=85_000, preprocess_ms=None, transport_ms=2_000,
            local_compile_ms=30_000, assemble_ms=9_000)
        self.assertIsNone(ledger["unexplained_ms"])
        self.assertIsNone(ledger["local_gap_ms"])

    def test_missing_local_reference_only_spares_the_self_check(self):
        ledger = dv_pvm_crossfire.compute_ledger(
            distcc_ms=85_000, preprocess_ms=20_000, transport_ms=2_000)
        self.assertEqual(85_000 - (20_000 + 2_000
                                   + dv_pvm_crossfire.PAYLOAD_COMPILE_BASELINE_MS),
                         ledger["unexplained_ms"])
        self.assertIsNone(ledger["local_gap_ms"])

    def test_failed_round_yields_none_not_an_exception(self):
        ledger = dv_pvm_crossfire.compute_ledger(None, None, None, None, None)
        self.assertEqual({"unexplained_ms": None, "local_gap_ms": None}, ledger)


class MedianSampleTest(unittest.TestCase):
    def test_odd_count_picks_the_middle(self):
        self.assertEqual(9, dv_pvm_crossfire.median_sample([5, 9, 11]))

    def test_even_count_averages_the_two_middles(self):
        self.assertEqual(15, dv_pvm_crossfire.median_sample([10, 20]))

    def test_none_is_dropped_not_counted_as_zero(self):
        self.assertEqual(15, dv_pvm_crossfire.median_sample([None, 10, 20]))
        self.assertEqual(20, dv_pvm_crossfire.median_sample([None, None, 20]))

    def test_timeout_sentinel_is_dropped(self):
        # -1 is the console client's "no answer", never a fast sample.
        self.assertEqual(7, dv_pvm_crossfire.median_sample([-1, 7]))

    def test_no_usable_sample_is_none(self):
        self.assertIsNone(dv_pvm_crossfire.median_sample([None]))
        self.assertIsNone(dv_pvm_crossfire.median_sample([]))
        self.assertIsNone(dv_pvm_crossfire.median_sample(None))


class SummarizeSegmentsTest(unittest.TestCase):
    def test_disabled_reports_every_field_as_none(self):
        summary = dv_pvm_crossfire.summarize_segments([segment_round()],
                                                      enabled=False)
        self.assertEqual(set(dv_pvm_crossfire.SEGMENT_SUMMARY_FIELDS),
                         set(summary))
        for name, value in summary.items():
            self.assertIsNone(value, f"{name} must be None when profiling is off")

    def test_disabled_merge_leaves_the_summary_untouched(self):
        summary = {"mode": "dv-pvm-02b-crossfire", "distcc_ms_p50": 85_000}
        before = dict(summary)
        merged = dv_pvm_crossfire.apply_segment_summary(
            summary, [segment_round()], enabled=False)
        self.assertEqual(before, merged)
        self.assertNotIn("preprocess_ms_p50", merged)

    def test_enabled_merge_adds_medians_and_the_round_lists(self):
        rounds = [segment_round(0, preprocess=20_000, transport_up=1_200,
                                transport_down=800),
                  segment_round(1, preprocess=22_000, transport_up=1_400,
                                transport_down=1_000)]
        summary = dv_pvm_crossfire.apply_segment_summary({}, rounds,
                                                         enabled=True)
        self.assertEqual(21_000, summary["preprocess_ms_p50"])
        self.assertEqual(2_200, summary["transport_ms_p50"])
        self.assertEqual(1_300, summary["transport_up_ms_p50"])
        self.assertEqual(900, summary["transport_down_ms_p50"])
        self.assertEqual([2_000, 2_400],
                         summary["segment_samples"]["transport_ms"])

    def test_enabled_medians_ignore_a_round_without_measurements(self):
        failed = {"round": 1, **dv_pvm_crossfire.blank_segment_fields()}
        summary = dv_pvm_crossfire.summarize_segments(
            [segment_round(0), failed], enabled=True)
        self.assertEqual(20_000, summary["preprocess_ms_p50"])
        self.assertEqual(2_000, summary["transport_ms_p50"])
        # The aligned list keeps the gap visible instead of shortening.
        self.assertEqual([20_000, None],
                         summary["segment_samples"]["preprocess_ms"])
        self.assertIsNone(summary["ledger"]["1"]["unexplained_ms"])

    def test_enabled_without_any_round_reports_none(self):
        summary = dv_pvm_crossfire.summarize_segments([], enabled=True)
        self.assertIsNone(summary["preprocess_ms_p50"])
        self.assertIsNone(summary["transport_ms_p50"])
        self.assertEqual({}, summary["ledger"])

    def test_ledger_is_keyed_by_round_and_closes(self):
        rounds = [segment_round(0, transport_down=None),
                  segment_round(1)]
        summary = dv_pvm_crossfire.summarize_segments(rounds, enabled=True)
        self.assertEqual({"0", "1"}, set(summary["ledger"]))
        self.assertIsNone(summary["ledger"]["0"]["unexplained_ms"])
        expected = 85_000 - (20_000 + 2_000
                             + dv_pvm_crossfire.PAYLOAD_COMPILE_BASELINE_MS)
        self.assertEqual(expected, summary["ledger"]["1"]["unexplained_ms"])
        self.assertEqual(1_000, summary["ledger"]["1"]["local_gap_ms"])


class RemoteCompileVerdictTest(unittest.TestCase):
    """The verdict that replaced `distcc_remote_seen`.

    The old metric was `"remote" in text.lower()`, and the local-fallback
    warning contains that word, so every round looked remote. These cases pin
    the replacement to the bridge's own record.
    """

    @staticmethod
    def evidence(outcome="COMPLETED", failure="NONE", bytes_in=2672,
                 bytes_out=1024):
        return {"connections": [{
            "outcome": outcome,
            "failure_code": failure,
            "bytes_in": bytes_in,
            "bytes_out": bytes_out,
            "duration_ms": 1050,
        }]}

    def test_a_completed_connection_with_a_response_is_remote(self):
        verdict = dv_pvm_crossfire.remote_compile_verdict(
            self.evidence(), "distcc[1] (dcc_scan_args) ...", 0)
        self.assertTrue(verdict["verdict"])
        self.assertEqual("REMOTE", verdict["reason"])
        self.assertEqual(2672, verdict["bytes_in"])
        self.assertEqual(1024, verdict["bytes_out"])
        self.assertFalse(verdict["truncated"])

    def test_local_fallback_is_not_remote_even_though_the_log_says_remote(self):
        # Verbatim shape of distcc's fallback warning: it contains "remote",
        # which is why the old substring test could never fail.
        text = ("distcc[4242] (dcc_note_state) ERROR: remote compilation of "
                "'/root/fixture.c' failed, retrying locally")
        verdict = dv_pvm_crossfire.remote_compile_verdict(
            self.evidence(bytes_out=0), text, 0)
        self.assertFalse(verdict["verdict"])
        self.assertEqual("LOCAL_FALLBACK", verdict["reason"])
        self.assertTrue(verdict["local_fallback"])
        # A refused job is not a truncated response; the two need different fixes.
        self.assertFalse(verdict["truncated"])

    def test_a_payload_rejection_names_its_code(self):
        # distcc echoes the server's SERR, so the fixed code is recoverable
        # without pulling the raw device JSON.
        text = ("distcc[7] (dcc_r_bulk) ERROR: remote compilation of "
                "'/root/fixture.c' failed, retrying locally\n"
                "ARGV_REJECTED:UNKNOWN_FLAG")
        verdict = dv_pvm_crossfire.remote_compile_verdict(
            self.evidence(bytes_out=87, failure="NONE"), text, 0)
        self.assertFalse(verdict["verdict"])
        self.assertEqual("PAYLOAD_REJECTED", verdict["reason"])
        self.assertEqual("ARGV_REJECTED:UNKNOWN_FLAG", verdict["payload_code"])

    def test_a_real_truncation_is_reported_as_one(self):
        verdict = dv_pvm_crossfire.remote_compile_verdict(
            self.evidence(bytes_out=0), "ERROR: exit code 108 (EXIT_TRUNCATED)", 0)
        self.assertFalse(verdict["verdict"])
        self.assertEqual("TRUNCATED", verdict["reason"])
        self.assertTrue(verdict["truncated"])

    def test_a_remote_compile_error_is_not_a_channel_problem(self):
        verdict = dv_pvm_crossfire.remote_compile_verdict(
            self.evidence(), "COMPILE_ERROR", 1)
        self.assertFalse(verdict["verdict"])
        self.assertEqual("COMPILE_ERROR", verdict["reason"])

    def test_an_answered_connection_that_did_not_complete_is_a_failure(self):
        verdict = dv_pvm_crossfire.remote_compile_verdict(
            self.evidence(outcome="CHANNEL_FAILED",
                          failure="AXP1_DATA_TIMEOUT"), "", 0)
        self.assertFalse(verdict["verdict"])
        self.assertEqual("CHANNEL_FAILED", verdict["reason"])
        self.assertEqual("AXP1_DATA_TIMEOUT", verdict["failure_code"])

    def test_a_response_too_short_to_hold_a_doto_is_not_a_response(self):
        verdict = dv_pvm_crossfire.remote_compile_verdict(
            self.evidence(bytes_out=dv_pvm_crossfire.MIN_DISTCC_RESPONSE_BYTES - 1),
            "", 0)
        self.assertFalse(verdict["verdict"])
        self.assertEqual("NO_RESPONSE", verdict["reason"])

    def test_missing_evidence_is_unknown_not_failed(self):
        # No array at all: the driver must not claim it proved anything.
        verdict = dv_pvm_crossfire.remote_compile_verdict({}, "", 0)
        self.assertIsNone(verdict["verdict"])
        self.assertEqual("NO_CONNECTION", verdict["reason"])
        self.assertIsNone(verdict["bytes_out"])

    def test_a_round_without_a_distcc_exit_status_is_unknown(self):
        verdict = dv_pvm_crossfire.remote_compile_verdict(self.evidence(), "", None)
        self.assertIsNone(verdict["verdict"])
        self.assertEqual("NO_DISTCC_RC", verdict["reason"])

    def test_a_nonzero_distcc_exit_is_a_failure(self):
        verdict = dv_pvm_crossfire.remote_compile_verdict(self.evidence(), "", 1)
        self.assertFalse(verdict["verdict"])
        self.assertEqual("DISTCC_FAILED", verdict["reason"])

    def test_the_last_connection_is_the_one_that_counts(self):
        evidence = {"connections": [
            {"outcome": "LIMIT_REJECTED", "failure_code": "NONE",
             "bytes_in": 0, "bytes_out": 0, "duration_ms": 1},
            {"outcome": "COMPLETED", "failure_code": "NONE",
             "bytes_in": 2672, "bytes_out": 1024, "duration_ms": 1050},
        ]}
        verdict = dv_pvm_crossfire.remote_compile_verdict(evidence, "", 0)
        self.assertTrue(verdict["verdict"])
        self.assertEqual(1024, verdict["bytes_out"])


class FixtureLanguageTest(unittest.TestCase):
    """The C fixture is the 02b baseline and must not drift; the C++ one adds
    exactly what makes the payload's C++ path observable (a try/catch and C
    linkage so the symbol names stay comparable)."""

    def test_the_default_fixture_is_the_unchanged_c_unit(self):
        text = dv_pvm_crossfire.build_fixture(2000)
        self.assertNotIn('extern "C"', text)
        self.assertNotIn("try {", text)
        self.assertIn("int puts(const char*);", text)
        self.assertIn("int fixture_entry(void) {", text)
        self.assertIn("int probe_fn_1999(int x) { return x + 1999; }", text)

    def test_the_cxx_fixture_keeps_the_probes_and_adds_an_exception(self):
        text = dv_pvm_crossfire.build_fixture(2000, "cxx")
        self.assertIn('extern "C" {', text)
        self.assertIn("try {", text)
        self.assertIn("catch (int value)", text)
        self.assertIn("throw x;", text)
        self.assertIn("int probe_fn_1999(int x) { return x + 1999; }", text)
        self.assertIn("int fixture_entry(void) {", text)

    def test_the_language_decides_where_the_fixture_lands_and_who_compiles_it(self):
        c_args = type("Args", (), {"language": "c"})()
        cxx_args = type("Args", (), {"language": "cxx"})()
        self.assertEqual("/root/fixture.c", dv_pvm_crossfire.guest_fixture_path(c_args))
        self.assertEqual("/root/fixture.cpp", dv_pvm_crossfire.guest_fixture_path(cxx_args))
        self.assertEqual("clang", dv_pvm_crossfire.guest_compiler(c_args))
        self.assertEqual("clang++", dv_pvm_crossfire.guest_compiler(cxx_args))


class StaticCompareCommandTest(unittest.TestCase):
    def test_the_base_command_targets_the_static_criterion(self):
        command = dv_pvm_crossfire.static_compare_command(
            "cmp.py", "cross.o", "ref.o")
        self.assertIn("--no-link", command)
        self.assertIn("--expect-arch", command)
        self.assertEqual("cross.o", command[command.index("--a") + 1])
        self.assertEqual("ref.o", command[command.index("--b") + 1])
        self.assertNotIn("--expect-producer", command)

    def test_the_producer_fingerprint_is_forwarded_when_given(self):
        command = dv_pvm_crossfire.static_compare_command(
            "cmp.py", "cross.o", "ref.o", expect_producer="22.1.8")
        self.assertEqual("22.1.8", command[command.index("--expect-producer") + 1])


class ReferenceCompileCommandTest(unittest.TestCase):
    """The reference must mirror the payload's cc1 codegen defaults or .text
    can never match: the payload injects -mframe-pointer=all for C++ only
    (fastlane-payload cc1_defaults.cpp), and C deliberately stays without it
    so the anchored hash 9fd1d91e... survives. Round 3 cxx-v3 compiled a
    genuine C++ reference without frame pointers (.text 16,088 B) against a
    remote 40,100 B and misread the gap as fixture pollution."""

    def test_the_c_reference_keeps_the_plain_command(self):
        self.assertEqual(
            "clang -c --target=aarch64-unknown-linux-android -O1 "
            "/root/fixture.c -o /root/ref.o",
            dv_pvm_crossfire.reference_compile_command("clang", "/root/fixture.c"))

    def test_the_cxx_reference_carries_the_payloads_frame_pointer_default(self):
        command = dv_pvm_crossfire.reference_compile_command(
            "clang++", "/root/fixture.cpp")
        self.assertTrue(command.startswith(
            "clang++ -c --target=aarch64-unknown-linux-android -O1 "
            "/root/fixture.cpp -o /root/ref.o"), command)
        # The DRIVER rejects the cc1 spelling outright (round 4 cxx-v4:
        # "unknown argument '-mframe-pointer=all'" in 1.4 s), and
        # -fno-omit-frame-pointer lowers to non-leaf, not all. Only the
        # -Xclang forward matches the payload's cc1 default exactly.
        self.assertIn(" -Xclang -mframe-pointer=all", command)
        self.assertNotIn(" -mframe-pointer=all -Xclang", command)


class AssembleProbeCommandTest(unittest.TestCase):
    """The assemble probe feeds the preprocessed unit back to the compiler.
    For C++ that content is preprocessed C++, but the `.i` name says C —
    without pinning the type, clang parses it as C and fails
    (round 3 cxx-v3: assemble_failed, assemble_ms=None)."""

    def test_the_c_probe_relies_on_the_i_suffix(self):
        args = type("Args", (), {"language": "c"})()
        self.assertEqual(
            "clang -c --target=aarch64-unknown-linux-android -O1 "
            "/root/fixture.i -o /root/fromdoti.o",
            dv_pvm_crossfire.assemble_probe_command(args))

    def test_the_cxx_probe_pins_the_preprocessed_cxx_type(self):
        args = type("Args", (), {"language": "cxx"})()
        self.assertEqual(
            "clang++ -c --target=aarch64-unknown-linux-android -O1 "
            "-x c++-cpp-output /root/fixture.i -o /root/fromdoti.o",
            dv_pvm_crossfire.assemble_probe_command(args))


class InjectFixtureHashGateTest(unittest.TestCase):
    """`already-present` trusted the byte COUNT only; a same-size stale
    fixture passed silently. The gate must be content (sha256), and an
    unverifiable hash must fail closed into a re-push."""

    FIXTURE = b"int probe_fn_0(int x) { return x; }\n"

    @staticmethod
    def _sha_reply(digest):
        return (0, f"{digest}  /data/only/path-needed\n", 1.0)

    def _local_digest(self):
        return hashlib.sha256(self.FIXTURE).hexdigest()

    def test_a_hash_verified_copy_is_hash_match_and_never_repushed(self):
        console = FakeConsole([
            (0, f"{len(self.FIXTURE)}\n", 1.0),
            self._sha_reply(self._local_digest()),
        ])
        result = dv_pvm_crossfire.inject_fixture(
            None, console, self.FIXTURE, 30, itertools.count().__next__)
        self.assertTrue(result["injected"])
        self.assertEqual("hash-match", result["path"])
        self.assertEqual(2, len(console.commands))  # wc + sha256sum only

    def test_same_size_stale_content_is_detected_and_overwritten(self):
        console = FakeConsole([
            (0, f"{len(self.FIXTURE)}\n", 1.0),   # wc: size matches
            self._sha_reply("0" * 64),            # sha256sum: wrong content
            (0, "", 1.0),                         # 9p mount+cp attempt
            (0, f"{len(self.FIXTURE)}\n", 1.0),   # wc again: still same size
            self._sha_reply("0" * 64),            # sha256sum: still stale
            (0, "", 1.0),                         # push: rm -f
            (0, "", 1.0),                         # push: printf chunk
            (0, f"{len(self.FIXTURE)}\n", 1.0),   # push: base64 -d && wc
        ])
        result = dv_pvm_crossfire.inject_fixture(
            None, console, self.FIXTURE, 30, itertools.count().__next__)
        self.assertTrue(result["injected"])
        self.assertEqual("console-base64", result["path"])
        # The overwrite is the console push (rm -f first), not a pass-through.
        self.assertIn("rm -f", console.commands[-3])

    def test_an_unverifiable_hash_fails_closed_into_a_repush(self):
        console = FakeConsole([
            (0, f"{len(self.FIXTURE)}\n", 1.0),   # wc: size matches
            (127, "", 1.0),                       # sha256sum: not found
            (0, "", 1.0),                         # 9p mount+cp attempt
            (0, f"{len(self.FIXTURE)}\n", 1.0),   # wc again
            (127, "", 1.0),                       # sha256sum: still absent
            (0, "", 1.0),                         # push: rm -f
            (0, "", 1.0),                         # push: printf chunk
            (0, f"{len(self.FIXTURE)}\n", 1.0),   # push: base64 -d && wc
        ])
        result = dv_pvm_crossfire.inject_fixture(
            None, console, self.FIXTURE, 30, itertools.count().__next__)
        self.assertTrue(result["injected"])
        self.assertEqual("console-base64", result["path"])

    def test_size_mismatch_never_bothers_hashing(self):
        console = FakeConsole([
            (0, "1\n", 1.0),                      # wc: wrong size
            (0, "", 1.0),                         # 9p mount+cp
            (0, f"{len(self.FIXTURE)}\n", 1.0),   # wc: right size now
            self._sha_reply(self._local_digest()),
        ])
        result = dv_pvm_crossfire.inject_fixture(
            None, console, self.FIXTURE, 30, itertools.count().__next__)
        self.assertTrue(result["injected"])
        self.assertEqual("9p-downloads", result["path"])
        # No sha256sum before the 9p attempt: size said no, hashing is moot.
        self.assertNotIn("sha256sum", console.commands[0])
        self.assertIn("sha256sum", console.commands[3])


class ReferenceFreshnessTest(unittest.TestCase):
    """A stale /root/ref.o must never pose as this round's reference: the
    chroot has no `rm` applet, so the driver zeroes it with a sh builtin
    before the compile and refuses an empty product afterwards."""

    def test_prepare_zeroes_the_stale_object_and_reports_trust(self):
        console = FakeConsole([(0, "", 1.0)])
        self.assertTrue(dv_pvm_crossfire.prepare_reference_object(
            console, "", itertools.count().__next__))
        self.assertEqual([": > /root/ref.o"], console.commands)

    def test_prepare_applies_the_chroot_prefix(self):
        console = FakeConsole([(0, "", 1.0)])
        dv_pvm_crossfire.prepare_reference_object(console, "/ch", itertools.count().__next__)
        self.assertEqual([": > /ch/root/ref.o"], console.commands)

    def test_a_failed_truncate_is_reported_untrusted(self):
        console = FakeConsole([(1, "read-only\n", 1.0)])
        self.assertFalse(dv_pvm_crossfire.prepare_reference_object(
            console, "", itertools.count().__next__))

    def test_reference_size_gate_accepts_a_real_object_only(self):
        ok = FakeConsole([(0, "245424\n", 1.0)])
        self.assertTrue(dv_pvm_crossfire.reference_object_present(
            ok, "", itertools.count().__next__))
        empty = FakeConsole([(0, "0\n", 1.0)])
        self.assertFalse(dv_pvm_crossfire.reference_object_present(
            empty, "", itertools.count().__next__))
        junk = FakeConsole([(0, "wc: bad\n", 1.0)])
        self.assertFalse(dv_pvm_crossfire.reference_object_present(
            junk, "", itertools.count().__next__))
        failed = FakeConsole([(1, "", 1.0)])
        self.assertFalse(dv_pvm_crossfire.reference_object_present(
            failed, "", itertools.count().__next__))


class DownloadProbeInnerTest(unittest.TestCase):
    """Round 3 cxx-v3 burned on receiver self-harm: the chroot's `sh` ships
    without `sleep`/`rm`, the polling loop spun with no delay, and the trap's
    `rm: not found` became the LAST output line — so `received` parsed as
    None even though rc was 0. The rebuilt script may degrade (busybox
    fallback chain, bounded spins) but its last stdout line must always be
    the measured byte count."""

    def test_the_last_output_is_always_the_measured_count(self):
        script = dv_pvm_crossfire.download_probe_inner(19990)
        self.assertTrue(script.rstrip().endswith("echo $SZ"), script[-120:])

    def test_no_trap_and_no_timeout_dependency(self):
        script = dv_pvm_crossfire.download_probe_inner(19990)
        self.assertNotIn("trap", script)
        self.assertNotIn("timeout ", script)

    def test_the_delay_chain_falls_back_to_busybox(self):
        script = dv_pvm_crossfire.download_probe_inner(19990)
        self.assertIn("/bin/busybox sleep 0.15", script)
        # Both polling loops carry the chain; neither may rely on plain sleep.
        self.assertEqual(2, script.count("usleep 150000"))

    def test_cleanup_runs_before_the_final_measurement(self):
        script = dv_pvm_crossfire.download_probe_inner(19990)
        self.assertLess(script.index("rm -f $D 2>/dev/null; "),
                        script.index("echo $SZ"))

    def test_the_background_nc_is_killed_by_pid_not_by_timeout(self):
        script = dv_pvm_crossfire.download_probe_inner(19990)
        self.assertIn("nc 192.168.127.254 19990 > $D & NC=$!;", script)
        self.assertIn("kill $NC 2>/dev/null;", script)

    def test_the_nc_exit_code_travels_as_evidence_before_the_count(self):
        # The count must stay the LAST line (the parser takes the final
        # digits); the nc verdict rides one line above it. Round 4 cxx-v4
        # burned on a zero-byte leg with no way to tell "refused" from
        # "connected but silent".
        script = dv_pvm_crossfire.download_probe_inner(19990)
        self.assertIn("NCR=$?;", script)
        self.assertIn("echo QA_NC_RC $NCR;", script)
        self.assertLess(script.index("echo QA_NC_RC $NCR;"),
                        script.rindex("echo $SZ"))


class HealChrootAppletsTest(unittest.TestCase):
    """The chroot's own busybox multi-call binary is the only cop that exists
    (round 4 cxx-v4/v5: the outer guest shell lacks `ln` AND `cp`), so the
    heal runs through `tool()` and copies busybox under each applet name —
    busybox dispatches on argv[0], a copy is as good as a symlink."""

    @staticmethod
    def _run():
        console = FakeConsole([
            (0, "QA_BB=yes\n", 1.0),
            (0, "", 1.0), (0, "", 1.0), (0, "", 1.0), (0, "", 1.0),
        ])
        result = dv_pvm_crossfire.heal_chroot_applets(
            console, lambda command: command, itertools.count().__next__)
        return console, result

    def test_copies_busybox_under_the_applet_names(self):
        console, result = self._run()
        self.assertTrue(result["applied"])
        self.assertEqual(["sleep", "rm", "timeout", "dd"], result["applets"])
        self.assertEqual("/bin/busybox cp /bin/busybox /bin/sleep",
                         console.commands[1])
        self.assertEqual("/bin/busybox cp /bin/busybox /bin/dd",
                         console.commands[4])

    def test_without_busybox_it_reports_and_does_not_copy(self):
        console = FakeConsole([(0, "QA_BB=no\n", 1.0)])
        result = dv_pvm_crossfire.heal_chroot_applets(
            console, lambda command: command, itertools.count().__next__)
        self.assertFalse(result["applied"])
        self.assertEqual("no-busybox", result["reason"])
        self.assertEqual(1, len(console.commands))


class DeviceSha256FallbackTest(unittest.TestCase):
    """The outer guest shell lacks sha256sum (round 4 cxx-v5: every round
    repushed 118 chunks for nothing); the chroot's busybox multi-call does
    not. The hash must be found in the chroot before the inject fails
    closed into a repush."""

    FIXTURE = b"int probe_fn_0(int x) { return x; }\n"
    DIGEST = hashlib.sha256(
        b"int probe_fn_0(int x) { return x; }\n").hexdigest()

    def test_the_chroot_busybox_hash_matches_without_a_repush(self):
        console = FakeConsole([
            (127, "", 1.0),                       # outer sha256sum: absent
            (0, f"{self.DIGEST}  /root/fixture.c\n", 1.0),  # chroot busybox
        ])
        digest = dv_pvm_crossfire.device_sha256(
            console, "/ch/root/fixture.c", itertools.count().__next__, 30,
            tool=lambda command: f"CHROOT({command})",
            chroot_path="/root/fixture.c")
        self.assertEqual(self.DIGEST, digest)
        self.assertEqual("CHROOT(/bin/busybox sha256sum /root/fixture.c"
                         " 2>/dev/null)", console.commands[1])

    def test_malformed_outer_output_falls_through_to_the_chroot(self):
        console = FakeConsole([
            (0, "not-a-hash\n", 1.0),
            (0, f"{self.DIGEST}  /root/fixture.c\n", 1.0),
        ])
        digest = dv_pvm_crossfire.device_sha256(
            console, "/ch/root/fixture.c", itertools.count().__next__, 30,
            tool=lambda command: command, chroot_path="/root/fixture.c")
        self.assertEqual(self.DIGEST, digest)

    def test_without_a_tool_it_stays_none(self):
        console = FakeConsole([(127, "", 1.0)])
        digest = dv_pvm_crossfire.device_sha256(
            console, "/x", itertools.count().__next__, 30)
        self.assertIsNone(digest)


class BrokenFixtureTest(unittest.TestCase):
    """W-C: the stderr capture chain is only proven by a REAL diagnostic.
    The corruption must be a syntax error both compilers reject."""

    def test_the_suffix_is_a_rejected_syntax_error(self):
        suffix = dv_pvm_crossfire.broken_fixture_suffix()
        self.assertIn("int broken( {", suffix)
        # Unbalanced braces survive even a lenient parse: not valid C or C++.
        self.assertNotEqual(suffix.count("{"), suffix.count("}"))


if __name__ == "__main__":
    unittest.main()
