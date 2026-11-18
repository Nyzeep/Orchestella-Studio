#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Original authenticated private audio relay (Python >= 3.9, Linux).

Public CLI: --user orchestella. No path overrides, TCP listeners, shell commands,
volume/control protocol or playback synthesis.

The root supervisor reads the fixed 0600 token and authenticates the dedicated
host stream on the fixed virtio audio port. It launches a NEW Python interpreter
as the normal user with empty supplementary groups. Only audio FDs cross exec;
token/request memory, root environment and control FDs do not. That worker
connects the fixed loopback PulseAudio simple-protocol TCP endpoint before
sending READY. PCM flows PulseAudio -> host only.

NOTE (G-05): the audio data-plane closure (guest relay -> virtio-serial ->
host listener -> LocalSocketAudioSource) is code-complete and unit-tested only.
PCM playback has never been verified on a real device; this relay is not an
audited audio capability yet.
"""
import argparse
from dataclasses import dataclass
import hmac
import os
import pwd
import re
import select
import socket
import stat
import subprocess
import sys
import time

TOKEN_FILE = "/etc/droidlayer/bridge.token"
AUDIO_DEVICE = "/dev/virtio-ports/orchestella.audio"
AUDIO_DEVICE_NAME = b"orchestella.audio\n"
# Fixed in-guest PulseAudio simple-protocol endpoint (ADR-0018). Loopback only;
# there is no authentication on this protocol, so it must never be rebindable.
AUDIO_BACKEND_HOST = "127.0.0.1"
AUDIO_BACKEND_PORT = 9104
MAX_LINE = 128
MAX_DRAIN = 4096
MAX_AUTH_ATTEMPTS = 64
MAX_BUFFER = 65536
CHUNK = 16384
AUTH_TIMEOUT = 10.0
WRITE_TIMEOUT = 10.0
READY = b"ORCHESTELLA/AUDIO READY 1\n"
PREAMBLE = b"ORCHESTELLA/AUDIO 1 "


class Rejected(Exception):
    """A static classification only. Never attach token, request, paths or OS exceptions."""


def write_all(fd, data, timeout=WRITE_TIMEOUT):
    view = memoryview(data)
    end = time.monotonic() + timeout
    while view:
        remaining = end - time.monotonic()
        if remaining <= 0 or not select.select([], [fd], [], remaining)[1]:
            raise Rejected("write-timeout")
        try:
            count = os.write(fd, view)
        except BlockingIOError:
            continue
        if count <= 0:
            raise Rejected("write-ended")
        view = view[count:]


def write_error(fd, code):
    if code not in ("auth", "protocol", "timeout", "unavailable", "limit"):
        raise ValueError("unknown static audio error")
    write_all(fd, b"ORCHESTELLA/AUDIO ERROR " + code.encode("ascii") + b"\n")


def read_auth_line(fd, timeout=AUTH_TIMEOUT):
    """Never prefetch PCM bytes. Drain overlong lines only up to a fixed byte/time limit."""
    result = bytearray()
    end = time.monotonic() + timeout
    oversized = False
    for _ in range(MAX_DRAIN):
        remaining = end - time.monotonic()
        if remaining <= 0 or not select.select([fd], [], [], remaining)[0]:
            raise Rejected("auth-timeout")
        try:
            byte = os.read(fd, 1)
        except BlockingIOError:
            continue
        if not byte:
            return None
        if len(result) < MAX_LINE:
            result.extend(byte)
        else:
            oversized = True
        if byte == b"\n":
            return b"" if oversized else bytes(result)
    raise Rejected("auth-framing")


def authorize(read_fd, write_fd, token, timeout=AUTH_TIMEOUT):
    if not re.fullmatch(b"[0-9a-f]{64}", token):
        raise Rejected("token-format")
    for _ in range(MAX_AUTH_ATTEMPTS):
        try:
            line = read_auth_line(read_fd, timeout)
        except Rejected:
            write_error(write_fd, "timeout")
            return False
        if line is None:
            return False
        if len(line) != len(PREAMBLE) + 65 or not line.startswith(PREAMBLE) or not line.endswith(b"\n"):
            write_error(write_fd, "protocol")
            continue
        candidate = line[len(PREAMBLE):-1]
        if not re.fullmatch(b"[0-9a-f]{64}", candidate):
            write_error(write_fd, "protocol")
            continue
        if not hmac.compare_digest(token, candidate):
            write_error(write_fd, "auth")
            continue
        return True
    write_error(write_fd, "limit")
    return False


@dataclass(frozen=True)
class RelayStats:
    host_read_bytes: int
    backend_read_bytes: int
    backend_written_bytes: int
    host_written_bytes: int
    maximum_buffer_bytes: int


def relay_bytes(host_read_fd, host_write_fd, backend, stall_timeout=WRITE_TIMEOUT):
    """One select loop, two <=64KiB buffers, partial writes, no retries after stream failure.

    For audio the meaningful direction is backend -> host (PCM); host -> backend
    is kept for symmetric teardown so either end can signal closure.
    """
    backend_fd = backend.fileno()
    for fd in set((host_read_fd, host_write_fd, backend_fd)):
        os.set_blocking(fd, False)
    buffers = [bytearray(), bytearray()]
    sources = [host_read_fd, backend_fd]
    destinations = [backend_fd, host_write_fd]
    reads = [0, 0]
    writes = [0, 0]
    progress = [time.monotonic(), time.monotonic()]
    maximum = 0
    ended = False
    while True:
        if ended and not any(buffers):
            return RelayStats(reads[0], reads[1], writes[0], writes[1], maximum)
        now = time.monotonic()
        pending = [index for index in range(2) if buffers[index]]
        if any(now - progress[index] >= stall_timeout for index in pending):
            raise Rejected("stream-stalled")
        read_fds = [] if ended else [sources[index] for index in range(2) if len(buffers[index]) < MAX_BUFFER]
        write_fds = [destinations[index] for index in pending]
        wait = min((stall_timeout - (now - progress[index]) for index in pending), default=None)
        readable, writable, _ = select.select(read_fds, write_fds, [], wait)
        for index in range(2):
            if destinations[index] in writable and buffers[index]:
                try:
                    count = os.write(destinations[index], buffers[index])
                except BlockingIOError:
                    continue
                if count <= 0:
                    raise Rejected("stream-ended")
                del buffers[index][:count]
                writes[index] += count
                progress[index] = time.monotonic()
            if not ended and sources[index] in readable:
                try:
                    data = os.read(sources[index], min(CHUNK, MAX_BUFFER - len(buffers[index])))
                except BlockingIOError:
                    continue
                if not data:
                    ended = True
                    continue
                if not buffers[index]:
                    progress[index] = time.monotonic()
                buffers[index].extend(data)
                reads[index] += len(data)
                maximum = max(maximum, len(buffers[index]))


def serve_session(read_fd, write_fd, connect_backend):
    """Authorized session seam. Return False only if connect failed before READY."""
    try:
        backend = connect_backend()
    except (OSError, Rejected):
        write_error(write_fd, "unavailable")
        return False
    try:
        write_all(write_fd, READY)
        relay_bytes(read_fd, write_fd, backend)
        return True
    finally:
        backend.close()


def serve_host(read_fd, write_fd, token, start_session):
    """Root preamble loop. Callback performs isolated privilege drop/exec or a local test peer."""
    while authorize(read_fd, write_fd, token):
        if start_session():
            return True
    return False


def _checked_directory(path, owner, mode=None, dir_fd=None):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=dir_fd)
    try:
        info = os.fstat(fd)
        actual = stat.S_IMODE(info.st_mode)
        if info.st_uid != owner or (mode is not None and actual != mode) or (mode is None and actual & 0o022):
            raise Rejected("directory-permissions")
        return fd
    except BaseException:
        os.close(fd)
        raise


def read_token_file():
    """Only the verified existing guest-agent token path is read; never logged or passed to exec."""
    etc = _checked_directory("/etc", 0)
    config = None
    token_fd = None
    try:
        config = _checked_directory("droidlayer", 0, dir_fd=etc)
        token_fd = os.open("bridge.token", os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=config)
        info = os.fstat(token_fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_nlink != 1
                or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size not in (64, 65)):
            raise Rejected("token-permissions")
        token = os.read(token_fd, 66)
        if token.endswith(b"\n"):
            token = token[:-1]
        if not re.fullmatch(b"[0-9a-f]{64}", token):
            raise Rejected("token-format")
        return token
    finally:
        if token_fd is not None:
            os.close(token_fd)
        if config is not None:
            os.close(config)
        os.close(etc)


def normal_user(name):
    if not re.fullmatch(r"[a-z_][a-z0-9_-]{0,31}", name):
        raise Rejected("user")
    try:
        user = pwd.getpwnam(name)
    except KeyError:
        raise Rejected("user") from None
    if (user.pw_uid < 1000 or user.pw_gid == 0 or not user.pw_dir.startswith("/")
            or user.pw_shell in ("", "/bin/false", "/sbin/nologin", "/usr/sbin/nologin")):
        raise Rejected("user")
    return user


def connect_audio_backend():
    """Connect the fixed loopback PulseAudio simple-protocol endpoint as the ordinary user.

    No TCP peer-credential probe exists; the boundary is the loopback-only listener
    inside the guest network namespace plus this worker's unprivileged identity.
    """
    if os.geteuid() < 1000 or os.getegid() == 0 or os.getgroups():
        raise Rejected("worker-identity")
    backend = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        backend.settimeout(5)
        backend.connect((AUDIO_BACKEND_HOST, AUDIO_BACKEND_PORT))
        backend.setblocking(False)
        return backend
    except BaseException:
        backend.close()
        raise


def open_audio_device():
    """Accept the fixed virtio alias (normally a udev symlink) only to a named vport character device."""
    dev = _checked_directory("/dev", 0)
    ports = None
    result = None
    try:
        ports = _checked_directory("virtio-ports", 0, dir_fd=dev)
        info = os.stat("orchestella.audio", dir_fd=ports, follow_symlinks=False)
        if stat.S_ISLNK(info.st_mode):
            target = os.readlink("orchestella.audio", dir_fd=ports)
            resolved = os.path.normpath(os.path.join("/dev/virtio-ports", target))
            if not re.fullmatch(r"/dev/vport[0-9]+p[0-9]+", resolved):
                raise Rejected("device-path")
            name = os.path.basename(resolved)
            # sysfs is kernel-owned; check that the fixed alias really names the dedicated audio port.
            with open("/sys/class/virtio-ports/" + name + "/name", "rb") as identity:
                if identity.read(128) != AUDIO_DEVICE_NAME:
                    raise Rejected("device-name")
            result = os.open(name, os.O_RDWR | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK | os.O_NOCTTY, dir_fd=dev)
        else:
            result = os.open("orchestella.audio", os.O_RDWR | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK | os.O_NOCTTY, dir_fd=ports)
        info = os.fstat(result)
        if not stat.S_ISCHR(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o600:
            raise Rejected("device-permissions")
        major, minor = os.major(info.st_rdev), os.minor(info.st_rdev)
        with open("/sys/dev/char/" + str(major) + ":" + str(minor) + "/name", "rb") as identity:
            if identity.read(128) != AUDIO_DEVICE_NAME:
                raise Rejected("device-name")
        opened = result
        result = None
        return opened
    finally:
        if result is not None:
            os.close(result)
        if ports is not None:
            os.close(ports)
        os.close(dev)


def launch_worker(user, read_fd, write_fd):
    """Exec clears root token memory. No shell, user text, environment credentials or control FDs."""
    script = os.path.realpath(__file__)
    info = os.stat(script, follow_symlinks=False)
    if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) & 0o022:
        raise Rejected("worker-source-permissions")
    child = subprocess.Popen(
        [sys.executable, "-I", "-B", script, "--session", str(read_fd), str(write_fd), user.pw_name],
        pass_fds=tuple(set((read_fd, write_fd))), close_fds=True,
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        user=user.pw_uid, group=user.pw_gid, extra_groups=(), cwd="/", env={"LANG": "C.UTF-8"})
    try:
        return child.wait()
    finally:
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=3)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()


class _Arguments(argparse.ArgumentParser):
    def error(self, _message):
        raise Rejected("arguments")


def main(argv=None):
    parser = _Arguments(description="Private authenticated audio relay; CPU-copy PCM, playback unverified.")
    parser.add_argument("--user", default="orchestella")
    parser.add_argument("--session", nargs=3, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    if args.session is not None:
        read_fd, write_fd = int(args.session[0]), int(args.session[1])
        if read_fd < 3 or write_fd < 3:
            raise Rejected("worker-descriptors")
        user = normal_user(args.session[2])
        return 0 if serve_session(read_fd, write_fd, connect_audio_backend) else 2
    if os.geteuid() != 0:
        raise Rejected("supervisor-identity")
    user = normal_user(args.user)
    token = read_token_file()

    def supervisor(read_fd, write_fd):
        result = [0]
        def start():
            try:
                result[0] = launch_worker(user, read_fd, write_fd)
            except (OSError, Rejected):
                write_error(write_fd, "unavailable")
                return False
            return result[0] != 2
        serve_host(read_fd, write_fd, token, start)
        return result[0]

    fd = open_audio_device()
    try:
        return supervisor(fd, fd)
    finally:
        os.close(fd)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, Rejected, ValueError):
        print("audio relay refused or transport failed; PCM loopback unverified", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(0)
