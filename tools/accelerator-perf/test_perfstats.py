"""Unit tests for the fixed statistics used by every AX-07 report."""

import unittest

import perfstats


class PercentileTest(unittest.TestCase):
    def test_single_sample_is_the_sample(self):
        self.assertEqual(7.0, perfstats.percentile([7], 50))

    def test_median_of_odd_count_is_the_middle(self):
        self.assertEqual(3, perfstats.median([5, 1, 3]))

    def test_median_of_even_count_interpolates(self):
        self.assertEqual(2.5, perfstats.median([1, 2, 3, 4]))

    def test_bounds(self):
        values = [10, 20, 30]
        self.assertEqual(10, perfstats.percentile(values, 0))
        self.assertEqual(30, perfstats.percentile(values, 100))

    def test_rejects_empty_and_out_of_range(self):
        with self.assertRaises(ValueError):
            perfstats.percentile([], 50)
        with self.assertRaises(ValueError):
            perfstats.percentile([1, 2], 101)


class IqrTest(unittest.TestCase):
    def test_iqr_of_known_sample(self):
        # Q1 = 2.25, Q3 = 4.75 under linear interpolation.
        self.assertEqual(2.5, perfstats.iqr([1, 2, 3, 4, 5, 6]))

    def test_iqr_is_zero_for_a_constant_sample(self):
        self.assertEqual(0.0, perfstats.iqr([42, 42, 42]))


class SummariseTest(unittest.TestCase):
    def test_reports_the_five_required_fields(self):
        summary = perfstats.summarise(range(1, 11))

        self.assertEqual(10, summary["n"])
        self.assertEqual(5.5, summary["median_ms"])
        self.assertEqual(1, summary["min_ms"])
        self.assertEqual(10, summary["max_ms"])
        self.assertGreater(summary["iqr_ms"], 0)

    def test_rejects_empty_and_negative_samples(self):
        with self.assertRaises(ValueError):
            perfstats.summarise([])
        with self.assertRaises(ValueError):
            perfstats.summarise([1, -2])


class SpeedupTest(unittest.TestCase):
    def test_speedup_is_baseline_over_candidate(self):
        self.assertEqual(2.0, perfstats.speedup(2000, 1000))

    def test_rejects_a_non_positive_candidate(self):
        with self.assertRaises(ValueError):
            perfstats.speedup(1000, 0)


if __name__ == "__main__":
    unittest.main()
