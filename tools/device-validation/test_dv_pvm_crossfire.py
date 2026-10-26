"""Tests for the DV-PVM-02b crossfire driver's perf-attribution seam.

Zero-device by design: the probes need a guest, the aggregation and the ledger
do not — and that is exactly where a mis-signed segment or a `None` read as 0
would hide. Every value here is a plain number: `None` is "not measured".
"""

import unittest

import dv_pvm_crossfire


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


if __name__ == "__main__":
    unittest.main()
