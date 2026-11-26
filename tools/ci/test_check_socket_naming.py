#!/usr/bin/env python3
"""Tests for the single-source abstract socket naming consistency guard."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check_socket_naming.py")
REPOSITORY = Path(__file__).resolve().parents[2]


def run(root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--root", str(root)],
        capture_output=True,
        text=True,
        check=False,
    )


def write_fixture(root: Path, **overrides: str) -> None:
    values = {
        "launcher_control": '"droidlayer-control-" + parameters.instanceId',
        "launcher_terminal": '"droidlayer-terminal-" + parameters.instanceId',
        "launcher_audio": '"orchestella-audio-" + shortAudioInstanceId(instanceId)',
        "launcher_extra": "",
        "terminal_control_prefix": "droidlayer-control-",
        "terminal_terminal_prefix": "droidlayer-terminal-",
        "terminal_extra": "",
        "peripheral_extra": "",
        "gui_control_prefix": "droidlayer-control-",
        "gui_terminal_prefix": "droidlayer-terminal-",
        "gui_audio_prefix": "orchestella-audio-",
        "crossfire_terminal_prefix": "droidlayer-terminal-",
        "pvm_probe_terminal": ': "droidlayer-terminal-" + instance.id()',
    }
    values.update(overrides)

    app_java = root / "app/src/main/java/io/github/nyzeep/orchestella"
    app_android_test = root / "app/src/androidTest/java/io/github/nyzeep/orchestella"
    dv_dir = root / "tools/device-validation"
    app_java.mkdir(parents=True, exist_ok=True)
    app_android_test.mkdir(parents=True, exist_ok=True)
    dv_dir.mkdir(parents=True, exist_ok=True)

    (app_java / "QemuInstanceLauncher.java").write_text(
        f"""package io.github.nyzeep.orchestella;
class QemuInstanceLauncher {{
    String ctrl() {{ String controlSocket = {values['launcher_control']}; return controlSocket; }}
    String term() {{ String terminalSocket = {values['launcher_terminal']}; return terminalSocket; }}
    String audio(String instanceId) {{ return {values['launcher_audio']}; }}
    {values['launcher_extra']}
}}
""",
        encoding="utf-8",
    )
    (app_java / "TerminalActivity.java").write_text(
        f"""package io.github.nyzeep.orchestella;
class TerminalActivity {{
    static final String CONTROL_SOCKET_PREFIX = "{values['terminal_control_prefix']}";
    static final String TERMINAL_SOCKET_PREFIX = "{values['terminal_terminal_prefix']}";
    {values['terminal_extra']}
}}
""",
        encoding="utf-8",
    )
    (app_java / "DesktopPeripheralController.java").write_text(
        f"""package io.github.nyzeep.orchestella;
class DesktopPeripheralController {{
    String resolve() {{ return "ok"; }}
    {values['peripheral_extra']}
}}
""",
        encoding="utf-8",
    )
    (app_android_test / "PvmProbeInstrumentation.java").write_text(
        f"""package io.github.nyzeep.orchestella;
class PvmProbeInstrumentation {{
    String sock(boolean directQemu) {{ return directQemu ? "qa" {values['pvm_probe_terminal']}; }}
}}
""",
        encoding="utf-8",
    )
    (dv_dir / "gui_desktop_acceptance.py").write_text(
        f'''AUDIO_SOCKET_PREFIX = "{values['gui_audio_prefix']}"
CONTROL_SOCKET_PREFIX = "{values['gui_control_prefix']}"
TERMINAL_SOCKET_PREFIX = "{values['gui_terminal_prefix']}"
''',
        encoding="utf-8",
    )
    (dv_dir / "dv_pvm_crossfire.py").write_text(
        f'TERMINAL_SOCKET_PREFIX = "{values["crossfire_terminal_prefix"]}"\n',
        encoding="utf-8",
    )


class CheckSocketNamingTest(unittest.TestCase):
    def test_valid_fixture_passes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_fixture(root)
            result = run(root)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("SUMMARY:", result.stdout)

    def test_drifted_control_prefix_in_probe_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_fixture(root, gui_control_prefix="orchestella-ctrl-")
            result = run(root)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("FAIL  gui_desktop_acceptance.py control prefix", result.stdout)

    def test_drifted_terminal_prefix_in_terminal_activity_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_fixture(root, terminal_terminal_prefix="orchestella-term-")
            result = run(root)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("FAIL  TerminalActivity.java terminal prefix", result.stdout)

    def test_drifted_audio_prefix_in_launcher_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_fixture(root, launcher_audio='"droidlayer-audio-" + instanceId')
            result = run(root)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("FAIL  QemuInstanceLauncher.java audio prefix", result.stdout)

    def test_comment_mention_does_not_mask_launcher_terminal_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_fixture(
                root,
                launcher_terminal='"custom-term-" + parameters.instanceId',
                launcher_extra='// previously "droidlayer-terminal-"',
            )
            result = run(root)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("FAIL  QemuInstanceLauncher.java terminal prefix", result.stdout)

    def test_legacy_orchestella_ctrl_pattern_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_fixture(
                root,
                terminal_extra='String legacy(String suffix) { return "orchestella-" + suffix + "-ctrl"; }',
            )
            result = run(root)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("legacy control/terminal socket pattern", result.stdout)

    def test_shared_droidlayer_audio_fallback_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_fixture(
                root,
                peripheral_extra='static final String LEGACY = "@droidlayer-audio";',
            )
            result = run(root)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("shared @droidlayer-audio fallback", result.stdout)

    def test_current_repository_is_green(self) -> None:
        result = run(REPOSITORY)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
