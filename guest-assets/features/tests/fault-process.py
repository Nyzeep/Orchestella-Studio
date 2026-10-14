#!/usr/bin/env python3
"""Inject I/O/cancel faults at filesystem and tar boundaries of the external CLI."""
import argparse
import errno
import importlib.util
import json
import os
import threading
import time
from pathlib import Path
import runpy
import subprocess
import sys
import tarfile

root = Path(sys.argv[1]).resolve()
if not (root / ".orchestella-profile-test-root").is_file():
    sys.exit(2)
target = sys.argv[2]
if target in ("held-stdout-timeout", "held-stdout-cancel", "status-io"):
    source = Path(__file__).parents[1] / "profilectl.py"
    spec = importlib.util.spec_from_file_location("profile_process_contract", source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    if target == "status-io":
        read_json = module.read_json
        def unavailable(path, default=None):
            if "features" in path.parts:
                raise OSError(errno.EIO, "synthetic unavailable state")
            return read_json(path, default)
        module.read_json = unavailable
        sys.argv = [str(source), "--test-root", str(root), "--test-runner", str(Path(__file__).with_name("fake-runner.py")), "status", "desktop-labwc"]
        sys.exit(module.main())
    options = argparse.Namespace(test_root=str(root), test_runner=str(Path(__file__).with_name("fake-runner.py")), profile="desktop-labwc")
    controller = module.Controller(options)
    controller.directory.mkdir(parents=True, exist_ok=True)
    if target == "held-stdout-cancel":
        controller.job = "f" * 32
        fixture_path = root / "fixture.json"
        value = json.loads(fixture_path.read_text())
        value["cancel_held_stdout"] = True
        fixture_path.write_text(json.dumps(value))
    before_threads = len(threading.enumerate())
    started = time.monotonic()
    try:
        controller.run(["held-stdout"], timeout=0.3)
        reason = "unexpected-success"
    except module.Failure as error:
        reason = error.reason
    elapsed = time.monotonic() - started
    pid = int((root / "stdout-descendant.pid").read_text())
    statfile = Path("/proc", str(pid), "stat")
    alive = statfile.exists() and statfile.read_text().rsplit(")", 1)[1].split()[0] != "Z"
    result = {"reason": reason, "elapsed": elapsed, "descendant_alive": alive,
              "thread_delta": len(threading.enumerate()) - before_threads}
    print(json.dumps(result))
    expected = "cancelled" if target.endswith("cancel") else "command-timeout"
    sys.exit(0 if reason == expected and elapsed < 1.8 and not alive and result["thread_delta"] == 0 else 1)
worker_arguments = sys.argv[3:]
staging_fault = target in ("extract-cancel", "extract-io", "extract-disk", "extract-tar", "staging-marker-io", "staging-rename-io")
original = os.replace
original_rename = os.rename
original_extract = tarfile.TarFile._extract_member
injected = False
extracted_files = 0
original_popen = subprocess.Popen


def replace(source, destination, *args, **kwargs):
    global injected
    path = Path(destination)
    if not injected and not staging_fault and str(path).replace("\\", "/").endswith(target):
        injected = True
        raise OSError(errno.ENOSPC, "synthetic disk full at atomic publish")
    if not injected and target == "staging-marker-io" and path.name == ".orchestella-input.json" and any(part.startswith(".staging-") for part in path.parts):
        injected = True
        raise OSError(errno.EIO, "synthetic staging marker publish failure")
    return original(source, destination, *args, **kwargs)


def rename(source, destination, *args, **kwargs):
    global injected
    if not injected and target == "staging-rename-io" and Path(source).parent.name.startswith(".staging-"):
        injected = True
        raise OSError(errno.EIO, "synthetic installation publish failure")
    return original_rename(source, destination, *args, **kwargs)


def extract(tar, member, destination, *args, **kwargs):
    global injected, extracted_files
    result = original_extract(tar, member, destination, *args, **kwargs)
    if member.isfile():
        extracted_files += 1
        (root / "extracted-files.txt").write_text(str(extracted_files))
        if not injected and target.startswith("extract-"):
            injected = True
            if target == "extract-cancel":
                job = worker_arguments[worker_arguments.index("--job-id") + 1]
                (root / "var/lib/orchestella/features" / (job + ".cancel")).write_text("{}\n")
            elif target == "extract-tar":
                raise tarfile.ReadError("synthetic tar error after partial extraction")
            else:
                code = errno.ENOSPC if target == "extract-disk" else errno.EIO
                raise OSError(code, "synthetic extraction write failure")
    return result


def popen(command, *args, **kwargs):
    if staging_fault and "--worker" in command:
        worker = list(command[command.index("--worker"):])
        command = [sys.executable, "-B", str(Path(__file__).resolve()), str(root), target, *worker]
    process = original_popen(command, *args, **kwargs)
    (root / "launched.pid").write_text(str(process.pid))
    return process


os.replace = replace
if staging_fault:
    os.rename = rename
    tarfile.TarFile._extract_member = extract
subprocess.Popen = popen
sys.argv = [str(Path(__file__).parents[1] / "profilectl.py"), "--test-root", str(root), "--test-runner",
            str(Path(__file__).with_name("fake-runner.py")), *(worker_arguments if worker_arguments else ["install", "code-server" if staging_fault else "desktop-labwc"])]
runpy.run_path(sys.argv[0], run_name="__main__")
