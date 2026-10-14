#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Original authenticated private display relay (Python >= 3.9, Linux).

Public CLI: --user orchestella --transport virtio|vsock. No path overrides, TCP listeners,
shell commands, wtype, control protocol, framebuffer synthesis or GPU claims.

The root supervisor reads the fixed 0600 token and authenticates the dedicated host stream.
It launches a NEW Python interpreter as the normal user with empty supplementary groups.
Only display FDs cross exec; token/request memory, root environment and control FDs do not.
That worker checks/connects the fixed private wayvnc Unix socket before sending READY.
A service manager should restart the virtio supervisor with a delay after a completed or
failed RFB session; malformed/auth-rejected preambles recover on the same display FD.
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
import struct
import subprocess
import sys
import time

TOKEN_FILE = "/etc/droidlayer/bridge.token"
DISPLAY_DEVICE = "/dev/virtio-ports/orchestella.display"
VSOCK_PORT = 9103
MAX_LINE = 128
MAX_DRAIN = 4096
MAX_AUTH_ATTEMPTS = 64
MAX_BUFFER = 65536
CHUNK = 16384
AUTH_TIMEOUT = 10.0
WRITE_TIMEOUT = 10.0
READY = b"ORCHESTELLA/DISPLAY READY 1\n"
PREAMBLE = b"ORCHESTELLA/DISPLAY 1 "


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
        raise ValueError("unknown static display error")
    write_all(fd, b"ORCHESTELLA/DISPLAY ERROR " + code.encode("ascii") + b"\n")


def read_auth_line(fd, timeout=AUTH_TIMEOUT):
    """Never prefetch RFB bytes. Drain overlong lines only up to a fixed byte/time limit."""
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
    """One select loop, two <=64KiB buffers, partial writes, no retries after stream failure."""
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


def private_socket_path(user):
    return "/run/user/" + str(user.pw_uid) + "/orchestella/wayvnc.sock"


def connect_private_backend(user):
    """Pin checked runtime directories, verify socket ownership/mode and Linux peer credentials."""
    if os.geteuid() != user.pw_uid or os.getegid() != user.pw_gid or os.getgroups():
        raise Rejected("worker-identity")
    directories = []
    backend = None
    try:
        directories.append(_checked_directory("/run", 0))
        directories.append(_checked_directory("user", 0, dir_fd=directories[-1]))
        directories.append(_checked_directory(str(user.pw_uid), user.pw_uid, 0o700, directories[-1]))
        directories.append(_checked_directory("orchestella", user.pw_uid, 0o700, directories[-1]))
        info = os.stat("wayvnc.sock", dir_fd=directories[-1], follow_symlinks=False)
        if (not stat.S_ISSOCK(info.st_mode) or info.st_uid != user.pw_uid
                or stat.S_IMODE(info.st_mode) & 0o077 or info.st_nlink != 1):
            raise Rejected("socket-permissions")
        backend = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        backend.settimeout(5)
        backend.connect("/proc/self/fd/" + str(directories[-1]) + "/wayvnc.sock")
        _pid, uid, _gid = struct.unpack("3i", backend.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
        if uid != user.pw_uid:
            raise Rejected("socket-peer")
        backend.setblocking(False)
        return backend
    except BaseException:
        if backend is not None:
            backend.close()
        raise
    finally:
        for fd in reversed(directories):
            os.close(fd)


def open_display_device():
    """Accept the fixed virtio alias (normally a udev symlink) only to a named vport character device."""
    dev = _checked_directory("/dev", 0)
    ports = None
    result = None
    try:
        ports = _checked_directory("virtio-ports", 0, dir_fd=dev)
        info = os.stat("orchestella.display", dir_fd=ports, follow_symlinks=False)
        if stat.S_ISLNK(info.st_mode):
            target = os.readlink("orchestella.display", dir_fd=ports)
            resolved = os.path.normpath(os.path.join("/dev/virtio-ports", target))
            if not re.fullmatch(r"/dev/vport[0-9]+p[0-9]+", resolved):
                raise Rejected("device-path")
            name = os.path.basename(resolved)
            # sysfs is kernel-owned; check that the fixed alias really names the dedicated display port.
            with open("/sys/class/virtio-ports/" + name + "/name", "rb") as identity:
                if identity.read(128) != b"orchestella.display\n":
                    raise Rejected("device-name")
            result = os.open(name, os.O_RDWR | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK | os.O_NOCTTY, dir_fd=dev)
        else:
            result = os.open("orchestella.display", os.O_RDWR | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK | os.O_NOCTTY, dir_fd=ports)
        info = os.fstat(result)
        if not stat.S_ISCHR(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o600:
            raise Rejected("device-permissions")
        major, minor = os.major(info.st_rdev), os.minor(info.st_rdev)
        with open("/sys/dev/char/" + str(major) + ":" + str(minor) + "/name", "rb") as identity:
            if identity.read(128) != b"orchestella.display\n":
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


def create_vsock_listener():
    """A successful bind/listen is a local transport probe, not an AVF display capability result."""
    if not hasattr(socket, "AF_VSOCK"):
        raise Rejected("vsock-unavailable")
    listener = socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM)
    try:
        listener.bind((socket.VMADDR_CID_ANY, VSOCK_PORT))
        listener.listen(1)
        return listener
    except BaseException:
        listener.close()
        raise


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
    parser = _Arguments(description="Private authenticated display relay; CPU-copy bytes, capture unverified.")
    parser.add_argument("--user", default="orchestella")
    parser.add_argument("--transport", choices=("virtio", "vsock"), default="virtio")
    parser.add_argument("--session", nargs=3, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    if args.session is not None:
        read_fd, write_fd = int(args.session[0]), int(args.session[1])
        if read_fd < 3 or write_fd < 3:
            raise Rejected("worker-descriptors")
        user = normal_user(args.session[2])
        return 0 if serve_session(read_fd, write_fd, lambda: connect_private_backend(user)) else 2
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

    if args.transport == "virtio":
        fd = open_display_device()
        try:
            return supervisor(fd, fd)
        finally:
            os.close(fd)
    with create_vsock_listener() as listener:
        while True:
            host, address = listener.accept()
            with host:
                if address[0] != socket.VMADDR_CID_HOST:
                    continue
                host.setblocking(False)
                supervisor(host.fileno(), host.fileno())


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, Rejected, ValueError):
        print("display relay refused or transport failed; capability unverified", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(0)
