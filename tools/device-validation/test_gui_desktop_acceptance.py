"""Self-tests for the G-08 GUI desktop acceptance probes.

All device interaction runs over socketpair-backed fakes (fake guest agent,
fake guest console, fake adb), aligned with the existing device-validation
self-test pattern: parsing/aggregation seams, verdict matrices, channel
protocol behavior and the no-device fail-fast path. No real adb, device or
guest is involved.
"""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest import mock

sys.dont_write_bytecode = True

import gui_desktop_acceptance as g08


TOKEN = "a" * 64
INSTANCE = "0f1e2d3c-4b5a-6978-8796-aabbccddeeff"
SERIAL = "qa-device-1"


def socketpair():
    client, server = socket.socketpair()
    client.settimeout(2.0)
    server.settimeout(2.0)
    return client, server


class FakeAdb:
    """Canned adb responses; dispatch by command label like the real Adb.run."""

    def __init__(self, adb, serial, *, logcat_qemu="", logcat_source="",
                 package_present=True, native_dir="/data/app/~~x/pkg-y/lib/arm64",
                 native_listing="libdroidlayer_qemu.so libgvproxy.so libvirglrenderer.so",
                 devices_output="List of devices attached\n" + SERIAL + "\tdevice product:x model:y\n"):
        self.adb, self.serial = adb, serial
        self.logcat_qemu = logcat_qemu
        self.logcat_source = logcat_source
        self.package_present = package_present
        self.native_dir = native_dir
        self.native_listing = native_listing
        self.devices_output = devices_output
        self.history = []
        self.commands = []
        self.forwards = []

    def run(self, label, argv, *, timeout_seconds=None, check=True):
        self.history.append(label)
        self.commands.append((label, list(argv)))
        if label == "discover":
            return self.devices_output
        if label == "package-path":
            return "package:/data/app/x/base.apk\n" if self.package_present else ""
        if label == "logcat-OrchestellaQemu":
            return self.logcat_qemu
        if label == "logcat-AudioSource":
            return self.logcat_source
        if label == "native-library-dir":
            return "  nativeLibraryDir=" + self.native_dir + "\n"
        if label == "native-lib-listing":
            return self.native_listing
        if label == "forward":
            self.forwards.append(argv[-1])
            return "34567"
        if label == "remove-forward":
            return ""
        raise AssertionError("unexpected adb command: " + label)

    def discover(self):
        return self.run("discover", ["devices", "-l"])

    def package_path(self, package):
        return self.run("package-path", ["shell", "pm", "path", package], check=False)

    def logcat(self, tag):
        return self.run("logcat-" + tag, ["logcat", "-d", "-s", tag + ":I"])

    def forward(self, target):
        return int(self.run("forward", ["forward", "tcp:0", "localabstract:" + target]))

    def remove_forward(self, port):
        return self.run("remove-forward", ["forward", "--remove", "tcp:" + str(port)], check=False)

    def native_library_dir(self, package):
        return self.run("native-library-dir", ["shell", "dumpsys", "package", package], check=False)


class FakeAgent:
    """Fake guest bridge agent: HELLO/FEATURES over one socketpair end."""

    def __init__(self, server, status_payloads):
        self.server = server
        self.status_payloads = list(status_payloads)
        self.thread = threading.Thread(target=self.loop, daemon=True)
        self.thread.start()

    def reply(self, text):
        self.server.sendall((text + "\n").encode("ascii"))

    def loop(self):
        buffer = b""
        while True:
            try:
                data = self.server.recv(4096)
            except socket.timeout:
                continue
            except OSError:
                return
            if not data:
                return
            buffer += data
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                text = line.decode("ascii", "replace").strip()
                if text.startswith("ORCHESTELLA/HELLO"):
                    self.reply("ORCHESTELLA/READY 1")
                elif text.startswith("ORCHESTELLA/FEATURES 1 INSTALL"):
                    self.reply("ORCHESTELLA/OK features " +
                               json.dumps({"state": "installing"}))
                elif text.startswith("ORCHESTELLA/FEATURES 1 START"):
                    self.reply("ORCHESTELLA/OK features " +
                               json.dumps({"state": "starting"}))
                elif text.startswith("ORCHESTELLA/FEATURES 1 STATUS"):
                    if self.status_payloads:
                        self.reply("ORCHESTELLA/OK features " +
                                   json.dumps(self.status_payloads.pop(0)))
                    else:
                        self.reply("ORCHESTELLA/ERROR features-unavailable")


class SilentAgent(FakeAgent):
    """Answers HELLO only; every FEATURES request gets the fixed ERROR line."""

    def loop(self):
        buffer = b""
        while True:
            try:
                data = self.server.recv(4096)
            except socket.timeout:
                continue
            except OSError:
                return
            if not data:
                return
            buffer += data
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                text = line.decode("ascii", "replace").strip()
                if text.startswith("ORCHESTELLA/HELLO"):
                    self.reply("ORCHESTELLA/READY 1")
                elif text.startswith("ORCHESTELLA/FEATURES"):
                    self.reply("ORCHESTELLA/ERROR features-unavailable")


class FakeShell:
    """Fake guest console: answers marker probes with canned blocks."""

    def __init__(self, server, blocks):
        self.server = server
        self.blocks = blocks  # nonce -> canned marker block
        self.thread = threading.Thread(target=self.loop, daemon=True)
        self.thread.start()

    def loop(self):
        buffer = ""
        while True:
            try:
                data = self.server.recv(8192)
            except socket.timeout:
                continue
            except OSError:
                return
            if not data:
                return
            buffer += data.decode("utf-8", "replace")
            for nonce, block in self.blocks.items():
                if nonce in buffer:
                    buffer = ""
                    self.server.sendall(block.encode("utf-8"))
                    break


class PairRegistry:
    """Patches g08.socket_factory_for: each connect pops the next scripted pair."""

    def __init__(self):
        self.pairs = {}  # abstract name -> [client sockets in connect order]

    def add_pair(self, name):
        client, server = socketpair()
        self.pairs.setdefault(name, []).append(client)
        return server

    def factory_for(self, adb, name):
        queue = self.pairs.get(name, [])
        if not queue:
            return None, None
        def factory():
            connection = queue.pop(0)
            connection.settimeout(2.0)
            return connection
        return factory, 0


def awaiting_payload(state="awaiting-frame"):
    return {"version": 1, "profile": "desktop-labwc", "state": state, "installed": True,
            "reason": "none", "phase": "host-frame-confirmation", "generation": "ab" * 16,
            "capture": "verified", "protocols": "verified", "renderer": "pixman",
            "transport": "cpu-copy-rfb", "capture_generation": "ab" * 16,
            "capture_width": 1080, "capture_height": 2400,
            "input": {"keyboard": True, "unicodeText": False, "pointer": True},
            "secret_field": "must-not-appear"}


GPU_LOG = ("I OrchestellaQemu: gpu-acceleration state=ACTIVE reason=NONE code=0 "
           "device=virtio-gpu-gl-device quotaMb=1024 robustness=true\n"
           "I OrchestellaQemu: Launching QEMU process with sanitized arguments\n"
           "I OrchestellaQemu: audio-relay consumer-attached replaced=false\n"
           "I OrchestellaQemu: audio-relay transport-ready attempt=1\n")
SOURCE_LOG = "I AudioSource: audio-probe phase=PRIMARY_ACTIVE primary-source-active\n"


def base_args(**overrides):
    namespace = SimpleNamespace(
        device_alias="bench-a",
        adb="/fake/adb",
        serial=SERIAL,
        instance_id=INSTANCE,
        bridge_token=TOKEN,
        sections="all",
        fail_fast=False,
        skip_install=False,
        skip_start=False,
        gpu_enabled=True,
        gpu_benchmark=False,
        pcm_probe=False,
        deadline=600,
        install_deadline=120,
        start_deadline=60,
        confirm_window=5,
        confirm_poll_interval=0.05,
        hello_timeout=2.0,
        walkcheck=None,
        output_root=None,
        output=None,
    )
    for key, value in overrides.items():
        setattr(namespace, key, value)
    return namespace


class ValidationTest(unittest.TestCase):
    def test_alias_rejects_bad_shapes(self):
        for value in ("A-upper", "9start", "x", "ok-alias-" + "z" * 60, None, ""):
            with self.assertRaises(g08.Rejected):
                g08.alias(value)
        self.assertEqual("bench-a", g08.alias("bench-a"))

    def test_serial_and_instance_and_token(self):
        self.assertEqual(SERIAL, g08.serial_value(SERIAL))
        with self.assertRaises(g08.Rejected):
            g08.serial_value("bad serial!")
        self.assertEqual(INSTANCE, g08.instance_value(INSTANCE))
        with self.assertRaises(g08.Rejected):
            g08.instance_value("not-a-uuid")
        self.assertEqual(TOKEN, g08.token_value(TOKEN))
        with self.assertRaises(g08.Rejected):
            g08.token_value("short")

    def test_output_path_rules(self):
        with tempfile.TemporaryDirectory() as root:
            good = g08.output_path(root, "evidence/g08.json")
            self.assertTrue(str(good).replace("\\", "/").endswith("evidence/g08.json"))
            for relative in ("g08.txt", "../escape.json", "a//b.json", "g08.yaml"):
                with self.assertRaises(g08.Rejected):
                    g08.output_path(root, relative)
            # yaml allowed when the extension set says so (walkcheck output).
            g08.output_path(root, "walk.yaml", extensions=(".yaml",))


class ParseGpuLinesTest(unittest.TestCase):
    def test_parses_structured_lines(self):
        lines = g08.parse_gpu_lines(
            "I OrchestellaQemu: gpu-acceleration state=FALLBACK reason=NATIVE_UNAVAILABLE "
            "code=0 device=virtio-gpu-device quotaMb=1024 robustness=false\n"
            "I OrchestellaQemu: unrelated\n")
        self.assertEqual(1, len(lines))
        self.assertEqual("FALLBACK", lines[0]["state"])
        self.assertEqual("NATIVE_UNAVAILABLE", lines[0]["reason"])
        self.assertFalse(lines[0]["robustness"])

    def test_parses_qemu_device_model_unsupported_line(self):
        # DW-01 reason literal pinned by VirglGpuLifecycleTest; the probe regex
        # must carry the full structured shape for the packaged-binary downgrade.
        lines = g08.parse_gpu_lines(
            "I OrchestellaQemu: gpu-acceleration state=FALLBACK "
            "reason=QEMU_DEVICE_MODEL_UNSUPPORTED "
            "code=0 device=virtio-gpu-device quotaMb=1024 robustness=false\n"
            "I OrchestellaQemu: unrelated\n")
        self.assertEqual(1, len(lines))
        self.assertEqual("FALLBACK", lines[0]["state"])
        self.assertEqual("QEMU_DEVICE_MODEL_UNSUPPORTED", lines[0]["reason"])
        self.assertEqual(0, lines[0]["code"])
        self.assertEqual("virtio-gpu-device", lines[0]["device"])
        self.assertEqual(1024, lines[0]["quota_mb"])
        self.assertFalse(lines[0]["robustness"])

    def test_launch_observed(self):
        self.assertTrue(g08.launch_observed("x Launching QEMU process with sanitized arguments\n"))
        self.assertFalse(g08.launch_observed("nothing\n"))

    def test_structured_log_format_matches_production_shape(self):
        # The production line shape comes from GpuAccelerationStatus.structuredLogLine().
        parsed = g08.parse_gpu_lines(
            "gpu-acceleration state=ACTIVE reason=NONE code=0 device=virtio-gpu-gl-device "
            "quotaMb=1024 robustness=true")
        self.assertEqual(1, len(parsed))
        self.assertEqual("virtio-gpu-gl-device", parsed[0]["device"])


class ParseAudioEventsTest(unittest.TestCase):
    def test_relay_and_probe_events(self):
        events = g08.parse_audio_events(
            "I OrchestellaQemu: audio-relay consumer-attached replaced=false\n"
            "I OrchestellaQemu: audio-relay transport-ready attempt=2\n"
            "I AudioSource: audio-probe phase=BACKOFF attempt=3/5\n"
            "I OrchestellaQemu: audio-relay garbage\n")
        kinds = [(event["source"], event["event"]) for event in events]
        self.assertEqual([("audio-relay", "consumer-attached"), ("audio-relay", "transport-ready"),
                          ("audio-probe", "backoff")], kinds)


class ControlChannelTest(unittest.TestCase):
    def test_hello_and_feature_roundtrip(self):
        client, server = socketpair()
        FakeAgent(server, [{"state": "ready"}])
        control = g08.ControlChannel(lambda: client, reply_timeout=2.0)
        control.connect(control.clock() + 2)
        hello = control.hello(TOKEN, control.clock() + 2)
        self.assertTrue(hello["drivable"], hello)
        payload = control.feature("STATUS", "desktop-labwc", control.clock() + 2)
        self.assertEqual("ready", payload["state"])
        control.close()

    def test_hello_timeout_reports_occupied(self):
        client, _ = socketpair()  # silent peer models the app-owned chardev client
        control = g08.ControlChannel(lambda: client, reply_timeout=0.2)
        control.connect(control.clock() + 1)
        hello = control.hello(TOKEN, control.clock() + 1)
        self.assertFalse(hello["drivable"])
        self.assertEqual("control-reply-timeout", hello["reason"])

    def test_error_response_maps_to_fixed_reason(self):
        client, server = socketpair()
        SilentAgent(server, [])
        control = g08.ControlChannel(lambda: client, reply_timeout=2.0)
        control.connect(control.clock() + 2)
        self.assertTrue(control.hello(TOKEN, control.clock() + 2)["drivable"])
        with self.assertRaises(g08.ChannelError) as caught:
            control.feature("STATUS", "desktop-labwc", control.clock() + 2)
        self.assertEqual("control-rejected-features-unavailable", caught.exception.reason)
        control.close()


class TerminalProbeTest(unittest.TestCase):
    COMPOSITOR_BLOCK = (
        "\nQA_BEGIN_G08COMPOSITOR\n"
        "QA_CHECK_labwc_running=0\n"
        "QA_CHECK_pulseaudio_installed=1\n"
        "QA_EVIDENCE guest_uid=10123\n"
        "QA_EVIDENCE pulseaudio_status=\n"
        "QA_END_G08COMPOSITOR=1\n")

    def test_probe_parses_checks_and_evidence(self):
        client, server = socketpair()
        FakeShell(server, {"G08COMPOSITOR": self.COMPOSITOR_BLOCK})
        probe = g08.TerminalProbe(lambda: client, settle_seconds=0.05)
        probe.connect(probe.clock() + 2)
        probe.settle()
        command, names = g08.compose_compositor_probe()
        self.assertIn("qc labwc_running", command)
        self.assertIn("pulseaudio_installed", [name for name, _ in names])
        result = probe.run(command, "G08COMPOSITOR", probe.clock() + 5)
        self.assertEqual(1, result["code"])
        self.assertEqual("0", result["checks"]["labwc_running"])
        self.assertEqual("1", result["checks"]["pulseaudio_installed"])
        self.assertIn("guest_uid=10123", result["evidence"])
        probe.close()

    def test_probe_timeout_raises(self):
        client, _ = socketpair()
        probe = g08.TerminalProbe(lambda: client, settle_seconds=0.05)
        probe.connect(probe.clock() + 2)
        probe.settle()
        with self.assertRaises(g08.ChannelError) as caught:
            probe.run("true", "G08COMPOSITOR", probe.clock() + 0.3)
        self.assertEqual("terminal-reply-timeout", caught.exception.reason)
        probe.close()

    def test_display_probe_extracts_rfb_bytes(self):
        client, server = socketpair()
        block = ("\nQA_BEGIN_G08DISPLAY\n"
                 "QA_EVIDENCE RFB_BYTES 4242\n"
                 "QA_END_G08DISPLAY=0\n")
        FakeShell(server, {"G08DISPLAY": block})
        probe = g08.TerminalProbe(lambda: client, settle_seconds=0.05)
        probe.connect(probe.clock() + 2)
        probe.settle()
        result = g08.run_display_probe(probe, probe.clock() + 5)
        self.assertEqual(4242, result["rfb_bytes"])
        probe.close()


class ConsumerPcmTest(unittest.TestCase):
    def test_counts_bytes_until_eof(self):
        client, server = socketpair()
        server.sendall(b"\x01" * 1000)
        server.close()
        counts = g08.count_consumer_pcm(lambda: client, 2.0)
        self.assertEqual(1000, counts["pcm_bytes"])
        self.assertTrue(counts["transport_ended"])

    def test_counts_bytes_until_window(self):
        client, server = socketpair()
        server.sendall(b"\x02" * 10)
        counts = g08.count_consumer_pcm(lambda: client, 0.3)
        self.assertEqual(10, counts["pcm_bytes"])
        self.assertFalse(counts["transport_ended"])
        client.close()
        server.close()


class AudioVerdictTest(unittest.TestCase):
    def test_verdict_matrix(self):
        cases = [
            ({"pcm": {"status": "pass"}}, "pass", "PCM_BYTES_COUNTED"),
            ({"guest_ready_observed": True, "host_listener_observed": True}, "pass",
             "AUDIO_RELAY_READY_WITHOUT_PCM_PROBE"),
            ({"relay_failed_observed": True}, "fail", "AUDIO_RELAY_FAILED_EVENTS_OBSERVED"),
            ({"probe_events": [{"event": "backoff"}]}, "blocked",
             "AUDIO_LOOP_PROBING_WITHOUT_READY"),
            ({}, "blocked", "AUDIO_EVENTS_NOT_IN_LOG_BUFFER"),
        ]
        for audio, status, reason in cases:
            self.assertEqual((status, reason), g08.audio_verdict(audio), audio)


class GpuLogcatTest(unittest.TestCase):
    def test_active_passes_with_flag(self):
        adb = FakeAdb("/fake/adb", SERIAL, logcat_qemu=GPU_LOG)
        result, verdict = g08.check_gpu_logcat(adb, gpu_enabled=True)
        self.assertEqual("pass", verdict["status"], verdict)
        self.assertEqual("GPU_ACTIVE", verdict["reason"])
        self.assertTrue(result["native_packaged"])
        self.assertTrue(result["launch_seen"])

    def test_fallback_records_reason(self):
        adb = FakeAdb("/fake/adb", SERIAL, logcat_qemu=(
            "I OrchestellaQemu: gpu-acceleration state=FALLBACK reason=INIT_FAILED "
            "code=-1 device=virtio-gpu-device quotaMb=700 robustness=false\n"))
        _, verdict = g08.check_gpu_logcat(adb, gpu_enabled=True)
        self.assertEqual("fail", verdict["status"])
        self.assertEqual("GPU_FALLBACK_INIT_FAILED", verdict["reason"])

    def test_native_unavailable_reason_surfaced(self):
        # Real 3D/VirGL gate: a genuine native-package fallback stays a failure.
        adb = FakeAdb("/fake/adb", SERIAL, logcat_qemu=(
            "I OrchestellaQemu: gpu-acceleration state=FALLBACK reason=NATIVE_UNAVAILABLE "
            "code=0 device=virtio-gpu-device quotaMb=1024 robustness=false\n"))
        _, verdict = g08.check_gpu_logcat(adb, gpu_enabled=True)
        self.assertEqual(("fail", "GPU_FALLBACK_NATIVE_UNAVAILABLE"),
                         (verdict["status"], verdict["reason"]))

    def test_qemu_model_unsupported_fallback_is_expected_2d_pass(self):
        # DW-01: the packaged QEMU binary deterministically lacks the gl device
        # model, so this FALLBACK is the expected 2D downgrade and must not be
        # reported as a fail. It records honest 2D acceptance, no 3D claim.
        adb = FakeAdb("/fake/adb", SERIAL, logcat_qemu=(
            "I OrchestellaQemu: gpu-acceleration state=FALLBACK "
            "reason=QEMU_DEVICE_MODEL_UNSUPPORTED "
            "code=0 device=virtio-gpu-device quotaMb=1024 robustness=false\n"))
        _, verdict = g08.check_gpu_logcat(adb, gpu_enabled=True)
        self.assertEqual(("pass", "GPU_EXPECTED_2D_FALLBACK"),
                         (verdict["status"], verdict["reason"]))

    def test_qemu_model_unsupported_device_mismatch_still_fails(self):
        # Even the expected 2D downgrade must not mask a device/state mismatch:
        # a FALLBACK line claiming the gl device is not an honest 2D downgrade.
        adb = FakeAdb("/fake/adb", SERIAL, logcat_qemu=(
            "I OrchestellaQemu: gpu-acceleration state=FALLBACK "
            "reason=QEMU_DEVICE_MODEL_UNSUPPORTED "
            "code=0 device=virtio-gpu-gl-device quotaMb=1024 robustness=false\n"))
        _, verdict = g08.check_gpu_logcat(adb, gpu_enabled=True)
        self.assertEqual(("fail", "GPU_DEVICE_ARG_MISMATCH"),
                         (verdict["status"], verdict["reason"]))

    def test_device_arg_mismatch_fails(self):
        adb = FakeAdb("/fake/adb", SERIAL, logcat_qemu=(
            "I OrchestellaQemu: gpu-acceleration state=ACTIVE reason=NONE code=0 "
            "device=virtio-gpu-device quotaMb=1024 robustness=true\n"))
        _, verdict = g08.check_gpu_logcat(adb, gpu_enabled=True)
        self.assertEqual(("fail", "GPU_DEVICE_ARG_MISMATCH"),
                         (verdict["status"], verdict["reason"]))

    def test_disabled_with_flag_fails(self):
        adb = FakeAdb("/fake/adb", SERIAL, logcat_qemu=(
            "I OrchestellaQemu: gpu-acceleration state=DISABLED reason=NONE code=0 "
            "device=virtio-gpu-device quotaMb=0 robustness=false\n"))
        _, verdict = g08.check_gpu_logcat(adb, gpu_enabled=True)
        self.assertEqual(("fail", "GPU_DISABLED_WITH_FLAG"),
                         (verdict["status"], verdict["reason"]))

    def test_missing_line_with_launch_fails(self):
        adb = FakeAdb("/fake/adb", SERIAL,
                      logcat_qemu="I OrchestellaQemu: Launching QEMU process with sanitized arguments\n")
        _, verdict = g08.check_gpu_logcat(adb, gpu_enabled=True)
        self.assertEqual(("fail", "GPU_LOG_LINE_MISSING"), (verdict["status"], verdict["reason"]))

    def test_empty_buffer_blocks(self):
        adb = FakeAdb("/fake/adb", SERIAL, logcat_qemu="")
        _, verdict = g08.check_gpu_logcat(adb, gpu_enabled=True)
        self.assertEqual(("blocked", "QEMU_LAUNCH_NOT_IN_LOG_BUFFER"),
                         (verdict["status"], verdict["reason"]))


class StatusEvidenceTest(unittest.TestCase):
    def test_allow_list_projection(self):
        evidence = g08.status_evidence(awaiting_payload())
        self.assertNotIn("secret_field", evidence)
        self.assertEqual("awaiting-frame", evidence["state"])
        self.assertEqual("ab" * 16, evidence["generation"])


class WalkcheckTest(unittest.TestCase):
    def test_yaml_contains_all_items_and_gap(self):
        text = g08.walkcheck_yaml("bench-a", "instance-0f1e2d3c")
        for item in g08.WALKCHECK_ITEMS:
            self.assertIn("  - id: " + item["id"], text)
        self.assertIn("pointer-capture", text)
        self.assertIn("dual-mode-navigation", text)
        self.assertIn("pulseaudio-installed", text)
        # DW-04: pulseaudio ships in PACKAGES; the checklist no longer carries
        # the outdated known-gap framing.
        self.assertNotIn("known-gap", text)
        self.assertIn("result: ", text)
        self.assertIn("device_alias: bench-a", text)
        self.assertNotIn(SERIAL, text)
        self.assertNotIn(TOKEN, text)


class CompositorProbeCompositionTest(unittest.TestCase):
    """DW-03 manager split inside the fixed compositor probe command."""

    def test_user_manager_units_vs_system_units(self):
        command, names = g08.compose_compositor_probe()
        names = [name for name, _ in names]
        # compositor/wayvnc: persistent guest user manager (linger), probed with
        # the profilectl user_systemctl environment shape.
        self.assertIn("unit_compositor", names)
        self.assertIn("unit_wayvnc", names)
        self.assertIn('runuser --user "$QA_USER" -- env XDG_RUNTIME_DIR="/run/user/$U"', command)
        self.assertIn('DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$U/bus"', command)
        self.assertIn("systemctl --user is-active --quiet " + g08.UNITS[0], command)
        self.assertIn("systemctl --user is-active --quiet " + g08.UNITS[1], command)
        # The relays stay on the system manager: plain systemctl, no --user.
        self.assertIn("qc unit_display_relay 'systemctl is-active --quiet "
                      + g08.UNITS[2] + "'", command)
        self.assertIn("if systemctl is-active --quiet " + g08.UNITS[3], command)
        self.assertNotIn("systemctl --user is-active --quiet " + g08.UNITS[2], command)
        self.assertNotIn("systemctl --user is-active --quiet " + g08.UNITS[3], command)
        # The audio relay systemctl show evidence stays on the system manager too.
        self.assertIn("systemctl show -p SubState --value " + g08.UNITS[3], command)
        self.assertIn("systemctl show -p NRestarts --value " + g08.UNITS[3], command)

    @unittest.skipUnless(os.name == "posix", "requires a POSIX guest shell fixture")
    def test_guest_child_shell_inherits_uid_and_targets_the_user_bus(self):
        command, _ = g08.compose_compositor_probe()
        with tempfile.TemporaryDirectory() as directory:
            for name, script in {
                "cat": (
                    'if [ "$1" = /etc/orchestella/instance-user ]; then echo testdesk; '
                    'else /bin/cat "$@"; fi'
                ),
                "id": 'test "$3" = testdesk && echo 10123',
                "runuser": (
                    'test "$1 $2 $3 $4" = "--user testdesk -- env" && '
                    'test "$5" = "XDG_RUNTIME_DIR=/run/user/10123" && '
                    'test "$6" = "DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/10123/bus" && '
                    'test "$7 $8" = "systemctl --user"'
                ),
            }.items():
                path = Path(directory) / name
                path.write_text("#!/bin/sh\n" + script + "\n", encoding="utf-8")
                path.chmod(0o700)
            env = {**os.environ, "PATH": directory + os.pathsep + os.environ["PATH"]}
            env.pop("U", None)
            env.pop("QA_USER", None)
            result = subprocess.run(["sh", "-c", command], capture_output=True,
                                    text=True, timeout=10, env=env)
        self.assertIn("QA_CHECK_unit_compositor=0", result.stdout)
        self.assertIn("QA_CHECK_unit_wayvnc=0", result.stdout)


class MissingChecklistTest(unittest.TestCase):
    def test_note_missing_deduplicates(self):
        g08.MISSING.clear()
        g08.note_missing("x", "REASON", "do this")
        g08.note_missing("x", "REASON", "do this")
        self.assertEqual(1, len(g08.MISSING))
        g08.MISSING.clear()

    def test_pulseaudio_probe_composition(self):
        # DW-04: the package ships, so the check is a regular expectation
        # (missing = probe failure + missing item, never a known gap).
        command, names = g08.compose_compositor_probe()
        names = [name for name, _ in names]
        self.assertIn("pulseaudio_installed", names)
        self.assertIn("dpkg-query", command)
        self.assertIn("pulseaudio", command)
        self.assertIn("wayland_socket", names)
        self.assertNotIn("unit_audio_relay_active", names)
        self.assertNotIn("audio_chardev", names)


class FailFastTest(unittest.TestCase):
    def test_no_adb_binary_fails_fast_with_missing_list(self):
        g08.MISSING.clear()
        with tempfile.TemporaryDirectory() as root:
            args = base_args(adb="C:/definitely/missing/adb.exe",
                             output_root=root, output="evidence/no-device.json")
            code = g08.check(args)
            self.assertEqual(2, code)
            self.assertEqual("adb-device", g08.MISSING[0]["id"])
            path = g08.output_path(root, "evidence/no-device.json")
            document = json.loads(path.read_text(encoding="utf-8"))
            self.assertFalse(document["passed"])
            self.assertFalse(document["preconditions"]["device_ready"])
            raw = path.read_text(encoding="utf-8")
            self.assertNotIn(TOKEN, raw)
            self.assertNotIn(SERIAL, raw)
        g08.MISSING.clear()

    def test_main_rejects_nonexistent_adb(self):
        g08.MISSING.clear()
        with tempfile.TemporaryDirectory() as root:
            code = g08.main([
                "check", "--adb", "C:/definitely/missing/adb.exe",
                "--serial", SERIAL, "--device-alias", "bench-a",
                "--instance-id", INSTANCE, "--bridge-token", TOKEN,
                "--output-root", root, "--output", "g08.json"])
            self.assertEqual(2, code)
        g08.MISSING.clear()

    def test_emit_walkcheck_writes_yaml(self):
        with tempfile.TemporaryDirectory() as root:
            code = g08.main(["emit-walkcheck", "--device-alias", "bench-a",
                             "--output-root", root, "--output", "walk.yaml",
                             "--instance-id", INSTANCE])
            self.assertEqual(0, code)
            text = (g08.output_path(root, "walk.yaml", extensions=(".yaml",))
                    .read_text(encoding="utf-8"))
            self.assertIn("pulseaudio-installed", text)


class EndToEndDriveTest(unittest.TestCase):
    """Full check() round-trip over socketpair fakes with a scripted guest."""

    def setUp(self):
        g08.MISSING.clear()
        self.registry = PairRegistry()
        self.adb = FakeAdb("/fake/adb", SERIAL, logcat_qemu=GPU_LOG, logcat_source=SOURCE_LOG)
        self.patcher = mock.patch.object(g08, "Adb", lambda adb, serial: self.adb)
        self.patcher.start()
        self.factory_patcher = mock.patch.object(
            g08, "socket_factory_for",
            lambda adb, name: self.registry.factory_for(adb, name))
        self.factory_patcher.start()
        self.sockets = []

    def tearDown(self):
        self.patcher.stop()
        self.factory_patcher.stop()
        for sock in self.sockets:
            try:
                sock.close()
            except OSError:
                pass
        g08.MISSING.clear()

    def add_control(self, status_payloads):
        server = self.registry.add_pair("droidlayer-control-" + INSTANCE)
        self.sockets.append(server)
        FakeAgent(server, status_payloads)

    def add_terminal(self, blocks):
        server = self.registry.add_pair("droidlayer-terminal-" + INSTANCE)
        self.sockets.append(server)
        FakeShell(server, blocks)

    def test_full_drive_run_with_missing_pulseaudio_package(self):
        # DW-04: pulseaudio ships in desktop-labwc PACKAGES, so a missing guest
        # package is a regular expectation failure (missing item), not a gap.
        compositor_checks = (
            "labwc_running", "labwc_identity", "display_marker", "wayland_socket",
            "wayvnc_socket", "unit_compositor", "unit_wayvnc", "unit_display_relay")
        compositor = (
            "\nQA_BEGIN_G08COMPOSITOR\n"
            + "".join("QA_CHECK_%s=0\n" % name for name in compositor_checks)
            + "QA_CHECK_pulseaudio_installed=1\n"
            "QA_EVIDENCE guest_uid=10123\n"
            "QA_EVIDENCE wayland_display=wayland-0\n"
            "QA_EVIDENCE audio_relay_substate=running\n"
            "QA_EVIDENCE audio_relay_nrestarts=9\n"
            "QA_EVIDENCE pulseaudio_status=\n"
            "QA_END_G08COMPOSITOR=1\n")
        display = ("\nQA_BEGIN_G08DISPLAY\n"
                   "QA_EVIDENCE RFB_BYTES 4242\n"
                   "QA_END_G08DISPLAY=0\n")
        # One control connection carries the whole session: STATUS planned ->
        # INSTALL -> installed -> START -> awaiting-frame, five display samples,
        # and the capture STATUS before the operator round.
        self.add_control([
            {"version": 1, "state": "planned", "installed": False, "phase": "none",
             "reason": "none"},
            dict(awaiting_payload(), state="installed", phase="installed"),
            awaiting_payload("awaiting-frame"),
        ] + [awaiting_payload("awaiting-frame") for _ in range(6)])
        # Reconnect pair after the presentation round: the app confirmed ready.
        self.add_control([awaiting_payload("ready")])
        # Two console pairs: compositor probe, then the wayvnc RFB probe.
        self.add_terminal({"G08COMPOSITOR": compositor, "G08DISPLAY": display})
        self.add_terminal({"G08COMPOSITOR": compositor, "G08DISPLAY": display})
        with tempfile.TemporaryDirectory() as root:
            args = base_args(output_root=root, output="evidence/run.json")
            code = g08.check(args)
            path = g08.output_path(root, "evidence/run.json")
            document = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(1, code)  # compositor failed on the missing pulseaudio package
            self.assertEqual("fail", document["sections"]["compositor"]["status"])
            self.assertEqual("pass", document["sections"]["profile"]["status"])
            self.assertEqual("PROFILE_awaiting-frame", document["sections"]["profile"]["reason"])
            self.assertEqual("pass", document["sections"]["display"]["status"])
            self.assertEqual("first-frame-confirmed",
                             document["sections"]["capture"]["presentation_gate"])
            self.assertEqual("pass", document["sections"]["audio"]["status"])
            self.assertEqual("pass", document["sections"]["gpu"]["status"])
            self.assertEqual(["pulseaudio_installed"],
                             document["sections"]["compositor"]["failed_checks"])
            gap_items = [item for item in document["missing"]
                         if item["id"] == "pulseaudio-installed"]
            self.assertEqual(1, len(gap_items))
            self.assertEqual("PULSEAUDIO_NOT_INSTALLED_IN_GUEST", gap_items[0]["reason"])
            self.assertIn("apt-get install pulseaudio", gap_items[0]["remediation"])
            raw = path.read_text(encoding="utf-8")
            self.assertNotIn(TOKEN, raw)
            self.assertNotIn(SERIAL, raw)
            self.assertNotIn("must-not-appear", raw)
            self.assertIn("device_alias", raw)
        self.assertTrue(any(label.startswith("remove-forward") for label in self.adb.history))

    def test_full_drive_run_passes_video_when_audio_runtime_is_inactive(self):
        # DW-04 regression: with pulseaudio installed the compositor probe is a
        # plain expectation and the whole drive can exit 0 (no missing items).
        compositor_checks = (
            "labwc_running", "labwc_identity", "display_marker", "wayland_socket",
            "wayvnc_socket", "unit_compositor", "unit_wayvnc", "unit_display_relay",
            "pulseaudio_installed")
        compositor = (
            "\nQA_BEGIN_G08COMPOSITOR\n"
            + "".join("QA_CHECK_%s=0\n" % name for name in compositor_checks)
            + "QA_EVIDENCE guest_uid=10123\n"
            "QA_EVIDENCE wayland_display=wayland-0\n"
            "QA_EVIDENCE audio_relay_substate=dead\n"
            "QA_EVIDENCE audio_relay_nrestarts=0\n"
            "QA_EVIDENCE audio_relay_active=no\n"
            "QA_EVIDENCE audio_chardev=absent\n"
            "QA_EVIDENCE pulseaudio_status=install ok installed\n"
            "QA_END_G08COMPOSITOR=0\n")
        display = ("\nQA_BEGIN_G08DISPLAY\n"
                   "QA_EVIDENCE RFB_BYTES 4242\n"
                   "QA_END_G08DISPLAY=0\n")
        self.add_control([
            {"version": 1, "state": "planned", "installed": False, "phase": "none",
             "reason": "none"},
            dict(awaiting_payload(), state="installed", phase="installed"),
            awaiting_payload("awaiting-frame"),
        ] + [awaiting_payload("awaiting-frame") for _ in range(6)])
        self.add_control([awaiting_payload("ready")])
        self.add_terminal({"G08COMPOSITOR": compositor, "G08DISPLAY": display})
        self.add_terminal({"G08COMPOSITOR": compositor, "G08DISPLAY": display})
        with tempfile.TemporaryDirectory() as root:
            args = base_args(output_root=root, output="evidence/run.json")
            code = g08.check(args)
            document = json.loads(g08.output_path(root, "evidence/run.json")
                                  .read_text(encoding="utf-8"))
            self.assertEqual(0, code)
            self.assertTrue(document["passed"])
            self.assertEqual("pass", document["sections"]["compositor"]["status"])
            self.assertEqual("COMPOSITOR_PROBE_OK", document["sections"]["compositor"]["reason"])
            self.assertEqual([], document["sections"]["compositor"]["failed_checks"])
            self.assertEqual("no", document["sections"]["compositor"]["evidence"]["audio_relay_active"])
            self.assertEqual("absent", document["sections"]["compositor"]["evidence"]["audio_chardev"])
            self.assertEqual([], [item for item in document["missing"]
                                  if item["id"].startswith("pulseaudio")])
            self.assertEqual([], document["missing"])

    def test_expected_2d_fallback_skips_the_3d_benchmark(self):
        self.add_control([])
        self.adb.logcat_qemu = (
            "I OrchestellaQemu: gpu-acceleration state=FALLBACK reason=QEMU_DEVICE_MODEL_UNSUPPORTED "
            "code=0 device=virtio-gpu-device quotaMb=1024 robustness=false\n"
        )
        with tempfile.TemporaryDirectory() as root, mock.patch.object(g08, "run_guest_benchmark") as benchmark:
            args = base_args(output_root=root, output="evidence/gpu.json", sections="gpu", gpu_benchmark=True)
            self.assertEqual(0, g08.check(args))
            document = json.loads(g08.output_path(root, "evidence/gpu.json").read_text(encoding="utf-8"))
            gpu = document["sections"]["gpu"]
            self.assertEqual("pass", gpu["status"])
            self.assertEqual("GPU_EXPECTED_2D_FALLBACK", gpu["reason"])
            self.assertEqual("skipped", gpu["benchmark"]["status"])
            benchmark.assert_not_called()

    def test_occupied_channel_blocks_guest_sections(self):
        # A control socket that never answers models the app-owned chardev.
        self.registry.add_pair("droidlayer-control-" + INSTANCE)
        with tempfile.TemporaryDirectory() as root:
            args = base_args(output_root=root, output="evidence/blocked.json",
                             hello_timeout=0.2)
            code = g08.check(args)
            document = json.loads(g08.output_path(root, "evidence/blocked.json")
                                  .read_text(encoding="utf-8"))
            self.assertEqual(2, code)
            self.assertFalse(document["preconditions"]["bridge_drivable"])
            for name in ("profile", "compositor", "display", "capture"):
                self.assertEqual("blocked", document["sections"][name]["status"])
            self.assertEqual("pass", document["sections"]["gpu"]["status"])
            blocked = [item for item in document["missing"]
                       if item["id"] == "section-profile"]
            self.assertEqual(1, len(blocked))
            self.assertIn("CONTROL_CHANNEL_", blocked[0]["reason"])

    def test_absent_instance_socket_fails_fast(self):
        # No pair registered for the control socket name: instance not running.
        with tempfile.TemporaryDirectory() as root:
            args = base_args(output_root=root, output="evidence/no-instance.json")
            code = g08.check(args)
            self.assertEqual(2, code)
            self.assertEqual("instance-running", g08.MISSING[0]["id"])
        g08.MISSING.clear()

    def test_socket_naming_matches_qemu_launcher_and_rejects_legacy_prefix(self):
        import dv_pvm_crossfire as crossfire

        self.assertEqual("droidlayer-control-inst-1", g08.control_socket_name("inst-1"))
        self.assertEqual("droidlayer-terminal-inst-1", g08.terminal_socket_name("inst-1"))
        self.assertEqual(crossfire.TERMINAL_SOCKET_PREFIX, g08.TERMINAL_SOCKET_PREFIX)

        # Register ONLY the obsolete orchestella-<id>-ctrl/-term sockets:
        # g08 must still fail fast with INSTANCE_SOCKET_ABSENT instead of accepting them.
        self.sockets.append(self.registry.add_pair("orchestella-" + INSTANCE + "-ctrl"))
        self.sockets.append(self.registry.add_pair("orchestella-" + INSTANCE + "-term"))
        with tempfile.TemporaryDirectory() as root:
            args = base_args(output_root=root, output="evidence/legacy-prefix.json")
            code = g08.check(args)
            self.assertEqual(2, code)
            self.assertEqual("instance-running", g08.MISSING[0]["id"])
            self.assertEqual("INSTANCE_SOCKET_ABSENT", g08.MISSING[0]["reason"])
        g08.MISSING.clear()

    def test_package_override_and_default_compatibility(self):
        qa_package = "io.github.nyzeep.orchestella.qa.w402"
        self.add_control([])
        with tempfile.TemporaryDirectory() as root:
            code = g08.main([
                "check",
                "--adb", "/fake/adb",
                "--serial", SERIAL,
                "--device-alias", "bench-a",
                "--instance-id", INSTANCE,
                "--bridge-token", TOKEN,
                "--package", qa_package,
                "--sections", "gpu",
                "--output-root", root,
                "--output", "evidence/qa-pkg.json",
            ])
            self.assertEqual(0, code)
            document = json.loads(g08.output_path(root, "evidence/qa-pkg.json").read_text(encoding="utf-8"))
            self.assertEqual(qa_package, document["package"])
            self.assertIn(("package-path", ["shell", "pm", "path", qa_package]), self.adb.commands)
            self.assertIn(("native-library-dir", ["shell", "dumpsys", "package", qa_package]), self.adb.commands)

        self.adb.commands.clear()
        self.add_control([])
        with tempfile.TemporaryDirectory() as root:
            code = g08.main([
                "check",
                "--adb", "/fake/adb",
                "--serial", SERIAL,
                "--device-alias", "bench-a",
                "--instance-id", INSTANCE,
                "--bridge-token", TOKEN,
                "--sections", "gpu",
                "--output-root", root,
                "--output", "evidence/default-pkg.json",
            ])
            self.assertEqual(0, code)
            document = json.loads(g08.output_path(root, "evidence/default-pkg.json").read_text(encoding="utf-8"))
            self.assertEqual(g08.PACKAGE, document["package"])
            self.assertIn(("package-path", ["shell", "pm", "path", g08.PACKAGE]), self.adb.commands)
            self.assertIn(("native-library-dir", ["shell", "dumpsys", "package", g08.PACKAGE]), self.adb.commands)

        with tempfile.TemporaryDirectory() as root:
            code = g08.main([
                "check",
                "--adb", "/fake/adb",
                "--serial", SERIAL,
                "--device-alias", "bench-a",
                "--instance-id", INSTANCE,
                "--bridge-token", TOKEN,
                "--package", "io.github.bad;id",
                "--output-root", root,
                "--output", "evidence/bad-pkg.json",
            ])
            self.assertEqual(2, code)


class Glmark2ProbeTest(unittest.TestCase):
    def test_missing_binary_reports_remediation(self):
        client, server = socketpair()
        block = ("\nQA_BEGIN_G08GLCHECK\n"
                 "QA_CHECK_glmark2_wayland=1\n"
                 "QA_CHECK_glmark2_x11=1\n"
                 "QA_END_G08GLCHECK=1\n")
        FakeShell(server, {"G08GLCHECK": block})
        probe = g08.TerminalProbe(lambda: client, settle_seconds=0.05)
        probe.connect(probe.clock() + 2)
        probe.settle()
        result = g08.run_guest_benchmark(probe, probe.clock() + 5)
        self.assertEqual("missing", result["status"])
        self.assertIn("apt-get install glmark2", result["remediation"])
        probe.close()

    def test_score_parsed_and_threshold(self):
        client, server = socketpair()
        blocks = {
            "G08GLCHECK": ("\nQA_BEGIN_G08GLCHECK\n"
                           "QA_CHECK_glmark2_wayland=0\n"
                           "QA_CHECK_glmark2_x11=1\n"
                           "QA_END_G08GLCHECK=0\n"),
            "G08GLBENCH": ("\nQA_BEGIN_G08GLBENCH\n"
                           "QA_EVIDENCE glmark2_line=glmark2 Score: 214\n"
                           "QA_END_G08GLBENCH=0\n"),
        }
        FakeShell(server, blocks)
        probe = g08.TerminalProbe(lambda: client, settle_seconds=0.05)
        probe.connect(probe.clock() + 2)
        probe.settle()
        result = g08.run_guest_benchmark(probe, probe.clock() + 10)
        self.assertEqual("pass", result["status"])
        self.assertEqual(214, result["score"])
        self.assertEqual("glmark2-es2-wayland", result["binary"])
        probe.close()


if __name__ == "__main__":
    unittest.main()
