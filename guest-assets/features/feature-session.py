#!/usr/bin/env python3
"""Fixed ordinary-user service executables. Never accepts a shell command."""
import ipaddress
import json
import os
from pathlib import Path
import pwd
import re
import signal
import socket
import stat
import subprocess
import sys
import threading
import time

def ordinary_user():
    # The root-owned unit chooses this UID; service helpers need no access to the 0600 host identity marker.
    user = pwd.getpwuid(os.geteuid())
    if not re.fullmatch(r"[a-z_][a-z0-9_-]{1,15}", user.pw_name) or user.pw_name == "root" or user.pw_dir != "/home/" + user.pw_name:
        raise ValueError("ordinary-user-required")
    return user


def runtime_dir():
    user = ordinary_user()
    if user.pw_uid < 1000 or user.pw_uid == 65534 or user.pw_gid <= 0 or user.pw_gid == 65534 or 0 in os.getgroups() or os.geteuid() != user.pw_uid or os.getegid() != user.pw_gid:
        raise ValueError("ordinary-user-required")
    runtime = Path("/run/user/%d" % user.pw_uid)
    for directory in (runtime, runtime / "orchestella"):
        info = directory.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != user.pw_uid or stat.S_IMODE(info.st_mode) != 0o700:
            raise ValueError("unsafe-runtime-dir")
    return runtime


def wait_wayland(runtime, inherited=False):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if inherited:
            display = os.environ.get("WAYLAND_DISPLAY", "")
        else:
            marker = runtime / "orchestella/desktop-clients.json"
            if marker.exists() and not marker.is_symlink() and marker.stat().st_size <= 4096:
                display = json.loads(marker.read_text()).get("display", "")
            else:
                display = ""
        if re.fullmatch(r"wayland-[0-9]+", display):
            path = runtime / display
            if path.exists():
                info = path.lstat()
                if stat.S_ISSOCK(info.st_mode) and info.st_uid == os.geteuid():
                    os.environ["WAYLAND_DISPLAY"] = display
                    return display
        time.sleep(0.05)
    raise ValueError("wayland-socket-timeout")


def desktop_apps(runtime):
    display = wait_wayland(runtime, inherited=True)
    stopped = threading.Event()
    def stop(signum, frame):
        stopped.set()
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    children = {}
    marker = runtime / "orchestella/desktop-clients.json"
    try:
        home = ordinary_user().pw_dir
        for name, arguments in (("foot", ["--config", home + "/.config/foot/foot.ini"]),
                                ("waybar", ["--config", home + "/.config/waybar/config"])):
            children[name] = subprocess.Popen(["/usr/bin/" + name, *arguments], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        temporary = marker.with_suffix(".temporary")
        with temporary.open("w") as output:
            os.chmod(temporary, 0o600)
            json.dump({**{name: child.pid for name, child in children.items()}, "display": display}, output)
        os.replace(temporary, marker)
        while not stopped.wait(0.1):
            if any(child.poll() is not None for child in children.values()):
                return 1
        return 0
    finally:
        marker.unlink(missing_ok=True)
        for child in children.values():
            if child.poll() is None:
                child.terminate()
        for child in children.values():
            try:
                child.wait(timeout=2)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=1)


def nic_relay(address, port):
    # The root-owned unit passes the fixed, explicitly selected guest NIC endpoint.
    ip = ipaddress.IPv4Address(address)
    if str(ip) != "192.168.127.15" or port != 18080:
        raise ValueError("relay-endpoint-refused")
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((address, port))
    server.listen(8)
    server.settimeout(0.3)
    active = set()
    mutex = threading.Lock()
    slots = threading.BoundedSemaphore(8)
    stopped = threading.Event()
    def stop(signum, frame):
        stopped.set()
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    def serve(client):
        target = None
        try:
            target = socket.create_connection(("127.0.0.1", 8080), timeout=3)
            client.settimeout(60)
            target.settimeout(60)
            with mutex:
                active.update((client, target))
            def copy(source, destination):
                try:
                    while not stopped.is_set():
                        data = source.recv(65536)
                        if not data:
                            break
                        destination.sendall(data)
                except OSError:
                    pass
                finally:
                    try:
                        destination.shutdown(socket.SHUT_WR)
                    except OSError:
                        pass
            outgoing = threading.Thread(target=copy, args=(client, target), daemon=True)
            outgoing.start()
            copy(target, client)
            client.close()
            target.close()
            outgoing.join(timeout=1)
        except OSError:
            pass
        finally:
            with mutex:
                active.discard(client)
                active.discard(target)
            client.close()
            if target:
                target.close()
            slots.release()
    try:
        while not stopped.is_set():
            try:
                client, _ = server.accept()
            except socket.timeout:
                continue
            if not slots.acquire(blocking=False):
                client.close()
                continue
            threading.Thread(target=serve, args=(client,), daemon=True).start()
    finally:
        server.close()
        with mutex:
            for connection in active:
                connection.close()
    return 0


def main():
    runtime = runtime_dir()
    if sys.argv[1:] == ["desktop-apps"]:
        return desktop_apps(runtime)
    if sys.argv[1:] == ["wayvnc"]:
        wait_wayland(runtime)
        socket_path = runtime / "orchestella/wayvnc.sock"
        if socket_path.is_symlink():
            return 1
        os.execv("/usr/bin/wayvnc", ["wayvnc", "--config", ordinary_user().pw_dir + "/.config/orchestella/wayvnc.conf", "--max-fps", "15", "--unix-socket", str(socket_path)])
    if len(sys.argv) == 4 and sys.argv[1] == "nic-relay":
        return nic_relay(sys.argv[2], int(sys.argv[3]))
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, IndexError):
        # The service journal must not contain paths, desktop content or credentials.
        sys.exit(1)
