#!/usr/bin/env python3
"""Actual Linux probes. Invoked only as the dedicated guest user/podman unshare."""
import ctypes
import json
import os
from pathlib import Path
import pwd
import secrets
import shutil
import signal
import subprocess
import sys
import time


def seccomp():
    class Filter(ctypes.Structure):
        _fields_ = [("code", ctypes.c_ushort), ("jt", ctypes.c_ubyte), ("jf", ctypes.c_ubyte), ("k", ctypes.c_uint)]
    class Program(ctypes.Structure):
        _fields_ = [("length", ctypes.c_ushort), ("filter", ctypes.POINTER(Filter))]
    libc = ctypes.CDLL(None, use_errno=True)
    instruction = Filter(0x06, 0, 0, 0x7FFF0000)  # BPF_RET | BPF_K, SECCOMP_RET_ALLOW.
    program = Program(1, ctypes.pointer(instruction))
    if libc.prctl(38, 1, 0, 0, 0) or libc.prctl(22, 2, ctypes.byref(program), 0, 0) or libc.prctl(21, 0, 0, 0, 0) != 2:
        raise ValueError("seccomp-unavailable")
    return {"seccomp": True}


def cgroup():
    uid = os.getuid()
    controllers = Path("/sys/fs/cgroup/cgroup.controllers").read_text().split()
    delegated = Path("/sys/fs/cgroup/user.slice/user-%d.slice/user@%d.service" % (uid, uid))
    if not {"memory", "pids"}.issubset(controllers) or not os.access(delegated / "cgroup.procs", os.W_OK):
        raise ValueError("cgroup-delegation-unavailable")
    return {"cgroup2": True}


def mounted(path):
    return any(line.split()[4] == str(path) for line in Path("/proc/self/mountinfo").read_text().splitlines())


def overlay(uid):
    # Namespace UID 0 must map to the configured ordinary guest UID, never guest root.
    mapping = [line.split() for line in Path("/proc/self/uid_map").read_text().splitlines()]
    if os.getuid() != 0 or not any(parts == ["0", str(uid), "1"] for parts in mapping):
        raise ValueError("podman-userns-required")
    runtime = Path("/run/user/%d/orchestella" % uid)
    work = runtime / ("overlay-probe-" + secrets.token_hex(16))
    work.mkdir(mode=0o700)
    lower, upper, overlay_work, merged = [work / name for name in ("lower", "upper", "work", "merged")]
    for directory in (lower, upper, overlay_work, merged):
        directory.mkdir(mode=0o700)
    (lower / "probe").write_text("orchestella-overlay\n")
    options = "lowerdir=%s,upperdir=%s,workdir=%s" % (lower, upper, overlay_work)
    fuse = None
    backend = "native"
    try:
        result = subprocess.run(["/usr/bin/mount", "-t", "overlay", "overlay", "-o", options, str(merged)], stdin=subprocess.DEVNULL,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
        if result.returncode:
            backend = "fuse"
            fuse = subprocess.Popen(["/usr/bin/fuse-overlayfs", "-f", "-o", options, str(merged)], stdin=subprocess.DEVNULL,
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline and not mounted(merged) and fuse.poll() is None:
                time.sleep(0.05)
        if not mounted(merged) or (merged / "probe").read_text() != "orchestella-overlay\n":
            raise ValueError("overlay-unavailable")
        (merged / "write-probe").write_text("upper\n")
        if (upper / "write-probe").read_text() != "upper\n":
            raise ValueError("overlay-write-unavailable")
        return {"overlay": backend}
    finally:
        if mounted(merged):
            subprocess.run(["/usr/bin/umount", str(merged)], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
        if fuse and fuse.poll() is None:
            os.killpg(fuse.pid, signal.SIGTERM)
            try:
                fuse.wait(timeout=1)
            except subprocess.TimeoutExpired:
                os.killpg(fuse.pid, signal.SIGKILL)
                fuse.wait(timeout=1)
        # Only this generated guest probe directory; never delete a live mount.
        if not mounted(merged):
            shutil.rmtree(work)
        else:
            raise ValueError("overlay-cleanup-failed")


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("seccomp", "cgroup", "overlay") or not sys.argv[2].isdigit():
        return 1
    uid = int(sys.argv[2])
    if uid < 1000 or uid == 65534:
        return 1
    if sys.argv[1] != "overlay":
        user = pwd.getpwuid(uid)
        if os.getuid() != uid or os.geteuid() != uid or user.pw_gid <= 0 or 0 in os.getgroups():
            return 1
    result = overlay(uid) if sys.argv[1] == "overlay" else {"seccomp": seccomp, "cgroup": cgroup}[sys.argv[1]]()
    print(json.dumps(result, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        sys.exit(1)
