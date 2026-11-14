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
    `stageUmlFastProfile` writes) and `assets/uml-net-helper-v1.sha256`
    (the Gate 2 pair `stageUmlNetHelper` writes, added 2026-11-14) are read
    FROM the APK, never from the build tree. The script carries no pins of
    its own: the pins live in `UmlKernelAssets.FastProfile.PIN_MANIFEST` and
    the gradle `umlNetHelperPins` map, and the build already failed the
    staged files against them, so this gate only needs the APK's internal
    consistency — shipped bytes vs the manifests shipped alongside them.
  - every manifest line must match the APK entry it accounts for: the native
    libraries ship under `lib/arm64-v8a/` (jniLibs unpacking), every other
    accounted artifact (the optional rootfs tar) ships as a java resource at
    the APK root (the `initramfs.cpio.gz` precedent). A digest mismatch, a
    missing entry, or an unparsable manifest line fails the gate with the
    full diff.
  - an APK with neither manifest is the honest-degradation shape (a flag-off
    build, or the sanctioned hosted exemption): the runtime answers
    MISSING_ASSET at width 0 and the app still boots, so — unlike the QEMU
    gate, which must refuse — this gate SKIPs with the reason and exits 0.
    `--require-payload` flips that: the packaging machine, which KNOWS it
    staged the UML artifacts, asserts positively that the payload is really
    in the APK; there a skip is a failure. That is the closing move for
    P2-1: the exemption stays honest, and the machine that promised the
    payload proves it.
  - `--fullpkg` (2026-11-14 single-package round) is the complete-package
    smoke: both UML manifests, the embedded rootfs tar, the pVM compiler
    manifest (`assets/PVM-COMPILER-MANIFEST`, its three pinned libraries
    reconciled against the shipped APK entries) and the QEMU manifest
    (`assets/runtime-artifacts-v1.sha256`, every entry reconciled) in one
    pass. A fullpkg build carries every engine in one APK; this is the
    artifact-level proof that nothing was left behind.

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

FAST_MANIFEST_ENTRY = "assets/uml-fast-profile-v1.sha256"
NET_HELPER_MANIFEST_ENTRY = "assets/uml-net-helper-v1.sha256"
PVM_MANIFEST_ENTRY = "assets/PVM-COMPILER-MANIFEST"
QEMU_MANIFEST_ENTRY = "assets/runtime-artifacts-v1.sha256"
LIB_DIR = "lib/arm64-v8a/"
# The two artifacts stageUmlFastProfile always stages (linux-fast -> the kernel
# image, stub_exe -> the 16 KB-relinked helper). Named under their packaged
# names; the manifest itself spells packaged names for exactly these two.
REQUIRED_FAST_NAMES: tuple[str, ...] = ("libuml_kernel.so", "libuml_stub.so")
# The Gate 2 pair stageUmlNetHelper always stages (umnet supervisor + passt).
REQUIRED_NET_HELPER_NAMES: tuple[str, ...] = ("libuml_net.so", "libuml_passt.so")
# The embedded rootfs tar a full-package build must carry (the provisioner's
# embedded channel consumes it; the CDN chain stays the repair channel).
FULLPKG_ROOTFS_TAR = "alpine-minirootfs-3.22.2-aarch64.tar.gz"
# The pVM payload entries a full-package build must carry: the manifest-pinned
# compiler libraries plus the two payload libraries (payload_binary per the
# manifest, and the AVF lane payload).
PVM_REQUIRED_SOS: tuple[str, ...] = (
    "libfastlane_payload.so",
    "libpvm_compiler_payload.so",
    "libLLVM.so",
    "libclang-cpp.so",
    "libc++_shared.so",
)
# QEMU manifest key -> APK entry. The kernel and the notices ship under
# assets/, the initramfs as a java resource at the APK root, the native
# library under lib/<abi>/.
QEMU_ENTRY_FOR_KEY: dict[str, str] = {
    "Image": "assets/Image",
    "initramfs.cpio.gz": "initramfs.cpio.gz",
    "QEMU-BUILD-INPUTS": "assets/QEMU-BUILD-INPUTS",
    "QEMU-GPL-SOURCES.md": "assets/QEMU-GPL-SOURCES.md",
    "LICENSE.GPL-2.0": "assets/LICENSE.GPL-2.0",
    "NOTICE.QEMU": "assets/NOTICE.QEMU",
}


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


def parse_manifest(text: str, manifest_entry: str) -> tuple[dict[str, str], list[Problem]]:
    """`<sha256>  <name>` lines -> ({name: digest}, unparsable-line problems).

    The manifests are machine-written by the staging tasks, so unlike the
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
            problems.append(Problem(manifest_entry, f"unparsable manifest line: {line!r}"))
            continue
        digest, name = parts[0].lower(), parts[1]
        if name in digests and digests[name] != digest:
            problems.append(Problem(name, f"manifest accounts for {name!r} twice with different digests"))
        digests[name] = digest
    return digests, problems


def apk_entry_for(manifest_name: str) -> str:
    """The APK entry a packaged manifest name accounts for.

    The gradle staging writes packaged names: the native libraries unpack
    from jniLibs under `lib/<abi>/`, everything else (the optional rootfs
    tar) ships as a java resource at the APK root.
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


def reconcile_manifest(
    archive: zipfile.ZipFile,
    manifest_entry: str,
    required_names: tuple[str, ...],
) -> tuple[list[Problem], list[Verified], bool]:
    """One accounting manifest against the APK entries. Returns
    (problems, verified, manifest_present)."""
    problems: list[Problem] = []
    verified: list[Verified] = []
    if manifest_entry not in archive.namelist():
        return problems, verified, False
    digests, parse_problems = parse_manifest(
        archive.read(manifest_entry).decode("utf-8"), manifest_entry)
    problems.extend(parse_problems)
    for name in required_names:
        if name not in digests:
            problems.append(Problem(
                name,
                f"the accounting manifest {manifest_entry} does not account for {name!r} — "
                "staging always stages it, so the manifest is incomplete"
            ))
    for name, expected in digests.items():
        entry = apk_entry_for(name)
        actual = sha256_of(archive, entry)
        if actual is None:
            problems.append(Problem(entry, "accounted by the manifest but missing from the APK"))
        elif actual != expected:
            problems.append(Problem(
                entry,
                f"sha256 {actual[:16]}… != manifest {expected[:16]}… "
                "(the shipped bytes are not the staged, pinned bytes)"
            ))
        else:
            verified.append(Verified(entry, actual))
    return problems, verified, True


def check_pvm_manifest(archive: zipfile.ZipFile) -> tuple[list[Problem], list[Verified]]:
    """assets/PVM-COMPILER-MANIFEST against the shipped lib/arm64-v8a/ entries.

    The manifest is the build's own pin source (generated by
    tools/pvm-compiler/assemble_payload_libs.py): its declared libraries and
    digests must match the APK's shipped bytes. Also asserts the two payload
    libraries the manifest describes (payload_binary) or carries implicitly
    (the AVF lane payload) are present — presence-level, since the manifest
    pins only the compiler-side libraries.
    """
    problems: list[Problem] = []
    verified: list[Verified] = []
    names = archive.namelist()
    if PVM_MANIFEST_ENTRY not in names:
        problems.append(Problem(PVM_MANIFEST_ENTRY, "missing from the APK"))
        return problems, verified
    try:
        manifest = json.loads(archive.read(PVM_MANIFEST_ENTRY).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        problems.append(Problem(PVM_MANIFEST_ENTRY, f"unreadable JSON: {exc}"))
        return problems, verified
    declared = manifest.get("libs") if isinstance(manifest, dict) else None
    if not isinstance(declared, list) or not declared:
        problems.append(Problem(PVM_MANIFEST_ENTRY, "field 'libs' must be a non-empty array"))
        declared = []
    for item in declared:
        if not isinstance(item, dict):
            problems.append(Problem(PVM_MANIFEST_ENTRY, "every libs entry must be a JSON object"))
            continue
        name = item.get("name")
        pin = item.get("sha256")
        if not isinstance(name, str) or not isinstance(pin, str):
            problems.append(Problem(PVM_MANIFEST_ENTRY, f"malformed libs entry: {item!r}"))
            continue
        entry = LIB_DIR + name
        actual = sha256_of(archive, entry)
        if actual is None:
            problems.append(Problem(entry, "pinned by PVM-COMPILER-MANIFEST but missing from the APK"))
        elif actual != pin.lower():
            problems.append(Problem(
                entry,
                f"sha256 {actual[:16]}… != PVM-COMPILER-MANIFEST pin {pin[:16]}… "
                "(strip or packaging corruption; the shipped bytes are not the pinned bytes)",
            ))
        else:
            verified.append(Verified(entry, actual))
    payload_binary = manifest.get("payload_binary") if isinstance(manifest, dict) else None
    if isinstance(payload_binary, str) and sha256_of(archive, LIB_DIR + payload_binary) is None:
        problems.append(Problem(
            LIB_DIR + payload_binary,
            "the manifest's payload_binary is missing from the APK",
        ))
    for name in PVM_REQUIRED_SOS:
        if LIB_DIR + name not in names:
            problems.append(Problem(LIB_DIR + name, "pVM payload entry missing from the APK"))
    return problems, verified


def check_qemu_manifest(archive: zipfile.ZipFile) -> tuple[list[Problem], list[Verified]]:
    """assets/runtime-artifacts-v1.sha256: every entry reconciled, full-strength.

    The dedicated QEMU gate (check_runtime_payload.py) reconciles the three
    runtime-critical entries; the single-package smoke is stricter — all
    seven entries (the two notice/build-input text files too) because a
    fullpkg APK claiming the complete engine set has no honest excuse for a
    partially stale manifest.
    """
    problems: list[Problem] = []
    verified: list[Verified] = []
    if QEMU_MANIFEST_ENTRY not in archive.namelist():
        problems.append(Problem(QEMU_MANIFEST_ENTRY, "missing from the APK"))
        return problems, verified
    digests, _ = parse_manifest(
        archive.read(QEMU_MANIFEST_ENTRY).decode("utf-8"), QEMU_MANIFEST_ENTRY)
    for key, expected in digests.items():
        entry = QEMU_ENTRY_FOR_KEY.get(key, key)
        actual = sha256_of(archive, entry)
        if actual is None:
            problems.append(Problem(entry, f"manifest names {key!r} but the entry is missing from the APK"))
        elif actual != expected:
            problems.append(Problem(entry, f"sha256 {actual[:16]}… != manifest {expected[:16]}…"))
        else:
            verified.append(Verified(entry, actual))
    if "lib/arm64-v8a/libdroidlayer_qemu.so" not in digests:
        problems.append(Problem(
            "lib/arm64-v8a/libdroidlayer_qemu.so",
            "the QEMU manifest does not account for the QEMU native library",
        ))
    return problems, verified


def check_apk(apk: Path, require_payload: bool, fullpkg: bool) -> Result:
    """The artifact-level verdict: ok / skip / fail (error is the caller's)."""
    problems: list[Problem] = []
    verified: list[Verified] = []
    with zipfile.ZipFile(apk) as archive:
        fast_problems, fast_verified, fast_present = reconcile_manifest(
            archive, FAST_MANIFEST_ENTRY, REQUIRED_FAST_NAMES)
        net_problems, net_verified, net_present = reconcile_manifest(
            archive, NET_HELPER_MANIFEST_ENTRY, REQUIRED_NET_HELPER_NAMES)
        problems.extend(fast_problems)
        problems.extend(net_problems)
        verified.extend(fast_verified)
        verified.extend(net_verified)

        if fullpkg:
            pvm_problems, pvm_verified = check_pvm_manifest(archive)
            qemu_problems, qemu_verified = check_qemu_manifest(archive)
            problems.extend(pvm_problems)
            problems.extend(qemu_problems)
            verified.extend(pvm_verified)
            verified.extend(qemu_verified)
            # The embedded rootfs tar: the fast-profile manifest must account
            # for it and the APK must carry it (reconcile_manifest already
            # proved the bytes when the line exists; here the line itself is
            # required).
            fast_digests, _ = parse_manifest(
                archive.read(FAST_MANIFEST_ENTRY).decode("utf-8"), FAST_MANIFEST_ENTRY
            ) if FAST_MANIFEST_ENTRY in archive.namelist() else ({}, [])
            if FULLPKG_ROOTFS_TAR not in fast_digests:
                problems.append(Problem(
                    FULLPKG_ROOTFS_TAR,
                    "the single-package build must embed the rootfs baseline tar "
                    "(the fast-profile accounting manifest must name it)",
                ))
            elif FULLPKG_ROOTFS_TAR not in archive.namelist():
                problems.append(Problem(
                    FULLPKG_ROOTFS_TAR,
                    "accounted by the fast-profile manifest but missing from the APK",
                ))
            for name in REQUIRED_NET_HELPER_NAMES:
                if LIB_DIR + name not in archive.namelist():
                    problems.append(Problem(LIB_DIR + name, "net-helper entry missing from the APK"))
            for name in REQUIRED_FAST_NAMES:
                if LIB_DIR + name not in archive.namelist():
                    problems.append(Problem(LIB_DIR + name, "fast-profile entry missing from the APK"))
            if "assets/NOTICE.UML" not in archive.namelist():
                problems.append(Problem(
                    "assets/NOTICE.UML",
                    "the single-package build ships the UML engine and must ship its notice",
                ))

    if not fast_present and not net_present:
        if require_payload or fullpkg:
            return Result(
                status="fail",
                problems=[Problem(
                    FAST_MANIFEST_ENTRY,
                    "the APK carries neither UML accounting manifest but "
                    + ("--fullpkg" if fullpkg else "--require-payload")
                    + " was given: this build was expected to stage the UML payloads "
                    "(were -Porchestella.uml.fastProfileDir / -Porchestella.uml.netHelperDir "
                    "actually passed?)",
                )],
            )
        return Result(
            status="skip",
            reason=(
                "no UML accounting manifests in the APK: either the build did not enable "
                "the UML flags, or the staged staging exemption applied (no fastProfileDir "
                "/ netHelperDir). The APK ships no UML kernel and UmlFaceLauncher answers "
                "MISSING_ASSET at width 0 — the honest-degradation shape. Pass "
                "--require-payload to assert the payload positively on a packaging machine."
            ),
        )

    if problems:
        return Result(status="fail", problems=problems, verified=verified)
    if fullpkg:
        return Result(status="ok", verified=verified)
    return Result(status="ok", verified=verified)


def _write_zip(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for name, payload in entries.items():
            archive.writestr(name, payload)


def _fast_manifest_text(kernel: bytes, stub: bytes, rootfs: bytes | None) -> str:
    lines = [
        f"{hashlib.sha256(kernel).hexdigest()}  libuml_kernel.so",
        f"{hashlib.sha256(stub).hexdigest()}  libuml_stub.so",
    ]
    if rootfs is not None:
        lines.append(f"{hashlib.sha256(rootfs).hexdigest()}  alpine-minirootfs-3.22.2-aarch64.tar.gz")
    return "".join(line + "\n" for line in lines)


def _net_manifest_text(umnet: bytes, passt: bytes) -> str:
    return (
        f"{hashlib.sha256(umnet).hexdigest()}  libuml_net.so\n"
        f"{hashlib.sha256(passt).hexdigest()}  libuml_passt.so\n"
    )


def _pvm_manifest_text(llvm: bytes, cppshared: bytes, clang: bytes) -> str:
    return json.dumps({
        "schema": 1,
        "engine": "clang",
        "payload_binary": "libpvm_compiler_payload.so",
        "libs": [
            {"name": "libLLVM.so", "size": len(llvm), "sha256": hashlib.sha256(llvm).hexdigest()},
            {"name": "libc++_shared.so", "size": len(cppshared), "sha256": hashlib.sha256(cppshared).hexdigest()},
            {"name": "libclang-cpp.so", "size": len(clang), "sha256": hashlib.sha256(clang).hexdigest()},
        ],
    }).encode("utf-8")


def _qemu_manifest_text(qemu_lib: bytes) -> str:
    image = b"qemu-image-bytes"
    initramfs = b"qemu-initramfs-bytes"
    build_inputs = b"qemu-build-inputs"
    gpl_sources = b"qemu-gpl-sources"
    license_gpl = b"qemu-license"
    notice = b"qemu-notice"
    return "".join([
        f"{hashlib.sha256(image).hexdigest()}  Image\n",
        f"{hashlib.sha256(initramfs).hexdigest()}  initramfs.cpio.gz\n",
        f"{hashlib.sha256(build_inputs).hexdigest()}  QEMU-BUILD-INPUTS\n",
        f"{hashlib.sha256(gpl_sources).hexdigest()}  QEMU-GPL-SOURCES.md\n",
        f"{hashlib.sha256(license_gpl).hexdigest()}  LICENSE.GPL-2.0\n",
        f"{hashlib.sha256(notice).hexdigest()}  NOTICE.QEMU\n",
        f"{hashlib.sha256(qemu_lib).hexdigest()}  lib/arm64-v8a/libdroidlayer_qemu.so\n",
    ])


def _fullpkg_entries(kernel, stub, rootfs, umnet, passt, llvm, cppshared, clang, payload, compiler, qemu_lib) -> dict[str, bytes]:
    return {
        LIB_DIR + "libuml_kernel.so": kernel,
        LIB_DIR + "libuml_stub.so": stub,
        FULLPKG_ROOTFS_TAR: rootfs,
        LIB_DIR + "libuml_net.so": umnet,
        LIB_DIR + "libuml_passt.so": passt,
        LIB_DIR + "libLLVM.so": llvm,
        LIB_DIR + "libc++_shared.so": cppshared,
        LIB_DIR + "libclang-cpp.so": clang,
        LIB_DIR + "libfastlane_payload.so": payload,
        LIB_DIR + "libpvm_compiler_payload.so": compiler,
        LIB_DIR + "libdroidlayer_qemu.so": qemu_lib,
        "assets/Image": b"qemu-image-bytes",
        "initramfs.cpio.gz": b"qemu-initramfs-bytes",
        "assets/NOTICE.UML": b"uml-notice",
        "assets/QEMU-BUILD-INPUTS": b"qemu-build-inputs",
        "assets/QEMU-GPL-SOURCES.md": b"qemu-gpl-sources",
        "assets/LICENSE.GPL-2.0": b"qemu-license",
        "assets/NOTICE.QEMU": b"qemu-notice",
        FAST_MANIFEST_ENTRY: _fast_manifest_text(kernel, stub, rootfs).encode(),
        NET_HELPER_MANIFEST_ENTRY: _net_manifest_text(umnet, passt).encode(),
        PVM_MANIFEST_ENTRY: _pvm_manifest_text(llvm, cppshared, clang),
        QEMU_MANIFEST_ENTRY: _qemu_manifest_text(qemu_lib).encode(),
    }


def run_selftest() -> int:
    """Synthetic zip-as-APK fixtures, positive and negative, through main().

    No binary fixtures in the repo: every case builds its own minimal APK in a
    throwaway directory, so the gate's contract is testable anywhere python
    runs (packaging machine included) with zero inputs.
    """
    kernel = b"uml-kernel-bytes"
    stub = b"uml-stub-bytes"
    rootfs = b"rootfs-tar-bytes"
    umnet = b"umnet-bytes"
    passt = b"passt-bytes"
    llvm = b"llvm-bytes" * 100
    cppshared = b"cpp-shared-bytes"
    clang = b"clang-bytes" * 100
    payload = b"fastlane-payload-bytes"
    compiler = b"pvm-compiler-payload-bytes"
    qemu_lib = b"qemu-lib-bytes" * 100

    full_manifest = _fast_manifest_text(kernel, stub, rootfs)
    no_tar_manifest = _fast_manifest_text(kernel, stub, None)
    net_manifest = _net_manifest_text(umnet, passt)

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)

        def apk(name: str, entries: dict[str, bytes]) -> str:
            path = root / name
            _write_zip(path, entries)
            return str(path)

        kernel_entry = LIB_DIR + "libuml_kernel.so"
        stub_entry = LIB_DIR + "libuml_stub.so"
        rootfs_entry = FULLPKG_ROOTFS_TAR
        net_entry = LIB_DIR + "libuml_net.so"
        passt_entry = LIB_DIR + "libuml_passt.so"

        cases: list[tuple[str, list[str], str, str]] = []
        # (label, argv, expected exit code, expected output marker)

        # Positive: full UML payload with the optional rootfs tar, digests agree.
        cases.append((
            "ok-full-payload",
            ["--apk", apk("ok.apk", {
                kernel_entry: kernel,
                stub_entry: stub,
                rootfs_entry: rootfs,
                FAST_MANIFEST_ENTRY: full_manifest.encode(),
            })],
            "0", "UML_PAYLOAD_GATE_OK",
        ))
        # Positive: payload without the optional rootfs tar.
        cases.append((
            "ok-without-optional-tar",
            ["--apk", apk("ok_no_tar.apk", {
                kernel_entry: kernel,
                stub_entry: stub,
                FAST_MANIFEST_ENTRY: no_tar_manifest.encode(),
            })],
            "0", "UML_PAYLOAD_GATE_OK",
        ))
        # Positive: the net-helper manifest reconciles alongside the fast one
        # (the fast manifest here spells the no-tar shape, matching the fixture).
        cases.append((
            "ok-with-net-helper",
            ["--apk", apk("ok_net.apk", {
                kernel_entry: kernel,
                stub_entry: stub,
                net_entry: umnet,
                passt_entry: passt,
                FAST_MANIFEST_ENTRY: no_tar_manifest.encode(),
                NET_HELPER_MANIFEST_ENTRY: net_manifest.encode(),
            })],
            "0", "UML_PAYLOAD_GATE_OK",
        ))
        # Negative: shipped kernel bytes are not the manifest's bytes.
        cases.append((
            "fail-digest-mismatch",
            ["--apk", apk("mismatch.apk", {
                kernel_entry: b"different-kernel-bytes",
                stub_entry: stub,
                FAST_MANIFEST_ENTRY: full_manifest.encode(),
            })],
            "1", "UML_PAYLOAD_GATE_FAIL",
        ))
        # Negative: the manifest accounts for the stub but the APK lacks it.
        cases.append((
            "fail-missing-stub",
            ["--apk", apk("missing_stub.apk", {
                kernel_entry: kernel,
                FAST_MANIFEST_ENTRY: full_manifest.encode(),
            })],
            "1", "UML_PAYLOAD_GATE_FAIL",
        ))
        # Negative: manifest present but incomplete (no kernel line).
        cases.append((
            "fail-incomplete-manifest",
            ["--apk", apk("incomplete.apk", {
                kernel_entry: kernel,
                stub_entry: stub,
                FAST_MANIFEST_ENTRY: (
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
                FAST_MANIFEST_ENTRY: b"not-a-digest  libuml_kernel.so\n",
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
                FAST_MANIFEST_ENTRY: full_manifest.encode(),
            })],
            "1", "UML_PAYLOAD_GATE_FAIL",
        ))
        # Negative: the net-helper manifest ships but passt's bytes were swapped.
        cases.append((
            "fail-net-helper-mismatch",
            ["--apk", apk("net_mismatch.apk", {
                kernel_entry: kernel,
                stub_entry: stub,
                net_entry: umnet,
                passt_entry: b"swapped-passt-bytes",
                FAST_MANIFEST_ENTRY: full_manifest.encode(),
                NET_HELPER_MANIFEST_ENTRY: net_manifest.encode(),
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
                FAST_MANIFEST_ENTRY: full_manifest.encode(),
            }), "--require-payload"],
            "0", "UML_PAYLOAD_GATE_OK",
        ))

        # Fullpkg positive: the complete single-package APK passes the smoke.
        cases.append((
            "ok-fullpkg",
            ["--apk", apk("fullpkg.apk", _fullpkg_entries(
                kernel, stub, rootfs, umnet, passt, llvm, cppshared, clang,
                payload, compiler, qemu_lib)), "--fullpkg"],
            "0", "FULLPKG_GATE_OK",
        ))
        # Fullpkg negative: a UML digest mismatch is still a fail there.
        cases.append((
            "fail-fullpkg-kernel-mismatch",
            ["--apk", apk("fullpkg_bad.apk", {
                **_fullpkg_entries(kernel, stub, rootfs, umnet, passt, llvm, cppshared,
                                   clang, payload, compiler, qemu_lib),
                kernel_entry: b"different-kernel-bytes",
            }), "--fullpkg"],
            "1", "FULLPKG_GATE_FAIL",
        ))
        # Fullpkg negative: the manifest pins wrong LLVM bytes (strip/corruption).
        cases.append((
            "fail-fullpkg-pvm-pin",
            ["--apk", apk("fullpkg_pvm.apk", {
                **_fullpkg_entries(kernel, stub, rootfs, umnet, passt, llvm, cppshared,
                                   clang, payload, compiler, qemu_lib),
                LIB_DIR + "libLLVM.so": b"stripped-llvm-bytes",
            }), "--fullpkg"],
            "1", "FULLPKG_GATE_FAIL",
        ))
        # Fullpkg negative: the embedded rootfs tar is absent.
        no_tar_entries = _fullpkg_entries(kernel, stub, rootfs, umnet, passt, llvm,
                                          cppshared, clang, payload, compiler, qemu_lib)
        del no_tar_entries[rootfs_entry]
        no_tar_manifest_only_kernel = no_tar_manifest.encode()
        no_tar_entries[FAST_MANIFEST_ENTRY] = no_tar_manifest_only_kernel
        cases.append((
            "fail-fullpkg-no-rootfs-tar",
            ["--apk", apk("fullpkg_notar.apk", no_tar_entries), "--fullpkg"],
            "1", "FULLPKG_GATE_FAIL",
        ))
        # Fullpkg negative: the QEMU native library is missing from the APK.
        no_qemu_entries = _fullpkg_entries(kernel, stub, rootfs, umnet, passt, llvm,
                                           cppshared, clang, payload, compiler, qemu_lib)
        del no_qemu_entries[LIB_DIR + "libdroidlayer_qemu.so"]
        cases.append((
            "fail-fullpkg-no-qemu-lib",
            ["--apk", apk("fullpkg_noqemu.apk", no_qemu_entries), "--fullpkg"],
            "1", "FULLPKG_GATE_FAIL",
        ))
        # Fullpkg require shape: neither manifest fails even without --fullpkg.
        cases.append((
            "fail-fullpkg-no-manifests",
            ["--apk", apk("empty.apk", {kernel_entry: b"unrelated-native-lib"}), "--fullpkg"],
            "1", "UML_PAYLOAD_GATE_FAIL",
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
              "(positive, negative, skip, require, fullpkg and usage-error paths)")
        return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, help="APK to inspect")
    parser.add_argument("--require-payload", action="store_true",
                        help="a missing accounting manifest is a failure, not a skip "
                             "(packaging machine mode: the build DID stage the UML payloads)")
    parser.add_argument("--fullpkg", action="store_true",
                        help="single-package completeness smoke: both UML manifests, the "
                             "embedded rootfs tar, the pVM compiler manifest pins and the "
                             "QEMU manifest, all reconciled in one pass (implies "
                             "--require-payload)")
    parser.add_argument("--json", type=Path, help="write a machine-readable result")
    parser.add_argument("--selftest", action="store_true",
                        help="run the built-in synthetic fixture cases and exit")
    args = parser.parse_args(argv)

    if args.selftest:
        return run_selftest()
    if args.apk is None:
        parser.error("--apk is required (or use --selftest)")
    require_payload = args.require_payload or args.fullpkg

    if not args.apk.is_file():
        print(f"UML_PAYLOAD_GATE_ERROR apk not found: {args.apk}", file=sys.stderr)
        return 2

    try:
        result = check_apk(args.apk, require_payload, args.fullpkg)
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
        if args.fullpkg:
            print(f"FULLPKG_GATE_OK the single-package APK carries every engine it accounts "
                  f"for (UML fast profile, net helper, embedded rootfs tar, pVM compiler "
                  f"manifest pins, QEMU manifest): {names}")
        else:
            print(f"UML_PAYLOAD_GATE_OK every accounted payload artifact is present "
                  f"and matches the in-APK manifest: {names}")

    if result.status == "fail":
        if args.fullpkg:
            print(f"FULLPKG_GATE_FAIL {len(result.problems)} single-package integrity "
                  "problems (see the UML_PAYLOAD_GATE_FAIL lines above)")
            print(
                "FULLPKG_GATE_HINT the single-package build must ship every engine it "
                "accounts for. Rebuild with all staging parameters "
                "(-Porchestella.uml.fastProfileDir / -Porchestella.uml.netHelperDir / "
                "-Porchestella.fastlane.payloadLibDir / -Porchestella.pvmCompiler.libDir) "
                "and rerun the repack pipeline; shipped bytes and manifests must agree.",
                file=sys.stderr,
            )
        else:
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
                "fullpkg": args.fullpkg,
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
