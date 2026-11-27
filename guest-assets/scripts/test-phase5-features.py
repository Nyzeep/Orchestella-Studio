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

    def wait(self, profile, expected, *, require_inactive=False):
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            rc, value = self.ctl("status", profile)
            if value["state"] in expected and (not require_inactive or not value["job_active"]):
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
        inputs["units_version"] = 3
        (self.root / "inputs.json").write_text(json.dumps(inputs))
        self.ctl("install", "desktop-labwc")
        state = self.wait("desktop-labwc", {"installed"})
        self.assertEqual(3, state["units_version"])
        self.assertIn("user choice", config.read_text())
        unit = self.root / "home/orchestella/.config/systemd/user/orchestella-desktop-compositor.service"
        self.assertTrue(unit.read_text().startswith("# Orchestella owned unit schema=3"))
        # DW-03: user-manager units run as the owning user and never carry User=/Group=.
        self.assertNotIn("User=", unit.read_text())
        self.assertNotIn("Group=", unit.read_text())
        self.assertNotIn("no-sandbox", unit.read_text())
        self.assertFalse((self.root / "etc/systemd/system/orchestella-desktop-compositor.service").exists())
        inputs["units_version"] = 4
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
        # DW-03: the wayvnc dependency cannot cross the manager boundary once
        # wayvnc became a user unit; readiness is sequenced by start instead.
        self.assertNotIn("orchestella-desktop-wayvnc.service", relay)
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

    def test_desktop_units_are_persistent_user_units_with_linger(self):
        self.ctl("install", "desktop-labwc")
        state = self.wait("desktop-labwc", {"installed"})
        self.assertEqual(3, state["units_version"])
        user_dir = self.root / "home/orchestella/.config/systemd/user"
        compositor = (user_dir / "orchestella-desktop-compositor.service").read_text()
        wayvnc = (user_dir / "orchestella-desktop-wayvnc.service").read_text()
        self.assertTrue(compositor.startswith("# Orchestella owned unit schema=3"))
        self.assertIn("WLR_BACKENDS=headless", compositor)
        self.assertIn("feature-session.py desktop-apps", compositor)
        self.assertIn("Requires=orchestella-desktop-compositor.service", wayvnc)
        self.assertIn("BindsTo=orchestella-desktop-compositor.service", wayvnc)
        # The root relays stay persistent system units; wayvnc-name dependencies
        # cannot cross the manager boundary (DW-03).
        relay = (self.root / "etc/systemd/system/orchestella-display-relay.service").read_text()
        self.assertIn("display-relay.py --user orchestella", relay)
        self.assertNotIn("orchestella-desktop-wayvnc.service", relay)
        self.assertIn("audio-relay.py --user orchestella", (self.root / "etc/systemd/system/orchestella-audio-relay.service").read_text())
        self.assertFalse((self.root / "etc/systemd/system/orchestella-desktop-compositor.service").exists())
        self.assertFalse((self.root / "etc/systemd/system/orchestella-desktop-wayvnc.service").exists())
        # DW-04: the fixed 9104 PulseAudio endpoint ships with the profile.
        self.assertIn("port=9104", (self.root / "home/orchestella/.config/pulse/default.pa").read_text())
        self.assertEqual("autospawn = no\n", (self.root / "home/orchestella/.config/pulse/client.conf").read_text())
        pulse_unit = (user_dir / "orchestella-desktop-pulseaudio.service").read_text()
        self.assertIn("feature-session.py pulseaudio", pulse_unit)
        self.assertIn("Conflicts=pulseaudio.socket pulseaudio.service", pulse_unit)
        self.assertIn("Restart=always", pulse_unit)
        commands = (self.root / "commands.jsonl").read_text()
        self.assertIn('"enable-linger"', commands)
        self.assertIn('"show-user"', commands)
        self.assertIn('"daemon-reload"', commands)
        user_reloads = [json.loads(line) for line in commands.splitlines()
                        if json.loads(line)[:1] == ["runuser"] and "daemon-reload" in json.loads(line)]
        self.assertEqual(1, len(user_reloads))

    def test_linger_failure_is_honest_and_recoverable(self):
        self.fixture(fail_linger=True)
        self.ctl("install", "desktop-labwc")
        value = self.wait("desktop-labwc", {"failed"})
        self.assertEqual("linger-enable-failed", value["reason"])
        self.assertFalse(value["installed"])
        self.assertTrue((self.root / "home/orchestella/.config/systemd/user/orchestella-desktop-compositor.service").exists())
        # enable-linger succeeds but logind does not actually persist the flag:
        # the Linger=yes proof must still fail the install.
        self.fixture(fail_linger="", linger_untracked=True)
        self.ctl("install", "desktop-labwc")
        value = self.wait("desktop-labwc", {"failed"})
        self.assertEqual("linger-enable-failed", value["reason"])
        self.fixture(linger_untracked=False)
        self.ctl("install", "desktop-labwc")
        state = self.wait("desktop-labwc", {"installed"})
        self.assertEqual("installed", state["state"])

    def test_desktop_lifecycle_routes_user_units_through_user_manager(self):
        self.ctl("install", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})
        self.ctl("start", "desktop-labwc")
        self.wait("desktop-labwc", {"awaiting-frame"})
        commands = [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]
        user_sessions = [call for call in commands if call[:1] == ["runuser"]]
        self.assertTrue(user_sessions)
        self.assertTrue(all(call[call.index("--user") + 1] == "orchestella" for call in user_sessions))
        compositor_starts = [call for call in user_sessions
                             if call[-1] == "orchestella-desktop-compositor.service" and call[-2] == "start"]
        self.assertEqual(1, len(compositor_starts))
        self.assertIn("systemctl", compositor_starts[0])
        self.assertIn("--user", compositor_starts[0])
        # The compositor is never started against the root manager.
        self.assertFalse([call for call in commands if call[0] == "systemctl"
                          and call[-1] == "orchestella-desktop-compositor.service"])
        # The display relay remains a root system unit started by the system manager.
        self.assertTrue([call for call in commands if call[0] == "systemctl"
                         and call[-1] == "orchestella-display-relay.service"])
        self.ctl("stop", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})
        commands = [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]
        stops = [call for call in commands if call[0] == "systemctl" and call[-1] == "orchestella-display-relay.service"
                 and call[1] == "stop"]
        user_stops = [call for call in commands if call[:1] == ["runuser"]
                      and call[-1] == "orchestella-desktop-compositor.service" and call[-2] == "stop"]
        self.assertTrue(stops)
        self.assertTrue(user_stops)

    def test_upgrade_retires_legacy_system_desktop_units(self):
        self.ctl("install", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})
        # Model a pre-DW-03 device whose compositor/wayvnc lived in the root manager.
        legacy = self.root / "etc/systemd/system"
        legacy.mkdir(exist_ok=True)
        (legacy / "orchestella-desktop-compositor.service").write_text("# Orchestella owned unit schema=2\nExecStart=/bin/labwc\n")
        (legacy / "orchestella-desktop-wayvnc.service").write_text("# Orchestella owned unit schema=2\nExecStart=/bin/wayvnc\n")
        self.fixture(services={"dbus.service": True, "ssh.service": True, "orchestella-desktop-compositor.service": True})
        inputs = json.loads((FEATURES / "inputs.json").read_text())
        inputs["units_version"] = 4
        (self.root / "inputs.json").write_text(json.dumps(inputs))
        self.ctl("install", "desktop-labwc")
        state = self.wait("desktop-labwc", {"installed"})
        self.assertEqual(4, state["units_version"])
        self.assertFalse((legacy / "orchestella-desktop-compositor.service").exists())
        self.assertFalse((legacy / "orchestella-desktop-wayvnc.service").exists())
        fixture = json.loads((self.root / "fixture.json").read_text())
        self.assertFalse(fixture["services"].get("orchestella-desktop-compositor.service"))
        self.assertTrue((self.root / "home/orchestella/.config/systemd/user/orchestella-desktop-compositor.service").exists())
        # Foreign unit files in the root manager are never touched.
        (legacy / "orchestella-desktop-compositor.service").write_text("# foreign unit\nExecStart=/bin/false\n")
        inputs["units_version"] = 5
        (self.root / "inputs.json").write_text(json.dumps(inputs))
        self.ctl("install", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})
        self.assertTrue((legacy / "orchestella-desktop-compositor.service").read_text().startswith("# foreign unit"))
        self.assertTrue((self.root / "home/orchestella/.config/systemd/user/orchestella-desktop-compositor.service").read_text().startswith("# Orchestella owned unit schema=5"))

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

    def test_audio_endpoint_observed_reports_verified_without_gating(self):
        # DW-08: with the guest PulseAudio endpoint (127.0.0.1:9104) present, the
        # audio closure is reported verified in its own fields while the display
        # chain reaches awaiting-frame exactly as before.
        self.ctl("install", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})
        rc, value = self.ctl("start", "desktop-labwc")
        self.assertEqual(0, rc)
        value = self.wait("desktop-labwc", {"awaiting-frame", "failed"}, require_inactive=True)
        self.assertEqual("awaiting-frame", value["state"])
        self.assertEqual("verified", value["capture"])
        self.assertEqual("verified", value["protocols"])
        self.assertEqual("verified", value["audio_relay"])
        self.assertEqual("none", value["audio_relay_reason"])
        self.assertIn("0100007F:2390", (self.root / "proc/net/tcp").read_text())
        self.ctl("stop", "desktop-labwc")
        value = self.wait("desktop-labwc", {"installed"})
        self.assertEqual("unverified", value["audio_relay"])
        self.assertEqual("none", value["audio_relay_reason"])

    def test_audio_relay_timeout_is_isolated_and_frame_confirm_only_needs_display_chain(self):
        # Simulate a stuck submission to systemd. Verify that the video can
        # confirm its frame while the audio submission is still in flight.
        self.ctl("install", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})
        self.fixture(audio_start_seconds=8,
                     delay_on="start --no-block orchestella-audio-relay.service", delay_seconds=12,
                     no_audio_endpoint=True)
        rc, value = self.ctl("start", "desktop-labwc")
        self.assertEqual(0, rc)
        generation = value["generation"]
        value = self.wait("desktop-labwc", {"awaiting-frame", "failed"})
        self.assertEqual("awaiting-frame", value["state"])
        self.assertEqual("awaiting-host-frame", value["reason"])
        self.assertEqual("verified", value["capture"])
        self.assertEqual("verified", value["protocols"])
        self.assertEqual("unverified", value["audio_relay"])
        self.assertEqual("audio-endpoint-unavailable", value["audio_relay_reason"])
        # Negative: a stale generation is still refused with stale-frame.
        rc, rejected = self.ctl("confirm-frame", "desktop-labwc", "0" * 32)
        self.assertNotEqual(0, rc)
        self.assertEqual("stale-frame", rejected["reason"])
        # Confirm-frame probes only the display chain: the broken audio relay
        # cannot hold the commit hostage.
        rc, ready = self.ctl("confirm-frame", "desktop-labwc", generation)
        self.assertEqual(0, rc, ready.get("reason"))
        self.assertEqual("ready", ready["state"])
        self.assertTrue(ready["job_active"], "the frame must confirm before audio submission returns")
        self.assertEqual("audio-endpoint-unavailable", ready["audio_relay_reason"])
        settled = self.wait("desktop-labwc", {"ready", "failed"}, require_inactive=True)
        self.assertEqual("ready", settled["state"])
        self.assertEqual("command-timeout", settled["audio_relay_reason"])
        commands = [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]
        self.assertEqual(1, commands.count(["systemctl", "start", "--no-block", "orchestella-audio-relay.service"]))
        pulse_starts = [call for call in commands if call[:1] == ["runuser"]
                        and call[-1] == "orchestella-desktop-pulseaudio.service" and "start" in call]
        self.assertEqual(1, len(pulse_starts))
        # Negative: a cancel file against the published job owner still refuses
        # the commit while the on-disk state stays untouched.
        features = self.root / "var/lib/orchestella/features"
        lock = features / "install.lock"
        deadline = time.monotonic() + 5
        while lock.exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertFalse(lock.exists())
        lock.mkdir()
        (lock / "owner.json").write_text(json.dumps({"job_id": generation}), encoding="utf-8")
        (features / (generation + ".cancel")).write_text("{}\n", encoding="utf-8")
        rc, refused = self.ctl("confirm-frame", "desktop-labwc", generation)
        self.assertNotEqual(0, rc)
        self.assertEqual("stale-frame", refused["reason"])
        rc, value = self.ctl("status", "desktop-labwc")
        self.assertEqual(0, rc)
        self.assertEqual("ready", value["state"])
        (features / (generation + ".cancel")).unlink()
        (lock / "owner.json").unlink()
        lock.rmdir()

    def test_pulseaudio_start_failure_keeps_video_ready_under_dw08(self):
        self.ctl("install", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})
        self.fixture(fail_on="orchestella-desktop-pulseaudio.service", no_audio_endpoint=True)
        rc, value = self.ctl("start", "desktop-labwc")
        self.assertEqual(0, rc)
        value = self.wait("desktop-labwc", {"awaiting-frame", "failed"}, require_inactive=True)
        self.assertEqual("awaiting-frame", value["state"])
        self.assertEqual("verified", value["capture"])
        self.assertEqual("verified", value["protocols"])
        self.assertEqual("unverified", value["audio_relay"])
        self.assertEqual("pulseaudio-start-failed", value["audio_relay_reason"])
        rc, ready = self.ctl("confirm-frame", "desktop-labwc", value["generation"])
        self.assertEqual(0, rc, ready.get("reason"))
        self.assertEqual("ready", ready["state"])
        self.assertEqual("unverified", ready["audio_relay"])

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
        self.assertEqual(3, upgraded["units_version"])
        self.assertFalse(upgraded["authenticationConfigured"])
        self.assertTrue(unit.read_text().startswith("# Orchestella owned unit schema=3\n"))
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

    def test_dw07_stale_install_lock_recovers_via_status_recover_and_install(self):
        features = self.root / "var/lib/orchestella/features"
        features.mkdir(parents=True, exist_ok=True)
        state_file = features / "desktop-labwc.json"
        lock = features / "install.lock"
        dead_proc = subprocess.Popen([sys.executable, "-c", "pass"])
        dead_proc.wait(timeout=5)
        dead_owner = {"pid": dead_proc.pid, "identity": "dead-boot:0", "job_id": "1" * 32,
                      "profile": "desktop-labwc", "operation": "install"}

        def plant_stale_lock(state_payload):
            state_file.write_text(json.dumps(state_payload), encoding="utf-8")
            lock.mkdir(exist_ok=True)
            (lock / "owner.json").write_text(json.dumps(dead_owner), encoding="utf-8")
            (lock / "stale.tmp").write_text("partial\n", encoding="utf-8")

        # 1. Interrupted installing state + stale lock -> status marks recovery-required and cleans lock.
        plant_stale_lock({"version": 1, "profile": "desktop-labwc", "state": "installing",
                          "installed": False, "phase": "packages", "reason": "none", "job_id": "1" * 32})
        rc, status = self.ctl("status", "desktop-labwc")
        self.assertEqual(0, rc)
        self.assertEqual("failed", status["state"])
        self.assertEqual("interrupted", status["reason"])
        self.assertEqual("recovery-required", status["phase"])
        self.assertFalse(lock.exists())

        # 2. Already failed + recovery-required + stale lock -> status also cleans lock.
        plant_stale_lock({"version": 1, "profile": "desktop-labwc", "state": "failed",
                          "installed": False, "phase": "recovery-required", "reason": "interrupted", "job_id": "1" * 32})
        rc, status = self.ctl("status", "desktop-labwc")
        self.assertEqual(0, rc)
        self.assertEqual("failed", status["state"])
        self.assertEqual("recovery-required", status["phase"])
        self.assertFalse(lock.exists())

        # 3. failed + recovery-required + stale lock + .cancel -> recover cleans lock/cancel and resets state.
        cancel_file = features / ("1" * 32 + ".cancel")
        cancel_file.write_text("{}\n", encoding="utf-8")
        plant_stale_lock({"version": 1, "profile": "desktop-labwc", "state": "failed",
                          "installed": False, "phase": "recovery-required", "reason": "interrupted", "job_id": "1" * 32})
        rc, recovered = self.ctl("recover", "desktop-labwc")
        self.assertEqual(0, rc)
        self.assertEqual("planned", recovered["state"])
        self.assertEqual("recovered", recovered["phase"])
        self.assertEqual("none", recovered["reason"])
        self.assertEqual("none", recovered["job_id"])
        self.assertFalse(lock.exists())
        self.assertFalse(cancel_file.exists())

        # Installed profile recovers to installed rather than planned.
        plant_stale_lock({"version": 1, "profile": "desktop-labwc", "state": "failed",
                          "installed": True, "phase": "recovery-required", "reason": "interrupted", "job_id": "2" * 32})
        rc, recovered = self.ctl("recover", "desktop-labwc")
        self.assertEqual(0, rc)
        self.assertEqual("installed", recovered["state"])
        self.assertEqual("recovered", recovered["phase"])
        self.assertFalse(lock.exists())

        # 4. Direct install from failed + recovery-required + stale lock cleans lock and completes installed.
        plant_stale_lock({"version": 1, "profile": "desktop-labwc", "state": "failed",
                          "installed": False, "phase": "recovery-required", "reason": "interrupted", "job_id": "3" * 32})
        rc, accepted = self.ctl("install", "desktop-labwc")
        self.assertEqual(0, rc)
        final = self.wait("desktop-labwc", {"installed", "failed"}, require_inactive=True)
        self.assertEqual("installed", final["state"])
        self.assertFalse(lock.exists())

        # 5. CLI validation: recover rejects extra args and --worker.
        rc, bad = self.ctl("recover", "desktop-labwc", "extra")
        self.assertNotEqual(0, rc)
        self.assertEqual("malformed-command", bad["reason"])
        rc, bad = self.ctl("--worker", "--job-id", "a" * 32, "recover", "desktop-labwc")
        self.assertNotEqual(0, rc)
        self.assertEqual("malformed-command", bad["reason"])

    def test_dw07_live_install_lock_pid_causes_busy_and_preserves_lock(self):
        features = self.root / "var/lib/orchestella/features"
        features.mkdir(parents=True, exist_ok=True)
        lock = features / "install.lock"
        live_proc = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        try:
            if os.name == "posix":
                boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
                fields = Path("/proc", str(live_proc.pid), "stat").read_text().rsplit(")", 1)[1].split()
                identity = boot + ":" + fields[19]
            else:
                identity = str(live_proc.pid)
            lock.mkdir(exist_ok=True)
            owner_file = lock / "owner.json"
            owner_bytes = json.dumps({"pid": live_proc.pid, "identity": identity, "job_id": "4" * 32,
                                      "profile": "podman", "operation": "install"}).encode("utf-8")
            owner_file.write_bytes(owner_bytes)

            rc, install_res = self.ctl("install", "desktop-labwc")
            self.assertNotEqual(0, rc)
            self.assertEqual("busy", install_res["reason"])
            self.assertTrue(lock.exists())
            self.assertEqual(owner_bytes, owner_file.read_bytes())

            rc, recover_res = self.ctl("recover", "desktop-labwc")
            self.assertNotEqual(0, rc)
            self.assertEqual("busy", recover_res["reason"])
            self.assertTrue(lock.exists())
            self.assertEqual(owner_bytes, owner_file.read_bytes())
        finally:
            live_proc.terminate()
            live_proc.wait(timeout=5)

    def test_live_resize_skips_service_restart_rotates_generation_and_is_idempotent(self):
        self.ctl("install", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"})
        rc, started = self.ctl("start", "desktop-labwc")
        self.assertEqual(0, rc)
        gen1 = started["generation"]
        self.wait("desktop-labwc", {"awaiting-frame"}, require_inactive=True)
        rc, ready = self.ctl("confirm-frame", "desktop-labwc", gen1)
        self.assertEqual(0, rc)
        self.assertEqual("ready", ready["state"])

        def command_list():
            return [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]

        def is_unit_start(cmd):
            return "systemctl" in cmd and "start" in cmd and any(
                arg.startswith("orchestella-desktop-") or arg.startswith("orchestella-display-") for arg in cmd
            )

        def is_wlr_randr(cmd):
            return cmd[:1] == ["runuser"] and "wlr-randr" in cmd

        starts_before = sum(1 for cmd in command_list() if is_unit_start(cmd))
        self.assertEqual(0, sum(1 for cmd in command_list() if is_wlr_randr(cmd)))

        # Live resize: calls wlr-randr, does NOT restart services, rotates generation to awaiting-frame.
        rc, resized = self.ctl("resize", "desktop-labwc", "1280", "720", "1")
        self.assertEqual(0, rc)
        self.assertEqual("awaiting-frame", resized["state"])
        gen2 = resized["generation"]
        self.assertNotEqual(gen1, gen2)
        self.assertEqual(gen2, resized["capture_generation"])
        self.assertEqual("verified", resized["capture"])
        self.assertEqual(starts_before, sum(1 for cmd in command_list() if is_unit_start(cmd)))
        self.assertEqual(1, sum(1 for cmd in command_list() if is_wlr_randr(cmd)))

        # Old generation rejected, new generation confirmed.
        rc, rejected = self.ctl("confirm-frame", "desktop-labwc", gen1)
        self.assertNotEqual(0, rc)
        self.assertEqual("stale-frame", rejected["reason"])
        rc, confirmed = self.ctl("confirm-frame", "desktop-labwc", gen2)
        self.assertEqual(0, rc)
        self.assertEqual("ready", confirmed["state"])

        # Idempotent repeat resize at ready with same width/height/scale: no wlr-randr, same generation, stays ready.
        rc, repeated = self.ctl("resize", "desktop-labwc", "1280", "720", "1")
        self.assertEqual(0, rc)
        self.assertEqual("ready", repeated["state"])
        self.assertEqual(gen2, repeated["generation"])
        self.assertEqual(1, sum(1 for cmd in command_list() if is_wlr_randr(cmd)))
        self.assertEqual(starts_before, sum(1 for cmd in command_list() if is_unit_start(cmd)))

        # Fresh stop + start resets stored resize state so the next resize applies again.
        self.ctl("stop", "desktop-labwc")
        self.wait("desktop-labwc", {"installed"}, require_inactive=True)
        rc, restarted = self.ctl("start", "desktop-labwc")
        self.assertEqual(0, rc)
        gen3 = restarted["generation"]
        self.wait("desktop-labwc", {"awaiting-frame"}, require_inactive=True)
        rc, ready2 = self.ctl("confirm-frame", "desktop-labwc", gen3)
        self.assertEqual(0, rc)
        self.assertEqual("ready", ready2["state"])
        rc, resized2 = self.ctl("resize", "desktop-labwc", "1280", "720", "1")
        self.assertEqual(0, rc)
        self.assertEqual("awaiting-frame", resized2["state"])
        self.assertNotEqual(gen3, resized2["generation"])
        self.assertEqual(2, sum(1 for cmd in command_list() if is_wlr_randr(cmd)))

    def test_dw07_stale_command_lock_with_orphan_temp_and_corrupt_owner_recovers(self):
        features = self.root / "var/lib/orchestella/features"
        features.mkdir(parents=True, exist_ok=True)
        cmd_lock = features / "command.lock"
        cmd_lock.mkdir()
        orphan = cmd_lock / (".owner.json." + "ab12" * 4)
        orphan.write_text('{"pid":9999}', encoding="utf-8")
        stale_ts = time.time() - 60
        os.utime(cmd_lock, (stale_ts, stale_ts))

        rc, status = self.ctl("status", "desktop-labwc")
        self.assertEqual(0, rc)
        self.assertEqual("planned", status["state"])
        self.assertFalse(cmd_lock.exists())

        cmd_lock.mkdir()
        (cmd_lock / "owner.json").write_text("{corrupt", encoding="utf-8")
        os.utime(cmd_lock, (stale_ts, stale_ts))
        rc, status = self.ctl("status", "desktop-labwc")
        self.assertEqual(0, rc)
        self.assertFalse(cmd_lock.exists())

        cmd_lock.mkdir()
        (cmd_lock / "bad-link").symlink_to(features)
        os.utime(cmd_lock, (stale_ts, stale_ts))
        rc, bad = self.ctl("status", "desktop-labwc")
        self.assertNotEqual(0, rc)
        self.assertEqual("unsafe-state-directory", bad["reason"])

    def test_dw07_corrupt_install_lock_owner_does_not_block_install_recovery(self):
        features = self.root / "var/lib/orchestella/features"
        features.mkdir(parents=True, exist_ok=True)
        install_lock = features / "install.lock"
        install_lock.mkdir()
        (install_lock / "owner.json").write_text("{corrupt", encoding="utf-8")

        rc, accepted = self.ctl("install", "desktop-labwc")
        self.assertEqual(0, rc)
        self.assertEqual("installing", accepted["state"])
        installed = self.wait("desktop-labwc", {"installed"})
        self.assertEqual("installed", installed["state"])
        self.assertFalse(install_lock.exists())

    def test_docker_cli_available_without_daemon_never_reports_ready(self):
        self.ctl("install", "docker-cli")
        installed = self.wait("docker-cli", {"installed", "failed"})
        self.assertEqual("installed", installed["state"])
        self.ctl("start", "docker-cli")
        started = self.wait("docker-cli", {"installed", "ready", "failed"})
        self.assertEqual("installed", started["state"])
        self.assertNotEqual("ready", started["state"])
        self.assertEqual("daemon-unverified", started["reason"])
        self.assertEqual("verified", started["docker_cli"])
        self.assertEqual("unverified", started["docker_daemon"])

    def test_docker_engine_missing_daemon_or_seccomp_or_vfs_fails_honestly(self):
        self.ctl("install", "docker-engine")
        installed = self.wait("docker-engine", {"installed", "failed"})
        self.assertEqual("installed", installed["state"])

        # Daemon down / unreachable after unit start
        self.fixture(docker_daemon_down=True)
        self.ctl("start", "docker-engine")
        failed = self.wait("docker-engine", {"failed"})
        self.assertEqual("docker-daemon-unavailable", failed["reason"])
        self.assertEqual("verified", failed["docker_cli"])
        self.assertEqual("unverified", failed["docker_daemon"])

        # Unconfined / missing seccomp
        self.fixture(docker_daemon_down=False, docker_seccomp_unconfined=True)
        self.ctl("start", "docker-engine")
        failed = self.wait("docker-engine", {"failed"})
        self.assertEqual("docker-seccomp-unavailable", failed["reason"])
        self.assertEqual("unverified", failed["docker_seccomp"])

        # Degraded vfs storage driver must not pass
        self.fixture(docker_seccomp_unconfined=False, docker_storage_driver="vfs")
        self.ctl("start", "docker-engine")
        failed = self.wait("docker-engine", {"failed"})
        self.assertEqual("docker-storage-degraded", failed["reason"])
        self.assertEqual("vfs", failed["docker_storage"])
        self.assertNotEqual("ready", failed["state"])

    def test_docker_engine_network_failure_classifies_and_cleans_probes(self):
        self.ctl("install", "docker-engine")
        self.wait("docker-engine", {"installed"})
        self.fixture(docker_network_fail=True)
        self.ctl("start", "docker-engine")
        failed = self.wait("docker-engine", {"failed"})
        self.assertEqual("container-network-failed", failed["reason"])
        self.assertEqual("verified", failed["container_info"])
        self.assertEqual("verified", failed["container_run"])
        self.assertNotEqual("ready", failed["state"])
        fixture = json.loads((self.root / "fixture.json").read_text())
        self.assertEqual({}, fixture.get("docker_containers", {}))
        self.assertEqual({}, fixture.get("docker_volumes", {}))

    def test_docker_engine_acceptance_verifies_daemon_limits_volume_port_and_cleans(self):
        self.ctl("install", "docker-engine")
        self.wait("docker-engine", {"installed"})
        self.ctl("start", "docker-engine")
        ready = self.wait("docker-engine", {"ready", "failed"})
        self.assertEqual("ready", ready["state"], ready)
        for key in ("docker_cli", "docker_daemon", "docker_seccomp",
                    "container_info", "container_run", "container_dns",
                    "container_tls", "container_volume", "container_port"):
            self.assertEqual("verified", ready[key], key)
        self.assertEqual("overlay2", ready["docker_storage"])
        self.assertEqual("v2", ready["docker_cgroup"])
        fixture = json.loads((self.root / "fixture.json").read_text())
        self.assertEqual({}, fixture.get("docker_containers", {}))
        self.assertEqual({}, fixture.get("docker_volumes", {}))
        with self.assertRaises(OSError):
            socket.create_connection(("127.0.0.1", fixture["docker_port"]), timeout=0.3)
        commands = (self.root / "commands.jsonl").read_text()
        self.assertIn("--memory=", commands)
        self.assertIn("--pids-limit=", commands)
        self.assertNotIn("--privileged", commands)
        self.assertIn("sha256:", commands)
        self.ctl("stop", "docker-engine")
        stopped = self.wait("docker-engine", {"installed"})
        self.assertEqual("installed", stopped["state"])
        self.assertEqual("unverified", stopped["docker_daemon"])


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
