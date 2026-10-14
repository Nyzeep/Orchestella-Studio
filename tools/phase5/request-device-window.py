#!/usr/bin/env python3
"""Write a local device handoff request; this script performs no ADB actions."""
import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    request = {"owner": "phase5", "status": "REQUESTED_NOT_GRANTED", "device": "HONOR MEY-AN00 API36",
               "activity": "First session retains exclusive access until an explicit local handoff",
               "apk": str(args.apk), "applicationId": "io.github.nyzeep.orchestella.phase5",
               "estimatedMinutes": 90,
               "tests": ["Debian13 base boot and optional labwc install", "capture, host Surface and input damage proof",
                         "terminal/desktop reattach, rotation, IME and transport failure",
                         "normal-user Podman run/DNS/TLS/volume/port", "code-server authenticated local IDE",
                         "explicit LAN lease expiration and listener removal", "held SAF FD Downloads proof and USB permission probe"],
               "prohibited": ["Do not operate ADB while the first session owns the device", "Do not overwrite the main app",
                              "Do not clear user instances", "No AVF/GPU claim based on phone version or CPU"]}
    (args.output / "phase5-device-window-request.json").write_text(json.dumps(request, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Local request recorded; no device operation was performed")


if __name__ == "__main__":
    main()
