#!/usr/bin/env python3
"""API 35 profile sanity: monotonicity + pipeline drift, check-only (P2-2).

Verifies the checked-in generated profiles against two invariants, without
regenerating anything (the file bytes are never rewritten; this is the
``gen_profile.py --check`` semantic restricted to the android15 lane plus the
R1 §2.3 set-difference assertions):

1. Pipeline drift: ``build_profile("android15", arch)`` — the exact function
   gen_profile.py's ``--generate`` writes with — recomputed in memory from the
   vendored ``android15-release`` inputs must be byte-identical to the
   checked-in ``aosp-app-android15-<arch>.json``. Any drift means the vendored
   inputs or the generator moved after the profile landed; fix the data before
   running any device matrix.
2. Monotonicity (R1 §2.3, the C1/C3 evidence): ``android16 − android15 ==
   {landlock_add_rule, landlock_create_ruleset, landlock_restrict_self,
   mseal}`` and ``android15 − android16 == ∅``, per architecture — and the
   android14 → android15 step is exactly ``+clone3, +epoll_pwait2``. A device
   on API 35 therefore runs the android15 profile and boot evidence may move
   monotonically to the 36 domain, never the other way.

Usage:
  profile_sanity.py --check       # the real invariant check (reads repo data)
  profile_sanity.py --self-test   # synthetic-fixture self-test of the logic

Exit codes: 0 ok, 1 check/self-test failure, 2 input error.

This tool never writes files: it exists so a reviewer can re-derive the
checked-in profile from the vendored sources without touching the tree.
"""

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import gen_profile  # noqa: E402  (same directory; provides build_profile)

GENERATED = HERE / "profile" / "generated"
ARCHES = ("arm64", "x86_64")

# R1 §2.3, verified against the vendored android15/16-release inputs on
# 2026-11-12, identical for arm64 and x86_64.
ANDROID15_TO_16_ADDED = {
    "landlock_add_rule",
    "landlock_create_ruleset",
    "landlock_restrict_self",
    "mseal",
}
# R1 §2.3 secondary row: b3904ee5b9 (epoll_pwait2 wrapper) and b65dec5629
# (clone3 hole) both landed inside android15-release.
ANDROID14_TO_15_ADDED = {"clone3", "epoll_pwait2"}


class SanityError(Exception):
    pass


def load_profile(ref, arch):
    path = GENERATED / ("aosp-app-%s-%s.json" % (ref, arch))
    if not path.is_file():
        raise SanityError("%s: missing generated profile" % path.name)
    doc = json.loads(path.read_text(encoding="utf-8"))
    return doc, path


def set_difference(newer, older):
    """Both directions of the syscall-name set difference between profiles."""
    a = set(newer["syscalls"])
    b = set(older["syscalls"])
    return a - b, b - a


def check_monotonic_step(older_doc, newer_doc, older_ref, newer_ref, expected_added):
    problems = []
    added, removed = set_difference(newer_doc, older_doc)
    if added != expected_added:
        problems.append("%s -> %s: added set mismatch: got %s, expected %s"
                        % (older_ref, newer_ref, sorted(added), sorted(expected_added)))
    if removed:
        problems.append("%s -> %s: non-monotonic removals: %s"
                        % (older_ref, newer_ref, sorted(removed)))
    return problems


def check_counts(doc, ref, arch):
    counts = doc.get("counts", {})
    problems = []
    if doc.get("schema") != gen_profile.PROFILE_SCHEMA:
        problems.append("%s-%s: schema %r" % (ref, arch, doc.get("schema")))
    if doc.get("default_action") != "trap":
        problems.append("%s-%s: default_action %r != trap"
                        % (ref, arch, doc.get("default_action")))
    if counts.get("unmapped") != 0:
        problems.append("%s-%s: unmapped=%r (must be 0: every allowed name maps)"
                        % (ref, arch, counts.get("unmapped")))
    if counts.get("mapped") != counts.get("allowed_names"):
        problems.append("%s-%s: mapped=%r != allowed=%r"
                        % (ref, arch, counts.get("mapped"), counts.get("allowed_names")))
    if doc.get("android_api") is not None and doc.get("aosp_ref") != "main":
        # R1 §5.1: android14->34, 15->35, 16->36, 17->37; main is unversioned.
        expected_api = {"android14": 34, "android15": 35,
                        "android16": 36, "android17": 37}.get(ref)
        if expected_api is not None and doc.get("android_api") != expected_api:
            problems.append("%s-%s: android_api %r != %d"
                            % (ref, arch, doc.get("android_api"), expected_api))
    return problems


def run_check():
    """The real check: recomputed-in-memory vs checked-in, plus monotonicity."""
    refs_doc = json.loads((HERE / "profile" / "refs.json").read_text(encoding="utf-8"))
    known_refs = {entry["id"] for entry in refs_doc["refs"]}
    for ref in ("android14", "android15", "android16"):
        if ref not in known_refs:
            raise SanityError("refs.json has no %s entry" % ref)
    if refs_doc.get("refs") and next(
            e for e in refs_doc["refs"] if e["id"] == "android15")["bionic_ref"] \
            != "android15-release":
        raise SanityError("android15 must lock refs/heads/android15-release (R1 §2.5)")

    problems = []
    for arch in ARCHES:
        by_ref = {}
        for ref in ("android14", "android15", "android16"):
            doc, path = load_profile(ref, arch)
            by_ref[ref] = doc
            # 1a. pipeline drift: build_profile recomputed in memory.
            rebuilt = gen_profile.build_profile(ref, arch, refs_doc)
            expected = json.dumps(rebuilt, indent=2, ensure_ascii=False) + "\n"
            actual = path.read_text(encoding="utf-8")
            if actual != expected:
                problems.append("%s: drift vs gen_profile pipeline "
                                "(vendored inputs or generator moved)" % path.name)
            # 1b. structural sanity of the checked-in document itself.
            problems.extend(check_counts(doc, ref, arch))
        # 2. monotonicity (R1 §2.3), on the checked-in profiles.
        problems.extend(check_monotonic_step(
            by_ref["android14"], by_ref["android15"], "android14", "android15",
            ANDROID14_TO_15_ADDED))
        problems.extend(check_monotonic_step(
            by_ref["android15"], by_ref["android16"], "android15", "android16",
            ANDROID15_TO_16_ADDED))
        # The R1 headline count for the device-b lane (API 35 arm64): 216
        # allowed / 0 unmapped. Checked explicitly so a silent policy widening
        # cannot pass unnoticed.
        counts = by_ref["android15"].get("counts", {})
        if arch == "arm64" and counts.get("allowed_names") != 216:
            problems.append("android15-arm64: allowed_names=%r != 216 (R1 §2.3)"
                            % counts.get("allowed_names"))

    if problems:
        for problem in problems:
            print("PROFILE_SANITY_FAIL: %s" % problem, file=sys.stderr)
        return 1
    print("PROFILE_SANITY_OK arches=%d pipeline=android15-release "
          "monotonic=14->15->16" % len(ARCHES))
    return 0


# --- self-test: the logic against synthetic fixtures --------------------------

def self_test():
    if not __debug__:
        print("PROFILE_SANITY_SELF_TEST_FAIL: run without -O/PYTHONOPTIMIZE",
              file=sys.stderr)
        return 1

    def profile(names, ref="android15", api=35):
        return {
            "schema": gen_profile.PROFILE_SCHEMA,
            "name": "aosp-app-%s-arm64" % ref,
            "arch": "arm64",
            "android_api": api,
            "aosp_ref": ref + "-release",
            "default_action": "trap",
            "counts": {"unmapped": 0, "mapped": len(names),
                       "allowed_names": len(names)},
            "syscalls": {name: index for index, name in enumerate(sorted(names))},
        }

    checks = 0
    # The monotonic happy path.
    older = profile({"a", "b", "c"})
    newer = profile({"a", "b", "c", "landlock_add_rule", "mseal"}, ref="android16", api=36)
    problems = check_monotonic_step(older, newer, "android15", "android16",
                                    {"landlock_add_rule", "mseal"})
    assert problems == [], problems
    assert check_counts(newer, "android16", "arm64") == []
    checks += 2

    # A removal is non-monotonic, and a wrong added set is reported with the
    # exact diff.
    shrunk = profile({"a", "b"}, ref="android16", api=36)
    problems = check_monotonic_step(older, shrunk, "android15", "android16",
                                    {"landlock_add_rule", "mseal"})
    assert any("non-monotonic removals" in line and "'c'" in line
               for line in problems), problems
    assert any("added set mismatch" in line for line in problems), problems
    checks += 2

    # Bad counts / schema / api mapping are caught.
    broken = profile({"a"})
    broken["counts"]["unmapped"] = 3
    assert any("unmapped" in line for line in check_counts(broken, "android15", "arm64"))
    broken2 = profile({"a"}, ref="android15")
    broken2["default_action"] = "kill"
    assert any("trap" in line for line in check_counts(broken2, "android15", "arm64"))
    broken3 = profile({"a"}, ref="android15")
    broken3["android_api"] = 36
    assert any("android_api" in line for line in check_counts(broken3, "android15", "arm64"))
    checks += 3

    # The vendored android15 must lock android15-release (the §2.5 correction:
    # the profile already exists; the lock is the branch, not a new file).
    refs_doc = json.loads((HERE / "profile" / "refs.json").read_text(encoding="utf-8"))
    lock = {e["id"]: e["bionic_ref"] for e in refs_doc["refs"]}
    assert lock["android15"] == "android15-release", lock
    assert lock["android14"] == "android14-release", lock
    checks += 1

    print("PROFILE_SANITY_SELF_TEST_OK checks=%d" % checks)
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="verify the checked-in profiles (never writes)")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)

    if args.self_test:
        try:
            return self_test()
        except AssertionError as exc:
            print("PROFILE_SANITY_SELF_TEST_FAIL: %s" % exc, file=sys.stderr)
            return 1
    if not args.check:
        parser.error("choose --check or --self-test")
    try:
        return run_check()
    except (SanityError, ValueError, KeyError,
            json.JSONDecodeError, OSError) as exc:
        print("PROFILE_SANITY_INPUT_ERROR: %s" % exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
