#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""G-08 GUI desktop acceptance probes (desktop-labwc on a real device).

Covers the seven device-window acceptance sections:

  1. profile lifecycle  INSTALL -> START -> STATUS (bridge FEATURES commands)
  2. compositor runtime labwc process + wayland-0 socket + core units (guest
     probes; DW-03 manager split: compositor/wayvnc answer to the guest's
     persistent user manager via systemctl --user with
     XDG_RUNTIME_DIR=/run/user/<uid>, the relays stay on the system manager)
  3. wayvnc capture generation + presentation-gate first frame (STATUS evidence
     and the awaiting-frame -> ready transition performed by the app itself)
  4. display channel E2E STATUS evidence + wayvnc RFB byte diversity (guest probe)
  5. audio relay unit state + host per-instance socket attachment + PCM counting
     (no audio-quality judgment; includes the desktop-labwc pulseaudio check:
     profilectl PACKAGES ships pulseaudio since DW-04, so a missing package is
     a regular missing-item failure now, not a recorded known gap. The honest
     boundary stays: PCM byte counts are presence evidence only, audio quality
     is never judged)
  6. GPU gpuAccelerationEnabled=true launch -> structured log reason assertion
     ("gpu-acceleration state=<State> reason=<GpuFallbackReason> ..." from
     QemuInstanceLauncher). Two honest acceptance gates: ACTIVE passes the real
     3D/VirGL gate; FALLBACK reason=QEMU_DEVICE_MODEL_UNSUPPORTED (the packaged
     QEMU binary deterministically lacks the gl device model) passes as the
     expected 2D downgrade (GPU_EXPECTED_2D_FALLBACK) and never claims 3D
     acceleration; any other FALLBACK reason (NATIVE_UNAVAILABLE / INIT_FAILED)
     fails the real-3D gate. On a native-packaged device the guest glmark2
     benchmark (Ticket58 acceptance surface) runs when --gpu-benchmark is set
  7. pointer capture / IME / dual-mode manual walkthrough checklist (YAML)

Channel ownership boundary (read before running on a GUI instance): QEMU serves
one client per chardev socket (QemuLaunchSpec server=on) and the app holds the
terminal stream from session start (PersistentTerminalSessionPool pump) and the
control channel from the first screen entry (BridgeConnector serveSession). This
script probes that empirically: the control channel must answer an authenticated
HELLO, otherwise the guest-side sections are reported as blocked missing items
(never silently skipped), while the always-available surfaces (logcat structured
evidence, the app-owned @orchestella-audio-<id12>-<gen8> consumer socket) are still harvested.
Best window for a full scripted run: right after the instance starts, before any
screen is opened. A takeover that displaces an app-owned client kills that app
connection (re-enter the terminal/desktop screen afterwards to reattach).

Discipline: device alias only (never Build.MODEL, never the serial, never the
bridge token in any recorded output); fixed command payloads; bounded timeouts;
fail-fast preconditions with an explicit missing-item checklist; --json
evidence. The script never sends CONFIRM-FRAME itself: the presentation-gate
first-frame confirmation must come from the real DesktopActivity surface path.

Exit codes: 0 pass, 1 acceptance failure, 2 blocked / missing preconditions.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import PurePosixPath, Path
import re
import socket
import subprocess
import sys
import time

sys.dont_write_bytecode = True

PACKAGE = "io.github.nyzeep.orchestella"
PROFILE = "desktop-labwc"
# desktop-labwc systemd units; the guest readiness gate slices [:3], the audio
# relay is best-effort with Restart=always (profilectl.start_profile) and is
# excluded from the ready gate: confirm-frame re-checks only the first three
# display units before committing state=ready, while the audio-relay state is
# reported independently and never blocks the presentation gate.
UNITS = (
    "orchestella-desktop-compositor.service",
    "orchestella-desktop-wayvnc.service",
    "orchestella-display-relay.service",
    "orchestella-audio-relay.service",
)
GUEST_IDENTITY_PREAMBLE = (
    'QA_USER=$(cat /etc/orchestella/instance-user 2>/dev/null); '
    'U=$(id -u -- "$QA_USER" 2>/dev/null); export QA_USER U; '
)
CONSUMER_SOCKET = "droidlayer-audio"
AUDIO_SOCKET_PREFIX = "orchestella-audio-"
CONTROL_SOCKET_PREFIX = "droidlayer-control-"
TERMINAL_SOCKET_PREFIX = "droidlayer-terminal-"
GOOD_STATES = ("awaiting-frame", "ready")


def short_audio_instance_id(instance_id):
    normalized = re.sub(r"[^a-z0-9]", "", str(instance_id or "").lower())
    return normalized[:12] or "unknown"


def audio_socket_prefix(instance_id):
    return AUDIO_SOCKET_PREFIX + short_audio_instance_id(instance_id)


def audio_socket_value(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,96}", value):
        raise Rejected("AUDIO_SOCKET_REJECTED")
    return value


def resolve_audio_socket(args, relay_events=()):
    explicit = getattr(args, "audio_socket", None)
    if explicit is not None:
        return audio_socket_value(explicit)
    prefix = audio_socket_prefix(args.instance_id)
    for event in reversed(list(relay_events)):
        if event.get("source") != "audio-relay" or event.get("event") != "consumer-listening":
            continue
        match = re.search(r"\bsocket=([A-Za-z0-9._-]{1,96})\b", event.get("detail", ""))
        if match:
            candidate = match.group(1)
            if candidate == prefix or candidate.startswith(prefix + "-"):
                return candidate
    return prefix


def control_socket_name(instance_id):
    return CONTROL_SOCKET_PREFIX + instance_id


def terminal_socket_name(instance_id):
    return TERMINAL_SOCKET_PREFIX + instance_id
BAD_STATES = ("failed", "cancelled")
GPU_LOG_RE = re.compile(
    r"gpu-acceleration state=(ACTIVE|FALLBACK|DISABLED) "
    r"reason=(NONE|NATIVE_UNAVAILABLE|INIT_FAILED|QEMU_DEVICE_MODEL_UNSUPPORTED) "
    r"code=(-?[0-9]+) device=([\w-]+) quotaMb=([0-9]+) robustness=(true|false)")
GPU_DEVICE_BY_STATE = {"ACTIVE": "virtio-gpu-gl-device",
                       "FALLBACK": "virtio-gpu-device",
                       "DISABLED": "virtio-gpu-device"}
AUDIO_RELAY_RE = re.compile(r"audio-relay ([\w-]+) ([ -~]{1,120})$")
AUDIO_PROBE_RE = re.compile(r"audio-probe phase=([A-Z_]+) ([ -~]{1,160})$")
QEMU_LAUNCH_RE = re.compile(r"Launching QEMU process with sanitized arguments")
FEATURE_ACTION_RE = re.compile(r"[A-Za-z]+")
FEATURE_ERROR_REASONS = {
    "malformed-handshake", "unauthorized", "unsupported-version", "malformed-command",
    "unsupported-feature-version", "unsupported-feature-command", "features-unavailable",
    "display-confirm-unavailable", "power-unavailable", "resize-unavailable",
    "notify-unavailable", "open-url-unavailable", "display-unavailable",
    "clipboard-unavailable", "payload-too-large",
}
STATUS_KEYS = (
    "version", "profile", "state", "installed", "reason", "phase", "generation",
    "capture", "protocols", "renderer", "transport", "capture_generation",
    "capture_width", "capture_height", "width", "height", "input", "exposed",
    "job_active", "units_version", "updated_at", "verified_boot", "health", "user",
    # DW-08: audio closure fields reported separately from the display chain.
    "audio_relay", "audio_relay_reason",
)
COMPOSITOR_EVIDENCE_KEYS = ("guest_uid", "wayland_display", "audio_relay_substate",
                            "audio_relay_nrestarts", "audio_relay_active",
                            "audio_chardev", "pulseaudio_status")
# The wayvnc RFB frame-diversity probe runs as a fixed python3 payload over the
# guest console; it counts bytes served on the wayvnc socket (no pixels, no
# quality judgment) and prints one bounded result line.
DISPLAY_PROBE_PY = b"""\
import socket, sys, time
path, seconds = sys.argv[1], float(sys.argv[2])
client = socket.socket(socket.AF_UNIX)
client.settimeout(1.0)
client.connect(path)
total, end = 0, time.monotonic() + seconds
while time.monotonic() < end:
    try:
        data = client.recv(65536)
    except Exception:
        continue
    if not data:
        break
    total += len(data)
client.close()
print("RFB_BYTES %d" % total)
"""
DISPLAY_PROBE_B64 = base64.b64encode(DISPLAY_PROBE_PY).decode("ascii")

MISSING = []  # aggregated missing-item checklist entries


class Rejected(Exception):
    """Fixed reason codes only; never attach token, serial or OS error text."""


def note_missing(item_id, reason, remediation):
    entry = {"id": item_id, "reason": reason, "remediation": remediation}
    if entry not in MISSING:
        MISSING.append(entry)


def alias(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-z][a-z0-9-]{2,39}", value):
        raise Rejected("ALIAS_REJECTED")
    return value


def serial_value(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}", value):
        raise Rejected("SERIAL_REJECTED")
    return value


def adb_value(value):
    path = Path(value)
    if not path.is_absolute() or path.name.lower() not in ("adb", "adb.exe") or not path.is_file():
        raise Rejected("ADB_REJECTED")
    return str(path)


def instance_value(value):
    if not isinstance(value, str) or not re.fullmatch(
            r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", value):
        raise Rejected("INSTANCE_ID_REJECTED")
    return value


def token_value(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise Rejected("TOKEN_REJECTED")
    return value


def package_value(value):
    if (not isinstance(value, str) or len(value) > 255
            or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+", value)):
        raise Rejected("PACKAGE_REJECTED")
    return value


def output_path(root_value, relative, *, extensions=(".json",)):
    root = Path(root_value)
    if not root.is_absolute():
        raise Rejected("ROOT_REJECTED")
    if not isinstance(relative, str) or not re.fullmatch(r"[A-Za-z0-9_./-]+", relative) \
            or not relative.endswith(tuple(extensions)):
        raise Rejected("OUTPUT_REJECTED")
    parts = relative.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise Rejected("OUTPUT_REJECTED")
    path = root.joinpath(*PurePosixPath(relative).parts)
    if any(parent.is_symlink() for parent in [path, *path.parents]):
        raise Rejected("LINK_REJECTED")
    return path


def subprocess_runner(argv, timeout_seconds):
    environment = os.environ.copy()
    environment["MSYS_NO_PATHCONV"] = "1"
    try:
        result = subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, timeout=timeout_seconds,
                                shell=False, env=environment)
    except subprocess.TimeoutExpired:
        raise Rejected("ADB_TIMEOUT") from None
    except OSError:
        raise Rejected("ADB_UNAVAILABLE") from None
    if len(result.stdout) > 1024 * 1024:
        raise Rejected("ADB_OUTPUT_REJECTED")
    try:
        return result.returncode, result.stdout.decode("utf-8", errors="strict")
    except UnicodeError:
        raise Rejected("ADB_ENCODING_REJECTED") from None


class Adb:
    """Small fixed-command runner. Raw output never reaches the evidence file."""

    def __init__(self, adb, serial, *, timeout_seconds=15, runner=subprocess_runner):
        self.adb, self.serial = adb_value(adb), serial_value(serial)
        self.timeout_seconds = float(timeout_seconds)
        self.runner = runner
        self.history = []

    def run(self, label, argv, *, timeout_seconds=None, check=True):
        try:
            code, text = self.runner([self.adb, "-s", self.serial, *argv],
                                     min(float(timeout_seconds or self.timeout_seconds), 120.0))
        except Rejected as error:
            self.history.append({"command": label, "exit_code": None, "reason": str(error)})
            raise
        self.history.append({"command": label, "exit_code": code})
        if code != 0 and check:
            raise Rejected("ADB_QUERY_FAILED")
        return text

    # Fixed device-side commands only.
    def discover(self):
        return self.run("discover", ["devices", "-l"])

    def package_path(self, package):
        return self.run("package-path", ["shell", "pm", "path", package], check=False)

    def logcat(self, tag):
        return self.run("logcat-" + tag, ["logcat", "-d", "-s", tag + ":I"], timeout_seconds=30)

    def forward(self, target):
        port = self.run("forward", ["forward", "tcp:0", "localabstract:" + target]).strip()
        if not port.isdigit() or not 0 < int(port) < 65536:
            raise Rejected("FORWARD_REJECTED")
        return int(port)

    def remove_forward(self, port):
        return self.run("remove-forward", ["forward", "--remove", "tcp:" + str(port)], check=False)

    def native_library_dir(self, package):
        return self.run("native-library-dir", ["shell", "dumpsys", "package", package],
                        timeout_seconds=20, check=False)


def parse_device_list(raw):
    """Counts only; the target serial is compared but never recorded."""
    lines = raw.splitlines()
    if not lines or lines[0].strip() != "List of devices attached":
        raise Rejected("DISCOVERY_FORMAT_REJECTED")
    ready = 0
    for line in lines[1:]:
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            ready += 1
    return {"ready_devices": ready}


def assert_device_ready(adb):
    counts = parse_device_list(adb.discover())
    if counts["ready_devices"] == 0:
        raise Rejected("NO_DEVICE")
    if counts["ready_devices"] != 1:
        raise Rejected("DEVICE_COUNT_NOT_ONE")
    return counts


def parse_gpu_lines(raw):
    lines = []
    for line in raw.splitlines():
        match = GPU_LOG_RE.search(line)
        if match:
            state, reason, code, device, quota, robustness = match.groups()
            lines.append({"state": state, "reason": reason, "code": int(code),
                          "device": device, "quota_mb": int(quota), "robustness": robustness == "true"})
    return lines


def parse_audio_events(raw):
    events = []
    for line in raw.splitlines():
        match = AUDIO_RELAY_RE.search(line)
        if match:
            events.append({"source": "audio-relay", "event": match.group(1),
                           "detail": match.group(2)})
            continue
        match = AUDIO_PROBE_RE.search(line)
        if match:
            events.append({"source": "audio-probe", "event": match.group(1).lower(),
                           "detail": match.group(2)})
    return events


def launch_observed(raw):
    return any(QEMU_LAUNCH_RE.search(line) for line in raw.splitlines())


class ChannelError(Exception):
    def __init__(self, reason):
        super().__init__(reason)
        self.reason = reason


class ControlChannel:
    """Authenticated bridge control channel (HELLO + FEATURES command surface).

    connect_factory returns a socket-like object (sendall/recv/settimeout/close);
    tests inject socketpair-backed fakes. The token never enters any record.
    """

    def __init__(self, connect_factory, *, reply_timeout=6.0, clock=time.monotonic):
        self.connect_factory = connect_factory
        self.reply_timeout = float(reply_timeout)
        self.clock = clock
        self.connection = None
        self.buffer = b""

    def connect(self, deadline):
        while self.clock() < deadline:
            try:
                self.connection = self.connect_factory()
                return
            except OSError:
                time.sleep(0.3)
        raise ChannelError("control-connect-timeout")

    def close(self):
        if self.connection is not None:
            try:
                self.connection.close()
            except OSError:
                pass
        self.connection = None
        self.buffer = b""

    def _readline(self, deadline):
        while self.clock() < deadline:
            if b"\n" in self.buffer:
                line, self.buffer = self.buffer.split(b"\n", 1)
                return line.decode("ascii", "replace").strip("\r")
            try:
                data = self.connection.recv(4096)
            except socket.timeout:
                continue
            except OSError:
                raise ChannelError("control-transport-failed") from None
            if not data:
                raise ChannelError("control-eof")
            self.buffer += data
            if len(self.buffer) > 65536:
                raise ChannelError("control-message-too-large")
        raise ChannelError("control-reply-timeout")

    def exchange(self, command, deadline, *, prefix="ORCHESTELLA/OK"):
        self.connection.sendall((command + "\n").encode("ascii"))
        while True:
            line = self._readline(deadline)
            if line.startswith("ORCHESTELLA/ERROR "):
                reason = line[len("ORCHESTELLA/ERROR "):]
                if reason in FEATURE_ERROR_REASONS:
                    raise ChannelError("control-rejected-" + reason)
                raise ChannelError("control-rejected")
            if line.startswith(prefix):
                return line
            # Guest-initiated lines (notify/ping) are ignored; the expected
            # answer may follow after them.

    def hello(self, token, deadline):
        deadline = min(deadline, self.clock() + self.reply_timeout)
        try:
            self.exchange("ORCHESTELLA/HELLO 1 1 " + token, deadline,
                          prefix="ORCHESTELLA/READY")
        except (ChannelError, socket.timeout, OSError) as failure:
            reason = failure.reason if isinstance(failure, ChannelError) else "control-transport-failed"
            self.close()
            return {"drivable": False, "reason": reason}
        return {"drivable": True, "reason": "ready"}

    def feature(self, action, profile, deadline):
        if not FEATURE_ACTION_RE.fullmatch(action):
            raise ChannelError("control-rejected-malformed-command")
        line = self.exchange("ORCHESTELLA/FEATURES 1 " + action + " " + profile, deadline)
        prefix = "ORCHESTELLA/OK features "
        if not line.startswith(prefix):
            return None
        try:
            payload = json.loads(line[len(prefix):])
        except json.JSONDecodeError:
            raise ChannelError("control-rejected-malformed-command") from None
        if not isinstance(payload, dict):
            raise ChannelError("control-rejected-malformed-command")
        return payload


def status_evidence(payload):
    """Allow-listed STATUS projection only; unknown keys never reach evidence."""
    return {key: payload[key] for key in STATUS_KEYS if key in payload}


def shell_quote(text):
    return "'" + text.replace("'", "'\"'\"'") + "'"


def guest_probe_command(checks, evidences, nonce):
    """Fixed shell wrapper: one qc() helper, per-check result codes, evidence lines."""
    body = ["printf '\\nQA_BEGIN_" + nonce + "\\n'", "qrc=0",
            "qc() { qn=$1; shift; sh -c \"$1\" >/dev/null 2>&1; qs=$?; "
            "printf 'QA_CHECK_%s=%s\\n' \"$qn\" \"$qs\"; [ \"$qs\" -eq 0 ] || qrc=1; }"]
    body += ["qc " + name + " " + shell_quote(command) for name, command in checks]
    body += evidences
    body.append("printf 'QA_END_" + nonce + "=%s\\n' \"$qrc\"")
    return "; ".join(body), list(checks)


class TerminalProbe:
    """Marker-delimited guest console probes (run_distro_smoke pattern).

    The console is an interactive root shell; every probe is one fixed command
    with a fixed nonce, and only the QA_CHECK_* result codes plus explicitly
    printed QA_EVIDENCE lines are kept.
    """

    def __init__(self, connect_factory, *, settle_seconds=1.5, clock=time.monotonic):
        self.connect_factory = connect_factory
        self.settle_seconds = float(settle_seconds)
        self.clock = clock
        self.connection = None

    def connect(self, deadline):
        while self.clock() < deadline:
            try:
                self.connection = self.connect_factory()
                self.connection.settimeout(1.0)
                return
            except OSError:
                time.sleep(0.3)
        raise ChannelError("terminal-connect-timeout")

    def close(self):
        if self.connection is not None:
            try:
                self.connection.close()
            except OSError:
                pass
        self.connection = None

    def settle(self):
        """Send a harmless newline; give the console a bounded prompt-settle window."""
        try:
            self.connection.sendall(b"\n")
        except OSError:
            raise ChannelError("terminal-transport-failed") from None
        end = self.clock() + self.settle_seconds
        while self.clock() < end:
            try:
                data = self.connection.recv(4096)
            except socket.timeout:
                continue
            except OSError:
                raise ChannelError("terminal-transport-failed") from None
            if not data:
                raise ChannelError("terminal-eof")

    def run(self, command, nonce, deadline):
        begin, end = "QA_BEGIN_" + nonce, "QA_END_" + nonce
        self.connection.sendall((command + "\r").encode("utf-8"))
        collected, size = [], 0
        while self.clock() < deadline:
            try:
                data = self.connection.recv(8192)
            except socket.timeout:
                continue
            except OSError:
                raise ChannelError("terminal-transport-failed") from None
            if not data:
                raise ChannelError("terminal-eof")
            collected.append(data)
            size += len(data)
            if size > 131072:
                raise ChannelError("terminal-output-too-large")
            text = b"".join(collected).decode("utf-8", "replace")
            match = re.search(r"(?ms)^" + begin + r"\n(.*?)^" + end + r"=([0-9]{1,3})[ \t]*$", text)
            if match:
                body, code = match.group(1), int(match.group(2))
                checks = dict(re.findall(r"(?m)^QA_CHECK_([a-z0-9_]+)=([0-9]+)[ \t]*$", body))
                evidence = re.findall(r"(?m)^QA_EVIDENCE ([ -~]{1,200})$", body)
                return {"code": code, "checks": checks, "evidence": evidence}
        raise ChannelError("terminal-reply-timeout")


def compose_compositor_probe():
    """Fixed guest checks: labwc process, wayland socket, wayvnc socket, units.

    Run the compositor and wayvnc probes as the desktop user against its
    persistent manager. The relays belong to the system manager.
    """
    user_systemctl = ('runuser --user "$QA_USER" -- env '
                      'XDG_RUNTIME_DIR="/run/user/$U" '
                      'DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$U/bus" systemctl --user')
    checks = [
        ("labwc_running", "pgrep -x labwc >/dev/null"),
        ("labwc_identity", "test \"$(cat /proc/$(pgrep -n -x labwc)/comm 2>/dev/null)\" = labwc"),
        ("display_marker", "test -f /run/user/$U/orchestella/desktop-clients.json"),
        ("wayland_socket", "d=$(sed -n 's/.*\"display\"[[:space:]]*:[[:space:]]*\"\\([a-z0-9-]*\\)\".*/\\1/p' "
                           "/run/user/$U/orchestella/desktop-clients.json); "
                           "test -n \"$d\" && test -S /run/user/$U/$d"),
        ("wayvnc_socket", "test -S /run/user/$U/orchestella/wayvnc.sock"),
        ("unit_compositor", user_systemctl + " is-active --quiet " + UNITS[0]),
        ("unit_wayvnc", user_systemctl + " is-active --quiet " + UNITS[1]),
        ("unit_display_relay", "systemctl is-active --quiet " + UNITS[2]),
        ("pulseaudio_installed",
         "dpkg-query -W -f='${Status}' pulseaudio 2>/dev/null | grep -qx 'install ok installed'"),
    ]
    evidences = [
        "printf 'QA_EVIDENCE guest_uid=%s\\n' \"$U\"",
        "printf 'QA_EVIDENCE wayland_display=%s\\n' \"$(sed -n "
        "'s/.*\"display\"[[:space:]]*:[[:space:]]*\"\\([a-z0-9-]*\\)\".*/\\1/p' "
        "/run/user/$U/orchestella/desktop-clients.json)\"",
        "printf 'QA_EVIDENCE audio_relay_substate=%s\\n' \"$(systemctl show -p SubState --value "
        + UNITS[3] + " 2>/dev/null)\"",
        "printf 'QA_EVIDENCE audio_relay_nrestarts=%s\\n' \"$(systemctl show -p NRestarts --value "
        + UNITS[3] + " 2>/dev/null)\"",
        "if systemctl is-active --quiet " + UNITS[3] + " 2>/dev/null; then "
        "printf 'QA_EVIDENCE audio_relay_active=yes\\n'; else printf 'QA_EVIDENCE audio_relay_active=no\\n'; fi",
        "if test -e /dev/virtio-ports/orchestella.audio; then "
        "printf 'QA_EVIDENCE audio_chardev=present\\n'; else printf 'QA_EVIDENCE audio_chardev=absent\\n'; fi",
        "printf 'QA_EVIDENCE pulseaudio_status=%s\\n' \"$(dpkg-query -W -f='${Status}' pulseaudio 2>/dev/null)\"",
    ]
    command, check_names = guest_probe_command(checks, evidences, "G08COMPOSITOR")
    # Fixed preamble: resolve the guest uid once, then run the probe body.
    return GUEST_IDENTITY_PREAMBLE + command, check_names


def run_compositor_probe(probe, deadline):
    command, check_names = compose_compositor_probe()
    result = probe.run(command, "G08COMPOSITOR", deadline)
    result["expected_checks"] = [name for name, _ in check_names]
    return result


def run_display_probe(probe, deadline, seconds=3.0):
    """Frame diversity: count bytes wayvnc serves on its RFB socket for a window."""
    evidence = ["out=$(printf '%s' " + shell_quote(DISPLAY_PROBE_B64) + " | base64 -d | "
                "timeout 20 python3 - \"/run/user/$U/orchestella/wayvnc.sock\" "
                + shell_quote(str(seconds)) + " 2>/dev/null); "
                "printf 'QA_EVIDENCE %s\\n' \"$out\""]
    command, _ = guest_probe_command([], evidence, "G08DISPLAY")
    command = GUEST_IDENTITY_PREAMBLE + command
    result = probe.run(command, "G08DISPLAY", deadline)
    bytes_seen = None
    for line in result["evidence"]:
        match = re.fullmatch(r"RFB_BYTES ([0-9]+)", line)
        if match:
            bytes_seen = int(match.group(1))
    result["rfb_bytes"] = bytes_seen
    return result


def run_glmark2_availability_probe(probe, deadline):
    checks = [
        ("glmark2_wayland", "command -v glmark2-es2-wayland >/dev/null"),
        ("glmark2_x11", "command -v glmark2-es2 >/dev/null"),
    ]
    command, _ = guest_probe_command(checks, [], "G08GLCHECK")
    return probe.run(command, "G08GLCHECK", deadline)


def run_guest_benchmark(probe, deadline):
    """Optional Ticket58-surface glmark2 benchmark inside the guest (opt-in).

    Score parsing mirrors Ticket58DeviceBenchmarkVerifier's acceptance metric;
    the score is recorded to evidence, never interpreted as audio- or
    device-identifying data.
    """
    availability = run_glmark2_availability_probe(probe, deadline)
    binary = ("glmark2-es2-wayland" if availability["checks"].get("glmark2_wayland") == "0"
              else "glmark2-es2" if availability["checks"].get("glmark2_x11") == "0" else None)
    if binary is None:
        return {"status": "missing", "reason": "GLMARK2_NOT_INSTALLED",
                "remediation": "apt-get install glmark2-es2-wayland glmark2-es2 inside the guest, "
                               "then rerun with --gpu-benchmark"}
    evidence = ["out=$(timeout 300 " + binary + " --run-forever=false 2>/dev/null | "
                "grep -m1 'glmark2 Score:'); printf 'QA_EVIDENCE glmark2_line=%s\\n' \"$out\""]
    command, _ = guest_probe_command([], evidence, "G08GLBENCH")
    result = probe.run(command, "G08GLBENCH", deadline)
    score = None
    for line in result["evidence"]:
        match = re.search(r"glmark2 Score:\s*([0-9]+)", line)
        if match:
            score = int(match.group(1))
    if score is None:
        return {"status": "fail", "reason": "GLMARK2_SCORE_NOT_PARSED", "score": None}
    return {"status": "pass" if score >= 30 else "fail", "reason": "GLMARK2_SCORE_RECORDED",
            "score": score, "binary": binary}


def count_consumer_pcm(connect_factory, window_seconds, clock=time.monotonic):
    """Attach as the one audio consumer and count PCM bytes only.

    The app-owned relay replaces its consumer with this link; the desktop's
    audio player may need to re-probe afterwards (FallbackAudioSource walk).
    """
    connection = connect_factory()
    total, nonzero, end = 0, 0, clock() + float(window_seconds)
    try:
        while clock() < end:
            try:
                connection.settimeout(max(0.1, min(1.0, end - clock())))
                data = connection.recv(65536)
            except socket.timeout:
                continue
            except OSError:
                return {"pcm_bytes": total, "nonzero_bytes": nonzero, "transport_ended": True}
            if not data:
                return {"pcm_bytes": total, "nonzero_bytes": nonzero, "transport_ended": True}
            total += len(data)
            nonzero += len(data) - data.count(0)
        return {"pcm_bytes": total, "nonzero_bytes": nonzero, "transport_ended": False}
    finally:
        try:
            connection.close()
        except OSError:
            pass


def check_gpu_logcat(adb, gpu_enabled, package=PACKAGE):
    raw = adb.logcat("OrchestellaQemu")
    statuses = parse_gpu_lines(raw)
    launch_seen = launch_observed(raw)
    result = {"launch_seen": launch_seen, "statuses": statuses,
              "native_packaged": None, "native_library_dir_recorded": False}
    dumpsys = adb.native_library_dir(package)
    match = re.search(r"nativeLibraryDir=(/data/app/[^\s]+)", dumpsys)
    if match:
        result["native_library_dir_recorded"] = True
        try:
            listing = adb.run("native-lib-listing", ["shell", "ls", match.group(1)],
                              timeout_seconds=15, check=False)
            result["native_packaged"] = "libvirglrenderer.so" in listing.split()
        except Rejected:
            result["native_packaged"] = None
    verdict = {"status": "fail", "reason": "GPU_LOG_LINE_MISSING"}
    if not statuses:
        if not launch_seen:
            verdict = {"status": "blocked", "reason": "QEMU_LAUNCH_NOT_IN_LOG_BUFFER"}
        return result, verdict
    latest = statuses[-1]
    result["latest"] = latest
    if not gpu_enabled:
        verdict = {"status": "pass", "reason": "GPU_STRUCTURED_LOG_OK"}
    elif latest["state"] == "ACTIVE":
        # Real 3D/VirGL acceptance gate.
        verdict = {"status": "pass", "reason": "GPU_ACTIVE"}
    elif latest["state"] == "FALLBACK" and latest["reason"] == "QEMU_DEVICE_MODEL_UNSUPPORTED":
        # Expected 2D-safe downgrade, not a defect: the packaged QEMU binary
        # deterministically lacks the gl device model (GpuFallbackReason DW-01),
        # so the launch rebinds to the 2D device before the process ever starts.
        # This gate records honest 2D acceptance and never claims 3D acceleration.
        verdict = {"status": "pass", "reason": "GPU_EXPECTED_2D_FALLBACK"}
    elif latest["state"] == "FALLBACK":
        # NATIVE_UNAVAILABLE / INIT_FAILED stay failures on the real-3D gate.
        verdict = {"status": "fail", "reason": "GPU_FALLBACK_" + latest["reason"]}
    else:
        verdict = {"status": "fail", "reason": "GPU_DISABLED_WITH_FLAG"}
    if latest["device"] != GPU_DEVICE_BY_STATE[latest["state"]]:
        verdict = {"status": "fail", "reason": "GPU_DEVICE_ARG_MISMATCH"}
    return result, verdict


WALKCHECK_ITEMS = (
    {"id": "pointer-capture", "surface": "DesktopActivity top bar",
     "steps": ["Open the desktop window for the desktop-labwc instance.",
               "Tap the pointer-capture button and move the finger/pointer.",
               "Verify the guest cursor follows inside the surface and release returns control."],
     "pass_criteria": "Pointer capture engages, guest cursor tracks motion, release restores the Android pointer.",
     "automated_evidence": "compositor probe covers zwlr availability indirectly via the wayland socket; "
                           "the capture section reports input.pointer only."},
    {"id": "ime", "surface": "DesktopActivity + IME",
     "steps": ["Focus a guest text field (foot terminal).",
               "Invoke the IME and commit CJK and ASCII text.",
               "Verify committed text lands in the guest field exactly once."],
     "pass_criteria": "Committed text arrives once, backspace/dead keys behave, the IME window tracks the caret.",
     "automated_evidence": "capture section records input.keyboard/input.unicodeText negotiation only."},
    {"id": "dual-mode-navigation", "surface": "DualModeNavigation terminal <-> desktop",
     "steps": ["From the terminal screen use the desktop entry; verify the desktop opens for the same instance.",
               "From the desktop top bar use the terminal switch; verify the terminal reattaches without restarting QEMU.",
               "Cycle both directions three times."],
     "pass_criteria": "Both screens attach to the same live instance; no instance restart; no dead surface after switching.",
     "automated_evidence": "none; manual-only item."},
    {"id": "audio-listen", "surface": "DesktopActivity audio loop",
     "steps": ["With the desktop open, play any audio inside the guest.",
               "Listen on the host; record presence/absence only (never judge quality).",
               "Cross-check with the audio section PCM evidence when --pcm-probe was used."],
     "pass_criteria": "Sound audible on the host (presence); if absent, cross-check the "
                      "pulseaudio-installed item below. Presence/absence only, never quality.",
     "automated_evidence": "audio section records PCM byte counts and relay events; quality is out of scope."},
    {"id": "pulseaudio-installed", "surface": "desktop-labwc profile",
     "steps": ["Check the audio/compositor probe pulseaudio_status evidence.",
               "If pulseaudio is not installed: that is a probe failure, not a known gap; "
               "install it in the guest (apt-get install pulseaudio) and restart "
               "orchestella-audio-relay.service, then rerun."],
     "pass_criteria": "pulseaudio is installed (desktop-labwc PACKAGES ships it since DW-04) and "
                      "the relay reports transport-ready; PCM/audio quality stays out of scope.",
     "automated_evidence": "compositor probe check pulseaudio_installed + pulseaudio_status evidence line."},
)

WALKCHECK_HEADER = """# G-08 desktop-labwc manual walkthrough checklist
# Generated by tools/device-validation/gui_desktop_acceptance.py; fill `result`
# (pass / fail / n-a) and `notes` for each item on the real device.
# Discipline: record conclusions in the acceptance report; never attach
# screenshots, device model names or serials.
"""


def walkcheck_yaml(device_alias, instance_alias):
    lines = [WALKCHECK_HEADER, "device_alias: " + str(device_alias),
             "instance_alias: " + str(instance_alias), "items:"]
    for item in WALKCHECK_ITEMS:
        lines.append("  - id: " + item["id"])
        lines.append("    surface: " + item["surface"])
        lines.append("    steps:")
        for step in item["steps"]:
            lines.append("      - " + step)
        lines.append("    pass_criteria: " + item["pass_criteria"])
        lines.append("    automated_evidence: " + item["automated_evidence"])
        lines.append("    result: ")
        lines.append("    notes: ")
    return "\n".join(lines) + "\n"


# Report order follows the ticket; the runner executes guest sections in a
# channel-safe order (capture releases the control channel for the operator's
# presentation round and therefore runs last among the control-driven sections).
SECTION_ORDER = ("profile", "compositor", "display", "capture", "audio", "gpu")


def section_requested(selected, name):
    return selected == "all" or name in selected.split(",")


def requested_names(args):
    return [name for name in SECTION_ORDER if section_requested(args.sections, name)]


def socket_factory_for(adb, abstract_name):
    """Returns (factory, port); factory is None when the socket is absent.

    Tests patch this module symbol to return socketpair-backed factories.
    """
    try:
        port = adb.forward(abstract_name)
    except Rejected:
        return None, None

    def factory():
        connection = socket.create_connection(("127.0.0.1", port), timeout=3)
        connection.settimeout(2.0)
        return connection

    return factory, port


def terminal_probe_for(adb, terminal_name, deadline, forwards=None):
    factory, port = socket_factory_for(adb, terminal_name)
    if factory is None:
        return None
    if forwards is not None and port is not None:
        forwards.append(port)
    probe = TerminalProbe(factory)
    try:
        probe.connect(min(deadline, probe.clock() + 15))
        probe.settle()
        return probe
    except ChannelError:
        probe.close()
        return None


def pcm_probe_for(adb, window_seconds=10.0, socket_name=CONSUMER_SOCKET):
    factory, port = socket_factory_for(adb, socket_name)
    if factory is None:
        return {"status": "blocked", "reason": "AUDIO_CONSUMER_SOCKET_ABSENT", "socket": socket_name}
    try:
        counts = count_consumer_pcm(factory, window_seconds)
        counts["socket"] = socket_name
        counts["status"] = "pass" if counts["pcm_bytes"] > 0 else "fail"
        counts["reason"] = ("PCM_BYTES_COUNTED" if counts["pcm_bytes"] > 0
                            else "NO_PCM_WITHIN_WINDOW")
        counts["caveat"] = ("This probe attached as the single @" + socket_name + " consumer and "
                            "replaced the app player link; re-enter the desktop screen to re-probe audio.")
        return counts
    except OSError:
        return {"status": "blocked", "reason": "AUDIO_CONSUMER_CONNECT_FAILED", "socket": socket_name}
    finally:
        try:
            adb.remove_forward(port)
        except Rejected:
            pass


def audio_verdict(audio):
    """No audio-quality judgment: presence/absence only, plus the known gap."""
    if audio.get("pcm", {}).get("status") == "pass":
        return "pass", "PCM_BYTES_COUNTED"
    if audio.get("guest_ready_observed") and audio.get("host_listener_observed"):
        return "pass", "AUDIO_RELAY_READY_WITHOUT_PCM_PROBE"
    if audio.get("relay_failed_observed"):
        return "fail", "AUDIO_RELAY_FAILED_EVENTS_OBSERVED"
    if audio.get("probe_events"):
        return "blocked", "AUDIO_LOOP_PROBING_WITHOUT_READY"
    return "blocked", "AUDIO_EVENTS_NOT_IN_LOG_BUFFER"


def poll_status(control, deadline, interval=1.0,
                stop_states=GOOD_STATES + BAD_STATES):
    while control.clock() < deadline:
        payload = control.feature("STATUS", PROFILE, min(deadline, control.clock() + 10))
        if payload.get("state") in stop_states:
            return payload
        time.sleep(min(interval, max(0.05, deadline - control.clock())))
    raise ChannelError("status-deadline-exceeded")


def wait_for_control(adb, args, deadline, window_seconds):
    """Re-acquire the control channel after the operator's presentation round."""
    end = min(deadline, time.monotonic() + float(window_seconds))
    while time.monotonic() < end:
        print("WAITING: control channel re-acquisition after the desktop-window round; "
              "%d seconds remain (exit/close the desktop window as instructed)"
              % max(0, int(end - time.monotonic())), flush=True)
        time.sleep(float(getattr(args, "confirm_poll_interval", 10.0)))
        factory, _ = socket_factory_for(adb, control_socket_name(args.instance_id))
        if factory is None:
            continue
        control = ControlChannel(factory, reply_timeout=args.hello_timeout)
        control.connect(control.clock() + 15)
        hello = control.hello(args.bridge_token, control.clock() + args.hello_timeout)
        if hello["drivable"]:
            return control
        control.close()
    return None


def run_audio_section(args, adb, sections):
    audio = {"status": "blocked", "reason": "SECTION_INCOMPLETE"}
    try:
        relay_events = parse_audio_events(adb.logcat("OrchestellaQemu"))
        probe_events = parse_audio_events(adb.logcat("AudioSource"))
        audio["relay_events"] = [event for event in relay_events if event["source"] == "audio-relay"][-40:]
        audio["probe_events"] = [event for event in probe_events if event["source"] == "audio-probe"][-40:]
        consumer_socket = resolve_audio_socket(args, audio["relay_events"])
        audio["consumer_socket"] = consumer_socket
        events = {event["event"] for event in audio["relay_events"]}
        audio["host_listener_observed"] = bool(
            {"consumer-listening", "consumer-attached", "transport-ready"} & events)
        audio["guest_ready_observed"] = "transport-ready" in events
        audio["relay_failed_observed"] = bool({"relay-failed", "relay-closed", "token-unavailable"} & events)
        if args.pcm_probe:
            audio["pcm"] = pcm_probe_for(adb, socket_name=consumer_socket)
        audio["status"], audio["reason"] = audio_verdict(audio)
    except Rejected as error:
        audio = {"status": "blocked", "reason": str(error)}
        note_missing("audio-logcat", str(error), "Rerun with the instance alive so logcat still carries the events.")
    sections["audio"] = audio


def run_guest_sections(args, adb, sections, control, terminal_name, deadline, forwards):
    """Sections 1, 2, 4, 3 behind the already-proven HELLO. Returns whether to stop."""
    stop = False

    def blocked(name, reason):
        sections[name] = {"status": "blocked", "reason": reason}
        return args.fail_fast

    # 1: profile lifecycle (INSTALL -> START -> STATUS).
    if "profile" in requested_names(args) and not stop:
        try:
            current = control.feature("STATUS", PROFILE, control.clock() + 10)
            transitions = [{"action": "STATUS", "state": current.get("state"),
                            "phase": current.get("phase"), "reason": current.get("reason")}]
            if not args.skip_install and current.get("state") == "planned":
                control.feature("INSTALL", PROFILE, control.clock() + 15)
                install_deadline = min(deadline, control.clock() + args.install_deadline)
                current = poll_status(control, install_deadline,
                                      stop_states=("installed", "starting", "awaiting-frame", "ready")
                                      + BAD_STATES)
                transitions.append({"action": "INSTALL", "state": current.get("state")})
            if (not args.skip_start and current.get("state") in ("installed", "starting")
                    and current.get("state") not in BAD_STATES):
                control.feature("START", PROFILE, control.clock() + 15)
                start_deadline = min(deadline, control.clock() + args.start_deadline)
                current = poll_status(control, start_deadline)
                transitions.append({"action": "START", "state": current.get("state")})
            state = current.get("state")
            ok = state in GOOD_STATES and current.get("installed") is True
            sections["profile"] = {"status": "pass" if ok else "fail",
                                   "reason": "PROFILE_" + str(state),
                                   "note": "profilectl START lands in awaiting-frame; state=ready "
                                           "additionally requires the presentation-gate confirm "
                                           "(capture section).",
                                   "transitions": transitions,
                                   "final_status": status_evidence(current)}
        except (ChannelError, Rejected) as error:
            if blocked("profile", error.reason if isinstance(error, ChannelError) else str(error)):
                stop = True

    # 2: compositor runtime (guest console probes).
    if "compositor" in requested_names(args) and not stop:
        probe = terminal_probe_for(adb, terminal_name, deadline, forwards)
        if probe is None:
            if blocked("compositor", "TERMINAL_CHANNEL_UNAVAILABLE"):
                stop = True
        else:
            try:
                result = run_compositor_probe(probe, min(deadline, probe.clock() + 60))
                checks = result["checks"]
                failed = [name for name in result["expected_checks"] if checks.get(name) != "0"]
                missing_checks = [name for name in result["expected_checks"] if name not in checks]
                evidence = {}
                for line in result["evidence"]:
                    key, _, value = line.partition("=")
                    if key in COMPOSITOR_EVIDENCE_KEYS:
                        evidence[key] = value
                pulse = evidence.get("pulseaudio_status", "")
                if (checks.get("pulseaudio_installed", "1") != "0"
                        or bool(pulse) and not pulse.endswith("ok installed")):
                    # DW-04: pulseaudio ships in desktop-labwc PACKAGES, so a missing
                    # package is a regular missing item (probe failure), not a recorded
                    # known gap. PCM/audio quality judgment stays out of scope.
                    note_missing("pulseaudio-installed", "PULSEAUDIO_NOT_INSTALLED_IN_GUEST",
                                 "desktop-labwc PACKAGES ships pulseaudio; the guest reports it missing. "
                                 "Repair the guest (apt-get install pulseaudio), restart "
                                 "orchestella-audio-relay.service and rerun. PCM/audio quality "
                                 "judgment remains out of scope.")
                ok = result["code"] == 0 and not failed and not missing_checks
                sections["compositor"] = {"status": "pass" if ok else "fail",
                                          "reason": ("COMPOSITOR_PROBE_OK" if ok
                                                     else "COMPOSITOR_PROBE_FAILED"),
                                          "failed_checks": failed,
                                          "missing_checks": missing_checks,
                                          "evidence": evidence,
                                          "audio_relay_unit_substate": evidence.get("audio_relay_substate")}
            except ChannelError as error:
                if blocked("compositor", error.reason):
                    stop = True
            finally:
                probe.close()

    # 4: display channel E2E (STATUS evidence + wayvnc RFB byte diversity).
    if "display" in requested_names(args) and not stop:
        try:
            samples = []
            for _ in range(5):
                samples.append(status_evidence(
                    control.feature("STATUS", PROFILE, control.clock() + 10)))
                if control.clock() >= deadline:
                    break
                time.sleep(1.0)
            probe = terminal_probe_for(adb, terminal_name, deadline, forwards)
            rfb = None
            if probe is not None:
                try:
                    rfb = run_display_probe(probe, min(deadline, probe.clock() + 40))
                except ChannelError as error:
                    rfb = {"status": "blocked", "reason": error.reason}
                finally:
                    probe.close()
            transport_ok = (bool(samples)
                            and all(sample.get("state") in GOOD_STATES for sample in samples)
                            and samples[0].get("transport") == "cpu-copy-rfb"
                            and samples[0].get("renderer") == "pixman")
            diversity_ok = isinstance(rfb, dict) and isinstance(rfb.get("rfb_bytes"), int) \
                and rfb["rfb_bytes"] > 0
            ok = transport_ok and diversity_ok
            sections["display"] = {"status": "pass" if ok else "fail",
                                   "reason": "DISPLAY_E2E_OK" if ok else "DISPLAY_E2E_INCOMPLETE",
                                   "status_samples": samples[-3:], "wayvnc_probe": rfb}
            if not diversity_ok:
                note_missing("display-rfb-diversity",
                             str(rfb.get("reason", "RFB_BYTES_NOT_OBSERVED")) if isinstance(rfb, dict)
                             else "TERMINAL_CHANNEL_UNAVAILABLE",
                             "Check orchestella-desktop-wayvnc.service inside the guest; the probe "
                             "needs an answerable guest console.")
        except ChannelError as error:
            if blocked("display", error.reason):
                stop = True

    # 3: wayvnc capture evidence + presentation-gate first frame. Runs last:
    # the confirmation needs the app, so this section releases the control
    # channel and never sends CONFIRM-FRAME itself.
    if "capture" in requested_names(args) and not stop:
        try:
            payload = control.feature("STATUS", PROFILE, control.clock() + 10)
            evidence = status_evidence(payload)
            capture_ok = (payload.get("capture") == "verified"
                          and payload.get("protocols") == "verified"
                          and payload.get("generation") == payload.get("capture_generation")
                          and isinstance(payload.get("capture_width"), int)
                          and isinstance(payload.get("capture_height"), int))
            state = payload.get("state")
            result = {"status": "pass", "reason": "CAPTURE_VERIFIED_AND_CONFIRMED",
                      "evidence": evidence, "capture_ok": capture_ok}
            if not capture_ok:
                result.update(status="fail", reason="CAPTURE_EVIDENCE_INVALID")
            elif state == "ready":
                result["presentation_gate"] = "first-frame-confirmed"
            elif state == "awaiting-frame":
                control.close()
                print("ACTION: open the desktop window for this instance and wait for the first "
                      "frame; then exit/close the desktop window so the script can re-probe STATUS.",
                      flush=True)
                reconnected = wait_for_control(adb, args, deadline, args.confirm_window)
                if reconnected is None:
                    result.update(status="blocked", reason="PRESENTATION_CONFIRM_NOT_OBSERVABLE",
                                  presentation_gate="pending-manual")
                    note_missing("presentation-gate", "CONTROL_CHANNEL_NOT_REACQUIRED",
                                 "Re-run with --skip-install --skip-start after closing the desktop "
                                 "window, or record the ready state from the device window manually.")
                else:
                    payload = reconnected.feature("STATUS", PROFILE, reconnected.clock() + 10)
                    final_state = payload.get("state")
                    result["evidence"] = status_evidence(payload)
                    result["presentation_gate"] = ("first-frame-confirmed" if final_state == "ready"
                                                   else "not-observed-state=" + str(final_state))
                    if final_state != "ready":
                        result.update(status="fail", reason="PRESENTATION_FIRST_FRAME_NOT_CONFIRMED")
                    reconnected.close()
            else:
                result.update(status="fail", reason="CAPTURE_STATE_" + str(state))
            sections["capture"] = result
        except ChannelError as error:
            if blocked("capture", error.reason):
                stop = True
    return stop


def check(args):
    started = time.monotonic()
    deadline = started + float(args.deadline)
    alias(args.device_alias)
    token_value(args.bridge_token)
    instance_value(args.instance_id)
    if getattr(args, "audio_socket", None) is not None:
        audio_socket_value(args.audio_socket)
    token_sha256 = hashlib.sha256(args.bridge_token.encode("ascii")).hexdigest()

    package = package_value(getattr(args, "package", PACKAGE) or PACKAGE)
    sections = {name: {"status": "not-tested", "reason": "SECTION_NOT_REQUESTED"}
                for name in SECTION_ORDER}
    preconditions = {"device_ready": False, "app_installed": False,
                     "instance_socket_present": False, "bridge_drivable": False}
    report = {"schema_version": 1, "kind": "gui_desktop_acceptance",
              "device_alias": args.device_alias,
              "instance_alias": "instance-" + args.instance_id[:8],
              "package": package, "profile": PROFILE,
              "bridge_token_sha256": token_sha256,
              "serials_recorded": False, "raw_output_recorded": False, "tokens_recorded": False,
              "preconditions": preconditions, "sections": sections, "missing": MISSING,
              "passed": False}
    code = 2
    forwards = []
    adb = None
    control = None
    try:
        # ---- Preconditions (always fail-fast) --------------------------------
        try:
            adb = Adb(args.adb, args.serial)
            counts = assert_device_ready(adb)
            preconditions["device_ready"] = True
            report["device_counts"] = counts
        except Rejected as error:
            note_missing("adb-device", str(error),
                         "Provide --adb (absolute adb path) and --serial of exactly one ready device.")
            return finish(report, args, 2, started)
        try:
            if "package:" in adb.package_path(package):
                preconditions["app_installed"] = True
            else:
                raise Rejected("APP_NOT_INSTALLED")
        except Rejected as error:
            note_missing("app-installed", str(error),
                         "Install the Orchestella APK and start the desktop-labwc instance.")
            return finish(report, args, 2, started)

        control_name = control_socket_name(args.instance_id)
        terminal_name = terminal_socket_name(args.instance_id)
        factory, port = socket_factory_for(adb, control_name)
        if factory is None:
            note_missing("instance-running", "INSTANCE_SOCKET_ABSENT",
                         "Start the desktop-labwc instance in the device window, then rerun.")
            return finish(report, args, 2, started)
        forwards.append(port)
        preconditions["instance_socket_present"] = True

        control = ControlChannel(factory, reply_timeout=args.hello_timeout)
        control.connect(min(deadline, control.clock() + 15))
        hello = control.hello(args.bridge_token,
                              min(deadline, control.clock() + args.hello_timeout))
        preconditions["bridge_drivable"] = bool(hello["drivable"])

        # ---- Section 6 (GPU; logcat surfaces available without the channels) --
        if section_requested(args.sections, "gpu"):
            try:
                gpu_result, gpu_verdict = check_gpu_logcat(adb, gpu_enabled=args.gpu_enabled, package=package)
                sections["gpu"] = {"status": gpu_verdict["status"], "reason": gpu_verdict["reason"],
                                   "evidence": gpu_result}
                if gpu_verdict["status"] == "fail":
                    sections["gpu"]["remediation"] = (
                        "Launch the instance with gpuAccelerationEnabled=true. The real 3D/VirGL gate "
                        "fails on FALLBACK reasons NATIVE_UNAVAILABLE / INIT_FAILED; a "
                        "QEMU_DEVICE_MODEL_UNSUPPORTED fallback is the expected packaged-binary 2D "
                        "downgrade and passes as GPU_EXPECTED_2D_FALLBACK (no 3D claim).")
            except Rejected as error:
                sections["gpu"] = {"status": "blocked", "reason": str(error)}
                note_missing("gpu-logcat", str(error),
                             "Rerun with the instance freshly launched so the buffer holds the line.")
            if args.gpu_benchmark and sections["gpu"]["reason"] == "GPU_EXPECTED_2D_FALLBACK":
                sections["gpu"]["benchmark"] = {"status": "skipped", "reason": "GPU_EXPECTED_2D_FALLBACK"}
            if args.gpu_benchmark and sections["gpu"]["reason"] == "GPU_ACTIVE" and hello["drivable"]:
                probe = terminal_probe_for(adb, terminal_name, deadline, forwards)
                if probe is not None:
                    try:
                        bench = run_guest_benchmark(probe, min(deadline, probe.clock() + 420))
                        sections["gpu"]["benchmark"] = bench
                        if bench["status"] == "missing":
                            note_missing("glmark2", bench["reason"], bench["remediation"])
                        elif bench["status"] == "fail":
                            sections["gpu"]["status"] = "fail"
                            sections["gpu"]["reason"] = bench["reason"]
                    except ChannelError as error:
                        sections["gpu"]["benchmark"] = {"status": "blocked", "reason": error.reason}
                    finally:
                        probe.close()

        # ---- Section 5 (audio; logcat surfaces always, PCM probe opt-in) ------
        if section_requested(args.sections, "audio"):
            run_audio_section(args, adb, sections)

        # ---- Guest-side sections 1/2/4/3 (need an answerable HELLO) -----------
        if not hello["drivable"]:
            remediation = ("Run right after instance start before any screen is opened (the app "
                           "owns the QEMU chardev clients once a screen attaches), or use a "
                           "channel-free instance; otherwise use the manual walkthrough checklist.")
            for name, item_id in (("profile", "section-profile"),
                                  ("compositor", "section-compositor"),
                                  ("display", "section-display"),
                                  ("capture", "section-capture")):
                if section_requested(args.sections, name):
                    sections[name] = {"status": "blocked", "reason": "CONTROL_CHANNEL_OCCUPIED"}
                    note_missing(item_id, "CONTROL_CHANNEL_" + hello["reason"].upper(), remediation)
        else:
            run_guest_sections(args, adb, sections, control, terminal_name, deadline, forwards)

        requested = requested_names(args)
        statuses = [sections[name]["status"] for name in requested]
        if any(status == "fail" for status in statuses):
            code = 1
        elif any(status == "blocked" for status in statuses):
            code = 2
        else:
            code = 0
    finally:
        if control is not None:
            control.close()
        if adb is not None:
            for port in forwards:
                try:
                    adb.remove_forward(port)
                except Rejected:
                    pass
    return finish(report, args, code, started)


def finish(report, args, code, started):
    report["missing"] = MISSING
    report["passed"] = code == 0
    report["seconds"] = round(time.monotonic() - started, 3)
    path = output_path(args.output_root, args.output)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(report, ensure_ascii=True, indent=2, sort_keys=True) + "\n")
    if getattr(args, "walkcheck", None):
        walkcheck = output_path(args.output_root, args.walkcheck, extensions=(".yaml",))
        walkcheck.parent.mkdir(parents=True, exist_ok=True)
        with walkcheck.open("x", encoding="utf-8") as stream:
            stream.write(walkcheck_yaml(args.device_alias, report["instance_alias"]))
    print(json.dumps({"exit": code, "missing": MISSING,
                      "sections": {name: section["status"]
                                   for name, section in report["sections"].items()},
                      "evidence": str(path)}, sort_keys=True))
    return code


def emit_walkcheck(args):
    alias(args.device_alias)
    instance = args.instance_id or "00000000-0000-0000-0000-000000000000"
    instance_value(instance)
    path = output_path(args.output_root, args.output, extensions=(".yaml",))
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream:
        stream.write(walkcheck_yaml(args.device_alias, "instance-" + instance[:8]))
    print(json.dumps({"exit": 0, "walkcheck": str(path)}, sort_keys=True))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(prog="gui_desktop_acceptance", description=__doc__)
    sub = parser.add_subparsers(dest="operation", required=True)

    def common(command):
        command.add_argument("--device-alias", required=True)
        command.add_argument("--output-root", required=True,
                             help="explicit absolute evidence output root (outside the repo)")
        command.add_argument("--output", required=True, help="new relative evidence/*.json path")
        command.add_argument("--walkcheck", help="optional relative *.yaml path for the manual checklist")

    check_command = sub.add_parser("check")
    common(check_command)
    check_command.add_argument("--adb", required=True)
    check_command.add_argument("--serial", required=True)
    check_command.add_argument("--instance-id", required=True)
    check_command.add_argument("--bridge-token", required=True)
    check_command.add_argument("--package", default=PACKAGE,
                               help="target Android applicationId to inspect")
    check_command.add_argument("--sections", default="all",
                               help="comma list of profile,compositor,display,capture,audio,gpu")
    check_command.add_argument("--fail-fast", action="store_true")
    check_command.add_argument("--skip-install", action="store_true")
    check_command.add_argument("--skip-start", action="store_true")
    check_command.add_argument("--gpu-enabled", action="store_true",
                               help="the instance was launched with gpuAccelerationEnabled=true")
    check_command.add_argument("--gpu-benchmark", action="store_true",
                               help="run the guest glmark2 (Ticket58 surface) after a passing GPU log verdict")
    check_command.add_argument("--pcm-probe", action="store_true",
                               help="attach as the audio consumer and count PCM bytes")
    check_command.add_argument("--audio-socket", default=None,
                               help="override abstract audio consumer socket name (defaults to per-instance derivation)")
    check_command.add_argument("--deadline", type=int, default=1800)
    check_command.add_argument("--install-deadline", type=int, default=900)
    check_command.add_argument("--start-deadline", type=int, default=240)
    check_command.add_argument("--confirm-window", type=int, default=300)
    check_command.add_argument("--confirm-poll-interval", type=float, default=10.0)
    check_command.add_argument("--hello-timeout", type=float, default=6.0)

    walk_command = sub.add_parser("emit-walkcheck")
    common(walk_command)
    walk_command.add_argument("--instance-id", default=None)

    args = parser.parse_args(argv)
    try:
        if args.operation == "check":
            return check(args)
        return emit_walkcheck(args)
    except Rejected as error:
        print(json.dumps({"error": str(error), "missing": MISSING}, sort_keys=True))
        return 2


if __name__ == "__main__":
    sys.exit(main())
