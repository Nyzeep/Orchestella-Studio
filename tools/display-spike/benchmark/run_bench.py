"""Run the installed W4-05 benchmark APKs on one USB Android device."""
import argparse
from pathlib import Path
import subprocess
import time
import uuid

PREFIX = "io.github.nyzeep.orchestella.spike.w405bench"
ACTIVITY = PREFIX + ".BenchActivity"
MODES = ("raw", "zrle", "ivshmem")
LOADS = ("static", "terminal", "drag")


def adb(tool, *args, timeout=20):
    return subprocess.run([tool, "-d", *args],
                          capture_output=True, text=True, encoding="utf-8",
                          errors="replace", check=True, timeout=timeout).stdout


def one(args, mode, load, trial):
    package = PREFIX + (".zrle" if mode == "zrle" else "")
    run_id = uuid.uuid4().hex[:12]
    adb(args.adb, "shell", "am", "start", "-W", "-S", "-n", f"{package}/{ACTIVITY}",
        "--es", "mode", mode, "--es", "load", load, "--es", "run_id", run_id,
        "--ei", "seconds", str(args.seconds))
    begin = f"begin run={run_id} "
    end = f"end run={run_id} "
    deadline = time.monotonic() + args.seconds + 100
    active = []
    while time.monotonic() < deadline:
        lines = adb(args.adb, "logcat", "-d", "-v", "raw", "-s", "W405Bench:I", "*:S").splitlines()
        start = next((i for i, line in enumerate(lines) if line.startswith(begin)), None)
        if start is not None:
            active = lines[start:]
            finish = next((i for i, line in enumerate(active) if line.startswith(end)), None)
            if finish is not None:
                active = active[:finish + 1]
                break
        time.sleep(1)
    path = args.output / f"round-{trial}-{mode}-{load}.log"
    path.write_text("\n".join(active) + "\n", encoding="utf-8")
    summary = [line for line in active if line.startswith(("result ", "end ", "failure "))]
    for line in summary:
        print(line, flush=True)
    return any(line.startswith(end + "outcome=pass") for line in active)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--mode", choices=MODES, action="append")
    parser.add_argument("--load", choices=LOADS, action="append")
    parser.add_argument("--seconds", type=int, default=20, choices=range(5, 31))
    parser.add_argument("--rounds", type=int, default=1, choices=range(1, 6))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    modes = tuple(args.mode or MODES)
    failures = 0
    for trial in range(1, args.rounds + 1):
        order = modes[trial % len(modes):] + modes[:trial % len(modes)]
        for load in args.load or LOADS:
            for mode in order:
                print(f"round={trial} mode={mode} load={load}", flush=True)
                if not one(args, mode, load, trial):
                    print("benchmark-card failed or timed out", flush=True)
                    failures += 1
                time.sleep(2)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
