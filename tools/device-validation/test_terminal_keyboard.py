#!/usr/bin/env python3
"""Check that tapping an already running Orchestella terminal reopens its IME."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

APP = "io.github.nyzeep.orchestella"
REMOTE_XML = "/data/local/tmp/orchestella-terminal-ime-test.xml"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True, help="Existing external evidence directory")
    parser.add_argument("--label", required=True)
    parser.add_argument("--cycles", type=int, default=3)
    args = parser.parse_args()
    if not args.output.is_dir() or not re.fullmatch(r"[A-Za-z0-9_-]+", args.label) or not 1 <= args.cycles <= 5:
        parser.error("Use an existing output directory, a simple label and 1–5 cycles")
    output = args.output / (args.label + ".json")
    if output.exists():
        parser.error("Choose a new label; existing evidence is never overwritten")

    def adb(*command):
        result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, timeout=20)
        if result.returncode:
            raise RuntimeError("Device command failed: " + command[0])
        return result.stdout.decode("utf-8", "replace")

    def ime():
        state = adb("shell", "dumpsys input_method")
        shown = re.findall(r"\bmInputShown=(true|false)\b", state)
        if len(shown) != 1:
            raise RuntimeError("Unsupported IME diagnostic format")
        served = re.search(r"^\s*mServedView=([^\r\n]+)", state, re.MULTILINE)
        owner = served.group(1).split("{", 1)[0] if served else "unknown"
        return {"shown": shown[0] == "true", "served_view_class": owner}

    def wait_for_visibility(visible):
        deadline = time.monotonic() + 4
        while True:
            state = ime()
            if state["shown"] == visible or time.monotonic() >= deadline:
                return state
            time.sleep(0.15)

    def terminal_bounds():
        activities = adb("shell", "dumpsys activity activities")
        if not any("ResumedActivity" in line and APP + "/.TerminalActivity" in line for line in activities.splitlines()):
            raise RuntimeError("Bring the connected terminal to the foreground")
        adb("shell", "uiautomator", "dump", REMOTE_XML)
        document = ET.fromstring(adb("exec-out", "cat", REMOTE_XML))
        candidates = [node for node in document.iter("node") if node.get("package") == APP
                      and (node.get("class", "").endswith("ModernTerminalView")
                           or (node.get("class") == "android.widget.ScrollView"
                               and any(child.get("class") == "android.widget.TextView" for child in node)))]
        if len(candidates) != 1:
            raise RuntimeError("Open one connected terminal before running this check")
        bounds = tuple(map(int, re.findall(r"\d+", candidates[0].get("bounds", ""))))
        if len(bounds) != 4 or bounds[3] - bounds[1] < 100:
            raise RuntimeError("Terminal viewport is unavailable")
        return bounds

    result = {"cycles": [], "passed": False}
    try:
        terminal_bounds()
        for index in range(args.cycles):
            if ime()["shown"]:
                adb("shell", "input", "keyevent", "BACK")
            hidden = wait_for_visibility(False)
            if hidden["shown"]:
                raise RuntimeError("Keyboard dismissal did not complete")
            left, top, right, bottom = terminal_bounds()
            x = left + (right - left) // 4
            y = top + (bottom - top) * (2 if index % 2 == 0 else 3) // 4
            adb("shell", "input", "tap", str(x), str(y))
            reopened = wait_for_visibility(True)
            passed = reopened["shown"] and reopened["served_view_class"] == APP + ".ModernTerminalView"
            result["cycles"].append({"cycle": index + 1, "hidden": hidden, "after_tap": reopened, "passed": passed})
            if not passed:
                raise RuntimeError("Terminal tap failed to reopen the terminal-owned input connection")
        result["passed"] = True
    except (RuntimeError, subprocess.TimeoutExpired, ET.ParseError) as error:
        result["error"] = str(error)
    finally:
        subprocess.run([args.adb, "-s", args.serial, "shell", "rm", "-f", REMOTE_XML], capture_output=True, timeout=20)
        output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
