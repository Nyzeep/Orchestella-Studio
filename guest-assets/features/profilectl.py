#!/usr/bin/env python3
"""Orchestella guest feature CLI, v1. Fixed commands; no host content transport."""
import argparse
from contextlib import contextmanager
import errno
import hashlib
import http.client
import ipaddress
import json
import math
import socket
import stat
import tarfile
import threading
import urllib.parse
import urllib.request
from http.cookies import SimpleCookie
import os
from pathlib import Path, PurePosixPath
import platform
import re
import secrets
import shutil
import signal
import subprocess
import sys
import time

PROFILES = ("desktop-labwc", "podman", "code-server", "docker-cli")
ACTIONS = ("probe", "install", "status", "start", "stop", "cancel", "confirm-frame", "resize", "expose", "unexpose")
PACKAGES = {
    "desktop-labwc": ("python3", "labwc", "wayvnc", "foot", "waybar", "fonts-dejavu-core", "fonts-noto-core", "dbus-user-session", "xwayland", "wayland-utils", "grim", "wlr-randr"),
    "podman": ("python3", "podman", "uidmap", "fuse-overlayfs", "slirp4netns", "passt", "netavark", "aardvark-dns", "dbus-user-session", "libseccomp2", "util-linux"),
    "code-server": ("python3", "ca-certificates", "libc6", "libstdc++6", "dbus-user-session"),
    "docker-cli": ("python3", "docker-cli"),
}
UNITS = {
    "desktop-labwc": ("orchestella-desktop-compositor.service", "orchestella-desktop-wayvnc.service", "orchestella-display-relay.service"),
    "code-server": ("orchestella-code-server.service", "orchestella-code-server-relay.service"),
    "podman": (), "docker-cli": (),
}
STATES = ("planned", "installing", "installed", "starting", "awaiting-frame", "ready", "failed", "cancelled")
STATE_DIR = "/var/lib/orchestella/features"
PAYLOAD = "/usr/local/lib/orchestella"
IDENTITY_FILE = "/etc/orchestella/instance-user"
MAX_LINE = 4096
MAX_OUTPUT = 65536


class Failure(Exception):
    def __init__(self, reason):
        super().__init__(reason)
        self.reason = reason


class Cancelled(Failure):
    def __init__(self):
        super().__init__("cancelled")


def atomic_json(path, value):
    atomic_bytes(path, (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode(), 0o600)


def atomic_bytes(path, value, mode=0o600):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.parent / ("." + path.name + "." + secrets.token_hex(8))
    try:
        with temporary.open("xb") as output:
            os.chmod(temporary, mode)
            output.write(value)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        if os.name == "posix":
            descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
    finally:
        temporary.unlink(missing_ok=True)


def read_json(path, default=None):
    try:
        # A single opened descriptor sees one atomic publication, never a stat/read race.
        with path.open("rb") as source:
            if os.fstat(source.fileno()).st_size > MAX_OUTPUT:
                raise Failure("oversized-state")
            data = source.read(MAX_OUTPUT + 1)
        if len(data) > MAX_OUTPUT:
            raise Failure("oversized-state")
        return json.loads(data.decode("utf-8"))
    except FileNotFoundError:
        return default
    except (ValueError, UnicodeError):
        raise Failure("corrupt-state") from None


def process_identity(pid):
    if not isinstance(pid, int) or pid <= 0:
        return None
    if os.name == "nt":
        import ctypes
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.restype = ctypes.c_void_p
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            return None
        try:
            code = ctypes.c_ulong()
            if not kernel.GetExitCodeProcess(ctypes.c_void_p(handle), ctypes.byref(code)) or code.value != 259:
                return None
            return str(pid)
        finally:
            kernel.CloseHandle(ctypes.c_void_p(handle))
    try:
        boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        fields = Path("/proc", str(pid), "stat").read_text().rsplit(")", 1)[1].split()
        if fields[0] == "Z":
            return None
        return boot + ":" + fields[19]
    except (OSError, IndexError):
        return None


class Controller:
    def __init__(self, options):
        self.root = Path(options.test_root).resolve() if options.test_root else Path("/")
        self.testing = bool(options.test_root)
        self.runner = options.test_runner
        self.job = None
        self.profile = options.profile
        self.options = options
        if self.testing:
            if not self.runner or not (self.root / ".orchestella-profile-test-root").is_file():
                raise Failure("invalid-test-root")
        elif self.runner:
            raise Failure("invalid-test-root")
        elif os.geteuid() != 0:
            raise Failure("root-required")
        self.directory = self.path(STATE_DIR)
        self.fixture = read_json(self.root / "fixture.json", {}) if self.testing else {}

    def path(self, guest):
        path = self.root / guest.lstrip("/") if self.testing else Path(guest)
        if self.testing and not path.resolve().is_relative_to(self.root):
            raise Failure("path-escape")
        return path

    def initial(self):
        return {"version": 1, "profile": self.profile, "state": "planned", "installed": False,
                "reason": "none", "phase": "none", "generation": "none", "job_id": "none",
                "capture": "unverified", "protocols": "unverified", "health": "unverified",
                "container_info": "unverified", "container_run": "unverified", "docker_daemon": "unverified",
                "renderer": "pixman" if self.profile == "desktop-labwc" else "none",
                "transport": "cpu-copy-rfb" if self.profile == "desktop-labwc" else "none",
                "units_version": 0, "updated_at": 0}

    def state(self):
        path = self.directory / (self.profile + ".json")
        if path.is_symlink() or path.exists() and not self.testing and (path.stat().st_uid != 0 or path.stat().st_mode & 0o077):
            raise Failure("unsafe-state")
        state = read_json(path, self.initial())
        if not isinstance(state, dict) or state.get("state") not in STATES or state.get("profile") != self.profile or not isinstance(state.get("installed"), bool):
            raise Failure("corrupt-state")
        return {**self.initial(), **state}

    def boot_id(self):
        return self.fixture.get("boot_id", "synthetic-boot") if self.testing else Path("/proc/sys/kernel/random/boot_id").read_text().strip()

    def save(self, **changes):
        if changes.get("state") in ("ready", "awaiting-frame"):
            changes["verified_boot"] = self.boot_id()
        with self.mutex():
            state = self.state()
            state.update(changes)
            state["updated_at"] = int(time.time())
            atomic_json(self.directory / (self.profile + ".json"), state)
        return state

    @contextmanager
    def mutex(self):
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.directory.is_symlink() or not self.testing and (self.directory.stat().st_uid != 0 or stat.S_IMODE(self.directory.stat().st_mode) != 0o700):
            raise Failure("unsafe-state-directory")
        lock = self.directory / "command.lock"
        if lock.is_symlink():
            raise Failure("unsafe-state-directory")
        for attempt in range(40):
            try:
                lock.mkdir(mode=0o700)
                try:
                    atomic_json(lock / "owner.json", {"pid": os.getpid(), "identity": process_identity(os.getpid())})
                except BaseException:
                    # Only the lock this call just created; owner I/O failed before yield.
                    (lock / "owner.json").unlink(missing_ok=True)
                    lock.rmdir()
                    raise
                break
            except FileExistsError:
                owner = read_json(lock / "owner.json", {})
                stale = owner.get("pid") and process_identity(owner["pid"]) != owner.get("identity")
                if not owner:
                    try:
                        stale = time.time() - lock.stat().st_mtime > 30
                    except FileNotFoundError:
                        stale = False
                if stale:
                    (lock / "owner.json").unlink(missing_ok=True)
                    try:
                        lock.rmdir()
                    except OSError:
                        pass
                time.sleep(0.025)
        else:
            raise Failure("busy")
        try:
            yield
        finally:
            (lock / "owner.json").unlink(missing_ok=True)
            lock.rmdir()

    def distro(self):
        try:
            lines = self.path("/etc/os-release").read_text(encoding="utf-8").splitlines()
        except OSError:
            raise Failure("unknown-distro") from None
        values = {}
        for line in lines:
            if "=" in line:
                key, value = line.split("=", 1)
                values[key] = value.strip().strip('"')
        arch = self.fixture.get("arch", platform.machine())
        if arch not in ("aarch64", "arm64"):
            raise Failure("unsupported-architecture")
        distro = (values.get("ID"), values.get("VERSION_ID"))
        supported = (("debian", "13"),) if self.profile in ("desktop-labwc", "docker-cli") else (("debian", "13"), ("ubuntu", "24.04"))
        if distro not in supported:
            raise Failure("unsupported-distro")
        return {"distro": distro[0], "distro_version": distro[1], "architecture": "arm64"}

    def public(self, state=None):
        result = dict(state or self.state())
        for key in ("pid", "process_identity", "operation", "config_sha256"):
            result.pop(key, None)
        verified = (result["state"] in ("awaiting-frame", "ready") and result.get("generation") == result.get("capture_generation") and result["capture"] == "verified" and result["protocols"] == "verified")
        result["input"] = {"keyboard": verified, "unicodeText": False, "pointer": verified}
        result["exposed"] = result.get("guest_relay", False) is True
        result["job_active"] = bool(self.live_job(self.job_owner()))
        result["job_active_verified"] = True
        result["authenticationConfigured"] = (self.profile == "code-server" and result["state"] in ("ready", "starting") and result["health"] == "authenticated-http" and result.get("authenticationConfigured", False))
        return result

    def inputs(self):
        path = self.root / "inputs.json" if self.testing and (self.root / "inputs.json").exists() else Path(__file__).with_name("inputs.json")
        if not self.testing and (path.is_symlink() or path.stat().st_uid != 0 or path.stat().st_mode & 0o022):
            raise Failure("unsafe-input-lock")
        value = read_json(path)
        if not isinstance(value, dict) or value.get("schema") != 1 or not isinstance(value.get("units_version"), int) or not 1 <= value["units_version"] <= 10000:
            raise Failure("invalid-input-lock")
        return value

    def check_cancel(self):
        if self.job and (self.directory / (self.job + ".cancel")).exists():
            raise Cancelled()

    def run(self, args, timeout=30, required=True, reason="command-failed", cancellable=True):
        if cancellable:
            self.check_cancel()
        command = [sys.executable, "-B", self.runner, str(self.root), *args] if self.testing else args
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   start_new_session=True, env={**os.environ, "LC_ALL": "C", "DEBIAN_FRONTEND": "noninteractive", "PYTHONDONTWRITEBYTECODE": "1"})
        tail = bytearray()
        deadline = time.monotonic() + timeout
        descriptor = process.stdout.fileno()
        eof = False
        try:
            os.set_blocking(descriptor, False)
            # Completion includes pipe EOF. An exited leader may leave descendants holding stdout.
            while process.poll() is None or not eof:
                if cancellable:
                    self.check_cancel()
                if time.monotonic() >= deadline:
                    raise Failure("command-timeout")
                if not eof:
                    try:
                        chunk = os.read(descriptor, 4096)
                    except BlockingIOError:
                        chunk = None
                    if chunk == b"":
                        eof = True
                    elif chunk:
                        tail.extend(chunk)
                        if len(tail) > MAX_OUTPUT:
                            del tail[:-MAX_OUTPUT]
                        continue
                time.sleep(0.025)
        except BaseException:
            self.terminate(process)
            raise
        finally:
            # No reader thread owns this buffer lock, so closing cannot wait for descendant EOF.
            process.stdout.close()
        output = bytes(tail).decode("utf-8", "replace")
        if process.returncode and required:
            lowered = output.lower()
            if any(word in lowered for word in ("no_pubkey", "not signed", "signature", "unauthenticated", "badsig")):
                reason = "package-signature-failed"
            elif "no space left" in lowered:
                reason = "disk-full"
            elif "unable to locate package" in lowered or "no installation candidate" in lowered:
                reason = "packages-unavailable"
            raise Failure(reason)
        return process.returncode, output

    @staticmethod
    def terminate(process):
        # start_new_session pins the group to this Popen PID even after its leader exits.
        if os.name == "posix":
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        elif process.poll() is None:
            process.terminate()
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            if os.name != "posix":
                process.kill()
        if os.name == "posix":
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        process.wait(timeout=1)

    @property
    def username(self):
        if hasattr(self, "_username"):
            return self._username
        marker = self.root / IDENTITY_FILE.lstrip("/") if self.testing else Path(IDENTITY_FILE)
        try:
            descriptor = os.open(marker, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        except FileNotFoundError:
            raise Failure("ordinary-user-action-required") from None
        except OSError:
            raise Failure("unsafe-instance-identity") from None
        try:
            info = os.fstat(descriptor)
            # A sentinel-marked synthetic root may model POSIX modes on a Windows mount.
            mode = self.fixture.get("identity_mode", stat.S_IMODE(info.st_mode)) if self.testing else stat.S_IMODE(info.st_mode)
            if not stat.S_ISREG(info.st_mode) or not 2 <= info.st_size <= 17 or mode != 0o600 or not self.testing and info.st_uid != 0:
                raise Failure("unsafe-instance-identity")
            contents = bytearray()
            while len(contents) <= 17:
                chunk = os.read(descriptor, 18 - len(contents))
                if not chunk:
                    break
                contents.extend(chunk)
            if len(contents) != info.st_size:
                raise Failure("unsafe-instance-identity")
            name = contents.decode("ascii")
        except UnicodeError:
            raise Failure("unsafe-instance-identity") from None
        finally:
            os.close(descriptor)
        if not re.fullmatch(r"[a-z_][a-z0-9_-]{1,15}\n?", name):
            raise Failure("unsafe-instance-identity")
        name = name.removesuffix("\n")
        if name == "root":
            raise Failure("ordinary-user-action-required")
        self._username = name
        return name

    @property
    def home(self):
        return "/home/" + self.username

    def user(self, create=False):
        # Account creation belongs to baseline provisioning. A profile never guesses another user.
        name = self.username
        if self.testing:
            entries = self.path("/etc/passwd").read_text().splitlines() if self.path("/etc/passwd").exists() else []
            entry = next((entry.split(":") for entry in entries if entry.startswith(name + ":")), None)
            if entry is None:
                raise Failure("ordinary-user-missing")
            if len(entry) != 7:
                raise Failure("unsafe-ordinary-user")
            uid, gid, home, shell = int(entry[2]), int(entry[3]), entry[5], entry[6]
        else:
            import pwd
            try:
                entry = pwd.getpwnam(name)
            except KeyError:
                raise Failure("ordinary-user-missing") from None
            uid, gid, home, shell = entry.pw_uid, entry.pw_gid, entry.pw_dir, entry.pw_shell
        groups = self.fixture.get("groups", [gid]) if self.testing else os.getgrouplist(name, gid)
        if uid < 1000 or uid == 65534 or gid <= 0 or gid == 65534 or 0 in groups or home != self.home or shell not in ("/bin/bash", "/bin/sh", "/usr/bin/bash"):
            raise Failure("unsafe-ordinary-user")
        return uid, gid

    def private_dir(self, guest, uid=0, gid=0):
        directory = self.path(guest)
        if directory.is_symlink():
            raise Failure("unsafe-directory")
        if not directory.exists():
            if guest.startswith(self.home + "/") and not directory.parent.exists():
                self.private_dir(str(PurePosixPath(guest).parent), uid, gid)
            directory.mkdir(parents=True, mode=0o700)
            if not self.testing:
                os.chown(directory, uid, gid)
        if not self.testing:
            info = directory.stat()
            if info.st_uid != uid or stat.S_IMODE(info.st_mode) != 0o700:
                raise Failure("unsafe-directory")
        return directory

    def config(self, guest, content):
        uid, gid = self.user()
        file = self.path(guest)
        if file.is_symlink():
            raise Failure("unsafe-config")
        if file.exists():
            return
        self.private_dir(str(PurePosixPath(guest).parent), uid, gid)
        try:
            descriptor = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            return
        with os.fdopen(descriptor, "wb") as output:
            output.write(content.encode())
            output.flush()
            os.fsync(output.fileno())
        if not self.testing:
            os.chown(file, uid, gid)

    def runtime(self):
        uid, gid = self.user()
        self.run(["systemctl", "start", "user-runtime-dir@%d.service" % uid], reason="runtime-dir-failed")
        runtime = self.path("/run/user/%d" % uid)
        if not runtime.exists() or runtime.is_symlink():
            raise Failure("unsafe-runtime-dir")
        if not self.testing and (runtime.stat().st_uid != uid or stat.S_IMODE(runtime.stat().st_mode) != 0o700):
            raise Failure("unsafe-runtime-dir")
        self.private_dir("/run/user/%d/orchestella" % uid, uid, gid)
        return uid

    def display_name(self):
        uid, _ = self.user()
        marker = self.path("/run/user/%d/orchestella/desktop-clients.json" % uid)
        if marker.is_symlink() or not marker.is_file():
            raise Failure("desktop-session-unavailable")
        display = read_json(marker, {}).get("display", "")
        if not isinstance(display, str) or not re.fullmatch(r"wayland-[0-9]+", display):
            raise Failure("desktop-session-unavailable")
        return display

    def as_user(self, args, timeout=30, required=True, reason="runtime-probe-failed"):
        uid, _ = self.user()
        display = self.display_name() if self.profile == "desktop-labwc" else "wayland-0"
        return self.run(["runuser", "--user", self.username, "--", "env", "HOME=" + self.home,
                         "XDG_RUNTIME_DIR=/run/user/%d" % uid, "WAYLAND_DISPLAY=" + display,
                         "DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/%d/bus" % uid, *args], timeout, required, reason)

    def installed_packages(self):
        versions = {}
        for package in PACKAGES[self.profile]:
            rc, output = self.run(["dpkg-query", "-W", "-f=${Status}\\t${Version}\\n", package], timeout=5, required=False)
            if rc or not output.startswith("install ok installed\t"):
                raise Failure("dependency-missing")
            versions[package] = output.split("\t", 1)[1].strip()
        if self.profile == "docker-cli":
            self.run(["dpkg", "--compare-versions", versions["docker-cli"], "ge", "26.1.5"], reason="package-version-unsupported")
        if self.profile == "desktop-labwc":
            for package, minimum in self.inputs()["desktop"]["minimum_versions"].items():
                self.run(["dpkg", "--compare-versions", versions[package], "ge", minimum], reason="package-version-unsupported")
        return versions

    def ensure_disk(self):
        minimum = {"desktop-labwc": 700 << 20, "podman": 600 << 20, "code-server": 1800 << 20, "docker-cli": 400 << 20}[self.profile]
        free = self.fixture.get("free_bytes", shutil.disk_usage(self.root).free) if self.testing else shutil.disk_usage("/").free
        if free < minimum:
            raise Failure("disk-full")

    def wait_baseline(self):
        self.save(phase="waiting-for-base", reason="base-not-ready")
        deadline = time.monotonic() + (self.fixture.get("base_wait_seconds", 2) if self.testing else 600)
        while time.monotonic() < deadline:
            self.check_cancel()
            path = self.path("/var/lib/orchestella/provision/state")
            values = {}
            if path.is_file() and not path.is_symlink() and path.stat().st_size <= 4096:
                for line in path.read_text().splitlines():
                    if "=" in line:
                        key, value = line.split("=", 1)
                        values[key] = value
            if values.get("status") == "failed":
                raise Failure("base-provision-failed")
            comm = self.path("/proc/1/comm")
            if values.get("version") == "2" and values.get("status") == "ready" and comm.is_file() and comm.read_text().strip() == "systemd" and not self.path("/run/orchestella-provision.lock").exists():
                for unit in ("dbus.service", "ssh.service"):
                    self.run(["systemctl", "is-active", "--quiet", unit], timeout=2, reason="base-service-unavailable")
                self.save(reason="none")
                return
            time.sleep(0.1)
        raise Failure("base-wait-timeout")

    def signed_packages(self):
        for directory in ("/etc/apt/sources.list", "/etc/apt/sources.list.d"):
            path = self.path(directory)
            files = [path] if path.is_file() else list(path.glob("*.list")) + list(path.glob("*.sources")) if path.is_dir() else []
            for file in files:
                if file.is_symlink():
                    raise Failure("unsafe-package-source")
                text = file.read_text().lower()
                if re.search(r"(?:trusted|allow-insecure|allow-weak|allow-downgrade-to-insecure)\s*(?:=|:)\s*(?:yes|true|1)", text):
                    raise Failure("unsafe-package-source")
        options = ["-o", "DPkg::Lock::Timeout=30", "-o", "Acquire::Retries=0", "-o", "Acquire::AllowInsecureRepositories=false", "-o", "Acquire::AllowDowngradeToInsecureRepositories=false",
                   "-o", "APT::Get::AllowUnauthenticated=false", "-o", "APT::Update::Error-Mode=any"]
        self.save(phase="package-index")
        self.run(["apt-get", "update", *options], timeout=300, reason="package-index-failed")
        self.save(phase="packages")
        rc, audit = self.run(["dpkg", "--audit"], timeout=10, required=False)
        if rc or audit.strip():
            self.run(["dpkg", "--configure", "--pending"], timeout=600, reason="package-recovery-failed")
        self.run(["apt-get", "--yes", "--no-install-recommends", *options, "install", *PACKAGES[self.profile]], timeout=1800, reason="package-install-failed")
        self.installed_packages()

    def unit(self, name, content):
        path = self.path("/etc/systemd/system/" + name)
        prefix = "# Orchestella owned unit schema="
        if path.is_symlink() or (path.exists() and not path.read_text().startswith(prefix)):
            raise Failure("unit-collision")
        version = self.inputs()["units_version"]
        atomic_bytes(path, (prefix + str(version) + "\n" + content).encode(), 0o644)

    def service_body(self, executable, extra="", user=True, dependency="", restart=False):
        uid, gid = self.user()
        return ("[Unit]\nDescription=Orchestella optional guest feature\nAfter=dbus.service user-runtime-dir@%d.service\n" % uid + dependency +
                "[Service]\nType=simple\n" + (("User=%s\nGroup=%d\n" % (self.username, gid)) if user else "") +
                "Environment=HOME=%s\nEnvironment=XDG_RUNTIME_DIR=/run/user/%d\n" % (self.home, uid) +
                "UMask=0077\n" + extra +
                "ExecStart=" + executable + ("\nRestart=always\nRestartSec=2\n" if restart else "\nRestart=no\n") + "KillMode=control-group\nTimeoutStopSec=5\nStandardOutput=null\nStandardError=null\n")

    def configure(self):
        uid, gid = self.user(create=True)
        self.private_dir(self.home + "/.config", uid, gid)
        if self.profile == "desktop-labwc":
            self.config(self.home + "/.config/labwc/rc.xml", '<?xml version="1.0"?><labwc_config><core><decoration>server</decoration></core><theme><name>Clearlooks</name></theme><keyboard><keybind key="W-Return"><action name="Execute" command="foot"/></keybind></keyboard></labwc_config>\n')
            self.config(self.home + "/.config/foot/foot.ini", "[main]\nfont=monospace:size=12\n")
            self.config(self.home + "/.config/waybar/config", '{"layer":"top","modules-left":["wlr/taskbar"],"modules-right":["clock"],"clock":{"format":"{:%H:%M}"}}\n')
            self.config(self.home + "/.config/orchestella/wayvnc.conf", "enable_auth=false\n")
            extra = "Environment=WLR_BACKENDS=headless\nEnvironment=WLR_RENDERER=pixman\nEnvironment=WLR_HEADLESS_OUTPUTS=1\n"
            self.unit(UNITS[self.profile][0], self.service_body("/usr/bin/dbus-run-session -- /usr/bin/labwc -C " + self.home + "/.config/labwc -S '/usr/bin/python3 -B /usr/local/lib/orchestella/feature-session.py desktop-apps'", extra))
            self.unit(UNITS[self.profile][1], self.service_body("/usr/bin/python3 -B /usr/local/lib/orchestella/feature-session.py wayvnc", dependency="Requires=orchestella-desktop-compositor.service\nAfter=orchestella-desktop-compositor.service\nBindsTo=orchestella-desktop-compositor.service\n"))
            self.unit(UNITS[self.profile][2], self.service_body("/usr/bin/python3 -B /usr/local/lib/orchestella/display-relay.py --user " + self.username + " --transport " + self.display_transport(), user=False, restart=True, dependency="Requires=orchestella-desktop-wayvnc.service\nAfter=orchestella-desktop-wayvnc.service\nBindsTo=orchestella-desktop-wayvnc.service\n"))
        elif self.profile == "podman":
            self.configure_subids()
        elif self.profile == "code-server":
            self.config(self.home + "/.config/code-server/config.yaml", "bind-addr: 127.0.0.1:8080\nauth: password\npassword: " + secrets.token_hex(32) + "\ncert: false\n")
            self.credentials()
            pin = self.code_pin()
            cookie_path = self.directory / "code-server-cookie.json"
            cookie = read_json(cookie_path)
            if cookie is None:
                cookie = {"suffix": secrets.token_hex(16)}
                atomic_json(cookie_path, cookie)
            if not re.fullmatch("[0-9a-f]{32}", cookie.get("suffix", "")):
                raise Failure("invalid-cookie-suffix")
            self.unit(UNITS[self.profile][0], self.service_body("/opt/orchestella/code-server/%s/bin/code-server --config %s/.config/code-server/config.yaml --cookie-suffix %s --disable-telemetry --disable-update-check" % (pin["version"], self.home, cookie["suffix"])))
        self.run(["systemctl", "daemon-reload"], reason="unit-reload-failed")

    def configure_subids(self):
        starts = []
        missing = []
        for name in ("subuid", "subgid"):
            path = self.path("/etc/" + name)
            entries = path.read_text().splitlines() if path.exists() else []
            ranges = []
            own = []
            for line in entries:
                parts = line.split(":")
                if len(parts) != 3 or not parts[1].isdigit() or not parts[2].isdigit():
                    raise Failure("invalid-subid-ranges")
                start, count = int(parts[1]), int(parts[2])
                if start < 1 or count < 1 or start + count > 4294967295:
                    raise Failure("invalid-subid-ranges")
                ranges.append((start, start + count, parts[0]))
                if parts[0] == self.username:
                    own.append((start, count))
            for index, first in enumerate(ranges):
                for second in ranges[index + 1:]:
                    if max(first[0], second[0]) < min(first[1], second[1]):
                        raise Failure("overlapping-subid-ranges")
            if own and not any(count >= 65536 for _, count in own):
                raise Failure("subid-range-too-small")
            if not own:
                candidate = 100000
                while any(max(candidate, start) < min(candidate + 65536, end) for start, end, _ in ranges):
                    candidate += 65536
                starts.append(candidate)
                missing.append(name)
        for name, start in zip(missing, starts):
            self.run(["usermod", "--add-" + ("subuids" if name == "subuid" else "subgids"), "%d-%d" % (start, start + 65535), self.username], reason="subid-config-failed")
        if not self.testing:
            for helper in ("newuidmap", "newgidmap"):
                file = Path("/usr/bin", helper)
                if not file.is_file() or file.stat().st_uid != 0 or not file.stat().st_mode & stat.S_ISUID:
                    raise Failure("uidmap-helper-unavailable")

    def code_pin(self):
        pin = self.inputs().get("code_server")
        if not isinstance(pin, dict) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", str(pin.get("version", ""))):
            raise Failure("missing-input-pin")
        expected = "https://github.com/coder/code-server/releases/download/v{version}/code-server-{version}-linux-arm64.tar.gz".format(**pin)
        if pin.get("architecture") != "arm64" or pin.get("url") != expected or not re.fullmatch("[0-9a-f]{64}", str(pin.get("sha256", ""))) or not isinstance(pin.get("size"), int) or not 1 <= pin["size"] <= 512 << 20:
            raise Failure("invalid-input-pin")
        return pin

    def credentials(self):
        path = self.path(self.home + "/.config/code-server/config.yaml")
        if not path.is_file() or path.is_symlink() or path.stat().st_size > 4096:
            raise Failure("unsafe-code-server-config")
        if not self.testing and (path.stat().st_uid != self.user()[0] or stat.S_IMODE(path.stat().st_mode) != 0o600):
            raise Failure("unsafe-code-server-config")
        values = {}
        for line in path.read_text().splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            if ":" not in line:
                raise Failure("unsafe-code-server-config")
            key, value = line.split(":", 1)
            if key in values:
                raise Failure("unsafe-code-server-config")
            values[key] = value.strip().strip('\"\'')
        if values.get("bind-addr") != "127.0.0.1:8080" or values.get("auth") != "password" or values.get("cert") != "false" or not re.fullmatch("[0-9a-f]{64}", values.get("password", "")):
            raise Failure("unsafe-code-server-config")
        password = values["password"]
        if len(set(password)) < 8 or any(password == password[:period] * (64 // period) for period in (1, 2, 4, 8, 16, 32)):
            raise Failure("weak-service-credential")
        return password

    def install_code(self):
        pin = self.code_pin()
        self.save(phase="verified-download")
        cache = self.private_dir("/var/cache/orchestella/features")
        archive = cache / ("code-server-" + pin["version"] + ".tar.gz")
        if archive.is_symlink():
            raise Failure("unsafe-input-cache")
        if archive.exists():
            digest = hashlib.sha256()
            with archive.open("rb") as source:
                while chunk := source.read(1 << 20):
                    self.check_cancel()
                    digest.update(chunk)
            if archive.stat().st_size != pin["size"] or digest.hexdigest() != pin["sha256"]:
                raise Failure("cached-input-mismatch")
        else:
            part = cache / (".code-server-" + self.job + ".part")
            try:
                if self.testing:
                    source_path = self.root / self.fixture.get("archive", "missing-archive")
                    if not source_path.resolve().is_relative_to(self.root):
                        raise Failure("path-escape")
                    source = source_path.open("rb")
                else:
                    class FixedRedirect(urllib.request.HTTPRedirectHandler):
                        def redirect_request(self, request, fp, code, message, headers, newurl):
                            endpoint = urllib.parse.urlsplit(newurl)
                            if endpoint.scheme != "https" or endpoint.hostname not in ("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com") or endpoint.username or endpoint.password or endpoint.fragment or endpoint.port not in (None, 443):
                                raise Failure("download-endpoint-refused")
                            return super().redirect_request(request, fp, code, message, headers, newurl)
                    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), FixedRedirect())
                    source = opener.open(urllib.request.Request(pin["url"], headers={"User-Agent": "Orchestella-profilectl/1"}), timeout=15)
                digest = hashlib.sha256()
                size = 0
                deadline = time.monotonic() + 600
                with source, part.open("xb") as output:
                    os.chmod(part, 0o600)
                    while chunk := source.read(1 << 20):
                        self.check_cancel()
                        if time.monotonic() >= deadline:
                            raise Failure("download-timeout")
                        size += len(chunk)
                        if size > pin["size"]:
                            raise Failure("input-size-mismatch")
                        output.write(chunk)
                        digest.update(chunk)
                    output.flush()
                    os.fsync(output.fileno())
                if size != pin["size"]:
                    raise Failure("input-size-mismatch")
                if digest.hexdigest() != pin["sha256"]:
                    raise Failure("input-hash-mismatch")
                os.replace(part, archive)
            except (urllib.error.URLError, TimeoutError):
                raise Failure("input-download-failed") from None
            finally:
                part.unlink(missing_ok=True)
        self.save(phase="verified-extract")
        base = self.path("/opt/orchestella/code-server")
        for parent in (self.path("/opt/orchestella"), base):
            if not parent.exists():
                parent.mkdir(parents=True, mode=0o755)
                os.chmod(parent, 0o755)
            if parent.is_symlink() or not self.testing and (parent.stat().st_uid != 0 or parent.stat().st_mode & 0o022):
                raise Failure("unsafe-install-directory")
        destination = base / pin["version"]
        if destination.exists():
            marker = read_json(destination / ".orchestella-input.json", {})
            if marker.get("sha256") != pin["sha256"] or not (destination / "bin/code-server").is_file():
                raise Failure("installed-input-collision")
            return
        if not re.fullmatch("[0-9a-f]{32}", self.job or ""):
            raise Failure("invalid-job")
        staging = base / (".staging-" + self.job)
        base_descriptor = None
        created = None
        if os.name == "posix":
            if not shutil.rmtree.avoids_symlink_attacks:
                raise Failure("secure-staging-cleanup-unavailable")
            base_descriptor = os.open(base, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            if base_descriptor is not None:
                os.mkdir(staging.name, 0o700, dir_fd=base_descriptor)
                created = os.stat(staging.name, dir_fd=base_descriptor, follow_symlinks=False)
            else:
                # Windows is supported only for the sentinel-gated synthetic runner.
                staging.mkdir(mode=0o700)
                created = staging.lstat()
            top = "code-server-" + pin["version"] + "-linux-arm64"
            total = 0
            members = []
            try:
                with tarfile.open(archive, "r:gz") as tar:
                    for entry in tar:
                        self.check_cancel()
                        name = PurePosixPath(entry.name)
                        if name.is_absolute() or ".." in name.parts or not name.parts or name.parts[0] != top or entry.isdev() or entry.isfifo():
                            raise Failure("unsafe-input-archive")
                        total += entry.size
                        if total > 2147483648 or len(members) >= 100000:
                            raise Failure("input-extraction-limit")
                        members.append(entry)
                    def checked_data_filter(member, path):
                        # Also runs during extraction, before each file/link, not just inventory.
                        self.check_cancel()
                        return tarfile.data_filter(member, path)
                    tar.extractall(staging, members=members, filter=checked_data_filter)
            except (tarfile.TarError, ValueError):
                raise Failure("unsafe-input-archive") from None
            self.check_cancel()
            # The parent service owner never inherits tar-supplied writable or special modes.
            for directory, directories, files in os.walk(staging / top):
                self.check_cancel()
                os.chmod(directory, 0o755)
                for name in files:
                    self.check_cancel()
                    file = Path(directory, name)
                    if not file.is_symlink():
                        os.chmod(file, 0o755 if file.stat().st_mode & 0o111 else 0o644)
            executable = staging / top / "bin/code-server"
            if not executable.is_file() or not executable.resolve().is_relative_to(staging / top):
                raise Failure("input-executable-missing")
            self.check_cancel()
            os.chmod(staging / top, 0o755)
            atomic_json(staging / top / ".orchestella-input.json", {"version": pin["version"], "size": pin["size"], "sha256": pin["sha256"], "url": pin["url"]})
            self.check_cancel()
            if base_descriptor is not None:
                os.rename(staging.name + "/" + top, pin["version"], src_dir_fd=base_descriptor, dst_dir_fd=base_descriptor)
            else:
                os.rename(staging / top, destination)
        finally:
            try:
                if created is not None:
                    try:
                        current = (os.stat(staging.name, dir_fd=base_descriptor, follow_symlinks=False)
                                   if base_descriptor is not None else staging.lstat())
                    except FileNotFoundError:
                        current = None
                    if current is not None:
                        if not stat.S_ISDIR(current.st_mode) or (current.st_dev, current.st_ino) != (created.st_dev, created.st_ino):
                            raise Failure("unsafe-staging-cleanup")
                        # Only our exact created directory, anchored to the fixed parent FD.
                        # A published target was moved outside it and is never a cleanup target.
                        if base_descriptor is not None:
                            shutil.rmtree(staging.name, dir_fd=base_descriptor)
                        else:
                            shutil.rmtree(staging)
            finally:
                if base_descriptor is not None:
                    os.close(base_descriptor)

    def code_port(self):
        port = self.fixture.get("http_port", 8080) if self.testing else 8080
        if not isinstance(port, int) or not 1024 <= port <= 65535:
            raise Failure("invalid-test-port")
        return port

    @staticmethod
    def port_open(port, address="127.0.0.1"):
        try:
            with socket.create_connection((address, port), timeout=0.3):
                return True
        except OSError:
            return False

    def listener_present(self, address, port):
        # Passive observation: no connection, accept, or listener consumption.
        endpoint = socket.inet_aton(address)[::-1].hex().upper() + ":%04X" % port
        file = self.path("/proc/net/tcp")
        if not file.is_file() or file.stat().st_size > (1 << 20):
            return False
        uid, _ = self.user()
        for line in file.read_text().splitlines()[1:]:
            fields = line.split()
            if len(fields) >= 8 and fields[1].upper() == endpoint and fields[3] == "0A" and fields[7] == str(uid):
                return True
        return False

    def http(self, method, path, port, headers=None, body=None, address="127.0.0.1"):
        if not path.startswith("/") or "?" in path or "#" in path:
            raise Failure("unsafe-health-endpoint")
        connection = http.client.HTTPConnection(address, port, timeout=1)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            data = response.read(MAX_OUTPUT + 1)
            if len(data) > MAX_OUTPUT:
                raise Failure("health-response-too-large")
            return response.status, dict(response.getheaders()), data
        finally:
            connection.close()

    def code_health(self):
        password = self.credentials()
        port = self.code_port()
        status, _, _ = self.http("GET", "/", port, {"Accept": "text/html"})
        if status not in (302, 303, 401, 403):
            raise Failure("code-server-auth-failed")
        status, _, data = self.http("GET", "/healthz", port, {"Accept": "application/json"})
        if status != 200 or json.loads(data).get("status") != "alive":
            raise Failure("code-server-unhealthy")
        status, headers, _ = self.http("POST", "/login", port, {"Content-Type": "application/x-www-form-urlencoded"}, urllib.parse.urlencode({"password": password}).encode())
        cookie = SimpleCookie()
        cookie.load(headers.get("Set-Cookie", headers.get("set-cookie", "")))
        suffix = read_json(self.directory / "code-server-cookie.json", {}).get("suffix", "")
        if not re.fullmatch("[0-9a-f]{32}", suffix):
            raise Failure("auth-unverified")
        cookie_name = "code-server-session-" + suffix
        session = cookie.get(cookie_name)
        if status not in (302, 303) or session is None or len(session.value) > 4096:
            raise Failure("code-server-auth-failed")
        status, _, data = self.http("GET", "/", port, {"Cookie": cookie_name + "=" + session.value, "Accept": "text/html"})
        if status != 200 or not any(marker in data.lower() for marker in (b"vscode", b"workbench")):
            raise Failure("code-server-auth-failed")

    def start_code(self):
        self.credentials()
        pin = self.code_pin()
        installed = self.path("/opt/orchestella/code-server/" + pin["version"])
        if read_json(installed / ".orchestella-input.json", {}).get("sha256") != pin["sha256"]:
            raise Failure("installed-input-mismatch")
        rc, _ = self.run(["systemctl", "is-active", "--quiet", UNITS[self.profile][0]], required=False)
        if rc and self.port_open(self.code_port()):
            raise Failure("port-conflict")
        self.save(phase="code-server-service", health="unverified")
        self.run(["systemctl", "start", UNITS[self.profile][0]], timeout=20, reason="code-server-start-failed")
        deadline = time.monotonic() + (3 if self.testing else 120)
        reason = "code-server-unhealthy"
        while time.monotonic() < deadline:
            self.check_cancel()
            self.run(["systemctl", "is-active", "--quiet", UNITS[self.profile][0]], reason="service-not-active")
            try:
                self.code_health()
                break
            except (Failure, OSError, ValueError) as error:
                reason = error.reason if isinstance(error, Failure) else "code-server-unhealthy"
                time.sleep(0.1)
        else:
            raise Failure(reason)
        self.check_cancel()
        self.save(state="ready", phase="authenticated-http", reason="none", health="authenticated-http", code_server_version=pin["version"],
                  service_origin="http://127.0.0.1:8080", guest_relay=False, guest_relay_address="none", authenticationConfigured=True,
                  config_sha256=hashlib.sha256(self.path(self.home + "/.config/code-server/config.yaml").read_bytes()).hexdigest())

    def relay_profile(self, expose):
        if self.profile != "code-server":
            raise Failure("unsupported-action")
        address, port = "192.168.127.15", 18080
        if expose:
            try:
                self.credentials()
                self.code_health()
            except (Failure, OSError, ValueError):
                raise Failure("auth-unverified") from None
            self.run(["systemctl", "is-active", "--quiet", UNITS[self.profile][0]], reason="service-not-active")
            _, available = self.run(["ip", "-4", "-o", "addr", "show", "scope", "global"], reason="guest-relay-address-unavailable")
            if address not in re.findall(r"inet ([0-9.]+)/", available):
                raise Failure("guest-relay-address-unavailable")
            self.unit(UNITS[self.profile][1], self.service_body("/usr/bin/python3 -B /usr/local/lib/orchestella/feature-session.py nic-relay 192.168.127.15 18080", "NoNewPrivileges=yes\n", dependency="Requires=orchestella-code-server.service\nAfter=orchestella-code-server.service\nBindsTo=orchestella-code-server.service\n"))
            self.run(["systemctl", "daemon-reload"])
            self.run(["systemctl", "start", UNITS[self.profile][1]], reason="guest-relay-start-failed")
            deadline = time.monotonic() + 10
            while not self.listener_present(address, port):
                self.check_cancel()
                self.run(["systemctl", "is-active", "--quiet", UNITS[self.profile][1]], reason="guest-relay-start-failed")
                if time.monotonic() >= deadline:
                    raise Failure("guest-relay-listener-missing")
                time.sleep(0.1)
            uid, _ = self.user()
            atomic_json(self.path("/run/user/%d/orchestella/code-server-relay.json" % uid), {"address": address, "port": port, "explicit": True})
            self.save(state="ready", phase="guest-relay", reason="none", guest_relay=True, guest_relay_address=address, guest_relay_port=port)
        else:
            self.run(["systemctl", "stop", UNITS[self.profile][1]], timeout=10, required=False, cancellable=False)
            rc, _ = self.run(["systemctl", "is-active", "--quiet", UNITS[self.profile][1]], required=False, cancellable=False)
            if not rc or self.listener_present(address, port):
                raise Failure("listener-still-present")
            uid, _ = self.user()
            self.path("/run/user/%d/orchestella/code-server-relay.json" % uid).unlink(missing_ok=True)
            self.save(state="ready" if self.state()["health"] == "authenticated-http" else "installed", phase="loopback", reason="none", guest_relay=False, guest_relay_address="none", guest_relay_port=0)

    def wait_socket(self, guest, unit):
        deadline = time.monotonic() + 30
        path = self.path(guest)
        while time.monotonic() < deadline:
            self.check_cancel()
            if path.exists() and not path.is_symlink():
                info = path.stat()
                if self.testing or stat.S_ISSOCK(info.st_mode) and info.st_uid == self.user()[0]:
                    return
            self.run(["systemctl", "is-active", "--quiet", unit], timeout=2, reason="service-not-active")
            time.sleep(0.1)
        raise Failure("display-socket-timeout")

    def display_transport(self):
        file = self.path("/proc/cmdline")
        words = file.read_text().split() if file.is_file() and file.stat().st_size < 4096 else []
        markers = [word for word in words if word.startswith("droidlayer.vsock_control_port=")]
        if not markers:
            return "virtio"
        if markers == ["droidlayer.vsock_control_port=9100"]:
            return "vsock"
        raise Failure("display-transport-unsupported")

    def prepare_display_transport(self):
        transport = self.display_transport()
        if transport == "virtio":
            self.prepare_display_device()
        else:
            if self.testing:
                if not self.fixture.get("vsock_supported"):
                    raise Failure("display-transport-unsupported")
            else:
                rc, _ = self.run(["systemctl", "is-active", "--quiet", UNITS[self.profile][2]], required=False)
                if rc:
                    try:
                        with socket.socket(socket.AF_VSOCK, socket.SOCK_STREAM) as probe:
                            probe.bind((socket.VMADDR_CID_ANY, 9103))
                            probe.listen(1)
                    except (OSError, AttributeError):
                        raise Failure("display-transport-unsupported") from None
        self.save(display_transport=transport)

    def prepare_display_device(self):
        if self.testing:
            return
        link = Path("/dev/virtio-ports/orchestella.display")
        try:
            target = link.resolve(strict=True)
            if target.parent != Path("/dev") or not re.fullmatch(r"vport[0-9]+p[0-9]+", target.name):
                raise Failure("unsafe-display-device")
            info = target.stat()
            name = Path("/sys/class/virtio-ports", target.name, "name").read_text().strip()
            if not stat.S_ISCHR(info.st_mode) or info.st_uid != 0 or name != "orchestella.display":
                raise Failure("unsafe-display-device")
            os.chmod(target, 0o600)
            rule = Path("/etc/udev/rules.d/71-orchestella-display.rules")
            prefix = "# Orchestella owned display device rule\n"
            if rule.is_symlink() or rule.exists() and not rule.read_text().startswith(prefix):
                raise Failure("display-rule-collision")
            atomic_bytes(rule, (prefix + 'SUBSYSTEM=="virtio-ports", ATTR{name}=="orchestella.display", OWNER="root", GROUP="root", MODE="0600"\n').encode(), 0o644)
        except OSError:
            raise Failure("display-device-unavailable") from None

    def capture_desktop(self):
        uid, _ = self.user()
        capture = self.path("/run/user/%d/orchestella/capture-%s.ppm" % (uid, self.job))
        self.save(phase="guest-capture", capture="unverified")
        try:
            self.as_user(["grim", "-t", "ppm", str(capture)], timeout=20, reason="capture-unverified")
            if capture.is_symlink():
                raise Failure("capture-unverified")
            descriptor = os.open(capture, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
            with os.fdopen(descriptor, "rb") as source:
                if os.fstat(source.fileno()).st_size > (16 << 20):
                    raise Failure("capture-too-large")
                data = source.read((16 << 20) + 1)
            match = re.match(rb"P6\s([0-9]+)\s([0-9]+)\s255\s", data)
            if not match:
                raise Failure("capture-unverified")
            width, height = int(match[1]), int(match[2])
            pixels = data[match.end():]
            if not 1 <= width <= 4096 or not 1 <= height <= 4096 or width * height > 4194304 or len(pixels) != width * height * 3:
                raise Failure("capture-unverified")
            if pixels == pixels[:3] * (width * height):
                raise Failure("capture-no-client-content")
            self.check_cancel()
            self.save(capture="verified", capture_generation=self.job, capture_sha256=hashlib.sha256(data).hexdigest(), width=width, height=height, capture_width=width, capture_height=height)
        finally:
            capture.unlink(missing_ok=True)

    def desktop_clients(self):
        uid, _ = self.user()
        if self.testing:
            if self.fixture.get("no_clients"):
                raise Failure("desktop-client-unavailable")
            return
        path = Path("/run/user/%d/orchestella/desktop-clients.json" % uid)
        if path.is_symlink():
            raise Failure("desktop-client-unavailable")
        value = read_json(path, {})
        for name in ("foot", "waybar"):
            pid = value.get(name)
            if not isinstance(pid, int) or process_identity(pid) is None:
                raise Failure("desktop-client-unavailable")
            try:
                status = Path("/proc/%d/status" % pid).read_text()
                owner = re.search(r"^Uid:\s+(\d+)", status, re.MULTILINE)
                executable = Path("/proc/%d/exe" % pid).resolve()
                if not owner or int(owner[1]) != uid or executable != Path("/usr/bin/" + name).resolve():
                    raise Failure("desktop-client-unavailable")
            except OSError:
                raise Failure("desktop-client-unavailable") from None

    def start_profile(self):
        if self.profile == "desktop-labwc":
            uid, _ = self.user()
            if not self.testing and not Path(PAYLOAD + "/display-relay.py").is_file():
                raise Failure("display-relay-unavailable")
            self.save(phase="compositor", capture="unverified", protocols="unverified")
            self.run(["systemctl", "start", UNITS[self.profile][0]], timeout=20, reason="compositor-start-failed")
            deadline = time.monotonic() + 30
            while True:
                try:
                    display = self.display_name()
                    break
                except Failure:
                    self.check_cancel()
                    self.run(["systemctl", "is-active", "--quiet", UNITS[self.profile][0]], reason="service-not-active")
                    if time.monotonic() >= deadline:
                        raise Failure("desktop-session-timeout") from None
                    time.sleep(0.1)
            self.wait_socket("/run/user/%d/%s" % (uid, display), UNITS[self.profile][0])
            self.save(phase="wayland-protocols")
            _, protocols = self.as_user(["wayland-info"], timeout=15, reason="wayland-probe-failed")
            if any(name not in protocols for name in ("zwlr_virtual_pointer_manager_v1", "zwp_virtual_keyboard_manager_v1", "zwlr_screencopy_manager_v1")):
                raise Failure("wayland-protocol-unavailable")
            self.save(protocols="verified", phase="desktop-clients")
            deadline = time.monotonic() + 10
            while True:
                try:
                    self.desktop_clients()
                    break
                except Failure:
                    if time.monotonic() >= deadline:
                        raise
                    self.check_cancel()
                    time.sleep(0.1)
            self.capture_desktop()
            self.save(phase="wayvnc")
            self.run(["systemctl", "start", UNITS[self.profile][1]], timeout=20, reason="wayvnc-start-failed")
            self.wait_socket("/run/user/%d/orchestella/wayvnc.sock" % uid, UNITS[self.profile][1])
            self.prepare_display_transport()
            self.run(["systemctl", "start", UNITS[self.profile][2]], timeout=20, reason="display-relay-start-failed")
            for unit in UNITS[self.profile]:
                self.run(["systemctl", "is-active", "--quiet", unit], reason="service-not-active")
            self.check_cancel()
            self.save(state="awaiting-frame", phase="host-frame-confirmation", reason="awaiting-host-frame")
        elif self.profile == "code-server":
            self.start_code()
        elif self.profile == "podman":
            self.start_podman()
        elif self.profile == "docker-cli":
            self.as_user(["docker", "--version"], reason="docker-cli-unavailable")
            # Do not start a root daemon; the distribution's CLI package may also contain one.
            self.save(state="installed", phase="cli-verified", reason="daemon-unverified", docker_cli="verified", docker_daemon="unverified")

    def start_podman(self):
        uid, gid = self.user()
        self.configure_subids()
        self.save(phase="rootless-kernel", container_info="unverified", container_run="unverified")
        self.run(["systemctl", "start", "user@%d.service" % uid], reason="user-service-unavailable")
        self.as_user(["unshare", "--user", "--map-root-user", "true"], reason="userns-unavailable")
        _, mapping = self.as_user(["podman", "unshare", "cat", "/proc/self/uid_map"], reason="uidmap-unavailable")
        ranges = [line.split() for line in mapping.splitlines()]
        if not any(len(row) == 3 and row[2].isdigit() and int(row[2]) >= 65536 for row in ranges):
            raise Failure("uidmap-unavailable")
        for probe in ("seccomp", "cgroup"):
            _, output = self.as_user(["python3", "-B", PAYLOAD + "/rootless-probe.py", probe, str(self.user()[0])], reason=probe + "-unavailable")
            value = json.loads(output)
            if not value.get("seccomp" if probe == "seccomp" else "cgroup2"):
                raise Failure(probe + "-unavailable")
        _, output = self.as_user(["podman", "unshare", "python3", "-B", PAYLOAD + "/rootless-probe.py", "overlay", str(self.user()[0])], reason="overlay-unavailable")
        overlay = json.loads(output).get("overlay")
        if overlay not in ("native", "fuse"):
            raise Failure("overlay-unavailable")
        self.save(phase="podman-info", userns="verified", cgroup2="verified", seccomp="verified", overlay=overlay)
        _, output = self.as_user(["podman", "info", "--format", "json"], timeout=60, reason="podman-info-failed")
        info = json.loads(output)
        if not info.get("host", {}).get("security", {}).get("rootless"):
            raise Failure("podman-not-rootless")
        if not info["host"]["security"].get("seccompEnabled") or info["host"].get("cgroupVersion") != "v2" or info.get("store", {}).get("graphDriverName") != "overlay":
            raise Failure("podman-capability-unavailable")
        self.save(container_info="verified", phase="container-run")
        pin = self.inputs().get("podman_acceptance", {})
        if not re.fullmatch(r"quay.io/podman/hello@sha256:[0-9a-f]{64}", pin.get("hello", "")) or not re.fullmatch(r"docker.io/library/alpine@sha256:[0-9a-f]{64}", pin.get("alpine", "")):
            raise Failure("missing-container-pin")
        names = ["orchestella-accept-" + self.job + suffix for suffix in ("-hello", "-net", "-vol", "-port")]
        volume = self.private_dir(self.home + "/.local/share/orchestella/acceptance/" + self.job, uid, gid)
        marker = volume / "probe.txt"
        marker.write_text("orchestella-volume-acceptance\n")
        os.chmod(marker, 0o600)
        if not self.testing:
            os.chown(marker, uid, gid)
        try:
            self.as_user(["podman", "run", "--rm", "--name", names[0], "--network", "slirp4netns", "--pull", "missing", pin["hello"]], timeout=300, reason="container-run-failed")
            self.save(phase="container-dns-tls", container_run="verified")
            self.as_user(["podman", "run", "--rm", "--name", names[1], "--network", "slirp4netns", "--pull", "missing", pin["alpine"], "sh", "-ec", "nslookup example.com >/dev/null; wget -q -O /dev/null https://example.com/"], timeout=300, reason="container-network-failed")
            self.save(container_dns="verified", container_tls="verified", phase="container-volume")
            _, content = self.as_user(["podman", "run", "--rm", "--name", names[2], "--network", "none", "--pull", "never", "--volume", str(volume) + ":/probe:ro", pin["alpine"], "cat", "/probe/probe.txt"], timeout=60, reason="container-volume-failed")
            if content != "orchestella-volume-acceptance\n":
                raise Failure("container-volume-failed")
            self.save(container_volume="verified", phase="container-port")
            with socket.socket() as reservation:
                reservation.bind(("127.0.0.1", 0))
                port = reservation.getsockname()[1]
            self.as_user(["podman", "run", "--detach", "--name", names[3], "--network", "slirp4netns", "--pull", "never", "--publish", "127.0.0.1:%d:8080" % port,
                          pin["alpine"], "sh", "-ec", "mkdir -p /tmp/accept; printf orchestella-port-acceptance >/tmp/accept/index.html; exec httpd -f -p 8080 -h /tmp/accept"], timeout=60, reason="container-port-failed")
            deadline = time.monotonic() + 20
            while True:
                self.check_cancel()
                try:
                    status, _, body = self.http("GET", "/", port)
                    if status == 200 and body == b"orchestella-port-acceptance":
                        break
                except OSError:
                    pass
                if time.monotonic() >= deadline:
                    raise Failure("container-port-failed")
                time.sleep(0.1)
            self.save(container_port="verified")
        finally:
            # Remove only this job's probe containers, never user workloads.
            for name in names:
                self.as_user_cleanup(["podman", "rm", "--force", name])
            marker.unlink(missing_ok=True)
            volume.rmdir()
        self.check_cancel()
        self.save(state="ready", phase="rootless-run-verified", reason="none")

    def as_user_cleanup(self, args):
        uid, _ = self.user()
        return self.run(["runuser", "--user", self.username, "--", "env", "HOME=" + self.home, "XDG_RUNTIME_DIR=/run/user/%d" % uid, *args],
                        timeout=10, required=False, cancellable=False)

    def stop_profile(self):
        for unit in reversed(UNITS[self.profile]):
            self.run(["systemctl", "stop", unit], timeout=10, required=False, cancellable=False)
            rc, _ = self.run(["systemctl", "is-active", "--quiet", unit], timeout=2, required=False, cancellable=False)
            if rc == 0:
                raise Failure("service-stop-failed")
        uid, _ = self.user()
        if self.profile == "desktop-labwc":
            for name in ("wayvnc.sock", "desktop-clients.json"):
                self.path("/run/user/%d/orchestella/%s" % (uid, name)).unlink(missing_ok=True)
        elif self.profile == "code-server":
            # Any remaining listener is reported; do not kill a foreign process.
            if self.port_open(self.code_port()) or self.listener_present("192.168.127.15", 18080):
                raise Failure("listener-still-present")
            self.path("/run/user/%d/orchestella/code-server-relay.json" % uid).unlink(missing_ok=True)

    def resize(self):
        if self.profile != "desktop-labwc" or self.state()["state"] not in ("awaiting-frame", "ready"):
            raise Failure("desktop-not-running")
        try:
            width, height = int(self.options.arguments[0]), int(self.options.arguments[1])
            scale = float(self.options.arguments[2])
        except ValueError:
            raise Failure("invalid-display-size") from None
        if not 1 <= width <= 4096 or not 1 <= height <= 4096 or width * height > 4194304 or not math.isfinite(scale) or not 0.1 <= scale <= 5:
            raise Failure("invalid-display-size")
        self.as_user(["wlr-randr", "--output", "HEADLESS-1", "--custom-mode", "%dx%d" % (width, height), "--scale", str(scale)], timeout=2, reason="resize-unavailable")
        state, _ = self.launch("start")
        return state

    def job_owner(self):
        lock = self.directory / "install.lock"
        if lock.is_symlink():
            raise Failure("unsafe-state-directory")
        return read_json(lock / "owner.json", {})

    def live_job(self, owner):
        return isinstance(owner, dict) and owner.get("pid") and owner.get("identity") is not None and process_identity(owner["pid"]) == owner["identity"]

    def launch(self, operation):
        self.distro()
        if operation == "start" and not self.state()["installed"]:
            raise Failure("not-installed")
        if operation == "start" and self.state().get("user") != self.username:
            raise Failure("profile-identity-action-required")
        with self.mutex():
            owner = self.job_owner()
            if self.live_job(owner):
                if owner.get("profile") == self.profile and owner.get("operation") == operation:
                    return self.public(), 0
                raise Failure("busy")
            lock = self.directory / "install.lock"
            if lock.exists():
                (lock / "owner.json").unlink(missing_ok=True)
                lock.rmdir()
            initial = self.state()
            lock.mkdir(mode=0o700)
            process = None
            try:
                job = secrets.token_hex(16)
                state = dict(initial)
                state.update(state="installing" if operation == "install" else "starting", phase=operation, reason="none", job_id=job,
                             generation=job if operation == "start" else initial["generation"] if operation in ("expose", "unexpose") else "none", operation=operation, updated_at=int(time.time()))
                if operation in ("start", "stop"):
                    state.update(capture="unverified", protocols="unverified", health="unverified", authenticationConfigured=False)
                atomic_json(self.directory / (self.profile + ".json"), state)
                args = [sys.executable, "-B", str(Path(__file__).resolve())]
                if self.testing:
                    args.extend(["--test-root", str(self.root), "--test-runner", self.runner])
                args.extend(["--worker", "--job-id", job, operation, self.profile])
                process = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True,
                                           env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"})
                owner = {"pid": process.pid, "identity": process_identity(process.pid), "job_id": job, "profile": self.profile, "operation": operation}
                atomic_json(lock / "owner.json", owner)
            except BaseException as error:
                if process is not None:
                    self.terminate(process)
                (lock / "owner.json").unlink(missing_ok=True)
                lock.rmdir()
                try:
                    atomic_json(self.directory / (self.profile + ".json"), initial)
                except OSError:
                    pass  # The old or incomplete state is never promoted to ready.
                if isinstance(error, OSError):
                    raise Failure("disk-full" if error.errno == errno.ENOSPC else "job-launch-failed") from None
                raise
        return self.public(state), 0

    def worker(self):
        self.job = self.options.job_id
        try:
            # Parent holds command mutex until it has published the child's PID.
            with self.mutex():
                owner = self.job_owner()
                if owner.get("job_id") != self.job or owner.get("pid") != os.getpid():
                    raise Failure("stale-job")
            self.check_cancel()
            if self.options.action == "install":
                self.distro()
                if self.profile == "code-server":
                    self.code_pin()
                self.wait_baseline()
                self.ensure_disk()
                if self.state()["installed"]:
                    self.stop_profile()
                self.signed_packages()
                self.save(phase="configuration")
                self.configure()
                if self.profile == "code-server":
                    self.install_code()
                self.check_cancel()
                self.save(state="installed", installed=True, phase="complete", units_version=self.inputs()["units_version"], user=self.username, reason="none",
                          input_sha256=self.code_pin()["sha256"] if self.profile == "code-server" else "none")
            elif self.options.action == "start":
                self.installed_packages()
                self.runtime()
                self.start_profile()
            elif self.options.action == "stop":
                self.stop_profile()
                self.save(state="installed" if self.state()["installed"] else "planned", phase="stopped", reason="none", generation="none", health="unverified", capture="unverified", authenticationConfigured=False, guest_relay=False, guest_relay_address="none", guest_relay_port=0)
            elif self.options.action in ("expose", "unexpose"):
                self.relay_profile(self.options.action == "expose")
        except (Failure, OSError, ValueError, KeyError) as error:
            if self.options.action in ("start", "stop"):
                try:
                    self.stop_profile()
                except (Failure, OSError):
                    pass
            if self.options.action == "expose":
                try:
                    self.relay_profile(False)
                except (Failure, OSError):
                    pass
            reason = error.reason if isinstance(error, Failure) else "disk-full" if isinstance(error, OSError) and error.errno == errno.ENOSPC else "io-failed"
            self.save(state="cancelled" if reason == "cancelled" else "failed", phase="stopped" if reason == "cancelled" else self.state()["phase"], reason=reason, health="unverified", capture="unverified")
        finally:
            with self.mutex():
                owner = self.job_owner()
                if owner.get("job_id") == self.job:
                    (self.directory / "install.lock/owner.json").unlink(missing_ok=True)
                    (self.directory / "install.lock").rmdir()
            (self.directory / (self.job + ".cancel")).unlink(missing_ok=True)
        return self.public(), 0

    def command(self):
        action = self.options.action
        if action not in ("stop", "cancel", "unexpose"):
            self.user()
        if self.options.worker:
            return self.worker()
        if action == "status":
            # launch publishes state and owner under the same mutex; observe/reconcile that pair atomically.
            with self.mutex():
                state = self.state()
                changed = False
                if state["state"] in ("awaiting-frame", "ready") and state.get("verified_boot") != self.boot_id():
                    state.update(state="installed", reason="restart-required", generation="none", health="unverified", capture="unverified", authenticationConfigured=False, guest_relay=False)
                    changed = True
                if state["state"] in ("installing", "starting") and not self.live_job(self.job_owner()):
                    state.update(state="failed", reason="interrupted", phase="recovery-required")
                    changed = True
                if changed:
                    state["updated_at"] = int(time.time())
                    atomic_json(self.directory / (self.profile + ".json"), state)
            if state["state"] == "ready" and self.profile == "code-server":
                try:
                    self.credentials()
                    if hashlib.sha256(self.path(self.home + "/.config/code-server/config.yaml").read_bytes()).hexdigest() != state.get("config_sha256"):
                        raise Failure("auth-unverified")
                except (Failure, OSError):
                    state = self.save(state="failed", reason="auth-unverified", authenticationConfigured=False, health="unverified")
            if state["state"] in ("awaiting-frame", "ready") and self.profile in ("desktop-labwc", "code-server"):
                for unit in UNITS[self.profile][:3 if self.profile == "desktop-labwc" else 1]:
                    rc, _ = self.run(["systemctl", "is-active", "--quiet", unit], timeout=0.7, required=False)
                    if rc:
                        state = self.save(state="failed", reason="service-not-active", health="unverified", capture="unverified")
                        break
            if self.profile == "code-server" and state.get("guest_relay"):
                rc, _ = self.run(["systemctl", "is-active", "--quiet", UNITS[self.profile][1]], timeout=0.5, required=False)
                relay_live = not rc and self.listener_present("192.168.127.15", 18080)
                if state["state"] != "ready" or not relay_live:
                    if state["state"] != "ready":
                        self.run(["systemctl", "stop", UNITS[self.profile][1]], timeout=0.5, required=False)
                    uid, _ = self.user()
                    self.path("/run/user/%d/orchestella/code-server-relay.json" % uid).unlink(missing_ok=True)
                    state = self.save(guest_relay=False, guest_relay_address="none", guest_relay_port=0,
                                      reason="guest-relay-unavailable" if state["state"] == "ready" else state["reason"])
            return self.public(state), 0
        if action == "probe":
            metadata = self.distro()
            if self.profile == "code-server":
                self.code_pin()
            previous_action = self.options.action
            self.options.action = "status"
            try:
                result, _ = self.command()
            finally:
                self.options.action = previous_action
            result.update(metadata, selectable=True, runtime_verified=result["state"] == "ready", packages=",".join(PACKAGES[self.profile]),
                          minimum_disk_free_bytes={"desktop-labwc": 700 << 20, "podman": 600 << 20, "code-server": 1800 << 20, "docker-cli": 400 << 20}[self.profile],
                          recommended_ram_bytes={"desktop-labwc": 1024 << 20, "podman": 1024 << 20, "code-server": 1024 << 20, "docker-cli": 256 << 20}[self.profile],
                          backend="labwc-headless-pixman" if self.profile == "desktop-labwc" else "rootless-slirp4netns" if self.profile == "podman" else "local-service" if self.profile == "code-server" else "cli-only",
                          research_status="signed-packages-and-fixed-inputs", resource_estimate="planning-budget-not-measured")
            return result, 0
        if action in ("expose", "unexpose"):
            if self.profile != "code-server":
                raise Failure("unsupported-action")
            if action == "expose":
                try:
                    self.credentials()
                except Failure:
                    raise Failure("auth-unverified") from None
                if self.state()["state"] != "ready" or not self.state().get("authenticationConfigured"):
                    raise Failure("auth-unverified")
            return self.launch(action)
        if action in ("install", "start", "stop"):
            current = self.state()
            if action == "install" and current["state"] in ("installed", "ready", "awaiting-frame") and current["installed"] and current.get("user") == self.username and current["units_version"] == self.inputs()["units_version"] and (self.profile != "code-server" or current.get("input_sha256") == self.code_pin()["sha256"]):
                return self.public(), 0
            if action == "start" and current["state"] in ("ready", "awaiting-frame") and current.get("user") == self.username and current.get("verified_boot") == self.boot_id():
                running = True
                for unit in UNITS[self.profile][:3 if self.profile == "desktop-labwc" else 1]:
                    rc, _ = self.run(["systemctl", "is-active", "--quiet", unit], timeout=0.6, required=False)
                    running = running and not rc
                if self.profile == "code-server":
                    self.credentials()
                    running = running and hashlib.sha256(self.path(self.home + "/.config/code-server/config.yaml").read_bytes()).hexdigest() == current.get("config_sha256")
                if running:
                    return self.public(), 0
            if action == "stop" and current["state"] == "planned":
                return self.public(), 0
            owner = self.job_owner()
            if action == "stop" and self.live_job(owner):
                if owner.get("profile") != self.profile:
                    raise Failure("busy")
                atomic_json(self.directory / (owner["job_id"] + ".cancel"), {"cancel": True})
                return self.public(self.save(state="cancelled", reason="cancelled", phase="cancelling")), 0
            return self.launch(action)
        if action == "cancel":
            owner = self.job_owner()
            if self.live_job(owner) and owner.get("profile") == self.profile:
                atomic_json(self.directory / (owner["job_id"] + ".cancel"), {"cancel": True})
                return self.public(self.save(state="cancelled", reason="cancelled", phase="cancelling")), 0
            return self.public(), 0
        if action == "confirm-frame":
            state = self.state()
            if self.profile != "desktop-labwc" or not self.options.generation or state["generation"] != self.options.generation or state.get("verified_boot") != self.boot_id():
                raise Failure("stale-frame")
            if state["state"] not in ("awaiting-frame", "ready") or state["capture"] != "verified" or state["protocols"] != "verified":
                raise Failure("capture-unverified")
            for unit in UNITS[self.profile]:
                self.run(["systemctl", "is-active", "--quiet", unit], timeout=0.6, reason="service-not-active")
            with self.mutex():
                # Service probes above may race STOP/CANCEL/RESIZE. Commit the same proof only.
                current = self.state()
                owner = self.job_owner()
                cancelled = owner.get("job_id") and (self.directory / (owner["job_id"] + ".cancel")).exists()
                if (current["generation"] != self.options.generation or current.get("capture_generation") != self.options.generation
                        or current.get("verified_boot") != self.boot_id() or current["state"] not in ("awaiting-frame", "ready")
                        or current["capture"] != "verified" or current["protocols"] != "verified" or cancelled):
                    raise Failure("stale-frame")
                if self.live_job(owner) and owner.get("profile") == self.profile and (owner.get("operation") != "start" or owner.get("job_id") != self.options.generation):
                    raise Failure("busy")
                current.update(state="ready", phase="complete", reason="none", updated_at=int(time.time()))
                atomic_json(self.directory / (self.profile + ".json"), current)
            return self.public(current), 0
        if action == "resize":
            return self.resize(), 0
        raise Failure("unknown-action")


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        raise Failure("malformed-command")


def parse():
    parser = SafeParser(add_help=False, exit_on_error=False)
    parser.add_argument("--test-root")
    parser.add_argument("--test-runner")
    parser.add_argument("--format", choices=("json", "kv"), default="json")
    parser.add_argument("--worker", action="store_true")
    parser.add_argument("--job-id")
    parser.add_argument("action")
    parser.add_argument("profile")
    parser.add_argument("arguments", nargs="*")
    value = parser.parse_args()
    value.generation = value.arguments[0] if value.action == "confirm-frame" and len(value.arguments) == 1 else None
    if value.action == "confirm-frame" and len(value.arguments) != 1 or value.action == "resize" and len(value.arguments) != 3 or value.action not in ("confirm-frame", "resize") and value.arguments:
        raise Failure("malformed-command")
    if value.worker and (value.action not in ("install", "start", "stop", "expose", "unexpose") or not re.fullmatch("[0-9a-f]{32}", value.job_id or "")):
        raise Failure("malformed-command")
    return value


def safe_error(profile, reason):
    # Fixed schema even when state/owner storage is unavailable; never claim an unobserved job stopped.
    return {"version": 1, "profile": profile, "state": "failed", "installed": False,
            "reason": reason, "phase": "error", "generation": "none", "job_active": False,
            "job_active_verified": False, "capture": "unverified", "protocols": "unverified",
            "health": "unverified", "exposed": False, "authenticationConfigured": False,
            "input": {"keyboard": False, "unicodeText": False, "pointer": False}}


def emit(result, output_format="json"):
    if output_format == "kv":
        line = " ".join(key + "=" + str(value).lower() if isinstance(value, bool) else key + "=" + str(value)
                        for key, value in sorted(result.items()) if isinstance(value, (str, int, bool)))
    else:
        line = json.dumps(result, separators=(",", ":"), ensure_ascii=True)
    if len(line.encode()) + 1 > MAX_LINE or "\n" in line:
        line = json.dumps(safe_error("none", "response-too-large"), separators=(",", ":"))
    print(line, flush=True)


def main():
    options = None
    controller = None
    try:
        options = parse()
        if options.profile not in PROFILES:
            raise Failure("unknown-profile")
        if options.action not in ACTIONS:
            raise Failure("unknown-action")
        if options.generation is not None and (options.action != "confirm-frame" or not re.fullmatch("[0-9a-f]{32}", options.generation)):
            raise Failure("invalid-generation")
        controller = Controller(options)
        result, code = controller.command()
    except (Failure, OSError) as error:
        reason = error.reason if isinstance(error, Failure) else ("disk-full" if error.errno == errno.ENOSPC else "io-failed")
        try:
            result = controller.public() if controller else safe_error(options.profile if options else "none", reason)
        except (Failure, OSError, ValueError, KeyError, TypeError):
            result = safe_error(options.profile if options else "none", reason)
        result.update(state="failed", reason=reason, authenticationConfigured=False, input={"keyboard": False, "unicodeText": False, "pointer": False})
        if reason in ("ordinary-user-action-required", "ordinary-user-missing", "unsafe-ordinary-user", "unsafe-instance-identity", "profile-identity-action-required"):
            result["phase"] = "action-required"
        code = 1
    except (argparse.ArgumentError, SystemExit, ValueError, KeyError, TypeError):
        result = safe_error("none", "malformed-command")
        code = 1
    emit(result, options.format if options else "json")
    return code


if __name__ == "__main__":
    sys.exit(main())
