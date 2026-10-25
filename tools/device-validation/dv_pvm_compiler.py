#!/usr/bin/env python3
"""DV-PVM-02 compile-service host driver (QA-only).

Drives one protected Microdroid VM that runs `libpvm_compiler_payload.so` (the
PVM-03 service, CLANG engine) and pushes real distcc v1 jobs into it from the
host through `adb forward` and the production `DistccTcpVsockBridge`.

Two facts shape this driver:

1. The payload is a *server*. It only becomes useful after `payloadReady`, so
   the instrumentation publishes its bridge port in the evidence file and this
   driver polls for that port before it starts a client.
2. Microdroid has no host->payload environment channel, so the AXP1 token
   cannot be an env var on device. The payload reads
   `<apkContents>/assets/accel_token` when the variable is absent; the caller
   must have written that asset before building the APK (the token printed by
   `--print-token` is the value to use).

Evidence hygiene: serials never reach stdout or the summary. Only fixed codes,
byte counts and timings are recorded.
"""

from __future__ import annotations

import argparse
import json
import os
import secrets
import socket
import subprocess
import sys
import time
from pathlib import Path

INSTRUMENTATION = "io.github.nyzeep.orchestella.PvmProbeInstrumentation"
DEFAULT_PACKAGE = "io.github.nyzeep.orchestella.qa.dvpvm"
EVIDENCE_DIR = "files/avf-qa"


def build_fixture(functions: int) -> str:
    """A preprocessed unit of the DV-PVM-01 proven shape (~85 KB at 2000)."""
    lines = [
        "/* DV-PVM-02 device fixture: %d functions, preprocessed form. */" % functions,
        "int puts(const char*);",
    ]
    for index in range(functions):
        lines.append("int probe_fn_%d(int x) { return x + %d; }" % (index, index))
    touched = sorted({0, 1, 2, 3, functions // 2, functions - 1})
    terms = " + ".join("probe_fn_%d(%d)" % (i, i) for i in touched)
    lines.append("int fixture_entry(void) {")
    lines.append("    int total = %s;" % terms)
    lines.append("    return total % 251;")
    lines.append("}")
    return "\n".join(lines) + "\n"


def parse_facts(text: str) -> dict[str, str]:
    facts: dict[str, str] = {}
    for line in text.splitlines():
        key, separator, value = line.partition("=")
        if separator:
            facts[key.strip()] = value.strip()
    return facts


class Adb:
    def __init__(self, adb_path: str, serial: str | None):
        self.adb_path = adb_path
        self.serial = serial

    def _base(self) -> list[str]:
        command = [self.adb_path]
        if self.serial:
            command += ["-s", self.serial]
        return command

    def run(self, *args: str, timeout: int = 120, check: bool = True) -> str:
        completed = subprocess.run(
            self._base() + list(args), capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=timeout)
        if check and completed.returncode != 0:
            raise RuntimeError(
                f"adb {' '.join(args)} failed: "
                f"{completed.stderr.strip() or completed.stdout.strip()}")
        return completed.stdout

    def discover(self) -> str:
        text = self.run("devices", "-l")
        serials = []
        for line in text.splitlines():
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                serials.append(parts[0])
        if self.serial:
            if self.serial not in serials:
                raise RuntimeError("requested serial is not in the device state")
            return self.serial
        if len(serials) != 1:
            raise RuntimeError(f"expected exactly one usable device, got {len(serials)}")
        return serials[0]

    def read_evidence(self, package: str, name: str) -> dict | None:
        text = self.run("shell", "run-as", package, "cat",
                        f"{EVIDENCE_DIR}/{name}", check=False, timeout=60)
        text = text.strip()
        if not text.startswith("{"):
            return None
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return None

    def process_rss_kb(self, needle: str) -> int | None:
        """VmHWM of the first process whose name contains `needle`.

        The pVM's guest RAM belongs to the host-side crosvm process, so this is
        the only OOM signal a protected VM exposes to the host. It is labelled
        as crosvm memory in the summary, never as payload RSS.
        """
        text = self.run("shell", "ps", "-A", "-o", "PID,NAME", check=False)
        for line in text.splitlines():
            parts = line.split()
            if len(parts) >= 2 and needle in line:
                pid = parts[0]
                status = self.run("shell", "cat", f"/proc/{pid}/status",
                                  check=False)
                for entry in status.splitlines():
                    if entry.startswith("VmHWM:"):
                        return int(entry.split()[1])
        return None


def wait_for_bridge(adb: Adb, package: str, evidence_name: str,
                    process: subprocess.Popen, deadline_s: float) -> dict:
    started = time.monotonic()
    last: dict | None = None
    while time.monotonic() - started < deadline_s:
        if process.poll() is not None and last is None:
            # am instrument exited within milliseconds: the run died early.
            evidence = adb.read_evidence(package, evidence_name)
            if evidence is not None:
                return evidence
        evidence = adb.read_evidence(package, evidence_name)
        if evidence is not None:
            last = evidence
            if evidence.get("bridge_port"):
                return evidence
            if evidence.get("failure_code") or evidence.get("stage") in ("done", "rejected"):
                return evidence
        time.sleep(1.0)
    return last or {}


def compile_reference(clang: str, doti: Path, output: Path) -> tuple[bool, str]:
    """Same clang version, same -cc1 argument vector as the payload engine."""
    command = [clang, "-cc1", "-triple", "aarch64-unknown-linux-android",
               "-emit-obj", "-x", "cpp-output", "-fno-crash-diagnostics",
               "-O2", str(doti), "-o", str(output)]
    completed = subprocess.run(command, capture_output=True, text=True,
                               encoding="utf-8", errors="replace")
    detail = (completed.stderr or "").strip()
    return completed.returncode == 0, detail[:400]


def run_job(adb: Adb, client: str, python: str, port: int, token: str,
            doti: Path, output: Path, timeout_s: int) -> tuple[dict, str]:
    # -u: without it the client's stdout is block-buffered and a killed
    # subprocess leaves an empty log, which hides handshake failures entirely.
    command = [python, "-u", client, "--port", str(port), "--token", token,
               "--arg=cc", "--arg=-c", "--arg=-O2", "--doti-file", str(doti),
               "--out", str(output), "--timeout", str(timeout_s)]
    completed = subprocess.run(command, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True,
                               encoding="utf-8", errors="replace",
                               timeout=timeout_s + 60)
    return parse_facts(completed.stdout), completed.stdout



def foreground_qa_app(adb: Adb, qa_package: str) -> None:
    """Make the QA uid foreground so HyperOS stops freezing it.

    HyperOS freezes a process it considers cached a couple of seconds after the
    VM starts: every thread stops, including the bridge's accept loop, and from
    the host that is indistinguishable from a stalled payload. A foreground
    activity makes the uid unfreezable. It must happen *after* the app process
    exists, or ActivityManager races the instrumentation restart.
    """
    resolved = adb.run("shell", "cmd", "package", "resolve-activity", "--brief",
                       qa_package, check=False).strip().splitlines()
    component = resolved[-1].strip() if resolved else ""
    if "/" in component:
        adb.run("shell", "am", "start", "-n", component, check=False)
    else:
        print("warn=launch activity not resolved; app may freeze", file=sys.stderr)

def run_files_mode(adb: Adb, args, root: Path, work: Path, doti: Path,
                   summary: dict) -> dict:
    """Push one distcc request, run the payload, pull the object back.

    The AXP1 client runs inside the app (Axp1DistccChannelFactory) and the
    request/response travel as app-private files. This exists because
    `adb forward` on this host accepts the host connection but never delivers
    it to the app socket (device-side `nc` to the same port works), which made
    the bridge mode silently hang.
    """
    sys.path.insert(0, str(root / "tools" / "pvm-compiler"))
    import axp1_distcc_client as client_module

    argv = args.arg or ["cc", "-c", "-O2", "fixture.i", "-o", "fixture.o"]
    request = client_module.build_request(argv, doti.read_bytes())
    request_path = work / "request.bin"
    request_path.write_bytes(request)
    summary["request_bytes"] = len(request)
    summary["argv"] = argv

    evidence_name = f"pvm-compiler-{args.token}.json"
    adb.run("shell", "am", "force-stop", args.qa_package)
    adb.run("shell", "run-as", args.qa_package, "rm", "-f",
            f"{EVIDENCE_DIR}/{evidence_name}", check=False)
    adb.run("shell", "run-as", args.qa_package, "rm", "-f",
            "files/csdl-response.bin", check=False)
    adb.run("push", str(request_path), "/data/local/tmp/csdl-request.bin")
    adb.run("shell", "run-as", args.qa_package, "cp",
            "/data/local/tmp/csdl-request.bin", "files/csdl-request.bin")

    instrument = adb._base() + [
        "shell", "am", "instrument", "-w", "-r",
        "-e", "qaMode", "compiler-file",
        "-e", "qaRunToken", args.token,
        "-e", "qaPayloadName", args.payload,
        "-e", "qaMemoryMb", str(args.memory_mb),
        "-e", "qaVsockPort", str(args.vsock_port),
        f"{args.qa_package}.test/{INSTRUMENTATION}",
    ]
    process = subprocess.Popen(instrument, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True,
                               encoding="utf-8", errors="replace")
    # The payload is ready in ~2.5 s; foreground the app before the freezer can
    # catch the process, otherwise the exchange never starts.
    time.sleep(2.0)
    foreground_qa_app(adb, args.qa_package)

    started = time.monotonic()
    evidence: dict = {}
    while time.monotonic() - started < args.ready_deadline_s + args.job_timeout_s:
        evidence = adb.read_evidence(args.qa_package, evidence_name) or {}
        if evidence.get("stage") == "done" or evidence.get("failure_code"):
            break
        time.sleep(1.0)
    summary["payload_ready_ms"] = evidence.get("payload_ready_ms")
    summary["create_ms"] = evidence.get("create_ms")
    summary["axp1_handshake_ms"] = evidence.get("axp1_handshake_ms")
    summary["transfer_ms"] = evidence.get("transfer_ms")
    summary["response_bytes"] = evidence.get("response_bytes")
    summary["capabilities_raw"] = (evidence.get("device") or {}).get("capabilities_raw")
    summary["sdk_int"] = (evidence.get("device") or {}).get("sdk_int")
    summary["abi"] = (evidence.get("device") or {}).get("abi")
    summary["instrumentation"] = {
        "stage": evidence.get("stage"),
        "failure_code": evidence.get("failure_code"),
        "stop_confirmed": evidence.get("stop_confirmed"),
        "deleted": evidence.get("deleted"),
        "notes": evidence.get("notes"),
        "channel": evidence.get("channel"),
    }

    try:
        process.wait(timeout=120)
    except subprocess.TimeoutExpired:
        process.kill()
    return evidence

def files_main(adb: Adb, args, root: Path, work: Path, doti: Path,
               summary: dict) -> int:
    """Whole files-mode run: install, push, compile, pull, judge."""
    adb.run("install", "-r", "-t", args.apk, timeout=1800)
    adb.run("install", "-r", "-t", args.test_apk, timeout=1800)
    for permission in ("android.permission.MANAGE_VIRTUAL_MACHINE",
                       "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"):
        adb.run("shell", "pm", "grant", args.qa_package, permission)
    adb.run("shell", "svc", "power", "stayon", "usb", check=False)
    adb.run("shell", "am", "set-standby-bucket", args.qa_package, "active", check=False)
    adb.run("shell", "cmd", "appops", "set", args.qa_package,
            "RUN_ANY_IN_BACKGROUND", "allow", check=False)

    evidence = run_files_mode(adb, args, root, work, doti, summary)
    failure = evidence.get("failure_code")

    response_path = work / "response.bin"
    with open(response_path, "wb") as sink:
        completed = subprocess.run(
            adb._base() + ["exec-out", "run-as", args.qa_package, "cat",
                           "files/csdl-response.bin"],
            stdout=sink, stderr=subprocess.DEVNULL)
    jobs: list[dict] = []
    if completed.returncode == 0 and response_path.stat().st_size > 0:
        sys.path.insert(0, str(root / "tools" / "pvm-compiler"))
        import axp1_distcc_client as client_module
        packets = client_module.decode_distcc_packets(response_path.read_bytes())
        stat = int.from_bytes(packets.get("STAT", b"\x00\x00\x00\x00"), "big")
        serr = packets.get("SERR", b"").decode("utf-8", "replace").strip()
        obj = packets.get("DOTO", b"")
        object_path = work / "device.o"
        object_path.write_bytes(obj)
        jobs.append({"index": 0, "stat": str(stat), "object_bytes": str(len(obj)),
                     "serr": serr[:400], "object_path": str(object_path),
                     "wall_ms": evidence.get("transfer_ms")})
        if stat != 0:
            failure = failure or "JOB_FAILED"
    else:
        failure = failure or "RESPONSE_MISSING"
    summary["jobs"] = jobs
    summary["failure_code"] = failure

    if args.reference_clang and jobs:
        reference = work / "reference.o"
        ok, detail = compile_reference(args.reference_clang, doti, reference)
        summary["reference"] = {"compiled": ok, "detail": detail,
                                "bytes": reference.stat().st_size if ok else None}
        if ok:
            command = [sys.executable, args.compare_script,
                       "--a", jobs[0]["object_path"], "--b", str(reference),
                       "--fixtures", args.fixtures, "--no-run"]
            for flag, value in (("--cc", args.cc), ("--nm", args.nm),
                                ("--readelf", args.readelf)):
                if value:
                    command += [flag, value]
            compared = subprocess.run(command, stdout=subprocess.PIPE,
                                      stderr=subprocess.STDOUT, text=True,
                                      encoding="utf-8", errors="replace")
            lines = compared.stdout.strip().splitlines()
            summary["equivalence"] = {
                "verdict": lines[0] if lines else "INCONCLUSIVE",
                "detail": lines[1] if len(lines) > 1 else "",
            }

    Path(args.output).write_text(json.dumps(summary, indent=2, sort_keys=True),
                                 encoding="utf-8")
    print(json.dumps({key: summary.get(key) for key in
                      ("device_alias", "payload_ready_ms", "create_ms",
                       "axp1_handshake_ms", "transfer_ms", "request_bytes",
                       "response_bytes", "failure_code", "equivalence")},
                     indent=2))
    print(json.dumps(summary.get("jobs"), indent=2))
    return 0 if failure is None else 1


def main(argv: list[str] | None = None) -> int:
    here = Path(__file__).resolve().parent
    root = here.parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True, help="path to platform-tools adb")
    parser.add_argument("--serial", default=None)
    parser.add_argument("--qa-package", default=DEFAULT_PACKAGE)
    parser.add_argument("--payload", default="libpvm_compiler_payload.so")
    parser.add_argument("--apk", required=True)
    parser.add_argument("--test-apk", required=True)
    parser.add_argument("--token", default=None,
                        help="32 lowercase hex; must equal the accel_token asset "
                             "baked into --apk (use --print-token to mint one)")
    parser.add_argument("--print-token", action="store_true",
                        help="print a fresh token and exit (for the APK build)")
    parser.add_argument("--mode", choices=("files", "bridge"), default="files",
                        help="files: request/response travel as app-private files "
                             "(robust); bridge: adb-forward the loopback bridge "
                             "(the product shape, unreliable on this host)")
    parser.add_argument("--arg", action="append", default=None,
                        help="one compiler argv element for the synthesised request "
                             "(repeatable); defaults to cc -c -O2 fixture.i -o fixture.o")
    parser.add_argument("--jobs", type=int, default=2,
                        help="sequential distcc jobs; the first is cold")
    parser.add_argument("--fixture-functions", type=int, default=2000)
    parser.add_argument("--memory-mb", type=int, default=2048)
    parser.add_argument("--vsock-port", type=int, default=5103)
    parser.add_argument("--forward-port", type=int, default=0,
                        help="host port for the adb forward; 0 picks a free one "
                             "(a reused port can still carry a stale forward)")
    parser.add_argument("--ready-deadline-s", type=int, default=300)
    parser.add_argument("--job-timeout-s", type=int, default=60,
                        help="per-job client budget; a hung channel must fail "
                             "fast instead of burning the whole run")
    parser.add_argument("--work-dir", required=True,
                        help="ASCII scratch directory outside the repo")
    parser.add_argument("--reference-clang", default=None,
                        help="clang 22.1.8 for the host reference object")
    parser.add_argument("--compare-script", default=str(
        root / "tools" / "compiler-equivalence" / "compare_objects.py"))
    parser.add_argument("--fixtures", default=str(
        root / "tools" / "compiler-equivalence" / "fixtures" / "c"))
    parser.add_argument("--nm", default=None)
    parser.add_argument("--readelf", default=None)
    parser.add_argument("--cc", default=None,
                        help="cross linker driver (NDK aarch64 clang) for level 1")
    parser.add_argument("--alias", default="qa-device")
    parser.add_argument("--output", required=True)
    parser.add_argument("--log-dir", required=True)
    args = parser.parse_args(argv)

    if args.print_token:
        print(secrets.token_hex(16))
        return 0
    if args.token is None:
        print("error=--token is required (mint one with --print-token)", file=sys.stderr)
        return 2
    if len(args.token) != 32 or any(c not in ("0123456789" + "abcdef") for c in args.token):
        print("error=--token must be 32 lowercase hex characters", file=sys.stderr)
        return 2

    if args.forward_port == 0:
        with socket.socket() as probe_socket:
            probe_socket.bind(("127.0.0.1", 0))
            args.forward_port = probe_socket.getsockname()[1]

    work = Path(args.work_dir)
    work.mkdir(parents=True, exist_ok=True)
    Path(args.log_dir).mkdir(parents=True, exist_ok=True)

    doti = work / "fixture.i"
    doti.write_text(build_fixture(args.fixture_functions), encoding="utf-8")

    adb = Adb(args.adb, args.serial)
    adb.discover()
    evidence_name = f"pvm-compiler-{args.token}.json"
    summary: dict = {
        "mode": "dv-pvm-02-compiler",
        "device_alias": args.alias,
        "payload_name": args.payload,
        "token_sha256": __import__("hashlib").sha256(
            args.token.encode("utf-8")).hexdigest(),
        "fixture_functions": args.fixture_functions,
        "fixture_bytes": doti.stat().st_size,
        "jobs_requested": args.jobs,
    }

    if args.mode == "files":
        return files_main(adb, args, root, work, doti, summary)

    adb.run("install", "-r", "-t", args.apk, timeout=1800)
    adb.run("install", "-r", "-t", args.test_apk, timeout=1800)
    for permission in ("android.permission.MANAGE_VIRTUAL_MACHINE",
                       "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"):
        adb.run("shell", "pm", "grant", args.qa_package, permission)
    adb.run("shell", "am", "force-stop", args.qa_package)
    # The evidence file name is keyed by the token, and the token is baked into
    # the APK asset, so it is stable across runs. Drop the previous run's file
    # first or the bridge-port poll below would read a stale port and drive the
    # client into a dead forward.
    adb.run("shell", "run-as", args.qa_package, "rm", "-f",
            f"{EVIDENCE_DIR}/{evidence_name}", check=False)
    adb.run("shell", "svc", "power", "stayon", "usb", check=False)
    # HyperOS freezes a freshly created uid ("freezeUid ... reason=new process")
    # and a frozen process stops accepting on its loopback socket, which from
    # the host looks exactly like a stalled payload. Pin the QA package out of
    # the freezer before any VM work starts.
    adb.run("shell", "am", "set-standby-bucket", args.qa_package, "active", check=False)
    adb.run("shell", "cmd", "appops", "set", args.qa_package,
            "RUN_ANY_IN_BACKGROUND", "allow", check=False)
    adb.run("shell", "dumpsys", "deviceidle", "whitelist", "+" + args.qa_package,
            check=False)
    adb.run("shell", "am", "set-inactive", args.qa_package, "false", check=False)
    adb.run("forward", "--remove", f"tcp:{args.forward_port}", check=False)

    instrument = adb._base() + [
        "shell", "am", "instrument", "-w", "-r",
        "-e", "qaMode", "compiler",
        "-e", "qaRunToken", args.token,
        "-e", "qaPayloadName", args.payload,
        "-e", "qaMemoryMb", str(args.memory_mb),
        "-e", "qaVsockPort", str(args.vsock_port),
        "-e", "qaJobs", str(args.jobs),
    ]
    instrument += [f"{args.qa_package}.test/{INSTRUMENTATION}"]
    instrument_log = Path(args.log_dir) / f"instrument-{int(time.time())}.log"
    rss_before = adb.process_rss_kb("crosvm")
    process = subprocess.Popen(instrument, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True,
                               encoding="utf-8", errors="replace")

    def _unused_foreground() -> None:
        """Make the QA uid foreground so HyperOS stops freezing it.

        HyperOS freezes a process it considers cached a couple of seconds after
        the VM starts: every thread stops, including the bridge's accept loop,
        and the host then only sees an idle socket. A foreground activity makes
        the uid unfreezable. The activity must be started *after* the app
        process exists — started too early, ActivityManager just races the
        instrumentation restart and the foreground state is lost again.
        """
        resolved = adb.run("shell", "cmd", "package", "resolve-activity", "--brief",
                           args.qa_package, check=False).strip().splitlines()
        component = resolved[-1].strip() if resolved else ""
        if "/" in component:
            adb.run("shell", "am", "start", "-n", component, check=False)
        else:
            print("warn=launch activity not resolved; app may freeze",
                  file=sys.stderr)

    bridge = wait_for_bridge(adb, args.qa_package, evidence_name, process,
                             args.ready_deadline_s)
    summary["payload_ready_ms"] = bridge.get("payload_ready_ms")
    summary["create_ms"] = bridge.get("create_ms")
    summary["bridge_port"] = bridge.get("bridge_port")
    summary["capabilities_raw"] = (bridge.get("device") or {}).get("capabilities_raw")
    summary["sdk_int"] = (bridge.get("device") or {}).get("sdk_int")
    summary["abi"] = (bridge.get("device") or {}).get("abi")

    jobs: list[dict] = []
    foreground_qa_app(adb, args.qa_package)
    if not bridge.get("bridge_port"):
        summary["failure_code"] = bridge.get("failure_code") or "BRIDGE_PORT_NEVER_PUBLISHED"
        summary["notes"] = bridge.get("notes")
    else:
        adb.run("forward", f"tcp:{args.forward_port}", f"tcp:{bridge['bridge_port']}")
        summary["forward"] = adb.run("forward", "--list", check=False).strip()
        client = str(root / "tools" / "pvm-compiler" / "axp1_distcc_client.py")
        for index in range(args.jobs):
            output = work / f"device-{index}.o"
            started = time.monotonic()
            try:
                facts, raw = run_job(adb, client, sys.executable, args.forward_port,
                                     args.token, doti, output, args.job_timeout_s)
            except subprocess.TimeoutExpired:
                facts, raw = {"error": "client timeout"}, ""
            (Path(args.log_dir) / f"client-{index}.log").write_text(
                raw, encoding="utf-8", errors="replace")
            if not raw.strip():
                print(f"warn=client {index} produced no output", file=sys.stderr)
            else:
                print(f"client {index}: " + raw.strip().replace("\n", " | "),
                      file=sys.stderr)
            jobs.append({
                "index": index,
                "wall_ms": int((time.monotonic() - started) * 1000),
                "stat": facts.get("stat"),
                "object_bytes": facts.get("object_bytes"),
                "serr_bytes": facts.get("serr_bytes"),
                "serr": (facts.get("serr") or "")[:400],
                "error": facts.get("error"),
                "rst": facts.get("rst"),
                "object_path": str(output) if output.is_file() else None,
            })
        adb.run("forward", "--remove", f"tcp:{args.forward_port}", check=False)
        rss_after = adb.process_rss_kb("crosvm")
        summary["crosvm_vmhwm_kb"] = {"before": rss_before, "after": rss_after}
        # ru_maxrss of the payload process itself is not observable: the guest
        # has no console (vendor debug policy) and the payload does not emit it.
        summary["payload_ru_maxrss_kb"] = "not-collected"
        if not all(job["stat"] == "0" and job["object_path"] for job in jobs):
            summary["failure_code"] = "JOB_FAILED"

    try:
        # The instrumentation bounds its own bridge wait, so this is only a
        # backstop: a longer value just makes a broken run feel hung.
        process.wait(timeout=120)
    except subprocess.TimeoutExpired:
        process.kill()
    instrument_log.write_text(
        process.stdout.read() if process.stdout else "", encoding="utf-8",
        errors="replace")
    final = adb.read_evidence(args.qa_package, evidence_name) or {}
    summary["instrumentation"] = {
        "stage": final.get("stage"),
        "failure_code": final.get("failure_code"),
        "stop_confirmed": final.get("stop_confirmed"),
        "deleted": final.get("deleted"),
        "connections": final.get("connections"),
        "connections_completed": final.get("connections_completed"),
        "bridge_elapsed_ms": final.get("bridge_elapsed_ms"),
        "notes": final.get("notes"),
        "exit_code_after_stop": final.get("exit_code_after_stop"),
        "vm_error_code": final.get("vm_error_code"),
    }
    if summary.get("failure_code") is None and final.get("failure_code"):
        summary["failure_code"] = final.get("failure_code")
    summary["jobs"] = jobs

    # Equivalence against a same-version reference. The reference is the same
    # clang driven through the same -cc1 vector, because the payload's contract
    # is a -cc1 argv, not a driver command line.
    if args.reference_clang and jobs and jobs[0]["object_path"]:
        reference = work / "reference.o"
        ok, detail = compile_reference(args.reference_clang, doti, reference)
        summary["reference"] = {"compiled": ok, "detail": detail,
                                "bytes": reference.stat().st_size if ok else None}
        if ok:
            command = [sys.executable, args.compare_script,
                       "--a", jobs[0]["object_path"], "--b", str(reference),
                       "--fixtures", args.fixtures, "--no-run"]
            for flag, value in (("--cc", args.cc), ("--nm", args.nm),
                                ("--readelf", args.readelf)):
                if value:
                    command += [flag, value]
            compared = subprocess.run(command, capture_output=True, text=True,
                                      encoding="utf-8", errors="replace")
            lines = compared.stdout.strip().splitlines()
            summary["equivalence"] = {
                "verdict": lines[0] if lines else "INCONCLUSIVE",
                "detail": lines[1] if len(lines) > 1 else "",
            }
    elif args.reference_clang:
        summary["reference"] = {"compiled": False, "detail": "no device object"}

    Path(args.output).write_text(json.dumps(summary, indent=2, sort_keys=True),
                                 encoding="utf-8")
    headline = {
        "device_alias": summary["device_alias"],
        "payload_ready_ms": summary["payload_ready_ms"],
        "bridge_port": summary["bridge_port"],
        "failure_code": summary["failure_code"],
        "equivalence": summary.get("equivalence"),
        "jobs": [{k: job[k] for k in ("index", "wall_ms", "stat", "object_bytes")}
                 for job in jobs],
        "stop_confirmed": summary["instrumentation"].get("stop_confirmed"),
        "deleted": summary["instrumentation"].get("deleted"),
    }
    print(json.dumps(headline, indent=2))
    return 0 if summary.get("failure_code") is None else 1


if __name__ == "__main__":
    sys.exit(main())
