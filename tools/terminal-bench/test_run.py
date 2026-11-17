#!/usr/bin/env python3
"""Offline tests for the terminal frame benchmark gate.

No device, no adb: every case feeds a generated logcat sample or calls the pure
helpers directly. Run with:

    python3 -B -m unittest discover -s tools/terminal-bench -p 'test_*.py'
"""

from __future__ import annotations

import contextlib
import copy
import io
import json
import tempfile
import unittest
from pathlib import Path

import run as bench

DEVICE = "Test Model"
ALIAS = "bench-a"
API = 34
FIXTURES_DIR = bench.SCRIPT_DIR / "fixtures"
SYNTHETIC_BASELINE = FIXTURES_DIR / "baseline.ci.json"
GOLDEN_LOG = FIXTURES_DIR / "golden.log"
REPLAY_BUCKET = "ci-replay/34"


def sample_results() -> dict:
    """Synthetic fixtures used only to exercise the parser and the thresholds."""
    return {
        "flood_seq": {
            "fixture": "flood_seq",
            "device": DEVICE,
            "alias": ALIAS,
            "api": API,
            "frames": 500,
            "janky": 10,
            "p50": 9.1,
            "p95": 14.8,
            "p99": 21.3,
            "max": 38.0,
            "fps": 58.2,
            "javaKb": 123,
            "nativeKb": 456,
            "cols": 42,
            "rows": 30,
            "refreshHz": 60.0,
            "durationMs": 10500,
            "drawP95": 5.2,
            "layoutP95": 1.8,
            "dropped": 0,
        },
        "flood_color": {
            "fixture": "flood_color",
            "device": DEVICE,
            "alias": ALIAS,
            "api": API,
            "frames": 400,
            "janky": 4,
            "p50": 8.0,
            "p95": 12.0,
            "p99": 18.0,
            "max": 30.0,
            "fps": 59.0,
            "javaKb": 100,
            "nativeKb": 400,
            "cols": 42,
            "rows": 30,
            "refreshHz": 60.0,
            "durationMs": 8000,
            "drawP95": 4.0,
            "layoutP95": 1.5,
            "dropped": 0,
        },
        "scroll_2000": {
            "fixture": "scroll_2000",
            "device": DEVICE,
            "alias": ALIAS,
            "api": API,
            "frames": 300,
            "janky": 3,
            "p50": 10.0,
            "p95": 16.0,
            "p99": 22.0,
            "max": 40.0,
            "fps": 57.0,
            "javaKb": 90,
            "nativeKb": 380,
            "cols": 42,
            "rows": 30,
            "refreshHz": 60.0,
            "durationMs": 7000,
            "drawP95": 5.0,
            "layoutP95": 2.0,
            "dropped": 0,
        },
        "type_100": {
            "fixture": "type_100",
            "device": DEVICE,
            "alias": ALIAS,
            "api": API,
            "frames": 200,
            "janky": 2,
            "p50": 7.0,
            "p95": 11.0,
            "p99": 15.0,
            "max": 25.0,
            "fps": 60.0,
            "javaKb": 80,
            "nativeKb": 360,
            "cols": 42,
            "rows": 30,
            "refreshHz": 60.0,
            "durationMs": 5000,
            "drawP95": 3.0,
            "layoutP95": 1.0,
            "dropped": 0,
        },
        "resize_font": {
            "fixture": "resize_font",
            "device": DEVICE,
            "alias": ALIAS,
            "api": API,
            "frames": 150,
            "janky": 1,
            "p50": 8.0,
            "p95": 13.0,
            "p99": 17.0,
            "max": 28.0,
            "fps": 59.5,
            "javaKb": 70,
            "nativeKb": 340,
            "cols": 42,
            "rows": 30,
            "refreshHz": 60.0,
            "durationMs": 4000,
            "drawP95": 4.0,
            "layoutP95": 1.5,
            "dropped": 0,
        },
    }


def logcat_text(results: dict, *, done: bool = True, errors: list[dict] | None = None) -> str:
    lines = ["--------- beginning of main"]
    for result in results.values():
        lines.append("I/OE_BENCH ( 4242): " + json.dumps(result))
    for entry in errors or []:
        lines.append("E/OE_BENCH_ERROR ( 4242): " + json.dumps(entry))
    if done:
        lines.append(
            'I/OE_BENCH_DONE ( 4242): {"fixtures":"[flood_seq, flood_color, scroll_2000, '
            'type_100, resize_font]","ok":true}'
        )
    return "\n".join(lines) + "\n"


def baseline_document(results: dict | None = None) -> dict:
    fixtures = {}
    for name, result in (results or sample_results()).items():
        fixtures[name] = {
            key: result[key]
            for key in bench.CAPTURE_FIELDS
            if key in result
        }
    return {
        "schema": bench.SCHEMA,
        "status": "captured",
        "capturedAt": "2026-01-01T00:00:00Z",
        "thresholds": dict(bench.DEFAULT_THRESHOLDS),
        "buckets": {f"{ALIAS}/{API}": {"alias": ALIAS, "api": API, "capturedAt": "2026-01-01T00:00:00Z", "fixtures": fixtures}},
    }


def call_main(argv: list[str]) -> tuple[int, str, str]:
    stdout, stderr = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        code = bench.main(argv)
    return code, stdout.getvalue(), stderr.getvalue()


class ParseTests(unittest.TestCase):
    def test_brief_sample_parses_five_fixtures_with_types(self) -> None:
        parsed = bench.parse_logcat(logcat_text(sample_results()))

        self.assertTrue(parsed["done"])
        self.assertEqual(parsed["errors"], [])
        self.assertEqual(set(parsed["results"]), set(bench.FIXTURES))
        seq = parsed["results"]["flood_seq"]
        self.assertEqual(seq["device"], DEVICE)
        self.assertIsInstance(seq["api"], int)
        self.assertIsInstance(seq["frames"], int)
        self.assertIsInstance(seq["p95"], float)
        self.assertIsInstance(seq["fps"], float)
        self.assertEqual(seq["janky"], 10)

    def test_crlf_is_tolerated(self) -> None:
        text = logcat_text(sample_results()).replace("\n", "\r\n")
        parsed = bench.parse_logcat(text)

        self.assertTrue(parsed["done"])
        self.assertEqual(len(parsed["results"]), 5)
        self.assertEqual(parsed["results"]["flood_seq"]["p95"], 14.8)

    def test_done_marker_is_not_a_fixture_result(self) -> None:
        done_only = 'I/OE_BENCH_DONE ( 4242): {"fixtures":"[...]","ok":true}\n'
        parsed = bench.parse_logcat(done_only)

        self.assertTrue(parsed["done"])
        self.assertEqual(parsed["results"], {})
        self.assertFalse(bench.is_result_tag("OE_BENCH_DONE"))
        self.assertTrue(bench.is_result_tag("OE_BENCH"))
        self.assertFalse(bench.is_result_tag("OE_BENCH_ERROR"))

    def test_error_marker_is_collected(self) -> None:
        parsed = bench.parse_logcat(
            logcat_text(sample_results(), done=False, errors=[{"fixture": "type_100", "error": "boom"}])
        )

        self.assertFalse(parsed["done"])
        self.assertEqual(parsed["errors"], [{"fixture": "type_100", "error": "boom"}])

    def test_unrelated_lines_are_ignored(self) -> None:
        parsed = bench.parse_logcat("garbage\nD/OE_BENCH_OTHER ( 1): {\"fixture\":\"x\"}\n")

        self.assertEqual(parsed["results"], {})
        self.assertFalse(parsed["done"])


class BaselineTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tempdir.cleanup)
        self.root = Path(self.tempdir.name)
        self.baseline_path = self.root / "baseline.json"
        self.logcat_path = self.root / "capture.log"

    def write_baseline(self, document: dict) -> None:
        self.baseline_path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")

    def write_logcat(self, text: str) -> None:
        self.logcat_path.write_text(text, encoding="utf-8")

    def run_gate(self, results: dict, *, extra: list[str] | None = None) -> tuple[int, str, str]:
        self.write_logcat(logcat_text(results))
        return call_main(
            ["--logcat", str(self.logcat_path), "--baseline", str(self.baseline_path)] + (extra or [])
        )

    def test_within_thresholds_exits_zero(self) -> None:
        self.write_baseline(baseline_document())

        code, out, _ = self.run_gate(sample_results())

        self.assertEqual(code, bench.EXIT_OK)
        self.assertIn("RESULT=PASS", out)
        self.assertEqual(
            len([line for line in out.splitlines() if line.startswith(("PASS ", "FAIL "))]), 5
        )

    def test_p95_plus_twenty_percent_fails(self) -> None:
        self.write_baseline(baseline_document())
        measured = sample_results()
        measured["flood_seq"]["p95"] = 17.8  # 14.8 * 1.15 = 17.02

        code, out, _ = self.run_gate(measured)

        self.assertEqual(code, bench.EXIT_REGRESSION)
        self.assertIn("FAIL flood_seq", out)
        self.assertIn("p95", out)

    def test_janky_plus_six_points_fails(self) -> None:
        self.write_baseline(baseline_document())
        measured = sample_results()
        measured["flood_seq"]["janky"] = 40  # 8% vs 2%

        code, out, _ = self.run_gate(measured)

        self.assertEqual(code, bench.EXIT_REGRESSION)
        self.assertIn("FAIL flood_seq", out)
        self.assertIn("janky", out)

    def test_fps_drop_of_four_fails(self) -> None:
        self.write_baseline(baseline_document())
        measured = sample_results()
        measured["flood_seq"]["fps"] = 54.2  # 58.2 - 3.0 = 55.2

        code, out, _ = self.run_gate(measured)

        self.assertEqual(code, bench.EXIT_REGRESSION)
        self.assertIn("FAIL flood_seq", out)
        self.assertIn("fps", out)

    def test_missing_bucket_reports_and_exits_zero(self) -> None:
        document = baseline_document()
        document["buckets"] = {}
        document["status"] = "pending-device"
        self.write_baseline(document)

        code, out, _ = self.run_gate(sample_results())

        self.assertEqual(code, bench.EXIT_OK)
        self.assertIn(f"NO_BASELINE bucket={ALIAS}/{API}", out)

    def test_missing_fixture_in_baseline_is_report_only(self) -> None:
        document = baseline_document()
        fixtures = document["buckets"][f"{ALIAS}/{API}"]["fixtures"]
        del fixtures["flood_color"]
        self.write_baseline(document)

        code, out, _ = self.run_gate(sample_results())

        self.assertEqual(code, bench.EXIT_OK)
        self.assertIn("SKIP flood_color", out)
        self.assertIn("no baseline fixture", out)

    def test_null_metric_in_baseline_is_report_only(self) -> None:
        document = baseline_document()
        document["buckets"][f"{ALIAS}/{API}"]["fixtures"]["flood_seq"]["p95"] = None
        self.write_baseline(document)
        measured = sample_results()
        measured["flood_seq"]["p95"] = 99.0

        code, out, _ = self.run_gate(measured)

        self.assertEqual(code, bench.EXIT_OK)
        # p95 cannot be judged, but janky and fps still are.
        self.assertNotIn("FAIL", out)
        self.assertIn("PASS flood_seq", out)

    def test_missing_fixture_in_capture_is_incomplete(self) -> None:
        self.write_baseline(baseline_document())
        partial = {"flood_seq": sample_results()["flood_seq"]}
        self.write_logcat(logcat_text(partial))

        code, _, err = call_main(
            ["--logcat", str(self.logcat_path), "--baseline", str(self.baseline_path)]
        )

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=BENCH_INCOMPLETE", err)

    def test_bucket_is_selected_by_alias_not_model(self) -> None:
        document = baseline_document()
        document["buckets"][f"{DEVICE}/{API}"] = document["buckets"].pop(f"{ALIAS}/{API}")
        self.write_baseline(document)

        code, out, _ = self.run_gate(sample_results())

        self.assertEqual(code, bench.EXIT_OK)
        self.assertIn(f"NO_BASELINE bucket={ALIAS}/{API}", out)
        self.assertNotIn(f"BUCKET {DEVICE}/{API}", out)

    def test_update_baseline_rejects_api_mismatch(self) -> None:
        self.write_baseline(baseline_document())
        self.write_logcat(logcat_text(sample_results()))

        code, _, err = call_main(
            [
                "--update-baseline",
                "--model",
                ALIAS,
                "--api",
                str(API + 1),
                "--baseline",
                str(self.baseline_path),
                "--logcat",
                str(self.logcat_path),
            ]
        )

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=API_MISMATCH", err)

    def test_update_baseline_rejects_alias_mismatch(self) -> None:
        self.write_baseline(baseline_document())
        self.write_logcat(logcat_text(sample_results()))

        code, _, err = call_main(
            [
                "--update-baseline",
                "--model",
                "some-other-alias",
                "--api",
                str(API),
                "--baseline",
                str(self.baseline_path),
                "--logcat",
                str(self.logcat_path),
            ]
        )

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=ALIAS_MISMATCH", err)

    def test_json_out_inside_repository_is_refused(self) -> None:
        self.write_baseline(baseline_document())

        code, _, err = call_main(
            [
                "--logcat",
                str(self.logcat_path),
                "--baseline",
                str(self.baseline_path),
                "--json-out",
                str(bench.REPO_ROOT / "artifacts" / "terminal-bench.json"),
            ]
        )

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=ARTIFACT_ROOT_REJECTED", err)

    def test_thresholds_are_read_from_the_baseline_file(self) -> None:
        document = baseline_document()
        document["thresholds"]["p95MaxRegressionRatio"] = 0.5
        self.write_baseline(document)
        measured = sample_results()
        measured["flood_seq"]["p95"] = 17.8

        code, _, _ = self.run_gate(measured)

        self.assertEqual(code, bench.EXIT_OK)

    def test_benched_error_exits_two(self) -> None:
        self.write_baseline(baseline_document())
        self.write_logcat(logcat_text(sample_results(), errors=[{"fixture": "type_100", "error": "boom"}]))

        code, out, err = call_main(
            ["--logcat", str(self.logcat_path), "--baseline", str(self.baseline_path)]
        )

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("BENCH_ERROR", out)
        self.assertIn("ERROR=BENCH_ERROR", err)

    def test_empty_capture_exits_two(self) -> None:
        self.write_baseline(baseline_document())
        self.write_logcat("nothing here\n")

        code, _, err = call_main(
            ["--logcat", str(self.logcat_path), "--baseline", str(self.baseline_path)]
        )

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=BENCH_NO_RESULTS", err)

    def test_update_baseline_writes_new_bucket_and_keeps_old(self) -> None:
        document = baseline_document()
        old_bucket = {
            "device": "Old Model",
            "api": 33,
            "capturedAt": "2025-12-01T00:00:00Z",
            "fixtures": {"flood_seq": {"frames": 10, "janky": 1, "p95": 20.0, "fps": 50.0}},
        }
        document["buckets"] = {"Old Model/33": copy.deepcopy(old_bucket)}
        document["status"] = "pending-device"
        document["capturedAt"] = None
        self.write_baseline(document)
        self.write_logcat(logcat_text(sample_results()))

        code, out, err = call_main(
            [
                "--update-baseline",
                "--model",
                ALIAS,
                "--api",
                str(API),
                "--baseline",
                str(self.baseline_path),
                "--logcat",
                str(self.logcat_path),
            ]
        )

        self.assertEqual(code, bench.EXIT_OK, err)
        updated = json.loads(self.baseline_path.read_text(encoding="utf-8"))
        self.assertIn(f"{ALIAS}/{API}", updated["buckets"])
        self.assertEqual(updated["buckets"]["Old Model/33"], old_bucket)
        self.assertEqual(updated["status"], "captured")
        self.assertTrue(updated["capturedAt"].endswith("Z"))
        self.assertEqual(set(updated["buckets"][f"{ALIAS}/{API}"]["fixtures"]), set(bench.FIXTURES))
        self.assertEqual(updated["buckets"][f"{ALIAS}/{API}"]["fixtures"]["flood_seq"]["p95"], 14.8)
        self.assertIn("BASELINE_UPDATE", out)
        self.assertIn("flood_seq.p95:", out)
        self.assertIn("->", out)

    def test_update_baseline_requires_model_and_api(self) -> None:
        self.write_baseline(baseline_document())
        self.write_logcat(logcat_text(sample_results()))

        code, _, err = call_main(
            ["--update-baseline", "--baseline", str(self.baseline_path), "--logcat", str(self.logcat_path)]
        )
        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=MODEL_REQUIRED", err)

        code, _, err = call_main(
            [
                "--update-baseline",
                "--model",
                ALIAS,
                "--baseline",
                str(self.baseline_path),
                "--logcat",
                str(self.logcat_path),
            ]
        )
        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=API_REQUIRED", err)

    def test_bucket_key_sanitizes_slashes(self) -> None:
        self.assertEqual(bench.bucket_key("Model/7", 34), "Model_7/34")
        self.assertEqual(bench.bucket_key(" Model 7 ", 34), "Model 7/34")


class DryRunTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tempdir.cleanup)
        self.root = Path(self.tempdir.name)
        self.baseline_path = self.root / "baseline.json"

    def test_dry_run_all_needs_no_device_or_apk(self) -> None:
        self.baseline_path.write_text(json.dumps(bench.default_baseline()), encoding="utf-8")

        code, out, err = call_main(
            [
                "--dry-run",
                "--fixture",
                "all",
                "--baseline",
                str(self.baseline_path),
                "--adb",
                str(self.root / "no-such-adb"),
            ]
        )

        self.assertEqual(code, bench.EXIT_OK, err)
        self.assertIn("PLAN fixture=all", out)
        self.assertIn("mode=dry-run", out)
        self.assertIn("DRY_RUN no measurements", out)
        self.assertIn("ADB_UNAVAILABLE", out)

    def test_dry_run_with_logcat_still_judges(self) -> None:
        baseline = baseline_document()
        self.baseline_path.write_text(json.dumps(baseline), encoding="utf-8")
        measured = sample_results()
        measured["flood_seq"]["p95"] = 17.8
        logcat_path = self.root / "capture.log"
        logcat_path.write_text(logcat_text(measured), encoding="utf-8")

        code, out, _ = call_main(
            [
                "--dry-run",
                "--fixture",
                "all",
                "--baseline",
                str(self.baseline_path),
                "--logcat",
                str(logcat_path),
            ]
        )

        self.assertEqual(code, bench.EXIT_REGRESSION)
        self.assertIn("FAIL flood_seq", out)

    def test_missing_baseline_is_an_environment_error(self) -> None:
        code, _, err = call_main(
            ["--dry-run", "--baseline", str(self.root / "absent.json")]
        )

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=BASELINE_MISSING", err)


def synthetic_baseline_document() -> dict:
    """Load the committed CI replay baseline (synthetic, never a device capture)."""
    return json.loads(SYNTHETIC_BASELINE.read_text(encoding="utf-8"))


class SyntheticReplayTests(unittest.TestCase):
    """The offline replay must judge instead of only printing a report."""

    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tempdir.cleanup)
        self.root = Path(self.tempdir.name)

    def replay(self, baseline_path: Path) -> tuple[int, str, str]:
        return call_main(
            [
                "--dry-run",
                "--fixture",
                "all",
                "--baseline",
                str(baseline_path),
                "--logcat",
                str(GOLDEN_LOG),
            ]
        )

    def test_golden_replay_against_synthetic_baseline_passes(self) -> None:
        code, out, err = self.replay(SYNTHETIC_BASELINE)

        self.assertEqual(code, bench.EXIT_OK, err)
        self.assertIn(f"BUCKET {REPLAY_BUCKET}", out)
        self.assertEqual(
            len([line for line in out.splitlines() if line.startswith("PASS ")]), 5
        )
        self.assertIn("RESULT=PASS judged=5", out)

    def test_p95_plus_twenty_percent_replay_exits_one(self) -> None:
        """The golden log degraded by 20% must be a regression, not a report."""
        marker = "I/OE_BENCH ( 4242): "
        lines = []
        for line in GOLDEN_LOG.read_text(encoding="utf-8").splitlines():
            if line.startswith(marker):
                payload = json.loads(line[len(marker):])
                payload["p95"] = round(payload["p95"] * 1.2, 3)
                line = marker + json.dumps(payload)
            lines.append(line)
        degraded = self.root / "golden.degraded.log"
        degraded.write_text("\n".join(lines) + "\n", encoding="utf-8")

        code, out, _ = call_main(
            [
                "--dry-run",
                "--fixture",
                "all",
                "--baseline",
                str(SYNTHETIC_BASELINE),
                "--logcat",
                str(degraded),
            ]
        )

        self.assertEqual(code, bench.EXIT_REGRESSION)
        self.assertIn("RESULT=REGRESSION failures=5", out)
        self.assertEqual(
            len([line for line in out.splitlines() if line.startswith("FAIL ")]), 5
        )

    def test_synthetic_fixture_is_marked_and_covers_every_fixture(self) -> None:
        synthetic = synthetic_baseline_document()

        self.assertTrue(synthetic["synthetic"])
        self.assertEqual(synthetic["status"], "synthetic")
        self.assertEqual(SYNTHETIC_BASELINE.name, "baseline.ci.json")
        self.assertEqual(
            set(synthetic["buckets"][REPLAY_BUCKET]["fixtures"]), set(bench.FIXTURES)
        )


class RequireBaselineTests(unittest.TestCase):
    """``--require-baseline`` is the release gate; the default stays report-only."""

    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tempdir.cleanup)
        self.root = Path(self.tempdir.name)
        self.baseline_path = self.root / "baseline.json"

    def write_baseline(self, document: dict) -> None:
        self.baseline_path.write_text(json.dumps(document), encoding="utf-8")

    def captured_document(self) -> dict:
        document = synthetic_baseline_document()
        document.pop("synthetic")
        document["status"] = "captured"
        return document

    def run_gate(self, *, extra: list[str] | None = None) -> tuple[int, str, str]:
        return call_main(
            [
                "--dry-run",
                "--fixture",
                "all",
                "--baseline",
                str(self.baseline_path),
                "--logcat",
                str(GOLDEN_LOG),
            ]
            + (extra or [])
        )

    def test_require_baseline_rejects_pending_device(self) -> None:
        self.write_baseline(bench.default_baseline())

        code, _, err = self.run_gate(extra=["--require-baseline"])

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=BASELINE_PENDING", err)
        self.assertIn("pending-device", err)

    def test_require_baseline_rejects_empty_buckets(self) -> None:
        document = baseline_document()
        document["buckets"] = {}
        self.write_baseline(document)

        code, _, err = self.run_gate(extra=["--require-baseline"])

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=BASELINE_PENDING", err)

    def test_require_baseline_rejects_synthetic_fixture(self) -> None:
        code, _, err = call_main(
            [
                "--dry-run",
                "--fixture",
                "all",
                "--baseline",
                str(SYNTHETIC_BASELINE),
                "--logcat",
                str(GOLDEN_LOG),
                "--require-baseline",
            ]
        )

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=BASELINE_PENDING", err)
        self.assertIn("synthetic", err)

    def test_require_baseline_rejects_missing_device_bucket(self) -> None:
        self.write_baseline(baseline_document())  # captured bucket is bench-a/34

        code, _, err = self.run_gate(extra=["--require-baseline"])

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=BASELINE_PENDING", err)
        self.assertIn(REPLAY_BUCKET, err)

    def test_require_baseline_rejects_bucket_without_fixtures(self) -> None:
        document = self.captured_document()
        document["buckets"][REPLAY_BUCKET]["fixtures"] = {}
        self.write_baseline(document)

        code, _, err = self.run_gate(extra=["--require-baseline"])

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=BASELINE_PENDING", err)

    def test_require_baseline_rejects_partial_bucket(self) -> None:
        document = self.captured_document()
        fixtures = document["buckets"][REPLAY_BUCKET]["fixtures"]
        for fixture in list(fixtures)[1:]:
            del fixtures[fixture]
        self.write_baseline(document)

        code, out, err = self.run_gate(extra=["--require-baseline"])

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=BASELINE_PENDING", err)
        self.assertIn("only 1/5 fixtures", err)
        self.assertIn("SKIP flood_color", out)

    def test_require_baseline_rejects_bucket_without_metrics(self) -> None:
        document = self.captured_document()
        document["buckets"][REPLAY_BUCKET]["fixtures"] = {"flood_seq": {}}
        self.write_baseline(document)

        code, out, err = self.run_gate(extra=["--require-baseline"])

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=BASELINE_PENDING", err)
        self.assertIn("only 0/5 fixtures", err)
        self.assertIn("SKIP flood_seq", out)

    def test_partial_bucket_without_flag_stays_report_only(self) -> None:
        document = self.captured_document()
        fixtures = document["buckets"][REPLAY_BUCKET]["fixtures"]
        for fixture in list(fixtures)[1:]:
            del fixtures[fixture]
        self.write_baseline(document)

        code, out, err = self.run_gate()

        self.assertEqual(code, bench.EXIT_OK, err)
        self.assertIn("RESULT=PASS judged=1", out)
        self.assertIn("SKIP flood_color", out)

    def test_require_baseline_rejects_synthetic_even_if_status_captured(self) -> None:
        document = synthetic_baseline_document()
        document["status"] = "captured"
        self.write_baseline(document)

        code, _, err = self.run_gate(extra=["--require-baseline"])

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=BASELINE_PENDING", err)
        self.assertIn("synthetic", err)

    def test_require_baseline_cannot_combine_with_update_baseline(self) -> None:
        self.write_baseline(self.captured_document())

        code, _, err = call_main(
            [
                "--require-baseline",
                "--update-baseline",
                "--model",
                "ci-replay",
                "--api",
                str(API),
                "--baseline",
                str(self.baseline_path),
                "--logcat",
                str(GOLDEN_LOG),
            ]
        )

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=BAD_ARGUMENT", err)

    def test_require_baseline_accepts_captured_bucket(self) -> None:
        self.write_baseline(self.captured_document())

        code, out, err = self.run_gate(extra=["--require-baseline"])

        self.assertEqual(code, bench.EXIT_OK, err)
        self.assertIn("RESULT=PASS judged=5", out)

    def test_require_baseline_works_without_device_or_logcat(self) -> None:
        self.write_baseline(bench.default_baseline())

        code, _, err = call_main(
            [
                "--dry-run",
                "--require-baseline",
                "--baseline",
                str(self.baseline_path),
            ]
        )

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=BASELINE_PENDING", err)

    def test_default_pending_baseline_stays_report_only(self) -> None:
        self.write_baseline(bench.default_baseline())

        code, out, err = self.run_gate()

        self.assertEqual(code, bench.EXIT_OK, err)
        self.assertIn(f"NO_BASELINE bucket={REPLAY_BUCKET}", out)
        self.assertIn("NOTE report only", out)

    def test_update_baseline_refuses_synthetic_fixture(self) -> None:
        copy_path = self.root / "baseline.ci.copy.json"
        copy_path.write_text(
            SYNTHETIC_BASELINE.read_text(encoding="utf-8"), encoding="utf-8"
        )

        code, _, err = call_main(
            [
                "--update-baseline",
                "--model",
                "ci-replay",
                "--api",
                str(API),
                "--baseline",
                str(copy_path),
                "--logcat",
                str(GOLDEN_LOG),
            ]
        )

        self.assertEqual(code, bench.EXIT_ENV)
        self.assertIn("ERROR=BASELINE_SYNTHETIC_READONLY", err)
        self.assertTrue(json.loads(copy_path.read_text(encoding="utf-8"))["synthetic"])


if __name__ == "__main__":
    unittest.main()
