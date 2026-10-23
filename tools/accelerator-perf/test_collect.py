"""Unit tests for the AX-07 collector: fixed input in, fixed output out."""

import unittest
from pathlib import Path

import collect

HERE = Path(__file__).resolve().parent
FIXTURE = HERE / "fixtures" / "samples-dryrun.tsv"
HEADER = "\t".join(collect.COLUMNS)


def sample(**overrides):
    row = {
        "scenario": "cold_start",
        "mode": "QEMU_ONLY",
        "status": "ok",
        "wall_ms": "1000",
        "pre_ms": "10",
        "tx_ms": "0",
        "cc_ms": "900",
        "ld_ms": "90",
        "offload_permille": "0",
        "retries": "0",
        "fallbacks": "0",
        "peak_kb": "1024",
        "code": "NONE",
    }
    row.update(overrides)
    return "\t".join(row[column] for column in collect.COLUMNS)


class ParseTest(unittest.TestCase):
    def test_parses_the_committed_fixture(self):
        rows = collect.parse_samples(FIXTURE.read_text(encoding="utf-8"))

        self.assertEqual(103, len(rows))
        self.assertEqual({"cold_start", "small_project"}, set(
            row["scenario"] for row in rows if row["scenario"].startswith(("cold", "small"))
        ))

    def test_header_is_mandatory_and_exact(self):
        with self.assertRaises(collect.SampleError):
            collect.parse_samples("")
        with self.assertRaises(collect.SampleError):
            collect.parse_samples("scenario\tmode\n")
        # A header with a column missing is rejected: the layout is a contract.
        with self.assertRaises(collect.SampleError):
            collect.parse_samples("\t".join(collect.COLUMNS[:-1]) + "\n" + sample() + "\n")
        # A data row before the header is rejected too.
        with self.assertRaises(collect.SampleError):
            collect.parse_samples(sample() + "\n" + HEADER + "\n")

    def test_blank_lines_and_crlf_are_tolerated(self):
        text = (HEADER + "\r\n" + sample() + "\r\n\r\n").replace("\t", "\t")

        rows = collect.parse_samples(text)

        self.assertEqual(1, len(rows))

    def test_fixed_vocabularies_are_enforced(self):
        for overrides in (
            {"scenario": "invented_scenario"},
            {"mode": "GPU_LANE"},
            {"status": "skipped"},
            {"code": "because_it_was_slow"},
        ):
            with self.assertRaises(collect.SampleError):
                collect.parse_samples(HEADER + "\n" + sample(**overrides) + "\n")

    def test_ok_samples_must_not_carry_a_failure_code(self):
        with self.assertRaises(collect.SampleError):
            collect.parse_samples(HEADER + "\n" + sample(code="TIMEOUT") + "\n")

    def test_numeric_fields_are_validated(self):
        for overrides in (
            {"wall_ms": "-1"},
            {"wall_ms": "1.5"},
            {"offload_permille": "1001"},
            {"peak_kb": "big"},
        ):
            with self.assertRaises(collect.SampleError):
                collect.parse_samples(HEADER + "\n" + sample(**overrides) + "\n")

    def test_error_messages_never_echo_the_sample_content(self):
        try:
            collect.parse_samples(HEADER + "\n" + sample(scenario="/sdcard/secret") + "\n")
        except collect.SampleError as error:
            self.assertNotIn("/sdcard/secret", str(error))
            self.assertIn("line 2", str(error))


class BuildReportTest(unittest.TestCase):
    def setUp(self):
        self.rows = collect.parse_samples(
            HEADER + "\n" + "\n".join(sample(wall_ms=str(1000 + index)) for index in range(10))
        )

    def test_report_shape_and_protocol(self):
        report = collect.build_report(self.rows, "build-1", "bench-a", "2026-10-23T00:00:00Z")

        self.assertEqual("accelerator-perf-v1", report["schema"])
        self.assertEqual(10, report["protocol"]["min_samples"])
        self.assertEqual(1.5, report["protocol"]["speedup_threshold"])
        self.assertEqual(1, len(report["scenarios"]))
        entry = report["scenarios"][0]
        self.assertEqual(10, entry["n"])
        self.assertFalse(entry["insufficient_samples"])
        self.assertEqual(0, entry["failures"])

    def test_failures_are_retained_and_counted_by_fixed_code(self):
        rows = self.rows + collect.parse_samples(
            HEADER + "\n" + sample(status="fail", wall_ms="0", code="TIMEOUT") + "\n"
            + sample(status="fail", wall_ms="0", code="TIMEOUT") + "\n"
            + sample(status="fail", wall_ms="0", code="DISTCC_FALLBACK") + "\n"
        )

        entry = collect.build_report(
            rows, "build-1", "bench-a", "2026-10-23T00:00:00Z"
        )["scenarios"][0]

        self.assertEqual(3, entry["failures"])
        self.assertEqual({"TIMEOUT": 2, "DISTCC_FALLBACK": 1}, entry["failure_codes"])
        self.assertEqual(10, entry["n"], "failures never enter the distribution")

    def test_small_n_is_flagged_not_silently_accepted(self):
        rows = collect.parse_samples(HEADER + "\n" + sample() + "\n")

        entry = collect.build_report(
            rows, "build-1", "bench-a", "2026-10-23T00:00:00Z"
        )["scenarios"][0]

        self.assertEqual(1, entry["n"])
        self.assertTrue(entry["insufficient_samples"])

    def test_scenario_with_only_failures_has_no_distribution(self):
        rows = collect.parse_samples(
            HEADER + "\n" + sample(status="fail", wall_ms="0", code="ACCEL_IMAGE_MISSING") + "\n"
        )

        entry = collect.build_report(
            rows, "build-1", "bench-a", "2026-10-23T00:00:00Z"
        )["scenarios"][0]

        self.assertEqual(0, entry["n"])
        self.assertIsNone(entry["median_ms"])
        self.assertIsNone(entry["phases_ms"])
        self.assertTrue(entry["insufficient_samples"])

    def test_aliases_must_not_be_addresses_or_paths(self):
        # Addresses are composed, not spelled out, so no literal hardware address
        # or LAN address is ever committed; 192.0.2.0/24 is the documentation range.
        mac_like = ":".join(["aa"] * 6)
        for alias in ("192.0.2.10", mac_like, "/data/local/tmp"):
            with self.assertRaises(collect.SampleError):
                collect.build_report(self.rows, "build-1", alias, "2026-10-23T00:00:00Z")


class DeterminismTest(unittest.TestCase):
    def test_same_input_and_time_produce_identical_bytes(self):
        rows = collect.parse_samples(FIXTURE.read_text(encoding="utf-8"))

        first = collect.render(
            collect.build_report(rows, "dryrun-local", "bench-sim", "2026-10-23T00:00:00Z")
        )
        second = collect.render(
            collect.build_report(rows, "dryrun-local", "bench-sim", "2026-10-23T00:00:00Z")
        )

        self.assertEqual(first, second)
        self.assertEqual(first, (HERE / "fixtures" / "expected-report.json").read_text(
            encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
