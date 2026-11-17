#!/usr/bin/env python3
"""Tests for compare.py — table rendering plus the allocation regression gate.

Device-free and cargo-free: every case builds its own JSON in a temporary
directory and runs the comparator as a subprocess, so the assertions cover the
real exit codes and the real stdout/stderr a CI job would see.

Exit-code contract under test:
  0  table rendered (and, when a baseline is supplied, allocations within tolerance)
  1  allocation regression beyond tolerance, or an unmeasurable gated fixture
  2  missing/unreadable input, missing fixture, or sha256 problem
"""

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))
COMPARE = os.path.join(SCRIPT_DIR, "compare.py")
FIXTURES = ("plain", "ansi_color", "cursor", "wide", "osc")
MIB = 1048576

# Median allocation per MB from the 2026-11-15 reference run (out/rust.json).
REFERENCE_ALLOC = {
    "plain": 143092.8,
    "ansi_color": 154360.0,
    "cursor": 1.6,
    "wide": 328932.8,
    "osc": 737032.0,
}
REFERENCE_MBPS = {
    "plain": 74.790144,
    "ansi_color": 75.710188,
    "cursor": 19.810804,
    "wide": 94.859785,
    "osc": 131.184772,
}
FIXTURE_BYTES = {
    "plain": 10 * MIB,
    "ansi_color": 10 * MIB,
    "cursor": 5 * MIB,
    "wide": 5 * MIB,
    "osc": 2 * MIB,
}


def fixture_sha(fixture_id):
    """Stable fake sha so Kotlin and Rust agree without shipping fixture bytes."""
    return hashlib.sha256(("fixture:" + fixture_id).encode("ascii")).hexdigest()


def entries(alloc=None, mbps=None, shas=None, drop_alloc=(), skip=()):
    out = []
    for fixture_id in FIXTURES:
        if fixture_id in skip:
            continue
        alloc_value = REFERENCE_ALLOC[fixture_id] if alloc is None else alloc[fixture_id]
        entry = {
            "id": fixture_id,
            "bytes": FIXTURE_BYTES[fixture_id],
            "sha256": (shas or {}).get(fixture_id, fixture_sha(fixture_id)),
            "mb_per_s": REFERENCE_MBPS[fixture_id] if mbps is None else mbps[fixture_id],
        }
        if fixture_id not in drop_alloc:
            entry["alloc_bytes_per_mb"] = alloc_value
        out.append(entry)
    return out


def document(items, impl):
    return {"schema": "orchestella.vtbench/1", "impl": impl, "fixtures": items}


def committed(relative_path):
    """Read a tracked file from HEAD, not the working tree.

    The bench overwrites out/rust.json on every local rerun, so a working-tree
    snapshot would turn "the recorded table is reproducible" into "the last run
    happens to match it". HEAD is the history under review.
    """
    result = subprocess.run(
        ["git", "-C", REPO_ROOT, "show", "HEAD:" + relative_path],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        raise unittest.SkipTest(
            "git show failed for %s: %s"
            % (relative_path, result.stderr.decode("utf-8", "replace"))
        )
    return result.stdout


class ComparatorCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="vtbench-test-")
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def write(self, name, payload):
        path = os.path.join(self.tmp, name)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2)
        return path

    def write_pair(self, kotlin, rust):
        self.write("kotlin.json", document(kotlin, "kotlin"))
        self.write("rust.json", document(rust, "rust"))

    def run_compare(self, *extra):
        return subprocess.run(
            [sys.executable, COMPARE, "--out", self.tmp] + list(extra),
            cwd=self.tmp,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
        )

    def read_compare_md(self):
        with open(os.path.join(self.tmp, "compare.md"), "r", encoding="utf-8") as handle:
            return handle.read()


class TableTest(ComparatorCase):
    def test_reproduces_recorded_history_byte_for_byte(self):
        """The committed out/compare.md must remain exactly reproducible."""
        prefix = "research/rust-vt-bench/out/"
        for name in ("kotlin.json", "rust.json"):
            with open(os.path.join(self.tmp, name), "wb") as handle:
                handle.write(committed(prefix + name))

        result = self.run_compare("--no-baseline")

        self.assertEqual(result.returncode, 0, result.stderr)
        recorded = committed(prefix + "compare.md").decode("utf-8")
        self.assertEqual(result.stdout, recorded)
        self.assertEqual(self.read_compare_md(), recorded)

    def test_fixture_order_is_fixed(self):
        self.write_pair(entries(), entries())
        result = self.run_compare()
        self.assertEqual(result.returncode, 0, result.stderr)
        positions = [result.stdout.index("| %s |" % fixture_id) for fixture_id in FIXTURES]
        self.assertEqual(positions, sorted(positions))


class CommittedArtifactTest(unittest.TestCase):
    """The gate inputs must agree with the run they claim to describe."""

    def setUp(self):
        self.baseline = json.loads(
            committed("research/rust-vt-bench/out/alloc_baseline.json").decode("utf-8")
        )
        self.recorded = json.loads(
            committed("research/rust-vt-bench/out/rust.json").decode("utf-8")
        )

    def test_baseline_matches_the_recorded_run(self):
        recorded = {item["fixture"]: item for item in self.recorded["fixtures"]}
        self.assertEqual([item["id"] for item in self.baseline["fixtures"]], list(FIXTURES))
        for item in self.baseline["fixtures"]:
            source = recorded[item["id"]]
            self.assertEqual(item["sha256"], source["sha256"], item["id"])
            self.assertAlmostEqual(
                item["alloc_bytes_per_mb"], source["alloc_bytes_per_mb_median"], places=3,
                msg=item["id"],
            )
            self.assertAlmostEqual(item["mb_per_s"], source["mb_s_median"], places=3,
                                   msg=item["id"])

    def test_pinned_checksums_match_the_recorded_run(self):
        recorded = {item["fixture"]: item["sha256"] for item in self.recorded["fixtures"]}
        pinned_text = committed(
            "research/rust-vt-bench/fixtures/expected-sha256.txt"
        ).decode("utf-8")
        pinned = dict(
            (line.split()[1].replace(".bin", ""), line.split()[0])
            for line in pinned_text.splitlines()
            if line.strip()
        )
        self.assertEqual(pinned, recorded)
        self.assertEqual(list(pinned), list(FIXTURES))


class InputErrorTest(ComparatorCase):
    def test_missing_rust_results_exit_2(self):
        self.write("kotlin.json", document(entries(), "kotlin"))
        result = self.run_compare()
        self.assertEqual(result.returncode, 2)
        self.assertIn("missing rust results", result.stderr)

    def test_missing_fixture_exit_2(self):
        self.write_pair(entries(), entries(skip=("osc",)))
        result = self.run_compare()
        self.assertEqual(result.returncode, 2)
        self.assertIn("rust missing fixture 'osc'", result.stderr)

    def test_sha_disagreement_exit_2(self):
        shas = {"plain": "0" * 64}
        self.write_pair(entries(), entries(shas=shas))
        result = self.run_compare()
        self.assertEqual(result.returncode, 2)
        self.assertIn("sha256 mismatch", result.stderr)


class AllocationGateTest(ComparatorCase):
    def baseline(self):
        return document(entries(), "rust")

    def test_no_baseline_does_not_gate(self):
        self.write_pair(entries(), entries(alloc={k: v * 5 for k, v in REFERENCE_ALLOC.items()}))
        result = self.run_compare("--no-baseline")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_identical_run_passes(self):
        self.write_pair(entries(), entries())
        self.write("alloc_baseline.json", self.baseline())
        result = self.run_compare()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("PASS", result.stdout)

    def test_twenty_percent_regression_exits_1(self):
        """Failure injection: a synthetic +20% allocation regression must fail."""
        regressed = {k: v * 1.20 for k, v in REFERENCE_ALLOC.items()}
        self.write_pair(entries(), entries(alloc=regressed))
        self.write("alloc_baseline.json", self.baseline())

        result = self.run_compare()

        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("allocation regression", result.stderr)
        for fixture_id in FIXTURES:
            self.assertIn(fixture_id, result.stderr)
            self.assertIn("+20.0%", result.stdout)

    def test_regression_on_one_fixture_is_enough(self):
        regressed = dict(REFERENCE_ALLOC)
        regressed["wide"] *= 1.5
        self.write_pair(entries(), entries(alloc=regressed))
        self.write("alloc_baseline.json", self.baseline())

        result = self.run_compare()

        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(result.stderr.count("wide"), 1)
        self.assertNotIn("plain", result.stderr)

    def test_small_growth_passes(self):
        grown = {k: v * 1.05 for k, v in REFERENCE_ALLOC.items()}
        self.write_pair(entries(), entries(alloc=grown))
        self.write("alloc_baseline.json", self.baseline())
        result = self.run_compare()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_tolerance_boundary_is_inclusive(self):
        """Exactly +25% with tolerance 25 is not a regression; 1 byte more is."""
        base = {k: 1024.0 for k in FIXTURES}
        at_limit = {k: 1280.0 for k in FIXTURES}
        self.write_pair(entries(alloc=at_limit), entries(alloc=at_limit))
        self.write("alloc_baseline.json", document(entries(alloc=base), "rust"))
        result = self.run_compare("--alloc-tolerance", "25")
        self.assertEqual(result.returncode, 0, result.stderr)

        over = {k: 1281.0 for k in FIXTURES}
        self.write_pair(entries(alloc=over), entries(alloc=over))
        result = self.run_compare("--alloc-tolerance", "25")
        self.assertEqual(result.returncode, 1, result.stderr)

    def test_throughput_drop_is_advisory_only(self):
        slow = {k: v * 0.2 for k, v in REFERENCE_MBPS.items()}
        self.write_pair(entries(), entries(mbps=slow))
        self.write("alloc_baseline.json", self.baseline())

        result = self.run_compare()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("advisory", result.stdout)
        self.assertIn("-80.0%", result.stdout)

    def test_missing_alloc_metric_cannot_pass_the_gate(self):
        self.write_pair(entries(), entries(drop_alloc=("cursor",)))
        self.write("alloc_baseline.json", self.baseline())
        result = self.run_compare()
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("cursor", result.stderr)

    def test_baseline_on_different_bytes_exit_2(self):
        self.write_pair(entries(), entries())
        self.write(
            "alloc_baseline.json",
            document(entries(shas={"plain": "f" * 64}), "rust"),
        )
        result = self.run_compare()
        self.assertEqual(result.returncode, 2)
        self.assertIn("baseline", result.stderr)

    def test_baseline_missing_fixture_exit_2(self):
        self.write_pair(entries(), entries())
        self.write("alloc_baseline.json", document(entries(skip=("wide",)), "rust"))
        result = self.run_compare()
        self.assertEqual(result.returncode, 2)
        self.assertIn("baseline", result.stderr)

    def test_baseline_without_alloc_metric_exit_2(self):
        """A baseline that lost its alloc values must not silently disable the gate."""
        self.write_pair(entries(), entries())
        self.write(
            "alloc_baseline.json", document(entries(drop_alloc=("osc",)), "rust")
        )
        result = self.run_compare()
        self.assertEqual(result.returncode, 2)
        self.assertIn("osc", result.stderr)

    def test_unreadable_baseline_exit_2(self):
        self.write_pair(entries(), entries())
        with open(os.path.join(self.tmp, "alloc_baseline.json"), "w", encoding="utf-8") as handle:
            handle.write("{not json")
        result = self.run_compare()
        self.assertEqual(result.returncode, 2)
        self.assertIn("baseline", result.stderr)

    def test_gate_still_writes_the_table(self):
        regressed = {k: v * 1.20 for k, v in REFERENCE_ALLOC.items()}
        self.write_pair(entries(), entries(alloc=regressed))
        self.write("alloc_baseline.json", self.baseline())
        result = self.run_compare()
        self.assertEqual(result.returncode, 1)
        self.assertIn("| 夹具 |", self.read_compare_md())


if __name__ == "__main__":
    unittest.main()
