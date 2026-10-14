"""Run synthetic host benchmark and USB model with external evidence and measured process RSS."""
import ctypes
from ctypes import wintypes
import datetime
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import time

WORKTREE = pathlib.Path(__file__).resolve().parents[2]
BATCH = pathlib.Path("D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013")


class MemoryCounters(ctypes.Structure):
    _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD),
                ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
                ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]


def main():
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output = BATCH / "C/benchmarks"
    build = output / "temp" / stamp
    classes = build / "classes"
    classes.mkdir(parents=True)
    dataset = build / "synthetic-dataset"
    dataset.mkdir()
    jvm_temp = build / "jvm-temp"
    jvm_temp.mkdir()
    evidence = output / "evidence" / (stamp + "-synthetic-storage.log.txt")
    evidence.parent.mkdir(parents=True, exist_ok=True)
    preserved = BATCH / "B/implementation/evidence/preserved" / evidence.name
    preserved.parent.mkdir(parents=True, exist_ok=True)
    sources = []
    for package in ["services", "sharing"]:
        sources += list((WORKTREE / "runtime/contract/src/main/java/io/github/nyzeep/orchestella/runtime/contract" / package).glob("*.java"))
    for root in [WORKTREE / "prototypes/phase5-readonly-data/src/main/java", WORKTREE / "prototypes/phase5-usb-fd/src/main/java"]:
        sources += list(root.rglob("*.java"))
    argfile = build / "javac.args"
    argfile.write_text("\n".join('"' + p.as_posix() + '"' for p in sorted(sources)), encoding="utf-8")
    compiler = ["D:/JDK17/bin/javac.exe", "-J-Dfile.encoding=UTF-8", "-encoding", "UTF-8", "--release", "17", "-Xlint:all", "-Werror", "-d", str(classes), "@" + str(argfile)]
    result = subprocess.run(compiler, cwd=WORKTREE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding="utf-8", errors="replace", timeout=120)
    evidence.write_text("COMPILE " + repr(compiler) + "\n" + result.stdout + "\nEXIT " + str(result.returncode) + "\n", encoding="utf-8")
    shutil.copy2(evidence, preserved)
    if result.returncode:
        print(result.stdout)
        return result.returncode
    java = ["D:/JDK17/bin/java.exe", "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=" + str(jvm_temp), "-Xms32m", "-Xmx256m", "-cp", str(classes)]
    command = java + ["io.github.nyzeep.orchestella.prototype.sharing.SyntheticStorageBenchmark", str(dataset)]
    process = subprocess.Popen(command, cwd=WORKTREE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    peak_rss = None
    final_rss = None
    rss_samples = 0
    handle = None
    rss_failure = None
    try:
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        psapi = ctypes.WinDLL("psapi", use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(MemoryCounters), wintypes.DWORD]
        psapi.GetProcessMemoryInfo.restype = wintypes.BOOL
        handle = kernel.OpenProcess(0x0400 | 0x0010, False, process.pid)
        if not handle:
            rss_failure = "OpenProcess unavailable"
        started = time.monotonic()
        while process.poll() is None:
            if time.monotonic() - started > 90:
                process.kill()
                raise RuntimeError("synthetic benchmark exceeded deadline")
            if handle:
                counters = MemoryCounters()
                counters.cb = ctypes.sizeof(counters)
                if psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb):
                    rss_samples += 1
                    final_rss = counters.WorkingSetSize
                    peak_rss = max(peak_rss or 0, counters.PeakWorkingSetSize)
            time.sleep(0.02)
        stdout = process.communicate(timeout=5)[0].decode("utf-8", errors="replace")
    finally:
        if handle:
            kernel.CloseHandle(handle)
    with evidence.open("a", encoding="utf-8") as record:
        record.write("COMMAND " + repr(command) + "\n" + stdout + "\nEXIT " + str(process.returncode) + "\n")
    shutil.copy2(evidence, preserved)
    print(stdout, end="")
    metrics = [json.loads(line.removeprefix("METRIC ")) for line in stdout.splitlines() if line.startswith("METRIC ")]
    report = {
        "timestampUtc": stamp, "execution": "HOST_SYNTHETIC_ONLY", "sourceBaseline": "8d0e8c75d769ecc645ea102b90a304c8657344a1",
        "datasetPath": str(dataset), "java": "D:/JDK17", "exitCode": process.returncode,
        "metrics": metrics, "peakProcessRssBytes": peak_rss, "lastSampleRssBytes": final_rss, "rssSamples": rss_samples,
        "rssMethod": "Windows GetProcessMemoryInfo PeakWorkingSetSize (RSS), sampled every 20ms", "rssFailure": rss_failure,
        "OSColdCache": "NOT_CONTROLLED; no cache flush/system modification; first and repeat filesystem opens separately identified",
        "guest9p": "NOT_RUN", "virtiofs": "NOT_RUN", "androidUsbHost": "NOT_RUN", "mountStatus": "UNVERIFIED",
        "copyBoundary": "both directions use newly generated host synthetic directories; no shared write or sync feature",
        "sources": [{"path": str(p.relative_to(WORKTREE)), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(sources)],
    }
    report_path = output / "evidence" / (stamp + "-synthetic-storage.json")
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    shutil.copy2(report_path, preserved.parent / report_path.name)
    if process.returncode:
        print("EVIDENCE " + str(report_path))
        return process.returncode
    probe_root = BATCH / "C/implementation/temp" / (stamp + "-usb-probe")
    probe_root.mkdir(parents=True)
    probe = java + ["io.github.nyzeep.orchestella.prototype.usbfd.UsbFdModelProbe", str(probe_root)]
    result = subprocess.run(probe, cwd=WORKTREE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding="utf-8", errors="replace", timeout=15)
    probe_evidence = BATCH / "C/implementation/evidence" / (stamp + "-usb-model-probe.log.txt")
    probe_evidence.parent.mkdir(parents=True, exist_ok=True)
    probe_evidence.write_text("COMMAND " + repr(probe) + "\n" + result.stdout + "\nEXIT " + str(result.returncode) + "\n", encoding="utf-8")
    shutil.copy2(probe_evidence, preserved.parent / probe_evidence.name)
    print(result.stdout, end="")
    print("BENCHMARK " + str(report_path))
    print("RSS_BYTES " + str(peak_rss))
    print("USB_PROBE " + str(probe_evidence))
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
