#!/usr/bin/env python3
"""Collect bounded local Phase 5 device facts only after an explicit device handoff."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


PACKAGE = "io.github.nyzeep.orchestella.phase5"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--handoff", required=True, type=Path)
    parser.add_argument("--adb", required=True, type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()
    grant = json.loads(args.handoff.read_text(encoding="utf-8"))
    if grant.get("status") != "GRANTED" or grant.get("owner") != "phase5" or grant.get("deviceSerial") != args.serial:
        raise SystemExit("No explicit Phase 5 device ownership grant; no ADB action performed")
    args.output.mkdir(parents=True, exist_ok=True)
    preserved = args.output / "preserved"
    preserved.mkdir(exist_ok=True)
    command = [str(args.adb), "-s", args.serial]
    operations = [(["get-state"], "state"), (["shell", "getprop", "ro.build.version.sdk"], "api"),
                  (["shell", "getprop", "ro.product.model"], "model")]
    if args.install:
        if not grant.get("installPhase5DebugApproved", False):
            raise SystemExit("The handoff does not authorize installing this isolated package")
        operations += [(["install", "-r", str(args.apk)], "install-isolated-package"),
                       (["shell", "am", "force-stop", PACKAGE], "stop-isolated-package"),
                       (["shell", "am", "start", "-n", PACKAGE + "/io.github.nyzeep.orchestella.MainActivity"], "launch-isolated-package")]
    results = []
    for arguments, name in operations:
        result = subprocess.run(command + arguments, capture_output=True, timeout=120)
        text = (result.stdout + result.stderr).decode("utf-8", errors="replace").replace(args.serial, "device-phase5")
        text = re.sub(r"[0-9a-f]{64}", "[digest-or-secret-redacted]", text)
        for base in (args.output, preserved):
            (base / (name + ".log.txt")).write_text(text, encoding="utf-8")
        results.append({"operation": name, "exit": result.returncode})
        if result.returncode:
            break
    record = {"device": "device-phase5", "apkSha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              "operations": results, "graphics": "NOT_RUN", "containers": "NOT_RUN", "editor": "NOT_RUN",
              "usb": "NOT_RUN", "storage": "NOT_RUN", "instruction": "Follow the local integration guide; installation alone is not runtime acceptance"}
    for base in (args.output, preserved):
        (base / "device-preflight.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
    print("Explicitly granted device preflight collected; feature acceptance remains separate")


if __name__ == "__main__":
    main()
