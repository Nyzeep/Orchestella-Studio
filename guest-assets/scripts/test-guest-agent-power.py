#!/usr/bin/env python3
"""Exercise authenticated v1 POWER with a compile-time mock; never run host power tools."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import tempfile

assets = Path(__file__).resolve().parent.parent
work = Path(os.environ.get("GUEST_RUNTIME_TEST_ROOT", ""))
if not os.environ.get("GUEST_RUNTIME_TEST_ROOT") or not work.is_dir():
    raise SystemExit("GUEST_RUNTIME_TEST_ROOT must name an existing approved evidence directory")
fixture = Path(tempfile.mkdtemp(prefix="guest-agent-power-", dir=work))
source = assets / "agent/guest-agent.c"
executable = fixture / ("guest-agent-power-mock.exe" if os.name == "nt" else "guest-agent-power-mock")
compiler = os.environ.get("CC", "gcc")
flags = ["-std=c11", "-Os", "-Wall", "-Wextra", "-Werror"]
subprocess.run([compiler, *flags, "-DORCHESTELLA_TEST_POWER", str(source), "-o", str(executable)],
               check=True, capture_output=True, timeout=60)
token = "0123456789abcdef" * 4
wrong = "1123456789abcdef" + "0123456789abcdef" * 3
token_file = fixture / "bridge.token"
token_file.write_text(token + "\n", encoding="ascii")
hello = f"ORCHESTELLA/HELLO 1 1 {token}\n"
ready = "ORCHESTELLA/READY 1\n"
ok = "ORCHESTELLA/OK power\n"
record = fixture / "dispatch-count.txt"
checks = []


def check(name, wire, code, response, dispatches=(), fault=None, mode=None, binary=None):
    record.unlink(missing_ok=True)
    env = dict(os.environ)
    env["ORCHESTELLA_TEST_POWER_RECORD"] = str(record)
    for key, value in [("ORCHESTELLA_TEST_POWER_FAULT", fault), ("ORCHESTELLA_TEST_POWER_MODE", mode)]:
        env.pop(key, None)
        if value is not None:
            env[key] = value
    result = subprocess.run([str(binary or executable), "--token-file", str(token_file),
                             "--terminal-device", "synthetic-terminal"],
                            input=wire, text=True, capture_output=True, timeout=5, env=env)
    assert result.returncode == code, (name, result)
    assert result.stdout.replace("\r\n", "\n") == response, (name, result.stdout)
    assert result.stderr == "", (name, result.stderr)
    actual = record.read_text().splitlines() if record.exists() else []
    assert actual == list(dispatches), (name, actual)
    checks.append(name)


for prefix in ("ORCHESTELLA", "DROIDLAYER"):
    for action in ("shutdown", "poweroff"):
        check(f"{prefix.lower()}_{action}_fixed_native_dispatch", hello + f"{prefix}/POWER {action}\n", 0,
              ready + ok, ["/sbin/poweroff"])
check("recovery_dispatch_uses_private_binary_fixed_argv", hello + "ORCHESTELLA/POWER shutdown\n", 0,
      ready + ok, ["/usr/lib/orchestella/busybox poweroff -f"], mode="recovery")
check("valid_retry_hello_then_power_and_ping", hello * 2 + "ORCHESTELLA/POWER poweroff\nORCHESTELLA/PING\n", 0,
      ready * 2 + ok + "ORCHESTELLA/PONG 1\n", ["/sbin/poweroff"])
check("power_before_authentication_does_not_dispatch", "ORCHESTELLA/POWER shutdown\n", 1,
      "ORCHESTELLA/ERROR malformed-handshake\n")
check("wrong_initial_token_cannot_dispatch", f"ORCHESTELLA/HELLO 1 1 {wrong}\nORCHESTELLA/POWER shutdown\n", 3,
      "ORCHESTELLA/ERROR unauthorized\n")
check("wrong_repeated_token_cannot_dispatch", hello + f"ORCHESTELLA/HELLO 1 1 {wrong}\nORCHESTELLA/POWER poweroff\n", 3,
      ready + "ORCHESTELLA/ERROR unauthorized\n")
for fault in ("access", "fork", "pipe"):
    check(f"{fault}_failure_returns_unavailable_without_dispatch", hello + "ORCHESTELLA/POWER shutdown\nORCHESTELLA/PING\n", 0,
          ready + "ORCHESTELLA/ERROR power-unavailable\nORCHESTELLA/PONG 1\n", fault=fault)
check("reply_failure_cancels_dispatch_and_returns_io_failure", hello + "ORCHESTELLA/POWER shutdown\n", 70,
      ready, fault="reply")
for action in ("", "reboot", "shutdown now", "poweroff extra", "shutdown;touch /tmp/sentinel",
               "$(poweroff)", "SHUTDOWN", " poweroff", "poweroff ", "shutdown\t"):
    check("malformed_power_" + repr(action), hello + f"ORCHESTELLA/POWER {action}\nORCHESTELLA/PING\n", 4,
          ready + "ORCHESTELLA/ERROR malformed-command\n")
check("invalid_power_verb_is_not_an_action", hello + "ORCHESTELLA/POWERX shutdown\n", 4,
      ready + "ORCHESTELLA/ERROR malformed-command\n")
if os.name == "nt":
    # Verify that even with test environment variables, the uninstrumented Windows build
    # only returns unavailable. Linux production builds are NEVER run with POWER in this test.
    production = fixture / "guest-agent-production.exe"
    subprocess.run([compiler, *flags, str(source), "-o", str(production)],
                   check=True, capture_output=True, timeout=60)
    check("windows_production_never_dispatches_host_power", hello + "ORCHESTELLA/POWER shutdown\nORCHESTELLA/PING\n", 0,
          ready + "ORCHESTELLA/ERROR power-unavailable\nORCHESTELLA/PONG 1\n", binary=production)
evidence = work / "guest-agent-power-results.txt"
evidence.write_text("\n".join(checks) + f"\nPASS count={len(checks)}\n"
                    + "protocol_version=1\nsource_sha256=" + hashlib.sha256(source.read_bytes()).hexdigest()
                    + "\npower_execution=compile-time-mock-only\nhost_power_commands_executed=0\n"
                    + "actual_ARM64_shutdown=main-session-device-gate\n", encoding="utf-8")
shutil.copyfile(evidence, work / "guest-agent-power-results.preserved.txt")
shutil.rmtree(fixture)
print(f"GUEST_AGENT_POWER_TEST_OK checks={len(checks)} source_sha256={hashlib.sha256(source.read_bytes()).hexdigest()}")
