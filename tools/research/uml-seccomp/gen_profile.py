#!/usr/bin/env python3
"""Generate versioned AOSP-like seccomp profiles for the U3 harness.

The app filter is *not* ALLOWLIST_COMMON + ALLOWLIST_APP.  AOSP's
libc/genseccomp.py composes:

    (SYSCALLS.TXT - SECCOMP_BLOCKLIST_COMMON - SECCOMP_BLOCKLIST_APP)
    | SECCOMP_ALLOWLIST_COMMON | SECCOMP_ALLOWLIST_APP

per architecture, with SECCOMP_RET_TRAP as the default action
(libc/seccomp/seccomp_policy.cpp, Disallow()).

Usage:
  gen_profile.py --generate          # write profile/generated/*.json
  gen_profile.py --check             # fail if checked-in profiles drift
  gen_profile.py --emit-filter PROFILE.json [--allow-extra a,b] \
                 [--deny-extra c] [-o filter.txt]

Exit codes: 0 ok, 1 drift/validation failure, 2 usage error.
"""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PROFILE_DIR = ROOT / "profile"
VENDOR = PROFILE_DIR / "vendor"
GENERATED = PROFILE_DIR / "generated"
ARCHES = ("arm64", "x86_64")
ALL_ARCHES = {"arm", "arm64", "riscv64", "x86", "x86_64"}
LP64 = {"arm64", "riscv64", "x86_64"}
LP32 = {"arm", "x86"}

PROFILE_SCHEMA = "uml-seccomp-profile/1"
COMPOSITION = ("(SYSCALLS.TXT - SECCOMP_BLOCKLIST_COMMON - SECCOMP_BLOCKLIST_APP) "
               "| SECCOMP_ALLOWLIST_COMMON | SECCOMP_ALLOWLIST_APP")


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_arches(text):
    arches = set()
    for token in text.replace(",", " ").split():
        if token == "all":
            arches |= ALL_ARCHES
        elif token == "lp64":
            arches |= LP64
        elif token == "lp32":
            arches |= LP32
        elif token in ALL_ARCHES:
            arches.add(token)
    return arches


def parse_spec_line(line):
    """Parse one bionic SYSCALLS.TXT / allowlist / blocklist line.

    Returns (syscall_name, arch_set) or None for comments/blank/malformed.
    """
    line = line.strip()
    if not line or line.startswith("#"):
        return None
    lparen = line.find("(")
    rparen = line.rfind(")")
    if lparen < 0 or rparen < lparen:
        return None
    head = line[:lparen].strip()
    arches = parse_arches(line[rparen + 1:])
    if not head or not arches:
        return None
    # Some entries carry a return type ("int clock_settime(...)"); keep the last token.
    head = head.split()[-1]
    # func|alias:syscall_name[:socketcall_id]
    if ":" in head:
        head = head.split(":", 1)[1].split(":", 1)[0]
    name = head.split("|", 1)[0].strip()
    return (name, arches) if name else None


def load_names(path, arch):
    names = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        parsed = parse_spec_line(line)
        if parsed and arch in parsed[1]:
            names.add(parsed[0])
    return names


def uapi_path(bionic_ref, arch):
    name = "unistd_generic.h" if arch == "arm64" else "unistd_64_x86.h"
    return VENDOR / bionic_ref / "uapi" / name


def load_numbers(bionic_ref, arch):
    path = uapi_path(bionic_ref, arch)
    if not path.is_file():
        raise ValueError("missing syscall number source: %s" % path)
    nr3264 = {}
    table = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^#define\s+__NR3264_(\w+)\s+(\d+)\s*$", raw)
        if match:
            nr3264[match.group(1)] = int(match.group(2))
            continue
        match = re.match(r"^#define\s+__NR_(\w+)\s+(\S+)\s*$", raw)
        if not match:
            continue
        name, value = match.group(1), match.group(2)
        if value.startswith("__NR3264_"):
            value = nr3264.get(value[len("__NR3264_"):], value)
        if isinstance(value, str) and not value.isdigit():
            continue
        table.setdefault(name, int(value))
    return {"arch": arch, "path": path, "syscalls": table}


def build_profile(ref, arch, refs_doc):
    entry = next((r for r in refs_doc["refs"] if r["id"] == ref), None)
    if entry is None:
        raise ValueError("unknown ref %s" % ref)
    bionic_ref = entry["bionic_ref"]
    src_dir = VENDOR / bionic_ref
    files = {
        "syscalls": src_dir / "SYSCALLS.TXT",
        "allowlist_common": src_dir / "SECCOMP_ALLOWLIST_COMMON.TXT",
        "allowlist_app": src_dir / "SECCOMP_ALLOWLIST_APP.TXT",
        "blocklist_common": src_dir / "SECCOMP_BLOCKLIST_COMMON.TXT",
        "blocklist_app": src_dir / "SECCOMP_BLOCKLIST_APP.TXT",
    }
    for path in files.values():
        if not path.is_file():
            raise ValueError("missing vendored source: %s" % path)

    base = load_names(files["syscalls"], arch)
    blocked = load_names(files["blocklist_common"], arch) | load_names(files["blocklist_app"], arch)
    added = load_names(files["allowlist_common"], arch) | load_names(files["allowlist_app"], arch)
    unknown_blocked = blocked - base
    if unknown_blocked:
        raise ValueError("%s/%s: blocklist entries not in SYSCALLS.TXT: %s"
                         % (bionic_ref, arch, sorted(unknown_blocked)))
    allowed = (base - blocked) | added

    numbers = load_numbers(bionic_ref, arch)
    syscalls = {}
    unmapped = []
    for name in sorted(allowed):
        if name in numbers["syscalls"]:
            syscalls[name] = numbers["syscalls"][name]
        else:
            unmapped.append(name)

    return {
        "schema": PROFILE_SCHEMA,
        "name": "aosp-app-%s-%s" % (ref, arch),
        "arch": arch,
        "android_api": entry.get("api"),
        "aosp_ref": bionic_ref,
        "default_action": "trap",
        "composition": COMPOSITION,
        "source_note": ("AOSP app seccomp policy as compiled by bionic genseccomp.py; "
                        "default action SECCOMP_RET_TRAP (SIGSYS), no argument filters."),
        "numbers_source": {
            "file": str(numbers["path"].relative_to(ROOT)),
            "url": "https://android.googlesource.com/platform/bionic/+/refs/heads/%s/libc/kernel/uapi/%s?format=TEXT"
                   % (bionic_ref, "asm-generic/unistd.h" if arch == "arm64" else "asm-x86/asm/unistd_64.h"),
            "sha256": sha256(numbers["path"]),
        },
        "sources": [{
            "file": str(path.relative_to(ROOT)),
            "url": "https://android.googlesource.com/platform/bionic/+/refs/heads/%s/libc/%s?format=TEXT"
                   % (bionic_ref, path.name),
            "sha256": sha256(path),
            "bytes": path.stat().st_size,
        } for path in files.values()],
        "counts": {
            "base": len(base),
            "blocked": len(blocked),
            "added": len(added),
            "allowed_names": len(allowed),
            "mapped": len(syscalls),
            "unmapped": len(unmapped),
        },
        "syscalls": dict(sorted(syscalls.items())),
        "unmapped": sorted(unmapped),
    }


def generate():
    refs_doc = json.loads((PROFILE_DIR / "refs.json").read_text(encoding="utf-8"))
    GENERATED.mkdir(parents=True, exist_ok=True)
    written = []
    for entry in refs_doc["refs"]:
        for arch in ARCHES:
            profile = build_profile(entry["id"], arch, refs_doc)
            path = GENERATED / ("%s.json" % profile["name"])
            path.write_text(json.dumps(profile, indent=2, ensure_ascii=False) + "\n",
                            encoding="utf-8")
            written.append((path, profile))
            print("%-36s allowed=%-3d mapped=%-3d unmapped=%d"
                  % (path.name, profile["counts"]["allowed_names"],
                     profile["counts"]["mapped"], profile["counts"]["unmapped"]))
    return written


def check():
    refs_doc = json.loads((PROFILE_DIR / "refs.json").read_text(encoding="utf-8"))
    failures = []
    for entry in refs_doc["refs"]:
        for arch in ARCHES:
            profile = build_profile(entry["id"], arch, refs_doc)
            path = GENERATED / ("%s.json" % profile["name"])
            expected = json.dumps(profile, indent=2, ensure_ascii=False) + "\n"
            if not path.is_file():
                failures.append("%s missing" % path.name)
            elif path.read_text(encoding="utf-8") != expected:
                failures.append("%s drift" % path.name)
    if failures:
        for failure in failures:
            print("PROFILE_CHECK_FAIL: %s" % failure, file=sys.stderr)
        return 1
    print("PROFILE_CHECK_OK profiles=%d" % (len(refs_doc["refs"]) * len(ARCHES)))
    return 0


def emit_filter(profile_path, allow_extra, deny_extra, out_path):
    profile = json.loads(Path(profile_path).read_text(encoding="utf-8"))
    arch = profile["arch"]
    numbers = load_numbers(profile["aosp_ref"], arch)["syscalls"]
    allowed = set(profile["syscalls"])
    denied = set()

    def resolve(names, label):
        resolved = set()
        for name in names:
            if name in numbers:
                resolved.add(name)
            else:
                raise ValueError("%s: unknown syscall %r for %s" % (label, name, arch))
        return resolved

    allowed |= resolve(allow_extra, "--allow-extra")
    denied |= resolve(deny_extra, "--deny-extra")
    allowed -= denied

    lines = ["# profile=%s" % profile["name"],
             "# arch=%s default=trap composition=%s" % (arch, profile["composition"])]
    lines += ["name %d %s" % (nr, name) for name, nr in sorted(numbers.items(), key=lambda kv: kv[1])]
    lines += ["allow %d %s" % (numbers[name], name) for name in sorted(allowed)]
    lines += ["deny %d %s" % (numbers[name], name) for name in sorted(denied)]
    text = "\n".join(lines) + "\n"
    if out_path == "-":
        sys.stdout.write(text)
    else:
        Path(out_path).write_text(text, encoding="utf-8")
        print("%s allow=%d deny=%d" % (out_path, len(allowed), len(denied)))
    return 0


def split_names(value):
    return [item for item in value.replace(",", " ").split() if item]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--generate", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--emit-filter", metavar="PROFILE.json")
    parser.add_argument("--allow-extra", default="")
    parser.add_argument("--deny-extra", default="")
    parser.add_argument("-o", "--output", default="-")
    args = parser.parse_args(argv)

    try:
        if args.generate:
            generate()
            return 0
        if args.check:
            return check()
        if args.emit_filter:
            return emit_filter(args.emit_filter, split_names(args.allow_extra),
                               split_names(args.deny_extra), args.output)
    except ValueError as exc:
        print("GEN_PROFILE_ERROR: %s" % exc, file=sys.stderr)
        return 2
    parser.error("choose --generate, --check or --emit-filter")


if __name__ == "__main__":
    sys.exit(main())
