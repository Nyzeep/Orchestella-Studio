#!/usr/bin/env python3
"""Release gate: a flag-on UML build must ship the fast profile it accounts for.

Why this exists
---------------
review P2-1 (UML payload productization round, 2026-11-12): when
`-Porchestella.uml.bundledFastProfile=true` is passed without
`-Porchestella.uml.fastProfileDir`, the gradle task `stageUmlFastProfile`
records the absence and skips with only a WARN in the build log. The hosted
release gate is the sanctioned exemption (ACR D1: a clean checkout cannot
carry the git-ignored U1 artifacts), but a packaging machine that forgets the
dir would still ship a "flag-on" APK whose UML lane answers MISSING_ASSET —
and the only evidence would be that dead log line. This gate re-proves the
payload at the artifact level, from the APK alone:

  - `assets/uml-fast-profile-v1.sha256` (the accounting manifest
    `stageUmlFastProfile` writes) is read FROM the APK, never from the build
    tree. The script carries no pins of its own: the pins live in
    `UmlKernelAssets.FastProfile.PIN_MANIFEST` and the build already failed
    them against the staged files, so this gate only needs the APK's internal
    consistency — shipped bytes vs the manifest shipped alongside them.
  - every manifest line must match the APK entry it accounts for: the two
    native libraries ship under `lib/arm64-v8a/` (jniLibs unpacking), every
    other accounted artifact (the optional rootfs tar) ships as a java
    resource at the APK root (the `initramfs.cpio.gz` precedent). A digest
    mismatch, a missing entry, or an unparsable manifest line fails the gate
    with the full diff.
  - an APK without the manifest is the honest-degradation shape (a flag-off
    build, or the sanctioned hosted exemption): the runtime answers
    MISSING_ASSET at width 0 and the app still boots, so — unlike the QEMU
    gate, which must refuse — this gate SKIPs with the reason and exits 0.
    `--require-payload` flips that: the packaging machine, which KNOWS it
    passed `-Porchestella.uml.fastProfileDir`, asserts positively that the
    payload is really in the APK; there a skip is a failure. That is the
    closing move for P2-1: the exemption stays honest, and the machine that
    promised the payload proves it.

Standard library only; no network.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import string
import sys
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence

MANIFEST_ENTRY = "assets/uml-fast-profile-v1.sha256"
LIB_DIR = "lib/arm64-v8a/"
# The two artifacts stageUmlFastProfile always stages (linux-fast -> the kernel
# image, stub_exe -> the 16 KB-relinked helper). Named under their packaged
# names; the manifest itself spells packaged names for exactly these two.
REQUIRED_MANIFEST_NAMES: tuple[str, ...] = ("libuml_kernel.so", "libuml_stub.so")


@dataclass(frozen=True)
class Problem:
    entry: str
    reason: str


@dataclass(frozen=True)
class Verified:
    entry: str
    digest: str


@dataclass
class Result:
    status: str  # "ok" | "skip" | "fail" | "error"
    problems: list[Problem] = field(default_factory=list)
    verified: list[Verified] = field(default_factory=list)
    reason: str = ""


def parse_manifest(text: str) -> tuple[dict[str, str], list[Problem]]:
    """`<sha256>  <name>` lines -> ({name: digest}, unparsable-line problems).

    The manifest is machine-written by stageUmlFastProfile, so unlike the
    QEMU gate's lenient parser this one treats every non-blank, non-comment
    line that does not match the grammar as a finding: a manifest that cannot
    be parsed must not be waved through as "no evidence of a problem".
    """
    digests: dict[str, str] = {}
    problems: list[Problem] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        # hexdigits via the stdlib constant: a literal alphabet here would read
        # as a 16-hex run to the sensitive-data gate (token_shape) for no gain.
        if len(parts) != 2 or len(parts[0]) != 64 or any(c not in string.hexdigits for c in parts[0]):
            problems.append(Problem(MANIFEST_ENTRY, f"unparsable manifest line: {line!r}"))
            continue
        digest, name = parts[0].lower(), parts[1]
        if name in digests and digests[name] != digest:
            problems.append(Problem(name, f"manifest accounts for {name!r} twice with different digests"))
        digests[name] = digest
    return digests, problems


def apk_entry_for(manifest_name: str) -> str:
    """The APK entry a packaged manifest name accounts for.

    The gradle staging writes packaged names: the two native libraries unpack
    from jniLibs under `lib/<abi>/`, everything else (the optional rootfs tar)
    ships as a java resource at the APK root.
    """
    if manifest_name.endswith(".so"):
        return LIB_DIR + manifest_name
    return manifest_name


def sha256_of(zip_file: zipfile.ZipFile, entry: str) -> str | None:
    if entry not in zip_file.namelist():
        return None
    digest = hashlib.sha256()
    with zip_file.open(entry) as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_apk(apk: Path, require_payload: bool) -> Result:
    """The artifact-level verdict: ok / skip / fail (error is the caller's)."""
    with zipfile.ZipFile(apk) as archive:
        manifest_text = None
        if MANIFEST_ENTRY in archive.namelist():
            manifest_text = archive.read(MANIFEST_ENTRY).decode("utf-8")

    if manifest_text is None:
        if require_payload:
            return Result(
                status="fail",
                problems=[Problem(
                    MANIFEST_ENTRY,
                    "the APK carries no accounting manifest but --require-payload "
                    "was given: this build was expected to stage the UML fast profile "
                    "(was -Porchestella.uml.fastProfileDir actually passed?)",
                )],
            )
        return Result(
            status="skip",
            reason=(
                "no assets/uml-fast-profile-v1.sha256 in the APK: either the build did "
                "not pass -Porchestella.uml.bundledFastProfile=true, or the staged "
                "staging exemption applied (no -Porchestella.uml.fastProfileDir). The "
                "APK ships no UML kernel and UmlFaceLauncher answers MISSING_ASSET at "
                "width 0 — the honest-degradation shape. Pass --require-payload to "
                "assert the payload positively on a packaging machine."
            ),
        )

    digests, problems = parse_manifest(manifest_text)

    for name in REQUIRED_MANIFEST_NAMES:
        if name not in digests:
            problems.append(Problem(
                name,
                f"the accounting manifest does not account for {name!r} — staging "
                "always stages it, so the manifest is incomplete"
            ))

    verified: list[Verified] = []
    with zipfile.ZipFile(apk) as archive:
        for name, expected in digests.items():
            entry = apk_entry_for(name)
            actual = sha256_of(archive, entry)
            if actual is None:
                problems.append(Problem(entry, f"accounted by the manifest but missing from the APK"))
            elif actual != expected:
                problems.append(Problem(
                    entry,
                    f"sha256 {actual[:16]}… != manifest {expected[:16]}… "
                    "(the shipped bytes are not the staged, pinned bytes)"
                ))
            else:
                verified.append(Verified(entry, actual))

    if problems:
        return Result(status="fail", problems=problems, verified=verified)
    return Result(status="ok", verified=verified)


def _write_zip(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for name, payload in entries.items():
            archive.writestr(name, payload)


def _manifest_text(kernel: bytes, stub: bytes, rootfs: bytes | None) -> str:
    lines = [
        f"{hashlib.sha256(kernel).hexdigest()}  libuml_kernel.so",
        f"{hashlib.sha256(stub).hexdigest()}  libuml_stub.so",
    ]
    if rootfs is not None:
        lines.append(f"{hashlib.sha256(rootfs).hexdigest()}  alpine-minirootfs-3.22.2-aarch64.tar.gz")
    return "".join(line + "\n" for line in lines)


def run_selftest() -> int:
    """Synthetic zip-as-APK fixtures, positive and negative, through main().

    No binary fixtures in the repo: every case builds its own minimal APK in a
    throwaway directory, so the gate's contract is testable anywhere python
    runs (packaging machine included) with zero inputs.
    """
    kernel = b"uml-kernel-bytes"
    stub = b"uml-stub-bytes"
    rootfs = b"rootfs-tar-bytes"
    full_manifest = _manifest_text(kernel, stub, rootfs)
    no_tar_manifest = _manifest_text(kernel, stub, None)

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)

        def apk(name: str, entries: dict[str, bytes]) -> str:
            path = root / name
            _write_zip(path, entries)
            return str(path)

        kernel_entry = LIB_DIR + "libuml_kernel.so"
        stub_entry = LIB_DIR + "libuml_stub.so"
        rootfs_entry = "alpine-minirootfs-3.22.2-aarch64.tar.gz"

        cases: list[tuple[str, list[str], str, str]] = []
        # (label, argv, expected exit code, expected output marker)

        # Positive: full payload with the optional rootfs tar, digests agree.
        cases.append((
            "ok-full-payload",
            ["--apk", apk("ok.apk", {
                kernel_entry: kernel,
                stub_entry: stub,
                rootfs_entry: rootfs,
                MANIFEST_ENTRY: full_manifest.encode(),
            })],
            "0", "UML_PAYLOAD_GATE_OK",
        ))
        # Positive: payload without the optional rootfs tar.
        cases.append((
            "ok-without-optional-tar",
            ["--apk", apk("ok_no_tar.apk", {
                kernel_entry: kernel,
                stub_entry: stub,
                MANIFEST_ENTRY: no_tar_manifest.encode(),
            })],
            "0", "UML_PAYLOAD_GATE_OK",
        ))
        # Negative: shipped kernel bytes are not the manifest's bytes.
        cases.append((
            "fail-digest-mismatch",
            ["--apk", apk("mismatch.apk", {
                kernel_entry: b"different-kernel-bytes",
                stub_entry: stub,
                MANIFEST_ENTRY: full_manifest.encode(),
            })],
            "1", "UML_PAYLOAD_GATE_FAIL",
        ))
        # Negative: the manifest accounts for the stub but the APK lacks it.
        cases.append((
            "fail-missing-stub",
            ["--apk", apk("missing_stub.apk", {
                kernel_entry: kernel,
                MANIFEST_ENTRY: full_manifest.encode(),
            })],
            "1", "UML_PAYLOAD_GATE_FAIL",
        ))
        # Negative: manifest present but incomplete (no kernel line).
        cases.append((
            "fail-incomplete-manifest",
            ["--apk", apk("incomplete.apk", {
                kernel_entry: kernel,
                stub_entry: stub,
                MANIFEST_ENTRY: (
                    f"{hashlib.sha256(stub).hexdigest()}  libuml_stub.so\n"
                ).encode(),
            })],
            "1", "UML_PAYLOAD_GATE_FAIL",
        ))
        # Negative: an unparsable manifest line is a finding, not noise.
        cases.append((
            "fail-unparsable-manifest",
            ["--apk", apk("unparsable.apk", {
                kernel_entry: kernel,
                stub_entry: stub,
                MANIFEST_ENTRY: b"not-a-digest  libuml_kernel.so\n",
            })],
            "1", "UML_PAYLOAD_GATE_FAIL",
        ))
        # Negative: the optional tar is accounted but its bytes were swapped.
        cases.append((
            "fail-rootfs-mismatch",
            ["--apk", apk("rootfs_mismatch.apk", {
                kernel_entry: kernel,
                stub_entry: stub,
                rootfs_entry: b"swapped-rootfs-bytes",
                MANIFEST_ENTRY: full_manifest.encode(),
            })],
            "1", "UML_PAYLOAD_GATE_FAIL",
        ))
        # Skip (auto mode): no manifest — the honest-degradation shape.
        cases.append((
            "skip-no-manifest",
            ["--apk", apk("exempt.apk", {kernel_entry: b"unrelated-native-lib"})],
            "0", "UML_PAYLOAD_GATE_SKIP",
        ))
        # Require mode: the same exempt APK must now FAIL.
        cases.append((
            "fail-require-no-manifest",
            ["--apk", apk("exempt.apk", {kernel_entry: b"unrelated-native-lib"}),
             "--require-payload"],
            "1", "UML_PAYLOAD_GATE_FAIL",
        ))
        # Require mode: a real payload build asserts positively and passes.
        cases.append((
            "ok-require-full-payload",
            ["--apk", apk("ok.apk", {
                kernel_entry: kernel,
                stub_entry: stub,
                rootfs_entry: rootfs,
                MANIFEST_ENTRY: full_manifest.encode(),
            }), "--require-payload"],
            "0", "UML_PAYLOAD_GATE_OK",
        ))
        # Usage error: not a zip at all.
        not_zip = root / "not-a-zip.apk"
        not_zip.write_bytes(b"definitely not a zip")
        cases.append((
            "error-not-a-zip",
            ["--apk", str(not_zip)],
            "2", "UML_PAYLOAD_GATE_ERROR",
        ))
        # Usage error: missing file.
        cases.append((
            "error-missing-file",
            ["--apk", str(root / "does-not-exist.apk")],
            "2", "UML_PAYLOAD_GATE_ERROR",
        ))

        import contextlib
        import io

        failures: list[str] = []
        for label, argv, expected_code, expected_marker in cases:
            buffer = io.StringIO()
            try:
                with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
                    code = main(argv)
            except SystemExit as exc:  # argparse errors, should not happen here
                code = 3
                buffer.write(f"SystemExit({exc.code})")
            output = buffer.getvalue()
            if str(code) != expected_code:
                failures.append(f"{label}: exit {code} != expected {expected_code}\n{output}")
            elif expected_marker not in output:
                failures.append(f"{label}: output missing {expected_marker!r}\n{output}")

        if failures:
            print(f"UML_PAYLOAD_SELFTEST_FAIL {len(failures)}/{len(cases)} cases failed:")
            for failure in failures:
                print("  - " + failure)
            return 1
        print(f"UML_PAYLOAD_SELFTEST_OK all {len(cases)} fixture cases passed "
              "(positive, negative, skip, require and usage-error paths)")
        return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, help="APK to inspect")
    parser.add_argument("--require-payload", action="store_true",
                        help="a missing accounting manifest is a failure, not a skip "
                             "(packaging machine mode: the build DID pass "
                             "-Porchestella.uml.fastProfileDir)")
    parser.add_argument("--json", type=Path, help="write a machine-readable result")
    parser.add_argument("--selftest", action="store_true",
                        help="run the built-in synthetic fixture cases and exit")
    args = parser.parse_args(argv)

    if args.selftest:
        return run_selftest()
    if args.apk is None:
        parser.error("--apk is required (or use --selftest)")

    if not args.apk.is_file():
        print(f"UML_PAYLOAD_GATE_ERROR apk not found: {args.apk}", file=sys.stderr)
        return 2

    try:
        result = check_apk(args.apk, args.require_payload)
    except zipfile.BadZipFile as exc:
        result = Result(status="error", reason=f"not a zip/apk: {exc}")
        print(f"UML_PAYLOAD_GATE_ERROR {result.reason}", file=sys.stderr)
        _write_json(args.json, args, result)
        return 2

    for problem in result.problems:
        print(f"UML_PAYLOAD_GATE_FAIL {problem.entry}: {problem.reason}")
    for item in result.verified:
        print(f"UML_PAYLOAD_GATE_VERIFIED {item.entry}: sha256 {item.digest}")

    if result.status == "skip":
        print(f"UML_PAYLOAD_GATE_SKIP {result.reason}")
    elif result.status == "ok":
        names = ", ".join(item.entry for item in result.verified)
        print(f"UML_PAYLOAD_GATE_OK every accounted fast-profile artifact is present "
              f"and matches the in-APK manifest: {names}")

    if result.status == "fail":
        print(
            "UML_PAYLOAD_GATE_HINT the manifest and the shipped bytes must agree. "
            "Rebuild on the packaging machine with -Porchestella.uml.fastProfileDir "
            "pointing at the pinned U1 artifacts (research/uml-fastboot/manifest.md §3), "
            "or build without the UML flags to ship the honest MISSING_ASSET shape.",
            file=sys.stderr,
        )

    _write_json(args.json, args, result)
    return {"ok": 0, "skip": 0, "fail": 1, "error": 2}[result.status]


def _write_json(path: Path | None, args: argparse.Namespace, result: Result) -> None:
    if path is None:
        return
    path.write_text(
        json.dumps(
            {
                "apk": str(args.apk) if args.apk else None,
                "require_payload": args.require_payload,
                "status": result.status,
                "reason": result.reason,
                "verified": [{"entry": v.entry, "sha256": v.digest} for v in result.verified],
                "problems": [{"entry": p.entry, "reason": p.reason} for p in result.problems],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )


if __name__ == "__main__":
    raise SystemExit(main())
