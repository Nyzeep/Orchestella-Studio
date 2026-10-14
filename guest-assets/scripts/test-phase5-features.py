#!/usr/bin/env python3
"""External CLI contracts. Synthetic roots only; never a guest validation claim."""
import argparse
import hashlib
import io
import json
import socket
import tarfile
import os
from pathlib import Path
import subprocess
import sys
import time
import unittest
import uuid

FEATURES = Path(__file__).resolve().parents[1] / "features"
OUTPUT = None


class ProfileContracts(unittest.TestCase):
    def setUp(self):
        self.root = OUTPUT / "synthetic-roots" / uuid.uuid4().hex
        self.root.mkdir(parents=True)
        (self.root / ".orchestella-profile-test-root").write_text("synthetic-only\n", encoding="utf-8")
        (self.root / "etc").mkdir()
        (self.root / "etc/os-release").write_text('ID=debian\nVERSION_ID="13"\n', encoding="utf-8")
        (self.root / "fixture.json").write_text(json.dumps({"arch": "aarch64", "identity_mode": 0o600, "services": {"dbus.service": True, "ssh.service": True}}), encoding="utf-8")
        baseline = self.root / "var/lib/orchestella/provision"
        baseline.mkdir(parents=True)
        (baseline / "state").write_text("version=2\nstatus=ready\nstage=verified\n")
        (self.root / "proc/1").mkdir(parents=True)
        (self.root / "proc/1/comm").write_text("systemd\n")
        self.identity()

    def identity(self, username="orchestella", uid=1000, gid=1000):
        marker = self.root / "etc/orchestella/instance-user"
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.write_text(username + "\n", encoding="ascii")
        marker.chmod(0o600)
        home = "/home/" + username
        (self.root / "etc/passwd").write_text(
            "root:x:0:0::/root:/bin/bash\n" + username + ":x:%d:%d::%s:/bin/bash\n" % (uid, gid, home))
        (self.root / home.lstrip("/")).mkdir(parents=True, exist_ok=True)

    def test_configured_identity_drives_private_profile_paths_and_service_user(self):
        self.identity("developer")
        self.code_archive()
        self.ctl("install", "code-server")
        state = self.wait("code-server", {"installed", "failed"})
        self.assertEqual("installed", state["state"])
        unit = (self.root / "etc/systemd/system/orchestella-code-server.service").read_text()
        self.assertIn("User=developer\n", unit)
        self.assertIn("Environment=HOME=/home/developer\n", unit)
        self.assertIn("--config /home/developer/.config/code-server/config.yaml", unit)
        self.assertTrue((self.root / "home/developer/.config/code-server/config.yaml").is_file())
        self.assertFalse((self.root / "home/orchestella/.config/code-server/config.yaml").exists())
        self.assertNotIn("useradd", (self.root / "commands.jsonl").read_text())
        self.assertIn("developer:x:1000:1000:", (self.root / "etc/passwd").read_text())

    def test_unknown_root_missing_and_unsafe_identity_require_action_without_commands(self):
        marker = self.root / "etc/orchestella/instance-user"
        for value, reason in ((None, "ordinary-user-action-required"), ("root\n", "ordinary-user-action-required"),
                              ("developer;id\n", "unsafe-instance-identity"), ("developer", "ordinary-user-missing")):
            marker.unlink(missing_ok=True)
            if value is not None:
                marker.write_text(value)
            rc, state = self.ctl("install", "desktop-labwc")
            self.assertNotEqual(0, rc)
            self.assertEqual(reason, state["reason"])
            self.assertEqual("action-required", state["phase"])
            self.assertFalse((self.root / "commands.jsonl").exists())
        self.identity("developer")
        self.fixture(identity_mode=0o644)
        rc, state = self.ctl("install", "code-server")
        self.assertNotEqual(0, rc)
        self.assertEqual("unsafe-instance-identity", state["reason"])
        self.assertFalse((self.root / "commands.jsonl").exists())

    def test_custom_user_podman_uses_own_subids_uid_and_no_root_account(self):
        self.identity("developer")
        self.ctl("install", "podman")
        state = self.wait("podman", {"installed", "failed"})
        self.assertEqual("installed", state["state"])
        self.assertIn("developer:100000:65536", (self.root / "etc/subuid").read_text())
        self.ctl("start", "podman")
        state = self.wait("podman", {"ready", "failed"})
        self.assertEqual("ready", state["state"])
        calls = [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]
        sessions = [call for call in calls if call[0] == "runuser"]
        self.assertTrue(sessions)
        self.assertTrue(all(call[call.index("--user") + 1] == "developer" for call in sessions))
        probes = [call for call in sessions if any(str(arg).endswith("/rootless-probe.py") for arg in call)]
        self.assertEqual(3, len(probes))
        self.assertTrue(all(call[-1] == "1000" for call in probes))

    def test_exited_leader_stdout_descendant_obeys_deadline(self):
        result = subprocess.run([sys.executable, "-B", str(FEATURES / "tests/fault-process.py"),
                                 str(self.root), "held-stdout-timeout"], capture_output=True, text=True, timeout=5)
        self.assertEqual("", result.stderr)
        observation = json.loads(result.stdout)
        self.assertEqual("command-timeout", observation["reason"])
        self.assertLess(observation["elapsed"], 1.8)
        self.assertFalse(observation["descendant_alive"])
        self.assertEqual(0, observation["thread_delta"])
        self.assertEqual(0, result.returncode)

    def test_exited_leader_stdout_descendant_is_cancelled_and_released(self):
        result = subprocess.run([sys.executable, "-B", str(FEATURES / "tests/fault-process.py"),
                                 str(self.root), "held-stdout-cancel"], capture_output=True, text=True, timeout=5)
        self.assertEqual("", result.stderr)
        observation = json.loads(result.stdout)
        self.assertEqual("cancelled", observation["reason"])
        self.assertLess(observation["elapsed"], 1.8)
        self.assertFalse(observation["descendant_alive"])
        self.assertEqual(0, observation["thread_delta"])
        self.assertEqual(0, result.returncode)

    def test_unavailable_status_storage_retains_safe_job_schema(self):
        result = subprocess.run([sys.executable, "-B", str(FEATURES / "tests/fault-process.py"),
                                 str(self.root), "status-io"], capture_output=True, text=True, timeout=5)
        self.assertEqual("", result.stderr)
        state = json.loads(result.stdout)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("io-failed", state["reason"])
        self.assertEqual("failed", state["state"])
        self.assertIsInstance(state["job_active"], bool)
        self.assertFalse(state["job_active_verified"])
        self.assertFalse(state["installed"])
        self.assertFalse(state["authenticationConfigured"])

    def ctl(self, *args):
        result = subprocess.run(
            [sys.executable, "-B", str(FEATURES / "profilectl.py"),
             "--test-root", str(self.root), "--test-runner", str(FEATURES / "tests/fake-runner.py"), *args],
            capture_output=True, text=True, timeout=15,
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"})
        self.assertEqual("", result.stderr, result.stderr)
        self.assertLessEqual(len(result.stdout.encode()), 4096)
        self.assertEqual(1, len(result.stdout.splitlines()), result.stdout)
        return result.returncode, json.loads(result.stdout)

    def fixture(self, **changes):
        path = self.root / "fixture.json"
        value = json.loads(path.read_text(encoding="utf-8"))
        value.update(changes)
        path.write_text(json.dumps(value), encoding="utf-8")

    def wait(self, profile, expected):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            rc, value = self.ctl("status", profile)
            if value["state"] in expected:
                return value
            time.sleep(0.04)
        self.fail("background job did not finish: " + repr(value))

    def test_signature_failure_is_recoverable_and_never_ready(self):
        self.fixture(fail_on="apt-get update", failure="signature")
        rc, value = self.ctl("install", "desktop-labwc")
        self.assertEqual(0, rc)
        self.assertEqual("installing", value["state"])
        value = self.wait("desktop-labwc", {"failed"})
        self.assertEqual("package-signature-failed", value["reason"])
        self.assertFalse(value["installed"])
        self.fixture(fail_on="")
        rc, value = self.ctl("install", "desktop-labwc")
        self.assertEqual(0, rc)
        value = self.wait("desktop-labwc", {"installed"})
        self.assertNotEqual("ready", value["state"])

    def test_desktop_requires_capture_protocols_and_current_frame_generation(self):
        self.ctl("install", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})
        rc, value = self.ctl("start", "desktop-labwc")
        self.assertEqual(0, rc)
        generation = value["generation"]
        value = self.wait("desktop-labwc", {"awaiting-frame", "failed"})
        self.assertEqual("awaiting-frame", value["state"])
        self.assertEqual("verified", value["capture"])
        self.assertEqual("verified", value["protocols"])
        rc, rejected = self.ctl("confirm-frame", "desktop-labwc", "0" * 32)
        self.assertNotEqual(0, rc)
        self.assertEqual("stale-frame", rejected["reason"])
        rc, ready = self.ctl("confirm-frame", "desktop-labwc", generation)
        self.assertEqual(0, rc)
        self.assertEqual("ready", ready["state"])
        rc, repeated = self.ctl("start", "desktop-labwc")
        self.assertEqual(0, rc)
        self.assertEqual(generation, repeated["generation"])
        self.ctl("stop", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})
        rc, rejected = self.ctl("confirm-frame", "desktop-labwc", generation)
        self.assertNotEqual(0, rc)
        self.assertNotEqual("ready", rejected["state"])

    def code_archive(self, version="4.141.0", extra_files=0):
        archive = self.root / "code-server.tar.gz"
        top = "code-server-" + version + "-linux-arm64"
        with tarfile.open(archive, "w:gz") as tar:
            payload = b"#!/bin/sh\nexit 0\n"
            item = tarfile.TarInfo(top + "/bin/code-server")
            item.size = len(payload)
            item.mode = 0o755
            tar.addfile(item, io.BytesIO(payload))
            for number in range(extra_files):
                content = ("synthetic file %d\n" % number).encode()
                item = tarfile.TarInfo(top + "/lib/fixture-%d" % number)
                item.size = len(content)
                item.mode = 0o644
                tar.addfile(item, io.BytesIO(content))
        inputs = json.loads((FEATURES / "inputs.json").read_text())
        inputs["code_server"]["version"] = version
        inputs["code_server"]["url"] = "https://github.com/coder/code-server/releases/download/v{0}/code-server-{0}-linux-arm64.tar.gz".format(version)
        inputs["code_server"]["size"] = archive.stat().st_size
        inputs["code_server"]["sha256"] = hashlib.sha256(archive.read_bytes()).hexdigest()
        (self.root / "inputs.json").write_text(json.dumps(inputs))
        self.fixture(archive="code-server.tar.gz")
        return inputs

    def test_code_input_must_be_pinned_and_bytes_verified(self):
        inputs = self.code_archive()
        inputs["code_server"] = None
        (self.root / "inputs.json").write_text(json.dumps(inputs))
        self.ctl("install", "code-server")
        value = self.wait("code-server", {"failed"})
        self.assertEqual("missing-input-pin", value["reason"])
        self.assertFalse((self.root / "commands.jsonl").exists())
        inputs = self.code_archive()
        inputs["code_server"]["sha256"] = "0" * 64
        (self.root / "inputs.json").write_text(json.dumps(inputs))
        self.ctl("install", "code-server")
        value = self.wait("code-server", {"failed"})
        self.assertEqual("input-hash-mismatch", value["reason"])
        self.assertFalse(value["installed"])
        self.assertFalse((self.root / "opt/orchestella/code-server/4.141.0/bin/code-server").exists())
        self.code_archive()
        self.ctl("install", "code-server")
        value = self.wait("code-server", {"installed"})
        self.assertNotEqual("ready", value["state"])
        yaml = self.root / "home/orchestella/.config/code-server/config.yaml"
        password = yaml.read_text().split("password: ", 1)[1].splitlines()[0]
        self.assertEqual(64, len(password))
        self.assertNotIn(password, json.dumps(value))

    def test_rootless_info_is_not_container_run_acceptance(self):
        self.ctl("install", "podman")
        self.wait("podman", {"installed"})
        self.fixture(rootful=True)
        self.ctl("start", "podman")
        value = self.wait("podman", {"failed"})
        self.assertEqual("podman-not-rootless", value["reason"])
        self.assertEqual("unverified", value["container_run"])
        self.fixture(rootful=False, fail_on="podman run", failure="run")
        self.ctl("start", "podman")
        value = self.wait("podman", {"failed"})
        self.assertEqual("verified", value["container_info"])
        self.assertEqual("unverified", value["container_run"])
        self.assertNotEqual("ready", value["state"])

    def test_code_authenticated_http_listener_lifecycle_and_tamper(self):
        self.code_archive()
        with socket.socket() as available:
            available.bind(("127.0.0.1", 0))
            port = available.getsockname()[1]
        self.fixture(http_port=port)
        try:
            self.ctl("install", "code-server")
            self.wait("code-server", {"installed"})
            self.ctl("start", "code-server")
            value = self.wait("code-server", {"ready", "failed"})
            self.assertEqual("ready", value["state"])
            self.assertTrue(value["authenticationConfigured"])
            self.assertEqual("authenticated-http", value["health"])
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                pass
            rc, repeated = self.ctl("start", "code-server")
            self.assertEqual(value["generation"], repeated["generation"])
            yaml = self.root / "home/orchestella/.config/code-server/config.yaml"
            old = yaml.read_text()
            yaml.write_text(old.replace("auth: password", "auth: none"))
            rc, value = self.ctl("status", "code-server")
            self.assertEqual("auth-unverified", value["reason"])
            self.assertFalse(value["authenticationConfigured"])
            rc, value = self.ctl("expose", "code-server")
            self.assertNotEqual(0, rc)
            self.assertEqual("auth-unverified", value["reason"])
            yaml.write_text(old)
            self.ctl("stop", "code-server")
            self.wait("code-server", {"installed"})
            with self.assertRaises(OSError):
                socket.create_connection(("127.0.0.1", port), timeout=0.3)
            self.assertFalse((self.root / "run/user/1000/orchestella/code-server-relay.json").exists())
        finally:
            self.ctl("stop", "code-server")
            self.wait("code-server", {"installed", "failed"})

    def test_disk_full_and_missing_packages_do_not_install_or_start(self):
        self.fixture(free_bytes=0)
        self.ctl("install", "desktop-labwc")
        value = self.wait("desktop-labwc", {"failed"})
        self.assertEqual("disk-full", value["reason"])
        commands = (self.root / "commands.jsonl").read_text()
        self.assertNotIn("apt-get", commands)
        self.fixture(free_bytes=3 << 30, missing_packages=["labwc"])
        self.ctl("install", "desktop-labwc")
        value = self.wait("desktop-labwc", {"failed"})
        self.assertEqual("dependency-missing", value["reason"])
        self.assertFalse(value["installed"])

    def test_cancelled_install_holds_global_lock_until_child_stops(self):
        self.fixture(delay_on="apt-get update", delay_seconds=5)
        before = time.monotonic()
        rc, first = self.ctl("install", "desktop-labwc")
        self.assertEqual(0, rc)
        self.assertLess(time.monotonic() - before, 2)
        rc, repeated = self.ctl("install", "desktop-labwc")
        self.assertEqual(first["job_id"], repeated["job_id"])
        rc, denied = self.ctl("install", "podman")
        self.assertNotEqual(0, rc)
        self.assertEqual("busy", denied["reason"])
        rc, value = self.ctl("cancel", "desktop-labwc")
        self.assertEqual(0, rc)
        self.wait("desktop-labwc", {"cancelled"})
        deadline = time.monotonic() + 3
        while (self.root / "var/lib/orchestella/features/install.lock").exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertFalse((self.root / "var/lib/orchestella/features/install.lock").exists())
        self.fixture(delay_on="")
        self.ctl("install", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})

    def test_baseline_wait_and_cancel_never_race_package_manager(self):
        baseline = self.root / "var/lib/orchestella/provision/state"
        baseline.write_text("version=2\nstatus=running\n")
        self.ctl("install", "desktop-labwc")
        time.sleep(0.15)
        rc, value = self.ctl("status", "desktop-labwc")
        self.assertEqual("waiting-for-base", value["phase"])
        self.assertFalse((self.root / "commands.jsonl").exists())
        self.ctl("cancel", "desktop-labwc")
        self.wait("desktop-labwc", {"cancelled"})

    def test_user_configs_preserved_and_only_owned_units_upgraded(self):
        legacy_inputs = json.loads((FEATURES / "inputs.json").read_text())
        legacy_inputs["units_version"] = 1
        (self.root / "inputs.json").write_text(json.dumps(legacy_inputs))
        self.ctl("install", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})
        config = self.root / "home/orchestella/.config/labwc/rc.xml"
        config.write_text("<labwc_config><!-- user choice --></labwc_config>\n")
        inputs = json.loads((FEATURES / "inputs.json").read_text())
        inputs["units_version"] = 2
        (self.root / "inputs.json").write_text(json.dumps(inputs))
        self.ctl("install", "desktop-labwc")
        state = self.wait("desktop-labwc", {"installed"})
        self.assertEqual(2, state["units_version"])
        self.assertIn("user choice", config.read_text())
        unit = self.root / "etc/systemd/system/orchestella-desktop-compositor.service"
        self.assertTrue(unit.read_text().startswith("# Orchestella owned unit schema=2"))
        self.assertIn("User=orchestella", unit.read_text())
        self.assertNotIn("no-sandbox", unit.read_text())
        inputs["units_version"] = 3
        (self.root / "inputs.json").write_text(json.dumps(inputs))
        unit.write_text("# foreign unit\nExecStart=/bin/false\n")
        self.ctl("install", "desktop-labwc")
        state = self.wait("desktop-labwc", {"failed"})
        self.assertEqual("unit-collision", state["reason"])
        self.assertTrue(unit.read_text().startswith("# foreign unit"))

    def test_missing_protocol_or_capture_failure_cannot_confirm_frame(self):
        self.ctl("install", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})
        self.fixture(missing_protocol=True)
        self.ctl("start", "desktop-labwc")
        value = self.wait("desktop-labwc", {"failed"})
        self.assertEqual("wayland-protocol-unavailable", value["reason"])
        self.assertFalse(value["input"]["keyboard"])
        self.fixture(missing_protocol=False, fail_on="grim -t", failure="capture")
        self.ctl("start", "desktop-labwc")
        value = self.wait("desktop-labwc", {"failed"})
        self.assertEqual("capture-unverified", value["reason"])
        rc, value = self.ctl("confirm-frame", "desktop-labwc", value["generation"])
        self.assertNotEqual(0, rc)
        self.assertFalse(value["input"]["pointer"])

    def test_signed_python_bootstrap_waits_for_baseline_and_is_queryable(self):
        runner = FEATURES / "tests/fake-runner.py"
        os.chmod(runner, 0o755)
        def shell(action):
            args = ["bash", str(FEATURES / "bootstrap-python.sh"), "--test-root", self.root.as_posix(),
                    "--test-runner", runner.as_posix(), action, "desktop-labwc"]
            result = subprocess.run(args, capture_output=True, text=True, timeout=5,
                                    env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"})
            self.assertEqual("", result.stderr)
            return result.returncode, json.loads(result.stdout)
        baseline = self.root / "var/lib/orchestella/provision/state"
        baseline.write_text("version=2\nstatus=running\n")
        rc, value = shell("start")
        self.assertEqual(0, rc)
        self.assertEqual("waiting-for-base", value["phase"])
        self.assertFalse((self.root / "commands.jsonl").exists())
        rc, value = shell("status")
        self.assertEqual("installing", value["state"])
        baseline.write_text("version=2\nstatus=ready\n")
        state = self.wait("desktop-labwc", {"installed", "failed"})
        self.assertEqual("installed", state["state"])
        commands = [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]
        bootstrap = next(cmd for cmd in commands if "install" in cmd and cmd[-1] == "python3")
        self.assertIn("APT::Get::AllowUnauthenticated=false", bootstrap)
        self.assertFalse((self.root / "var/lib/orchestella/features/python-bootstrap.lock").exists())

    def test_podman_acceptance_checks_dns_tls_volume_port_then_removes_probe(self):
        self.ctl("install", "podman")
        self.wait("podman", {"installed"})
        self.ctl("start", "podman")
        state = self.wait("podman", {"ready", "failed"})
        self.assertEqual("ready", state["state"])
        for key in ("container_info", "container_run", "container_dns", "container_tls", "container_volume", "container_port"):
            self.assertEqual("verified", state[key])
        fixture = json.loads((self.root / "fixture.json").read_text())
        self.assertEqual({}, fixture.get("containers"))
        with self.assertRaises(OSError):
            socket.create_connection(("127.0.0.1", fixture["podman_port"]), timeout=0.3)
        commands = (self.root / "commands.jsonl").read_text()
        self.assertNotIn(":latest", commands)
        self.assertIn("sha256:", commands)
        self.assertIn("slirp4netns", commands)
        self.assertIn("unshare", commands)

    def test_unknown_distro_untrusted_sources_and_corrupt_state_fail_closed(self):
        (self.root / "etc/os-release").write_text('ID=ubuntu\nVERSION_ID="24.04"\n')
        rc, value = self.ctl("probe", "desktop-labwc")
        self.assertNotEqual(0, rc)
        self.assertEqual("unsupported-distro", value["reason"])
        (self.root / "etc/os-release").write_text('ID=debian\nVERSION_ID="13"\n')
        sources = self.root / "etc/apt"
        sources.mkdir()
        (sources / "sources.list").write_text("deb [trusted=yes] https://example.invalid/ signed main\n")
        self.ctl("install", "desktop-labwc")
        value = self.wait("desktop-labwc", {"failed"})
        self.assertEqual("unsafe-package-source", value["reason"])
        self.assertNotIn("apt-get", (self.root / "commands.jsonl").read_text())
        (self.root / "var/lib/orchestella/features/desktop-labwc.json").write_text("{broken")
        rc, value = self.ctl("status", "desktop-labwc")
        self.assertNotEqual(0, rc)
        self.assertEqual("corrupt-state", value["reason"])

    def test_archive_paths_and_unknown_download_endpoints_refused_before_extract(self):
        inputs = self.code_archive()
        inputs["code_server"]["url"] = "https://evil.invalid/code-server.tar.gz"
        (self.root / "inputs.json").write_text(json.dumps(inputs))
        self.ctl("install", "code-server")
        value = self.wait("code-server", {"failed"})
        self.assertEqual("invalid-input-pin", value["reason"])
        self.assertFalse((self.root / "commands.jsonl").exists())
        inputs = self.code_archive()
        archive = self.root / "code-server.tar.gz"
        with tarfile.open(archive, "w:gz") as tar:
            entry = tarfile.TarInfo("../../escape")
            entry.size = 4
            tar.addfile(entry, io.BytesIO(b"fail"))
        inputs["code_server"]["size"] = archive.stat().st_size
        inputs["code_server"]["sha256"] = hashlib.sha256(archive.read_bytes()).hexdigest()
        (self.root / "inputs.json").write_text(json.dumps(inputs))
        self.ctl("install", "code-server")
        value = self.wait("code-server", {"failed"})
        self.assertEqual("unsafe-input-archive", value["reason"])
        self.assertFalse(value["installed"])
        self.assertFalse((self.root.parent / "escape").exists())

    def test_display_relay_restarts_clean_eof_and_resize_invalidates_old_frame(self):
        self.ctl("install", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})
        relay = (self.root / "etc/systemd/system/orchestella-display-relay.service").read_text()
        self.assertIn("Restart=always\nRestartSec=2", relay)
        self.assertIn("KillMode=control-group", relay)
        self.assertNotIn("[Install]", relay)
        self.ctl("start", "desktop-labwc")
        before = self.wait("desktop-labwc", {"awaiting-frame"})
        rc, value = self.ctl("resize", "desktop-labwc", "4096", "4096", "1")
        self.assertNotEqual(0, rc)
        self.assertEqual("invalid-display-size", value["reason"])
        rc, value = self.ctl("resize", "desktop-labwc", "1280", "720", "1")
        self.assertEqual(0, rc)
        self.assertNotEqual(before["generation"], value["generation"])
        self.wait("desktop-labwc", {"awaiting-frame"})
        rc, value = self.ctl("confirm-frame", "desktop-labwc", before["generation"])
        self.assertNotEqual(0, rc)
        self.assertEqual("stale-frame", value["reason"])

    def test_code_alive_port_without_auth_never_ready(self):
        self.code_archive()
        with socket.socket() as available:
            available.bind(("127.0.0.1", 0))
            port = available.getsockname()[1]
        self.fixture(http_port=port, reject_auth=True)
        try:
            self.ctl("install", "code-server")
            self.wait("code-server", {"installed"})
            self.ctl("start", "code-server")
            value = self.wait("code-server", {"failed"})
            self.assertEqual("code-server-auth-failed", value["reason"])
            self.assertFalse(value["authenticationConfigured"])
            with self.assertRaises(OSError):
                socket.create_connection(("127.0.0.1", port), timeout=0.3)
        finally:
            self.ctl("stop", "code-server")
            self.wait("code-server", {"installed", "failed"})

    def test_confirm_frame_cannot_overwrite_stop_during_service_probe(self):
        self.ctl("install", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})
        self.ctl("start", "desktop-labwc")
        before = self.wait("desktop-labwc", {"awaiting-frame"})
        self.fixture(stop_during_probe="orchestella-display-relay.service")
        rc, value = self.ctl("confirm-frame", "desktop-labwc", before["generation"])
        self.assertNotEqual(0, rc)
        self.assertNotEqual("ready", value["state"])
        self.assertIn(value["reason"], ("stale-frame", "service-not-active", "command-timeout"))
        state = self.wait("desktop-labwc", {"installed"})
        self.assertFalse(state["input"]["keyboard"])

    def test_code_expose_unexpose_and_dead_relay_remove_stale_flag(self):
        self.code_archive()
        with socket.socket() as available:
            available.bind(("127.0.0.1", 0))
            port = available.getsockname()[1]
        self.fixture(http_port=port)
        try:
            self.ctl("install", "code-server")
            self.wait("code-server", {"installed"})
            self.ctl("start", "code-server")
            self.wait("code-server", {"ready"})
            rc, accepted = self.ctl("expose", "code-server")
            self.assertEqual(0, rc)
            value = self.wait("code-server", {"ready"})
            self.assertTrue(value["exposed"])
            services = json.loads((self.root / "fixture.json").read_text())["services"]
            services["orchestella-code-server-relay.service"] = False
            self.fixture(services=services)
            (self.root / "proc/net/tcp").unlink()
            rc, value = self.ctl("status", "code-server")
            self.assertFalse(value["exposed"])
            self.assertEqual("guest-relay-unavailable", value["reason"])
            self.assertFalse((self.root / "run/user/1000/orchestella/code-server-relay.json").exists())
            self.ctl("expose", "code-server")
            value = self.wait("code-server", {"ready"})
            self.assertTrue(value["exposed"])
            self.ctl("unexpose", "code-server")
            value = self.wait("code-server", {"ready"})
            self.assertFalse(value["exposed"])
            self.assertFalse((self.root / "proc/net/tcp").exists())
        finally:
            self.ctl("stop", "code-server")
            self.wait("code-server", {"installed", "failed"})

    def test_bootstrap_cancel_reaps_real_child_ignoring_term_before_unlock(self):
        if os.name != "posix":
            self.skipTest("actual process group contract is run under WSL Linux")
        runner = FEATURES / "tests/fake-runner.py"
        os.chmod(runner, 0o755)
        self.fixture(delay_on="apt-get", delay_seconds=30, ignore_term=True)
        args = ["bash", str(FEATURES / "bootstrap-python.sh"), "--test-root", str(self.root), "--test-runner", str(runner)]
        start = subprocess.run([*args, "start", "desktop-labwc"], capture_output=True, text=True, timeout=5)
        self.assertEqual(0, start.returncode)
        deadline = time.monotonic() + 5
        pidfile = self.root / "external-command.pid"
        while not pidfile.exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertTrue(pidfile.exists())
        pid = int(pidfile.read_text())
        cancelled = subprocess.run([*args, "cancel", "desktop-labwc"], capture_output=True, text=True, timeout=5)
        self.assertEqual(0, cancelled.returncode)
        lock = self.root / "var/lib/orchestella/features/python-bootstrap.lock"
        deadline = time.monotonic() + 8
        while lock.exists() and time.monotonic() < deadline:
            time.sleep(0.1)
        self.assertFalse(lock.exists(), "lock cannot be released while ignoring-TERM command lives")
        statfile = Path("/proc") / str(pid) / "stat"
        if statfile.exists():
            self.assertEqual("Z", statfile.read_text().rsplit(")", 1)[1].split()[0])
        status = subprocess.run([*args, "status", "desktop-labwc"], capture_output=True, text=True, timeout=5)
        value = json.loads(status.stdout)
        self.assertEqual("cancelled", value["state"])
        self.assertEqual("stopped", value["phase"])

    def test_weak_user_credentials_are_preserved_and_never_accepted(self):
        self.code_archive()
        for password in ("0" * 64, "01234567" * 8, "0123456789abcdef" * 4):
            config = self.root / "home/orchestella/.config/code-server/config.yaml"
            config.parent.mkdir(parents=True, exist_ok=True)
            text = "bind-addr: 127.0.0.1:8080\nauth: password\npassword: " + password + "\ncert: false\n"
            config.write_text(text)
            self.ctl("install", "code-server")
            value = self.wait("code-server", {"failed"})
            self.assertEqual("weak-service-credential", value["reason"])
            self.assertFalse(value["authenticationConfigured"])
            self.assertEqual(text, config.read_text())
            rc, value = self.ctl("expose", "code-server")
            self.assertNotEqual(0, rc)
            self.assertEqual("auth-unverified", value["reason"])

    def test_avf_marker_requires_actual_transport_probe_and_boot_rejects_old_proof(self):
        (self.root / "proc/cmdline").write_text("console=hvc0 droidlayer.vsock_control_port=9100\n")
        self.ctl("install", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})
        relay = (self.root / "etc/systemd/system/orchestella-display-relay.service").read_text()
        self.assertIn("--transport vsock", relay)
        self.ctl("start", "desktop-labwc")
        value = self.wait("desktop-labwc", {"failed"})
        self.assertEqual("display-transport-unsupported", value["reason"])
        self.fixture(vsock_supported=True)
        self.ctl("start", "desktop-labwc")
        before = self.wait("desktop-labwc", {"awaiting-frame"})
        self.fixture(boot_id="other-synthetic-boot")
        rc, value = self.ctl("confirm-frame", "desktop-labwc", before["generation"])
        self.assertNotEqual(0, rc)
        self.assertEqual("stale-frame", value["reason"])
        rc, value = self.ctl("status", "desktop-labwc")
        self.assertEqual("installed", value["state"])
        self.assertEqual("restart-required", value["reason"])

    def test_atomic_publish_disk_full_never_orphans_lock_or_background_worker(self):
        for target in ("command.lock/owner.json", "features/desktop-labwc.json", "install.lock/owner.json"):
            with self.subTest(target=target):
                result = subprocess.run([sys.executable, "-B", str(FEATURES / "tests/fault-process.py"), str(self.root), target],
                                        capture_output=True, text=True, timeout=5)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", result.stderr)
                status = json.loads(result.stdout)
                self.assertEqual("disk-full", status["reason"])
                self.assertFalse((self.root / "var/lib/orchestella/features/command.lock").exists())
                self.assertFalse((self.root / "var/lib/orchestella/features/install.lock").exists())
                if (self.root / "launched.pid").exists() and os.name == "posix":
                    statfile = Path("/proc") / (self.root / "launched.pid").read_text() / "stat"
                    self.assertFalse(statfile.exists(), "failed owner publication must kill and reap spawned worker")
                rc, status = self.ctl("status", "desktop-labwc")
                self.assertEqual(0, rc)
                self.assertNotEqual("ready", status["state"])
        self.ctl("install", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})

    def test_root_primary_or_supplementary_group_is_never_an_ordinary_user(self):
        passwd = self.root / "etc/passwd"
        original = "orchestella:x:1000:0::/home/orchestella:/bin/bash\n"
        passwd.write_text(original)
        self.ctl("install", "desktop-labwc")
        state = self.wait("desktop-labwc", {"failed"})
        self.assertEqual("unsafe-ordinary-user", state["reason"])
        self.assertEqual(original, passwd.read_text())
        passwd.write_text(original.replace(":1000:0:", ":1000:1000:"))
        self.fixture(groups=[1000, 0])
        self.ctl("install", "desktop-labwc")
        state = self.wait("desktop-labwc", {"failed"})
        self.assertEqual("unsafe-ordinary-user", state["reason"])
        self.assertFalse(state["installed"])

    def test_old_empty_lock_recovers_but_new_owner_creation_window_is_busy(self):
        lock = self.root / "var/lib/orchestella/features/command.lock"
        lock.mkdir(parents=True)
        rc, status = self.ctl("install", "desktop-labwc")
        self.assertNotEqual(0, rc)
        self.assertEqual("busy", status["reason"])
        old = time.time() - 60
        os.utime(lock, (old, old))
        rc, status = self.ctl("install", "desktop-labwc")
        self.assertEqual(0, rc)
        self.wait("desktop-labwc", {"installed"})

    def test_editor_unit_keeps_uidmap_helpers_usable_without_root_or_autoenable(self):
        self.code_archive()
        self.ctl("install", "code-server")
        self.wait("code-server", {"installed"})
        unit = (self.root / "etc/systemd/system/orchestella-code-server.service").read_text()
        self.assertNotIn("NoNewPrivileges=yes", unit)
        self.assertIn("User=orchestella", unit)
        self.assertIn("UMask=0077", unit)
        self.assertNotIn("--no-sandbox", unit)
        self.assertNotIn("[Install]", unit)
        self.assertIn("--cookie-suffix", unit)
        config = (self.root / "home/orchestella/.config/code-server/config.yaml").read_text()
        self.assertIn("bind-addr: 127.0.0.1:8080", config)
        self.assertIn("auth: password", config)

    def test_explicit_install_upgrades_legacy_editor_unit_without_replacing_private_config(self):
        current_inputs = self.code_archive()
        legacy_inputs = json.loads(json.dumps(current_inputs))
        legacy_inputs["units_version"] = 1
        (self.root / "inputs.json").write_text(json.dumps(legacy_inputs))
        self.ctl("install", "code-server")
        legacy_state = self.wait("code-server", {"installed"})
        self.assertEqual(1, legacy_state["units_version"])
        unit = self.root / "etc/systemd/system/orchestella-code-server.service"
        unit.write_text(unit.read_text().replace("[Service]\n", "[Service]\nNoNewPrivileges=yes\n"))
        self.assertTrue(unit.read_text().startswith("# Orchestella owned unit schema=1\n"))
        self.assertIn("NoNewPrivileges=yes", unit.read_text())
        config = self.root / "home/orchestella/.config/code-server/config.yaml"
        config.write_text("# Existing user configuration: keep byte-for-byte.\n" + config.read_text())
        preserved_config = config.read_bytes()
        preserved_cookie = (self.root / "var/lib/orchestella/features/code-server-cookie.json").read_bytes()
        installed_input = self.root / "opt/orchestella/code-server/4.141.0/.orchestella-input.json"
        preserved_input = installed_input.read_bytes()
        (self.root / "inputs.json").write_text(json.dumps(current_inputs))
        rc, accepted = self.ctl("install", "code-server")
        self.assertEqual(0, rc)
        self.assertEqual("installing", accepted["state"], "units_version must force explicit upgrade instead of early return")
        upgraded = self.wait("code-server", {"installed", "failed"})
        self.assertEqual("installed", upgraded["state"])
        self.assertEqual(2, upgraded["units_version"])
        self.assertFalse(upgraded["authenticationConfigured"])
        self.assertTrue(unit.read_text().startswith("# Orchestella owned unit schema=2\n"))
        self.assertNotIn("NoNewPrivileges=yes", unit.read_text())
        self.assertIn("User=orchestella", unit.read_text())
        self.assertNotIn("[Install]", unit.read_text())
        self.assertEqual(preserved_config, config.read_bytes())
        self.assertEqual(preserved_cookie, (self.root / "var/lib/orchestella/features/code-server-cookie.json").read_bytes())
        self.assertEqual(preserved_input, installed_input.read_bytes())
        self.assertEqual("4.141.0", current_inputs["code_server"]["version"])
        commands_before = (self.root / "commands.jsonl").read_bytes()
        rc, repeated = self.ctl("install", "code-server")
        self.assertEqual(0, rc)
        self.assertEqual("installed", repeated["state"])
        self.assertEqual(upgraded["job_id"], repeated["job_id"])
        self.assertEqual(commands_before, (self.root / "commands.jsonl").read_bytes())

    def assert_staging_fault_recovers(self, fault, reason):
        self.code_archive()
        with socket.socket() as unused:
            unused.bind(("127.0.0.1", 0))
            self.fixture(http_port=unused.getsockname()[1])
        self.ctl("install", "code-server")
        self.wait("code-server", {"installed"})
        base = self.root / "opt/orchestella/code-server"
        previous = base / "4.141.0"
        def snapshot(directory):
            return {file.relative_to(directory).as_posix(): file.read_bytes() for file in directory.rglob("*") if file.is_file()}
        previous_bytes = snapshot(previous)
        config = self.root / "home/orchestella/.config/code-server/config.yaml"
        private_bytes = config.read_bytes()
        cookie = self.root / "var/lib/orchestella/features/code-server-cookie.json"
        cookie_bytes = cookie.read_bytes()
        other_stage = base / (".staging-" + "0" * 32)
        other_stage.mkdir()
        (other_stage / "other-job").write_bytes(b"preserve other exact directory\n")
        pin = self.code_archive(version="4.141.1", extra_files=3)["code_server"]
        result = subprocess.run([sys.executable, "-B", str(FEATURES / "tests/fault-process.py"), str(self.root), fault],
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stderr)
        accepted = json.loads(result.stdout)
        self.assertEqual("installing", accepted["state"])
        state = self.wait("code-server", {"failed", "cancelled"})
        self.assertEqual(reason, state["reason"])
        deadline = time.monotonic() + 10
        while state["job_active"] and time.monotonic() < deadline:
            time.sleep(0.05)
            _, state = self.ctl("status", "code-server")
        self.assertFalse(state["job_active"])
        self.assertFalse((base / (".staging-" + accepted["job_id"])).exists(), "failed exact job staging must be removed")
        self.assertFalse((base / "4.141.1").exists(), "failed publication must not install partial target")
        self.assertEqual(previous_bytes, snapshot(previous))
        self.assertEqual(private_bytes, config.read_bytes())
        self.assertEqual(cookie_bytes, cookie.read_bytes())
        self.assertEqual(b"preserve other exact directory\n", (other_stage / "other-job").read_bytes())
        cache = self.root / "var/cache/orchestella/features/code-server-4.141.1.tar.gz"
        self.assertTrue(cache.is_file(), "validated archive is retained for retry")
        self.assertEqual(pin["size"], cache.stat().st_size)
        self.assertEqual(pin["sha256"], hashlib.sha256(cache.read_bytes()).hexdigest())
        if fault == "extract-cancel":
            self.assertEqual("1", (self.root / "extracted-files.txt").read_text(), "cancel must stop before extracting the next file")
        _, retry = self.ctl("install", "code-server")
        self.assertEqual("installing", retry["state"])
        installed = self.wait("code-server", {"installed", "failed"})
        self.assertEqual("installed", installed["state"])
        self.assertEqual(pin["sha256"], installed["input_sha256"])
        self.assertTrue((base / "4.141.1/bin/code-server").is_file())
        self.assertFalse((base / (".staging-" + retry["job_id"])).exists(), "published job leaves no empty staging directory")
        self.assertEqual(previous_bytes, snapshot(previous))
        self.assertEqual(private_bytes, config.read_bytes())
        self.assertEqual(cookie_bytes, cookie.read_bytes())
        self.assertEqual(pin["sha256"], hashlib.sha256(cache.read_bytes()).hexdigest())
        self.assertEqual(b"preserve other exact directory\n", (other_stage / "other-job").read_bytes())

    def test_extraction_cancel_stops_per_file_and_cleans_only_failed_job(self):
        self.assert_staging_fault_recovers("extract-cancel", "cancelled")

    def test_extraction_io_failure_cleans_job_and_preserves_previous_install(self):
        self.assert_staging_fault_recovers("extract-io", "io-failed")

    def test_extraction_disk_full_cleans_job_and_retains_validated_archive(self):
        self.assert_staging_fault_recovers("extract-disk", "disk-full")

    def test_tar_error_after_partial_extract_cleans_job_and_retry_succeeds(self):
        self.assert_staging_fault_recovers("extract-tar", "unsafe-input-archive")

    def test_staging_marker_failure_cleans_unpublished_tree_and_retry_succeeds(self):
        self.assert_staging_fault_recovers("staging-marker-io", "io-failed")

    def test_staging_rename_failure_never_deletes_old_target_and_retry_succeeds(self):
        self.assert_staging_fault_recovers("staging-rename-io", "io-failed")

    def test_planned_and_unknown_profile_never_execute_commands(self):
        rc, status = self.ctl("status", "desktop-labwc")
        self.assertEqual(0, rc)
        self.assertEqual("planned", status["state"])
        self.assertFalse(status["installed"])
        rc, status = self.ctl("install", "desktop-labwc;touch-pwned")
        self.assertNotEqual(0, rc)
        self.assertEqual("unknown-profile", status["reason"])
        self.assertFalse((self.root / "commands.jsonl").exists())


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("tests", nargs="*")
    options = parser.parse_args()
    OUTPUT = options.output.resolve()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    suite = unittest.defaultTestLoader.loadTestsFromNames(options.tests, sys.modules[__name__]) if options.tests else unittest.defaultTestLoader.loadTestsFromTestCase(ProfileContracts)
    with (OUTPUT / "profile-contracts.log.txt").open("w", encoding="utf-8") as evidence:
        result = unittest.TextTestRunner(stream=evidence, verbosity=2).run(suite)
    print(json.dumps({"suite": "synthetic-profile-cli", "tests": result.testsRun,
                      "failures": len(result.failures), "errors": len(result.errors), "skips": len(result.skipped),
                      "guestValidated": False}, separators=(",", ":")))
    sys.exit(0 if result.wasSuccessful() else 1)
