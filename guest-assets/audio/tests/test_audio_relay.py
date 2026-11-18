#!/usr/bin/env python3
"""Synthetic protocol peers on real local pipes/sockets; not a guest/PulseAudio test."""
import contextlib
import importlib.util
import json
import os
from pathlib import Path
import select
import socket
import struct
import subprocess
import sys
import time
import unittest
import stat
import threading
from types import SimpleNamespace
from unittest.mock import patch, Mock

MODULE_PATH = Path(__file__).resolve().parents[1] / "audio-relay.py"
spec = importlib.util.spec_from_file_location("orchestella_audio_relay", MODULE_PATH)
relay = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = relay
spec.loader.exec_module(relay)
TOKEN = b"0123456789abcdef" * 4
# Four s16le stereo frames at 48kHz (fixed contract payload, not a real capture).
PCM = bytes.fromhex("0000000000000000c350c3500000000051f451f400000000")


def read_exact(fd, size, timeout=3):
    data = bytearray()
    end = time.monotonic() + timeout
    while len(data) < size:
        ready, _, _ = select.select([fd], [], [], max(0, end - time.monotonic()))
        if not ready:
            raise AssertionError("timed out waiting for local wire data")
        part = os.read(fd, size - len(data))
        if not part:
            raise AssertionError("local wire ended before expected bytes")
        data.extend(part)
    return bytes(data)


def read_line(fd):
    result = bytearray()
    while len(result) < 256:
        result.extend(read_exact(fd, 1))
        if result[-1] == 10:
            return bytes(result)
    raise AssertionError("test response exceeded limit")


class Peer:
    def __init__(self, fail_connect=False):
        self.backend, child = socket.socketpair(socket.AF_UNIX, socket.SOCK_STREAM)
        command = [sys.executable, "-I", "-B", str(Path(__file__).resolve()), "--wire-peer", str(child.fileno())]
        if fail_connect:
            command.append("fail")
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, pass_fds=(child.fileno(),), close_fds=True)
        child.close()

    def send(self, data):
        self.process.stdin.write(data)
        self.process.stdin.flush()

    def line(self):
        return read_line(self.process.stdout.fileno())

    def close(self):
        if self.process.poll() is None:
            self.process.stdin.close()
            self.backend.close()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=3)
        self.backend.close()
        for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
            if not stream.closed:
                stream.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


class AuthenticatedWireTests(unittest.TestCase):
    def test_auth_error_recovers_then_forwards_pcm_without_side_channels(self):
        with Peer() as peer:
            peer.send(b"ORCHESTELLA/AUDIO 1 " + b"f" * 64 + b"\n")
            self.assertEqual(b"ORCHESTELLA/AUDIO ERROR auth\n", peer.line())
            self.assertFalse(select.select([peer.backend], [], [], 0)[0])
            peer.send(b"ORCHESTELLA/AUDIO 1 " + TOKEN + b"\n")
            self.assertEqual(b"ORCHESTELLA/AUDIO READY 1\n", peer.line())
            # Fixed synthetic PCM frames flow backend -> host; host input is relayed back.
            peer.backend.sendall(b"RIFF" + PCM)
            self.assertEqual(b"RIFF" + PCM, read_exact(peer.process.stdout.fileno(), 4 + len(PCM)))
            peer.send(PCM)
            self.assertEqual(PCM, read_exact(peer.backend.fileno(), len(PCM)))
            peer.backend.shutdown(socket.SHUT_WR)
            self.assertEqual(0, peer.process.wait(timeout=3))
            self.assertEqual(b"", peer.process.stderr.read())

    def test_malformed_and_oversized_lines_resynchronize_to_next_token(self):
        with Peer() as peer:
            peer.send(b"ORCHESTELLA/AUDIO 2 " + TOKEN + b"\n")
            self.assertEqual(b"ORCHESTELLA/AUDIO ERROR protocol\n", peer.line())
            peer.send(b"x" * 1024 + b"\n")
            self.assertEqual(b"ORCHESTELLA/AUDIO ERROR protocol\n", peer.line())
            peer.send(b"ORCHESTELLA/AUDIO 1 " + TOKEN.upper() + b"\n")
            self.assertEqual(b"ORCHESTELLA/AUDIO ERROR protocol\n", peer.line())
            peer.send(b"ORCHESTELLA/AUDIO 1 " + TOKEN + b"\n")
            self.assertEqual(b"ORCHESTELLA/AUDIO READY 1\n", peer.line())

    def test_backend_failure_never_sends_ready_and_leaks_no_exception_or_token(self):
        with Peer(fail_connect=True) as peer:
            for _ in range(2):
                peer.send(b"ORCHESTELLA/AUDIO 1 " + TOKEN + b"\n")
                self.assertEqual(b"ORCHESTELLA/AUDIO ERROR unavailable\n", peer.line())
            peer.process.stdin.close()
            self.assertEqual(0, peer.process.wait(timeout=3))
            self.assertEqual(b"", peer.process.stderr.read())


class StreamBoundaryTests(unittest.TestCase):
    def test_auth_reads_no_pcm_bytes_and_incomplete_preamble_has_deadline(self):
        host, server = socket.socketpair()
        try:
            host.sendall(relay.PREAMBLE + TOKEN + b"\n" + PCM)
            self.assertTrue(relay.authorize(server.fileno(), server.fileno(), TOKEN))
            self.assertEqual(PCM, os.read(server.fileno(), len(PCM)))
            host.sendall(b"ORCHESTELLA/AUDIO 1 partial")
            self.assertFalse(relay.authorize(server.fileno(), server.fileno(), TOKEN, timeout=0.02))
            self.assertEqual(b"ORCHESTELLA/AUDIO ERROR timeout\n", read_line(host.fileno()))
        finally:
            host.close()
            server.close()

    def test_auth_attempts_and_missing_newline_are_bounded(self):
        for payload, expected in ((relay.PREAMBLE + b"f" * 64 + b"\n", b"auth"),):
            host, server = socket.socketpair()
            try:
                host.sendall(payload * 64)
                self.assertFalse(relay.authorize(server.fileno(), server.fileno(), TOKEN))
                for _ in range(64):
                    self.assertEqual(b"ORCHESTELLA/AUDIO ERROR " + expected + b"\n", read_line(host.fileno()))
                self.assertEqual(b"ORCHESTELLA/AUDIO ERROR limit\n", read_line(host.fileno()))
            finally:
                host.close()
                server.close()
        host, server = socket.socketpair()
        try:
            host.sendall(b"x" * 4096)
            self.assertFalse(relay.authorize(server.fileno(), server.fileno(), TOKEN))
            self.assertEqual(b"ORCHESTELLA/AUDIO ERROR timeout\n", read_line(host.fileno()))
        finally:
            host.close()
            server.close()

    def test_backpressure_preserves_large_payload_with_bounded_buffers_and_observed_counts(self):
        host_read, host_input = os.pipe()
        host_output, host_write = os.pipe()
        backend, peer = socket.socketpair()
        results = []
        failures = []
        def run():
            try:
                results.append(relay.relay_bytes(host_read, host_write, backend, stall_timeout=3))
            except BaseException as failure:
                failures.append(failure)
        worker = threading.Thread(target=run)
        payload = bytes(range(256)) * 4096
        def produce():
            peer.sendall(payload)
            peer.shutdown(socket.SHUT_WR)
        producer = threading.Thread(target=produce)
        try:
            worker.start()
            producer.start()
            self.assertEqual(payload, read_exact(host_output, len(payload), timeout=5))
            producer.join(3)
            worker.join(3)
            self.assertFalse(producer.is_alive())
            self.assertFalse(worker.is_alive())
            self.assertEqual([], failures)
            sample = results[0]
            self.assertEqual(1_048_576, sample.backend_read_bytes)
            self.assertEqual(1_048_576, sample.host_written_bytes)
            self.assertEqual(0, sample.host_read_bytes)
            self.assertGreater(sample.maximum_buffer_bytes, 0)
            self.assertLessEqual(sample.maximum_buffer_bytes, 65536)
        finally:
            peer.close()
            backend.close()
            for fd in (host_read, host_input, host_output, host_write):
                os.close(fd)
            producer.join(3)
            worker.join(3)

    def test_closed_host_sink_fails_transport_without_background_resend(self):
        host_read, host_input = os.pipe()
        host_output, host_write = os.pipe()
        backend, peer = socket.socketpair()
        try:
            os.close(host_output)
            peer.sendall(b"real-local-byte-fixture")
            with self.assertRaises(BrokenPipeError):
                relay.relay_bytes(host_read, host_write, backend, stall_timeout=0.1)
        finally:
            peer.close()
            backend.close()
            for fd in (host_read, host_input, host_write):
                os.close(fd)


class PolicyBoundaryTests(unittest.TestCase):
    """Synthetic OS metadata tests: policy proof only, not real guest directory ownership."""
    def test_fixed_cli_rejects_path_overrides_transport_and_privileged_or_unknown_users(self):
        for args in (("--token-file", "/tmp/sentinel"), ("--device", "/tmp/sentinel"),
                     ("--transport", "vsock"), ("--transport", "tcp"), ("--backend-port", "9999")):
            with self.assertRaises(relay.Rejected):
                relay.main(args)
        for name in ("root", "nobody", "unknown-audio-sentinel", "../root"):
            with self.assertRaises(relay.Rejected):
                relay.normal_user(name)
        user = SimpleNamespace(pw_uid=1234, pw_gid=1234, pw_dir="/home/audio-worker", pw_shell="/bin/bash")
        with patch.object(relay.pwd, "getpwnam", return_value=user):
            self.assertIs(user, relay.normal_user("audio-worker"))

    def test_backend_connect_is_loopback_pinned_and_identity_checked(self):
        created = []
        connected = []
        def fake_socket(family, kind):
            created.append((family, kind))
            peer = Mock()
            peer.connect.side_effect = lambda address: connected.append(address)
            return peer
        with patch.object(relay.os, "geteuid", return_value=1234), \
             patch.object(relay.os, "getegid", return_value=1234), \
             patch.object(relay.os, "getgroups", return_value=[]), \
             patch.object(relay.socket, "socket", side_effect=fake_socket) as factory:
            backend = relay.connect_audio_backend()
            self.assertEqual([(socket.AF_INET, socket.SOCK_STREAM)], created)
        self.assertEqual([("127.0.0.1", 9104)], connected)
        backend.setblocking(False)
        backend.close.assert_not_called()
        factory.assert_called_once_with(socket.AF_INET, socket.SOCK_STREAM)

    def test_backend_connect_refuses_root_groups_or_foreign_identity(self):
        for euid, egid, groups in ((0, 1234, []), (1234, 0, []), (1234, 1234, [100])):
            with patch.object(relay.os, "geteuid", return_value=euid), \
                 patch.object(relay.os, "getegid", return_value=egid), \
                 patch.object(relay.os, "getgroups", return_value=groups), \
                 patch.object(relay.socket, "socket") as factory:
                with self.assertRaises(relay.Rejected):
                    relay.connect_audio_backend()
                factory.assert_not_called()

    def test_token_file_is_fixed_bounded_and_rejects_owner_mode_type_link_and_format(self):
        original_fstat = os.fstat
        cases = [({}, TOKEN + b"\n", True), ({"owner": 1000}, TOKEN, False),
                 ({"mode": stat.S_IFREG | 0o644}, TOKEN, False), ({"links": 2}, TOKEN, False),
                 ({"mode": stat.S_IFLNK | 0o600}, TOKEN, False), ({"size": 1_000_000}, TOKEN, False),
                 ({}, TOKEN.upper(), False), ({}, TOKEN + b"\n\n", False)]
        for changes, content, allowed in cases:
            descriptors = []
            token_fd = os.memfd_create("audio-token-fixture", os.MFD_CLOEXEC)
            os.write(token_fd, content)
            os.lseek(token_fd, 0, os.SEEK_SET)
            paths = []
            def opened(path, flags, **kwargs):
                paths.append(path)
                self.assertTrue(flags & os.O_NOFOLLOW)
                if path == "bridge.token":
                    descriptors.append(token_fd)
                    return token_fd
                fd = os.memfd_create("audio-dir-metadata-fixture", os.MFD_CLOEXEC)
                descriptors.append(fd)
                return fd
            def metadata(fd):
                if fd != token_fd:
                    return SimpleNamespace(st_mode=stat.S_IFDIR | 0o755, st_uid=0)
                actual = original_fstat(fd)
                return SimpleNamespace(st_mode=changes.get("mode", stat.S_IFREG | 0o600),
                                       st_uid=changes.get("owner", 0), st_nlink=changes.get("links", 1),
                                       st_size=changes.get("size", actual.st_size))
            try:
                with patch.object(relay.os, "open", side_effect=opened), patch.object(relay.os, "fstat", side_effect=metadata):
                    if allowed:
                        self.assertEqual(TOKEN, relay.read_token_file())
                    else:
                        with self.assertRaises(relay.Rejected):
                            relay.read_token_file()
                self.assertEqual(["/etc", "droidlayer", "bridge.token"], paths)
            finally:
                for fd in set(descriptors + [token_fd]):
                    with contextlib.suppress(OSError):
                        os.close(fd)

    def test_direct_audio_device_also_requires_verified_sysfs_name(self):
        from unittest.mock import mock_open
        for name, mode in ((b"orchestella.audio\n", 0o600), (b"other.control\n", 0o600), (b"orchestella.audio\n", 0o644)):
            descriptors = []
            def opened(path, flags, **kwargs):
                fd = os.memfd_create("audio-device-fixture", os.MFD_CLOEXEC)
                descriptors.append(fd)
                return fd
            def metadata(fd):
                return SimpleNamespace(st_uid=0, st_mode=(stat.S_IFCHR | mode if fd == descriptors[-1] and len(descriptors) == 3 else stat.S_IFDIR | 0o755),
                                       st_rdev=os.makedev(240, 2))
            with patch.object(relay.os, "open", side_effect=opened), patch.object(relay.os, "fstat", side_effect=metadata), \
                 patch.object(relay.os, "stat", return_value=SimpleNamespace(st_mode=stat.S_IFCHR | 0o600)), \
                 patch("builtins.open", mock_open(read_data=name)) as identity:
                if mode == 0o600 and name == b"orchestella.audio\n":
                    fd = relay.open_audio_device()
                    self.assertEqual(descriptors[-1], fd)
                    os.close(fd)
                    identity.assert_called_once_with("/sys/dev/char/240:2/name", "rb")
                else:
                    with self.assertRaises(relay.Rejected):
                        relay.open_audio_device()
            for fd in descriptors:
                with self.assertRaises(OSError):
                    os.fstat(fd)

    def test_virtio_alias_cannot_redirect_to_token_or_arbitrary_device(self):
        descriptors = []
        def opened(path, flags, **kwargs):
            fd = os.memfd_create("audio-device-dir-fixture", os.MFD_CLOEXEC)
            descriptors.append(fd)
            return fd
        for target in ("../bridge.token", "/etc/droidlayer/bridge.token", "../tty0"):
            with patch.object(relay.os, "open", side_effect=opened), \
                 patch.object(relay.os, "fstat", return_value=SimpleNamespace(st_uid=0, st_mode=stat.S_IFDIR | 0o755)), \
                 patch.object(relay.os, "stat", return_value=SimpleNamespace(st_mode=stat.S_IFLNK | 0o777)), \
                 patch.object(relay.os, "readlink", return_value=target):
                with self.assertRaisesRegex(relay.Rejected, "device-path"):
                    relay.open_audio_device()
        for fd in descriptors:
            with self.assertRaises(OSError):
                os.fstat(fd)


class NativePrivilegeTests(unittest.TestCase):
    def test_launcher_really_execs_without_root_groups_environment_or_control_fd(self):
        """Real Linux exec as existing nobody; command-boundary capture substitutes identity probe.

        Does NOT prove an installed guest PulseAudio endpoint (absent in this sandbox).
        """
        if os.geteuid() != 0:
            self.skipTest("native privilege-drop probe requires the existing root sandbox entry")
        unprivileged = relay.pwd.getpwnam("nobody")
        read_fd, write_fd = os.pipe()
        control_read, control_write = os.pipe()
        os.set_inheritable(control_read, True)
        actual_popen = subprocess.Popen
        captured = []
        def native_probe(command, **kwargs):
            captured.append((command, kwargs))
            code = ("import os,json; "
                    "closed=False; "
                    "\ntry: os.fstat(" + str(control_read) + ")\nexcept OSError: closed=True\n"
                    "os.write(" + str(write_fd) + ",json.dumps([os.geteuid(),os.getegid(),os.getgroups(),"
                    "closed,'AUDIO_FAKE_SECRET' in os.environ]).encode())")
            return actual_popen([sys.executable, "-I", "-B", "-c", code], **kwargs)
        trusted_source = SimpleNamespace(st_mode=stat.S_IFREG | 0o644, st_uid=0)
        try:
            with patch.object(relay.os, "stat", return_value=trusted_source), \
                 patch.object(relay.subprocess, "Popen", side_effect=native_probe), \
                 patch.dict(os.environ, {"AUDIO_FAKE_SECRET": "synthetic-not-a-real-credential"}):
                self.assertEqual(0, relay.launch_worker(unprivileged, read_fd, write_fd))
            os.close(write_fd)
            result = json.loads(os.read(read_fd, 512))
            self.assertEqual([65534, 65534, [], True, False], result)
            command, kwargs = captured[0]
            self.assertEqual([sys.executable, "-I", "-B"], command[:3])
            self.assertEqual(["--session", str(read_fd), str(write_fd), "nobody"], command[4:])
            self.assertNotIn(TOKEN.decode("ascii"), repr(command))
            self.assertEqual({read_fd, write_fd}, set(kwargs["pass_fds"]))
            self.assertEqual({"LANG": "C.UTF-8"}, kwargs["env"])
            self.assertTrue(kwargs["close_fds"])
        finally:
            for fd in (read_fd, write_fd, control_read, control_write):
                with contextlib.suppress(OSError):
                    os.close(fd)


if __name__ == "__main__" and "--wire-peer" in sys.argv:
    backend = socket.socket(fileno=int(sys.argv[2]))
    def start_session():
        def connect():
            if len(sys.argv) > 3:
                raise OSError(TOKEN.decode("ascii"))
            return backend
        return relay.serve_session(0, 1, connect)
    relay.serve_host(0, 1, TOKEN, start_session)
    sys.exit(0)

if __name__ == "__main__":
    unittest.main()
