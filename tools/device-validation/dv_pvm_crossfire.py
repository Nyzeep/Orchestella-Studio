#!/usr/bin/env python3
"""DV-PVM-02b crossfire host driver (QA-only).

Drives the full 02b compile path: a real QEMU Alpine guest (the distcc
client) dialing the production `DistccTcpVsockBridge` through gvproxy's
hostIP mapping (guest 192.168.127.254:5103 -> host 127.0.0.1:5103), with the
protected Microdroid compiler payload behind the bridge. The
`compiler-crossfire` instrumentation owns both lanes under the production
`DualLaneCoordinator`; this driver phases the run:

- smoke: launch the QA app's main activity and capture the
  `OrchestellaAccelerator` decision/event lines from logcat.
- crossfire: run the compiler-crossfire instrumentation, then drive the QEMU
  guest console (fixture in, guest reference compile, distcc compile through
  the bridge, objects back out) and judge equivalence on the host.
- cleanup: print the stayon/fixture command list for the coordinating
  session; the coordinator owns the actual device operations.

Relationship to `dv_pvm_compiler.py`: the Adb wrapper, the fixture generator
and the foreground helper are imported from it, because a second definition
of the same facts would drift. This driver adds only the guest console
client and the crossfire orchestration.

Evidence hygiene: serials never reach stdout or the summary. Only fixed
codes, byte counts and timings are recorded.
"""

from __future__ import annotations

import argparse
import base64
import codecs
import json
import re
import secrets
import socket
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path

# Same-directory import, noted per the plan: the fixture generator, the Adb
# wrapper and the HyperOS foreground helper are the DV-PVM-02 implementations
# (build_fixture is a module-level function there, not private).
from dv_pvm_compiler import EVIDENCE_DIR, Adb, build_fixture, foreground_qa_app

INSTRUMENTATION = "io.github.nyzeep.orchestella.PvmProbeInstrumentation"
DEFAULT_QA_PACKAGE = "io.github.nyzeep.orchestella.qa.dvpvm"
# FastLaneDv01Contract.DEFAULT_DISTCC_PORT; the bridge must bind exactly this
# port because gvproxy's hostIP mapping is 1:1 on the host port.
CONTRACT_DISTCC_PORT = 5103
GUEST_HOST_IP = "192.168.127.254"
TERMINAL_SOCKET_PREFIX = "droidlayer-terminal-"
# The guest clang major the payload handshake expects; anything else aborts
# before the compile so a distro update cannot silently change the verdict.
REQUIRED_CLANG_MAJOR = 22
GUEST_FIXTURE = "/root/fixture.c"
# The C++ fixture keeps the .cpp extension: the guest compiles it with clang++,
# which is what makes distcc send `-x c++-cpp-output` and the payload take its
# C++ path.
GUEST_FIXTURE_CXX = "/root/fixture.cpp"
GUEST_REF_O = "/root/ref.o"
GUEST_CROSS_O = "/root/cross.o"
GUEST_MAIN_C = "/root/main.c"
GUEST_MAIN_O = "/root/main.o"
GUEST_SLACK_C = "/root/slack.c"
GUEST_SLACK_O = "/root/slack.o"
# The product catalog (DistroCatalogRegistry) has no Alpine entry, so the
# guest is whatever distro the QA instance provisioned. The instrumentation
# publishes its distro id; each family gets its package-manager line. The
# clang major hard-check below stays the real gate either way.
GUEST_INSTALL = {
    "alpine": "apk add distcc clang",
    "arch": "pacman -Sy --noconfirm --needed distcc clang",
    "debian": "apt-get update -qq && apt-get install -y -qq distcc clang",
    "ubuntu": "apt-get update -qq && apt-get install -y -qq distcc clang",
    "fedora": "dnf install -y distcc clang",
    "almalinux": "dnf install -y distcc clang",
    "rocky": "dnf install -y distcc clang",
    "rockylinux": "dnf install -y distcc clang",
    "opensuse": "zypper -n in distcc clang",
}
GUEST_INSTALL_DEFAULT = (
    "command -v apk >/dev/null && apk add distcc clang ; "
    "command -v pacman >/dev/null && pacman -Sy --noconfirm --needed distcc clang ; "
    "command -v apt-get >/dev/null && apt-get update -qq && apt-get install -y -qq distcc clang ; "
    "command -v dnf >/dev/null && dnf install -y distcc clang"
)
DEVICE_FIXTURE_PUSH = "/sdcard/Download/fixture.c"
# The instrumentation is sized for a ~92 KB fixture; a 1 MB input would blow
# past the AXP1 frame budget and the console round-trips alike.
MAX_FIXTURE_BYTES = 1_000_000
GUEST_TRIPLE = "aarch64-unknown-linux-android"
GUEST_DOT_I = "/root/fixture.i"
GUEST_DOT_I_O = "/root/fromdoti.o"
RC_MARKER_PATTERN = r"QA_CF_RC_{index}=(\d+)"
B64_CHUNK_BYTES = 1024  # tty canonical mode drops input past ~4 KiB per line
# Perf attribution (`--profile-segments`). PROVEN constant, not a measurement:
# DV-PVM-02a's file-channel run clocked the payload side — request in, compile,
# answer out — at 1.05-1.08 s for a 92 KB `.i`, so payload and vsock are not
# the bottleneck. The ledger subtracts this and reports the remainder as
# unexplained instead of attributing it to something nobody measured.
PAYLOAD_COMPILE_BASELINE_MS = 1050
# A distcc v1 response is five token headers (DONE, STAT, SERR, SOUT, DOTO)
# before any body, so a response shorter than this cannot contain a DOTO. It is
# a protocol floor, not a tuned threshold: below it the payload answered
# something, but not a distcc response.
MIN_DISTCC_RESPONSE_BYTES = 5 * 12
# What distcc prints when the response stops early: the client recompiled
# locally and still exited 0, which is exactly how a stalled payload used to
# look like a successful remote compile.
TRUNCATION_MARKERS = ("EXIT_TRUNCATED",)
# What distcc prints when the peer answered but the job was refused -- a payload
# ARGV rejection lands here, and so does any distcc-level failure code. It is a
# local fallback too, but it is not a truncation, and the two need different
# fixes.
LOCAL_FALLBACK_MARKERS = ("failed, retrying locally", "running locally instead")
# The fixed codes a payload can answer with. Captured so `crossfire.json` names
# the rejection instead of leaving it in the guest's scrollback.
PAYLOAD_CODE_PATTERN = re.compile(
    r"\b(ARGV_REJECTED:[A-Z_]{2,32}|JOB_TOO_LARGE|JOB_TIMEOUT|COMPILE_ERROR"
    r"|PAYLOAD_LOAD_FAILED)\b")
# Probe ports: 5103 is the production bridge contract, 19999 is the object
# return in this same round, 5037 is the adb server. Anything else is free.
PERF_PORT_UP_DEFAULT = 19901
PERF_PORT_DOWN_DEFAULT = 19902
PERF_RESERVED_PORTS = (CONTRACT_DISTCC_PORT, 19999, 5037)
# Matches the 02b round trip for the `.o` (179,912 B with the symbol table):
# the segment probe only needs the same order of magnitude of bytes.
PERF_DOWN_BYTES = 180 * 1024
DEVICE_PERF_UP = "/data/local/tmp/qa-perf-up.bin"
DEVICE_PERF_DOWN = "/data/local/tmp/qa-perf-down.bin"
# A stalled `nc` on either side must not eat the whole guest command budget;
# the probes are diagnostics, the crossfire evidence is the deliverable.
PERF_NC_TIMEOUT_S = 180.0
# Guest-side `timeout` wrapper for the transfer probes. The console session is
# strictly serial, so a hanging `nc` does not just cost the probe: it starves
# the distcc run behind it and the whole round is lost. Bound the damage at the
# shell instead of relying on the console deadline.
PERF_GUEST_NC_GUARD_S = 150
# Round-level segment fields. Every one of them is present on every round and
# `None` means "not measured" — never 0, which would read as "measured zero".
SEGMENT_ROUND_FIELDS = (
    "preprocess_ms_cold", "preprocess_ms_warm", "preprocess_ms",
    "preprocess_ms_samples", "dot_i_bytes",
    "assemble_ms", "assemble_ms_samples",
    "transport_up_ms", "transport_up_bytes",
    "transport_down_ms", "transport_down_bytes",
    "transport_ms", "transport_bytes",
    "transport_down_small_ms", "transport_down_small_bytes",
    "guest_has_nc", "perf_probe_errors",
)
# Summary-level aggregation keys (perf v2 vocabulary, README §7.1).
SEGMENT_SUMMARY_FIELDS = (
    "preprocess_ms_cold", "preprocess_ms_warm", "preprocess_ms_p50",
    "assemble_ms_p50", "transport_up_ms_p50", "transport_down_ms_p50",
    "transport_ms_p50", "transport_bytes", "segment_samples", "ledger",
)


class GuestConsole:
    """Minimal console client in the run_distro_smoke.py shape.

    One reader thread owns the connection; command sync uses an echo-able
    RC marker (the shell is under our control), with the OSC133 B prompt
    tracked for the initial shell-ready wait exactly like the smoke driver.
    """

    PROMPT = re.compile(r"\x1b\]133;B(?:;[^\x07\x1b]*)?(?:\x07|\x1b\\)")

    def __init__(self, connection):
        self.connection = connection
        self.text = ""
        self.error = None
        self.lock = threading.Lock()
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self._read, daemon=True)
        self.thread.start()

    def _read(self):
        decoder = codecs.getincrementaldecoder("utf-8")("replace")
        while not self.stopped.is_set():
            try:
                data = self.connection.recv(65536)
            except socket.timeout:
                continue
            except OSError:
                break
            if not data:
                break
            with self.lock:
                self.text = (self.text + decoder.decode(data))[-4 * 1024 * 1024:]

    def snapshot(self, since=None):
        """ANSI-stripped view; run_distro_smoke's regexes, small cap kept."""
        with self.lock:
            text = self.text if since is None else self.text[since:]
        text = re.sub(r"\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)", "", text)
        return re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", text).replace("\r", "").replace("\x00", "")

    def raw_length(self):
        with self.lock:
            return len(self.text)

    def wait_prompt(self, deadline_s):
        """Wait for an OSC133 B prompt; fall back to RC-marker probing.

        The baseline provision's package stage can fail (offline mirror),
        which leaves the guest without the prompt marker while the shell
        itself is fine. A returned exit-code marker proves the shell, so
        probe for that on a short cycle alongside the marker watch.
        """
        started = time.monotonic()
        next_probe = 0.0
        while time.monotonic() - started < deadline_s:
            if self.error:
                return False
            if self.PROMPT.search(self.snapshot()):
                return True
            now = time.monotonic()
            if now >= next_probe:
                next_probe = now + 10.0
                offset = self.raw_length()
                try:
                    self.connection.sendall(
                        b'true; printf "\\nQA_CF_PROBE=%s\\n" "$?"\r')
                except OSError:
                    return False
                probe_deadline = time.monotonic() + 8.0
                while time.monotonic() < probe_deadline:
                    if self.error:
                        return False
                    if re.search(r"QA_CF_PROBE=\d+", self.snapshot(since=offset)):
                        return True
                    time.sleep(0.2)
            time.sleep(0.5)
        return False

    def run(self, command, index, timeout_s):
        """Send one command; return (rc, text, elapsed_ms). The marker makes
        the exit code explicit, so prompt timing never gates a command."""
        marker = f"QA_CF_RC_{index}"
        offset = self.raw_length()
        # \n stays literal here: printf interprets it inside the guest shell.
        line = command + f'; printf "\\n{marker}=%s\\n" "$?"\r'
        self.connection.sendall(line.encode("utf-8"))
        started = time.monotonic()
        pattern = re.compile(RC_MARKER_PATTERN.format(index=index), re.M)
        while time.monotonic() - started < timeout_s:
            if self.error:
                return None, self.snapshot(since=offset)[-4000:], -1
            text = self.snapshot(since=offset)
            match = pattern.search(text)
            if match:
                body = text[:match.start()]
                return int(match.group(1)), body[-400000:], int(
                    (time.monotonic() - started) * 1000)
            time.sleep(0.2)
        return None, self.snapshot(since=offset)[-400000:], -1

    def close(self):
        self.stopped.set()
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.connection.close()
        except OSError:
            pass
        self.thread.join(timeout=3)


def guest_connect(adb: Adb, terminal_socket: str) -> GuestConsole:
    """adb forward tcp:0 -> the guest's abstract terminal socket."""
    port_text = adb.run("forward", "tcp:0",
                        f"localabstract:{terminal_socket}").strip()
    if not port_text.isdigit():
        raise RuntimeError("forward-port-invalid")
    connection = socket.create_connection(("127.0.0.1", int(port_text)), timeout=5)
    connection.settimeout(0.5)
    return GuestConsole(connection)


def parse_clang_major(version_text: str):
    match = re.search(r"clang version (\d+)\.", version_text)
    return int(match.group(1)) if match else None


def guest_fixture_path(args) -> str:
    """Where the fixture lives in the guest; the extension picks the language."""
    return GUEST_FIXTURE_CXX if getattr(args, "language", "c") == "cxx" else GUEST_FIXTURE


def guest_compiler(args) -> str:
    """The guest driver binary: clang++ for a C++ job, clang otherwise."""
    return "clang++" if getattr(args, "language", "c") == "cxx" else "clang"


def push_fixture_via_console(console: GuestConsole, fixture_bytes: bytes,
                             command_timeout_s: int, next_index,
                             chroot_prefix: str = "",
                             fixture_path: str = GUEST_FIXTURE) -> dict:
    """Console base64 fallback: chunked appends, one decode, size check.

    Chunk lines stay far below the tty canonical-mode line limit; the file
    paths are outer-shell (chroot prefix applied by the caller).
    """
    encoded = base64.b64encode(fixture_bytes).decode("ascii")
    b64_path = f"{chroot_prefix}/root/fixture.b64"
    out_path = f"{chroot_prefix}{fixture_path}"
    console.run(f"rm -f {b64_path} {out_path}", next_index(),
                command_timeout_s)
    sent = 0
    for offset in range(0, len(encoded), B64_CHUNK_BYTES):
        chunk = encoded[offset:offset + B64_CHUNK_BYTES]
        rc, _, _ = console.run(
            f"printf '%s' '{chunk}' >> {b64_path}", next_index(),
            command_timeout_s)
        if rc != 0:
            return {"path": "console-base64", "injected": False, "rc": rc,
                    "sent": sent}
        sent += len(chunk)
    rc, text, _ = console.run(
        f"base64 -d {b64_path} > {out_path} && wc -c < {out_path}",
        next_index(), command_timeout_s)
    size_ok = rc == 0 and text.strip().splitlines()[-1:] == [str(len(fixture_bytes))]
    return {"path": "console-base64", "injected": size_ok, "rc": rc,
            "chunks": (len(encoded) + B64_CHUNK_BYTES - 1) // B64_CHUNK_BYTES}


def inject_fixture(adb: Adb, console: GuestConsole, fixture_bytes: bytes,
                   command_timeout_s: int, next_index,
                   chroot_prefix: str = "",
                   fixture_path: str = GUEST_FIXTURE) -> dict:
    """Primary path: 9p downloads share; fallback: console base64 chunks.

    The product launch only attaches the share when the instance declares
    it, so the fallback is the expected path; both are implemented because
    the share may appear in later launches.
    """
    outer_fixture = f"{chroot_prefix}{fixture_path}"
    # A previous round's fixture may already sit in the persistent rootfs at
    # the exact size: skip the transfer entirely in that case.
    rc, text, _ = console.run(f"wc -c < {outer_fixture}", next_index(),
                              command_timeout_s)
    lines = [line.strip() for line in text.strip().splitlines()]
    if rc == 0 and lines and lines[-1] == str(len(fixture_bytes)):
        return {"path": "already-present", "injected": True, "rc": 0}
    console.run(
        "mkdir -p /mnt/downloads && mount -t 9p -o trans=virtio,version=9p2000.L,ro "
        "downloads /mnt/downloads 2>/dev/null || true; cp /mnt/downloads/fixture.c "
        f"{outer_fixture} 2>/dev/null || true", next_index(), command_timeout_s)
    rc, text, _ = console.run(f"wc -c < {outer_fixture}", next_index(),
                              command_timeout_s)
    lines = [line.strip() for line in text.strip().splitlines()]
    if rc == 0 and lines and lines[-1] == str(len(fixture_bytes)):
        return {"path": "9p-downloads", "injected": True, "rc": 0}
    result = push_fixture_via_console(console, fixture_bytes, command_timeout_s,
                                      next_index, chroot_prefix)
    result["primary_path"] = "9p-downloads"
    return result


def return_object_via_netcat(adb: Adb, console: GuestConsole,
                             guest_path_inner: str, local_path: Path,
                             port: int, tool, next_index,
                             timeout_s: float) -> dict:
    """Primary object return: guest nc -> gvproxy NAT -> device nc -> adb pull.

    The console path is tty-bound (canonical limits, echo noise); the guest's
    outbound network through gvproxy's hostIP mapping is a plain pipe.
    """
    device_tmp = f"/data/local/tmp/qa-cxcf-{port}.o"
    listener = subprocess.Popen(
        [adb.adb_path] + (["-s", adb.serial] if adb.serial else []) +
        ["shell", f"nc -l -p {port} > {device_tmp}"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.5)
    try:
        console.run(tool(f"nc {GUEST_HOST_IP} {port} < {guest_path_inner}"),
                    next_index(), timeout_s)
        try:
            listener.wait(timeout=timeout_s)
        except subprocess.TimeoutExpired:
            listener.kill()
            return {"ok": False, "path": "netcat", "reason": "listener-timeout"}
        adb.run("pull", device_tmp, str(local_path))
        adb.run("shell", "rm", "-f", device_tmp)
        data = local_path.read_bytes()
        return {"ok": len(data) > 0, "path": "netcat", "bytes": len(data),
                "guest_path": guest_path_inner}
    finally:
        if listener.poll() is None:
            listener.kill()


def return_object_via_console(console: GuestConsole, guest_path: str,
                              local_path: Path, command_timeout_s: int,
                              next_index) -> dict:
    """Pull one object back by console base64; simple and reliable.

    `guest_path` must be a plain POSIX string: a Python Path on Windows
    renders backslashes that the guest shell silently eats.
    """
    started = time.monotonic()
    rc, text, _ = console.run(
        f"echo QA_B64_BEGIN; base64 {guest_path}; echo QA_B64_END",
        next_index(), command_timeout_s)
    if rc != 0:
        return {"ok": False, "rc": rc, "guest_path": guest_path,
                "stderr_tail": text.strip().splitlines()[-2:]}
    # Keep only the sentinel-delimited segment: the console stream mixes in
    # prompt echoes and markers whose characters are all valid base64.
    segment = text[text.rfind("QA_B64_BEGIN"):]
    end = segment.find("QA_B64_END")
    segment = segment[:end] if end >= 0 else segment
    payload = "".join(re.findall(r"[A-Za-z0-9+/=]", segment))
    try:
        data = base64.b64decode(payload, validate=False)
    except Exception:  # noqa: BLE001 - a truncated stream is a failure code, not a crash
        return {"ok": False, "rc": rc, "guest_path": guest_path,
                "reason": "base64-decode-failed"}
    local_path.write_bytes(data)
    return {"ok": True, "guest_path": guest_path,
            "local_path": str(local_path), "bytes": len(data),
            "elapsed_ms": int((time.monotonic() - started) * 1000)}


def blank_segment_fields() -> dict:
    """The segment fields a round starts with: all `None` ("not measured").

    Present even when `--profile-segments` is off so the 02b JSON shape stays
    one shape; the values just stay `None` instead of turning into 0.
    """
    return {name: None for name in SEGMENT_ROUND_FIELDS}


def median_sample(values) -> float | int | None:
    """Median over the usable samples; `None` is dropped, never read as 0.

    Negative values are the console client's "no answer" sentinel (-1), so
    they are dropped for the same reason: a timed-out probe is not a fast
    probe. An empty list has no median, and says so.
    """
    clean = [value for value in (values or [])
             if isinstance(value, (int, float)) and not isinstance(value, bool)
             and value >= 0]
    if not clean:
        return None
    return statistics.median(clean)


def _record_probe_error(round_result: dict, code: str) -> None:
    """One short code per distinct probe failure; never raises, never repeats."""
    errors = round_result.get("perf_probe_errors")
    if not isinstance(errors, list):
        errors = []
        round_result["perf_probe_errors"] = errors
    if code not in errors:
        errors.append(code)


def run_guest_transfer(console: GuestConsole, tool, next_index,
                       inner_command: str, outer_command: str,
                       timeout_s: float, chroot_active: bool) -> tuple:
    """Run a guest-side `nc` transfer; return (rc, elapsed_ms).

    `inner_command` goes through `tool()` exactly like the real distcc and
    object-return legs, i.e. inside `/alpine` when the prepared chroot is
    active. A minirootfs may ship no netcat, so a "command not found" (127)
    retries once on the outer shell: `outer_command` is the same transfer with
    the chroot-prefixed path, or the identical text when the transfer touches
    no guest file (the download leg only writes `/dev/null`). A timeout stays
    a timeout — retrying a hung `nc` would just burn another budget.
    """
    rc, text, elapsed = console.run(tool(inner_command), next_index(), timeout_s)
    if rc == 127 and chroot_active:
        rc, text, elapsed = console.run(outer_command, next_index(), timeout_s)
    # The console text rides along: a failed probe with only a code is a
    # dead end, the guest's own words are the diagnosis.
    return rc, text, elapsed


def _wait_perf_listener(listener: subprocess.Popen, round_result: dict,
                        timeout_code: str) -> bool:
    """Wait for the device-side `nc -l` to exit; kill and flag it otherwise."""
    try:
        listener.wait(timeout=PERF_NC_TIMEOUT_S)
        return True
    except subprocess.TimeoutExpired:
        listener.kill()
        _record_probe_error(round_result, timeout_code)
        return False


def _probe_transport_up(adb: Adb, console: GuestConsole, tool, next_index,
                        chroot_prefix: str, port: int, expected_bytes,
                        round_work: Path, round_result: dict) -> dict:
    """Guest -> gvproxy -> device loopback upload over the distcc topology.

    Device side listens on its own loopback (gvproxy maps the guest's
    192.168.127.254:<port> there 1:1, which is why the bridge contract port is
    fixed); the guest side is a plain `nc` pipe, same shape as
    `return_object_via_netcat`.
    """
    result: dict = {"elapsed_ms": None, "bytes": None}
    local_path = round_work / "perf-up.bin"
    device_tmp = DEVICE_PERF_UP
    listener = subprocess.Popen(
        [adb.adb_path] + (["-s", adb.serial] if adb.serial else []) +
        ["shell", f"nc -l -p {port} > {device_tmp}"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.5)
    try:
        # `timeout` is the seatbelt: a wedged guest `nc` must not hold the
        # console session, because the very next thing on that session is the
        # distcc run we came to measure. Alpine busybox and Arch coreutils
        # both ship it; if it is missing the command 127s and is reported as a
        # probe failure, which is the right outcome either way.
        inner = f"timeout {PERF_GUEST_NC_GUARD_S} nc {GUEST_HOST_IP} {port} " \
                f"< {GUEST_DOT_I}"
        outer = f"timeout {PERF_GUEST_NC_GUARD_S} nc {GUEST_HOST_IP} {port} " \
                f"< {chroot_prefix}{GUEST_DOT_I}"
        rc, transfer_text, elapsed = run_guest_transfer(
            console, tool, next_index, inner, outer,
            PERF_NC_TIMEOUT_S, bool(chroot_prefix))
        transferred = rc == 0 and elapsed >= 0
        if elapsed < 0:
            _record_probe_error(round_result, "upload_timeout")
        elif rc != 0:
            _record_probe_error(round_result, "upload_nc_failed")
            round_result.setdefault("perf_probe_details", {})["upload"] = {
                "rc": rc,
                "tail": (transfer_text or "").strip().splitlines()[-3:],
            }
        else:
            result["elapsed_ms"] = elapsed
        _wait_perf_listener(listener, round_result, "upload_listener_timeout")
        if transferred:
            local_path.unlink(missing_ok=True)
            adb.run("pull", device_tmp, str(local_path), check=False)
            if local_path.exists():
                result["bytes"] = len(local_path.read_bytes())
                if expected_bytes is not None and result["bytes"] != expected_bytes:
                    _record_probe_error(round_result, "upload_size_mismatch")
            else:
                _record_probe_error(round_result, "upload_pull_failed")
    finally:
        if listener.poll() is None:
            listener.kill()
        adb.run("shell", "rm", "-f", device_tmp, check=False)
    return result


def _probe_transport_down(adb: Adb, console: GuestConsole, tool, next_index,
                          port: int, chroot_prefix: str, round_result: dict,
                          size_kib: int = 180, label: str = "download") -> dict:
    """Device -> gvproxy -> guest download: the return leg of a real job.

    `label` scopes the failure codes so the 180 KiB and 8 KiB runs cannot
    overwrite each other's verdict. The size pair exists to separate
    "bandwidth" from "stall": if a 180 KiB transfer is slow but 8 KiB is
    quick, the cost scales with bytes; if both are slow, the leg stalls
    before it moves anything.
    """
    result: dict = {"elapsed_ms": None, "bytes": None}
    device_tmp = f"{DEVICE_PERF_DOWN}.{size_kib}"
    adb.run("shell", "dd", "if=/dev/zero", f"of={device_tmp}", "bs=1024",
            "count=%d" % size_kib, check=False)
    listener = subprocess.Popen(
        [adb.adb_path] + (["-s", adb.serial] if adb.serial else []) +
        # Redirect, not a pipe: toybox `nc -l` fed by `cat |` accepted the
        # connection but never flushed the file, so the guest side waited for
        # data that never came and both ends hung. A plain `<` lets nc own the
        # fd and stream it.
        ["shell", f"nc -l -p {port} < {device_tmp}"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    # 3 s, not 1.5: the `dd` above plus process start can exceed the old
    # window, and a listener that is not up yet makes the guest's `nc` fail
    # instantly, which reads as a broken link instead of a late one.
    time.sleep(3.0)
    try:
        # /dev/null exists in both worlds: the chroot gets one mknod'ed by
        # the console setup, so the same path works either way.
        command = f"timeout {PERF_GUEST_NC_GUARD_S} nc {GUEST_HOST_IP} " \
                  f"{port} > /dev/null"
        rc, text, elapsed = run_guest_transfer(console, tool, next_index, command,
                                               command, PERF_NC_TIMEOUT_S,
                                               bool(chroot_prefix))
        if elapsed < 0:
            _record_probe_error(round_result, f"{label}_timeout")
        elif rc != 0:
            # A bare code names the symptom, not the cause; the console's own
            # words (sh: not found, Permission denied, timeout killed...) are
            # what turns a red probe into a fixable one.
            _record_probe_error(round_result, f"{label}_nc_failed")
            round_result.setdefault("perf_probe_details", {})[label] = {
                "rc": rc,
                "tail": (text or "").strip().splitlines()[-3:],
            }
        else:
            result["elapsed_ms"] = elapsed
            result["bytes"] = size_kib * 1024
        _wait_perf_listener(listener, round_result, f"{label}_listener_timeout")
    finally:
        if listener.poll() is None:
            listener.kill()
        adb.run("shell", "rm", "-f", device_tmp, check=False)
    return result


def _read_dot_i_bytes(console: GuestConsole, tool, next_index) -> int | None:
    """Size of the retained `.i`; the byte check every transport probe needs."""
    rc, text, _ = console.run(tool(f"wc -c < {GUEST_DOT_I}"), next_index(), 60)
    if rc != 0:
        return None
    lines = [line.strip() for line in text.strip().splitlines() if line.strip()]
    return int(lines[-1]) if lines and lines[-1].isdigit() else None


def probe_segments(adb: Adb, console: GuestConsole, args, tool, next_index,
                   chroot_prefix: str, round_work: Path,
                   round_result: dict) -> None:
    """Measure P (guest preprocess) and T (gvproxy transport) in this session.

    Runs after fixture injection and after the guest reference compile, before
    the full distcc run, so one guest boot yields the whole split: the
    reference compile stays byte-comparable with the 02b baseline, and P is
    measured in the warm-cache state distcc itself will see. `preprocess_ms_
    cold` is therefore the first probe invocation in the round, not a cold
    page cache.

    Every failure writes `None` plus a short code and falls through: a broken
    probe must never cost the crossfire evidence.
    """
    probe_timeout = args.guest_command_timeout_s
    # An empty list means "profiled and clean"; `None` means "never profiled".
    round_result["perf_probe_errors"] = []

    # --- P: guest preprocess, three invocations, `/root/fixture.i` kept ----
    preprocess_samples: list = []
    for _ in range(3):
        rc, _text, elapsed = console.run(
            tool(f"{guest_compiler(args)} -E --target={GUEST_TRIPLE} -O1 "
                 f"{guest_fixture_path(args)} -o {GUEST_DOT_I}"),
            next_index(), probe_timeout)
        if rc == 0 and elapsed >= 0:
            preprocess_samples.append(elapsed)
        elif elapsed < 0:
            _record_probe_error(round_result, "preprocess_timeout")
        else:
            _record_probe_error(round_result, "preprocess_failed")
    if preprocess_samples:
        round_result["preprocess_ms_samples"] = preprocess_samples
        round_result["preprocess_ms_cold"] = preprocess_samples[0]
        # Warm = the repeats. With a single usable sample there is no warm
        # state to report, and inventing one would poison the ledger.
        round_result["preprocess_ms_warm"] = median_sample(preprocess_samples[1:])
        round_result["preprocess_ms"] = round_result["preprocess_ms_warm"]

    dot_i_bytes = _read_dot_i_bytes(console, tool, next_index) \
        if preprocess_samples else None
    round_result["dot_i_bytes"] = dot_i_bytes
    if dot_i_bytes is None:
        _record_probe_error(round_result, "dot_i_bytes_unknown")
    if not preprocess_samples:
        # Without a `.i` neither the assemble control nor the transport
        # probes have a payload to move; the full distcc run still happens.
        return

    # --- A: same work, preprocessed input (P + A vs local_compile_ms) ------
    assemble_samples: list = []
    for _ in range(2):
        rc, _text, elapsed = console.run(
            tool(f"clang -c --target={GUEST_TRIPLE} -O1 {GUEST_DOT_I} "
                 f"-o {GUEST_DOT_I_O}"),
            next_index(), probe_timeout)
        if rc == 0 and elapsed >= 0:
            assemble_samples.append(elapsed)
        elif elapsed < 0:
            _record_probe_error(round_result, "assemble_timeout")
        else:
            _record_probe_error(round_result, "assemble_failed")
    if assemble_samples:
        round_result["assemble_ms_samples"] = assemble_samples
        round_result["assemble_ms"] = median_sample(assemble_samples)

    # --- nc availability: ask before measuring ----------------------------
    # A guest without netcat would not fail fast: each direction would sit
    # out the full listener timeout (120 s apiece) and only then report. The
    # probe runs in the same two shells the transfer would use, because the
    # chroot and the outer guest are separate installs.
    rc, text, _ = console.run(
        tool("command -v nc >/dev/null 2>&1 && echo QA_NC=yes "
             "|| echo QA_NC=no"), next_index(), 30)
    guest_has_nc = rc == 0 and "QA_NC=yes" in (text or "")
    if not guest_has_nc and chroot_prefix:
        rc, text, _ = console.run(
            "command -v nc >/dev/null 2>&1 && echo QA_NC=yes "
            "|| echo QA_NC=no", next_index(), 30)
        guest_has_nc = rc == 0 and "QA_NC=yes" in (text or "")
    round_result["guest_has_nc"] = guest_has_nc
    if not guest_has_nc:
        _record_probe_error(round_result, "guest_nc_missing")
        return

    # --- T up / T down: the distcc topology, both directions ---------------
    up = _probe_transport_up(adb, console, tool, next_index, chroot_prefix,
                             args.perf_port_up, dot_i_bytes, round_work,
                             round_result)
    round_result["transport_up_ms"] = up["elapsed_ms"]
    round_result["transport_up_bytes"] = up["bytes"]
    down = _probe_transport_down(adb, console, tool, next_index,
                                 args.perf_port_down, chroot_prefix,
                                 round_result)
    round_result["transport_down_ms"] = down["elapsed_ms"]
    round_result["transport_down_bytes"] = down["bytes"]
    # Size pair: 8 KiB on the next port discriminates a stall (both slow) from
    # bandwidth (only the big one slow). Cheap — a healthy link moves it in ms.
    down_small = _probe_transport_down(adb, console, tool, next_index,
                                       args.perf_port_down + 1, chroot_prefix,
                                       round_result, size_kib=8,
                                       label="download_small")
    round_result["transport_down_small_ms"] = down_small["elapsed_ms"]
    round_result["transport_down_small_bytes"] = down_small["bytes"]

    # A half-measured transport is not a transport figure: leave it `None`.
    if round_result["transport_up_ms"] is not None \
            and round_result["transport_down_ms"] is not None:
        round_result["transport_ms"] = round_result["transport_up_ms"] \
            + round_result["transport_down_ms"]
    if round_result["transport_up_bytes"] is not None \
            and round_result["transport_down_bytes"] is not None:
        round_result["transport_bytes"] = round_result["transport_up_bytes"] \
            + round_result["transport_down_bytes"]


def read_evidence(adb: Adb, package: str, name: str) -> dict | None:
    return adb.read_evidence(package, name)


def wait_for_crossfire_evidence(adb: Adb, package: str, evidence_name: str,
                                process: subprocess.Popen, deadline_s: float) -> dict:
    """Poll until bridge_ready (or a terminal failure) shows up."""
    started = time.monotonic()
    last: dict = {}
    while time.monotonic() - started < deadline_s:
        if process.poll() is not None:
            evidence = read_evidence(adb, package, evidence_name)
            if evidence:
                return evidence
        evidence = read_evidence(adb, package, evidence_name)
        if evidence:
            last = evidence
            if evidence.get("terminal_socket") or evidence.get("failure_code") \
                    or evidence.get("stage") in ("done", "rejected", "gate_fallback"):
                return evidence
        time.sleep(1.0)
    return last


def wait_instrumentation_exit(process: subprocess.Popen, log_path: Path,
                              timeout_s: float) -> str:
    try:
        process.wait(timeout=timeout_s)
    except subprocess.TimeoutExpired:
        process.kill()
        log_path.write_text("killed after backstop timeout", encoding="utf-8")
        return "killed"
    log = process.stdout.read() if process.stdout else ""
    log_path.write_text(log, encoding="utf-8", errors="replace")
    return "exited"


def run_smoke(adb: Adb, args, summary: dict) -> int:
    """Phase smoke: capture the product wiring's own accelerator lines."""
    adb.run("shell", "logcat", "-c")
    resolved = adb.run("shell", "cmd", "package", "resolve-activity", "--brief",
                       args.qa_package, check=False).strip().splitlines()
    component = resolved[-1].strip() if resolved else ""
    if "/" not in component:
        summary["failure_code"] = "MAIN_ACTIVITY_NOT_RESOLVED"
        return 1
    # dispatch lives in launchInstance, not onCreate: the product launch is
    # driven through the instance-launch extra MainActivity handles in both
    # onCreate and onNewIntent (action_launch_instance_id).
    start_cmd = ["shell", "am", "start", "-n", component]
    if args.instance_id:
        start_cmd += ["--es", "action_launch_instance_id", args.instance_id]
    adb.run(*start_cmd)
    started = time.monotonic()
    decision, payload_line, events = None, None, []
    # Logcat lines carry the standard header prefix, so the event text is
    # matched anywhere in the line, not anchored.
    pattern = re.compile(
        r"(lane_started|lane_ready|lane_stopped|lane_lost|budget_rejected)\s+"
        r"(QEMU|ACCEL)\s+(\S+)\s+(\d+)ms")
    while time.monotonic() - started < args.smoke_deadline_s:
        text = adb.run("shell", "logcat", "-d", "-s",
                       "OrchestellaAccelerator:I", check=False)
        if decision is None:
            for line in text.splitlines():
                match = re.search(r"backend=(\S+) (\{.*\})\s*$", line)
                if match:
                    decision = {"backend": match.group(1), "json": match.group(2)}
                elif "payload-compiler present=" in line:
                    payload_line = line.strip()
        if text:
            for match in pattern.finditer(text):
                record = {"name": match.group(1), "lane": match.group(2),
                          "code": match.group(3), "duration_ms": int(match.group(4))}
                if record not in events:
                    events.append(record)
        if decision is not None and payload_line is not None:
            break
        time.sleep(2.0)
    summary["smoke"] = {
        "decision_backend": decision["backend"] if decision else None,
        "decision_json": decision["json"][:2000] if decision else None,
        "payload_line": payload_line,
        "lane_events": events,
    }
    if decision:
        try:
            parsed = json.loads(decision["json"])
            summary["smoke"]["decision_mode"] = parsed.get("mode")
            summary["smoke"]["decision_reason_code"] = parsed.get("reasonCode")
        except json.JSONDecodeError:
            summary["smoke"]["decision_json_parse"] = "failed"
        return 0
    summary["failure_code"] = "DECISION_LINE_TIMEOUT"
    return 1


def link_and_run(console: GuestConsole, args, chroot_prefix: str, next_index,
                 tool) -> dict:
    """Link both objects against a C main and run both binaries.

    C-only: keeping it in one function is what lets the C++ mode skip it
    without threading a flag through the round body.
    """
    console.run(
        tool(f"printf 'int fixture_entry(void);\\nint main(void) "
             f"{{ return fixture_entry(); }}\\n' > {GUEST_MAIN_C}"),
        next_index(), args.guest_command_timeout_s)
    # The link runs OUTSIDE the chroot with the native toolchain: the chroot
    # lacks binutils and its apk mirror leg is the flakiest path, while the
    # link step is tool-version-agnostic for the crossfire evidence (the .o
    # equivalence is compare_objects' job).
    rc, link_text, _ = console.run(
        f"clang -O1 {chroot_prefix}{GUEST_MAIN_C} "
        f"{chroot_prefix}{GUEST_CROSS_O} -o {chroot_prefix}/root/cross.bin",
        next_index(), args.guest_command_timeout_s)
    cross_link_rc = rc
    rc_ref, ref_link_text, _ = console.run(
        f"clang -O1 {chroot_prefix}{GUEST_MAIN_C} "
        f"{chroot_prefix}{GUEST_REF_O} -o {chroot_prefix}/root/ref.bin",
        next_index(), args.guest_command_timeout_s)
    _, cross_out, _ = console.run(
        tool('/root/cross.bin; printf "exit=%d\\n" "$?"'),
        next_index(), 60)
    _, ref_out, _ = console.run(
        tool('/root/ref.bin; printf "exit=%d\\n" "$?"'),
        next_index(), 60)
    cross_exit = re.search(r"exit=(\d+)", cross_out)
    ref_exit = re.search(r"exit=(\d+)", ref_out)
    return {
        "cross_link_rc": cross_link_rc, "ref_link_rc": rc_ref,
        "cross_link_tail": link_text.strip().splitlines()[-3:],
        "ref_link_tail": ref_link_text.strip().splitlines()[-3:],
        "cross_exit": cross_exit.group(1) if cross_exit else None,
        "ref_exit": ref_exit.group(1) if ref_exit else None,
        "exit_codes_match": bool(cross_exit and ref_exit
                                 and cross_exit.group(1) == ref_exit.group(1)),
    }


def crossfire_round(adb: Adb, args, root: Path, work: Path, round_index: int,
                    fixture_bytes: bytes) -> dict:
    """One full crossfire round: instrument, guest session, equivalence."""
    # The AXP1 handshake token MUST equal the accel_token asset baked into
    # the QA APK: a random token makes the payload reject the bridge's
    # handshake in milliseconds (CHANNEL_FAILED) and distcc silently falls
    # back to local compilation, which looks like success but is not.
    token = (args.token or "").strip()
    if len(token) != 32 or any(c not in ("0123456789" + "abcdef") for c in token):
        raise RuntimeError("--token must be 32 lowercase hex characters "
                           "(the accel_token asset baked into --apk build)")
    evidence_name = f"pvm-compiler-{token}.json"
    round_work = work / f"round-{round_index}"
    round_work.mkdir(parents=True, exist_ok=True)
    round_result: dict = {"round": round_index, "token_sha256": __import__(
        "hashlib").sha256(token.encode("utf-8")).hexdigest(),
        **blank_segment_fields()}

    adb.run("shell", "am", "force-stop", args.qa_package, check=False)
    adb.run("shell", "run-as", args.qa_package, "rm", "-f",
            f"{EVIDENCE_DIR}/{evidence_name}", check=False)
    # The guest dials a fixed contract port; stayon keeps the device awake
    # through the long guest boot + apk add phase.
    adb.run("shell", "svc", "power", "stayon", "usb", check=False)
    adb.run("shell", "am", "set-standby-bucket", args.qa_package, "active",
            check=False)
    adb.run("shell", "cmd", "appops", "set", args.qa_package,
            "RUN_ANY_IN_BACKGROUND", "allow", check=False)
    adb.run("shell", "dumpsys", "deviceidle", "whitelist", "+" + args.qa_package,
            check=False)
    adb.run("push", str(work / "fixture.c"), DEVICE_FIXTURE_PUSH,
            check=False)

    # One extra slack job keeps the bridge wait loop alive while the console
    # session returns the object; the guest runs it after the pull.
    instrument_jobs = args.jobs + 1
    instrument = adb._base() + [
        "shell", "am", "instrument", "-w", "-r",
        "-e", "qaMode", "compiler-crossfire",
        "-e", "qaRunToken", token,
        "-e", "qaPayloadName", args.payload,
        "-e", "qaMemoryMb", str(args.memory_mb),
        "-e", "qaVsockPort", str(args.vsock_port),
        "-e", "bridge_port", str(args.bridge_port),
        "-e", "readyDeadlineMs", str(args.ready_deadline_ms),
        "-e", "qaReadyDeadlineMs", str(args.payload_ready_deadline_ms),
        "-e", "qaJobDeadlineMs", str(args.job_deadline_ms),
        "-e", "qaJobs", str(instrument_jobs),
        # Direct-QEMU: the product Session owns the terminal socket for its
        # lifetime, which starves this driver's console; the direct path
        # boots the same volume without a console client on the QEMU side.
        "-e", "qaDirectQemu", "1",
    ]
    if args.instance_id:
        instrument += ["-e", "qaInstanceId", args.instance_id]
    instrument += [f"{args.qa_package}.test/{INSTRUMENTATION}"]
    process = subprocess.Popen(instrument, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True,
                               encoding="utf-8", errors="replace")
    # HyperOS freezes a fresh uid seconds after the VM starts; foregrounding
    # early protects the QEMU boot, again at bridge_ready covers the compile.
    time.sleep(2.0)
    foreground_qa_app(adb, args.qa_package)

    evidence = wait_for_crossfire_evidence(
        adb, args.qa_package, evidence_name, process,
        (args.ready_deadline_ms + args.payload_ready_deadline_ms) / 1000.0 + 60)
    round_result["await_accelerator_ready"] = evidence.get("await_accelerator_ready")
    round_result["gate_ms"] = evidence.get("gate_ms")
    round_result["fallback_count"] = evidence.get("fallback_count")
    round_result["bridge_port"] = evidence.get("bridge_port")
    round_result["payload_ready_ms"] = evidence.get("payload_ready_ms")
    round_result["instance_id"] = evidence.get("instance_id")
    round_result["instrumentation"] = {
        "stage": evidence.get("stage"),
        "failure_code": evidence.get("failure_code"),
        "notes": evidence.get("notes"),
        "start_report": evidence.get("start_report"),
    }
    if not evidence.get("bridge_port") \
            or evidence.get("bridge_port") != args.bridge_port:
        round_result["failure_code"] = evidence.get("failure_code") \
            or "BRIDGE_PORT_NEVER_PUBLISHED"
        wait_instrumentation_exit(process, round_work / "instrument.log",
                                  300)
        return round_result
    if evidence.get("await_accelerator_ready") is False:
        round_result["failure_code"] = evidence.get("failure_code") \
            or "ACCEL_GATE_TIMEOUT_FALLBACK"
        wait_instrumentation_exit(process, round_work / "instrument.log", 300)
        return round_result
    foreground_qa_app(adb, args.qa_package)

    terminal_socket = evidence.get("terminal_socket") \
        or (TERMINAL_SOCKET_PREFIX + evidence.get("instance_id", ""))
    round_result["terminal_socket"] = terminal_socket
    console = None
    index = 0
    # The distcc client's own output, kept for the remote-compile verdict below:
    # its absence must read as "no evidence", not as an empty success.
    distcc_text = ""

    def next_index() -> str:
        nonlocal index
        index += 1
        return str(index)

    try:
        console = guest_connect(adb, terminal_socket)
        if not console.wait_prompt(args.guest_boot_timeout_s):
            round_result["failure_code"] = "GUEST_CONSOLE_TIMEOUT"
            return round_result
        rc, text, elapsed = console.run(
            "cat /etc/os-release 2>/dev/null | head -2", next_index(), 30)
        round_result["os_release"] = text.strip().splitlines()[-1:] \
            if rc == 0 else None
        # A prepared Alpine chroot (/alpine) pins the guest clang to the
        # payload's major (22.x): the Arch native toolchain rolled to 23 and
        # would trip the hard check below. When the chroot exists, tool
        # invocations run inside it and file paths shift by the prefix.
        rc, text, _ = console.run(
            "test -x /alpine/usr/bin/clang && echo CHROOT_CLANG=yes "
            "|| echo CHROOT_CLANG=no", next_index(), 30)
        chroot_active = "CHROOT_CLANG=yes" in text
        chroot_prefix = "/alpine" if chroot_active else ""
        round_result["guest_chroot"] = chroot_active
        if chroot_active:
            # The minirootfs chroot ships an empty /dev: clang's driver
            # spawns its subcommands through posix_spawn, which needs
            # /dev/null, and fails with "No such file or directory" without.
            console.run(
                "mount --bind /dev /alpine/dev 2>/dev/null; "
                "[ -e /alpine/dev/null ] || (mknod /alpine/dev/null c 1 3 "
                "&& chmod 666 /alpine/dev/null); "
                "mount -t proc proc /alpine/proc 2>/dev/null; true",
                next_index(), 30)

        def tool(command: str) -> str:
            """Run a toolchain command inside the chroot when one exists.

            The command travels base64-encoded so its own quoting (printf
            format strings, DISTCC_HOSTS) cannot collide with the wrapper.
            """
            if not chroot_active:
                return command
            encoded = base64.b64encode(command.encode()).decode()
            return f"chroot /alpine /bin/sh -c \"$(echo {encoded} | base64 -d)\""

        if chroot_active:
            # binutils is required for linking (clang execs ld through
            # posix_spawn; the minirootfs chroot does not carry it).
            install = "/sbin/apk add --no-cache distcc clang binutils"
        else:
            install = GUEST_INSTALL.get(
                str(evidence.get("guest_distro") or "").strip(),
                GUEST_INSTALL_DEFAULT)
        # Version check first: a prepared rootfs already carries the pinned
        # toolchain, and the package-manager round-trip is the flakiest leg
        # (cellular mirrors); only a missing/wrong toolchain needs it.
        rc, text, _ = console.run(tool("clang --version"), next_index(), 60)
        major = parse_clang_major(text)
        round_result["clang_major"] = major
        round_result["clang_version_line"] = text.strip().splitlines()[-2:-1]
        if major != REQUIRED_CLANG_MAJOR:
            round_result["guest_install_cmd"] = install
            rc, text, elapsed = console.run(tool(install), next_index(),
                                            args.apk_timeout_s)
            round_result["apk_add_rc"] = rc
            round_result["apk_add_ms"] = elapsed
            if rc != 0:
                round_result["failure_code"] = "GUEST_APK_ADD_FAILED"
                return round_result
            rc, text, _ = console.run(tool("clang --version"), next_index(), 60)
            major = parse_clang_major(text)
            round_result["clang_major"] = major
        if major != REQUIRED_CLANG_MAJOR:
            round_result["failure_code"] = "GUEST_CLANG_MAJOR_MISMATCH"
            return round_result

        fixture_path = guest_fixture_path(args)
        compiler = guest_compiler(args)
        inject = inject_fixture(adb, console, fixture_bytes,
                                args.guest_command_timeout_s, next_index,
                                chroot_prefix, fixture_path)
        round_result["fixture_injection"] = inject
        if not inject.get("injected"):
            round_result["failure_code"] = "FIXTURE_INJECTION_FAILED"
            return round_result

        # The guest-local reference uses the same -O1 as the payload path so
        # the equivalence compare sees one optimization level.
        rc, text, elapsed = console.run(
            tool(f"{compiler} -c --target={GUEST_TRIPLE} -O1 {fixture_path} "
                 f"-o {GUEST_REF_O}"),
            next_index(), args.guest_command_timeout_s)
        round_result["local_compile_rc"] = rc
        round_result["local_compile_ms"] = elapsed
        if rc != 0:
            round_result["failure_code"] = "GUEST_LOCAL_COMPILE_FAILED"
            return round_result

        # Perf attribution: P (guest preprocess) and T (gvproxy transport) in
        # this same console session, before the full distcc run. Opt-in, so
        # the 02b regression round keeps its exact behaviour and output. The
        # guard is deliberate: a probe that throws (adb vanished, port busy,
        # an unforeseen bug) is a missing measurement, never a failed round.
        if args.profile_segments:
            try:
                probe_segments(adb, console, args, tool, next_index,
                               chroot_prefix, round_work, round_result)
            except Exception as probe_failure:  # noqa: BLE001 - see above
                _record_probe_error(
                    round_result,
                    "probe_" + type(probe_failure).__name__.lower())

        # distcc must be the driver (a CC= prefix alone never wraps clang);
        # the segment split above is the only in-guest decomposition.
        distcc_command = (
            f'DISTCC_HOSTS="{GUEST_HOST_IP}:{args.bridge_port}" DISTCC_CMD_PREFIX="" '
            f"DISTCC_VERBOSE=1 distcc {compiler} -c --target={GUEST_TRIPLE} -O1 "
            f"{fixture_path} -o {GUEST_CROSS_O}")
        rc, text, elapsed = console.run(tool(distcc_command), next_index(),
                                        args.guest_command_timeout_s)
        round_result["distcc_rc"] = rc
        round_result["distcc_ms"] = elapsed
        round_result["distcc_argv"] = distcc_command
        distcc_text = text
        # The client's full words, next to instrument.log. `connections[]` says
        # whether bytes crossed; this says what distcc made of them -- the only
        # place a payload-side rejection (ARGV_REJECTED:*) becomes visible.
        (round_work / "distcc.log").write_text(text or "", encoding="utf-8",
                                              errors="replace")
        round_result["distcc_tail"] = (text or "").strip().splitlines()[-12:]
        if rc != 0:
            round_result["failure_code"] = "GUEST_DISTCC_COMPILE_FAILED"
            return round_result

        # Link + run is a C-only sub-criterion. A C++ binary would exercise the
        # guest's C++ runtime (libstdc++/libc++ packaging), not the payload;
        # what the payload owns is the object, and the object is pulled and
        # judged below either way.
        if compiler == "clang":
            round_result["link_run"] = link_and_run(
                console, args, chroot_prefix, next_index)
        else:
            round_result["link_run"] = {
                "skipped": "cxx fixture: object-level judgement only"}

        pulled_cross = return_object_via_netcat(
            adb, console, GUEST_CROSS_O, round_work / "cross.o",
            19999, tool, next_index, args.guest_command_timeout_s)
        if not pulled_cross.get("ok"):
            pulled_cross = return_object_via_console(
                console, f"{chroot_prefix}{GUEST_CROSS_O}",
                round_work / "cross.o",
                args.guest_command_timeout_s, next_index)
        round_result["cross_object"] = pulled_cross
        pulled_ref = return_object_via_netcat(
            adb, console, GUEST_REF_O, round_work / "ref.o",
            19999, tool, next_index, args.guest_command_timeout_s)
        if not pulled_ref.get("ok"):
            pulled_ref = return_object_via_console(
                console, f"{chroot_prefix}{GUEST_REF_O}",
                round_work / "ref.o",
                args.guest_command_timeout_s, next_index)
        round_result["ref_object"] = pulled_ref
        if not (pulled_cross.get("ok") and pulled_ref.get("ok")):
            round_result["failure_code"] = "OBJECT_RETURN_FAILED"
            return round_result

        # Slack job: closes the instrumentation's second connection, which
        # releases the coordinator teardown we are now blocking.
        console.run(
            tool("printf 'int slack_fn(int x) { return x + 1; }\\n' "
                 f"> {GUEST_SLACK_C} ; DISTCC_HOSTS=\"{GUEST_HOST_IP}:"
                 f"{args.bridge_port}\" distcc clang -c "
                 f"--target={GUEST_TRIPLE} -O1 {GUEST_SLACK_C} -o {GUEST_SLACK_O}"),
            next_index(), args.guest_command_timeout_s)

        if args.compare_script:
            compared_tail = ""
            try:
                compared = subprocess.run(
                    [sys.executable, args.compare_script,
                     "--a", str(round_work / "cross.o"),
                     "--b", str(round_work / "ref.o"),
                     "--fixtures", args.fixtures, "--no-run"],
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                    encoding="utf-8", errors="replace", timeout=300)
                lines = (compared.stdout or "").strip().splitlines()
            except subprocess.TimeoutExpired as compare_timeout:
                # A hung host linker (non-ASCII fixture paths are known to
                # stall mingw ld) must not lose the round evidence.
                tail = (compare_timeout.stdout or "")
                compared_tail = tail.strip().splitlines()[-2:] if tail else []
                lines = ["INCONCLUSIVE", "compare script timed out"]
            round_result["equivalence"] = {
                "verdict": lines[0] if lines else "INCONCLUSIVE",
                "detail": lines[1] if len(lines) > 1 else "",
                "tail": compared_tail,
            }
        return round_result
    except (OSError, RuntimeError) as session_failure:
        round_result["failure_code"] = round_result.get("failure_code") \
            or "GUEST_CONSOLE_TIMEOUT"
        round_result["session_error"] = type(session_failure).__name__
        return round_result
    finally:
        if console is not None:
            console.close()
        exit_state = wait_instrumentation_exit(
            process, round_work / "instrument.log",
            (args.job_deadline_ms / 1000.0) + 300)
        final = read_evidence(adb, args.qa_package, evidence_name) or {}
        round_result["instrumentation_exit"] = exit_state
        round_result["instrumentation_final"] = {
            "stage": final.get("stage"),
            "failure_code": final.get("failure_code"),
            "stop_confirmed": final.get("stop_confirmed"),
            "deleted": final.get("deleted"),
            "coordinator_stop": final.get("coordinator_stop"),
            "connections_completed": final.get("connections_completed"),
            "vm_error_code": final.get("vm_error_code"),
            # The array used to be dropped here while its length survived as
            # `connections_completed`. That array is the only place the bridge
            # says whether a response actually came back, so the next round of
            # attribution starts from it instead of from a device pull.
            "connections": final.get("connections"),
        }
        round_result["remote_compile"] = remote_compile_verdict(
            final, distcc_text, round_result.get("distcc_rc"))
        if round_result.get("failure_code") is None and final.get("failure_code"):
            round_result["failure_code"] = final.get("failure_code")
        if round_result.get("failure_code") is None and not final.get("stop_confirmed"):
            round_result["failure_code"] = "QA_STOP_UNCONFIRMED"


def _number(value):
    """A measured number, or None. `None` stays "not measured", never 0."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return value


def remote_compile_verdict(evidence: dict, distcc_text, distcc_rc) -> dict:
    """Did this round really compile on the payload?

    Two independent checks, neither of them a substring of the guest's log:
    the bridge's own connection record must show a whole distcc response coming
    back, and the client must not have fallen back to a local compile.

    `distcc_remote_seen` used to answer this with `"remote" in text.lower()`,
    which the fallback warning `remote compilation of '...' failed, retrying
    locally` matches -- the false positive that kept the 60 s floor looking
    like a working accelerator.

    Every field is a fixed code or a number; `None` means "no evidence", never
    0.
    """
    verdict = {
        "verdict": None,
        "reason": "NO_CONNECTION",
        "bytes_in": None,
        "bytes_out": None,
        "outcome": None,
        "failure_code": None,
        "truncated": None,
        "local_fallback": None,
        "payload_code": None,
    }
    connections = evidence.get("connections")
    if not isinstance(connections, list) or not connections:
        return verdict
    record = connections[-1] if isinstance(connections[-1], dict) else {}
    bytes_in = _number(record.get("bytes_in"))
    bytes_out = _number(record.get("bytes_out"))
    outcome = record.get("outcome")
    failure = record.get("failure_code")
    text = distcc_text or ""
    lowered = text.lower()
    truncated = any(marker.lower() in lowered for marker in TRUNCATION_MARKERS)
    local_fallback = any(marker.lower() in lowered for marker in LOCAL_FALLBACK_MARKERS)
    found = PAYLOAD_CODE_PATTERN.search(text)
    payload_code = found.group(1) if found else None
    verdict.update({
        "bytes_in": bytes_in,
        "bytes_out": bytes_out,
        "outcome": outcome,
        "failure_code": failure,
        "truncated": truncated,
        "local_fallback": local_fallback,
        "payload_code": payload_code,
    })
    if truncated:
        verdict.update({"verdict": False, "reason": "TRUNCATED"})
    elif payload_code == "COMPILE_ERROR":
        # The payload compiled and the source was wrong: the remote path did
        # work, the compile did not.
        verdict.update({"verdict": False, "reason": "COMPILE_ERROR"})
    elif payload_code is not None:
        verdict.update({"verdict": False, "reason": "PAYLOAD_REJECTED"})
    elif local_fallback:
        verdict.update({"verdict": False, "reason": "LOCAL_FALLBACK"})
    elif _number(distcc_rc) is None:
        # The client never reported an exit status: the round died before the
        # verdict could be read. Unknown is not the same as failed.
        verdict["reason"] = "NO_DISTCC_RC"
    elif _number(distcc_rc) != 0:
        verdict.update({"verdict": False, "reason": "DISTCC_FAILED"})
    elif bytes_out is None or bytes_out < MIN_DISTCC_RESPONSE_BYTES:
        verdict.update({"verdict": False, "reason": "NO_RESPONSE"})
    elif outcome != "COMPLETED" or failure not in (None, "NONE"):
        verdict.update({"verdict": False, "reason": "CHANNEL_FAILED"})
    else:
        verdict.update({"verdict": True, "reason": "REMOTE"})
    return verdict


def compute_ledger(distcc_ms, preprocess_ms, transport_ms,
                   local_compile_ms=None, assemble_ms=None) -> dict:
    """Close the 85 s gap as far as the measured segments allow.

    `unexplained_ms = distcc_ms - (preprocess_ms + transport_ms + 1.05 s)`:
    the payload constant is the 02a measurement, so whatever is left is distcc
    client overhead plus everything the probes cannot see from the host.
    `local_gap_ms = local_compile_ms - (preprocess_ms + assemble_ms)` is the
    calibration check: a full local compile should be about preprocess plus
    codegen, and a large gap means the split is not describing one workload.

    Any missing input yields `None`: an unmeasured ledger is not a zero one.
    """
    distcc, preprocess = _number(distcc_ms), _number(preprocess_ms)
    transport = _number(transport_ms)
    unexplained = None
    if None not in (distcc, preprocess, transport):
        unexplained = distcc - (preprocess + transport
                                + PAYLOAD_COMPILE_BASELINE_MS)
    local, assemble = _number(local_compile_ms), _number(assemble_ms)
    local_gap = None
    if None not in (preprocess, local, assemble):
        local_gap = local - (preprocess + assemble)
    return {"unexplained_ms": unexplained, "local_gap_ms": local_gap}


def summarize_segments(rounds, enabled: bool = True) -> dict:
    """Per-round segment lists plus medians; all `None` when profiling is off.

    The declared key set never changes, so a reader (and a test) can assert on
    the shape without special-casing the disabled run.
    """
    result = {name: None for name in SEGMENT_SUMMARY_FIELDS}
    if not enabled:
        return result

    def column(name: str) -> list:
        return [r.get(name) for r in rounds]

    result["preprocess_ms_cold"] = median_sample(column("preprocess_ms_cold"))
    result["preprocess_ms_warm"] = median_sample(column("preprocess_ms_warm"))
    result["preprocess_ms_p50"] = median_sample(column("preprocess_ms"))
    result["assemble_ms_p50"] = median_sample(column("assemble_ms"))
    result["transport_up_ms_p50"] = median_sample(column("transport_up_ms"))
    result["transport_down_ms_p50"] = median_sample(column("transport_down_ms"))
    result["transport_ms_p50"] = median_sample(column("transport_ms"))
    result["transport_bytes"] = median_sample(column("transport_bytes"))
    # Round-aligned raw values, `None` included: a missing round must stay
    # visible in the list rather than silently shortening it.
    result["segment_samples"] = {
        "preprocess_ms_cold": column("preprocess_ms_cold"),
        "preprocess_ms": column("preprocess_ms"),
        "assemble_ms": column("assemble_ms"),
        "transport_up_ms": column("transport_up_ms"),
        "transport_down_ms": column("transport_down_ms"),
        "transport_ms": column("transport_ms"),
    }
    result["ledger"] = {
        str(round_result.get("round", index)): compute_ledger(
            round_result.get("distcc_ms"),
            round_result.get("preprocess_ms"),
            round_result.get("transport_ms"),
            round_result.get("local_compile_ms"),
            round_result.get("assemble_ms"))
        for index, round_result in enumerate(rounds)
    }
    return result


def apply_segment_summary(summary: dict, rounds, enabled: bool) -> dict:
    """Merge the aggregation only when the probes actually ran.

    Disabled profiling must leave the summary key-for-key identical to the
    02b baseline, so the merge is conditional, not an all-`None` spray.
    """
    if not enabled:
        return summary
    summary.update(summarize_segments(rounds, True))
    return summary


def run_crossfire(adb: Adb, args, root: Path, work: Path, summary: dict) -> int:
    fixture_text = build_fixture(args.fixture_functions, args.language)
    if len(fixture_text.encode("utf-8")) >= MAX_FIXTURE_BYTES:
        summary["failure_code"] = "FIXTURE_TOO_LARGE"
        return 1
    (work / Path(guest_fixture_path(args)).name).write_text(
        fixture_text, encoding="utf-8")
    summary["fixture_functions"] = args.fixture_functions
    summary["fixture_language"] = args.language
    summary["fixture_bytes"] = len(fixture_text.encode("utf-8"))
    fixture_bytes = fixture_text.encode("utf-8")

    rounds = []
    for index in range(args.repeat):
        rounds.append(crossfire_round(adb, args, root, work, index, fixture_bytes))
        if rounds[-1].get("failure_code") and not args.keep_going:
            break
    summary["rounds"] = rounds
    summary["repeat"] = args.repeat

    distcc_times = [r["distcc_ms"] for r in rounds if isinstance(r.get("distcc_ms"), int)
                    and r["distcc_ms"] >= 0]
    gate_times = [r["gate_ms"] for r in rounds if isinstance(r.get("gate_ms"), int)]
    if distcc_times:
        summary["distcc_ms_p50"] = statistics.median(distcc_times)
        summary["distcc_ms_min"] = min(distcc_times)
        summary["distcc_ms_max"] = max(distcc_times)
    if gate_times:
        summary["gate_ms_p50"] = statistics.median(gate_times)
    apply_segment_summary(summary, rounds,
                          bool(getattr(args, "profile_segments", False)))
    failures = [r["failure_code"] for r in rounds if r.get("failure_code")]
    if failures:
        summary["failure_code"] = failures[0]
    return 0 if summary.get("failure_code") is None else 1


def run_cleanup(_adb: Adb, args, summary: dict) -> int:
    """Phase cleanup: command list only; the coordinator runs them."""
    guest_files = [
        f"rm -f {GUEST_FIXTURE} {GUEST_REF_O} {GUEST_CROSS_O} {GUEST_MAIN_C} "
        f"{GUEST_MAIN_O} {GUEST_SLACK_C} {GUEST_SLACK_O} /root/cross.bin "
        f"/root/ref.bin /root/fixture.b64",
    ]
    if getattr(args, "profile_segments", False):
        # The probes keep `/root/fixture.i` on purpose (the transport leg
        # reads it); only the attribution round leaves these behind.
        guest_files.append(f"rm -f {GUEST_DOT_I} {GUEST_DOT_I_O}")
    summary["cleanup_commands"] = {
        "device_power": ["adb shell svc power stayon true  # or restore the prior setting"],
        "host_forwards": ["adb forward --remove-all  # or remove each tcp:N this driver created"],
        "sdcard_fixture": [f"adb shell rm -f {DEVICE_FIXTURE_PUSH}"],
        "guest_files": guest_files,
    }
    return 0


def main(argv: list[str] | None = None) -> int:
    here = Path(__file__).resolve().parent
    root = here.parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True, help="path to platform-tools adb")
    parser.add_argument("--serial", default=None)
    parser.add_argument("--qa-package", default=DEFAULT_QA_PACKAGE)
    parser.add_argument("--payload", default="libpvm_compiler_payload.so")
    parser.add_argument("--alias", default="qa-alt-01")
    parser.add_argument("--phase", choices=("smoke", "crossfire", "cleanup", "all"),
                        default="all")
    parser.add_argument("--fixture-functions", type=int, default=2000)
    parser.add_argument("--language", choices=("c", "cxx"), default="c",
                        help="fixture language: c (default, the 02b baseline) or "
                             "cxx (same probes in extern \"C\" plus a try/catch; "
                             "link/run is skipped because a C++ binary would test "
                             "the guest runtime, not the payload)")
    parser.add_argument("--memory-mb", type=int, default=2048)
    parser.add_argument("--vsock-port", type=int, default=CONTRACT_DISTCC_PORT)
    parser.add_argument("--bridge-port", type=int, default=CONTRACT_DISTCC_PORT)
    parser.add_argument("--jobs", type=int, default=1,
                        help="distcc jobs the guest performs; the driver adds one "
                             "slack job so the bridge stays up during the pull")
    parser.add_argument("--ready-deadline-ms", type=int, default=120_000,
                        help="budget for the production gate awaitAcceleratorReady")
    parser.add_argument("--payload-ready-deadline-ms", type=int, default=180_000)
    parser.add_argument("--job-deadline-ms", type=int, default=600_000)
    parser.add_argument("--guest-boot-timeout-s", type=int, default=300)
    parser.add_argument("--guest-command-timeout-s", type=int, default=300)
    parser.add_argument("--apk-timeout-s", type=int, default=900,
                        help="apk add distcc clang budget on a cold package cache")
    parser.add_argument("--smoke-deadline-s", type=int, default=180)
    parser.add_argument("--repeat", type=int, default=1,
                        help="crossfire rounds; each round gets a fresh token")
    parser.add_argument("--token", default=None,
                        help="32 lowercase hex; must equal the accel_token "
                             "asset baked into the QA APK (AXP1 handshake)")
    parser.add_argument("--keep-going", action="store_true",
                        help="run every round even after a failure code")
    parser.add_argument("--profile-segments", action="store_true",
                        help="also measure guest preprocessing (P) and gvproxy "
                             "transport (T) in the same guest session, before "
                             "the full distcc run; off keeps the 02b round "
                             "byte-for-byte identical")
    parser.add_argument("--perf-port-up", type=int, default=PERF_PORT_UP_DEFAULT,
                        help="device-side loopback port for the upload probe "
                             "(guest -> device); 5103/19999/5037 are reserved")
    parser.add_argument("--perf-port-down", type=int, default=PERF_PORT_DOWN_DEFAULT,
                        help="device-side loopback port for the download probe "
                             "(device -> guest); 5103/19999/5037 are reserved")
    parser.add_argument("--instance-id", default=None,
                        help="QA Alpine instance id; default picks the first "
                             "stopped one in the app's instance store")
    parser.add_argument("--compare-script", default=str(
        root / "tools" / "compiler-equivalence" / "compare_objects.py"))
    parser.add_argument("--fixtures", default=str(
        root / "tools" / "compiler-equivalence" / "fixtures" / "c"))
    parser.add_argument("--work-dir", required=True,
                        help="ASCII scratch directory outside the repo")
    parser.add_argument("--output", required=True)
    parser.add_argument("--log-dir", required=True)
    args = parser.parse_args(argv)

    if args.jobs < 1 or args.repeat < 1:
        print("error=--jobs and --repeat must be >= 1", file=sys.stderr)
        return 2
    if args.bridge_port != CONTRACT_DISTCC_PORT:
        print(f"error=--bridge-port must be {CONTRACT_DISTCC_PORT}: the guest "
              "dials the contract port through gvproxy's hostIP mapping",
              file=sys.stderr)
        return 2
    if args.profile_segments:
        if args.perf_port_up == args.perf_port_down:
            print("error=--perf-port-up and --perf-port-down must differ: "
                  "both probes would bind the same device loopback port",
                  file=sys.stderr)
            return 2
        for flag, port in (("--perf-port-up", args.perf_port_up),
                           ("--perf-port-down", args.perf_port_down)):
            if not 1024 <= port <= 65535 or port in PERF_RESERVED_PORTS:
                reserved = ", ".join(str(p) for p in PERF_RESERVED_PORTS)
                print(f"error={flag} must be a free unprivileged port; "
                      f"{reserved} are already in use by this run",
                      file=sys.stderr)
                return 2

    work = Path(args.work_dir)
    work.mkdir(parents=True, exist_ok=True)
    Path(args.log_dir).mkdir(parents=True, exist_ok=True)

    adb = Adb(args.adb, args.serial)
    adb.discover()
    summary: dict = {
        "mode": "dv-pvm-02b-crossfire",
        "device_alias": args.alias,
        "phase": args.phase,
        "payload_name": args.payload,
        "guest_host_ip": GUEST_HOST_IP,
        "required_clang_major": REQUIRED_CLANG_MAJOR,
        "failure_code": None,
    }
    exit_code = 0
    if args.phase in ("smoke", "all"):
        exit_code |= run_smoke(adb, args, summary)
    if args.phase in ("crossfire", "all"):
        exit_code |= run_crossfire(adb, args, root, work, summary)
    if args.phase in ("cleanup", "all"):
        exit_code |= run_cleanup(adb, args, summary)

    Path(args.output).write_text(json.dumps(summary, indent=2, sort_keys=True),
                                 encoding="utf-8")
    headline = {
        "device_alias": summary["device_alias"],
        "failure_code": summary.get("failure_code"),
        "smoke_decision_backend": summary.get("smoke", {}).get("decision_backend"),
        "await_accelerator_ready": [
            r.get("await_accelerator_ready") for r in summary.get("rounds", [])],
        "gate_ms_p50": summary.get("gate_ms_p50"),
        "distcc_ms_p50": summary.get("distcc_ms_p50"),
        # The one line that says whether the remote path was actually taken.
        "remote_compile": [r.get("remote_compile") for r in summary.get("rounds", [])],
        "equivalence": [r.get("equivalence") for r in summary.get("rounds", [])],
    }
    if args.profile_segments:
        headline["segments"] = {
            "preprocess_ms_p50": summary.get("preprocess_ms_p50"),
            "transport_ms_p50": summary.get("transport_ms_p50"),
            "ledger": summary.get("ledger"),
        }
    print(json.dumps(headline, indent=2))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
