#!/usr/bin/env python3
"""Compile and exercise Bridge v1 HELLO retries without Gradle or a device."""
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
fixture = Path(tempfile.mkdtemp(prefix="guest-agent-hello-", dir=work))
source = assets / "agent/guest-agent.c"
executable = fixture / ("guest-agent.exe" if os.name == "nt" else "guest-agent")
subprocess.run([os.environ.get("CC", "gcc"), "-std=c11", "-Os", "-Wall", "-Wextra", "-Werror",
                str(source), "-o", str(executable)], check=True, capture_output=True, timeout=60)
token = "0123456789abcdef" * 4
wrong = "1123456789abcdef" + "0123456789abcdef" * 3
token_file = fixture / "bridge.token"
token_file.write_text(token + "\n", encoding="ascii")
hello = f"ORCHESTELLA/HELLO 1 1 {token}\n"
ready = "ORCHESTELLA/READY 1\n"
checks = []


def check(name, wire, code, response):
    result = subprocess.run([str(executable), "--token-file", str(token_file),
                             "--terminal-device", "synthetic-terminal"],
                            input=wire, text=True, capture_output=True, timeout=5)
    assert result.returncode == code, (name, result)
    assert result.stdout.replace("\r\n", "\n") == response, (name, result.stdout)
    assert result.stderr == "", (name, result.stderr)
    checks.append(name)


check("initial_hello_and_ping", hello + "ORCHESTELLA/PING\n", 0, ready + "ORCHESTELLA/PONG 1\n")
check("repeated_hello_is_idempotent", hello * 10 + "ORCHESTELLA/PING\n", 0,
      ready * 10 + "ORCHESTELLA/PONG 1\n")
check("hello_retry_after_command", hello + "ORCHESTELLA/PING\n" + hello + "ORCHESTELLA/PING\n", 0,
      ready + "ORCHESTELLA/PONG 1\n" + ready + "ORCHESTELLA/PONG 1\n")
check("legacy_prefix_retry_keeps_v1", hello + f"DROIDLAYER/HELLO 0 2 {token}\nDROIDLAYER/PING\n", 0,
      ready * 2 + "ORCHESTELLA/PONG 1\n")
check("wrong_retry_token_closes_channel", hello + f"ORCHESTELLA/HELLO 1 1 {wrong}\nORCHESTELLA/PING\n", 3,
      ready + "ORCHESTELLA/ERROR unauthorized\n")
check("wrong_initial_token_closes_channel", f"ORCHESTELLA/HELLO 1 1 {wrong}\n", 3,
      "ORCHESTELLA/ERROR unauthorized\n")
check("retry_version_must_include_v1", hello + f"ORCHESTELLA/HELLO 2 3 {token}\n", 2,
      ready + "ORCHESTELLA/ERROR unsupported-version\n")
check("retry_negative_version_rejected", hello + f"ORCHESTELLA/HELLO -1 1 {token}\n", 1,
      ready + "ORCHESTELLA/ERROR malformed-handshake\n")
check("retry_reversed_version_rejected", hello + f"ORCHESTELLA/HELLO 2 1 {token}\n", 1,
      ready + "ORCHESTELLA/ERROR malformed-handshake\n")
check("retry_extra_field_rejected", hello + hello.rstrip() + " extra\n", 1,
      ready + "ORCHESTELLA/ERROR malformed-handshake\n")
check("retry_truncated_token_rejected", hello + f"ORCHESTELLA/HELLO 1 1 {token[:-1]}\n", 1,
      ready + "ORCHESTELLA/ERROR malformed-handshake\n")
check("retry_long_line_rejected", hello + f"ORCHESTELLA/HELLO 1 1 {token}" + " " * 600 + "\n", 1,
      ready + "ORCHESTELLA/ERROR malformed-handshake\n")
check("malformed_verb_does_not_allow_commands", hello + f"ORCHESTELLA/HELLOX 1 1 {token}\n", 1,
      ready + "ORCHESTELLA/ERROR malformed-handshake\n")
check("unknown_command_still_fails_closed", hello + "ORCHESTELLA/UNRECOGNIZED\n", 4,
      ready + "ORCHESTELLA/ERROR malformed-command\n")
evidence = work / "guest-agent-hello-results.txt"
evidence.write_text("\n".join(checks) + f"\nPASS count={len(checks)}\n"
                    + "protocol_version=1\nsource_sha256=" + hashlib.sha256(source.read_bytes()).hexdigest()
                    + "\nplatform=" + os.name + "\nARM64_binary_delivery=owned_by_native_worker\n", encoding="utf-8")
shutil.copyfile(evidence, work / "guest-agent-hello-results.preserved.txt")
shutil.rmtree(fixture)
print(f"GUEST_AGENT_HELLO_TEST_OK checks={len(checks)} source_sha256={hashlib.sha256(source.read_bytes()).hexdigest()}")
