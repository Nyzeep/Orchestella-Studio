#!/usr/bin/env python3
"""Process-level authenticated control negative tests; host runs do not prove guest AVF."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def run(binary, token_file, requests):
    result = subprocess.run([str(binary), "--token-file", str(token_file), "--terminal-device", "synthetic-pty"],
                            input=requests.encode("ascii"), capture_output=True, timeout=10)
    result.stdout = result.stdout.replace(b"\r\n", b"\n")
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True, type=Path)
    parser.add_argument("--work-dir", required=True, type=Path)
    args = parser.parse_args()
    args.work_dir.mkdir(parents=True, exist_ok=True)
    token = "a" * 64
    with tempfile.TemporaryDirectory(prefix="phase5-control-", dir=args.work_dir) as directory:
        token_file = Path(directory) / "synthetic-token.txt"
        token_file.write_text(token + "\n", encoding="ascii")
        cases = [
            ("display-launch-denied-keeps-control", "ORCHESTELLA/DISPLAY LAUNCH", "display-launch-unavailable"),
            ("display-mode-denied-keeps-control", "ORCHESTELLA/DISPLAY MODE 1280 720 1.0", "display-mode-unavailable"),
            ("display-overflow-denied", "ORCHESTELLA/DISPLAY MODE 4096 4096 1.0", "malformed-command"),
            ("unknown-display-keeps-control", "ORCHESTELLA/DISPLAY EVIL", "malformed-command"),
            ("fps-is-not-faked", "ORCHESTELLA/DISPLAY FPS 60", "display-fps-unavailable"),
            ("unsupported-feature-version", "ORCHESTELLA/FEATURES 2 PROBE podman", "unsupported-feature-version"),
            ("unknown-feature-rejected", "ORCHESTELLA/FEATURES 1 INSTALL shell", "unsupported-feature-command"),
            ("arbitrary-action-rejected", "ORCHESTELLA/FEATURES 1 EXEC podman", "unsupported-feature-command"),
            ("extra-fields-rejected", "ORCHESTELLA/FEATURES 1 INSTALL podman anything", "malformed-command"),
            ("unknown-command-keeps-control", "ORCHESTELLA/UNKNOWN", "unsupported-command"),
        ]
        observations = []
        for label, command, expected in cases:
            hello = "ORCHESTELLA/HELLO 1 1 " + token + "\n"
            result = run(args.binary, token_file, hello + command + "\nORCHESTELLA/PING\n")
            assert result.returncode == 0, (label, result.returncode)
            assert result.stdout == ("ORCHESTELLA/READY 1\nORCHESTELLA/ERROR " + expected
                                      + "\nORCHESTELLA/PONG 1\n").encode("ascii"), (label, result.stdout)
            observations.append({"case": label, "passed": True})
        unauthorized = run(args.binary, token_file, "ORCHESTELLA/FEATURES 1 INSTALL podman\n")
        assert unauthorized.returncode != 0 and b"READY" not in unauthorized.stdout
        observations.append({"case": "no-feature-execution-before-hello", "passed": True})
        bad = run(args.binary, token_file, "ORCHESTELLA/HELLO 1 1 " + "b" * 64 + "\nORCHESTELLA/PING\n")
        assert bad.returncode == 3 and bad.stdout == b"ORCHESTELLA/ERROR unauthorized\n"
        observations.append({"case": "wrong-token-rejected", "passed": True})
        print(json.dumps({"tests": len(observations), "observations": observations,
                          "runtime": "host control process; guest/Android not run"}, indent=2))


if __name__ == "__main__":
    main()
