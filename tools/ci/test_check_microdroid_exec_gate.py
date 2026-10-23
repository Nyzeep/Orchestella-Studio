#!/usr/bin/env python3
"""Tests for the offline Microdroid exec-gate policy checker."""

from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check_microdroid_exec_gate.py")
FIXTURES = Path(__file__).with_name("fixtures") / "sepolicy"


def load_module():
    spec = importlib.util.spec_from_file_location("microdroid_gate", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


GATE = load_module()


def write(root: Path, name: str, text: str) -> Path:
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


class GateEvaluationTest(unittest.TestCase):
    def evaluate(self, *files: Path, payload: str = "system_file"):
        return GATE.evaluate(files, payload_type=payload)

    def test_direct_allow_opens_the_gate_and_reports_the_line(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = write(
                root,
                "microdroid_app.te",
                "type microdroid_app, domain, coredomain;\n"
                "allow microdroid_app system_file:file { execute execute_no_trans };\n",
            )
            result = self.evaluate(policy)
            self.assertEqual("GATE_OPEN", result.gate)
            self.assertTrue(any(e.line == 2 for e in result.evidence), result.evidence)

    def test_coredomain_inheritance_opens_the_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = write(
                root,
                "policy.te",
                "type microdroid_app, domain, coredomain;\n"
                "type system_file, file_type;\n"
                "allow coredomain system_file:file { execute execute_no_trans };\n",
            )
            result = self.evaluate(policy)
            self.assertEqual("GATE_OPEN", result.gate)
            self.assertEqual([3], [e.line for e in result.evidence])

    def test_execute_without_execute_no_trans_is_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = write(
                root,
                "policy.te",
                "type microdroid_app, domain, coredomain;\n"
                "type system_file, file_type;\n"
                "allow coredomain system_file:file { execute read open getattr map };\n",
            )
            result = self.evaluate(policy)
            self.assertEqual("GATE_CLOSED", result.gate)
            self.assertTrue(result.evidence)
            self.assertIn(3, [item.line for item in result.evidence])

    def test_commented_allow_does_not_open_the_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = write(
                root,
                "policy.te",
                "type microdroid_app, domain, coredomain;\n"
                "# allow microdroid_app system_file:file execute_no_trans;\n"
                "allow microdroid_app system_file:file execute;\n",
            )
            self.assertEqual("GATE_CLOSED", self.evaluate(policy).gate)

    def test_multiline_rule_is_parsed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = write(
                root,
                "policy.te",
                "type microdroid_app, domain, coredomain;\n"
                "allow microdroid_app system_file:file {\n"
                "    getattr\n"
                "    execute\n"
                "    execute_no_trans\n"
                "};\n",
            )
            result = self.evaluate(policy)
            self.assertEqual("GATE_OPEN", result.gate)
            self.assertEqual([2], [e.line for e in result.evidence])

    def test_backslash_continuation_is_parsed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = write(
                root,
                "policy.te",
                "type microdroid_app, domain, coredomain;\n"
                "allow microdroid_app system_file:file \\\n"
                "    { execute execute_no_trans };\n",
            )
            self.assertEqual("GATE_OPEN", self.evaluate(policy).gate)

    def test_macro_expansion_opens_the_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            macros = write(
                root,
                "global_macros",
                "define(`x_file_perms', `{ getattr execute execute_no_trans map }')\n",
            )
            policy = write(
                root,
                "policy.te",
                "type microdroid_app, domain, coredomain;\n"
                "allow microdroid_app system_file:file x_file_perms;\n",
            )
            result = self.evaluate(policy, macros)
            self.assertEqual("GATE_OPEN", result.gate)
            self.assertEqual(2, result.evidence[0].line)

    def test_nested_macro_is_expanded(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            macros = write(
                root,
                "global_macros",
                "define(`x_file_perms', `{ execute execute_no_trans }')\n"
                "define(`rx_file_perms', `{ read x_file_perms }')\n",
            )
            policy = write(
                root,
                "policy.te",
                "type microdroid_app, domain;\n"
                "allow microdroid_app system_file:file rx_file_perms;\n",
            )
            self.assertEqual("GATE_OPEN", self.evaluate(policy, macros).gate)

    def test_unknown_macro_is_unclear(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = write(
                root,
                "policy.te",
                "type microdroid_app, domain;\n"
                "allow microdroid_app system_file:file mystery_perms;\n",
            )
            self.assertEqual("GATE_UNCLEAR", self.evaluate(policy).gate)

    def test_neverallow_conflict_is_unclear(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = write(
                root,
                "policy.te",
                "type microdroid_app, domain;\n"
                "allow microdroid_app system_file:file execute_no_trans;\n"
                "neverallow microdroid_app system_file:file execute_no_trans;\n",
            )
            result = self.evaluate(policy)
            self.assertEqual("GATE_UNCLEAR", result.gate)
            self.assertEqual([2, 3], [e.line for e in result.evidence])

    def test_missing_payload_type_declaration_is_unclear(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = write(
                root,
                "policy.te",
                "type microdroid_app, domain;\n"
                "type other_file, file_type;\n"
                "allow microdroid_app other_file:file execute;\n",
            )
            self.assertEqual("GATE_UNCLEAR", self.evaluate(policy).gate)

    def test_missing_file_is_reported_as_an_error(self) -> None:
        with self.assertRaises(GATE.PolicyError) as caught:
            GATE.evaluate([Path("/nonexistent/policy.te")])
        self.assertEqual("POLICY_FILE_MISSING", caught.exception.code)

    def test_direct_rule_opens_even_without_a_type_declaration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = write(
                root,
                "policy.te",
                "type microdroid_app, domain;\n"
                "allow microdroid_app system_file:file execute_no_trans;\n",
            )
            self.assertEqual("GATE_OPEN", self.evaluate(policy).gate)

    def test_other_file_type_does_not_open_the_payload_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = write(
                root,
                "policy.te",
                "type microdroid_app, domain;\n"
                "type system_file, file_type;\n"
                "type tenant_apk_file, file_type;\n"
                "allow microdroid_app tenant_apk_file:file execute_no_trans;\n",
            )
            result = self.evaluate(policy)
            self.assertEqual("GATE_CLOSED", result.gate)

    def test_attribute_rule_applies_to_payload_type(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            policy = write(
                root,
                "policy.te",
                "type microdroid_app, domain;\n"
                "type system_file, file_type, system_file_type;\n"
                "allow microdroid_app system_file_type:file execute_no_trans;\n",
            )
            self.assertEqual("GATE_OPEN", self.evaluate(policy).gate)


class FixtureTest(unittest.TestCase):
    def test_all_fixture_expectations_match(self) -> None:
        result = GATE.evaluate_fixture_root(FIXTURES)
        self.assertEqual({}, result.mismatches, result.mismatches)
        self.assertEqual(3, len(result.cases))
        self.assertEqual("GATE_CLOSED", result.cases["aosp-main"].gate)

    def test_cli_exit_zero_for_fixtures(self) -> None:
        completed = subprocess.run(
            [sys.executable, str(SCRIPT), "--policy-fixtures", str(FIXTURES)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        self.assertIn("GATE_SUMMARY", completed.stdout)
        self.assertIn("aosp-main GATE_CLOSED", completed.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
