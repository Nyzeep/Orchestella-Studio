#!/usr/bin/env python3
"""UML seccomp compatibility matrix aggregator (U3).

Reads harness output directories written by run_uml_seccomp.sh and emits a
pass/fail/unknown matrix plus denied-syscall aggregation.

Public interface (the seam exercised by --self-test):
  python3 matrix.py --self-test
  python3 matrix.py --cases CASES.json --run-dir DIR [--run-dir DIR ...]
                    [--format markdown|json] [--mode aosp|trace|baseline]
                    [--profile PROFILE.json]

`--mode` selects which runs belong to the matrix; the default is `aosp`
(the enforcing run).  `trace` runs are diagnostic: denied syscalls are
reported but do not fail the row.  `baseline` runs are controls and are
never counted as AOSP evidence.

Exit codes: 0 parsed (unknown allowed), 2 invalid input, 1 self-test failure.
"""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

SCHEMA = "uml-seccomp-matrix/1"
FIXTURES = Path(__file__).resolve().parent / "fixtures" / "matrix"
CONSOLE_NAMES = ("console.log", "console.txt")
STDERR_NAMES = ("stderr.log", "stderr.txt")


class InputError(Exception):
    pass


def parse_env(path):
    values = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise InputError("%s: malformed line %r" % (path, raw))
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def read_text(run_dir, names):
    for name in names:
        path = Path(run_dir) / name
        if path.is_file():
            return path.read_text(encoding="utf-8", errors="replace")
    return ""


def parse_denied(text):
    denied = []
    for line in text.splitlines():
        if not line.startswith("SECCOMP_DENY"):
            continue
        fields = dict(re.findall(r"(\w+)=(\S+)", line))
        name = fields.get("name") or ("nr-%s" % fields.get("nr", "?"))
        if name not in denied:
            denied.append(name)
    return denied


def classify(case, env, console_text, stderr_text,
             expected_profile="", expected_mode="", expected_sha=""):
    """Return (status, reason, denied_names)."""
    if env is None:
        return "unknown", "not-run", []
    denied = parse_denied(console_text + "\n" + stderr_text)
    if env.get("STATUS", "ok") != "ok":
        return "unknown", env.get("UNSUPPORTED_REASON") or env.get("STATUS", "harness-error"), denied
    if expected_mode and env.get("MODE") and env["MODE"] != expected_mode:
        return "unknown", "mode-mismatch", denied
    if expected_profile and env.get("PROFILE") and env["PROFILE"] != expected_profile:
        return "unknown", "profile-mismatch", denied
    if expected_sha and env.get("PROFILE_SHA256") and env["PROFILE_SHA256"] != expected_sha:
        return "unknown", "profile-hash-mismatch", denied
    mode = env.get("MODE", "")
    if env.get("TIMED_OUT") == "1":
        return "fail", "timeout", denied
    if mode == "trace":
        # Diagnostic run: out-of-profile syscalls are expected and continued.
        if denied:
            pass
    elif denied:
        return "fail", "denied-syscall", denied
    if env.get("SIGNAL") == "31":
        return "fail", "sigsys", denied
    if env.get("RC") not in (None, "0", ""):
        return "fail", "exit-%s" % env.get("RC"), denied
    for marker in case.get("markers", []):
        if marker not in console_text:
            return "unknown", "marker-missing: %s" % marker, denied
    return "pass", "", denied


def sha256_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def build_matrix(cases_path, run_dirs, mode="aosp", profile_path=None):
    cases_doc = json.loads(Path(cases_path).read_text(encoding="utf-8"))
    if cases_doc.get("schema") != "uml-seccomp-cases/1":
        raise InputError("%s: unsupported schema %r" % (cases_path, cases_doc.get("schema")))
    cases = cases_doc.get("cases", [])
    if not cases:
        raise InputError("%s: no cases" % cases_path)

    expected_sha = sha256_file(profile_path) if profile_path else ""
    runs = {}
    for run_dir in run_dirs:
        env_path = Path(run_dir) / "harness.env"
        if not env_path.is_file():
            raise InputError("%s: harness.env missing" % run_dir)
        env = parse_env(env_path)
        case_id = env.get("CASE")
        if not case_id:
            raise InputError("%s: CASE missing" % env_path)
        if case_id in runs:
            raise InputError("duplicate run for case %s" % case_id)
        runs[case_id] = (
            env,
            read_text(run_dir, CONSOLE_NAMES),
            read_text(run_dir, STDERR_NAMES),
        )

    rows = []
    expected_profile = cases_doc.get("profile", "")
    for case in cases:
        case_id = case.get("id")
        if not case_id:
            raise InputError("%s: case without id" % cases_path)
        env, console, stderr = runs.get(case_id, (None, "", ""))
        status, reason, denied = classify(
            case, env, console, stderr, expected_profile, mode, expected_sha)
        if status == "unknown" and not case.get("device_entry"):
            raise InputError("case %s: unknown requires device_entry" % case_id)
        rows.append({
            "id": case_id,
            "title": case.get("title", case_id),
            "status": status,
            "reason": reason,
            "profile": (env or {}).get("PROFILE", ""),
            "mode": (env or {}).get("MODE", ""),
            "denied": denied,
            "device_entry": case.get("device_entry", ""),
        })

    summary = {"pass": 0, "fail": 0, "unknown": 0}
    for row in rows:
        summary[row["status"]] += 1
    denied_all = sorted({name for row in rows for name in row["denied"]})
    return {"schema": SCHEMA, "profile": expected_profile, "mode": mode,
            "cases": rows, "summary": summary, "denied_syscalls": denied_all}


def render_markdown(matrix):
    lines = [
        "| case | status | reason | denied | profile |",
        "| --- | --- | --- | --- | --- |",
    ]
    for row in matrix["cases"]:
        lines.append("| %s | %s | %s | %s | %s |" % (
            row["id"], row["status"], row["reason"] or "-",
            ", ".join(row["denied"]) or "-", row["profile"] or "-"))
    return "\n".join(lines)


def self_test():
    if not __debug__:
        print("UML_SECCOMP_MATRIX_SELF_TEST_FAIL: run without -O/PYTHONOPTIMIZE",
              file=sys.stderr)
        return 1

    cases_path = FIXTURES / "cases.json"
    run_dirs = [
        FIXTURES / "run-pass",
        FIXTURES / "run-sigsys",
        FIXTURES / "run-timeout",
        FIXTURES / "run-missing-marker",
        FIXTURES / "run-unsupported",
        FIXTURES / "run-wrong-profile",
        FIXTURES / "run-baseline",
    ]
    matrix = build_matrix(cases_path, run_dirs, mode="aosp")
    rows = {row["id"]: row for row in matrix["cases"]}

    checks = 0

    hostfs = rows["hostfs-boot"]
    assert hostfs["status"] == "pass", hostfs
    assert hostfs["denied"] == [], hostfs
    checks += 2

    clone = rows["clone"]
    assert clone["status"] == "fail", clone
    assert clone["reason"] == "sigsys", clone
    assert clone["denied"] == [], clone
    checks += 3

    mconsole = rows["mconsole"]
    assert mconsole["status"] == "fail", mconsole
    assert mconsole["reason"] == "timeout", mconsole
    checks += 2

    ubd0 = rows["ubd0-boot"]
    assert ubd0["status"] == "unknown", ubd0
    assert ubd0["reason"].startswith("marker-missing"), ubd0
    assert ubd0["device_entry"], ubd0
    checks += 3

    ncpus2 = rows["ncpus-2"]
    assert ncpus2["status"] == "unknown", ncpus2
    assert ncpus2["reason"] == "upstream-uml-has-no-ncpus", ncpus2
    checks += 2

    vector = rows["vector-net"]
    assert vector["status"] == "unknown", vector
    assert vector["reason"] == "profile-mismatch", vector
    checks += 2

    sigchld = rows["sigchld"]
    assert sigchld["status"] == "unknown", sigchld
    assert sigchld["reason"] == "mode-mismatch", sigchld
    checks += 2

    mount = rows["mount"]
    assert mount["status"] == "unknown", mount
    assert mount["reason"] == "not-run", mount
    checks += 2

    assert matrix["summary"] == {"pass": 1, "fail": 2, "unknown": 9}, matrix["summary"]
    checks += 1

    text = render_markdown(matrix)
    assert "| hostfs-boot | pass |" in text, text
    assert "| clone | fail |" in text, text
    assert "not-run" in text, text
    checks += 3

    json.dumps(matrix)
    checks += 1

    # Trace runs are diagnostic: denied syscalls are reported, not failed.
    trace = build_matrix(cases_path, [FIXTURES / "run-trace-denied"], mode="trace")
    trace_rows = {row["id"]: row for row in trace["cases"]}
    trace_hostfs = trace_rows["hostfs-boot"]
    assert trace_hostfs["status"] == "pass", trace_hostfs
    assert trace_hostfs["denied"] == ["epoll_wait", "rseq"], trace_hostfs
    assert trace["denied_syscalls"] == ["epoll_wait", "rseq"], trace["denied_syscalls"]
    checks += 3

    # A trace run must not be accepted as AOSP evidence.
    mixed = build_matrix(cases_path, [FIXTURES / "run-trace-denied"], mode="aosp")
    assert {row["id"]: row for row in mixed["cases"]}["hostfs-boot"]["reason"] == "mode-mismatch"
    checks += 1

    try:
        build_matrix(FIXTURES / "cases-missing-device-entry.json", [])
    except InputError:
        checks += 1
    else:
        raise AssertionError("unknown without device_entry must be rejected")

    import contextlib
    import io
    import tempfile
    with contextlib.redirect_stderr(io.StringIO()):
        assert main(["--cases", str(FIXTURES / "cases-missing-device-entry.json")]) == 2
    checks += 1

    profile = Path(__file__).resolve().parent / "profile" / "generated" / \
        "aosp-app-android17-x86_64.json"
    hash_matrix = build_matrix(cases_path, [FIXTURES / "run-wrong-hash"],
                               mode="aosp", profile_path=profile)
    assert {row["id"]: row for row in hash_matrix["cases"]}["personality"]["reason"] == \
        "profile-hash-mismatch"
    checks += 1

    with tempfile.TemporaryDirectory() as tmp:
        run_dir = Path(tmp) / "bad"
        run_dir.mkdir()
        (run_dir / "harness.env").write_text("CASE=hostfs-boot\nBROKEN\n", encoding="utf-8")
        try:
            build_matrix(cases_path, [run_dir])
        except InputError:
            checks += 1
        else:
            raise AssertionError("malformed harness.env must be rejected")

    print("UML_SECCOMP_MATRIX_SELF_TEST_OK checks=%d" % checks)
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--cases")
    parser.add_argument("--run-dir", action="append", default=[])
    parser.add_argument("--format", choices=["markdown", "json"], default="markdown")
    parser.add_argument("--mode", choices=["aosp", "trace", "baseline"], default="aosp")
    parser.add_argument("--profile")
    args = parser.parse_args(argv)

    if args.self_test:
        try:
            return self_test()
        except AssertionError as exc:
            print("UML_SECCOMP_MATRIX_SELF_TEST_FAIL: %s" % exc, file=sys.stderr)
            return 1

    if not args.cases:
        parser.error("--cases is required unless --self-test is used")

    try:
        matrix = build_matrix(Path(args.cases), [Path(p) for p in args.run_dir],
                              mode=args.mode,
                              profile_path=Path(args.profile) if args.profile else None)
    except (InputError, OSError, json.JSONDecodeError) as exc:
        print("UML_SECCOMP_MATRIX_INPUT_ERROR: %s" % exc, file=sys.stderr)
        return 2
    if args.format == "json":
        print(json.dumps(matrix, indent=2, ensure_ascii=False))
    else:
        print(render_markdown(matrix))
    return 0


if __name__ == "__main__":
    sys.exit(main())
