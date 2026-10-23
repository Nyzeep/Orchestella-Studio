"""Unit tests for the pre-registered AX-07 stop conditions."""

import unittest

import compare


def report(build_id, device_alias, entries):
    return {
        "schema": "accelerator-perf-v1",
        "build_id": build_id,
        "device_alias": device_alias,
        "generated_at_utc": "2026-10-23T00:00:00Z",
        "protocol": {
            "min_samples": 10,
            "iqr_method": "linear-interpolation-on-sorted-values",
            "speedup_threshold": 1.5,
            "regression_permille": 50,
        },
        "scenarios": [
            {
                "scenario": scenario,
                "mode": mode,
                "n": 10,
                "median_ms": median,
                "failures": 0,
                "failure_codes": {},
                "insufficient_samples": False,
            }
            for scenario, mode, median in entries
        ],
    }


class VerdictTest(unittest.TestCase):
    def test_reaching_the_threshold_productises(self):
        verdict = compare.compare(
            report("b", "bench-a", [("small_project", "QEMU_ONLY", 2000.0)]),
            report("c", "bench-a", [("small_project", "QEMU_ONLY", 1000.0)]),
        )

        self.assertEqual("PRODUCTIZE", verdict["overall"])
        self.assertEqual(2.0, verdict["scenarios"][0]["speedup"])

    def test_below_the_threshold_is_a_negative_result_not_a_win(self):
        verdict = compare.compare(
            report("b", "bench-a", [("cold_start", "QEMU_ONLY", 2000.0)]),
            report("c", "bench-a", [("cold_start", "QEMU_ONLY", 1800.0)]),
        )

        self.assertEqual("BELOW_THRESHOLD", verdict["overall"])

    def test_slower_than_five_percent_is_a_regression(self):
        verdict = compare.compare(
            report("b", "bench-a", [("cold_start", "QEMU_ONLY", 1000.0)]),
            report("c", "bench-a", [("cold_start", "QEMU_ONLY", 1100.0)]),
        )

        self.assertEqual("REGRESSION", verdict["overall"])

    def test_a_regression_in_any_scenario_blocks_productisation(self):
        verdict = compare.compare(
            report("b", "bench-a", [
                ("small_project", "QEMU_ONLY", 2000.0),
                ("medium_project_j4", "QEMU_ONLY", 1000.0),
            ]),
            report("c", "bench-a", [
                ("small_project", "QEMU_ONLY", 1000.0),
                ("medium_project_j4", "QEMU_ONLY", 1200.0),
            ]),
        )

        self.assertEqual("REGRESSION", verdict["overall"])

    def test_missing_samples_make_the_verdict_incomplete(self):
        baseline = report("b", "bench-a", [("small_project", "QEMU_ONLY", 2000.0)])
        baseline["scenarios"][0]["n"] = 0
        baseline["scenarios"][0]["median_ms"] = None

        verdict = compare.compare(
            baseline,
            report("c", "bench-a", [("small_project", "QEMU_ONLY", 1000.0)]),
        )

        self.assertEqual("NO_DATA", verdict["scenarios"][0]["verdict"])
        self.assertEqual("INCOMPLETE", verdict["overall"])


class GuardTest(unittest.TestCase):
    def test_different_devices_are_not_comparable(self):
        with self.assertRaises(compare.VerdictError):
            compare.compare(
                report("b", "bench-a", [("cold_start", "QEMU_ONLY", 1000.0)]),
                report("c", "bench-b", [("cold_start", "QEMU_ONLY", 1000.0)]),
            )

    def test_protocol_drift_is_not_comparable(self):
        candidate = report("c", "bench-a", [("cold_start", "QEMU_ONLY", 1000.0)])
        candidate["protocol"]["speedup_threshold"] = 1.2

        with self.assertRaises(compare.VerdictError):
            compare.compare(
                report("b", "bench-a", [("cold_start", "QEMU_ONLY", 1000.0)]), candidate)

    def test_no_shared_scenario_is_an_error(self):
        with self.assertRaises(compare.VerdictError):
            compare.compare(
                report("b", "bench-a", [("cold_start", "QEMU_ONLY", 1000.0)]),
                report("c", "bench-a", [("small_project", "QEMU_ONLY", 1000.0)]),
            )


if __name__ == "__main__":
    unittest.main()
