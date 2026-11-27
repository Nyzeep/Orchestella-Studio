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


def systemctl_fixture(service_args):
    # Same synthetic manager behavior for root and --user scope; the unit names are
    # unique across managers, so one services map models both.
    service_args = [argument for argument in service_args if argument != "--user"]
    operation = service_args[1]
    services = fixture.get("services", {})
    name = service_args[-1]
    if operation in ("start", "restart"):
        if name == "orchestella-code-server.service" and not services.get(name) and fixture.get("http_port"):
            process = subprocess.Popen([sys.executable, "-B", str(Path(__file__).with_name("fixture-http.py")), str(root)], stdin=subprocess.DEVNULL,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            fixture["http_pid"] = process.pid
        services[name] = True
        if name.startswith("user-runtime-dir@"):
            path("/run/user/1000").mkdir(parents=True, exist_ok=True)
        if name.startswith("user@"):
            path("/run/user/1000").mkdir(parents=True, exist_ok=True)
        if name == "orchestella-desktop-compositor.service":
            path("/run/user/1000/wayland-0").touch()
            store(path("/run/user/1000/orchestella/desktop-clients.json"), {"display": "wayland-0", "foot": 1, "waybar": 2})
        if name == "orchestella-desktop-wayvnc.service":
            path("/run/user/1000/orchestella/wayvnc.sock").touch()
        if name in ("orchestella-audio-relay.service", "orchestella-desktop-pulseaudio.service"):
            # DW-08/W4-03 synthetic audio data plane: the PulseAudio simple-protocol
            # listener on 127.0.0.1:9104 owned by the ordinary user, observed
            # passively through the synthetic /proc/net/tcp like the code-server
            # relay. no_audio_endpoint models a restart-looping relay whose guest
            # endpoint never appears; the audio fields then stay unverified.
            if not fixture.get("no_audio_endpoint"):
                tcp = path("/proc/net/tcp")
                tcp.parent.mkdir(parents=True, exist_ok=True)
                existing = tcp.read_text() if tcp.exists() else "sl local_address rem_address st tx_queue tr retrnsmt uid timeout inode\n"
                if "0100007F:2390" not in existing:
                    tcp.write_text(existing + "0: 0100007F:2390 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 1\n")
        if name == "orchestella-code-server-relay.service":
            tcp = path("/proc/net/tcp")
            tcp.parent.mkdir(parents=True, exist_ok=True)
            tcp.write_text("sl local_address rem_address st tx_queue tr retrnsmt uid timeout inode\n0: 0F7FA8C0:46A0 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 1\n")
    elif operation == "stop":
        services[name] = False
        if name in ("orchestella-code-server-relay.service", "orchestella-desktop-pulseaudio.service"):
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


if command == "apt-get":
    if "install" in args:
        packages = fixture.get("packages", {})
        defaults = {
            "labwc": "0.8.3-1", "wayvnc": "0.9.1-1", "wlr-randr": "0.4.1-1",
            "docker.io": "26.1.5+dfsg1-9+deb13u1", "docker-cli": "26.1.5+dfsg1-9+deb13u1",
            "containerd": "1.7.24~ds1-6", "runc": "1.1.15+ds1-2",
            "iptables": "1.8.11-2", "nftables": "1.1.3-1", "ca-certificates": "20250419", "python3": "3.13.3-1",
        }
        defaults.update(fixture.get("package_versions", {}))
        for package in args[args.index("install") + 1:]:
            if package.startswith("-"):
                continue
            packages[package] = defaults.get(package, "1.0")
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
    systemctl_fixture(args)
elif command == "loginctl":
    # DW-03: synthetic logind. fail_linger models an unavailable/insufficient
    # logind; linger_untracked models enable-linger returning success without
    # actually persisting the linger flag (proof mismatch).
    if fixture.get("fail_linger"):
        print("synthetic logind unavailable")
        sys.exit(1)
    if "enable-linger" in args:
        linger = path("/var/lib/systemd/linger") / args[-1]
        linger.parent.mkdir(parents=True, exist_ok=True)
        if not fixture.get("linger_untracked"):
            linger.touch()
    elif "show-user" in args:
        linger = path("/var/lib/systemd/linger") / args[args.index("show-user") + 1]
        print("Linger=%s" % ("yes" if linger.exists() else "no"))
elif command == "runuser":
    nested = args[args.index("--") + 1:]
    if nested and nested[0] == "env":
        nested = nested[1:]
        while nested and "=" in nested[0]:
            nested = nested[1:]
    nested_command = Path(nested[0]).name
    if nested_command == "systemctl":
        systemctl_fixture(nested)
    elif nested_command == "wayland-info":
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
        print("Docker version 26.1.5+dfsg1, build a72d7cd")
elif command == "docker":
    sub = args[1] if len(args) > 1 else ""
    if sub == "--version":
        print("Docker version 26.1.5+dfsg1, build a72d7cd")
    elif sub == "info":
        if fixture.get("docker_daemon_down") or not fixture.get("services", {}).get("docker.service"):
            print("Client: Docker Engine - Community\nCannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?")
            sys.exit(1)
        sec_opts = [] if fixture.get("docker_seccomp_off") else (
            ["name=seccomp,profile=unconfined"] if fixture.get("docker_seccomp_unconfined")
            else ["name=seccomp,profile=builtin", "name=cgroupns"]
        )
        print(json.dumps({
            "ServerVersion": "26.1.5+dfsg1",
            "Driver": fixture.get("docker_storage_driver", "overlay2"),
            "CgroupVersion": fixture.get("docker_cgroup_version", "2"),
            "CgroupDriver": "systemd",
            "SecurityOptions": sec_opts,
            "DefaultRuntime": "runc",
        }))
    elif sub == "volume":
        op = args[2] if len(args) > 2 else ""
        vol = args[-1]
        if op == "create":
            if fixture.get("docker_volume_fail"):
                print("synthetic docker volume create failed")
                sys.exit(1)
            fixture.setdefault("docker_volumes", {})[vol] = ""
            store(root / "fixture.json", fixture)
        elif op == "rm":
            fixture.get("docker_volumes", {}).pop(vol, None)
            store(root / "fixture.json", fixture)
    elif sub == "run":
        if "--publish" in args:
            if fixture.get("docker_port_fail"):
                print("synthetic docker port failed")
                sys.exit(1)
            published = args[args.index("--publish") + 1]
            port = int(published.split(":")[1])
            name = args[args.index("--name") + 1]
            process = subprocess.Popen([sys.executable, "-B", str(Path(__file__).with_name("fixture-port.py")), str(port)],
                                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            fixture.setdefault("docker_containers", {})[name] = process.pid
            fixture["docker_port"] = port
            store(root / "fixture.json", fixture)
        elif "--volume" in args or "-v" in args:
            flag = "--volume" if "--volume" in args else "-v"
            vol_spec = args[args.index(flag) + 1]
            vol_name = vol_spec.split(":", 1)[0]
            if fixture.get("docker_volume_fail") or vol_name not in fixture.get("docker_volumes", {}):
                print("synthetic docker volume missing")
                sys.exit(1)
            cmd_text = " ".join(args)
            if "orchestella-docker-volume-acceptance" in cmd_text and "cat " not in cmd_text:
                fixture["docker_volumes"][vol_name] = "orchestella-docker-volume-acceptance\n"
                store(root / "fixture.json", fixture)
            else:
                print(fixture["docker_volumes"].get(vol_name, ""), end="")
        elif "nslookup" in " ".join(args) or "https://" in " ".join(args):
            if fixture.get("docker_network_fail"):
                print("synthetic docker network/tls failed")
                sys.exit(1)
            print("docker-net-tls-ok")
        else:
            print("memory_max=67108864 pids_max=32")
    elif sub == "rm":
        name = args[-1]
        pid = fixture.get("docker_containers", {}).pop(name, None)
        if pid:
            try:
                os.kill(pid, signal.SIGTERM)
            except OSError:
                pass
            store(root / "fixture.json", fixture)
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
