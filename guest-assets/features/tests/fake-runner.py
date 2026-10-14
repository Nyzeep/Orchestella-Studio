#!/usr/bin/env python3
"""Synthetic package/service boundary adapter. Never installed in guest payload."""
import json
import os
from pathlib import Path
import sys
import time
import signal
import subprocess

root = Path(sys.argv[1])
args = sys.argv[2:]
fixture = json.loads((root / "fixture.json").read_text(encoding="utf-8"))
with (root / "commands.jsonl").open("a", encoding="utf-8") as output:
    output.write(json.dumps(args) + "\n")
text = " ".join(args)
if fixture.get("delay_on") and fixture["delay_on"] in text:
    if fixture.get("ignore_term"):
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        (root / "external-command.pid").write_text(str(os.getpid()))
    time.sleep(fixture.get("delay_seconds", 3))
if fixture.get("fail_on") and fixture["fail_on"] in text:
    print({"signature": "NO_PUBKEY repository signature verification failed", "disk": "No space left on device"}.get(fixture.get("failure"), "synthetic external command failed"))
    sys.exit(1)


def path(name):
    if name.startswith(str(root)):
        return Path(name)
    return root / name.lstrip("/")


def store(file, value):
    file.parent.mkdir(parents=True, exist_ok=True)
    temporary = file.with_suffix(".temporary")
    temporary.write_text(json.dumps(value), encoding="utf-8")
    os.replace(temporary, file)


command = Path(args[0]).name
if command == "apt-get":
    if "install" in args:
        packages = fixture.get("packages", {})
        for package in args[args.index("install") + 1:]:
            packages[package] = {"labwc": "0.8.3-1", "wayvnc": "0.9.1-1", "wlr-randr": "0.4.1-1"}.get(package, "1.0")
        fixture["packages"] = packages
        store(root / "fixture.json", fixture)
elif command == "dpkg-query":
    package = args[-1]
    if package not in fixture.get("packages", {}) or package in fixture.get("missing_packages", []):
        sys.exit(1)
    print("install ok installed\t" + fixture["packages"][package])
elif command == "dpkg":
    sys.exit(0)
elif command == "useradd":
    file = path("/etc/passwd")
    file.write_text("orchestella:x:1000:1000::/home/orchestella:/bin/bash\n", encoding="utf-8")
    path("/home/orchestella").mkdir(parents=True, exist_ok=True)
elif command == "usermod":
    for option, file in (("--add-subuids", "/etc/subuid"), ("--add-subgids", "/etc/subgid")):
        if option in args:
            start, end = map(int, args[args.index(option) + 1].split("-"))
            with path(file).open("a", encoding="utf-8") as output:
                output.write("%s:%d:%d\n" % (args[-1], start, end - start + 1))
elif command == "systemctl":
    operation = args[1]
    services = fixture.get("services", {})
    name = args[-1]
    if operation in ("start", "restart"):
        if name == "orchestella-code-server.service" and not services.get(name) and fixture.get("http_port"):
            process = subprocess.Popen([sys.executable, "-B", str(Path(__file__).with_name("fixture-http.py")), str(root)], stdin=subprocess.DEVNULL,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            fixture["http_pid"] = process.pid
        services[name] = True
        if name.startswith("user-runtime-dir@"):
            path("/run/user/1000").mkdir(parents=True, exist_ok=True)
        if name == "orchestella-desktop-compositor.service":
            path("/run/user/1000/wayland-0").touch()
            store(path("/run/user/1000/orchestella/desktop-clients.json"), {"display": "wayland-0", "foot": 1, "waybar": 2})
        if name == "orchestella-desktop-wayvnc.service":
            path("/run/user/1000/orchestella/wayvnc.sock").touch()
        if name == "orchestella-code-server-relay.service":
            tcp = path("/proc/net/tcp")
            tcp.parent.mkdir(parents=True, exist_ok=True)
            tcp.write_text("sl local_address rem_address st tx_queue tr retrnsmt uid timeout inode\n0: 0F7FA8C0:46A0 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 1\n")
    elif operation == "stop":
        services[name] = False
        if name == "orchestella-code-server-relay.service":
            path("/proc/net/tcp").unlink(missing_ok=True)
        if name == "orchestella-code-server.service" and fixture.get("http_pid"):
            try:
                os.kill(fixture["http_pid"], signal.SIGTERM)
            except OSError:
                pass
            fixture.pop("http_pid", None)
    elif operation == "is-active":
        if fixture.get("stop_during_probe") == name:
            fixture.pop("stop_during_probe")
            store(root / "fixture.json", fixture)
            controller = Path(__file__).parents[1] / "profilectl.py"
            stopping = subprocess.Popen([sys.executable, "-B", str(controller), "--test-root", str(root), "--test-runner", str(Path(__file__)),
                                          "stop", "desktop-labwc"], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                         start_new_session=True)
            stopping.wait(timeout=5)
        print("active" if services.get(name) else "inactive")
        sys.exit(0 if services.get(name) else 3)
    fixture["services"] = services
    store(root / "fixture.json", fixture)
elif command == "runuser":
    nested = args[args.index("--") + 1:]
    if nested and nested[0] == "env":
        nested = nested[1:]
        while nested and "=" in nested[0]:
            nested = nested[1:]
    nested_command = Path(nested[0]).name
    if nested_command == "wayland-info":
        print("zwlr_virtual_pointer_manager_v1 zwp_virtual_keyboard_manager_v1 zwlr_screencopy_manager_v1" if not fixture.get("missing_protocol") else "wl_compositor")
    elif nested_command == "grim":
        capture = path(nested[-1])
        capture.write_bytes(b"P6\n2 2\n255\n" + bytes([0, 0, 0, 255, 255, 255, 0, 255, 0, 255, 0, 0]))
    elif nested_command == "unshare":
        print("userns verified")
    elif nested_command in ("python3", "python"):
        print(json.dumps({"seccomp": True, "cgroup2": True, "overlay": "native"}))
    elif nested_command == "podman":
        if "info" in nested:
            print(json.dumps({"host": {"security": {"rootless": not fixture.get("rootful"), "seccompEnabled": True}, "cgroupVersion": "v2"}, "store": {"graphDriverName": "overlay"}}))
        elif "unshare" in nested:
            print("0 1000 1\n1 100000 65536" if "cat" in nested else json.dumps({"seccomp": True, "cgroup2": True, "overlay": "native"}))
        elif "port" in nested:
            print("127.0.0.1:" + str(fixture.get("podman_port", 1)))
        elif "run" in nested:
            if "--publish" in nested:
                published = nested[nested.index("--publish") + 1]
                port = int(published.split(":")[1])
                name = nested[nested.index("--name") + 1]
                process = subprocess.Popen([sys.executable, "-B", str(Path(__file__).with_name("fixture-port.py")), str(port)],
                                           stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
                fixture.setdefault("containers", {})[name] = process.pid
                fixture["podman_port"] = port
                store(root / "fixture.json", fixture)
            elif "--volume" in nested:
                volume = nested[nested.index("--volume") + 1].rsplit(":", 2)[0]
                print((Path(volume) / "probe.txt").read_text(), end="")
            else:
                print("hello from synthetic rootless container")
        elif "rm" in nested:
            pid = fixture.get("containers", {}).pop(nested[-1], None)
            if pid:
                try:
                    os.kill(pid, signal.SIGTERM)
                except OSError:
                    pass
                store(root / "fixture.json", fixture)
    elif nested_command == "docker":
        print("Docker version 28.0.0 (synthetic CLI)")
elif command == "held-stdout":
    child = subprocess.Popen([sys.executable, "-B", "-c", "import time; time.sleep(3)"], stdin=subprocess.DEVNULL)
    (root / "stdout-descendant.pid").write_text(str(child.pid))
    if fixture.get("cancel_held_stdout"):
        (root / "var/lib/orchestella/features" / ("f" * 32 + ".cancel")).write_text("{}\n")
    print("synthetic leader exits while child owns stdout", flush=True)
    sys.exit(0)
elif command == "ip":
    print("2: eth0 inet 192.168.127.15/24 scope global eth0")
else:
    print("unsupported synthetic command")
    sys.exit(2)
