"""PID 1 of the isolated desktop fixture."""
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import time

DESKTOP_BIN = "/usr/local/bin/w405-desktop"
DISPLAY_RELAY = "/usr/local/bin/w405-display-relay.py"
RUNTIME = Path("/run/user/1000")
WAYLAND = "wayland-0"
GUEST_ENV = {
    "HOME": "/var/lib/desktop",
    "USER": "desktop",
    "LOGNAME": "desktop",
    "LANG": "C.UTF-8",
    "PATH": "/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    "XDG_RUNTIME_DIR": str(RUNTIME),
    "WAYLAND_DISPLAY": WAYLAND,
}
SINGLE_BAR_ID = ("0x1af4", "0x1110")
BAR_BYTES = 8 * 1024 * 1024


def note(event):
    print("W405BENCH " + event, flush=True)


def wait_for(predicate, stage, limit=20):
    until = time.monotonic() + limit
    while time.monotonic() < until:
        result = predicate()
        if result:
            return result
        time.sleep(0.05)
    raise RuntimeError(stage)


def port(name):
    for path in Path("/sys/class/virtio-ports").glob("vport*p*"):
        if path.joinpath("name").read_text().strip() == name:
            if not re.fullmatch(r"vport[0-9]+p[0-9]+", path.name):
                raise RuntimeError("virtio-name")
            node = Path("/dev") / path.name
            if node.exists():
                return node
    return None


def message(wire):
    data = bytearray()
    while len(data) < 256:
        byte = wire.read(1)
        if byte == b"\n":
            return data.decode("ascii")
        if not byte or not 32 <= byte[0] <= 126:
            raise RuntimeError("control-byte")
        data.extend(byte)
    raise RuntimeError("control-length")


def expect(wire, data):
    if message(wire) != data:
        raise RuntimeError("control-message")


def send(wire, data):
    wire.write((data + "\n").encode("ascii"))


def ordinary_process(args, **opts):
    return subprocess.Popen(args, user=1000, group=1000, extra_groups=(),
                            env=GUEST_ENV, stdin=subprocess.DEVNULL,
                            start_new_session=True, **opts)


def fixture_desktop(children, mode, token):
    for directory in (RUNTIME, RUNTIME / "orchestella"):
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chown(directory, 1000, 1000)
        directory.chmod(0o700)
    compositor = ordinary_process([DESKTOP_BIN, "labwc"])
    children.append(compositor)
    wait_for(lambda: RUNTIME.joinpath(WAYLAND).is_socket(), "labwc-socket")
    children.append(ordinary_process([DESKTOP_BIN, "geometry"]))
    if children[-1].wait(timeout=12) != 0:
        raise RuntimeError("headless-mode")
    if mode != "ivshmem":
        wayvnc = ordinary_process([DESKTOP_BIN, "wayvnc"])
        children.append(wayvnc)
        wait_for(lambda: RUNTIME.joinpath("orchestella/wayvnc.sock").is_socket(), "wayvnc-socket")
        relay_node = wait_for(lambda: port("orchestella.display"), "rfb-port")
        Path("/dev/virtio-ports/orchestella.display").symlink_to(relay_node)
        directory = Path("/etc/droidlayer")
        directory.mkdir(mode=0o700, exist_ok=True)
        token_path = directory / "bridge.token"
        fd = os.open(token_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC, 0o600)
        with os.fdopen(fd, "w", encoding="ascii") as token_file:
            token_file.write(token + "\n")
        children.append(subprocess.Popen(["python3", DISPLAY_RELAY, "--user", "desktop", "--transport", "virtio"],
                                         stdin=subprocess.DEVNULL, start_new_session=True))
    if compositor.poll() is not None:
        raise RuntimeError("labwc-stopped")


def ivshmem_capture(children, generation):
    devices = []
    for device in Path("/sys/bus/pci/devices").glob("*"):
        ids = tuple((device / name).read_text().strip() for name in ("vendor", "device"))
        if ids == SINGLE_BAR_ID:
            devices.append(device)
    if len(devices) != 1:
        raise RuntimeError("unique-ivshmem")
    device = devices[0]
    with (device / "config").open("rb", buffering=0) as config:
        command = int.from_bytes(config.read(6)[4:], "little")
    if not command & 2:
        (device / "enable").write_text("1", encoding="ascii")
    bar = os.open(device / "resource2", os.O_RDWR | os.O_CLOEXEC | os.O_NOFOLLOW)
    try:
        info = os.fstat(bar)
        if not stat.S_ISREG(info.st_mode) or info.st_size != BAR_BYTES or stat.S_IMODE(info.st_mode) != 0o600:
            raise RuntimeError("bar-shape")
        frame_path = wait_for(lambda: port("w405.frames"), "capture-port")
        frames = os.open(frame_path, os.O_RDWR | os.O_CLOEXEC | os.O_NOFOLLOW)
        try:
            if not stat.S_ISCHR(os.fstat(frames).st_mode):
                raise RuntimeError("capture-port-shape")
            children.append(ordinary_process(
                ["/usr/local/bin/capture", generation, str(bar), str(frames)],
                pass_fds=(bar, frames)))
        finally:
            os.close(frames)
    finally:
        os.close(bar)


def cpu():
    with Path("/proc/stat").open(encoding="ascii") as profile:
        counters = tuple(map(int, profile.readline().split()[1:9]))
    total = sum(counters)
    return total - counters[3] - counters[4], total


def benchmark(wire, children):
    send(wire, "HELLO")
    config = message(wire)
    values = re.fullmatch(r"CONFIG (raw|zrle|ivshmem) (static|terminal|drag) ([0-9a-f]{32}) ([0-9a-f]{64})", config)
    if values is None:
        raise RuntimeError("config")
    mode, load, generation, token = values.groups()
    fixture_desktop(children, mode, token)
    if mode == "ivshmem":
        ivshmem_capture(children, generation)
    send(wire, "STAGED")

    expect(wire, "GO")
    busy_start, total_start = cpu()
    children.append(ordinary_process(["/usr/local/bin/workload", load]))
    send(wire, "RUNNING")
    expect(wire, "STOP")
    if children[-1].poll() is not None:
        raise RuntimeError("workload-stopped")
    busy_end, total_end = cpu()
    send(wire, f"CPU {busy_end - busy_start} {total_end - total_start}")
    expect(wire, "QUIT")
    note("fixture_complete=pass")


def main():
    children = []
    try:
        control = wait_for(lambda: port("w405.control"), "control-port")
        with control.open("r+b", buffering=0) as wire:
            benchmark(wire, children)
    except (OSError, RuntimeError, subprocess.SubprocessError) as failure:
        detail = failure.args[0] if type(failure) is RuntimeError else type(failure).__name__
        detail = detail if re.fullmatch(r"[a-zA-Z0-9_-]+", str(detail)) else "error"
        suffix = f" errno={failure.errno}" if isinstance(failure, OSError) and failure.errno else ""
        note(f"fixture_error={detail}{suffix}")
    finally:
        for child in reversed(children):
            if child.poll() is None:
                try:
                    os.killpg(child.pid, signal.SIGTERM)
                    child.wait(timeout=2)
                except ProcessLookupError:
                    pass
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait()
        os.sync()
        os.execv("/sbin/poweroff", ["/sbin/poweroff", "-f"])


if __name__ == "__main__":
    main()
