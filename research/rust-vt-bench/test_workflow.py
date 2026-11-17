#!/usr/bin/env python3
"""Consistency tests for `.github/workflows/rust-vt.yml` and this README.

The workflow is the contract: the README's reproduction block must list exactly
the same commands, in the same order, so that a reader can paste it and get the
same run the runner performs. Two independent checks enforce that:

  * the YAML parses (PyYAML when installed, plus a stdlib structural check that
    always runs, so the suite is green on a bare python3);
  * README commands == workflow `run` lines, line for line.

No cargo, no network, no device.
"""

import os
import re
import unittest

ROOT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(ROOT, "..", ".."))
WORKFLOW = os.path.join(REPO, ".github", "workflows", "rust-vt.yml")
README = os.path.join(ROOT, "README.md")

START = "<!-- repro:begin -->"
END = "<!-- repro:end -->"

try:  # optional: the CI job installs it, a bare checkout may not have it
    import yaml
except ImportError:  # pragma: no cover - exercised only on minimal hosts
    yaml = None


def read(path):
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def _comment_free(lines):
    cleaned = []
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        cleaned.append(stripped)
    return cleaned


def _fallback_run_blocks(text):
    """Extract `run:` values without PyYAML (block scalars and one-liners)."""
    blocks = []
    current = None
    indent = 0
    for raw in text.splitlines():
        if current is None:
            block = re.match(r"^(\s*)run:\s*\|\s*$", raw)
            if block:
                current = []
                indent = len(block.group(1))
                continue
            scalar = re.match(r"^\s*run:\s+(?!\|)(.+)$", raw)
            if scalar:
                blocks.append([scalar.group(1)])
            continue
        if not raw.strip():
            current.append("")
            continue
        if len(raw) - len(raw.lstrip()) > indent:
            current.append(raw)
            continue
        blocks.append(current)
        current = None
    if current is not None:
        blocks.append(current)
    return blocks


def run_blocks(text):
    if yaml is not None:
        document = yaml.safe_load(text)
        blocks = []
        for job in document["jobs"].values():
            for step in job.get("steps", []):
                if "run" in step:
                    blocks.append(step["run"].splitlines())
        return blocks
    return _fallback_run_blocks(text)


def workflow_commands(text):
    commands = []
    for block in run_blocks(text):
        commands.extend(_comment_free(block))
    return commands


def readme_commands(text):
    if START not in text or END not in text:
        raise AssertionError("README is missing the %s / %s markers" % (START, END))
    body = text.split(START, 1)[1].split(END, 1)[0]
    fences = re.findall(r"```(?:bash|sh)\n(.*?)```", body, re.DOTALL)
    if len(fences) != 1:
        raise AssertionError("expected exactly one bash fence between the markers")
    return _comment_free(fences[0].splitlines())


class WorkflowShapeTest(unittest.TestCase):
    def setUp(self):
        self.text = read(WORKFLOW)

    def test_no_tabs(self):
        self.assertNotIn("\t", self.text)

    def test_parses_with_pyyaml(self):
        if yaml is None:
            self.skipTest("PyYAML not installed; stdlib checks still run")
        document = yaml.safe_load(self.text)
        self.assertIsInstance(document, dict)
        self.assertIn("jobs", document)
        self.assertTrue(document["jobs"])

    def test_structural_shape(self):
        self.assertTrue(self.text.startswith("name:"))
        self.assertIn("runs-on: ubuntu-24.04", self.text)
        self.assertIn("permissions:\n  contents: read", self.text)
        self.assertIn("cancel-in-progress: true", self.text)
        self.assertIn("timeout-minutes:", self.text)

    def test_every_step_is_named_and_the_job_is_single(self):
        head, separator, steps = self.text.partition("    steps:\n")
        self.assertEqual(separator, "    steps:\n", "job has no steps block")
        self.assertNotIn("    steps:\n", steps, "expected exactly one job")
        step_starts = re.findall(r"^ {6}- (.*)$", steps, re.MULTILINE)
        self.assertGreaterEqual(len(step_starts), 8)
        for step in step_starts:
            self.assertTrue(step.startswith("name: "), "unnamed step: " + step)
        self.assertEqual(len(re.findall(r"^ {4}steps:\s*$", head, re.MULTILINE)), 0)

    def test_no_secrets_and_no_write_permissions(self):
        self.assertNotIn("${{ secrets.", self.text)
        self.assertNotIn("secrets:", self.text)
        self.assertNotRegex(self.text, r"permissions:\s*\n\s+contents:\s*write")

    def test_scoped_to_the_research_directory(self):
        self.assertIn('"research/rust-vt-bench/**"', self.text)
        self.assertIn('".github/workflows/rust-vt.yml"', self.text)

    def test_runs_every_step_from_the_research_directory(self):
        """README's block deliberately has no `cd`; the job must supply it."""
        expected = (
            "    defaults:\n"
            "      run:\n"
            "        working-directory: research/rust-vt-bench\n"
        )
        self.assertIn(expected, self.text)
        if yaml is not None:
            document = yaml.safe_load(self.text)
            for job in document["jobs"].values():
                self.assertEqual(
                    job["defaults"]["run"]["working-directory"],
                    "research/rust-vt-bench",
                )


class WorkflowContentTest(unittest.TestCase):
    def setUp(self):
        self.text = read(WORKFLOW)
        self.commands = workflow_commands(self.text)
        self.joined = "\n".join(self.commands)

    def test_pins_the_research_toolchain(self):
        self.assertIn("rustup toolchain install 1.91.0", self.joined)
        self.assertNotIn("toolchain: stable", self.text)
        self.assertNotIn("@nightly", self.text)

    def test_caches_cargo(self):
        self.assertIn("actions/cache@", self.text)
        self.assertIn("~/.cargo/registry", self.text)
        self.assertIn("hashFiles('research/rust-vt-bench/Cargo.lock')", self.text)

    def test_required_steps_appear_in_order(self):
        required = (
            "python3 -B -m unittest discover -v",
            "cargo test --release --workspace",
            "python3 fixtures/gen.py",
            "python3 fixtures/gen.py --verify",
            "sha256sum -c SHA256SUMS",
            "sha256sum -c ../expected-sha256.txt",
            "bench all --out out/rust.json",
            "python3 compare.py --baseline out/alloc_baseline.json",
        )
        cursor = -1
        for command in required:
            index = self.joined.find(command)
            self.assertGreaterEqual(index, 0, "workflow is missing: " + command)
            self.assertGreater(index, cursor, "workflow step out of order: " + command)
            cursor = index

    def test_bench_runs_in_release_mode_with_the_recorded_output_path(self):
        self.assertIn("cargo run --release -p vt-bench -- bench all", self.joined)
        self.assertIn("--out out/rust.json", self.joined)

    def test_fallback_parser_agrees_with_pyyaml(self):
        """The stdlib parser is what a bare python3 uses; keep it honest."""
        if yaml is None:
            self.skipTest("PyYAML not installed; nothing to compare against")
        document = yaml.safe_load(self.text)
        parsed = []
        for job in document["jobs"].values():
            for step in job.get("steps", []):
                if "run" in step:
                    parsed.append(step["run"].splitlines())
        self.assertEqual(
            [line for block in _fallback_run_blocks(self.text) for line in _comment_free(block)],
            [line for block in parsed for line in _comment_free(block)],
        )


class ReadmeTest(unittest.TestCase):
    def setUp(self):
        self.text = read(README)

    def test_declares_frozen_status_first(self):
        head = self.text.split("\n## ", 1)[0]
        self.assertIn("冷冻", head)
        self.assertIn("1.91.0", head)
        self.assertIn("2026-11-15", head)
        self.assertIn("rust-toolchain.toml", head)

    def test_points_at_the_recorded_conclusions(self):
        self.assertIn("out/compare.md", self.text)
        self.assertIn("out/alloc_baseline.json", self.text)

    def test_reproduction_commands_match_the_workflow_line_for_line(self):
        expected = workflow_commands(read(WORKFLOW))
        actual = readme_commands(self.text)
        self.assertEqual(actual, expected)

    def test_states_the_gate_and_its_tolerance(self):
        self.assertIn("10%", self.text)
        self.assertIn("advisory", self.text.lower())


if __name__ == "__main__":
    unittest.main()
