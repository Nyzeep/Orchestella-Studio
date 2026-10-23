#!/usr/bin/env python3
"""Tests for the host-side canonical protected-failure vocabulary."""

from __future__ import annotations

import unittest

import protected_failure as pf


class ProtectedFailureTest(unittest.TestCase):
    def test_platform_block_render_is_stable(self) -> None:
        failure = pf.from_terminal(7, "execve_errno_13")
        self.assertEqual("TOOLBOX_EXEC_BLOCKED_BY_PLATFORM", failure.code)
        self.assertEqual("PLATFORM_BLOCKED", failure.failure_class)
        self.assertEqual("execve", failure.stage)
        self.assertEqual(13, failure.errno)
        self.assertEqual(
            "TOOLBOX_EXEC_BLOCKED_BY_PLATFORM stage=execve errno=13",
            failure.render(),
        )

    def test_detail_is_case_insensitive_and_drops_paths(self) -> None:
        failure = pf.from_terminal(7, "EXECVE_ERRNO_13 at /data/local/tmp/x")
        self.assertEqual(pf.CODE_PLATFORM_EXEC_BLOCKED, failure.code)
        self.assertNotIn("/data", failure.render())

    def test_other_fln1_error_is_lifecycle(self) -> None:
        failure = pf.from_terminal(7, "open_pty_errno_13")
        self.assertEqual("TERMINAL_ERROR", failure.code)
        self.assertEqual("LIFECYCLE", failure.failure_class)
        self.assertEqual("open_pty", failure.stage)
        self.assertEqual(13, failure.errno)

    def test_unknown_fln1_has_no_stage_or_errno(self) -> None:
        failure = pf.from_terminal(9, None)
        self.assertEqual("TERMINAL_ERROR", failure.code)
        self.assertIsNone(failure.stage)
        self.assertIsNone(failure.errno)
        self.assertEqual("TERMINAL_ERROR", failure.render())

    def test_runner_reason_table(self) -> None:
        self.assertEqual("PERMISSION_DENIED", pf.from_runner("PERMISSION_DENIED").code)
        self.assertEqual("PERMISSION", pf.from_runner("HIDDEN_API_BLOCKED").failure_class)
        self.assertEqual("INSTALL", pf.from_runner("PAYLOAD_VERIFY_FAILED").failure_class)
        self.assertEqual("STOP", pf.from_runner("STOP_UNCONFIRMED").failure_class)
        self.assertEqual("SCHEMA", pf.from_runner("CONFIG_INVALID").failure_class)
        self.assertEqual("FASTLANE_UNKNOWN", pf.from_runner("other").code)

    def test_legacy_aliases_normalize(self) -> None:
        for value in ("TERMINAL_ERROR_FLN1_7", "execve_errno_13"):
            failure = pf.from_legacy(value)
            self.assertEqual(pf.CODE_PLATFORM_EXEC_BLOCKED, failure.code)
            self.assertEqual(13, failure.errno)
        self.assertEqual(
            pf.CODE_PLATFORM_EXEC_BLOCKED,
            pf.from_legacy("PREFLIGHT_TERMINAL_BLOCKED", fln1=7, errno=13).code,
        )
        self.assertEqual(
            "TERMINAL_ERROR", pf.from_legacy("PREFLIGHT_TERMINAL_BLOCKED").code
        )

    def test_matrix_cell_is_parsed(self) -> None:
        failure = pf.from_legacy(
            "TOOLBOX_EXEC_BLOCKED_BY_PLATFORM (fln1=7, execve errno=13)"
        )
        self.assertEqual(
            "TOOLBOX_EXEC_BLOCKED_BY_PLATFORM stage=execve errno=13",
            failure.render(),
        )

    def test_raw_avc_is_unknown_and_not_echoed(self) -> None:
        failure = pf.from_legacy("avc: denied for path=/mnt/apk/bin/sh")
        self.assertEqual("FASTLANE_UNKNOWN", failure.code)
        self.assertEqual("FASTLANE_UNKNOWN", failure.render())

    def test_every_code_has_a_class(self) -> None:
        for code, failure_class in pf.CODE_CLASSES.items():
            self.assertIn(failure_class, pf.FAILURE_CLASSES, code)
        self.assertEqual(
            {
                "PLATFORM_BLOCKED",
                "PERMISSION",
                "SCHEMA",
                "LIFECYCLE",
                "STOP",
                "INSTALL",
                "UNKNOWN",
            },
            set(pf.FAILURE_CLASSES),
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
