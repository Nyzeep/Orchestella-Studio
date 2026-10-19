#!/usr/bin/env python3
"""Tests for the T7 matrix helper; no adb or device is required."""

from __future__ import annotations

import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("matrix.py")
SPEC = importlib.util.spec_from_file_location("qemu_tuning_matrix", MODULE_PATH)
matrix = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(matrix)


class SummaryTests(unittest.TestCase):
    def test_ten_samples_report_median_and_iqr(self) -> None:
        summary = matrix.summarize(list(range(1, 11)))
        self.assertEqual("measured", summary["status"])
        self.assertEqual(10, summary["samples"])
        self.assertAlmostEqual(5.5, summary["median_ms"])
        self.assertAlmostEqual(4.5, summary["iqr_ms"])
        self.assertEqual(1.0, summary["min_ms"])
        self.assertEqual(10.0, summary["max_ms"])

    def test_fewer_than_ten_samples_stay_insufficient(self) -> None:
        summary = matrix.summarize(list(range(9)))
        self.assertEqual("insufficient-samples", summary["status"])
        self.assertEqual("", summary["median_ms"])

    def test_empty_samples_stay_not_tested(self) -> None:
        self.assertEqual("not-tested", matrix.summarize([])["status"])


class SampleFileTests(unittest.TestCase):
    def test_unknown_variant_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "samples.json"
            path.write_text('{"unknown": {"boot-to-prompt": [1]}}', encoding="utf-8")
            with self.assertRaises(SystemExit):
                matrix.load_samples(path)

    def test_negative_or_boolean_sample_is_rejected(self) -> None:
        for value in ("-1", "true"):
            with self.subTest(value=value):
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / "samples.json"
                    path.write_text(
                        '{"baseline": {"boot-to-prompt": [' + value + "]}}",
                        encoding="utf-8",
                    )
                    with self.assertRaises(SystemExit):
                        matrix.load_samples(path)


class TsvTests(unittest.TestCase):
    def test_dry_run_rows_are_not_tested(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "matrix.tsv"
            matrix.write_tsv(output, {"available": False, "reason": "dry-run"}, "test-image", {}, True)
            with output.open(encoding="utf-8") as handle:
                rows = list(csv.DictReader(handle, delimiter="\t"))
            self.assertEqual(len(matrix.VARIANTS) * len(matrix.WORKLOADS), len(rows))
            self.assertTrue(all(row["status"] == "not-tested" for row in rows))

    def test_measured_row_carries_device_and_statistics(self) -> None:
        samples = {"baseline": {"boot-to-prompt": list(range(1, 11))}}
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "matrix.tsv"
            matrix.write_tsv(
                output,
                {"model": "TEST-MODEL", "android": "16", "battery_temp_c": 35.5, "thermal_status": "0"},
                "test-image",
                samples,
                False,
            )
            with output.open(encoding="utf-8") as handle:
                rows = list(csv.DictReader(handle, delimiter="\t"))
            measured = [row for row in rows if row["status"] == "measured"]
            self.assertEqual(1, len(measured))
            self.assertEqual("5.5", measured[0]["median_ms"])
            self.assertEqual("4.5", measured[0]["iqr_ms"])
            self.assertEqual("TEST-MODEL", measured[0]["device_model"])
            self.assertEqual("16", measured[0]["android"])


if __name__ == "__main__":
    unittest.main()
