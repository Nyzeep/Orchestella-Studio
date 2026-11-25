#!/usr/bin/env python3
"""Exercise authenticated v1 POWER against a compiled substitute for the power utilities."""

import hashlib
import os
from pathlib import Path
import select
import shutil
import subprocess
import tempfile
import time

assets = Path(__file__).resolve().parent.parent
source = assets / "agent/guest-agent.c"
compiler = os.environ.get("CC", "gcc")
flags = ["-std=c11", "-Wall", "-Wextra", "-Werror"]
token = "0123456789abcdef" * 4
wrong = "1123456789abcdef" + "0123456789abcdef" * 3
hello = f"ORCHESTELLA/HELLO 1 1 {token}\n"
ready = "ORCHESTELLA/READY 1\n"
ok = "ORCHESTELLA/OK power\n"
pong = "ORCHESTELLA/PONG 1\n"
unavailable = "ORCHESTELLA/ERROR power-unavailable\n"
malformed = "ORCHESTELLA/ERROR malformed-command\n"

fake_source = r"""
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    const char *mode = getenv("ORCHESTELLA_TEST_POWER_BEHAVIOR");
    const char *record_path = getenv("ORCHESTELLA_TEST_POWER_RECORD");
    int forced = argc > 1;
    if (record_path == NULL) return 1;
    FILE *record = fopen(record_path, "a");
    if (record == NULL) return 1;
    fprintf(record, "%ld %ld", (long)getpid(), (long)getppid());
    for (int i = 0; i < argc; ++i) fprintf(record, " %s", argv[i]);
    fputc('\n', record);
    fclose(record);
    if (mode != NULL && strcmp(mode, forced ? "force-blocks" : "graceful-blocks") == 0) {
        alarm(8);  /* Stop the fixture even if watchdog supervision regresses. */
        for (;;) pause();
    }
    return !forced && mode != NULL && strcmp(mode, "inhibited") == 0 ? 1 : 0;
}
"""


def run_checks(fixture):
    is_windows = os.name == "nt"
    # The Phase 5 compiler bridge is scoped to compiling only guest-agent.c.
    record_only = "ORCH_QUALITY_CONFIG" in os.environ
    executable = fixture / ("guest-agent.exe" if is_windows else "guest-agent-power-test")
    fake_power = fixture / "fake-power"
    compile_options = []
    if record_only:
        compile_options = ["-DORCHESTELLA_TEST_POWER"]
    elif not is_windows:
        fake_code = fixture / "fake-power.c"
        fake_code.write_text(fake_source, encoding="ascii")
        subprocess.run([compiler, *flags, str(fake_code), "-o", str(fake_power)], check=True, timeout=60)
        compile_options = ["-DORCHESTELLA_TEST_POWER",
                           '-DORCHESTELLA_TEST_POWER_PROGRAM="' + fake_power.as_posix() + '"']
    subprocess.run([compiler, *flags, *compile_options, str(source), "-o", str(executable)],
                   check=True, timeout=60)

    token_file = fixture / "bridge.token"
    token_file.write_text(token + "\n", encoding="ascii")
    command = [str(executable), "--token-file", str(token_file), "--terminal-device", "synthetic-terminal"]
    record = fixture / "dispatches.txt"
    checks = []

    def reset_fixture(behavior="", mode="systemd", fault=""):
        record.unlink(missing_ok=True)
        return dict(os.environ, ORCHESTELLA_TEST_POWER_RECORD=str(record),
                    ORCHESTELLA_TEST_POWER_BEHAVIOR=behavior, ORCHESTELLA_TEST_POWER_MODE=mode,
                    ORCHESTELLA_TEST_POWER_FAULT=fault)

    def assert_invocations(name, expected):
        records = record.read_text().splitlines() if record.exists() else []
        if record_only:
            assert records == list(expected), (name, records)
            return
        invocations = [line.split(maxsplit=2) for line in records]
        assert [args for _, _, args in invocations] == list(expected), (name, records)
        for child, worker, _ in invocations:
            assert not Path("/proc", child).exists(), (name, "power utility still present", child)
            assert not Path("/proc", worker).exists(), (name, "dispatch worker still present", worker)

    def check(name, wire, code, response, dispatches=(), behavior="", mode="systemd", diagnostic="", fault=""):
        result = subprocess.run(command, input=wire, text=True, capture_output=True, timeout=5,
                                env=reset_fixture(behavior, mode, fault))
        assert result.returncode == code, (name, result)
        assert result.stdout == response, (name, result.stdout)
        assert result.stderr == diagnostic, (name, result.stderr)
        assert_invocations(name, dispatches)
        checks.append(name)

    check("power_before_authentication_does_not_dispatch", "ORCHESTELLA/POWER shutdown\n", 1,
          "ORCHESTELLA/ERROR malformed-handshake\n")
    check("wrong_initial_token_cannot_force", f"ORCHESTELLA/HELLO 1 1 {wrong}\nORCHESTELLA/POWER POWEROFF_FORCE\n",
          3, "ORCHESTELLA/ERROR unauthorized\n")
    check("wrong_repeated_token_cannot_dispatch", hello + f"ORCHESTELLA/HELLO 1 1 {wrong}\nORCHESTELLA/POWER poweroff\n",
          3, ready + "ORCHESTELLA/ERROR unauthorized\n")
    for action in ("", "reboot", "shutdown now", "poweroff extra", "shutdown;touch /tmp/sentinel",
                   "$(poweroff)", "SHUTDOWN", " poweroff", "poweroff ", "shutdown\t", "POWEROFF_FORCE -f"):
        check("malformed_power_" + repr(action), hello + f"ORCHESTELLA/POWER {action}\nORCHESTELLA/PING\n",
              4, ready + malformed)
    check("invalid_power_verb_is_not_an_action", hello + "ORCHESTELLA/POWERX shutdown\n", 4, ready + malformed)

    if is_windows:
        for action in ("shutdown", "POWEROFF_FORCE"):
            check("windows_production_never_dispatches_" + action,
                  hello + f"ORCHESTELLA/POWER {action}\nORCHESTELLA/PING\n", 0, ready + unavailable + pong)
        return checks

    graceful = "/sbin/poweroff" if record_only else "poweroff"
    force = "/usr/bin/systemctl --force poweroff" if record_only else "systemctl --force poweroff"
    recovery = "/usr/lib/orchestella/busybox poweroff -f" if record_only else "busybox poweroff -f"
    for prefix in ("ORCHESTELLA", "DROIDLAYER"):
        for action, invocation in (("shutdown", graceful), ("poweroff", graceful), ("POWEROFF_FORCE", force)):
            check(f"{prefix.lower()}_{action}_fixed_argv", hello + f"{prefix}/POWER {action}\n", 0,
                  ready + ok, [invocation])
    check("recovery_dispatch_uses_private_busybox", hello + "ORCHESTELLA/POWER shutdown\n", 0,
          ready + ok, [recovery], mode="recovery")
    check("force_with_traditional_init_uses_native_poweroff", hello + "ORCHESTELLA/POWER POWEROFF_FORCE\n", 0,
          ready + ok, [graceful + " -f"], mode="init")
    check("valid_retry_hello_then_power_and_ping", hello * 2 + "ORCHESTELLA/POWER poweroff\nORCHESTELLA/PING\n", 0,
          ready * 2 + ok + pong, [graceful])
    if record_only:
        for fault in ("access", "fork", "pipe"):
            check(f"{fault}_failure_keeps_control_channel_live",
                  hello + "ORCHESTELLA/POWER shutdown\nORCHESTELLA/PING\n", 0,
                  ready + unavailable + pong, fault=fault)
        check("reply_failure_cancels_dispatch", hello + "ORCHESTELLA/POWER shutdown\n", 70, ready, fault="reply")
        return checks

    check("inhibited_shutdown_escalates", hello + "ORCHESTELLA/POWER shutdown\n", 0,
          ready + ok, [graceful, force], behavior="inhibited",
          diagnostic="ORCHESTELLA_GUEST_POWER_FALLBACK reason=graceful-failed\n")
    check("hung_force_has_its_own_deadline", hello + "ORCHESTELLA/POWER POWEROFF_FORCE\n", 0,
          ready + ok, [force], behavior="force-blocks",
          diagnostic="ORCHESTELLA_GUEST_POWER_ERROR stage=force result=timeout\n")

    def read_reply(process):
        reply = bytearray()
        deadline = time.monotonic() + 5
        while not reply.endswith(b"\n"):
            remaining = deadline - time.monotonic()
            assert remaining > 0 and select.select([process.stdout], [], [], remaining)[0], "reply timed out"
            byte = process.stdout.read(1)
            assert byte, "control channel closed before reply"
            reply.extend(byte)
        return reply.decode("ascii")

    with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          env=reset_fixture("graceful-blocks"), bufsize=0) as process:
        process.stdin.write((hello + "ORCHESTELLA/POWER shutdown\nORCHESTELLA/PING\n").encode("ascii"))
        assert read_reply(process) == ready
        assert read_reply(process) == ok
        assert read_reply(process) == pong
        children = Path("/proc", str(process.pid), "task", str(process.pid), "children")
        deadline = time.monotonic() + 5
        while children.read_text().strip() and time.monotonic() < deadline:
            time.sleep(0.02)
        assert not children.read_text().strip(), "completed power worker must be reaped while the bridge is open"
        assert_invocations("hung_graceful_shutdown_escalates", [graceful, force])
        stdout, stderr = process.communicate(timeout=5)
        assert process.returncode == 0 and stdout == b"", (process.returncode, stdout)
        assert stderr == b"ORCHESTELLA_GUEST_POWER_FALLBACK reason=timeout\n", stderr
        checks.append("hung_graceful_shutdown_escalates_and_reaps")

    # Python's inherited SIGPIPE disposition makes a closed reply pipe return EPIPE.
    with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          env=reset_fixture(), bufsize=0, restore_signals=False) as process:
        process.stdin.write(hello.encode("ascii"))
        assert read_reply(process) == ready
        process.stdout.close()
        process.stdin.write(b"ORCHESTELLA/POWER shutdown\n")
        process.stdin.close()
        assert process.wait(timeout=5) == 70
        assert process.stderr.read() == b""
        assert_invocations("reply_failure_cancels_dispatch", [])
        checks.append("reply_failure_cancels_dispatch")

    fake_power.rename(fixture / "absent-power")
    check("unavailable_utility_keeps_control_channel_live", hello + "ORCHESTELLA/POWER shutdown\nORCHESTELLA/PING\n", 0,
          ready + unavailable + pong)
    return checks


work_value = os.environ.get("GUEST_RUNTIME_TEST_ROOT")
if not work_value or not Path(work_value).is_dir():
    raise SystemExit("GUEST_RUNTIME_TEST_ROOT must name an existing approved evidence directory")
work = Path(work_value).resolve()
with tempfile.TemporaryDirectory(prefix="guest-agent-power-", dir=work) as directory:
    checks = run_checks(Path(directory))
source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
power_execution = "compile-time-mock-only" if "ORCH_QUALITY_CONFIG" in os.environ else "compiled-fake-only"
evidence = work / "guest-agent-power-results.txt"
evidence.write_text("\n".join(checks) + f"\nPASS count={len(checks)}\n"
                    + f"protocol_version=1\nsource_sha256={source_hash}\n"
                    + f"power_execution={power_execution}\nhost_power_commands_executed=0\n"
                    + "actual_ARM64_shutdown=main-session-device-gate\n", encoding="utf-8")
shutil.copyfile(evidence, work / "guest-agent-power-results.preserved.txt")
print(f"GUEST_AGENT_POWER_TEST_OK checks={len(checks)} source_sha256={source_hash}")
