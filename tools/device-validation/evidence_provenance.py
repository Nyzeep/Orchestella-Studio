#!/usr/bin/env python3
"""Canonical candidate/device provenance, crash-safe ledger I/O, and resume validation.

Provides a single shared source of truth for:
1. Canonical candidate & device provenance (Studio commit, runner/helper scripts,
   QA app+test APKs & signatures, built-in UML/QEMU/initramfs/Alpine payloads,
   rootfs seed & W01.04 fixtures, device alias & measured host page size).
2. Cross-platform single-writer directory lock (``EvidenceDirLock``) and atomic
   fsynced JSON / JSONL I/O with strict line-level corruption detection.
3. Field-by-field ``--resume`` verification, read-only historical ledger auditing,
   retry supersession metadata, and unified sample selection shared between
   resume skipping and final summary aggregation.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import sys
import threading
import uuid
from typing import Any
import zipfile

sys.path.insert(0, os.path.dirname(__file__))
import qa_package_guard as qa_guard  # noqa: E402

sha256_file = qa_guard.sha256_file

if os.name == "nt":
    import msvcrt
else:
    import fcntl

PROVENANCE_SCHEMA_VERSION = 2

BUILTIN_APK_PAYLOAD_ENTRIES: tuple[str, ...] = (
    "lib/arm64-v8a/libuml_kernel_full.so",
    "lib/arm64-v8a/libuml_stub_full.so",
    "lib/arm64-v8a/libuml_kernel.so",
    "lib/arm64-v8a/libuml_stub.so",
    "lib/arm64-v8a/libuml_net.so",
    "lib/arm64-v8a/libuml_passt.so",
    "initramfs.cpio.gz",
    "assets/Image",
    "alpine-minirootfs-3.22.2-aarch64.tar.gz",
)

_RUN_ID_RE = re.compile(r"^run-(\d+)$")
_LOCK_REGISTRY_GUARD = threading.Lock()
_ACTIVE_LOCK_PATHS: set[Path] = set()


class EvidenceProvenanceError(ValueError):
    """Fail-closed provenance or ledger integrity error."""

    def __init__(
        self,
        code: str,
        detail: str,
        *,
        diffs: list[dict[str, Any]] | None = None,
    ) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail
        self.diffs = list(diffs or [])


def canonical_json_bytes(obj: Any) -> bytes:
    """Serialize ``obj`` deterministically with sorted keys and finite numbers only."""
    return json.dumps(
        obj,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
        allow_nan=False,
    ).encode("utf-8")


def compute_digest(obj: Any) -> str:
    """Compute SHA-256 hex digest of canonical JSON representation of ``obj``."""
    return hashlib.sha256(canonical_json_bytes(obj)).hexdigest()


def _reject_nonfinite(token: str) -> Any:
    raise ValueError(f"non-finite JSON constant {token!r}")


def _unique_object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key, val in pairs:
        if key in out:
            raise ValueError(f"duplicate JSON key {key!r}")
        out[key] = val
    return out


def extract_apk_builtin_payload_digests(app_apk: Path) -> dict[str, str | None]:
    """Hash actual built-in kernel/stub/net/initramfs/rootfs entries from the app APK ZIP."""
    digests: dict[str, str | None] = {}
    with zipfile.ZipFile(app_apk, "r") as zf:
        names = set(zf.namelist())
        for entry in BUILTIN_APK_PAYLOAD_ENTRIES:
            if entry not in names:
                digests[entry] = None
                continue
            h = hashlib.sha256()
            with zf.open(entry) as handle:
                for chunk in iter(lambda: handle.read(1 << 20), b""):
                    h.update(chunk)
            digests[entry] = h.hexdigest()
    return digests


def build_inputs_provenance(
    debian_tar: Path,
    fixtures_dir: Path,
    fixtures_lock: dict[str, Any],
) -> dict[str, Any]:
    """Compute actual-byte digests for rootfs seed and W01.04 offline fixtures."""
    lock_path = fixtures_dir / "w0104-lock.json"
    base_img = fixtures_dir / "base-image-arm64.tar"
    keyring = fixtures_dir / "apt-repo/qa-w0104-archive.gpg"
    inrelease = fixtures_dir / "apt-repo/dists/trixie/InRelease"

    debian_sha = sha256_file(debian_tar)
    lock_sha = sha256_file(lock_path)
    base_img_sha = sha256_file(base_img)
    keyring_sha = sha256_file(keyring)
    inrelease_sha = sha256_file(inrelease)

    expected_staged: dict[str, str] = {
        "files/distro-inputs/debian-13-rootfs.tar.xz": debian_sha,
        "files/w0104-fixtures/w0104-lock.json": lock_sha,
        "files/w0104-fixtures/base-image-arm64.tar": base_img_sha,
        "files/w0104-fixtures/apt-repo/qa-w0104-archive.gpg": keyring_sha,
        "files/w0104-fixtures/apt-repo/dists/trixie/InRelease": inrelease_sha,
    }

    pkg_digests: list[dict[str, str]] = []
    for pkg in sorted(fixtures_lock.get("packages", []), key=lambda p: str(p.get("filename", ""))):
        fname = str(pkg["filename"])
        deb_path = fixtures_dir / "apt-repo" / fname
        deb_sha = sha256_file(deb_path)
        pkg_digests.append({"filename": fname, "sha256": deb_sha})
        expected_staged[f"files/w0104-fixtures/apt-repo/{fname}"] = deb_sha

    return {
        "debian_rootfs": {
            "name": debian_tar.name,
            "size_bytes": debian_tar.stat().st_size,
            "sha256": debian_sha,
        },
        "w0104_fixtures": {
            "lock_sha256": lock_sha,
            "base_image_tar_sha256": base_img_sha,
            "keyring_sha256": keyring_sha,
            "inrelease_sha256": inrelease_sha,
            "packages_manifest_sha256": compute_digest(pkg_digests),
            "package_count": len(pkg_digests),
        },
        "expected_staged_files": expected_staged,
    }


def build_provenance(
    *,
    studio_commit: str,
    studio_dirty: bool,
    runner_path: Path,
    helper_paths: list[Path],
    apk_proof: dict[str, Any],
    builtin_payloads: dict[str, str | None],
    inputs_provenance: dict[str, Any],
    device_facts: dict[str, Any],
) -> dict[str, Any]:
    """Build canonical provenance object, digests, and per-row attribution payload."""
    app_apk = apk_proof["app_apk"]
    test_apk = apk_proof["test_apk"]
    qa_target = apk_proof["qa_target"]

    helper_digests = {
        Path(p).name: sha256_file(Path(p))
        for p in sorted(helper_paths, key=lambda x: Path(x).name)
        if Path(p).is_file()
    }
    runner_sha = sha256_file(runner_path) if runner_path.is_file() else ""

    inputs_clean = {
        "debian_rootfs": inputs_provenance["debian_rootfs"],
        "w0104_fixtures": inputs_provenance["w0104_fixtures"],
    }
    inputs_digest = compute_digest(inputs_clean)
    builtin_payloads_digest = compute_digest(builtin_payloads)

    candidate_obj = {
        "studio_source": {
            "commit": studio_commit,
            "dirty": bool(studio_dirty),
        },
        "runner": {
            "name": runner_path.name,
            "sha256": runner_sha,
            "helpers": helper_digests,
        },
        "qa_target": {
            "purpose": qa_target["purpose"],
            "app_package": qa_target["app_package"],
            "test_package": qa_target["test_package"],
            "instrumentation_component": qa_target["instrumentation_component"],
            "expected_abi": qa_target.get("expected_abi", "arm64-v8a"),
        },
        "app_apk": {
            "name": app_apk["name"],
            "size_bytes": app_apk["size_bytes"],
            "sha256": app_apk["sha256"],
            "version_name": app_apk.get("version_name"),
            "version_code": app_apk.get("version_code"),
            "signer_cert_sha256s": sorted(app_apk.get("apksigner", {}).get("signer_cert_sha256s") or []),
        },
        "test_apk": {
            "name": test_apk["name"],
            "size_bytes": test_apk["size_bytes"],
            "sha256": test_apk["sha256"],
            "signer_cert_sha256s": sorted(test_apk.get("apksigner", {}).get("signer_cert_sha256s") or []),
        },
        "builtin_payloads": dict(sorted(builtin_payloads.items())),
        "builtin_payloads_digest": builtin_payloads_digest,
        "inputs": inputs_clean,
        "inputs_digest": inputs_digest,
    }
    candidate_digest = compute_digest(candidate_obj)

    device_condition = {
        "device_alias": device_facts["device_alias"],
        "host_page_size": device_facts["host_page_size"],
        "android_release": str(device_facts.get("android_release", "")),
        "android_sdk": device_facts.get("android_sdk"),
        "abi": str(device_facts.get("abi", "")),
        "host_kernel_release": str(device_facts.get("host_kernel_release", "")),
        "host_cpus": device_facts.get("host_cpus"),
    }

    provenance = {
        **candidate_obj,
        "device_condition": device_condition,
    }
    provenance_digest = compute_digest(provenance)

    row_attribution = {
        "provenance_schema_version": PROVENANCE_SCHEMA_VERSION,
        "provenance_digest": provenance_digest,
        "candidate_digest": candidate_digest,
        "studio_commit": studio_commit,
        "qa_package": qa_target["app_package"],
        "qa_purpose": qa_target["purpose"],
        "instrumentation_component": qa_target["instrumentation_component"],
        "app_apk_sha256": app_apk["sha256"],
        "test_apk_sha256": test_apk["sha256"],
        "signing_cert_sha256s": sorted(apk_proof.get("signing_cert_sha256s") or []),
        "builtin_payloads_digest": builtin_payloads_digest,
        "inputs_digest": inputs_digest,
    }

    return {
        "provenance": provenance,
        "candidate_digest": candidate_digest,
        "provenance_digest": provenance_digest,
        "inputs_digest": inputs_digest,
        "builtin_payloads_digest": builtin_payloads_digest,
        "row_attribution": row_attribution,
    }


def diff_provenance(recorded: Any, current: Any, prefix: str = "") -> list[dict[str, Any]]:
    """Return recursive field-by-field differences between two provenance objects."""
    diffs: list[dict[str, Any]] = []
    if isinstance(recorded, dict) and isinstance(current, dict):
        all_keys = sorted(set(recorded) | set(current))
        for key in all_keys:
            child_prefix = f"{prefix}.{key}" if prefix else str(key)
            if key not in recorded:
                diffs.append({"field": child_prefix, "recorded": None, "current": current[key]})
            elif key not in current:
                diffs.append({"field": child_prefix, "recorded": recorded[key], "current": None})
            else:
                diffs.extend(diff_provenance(recorded[key], current[key], child_prefix))
        return diffs
    if recorded != current:
        diffs.append({"field": prefix or "<root>", "recorded": recorded, "current": current})
    return diffs


class EvidenceDirLock:
    """Exclusive non-blocking lock protecting an evidence directory from concurrent writers."""

    def __init__(self, evidence_dir: Path, run_id: str | None = None) -> None:
        self.evidence_dir = Path(evidence_dir)
        self.lock_path = (self.evidence_dir / ".evidence.lock").resolve()
        self.run_id = run_id or ""
        self._handle: Any = None
        self._registered = False

    def __enter__(self) -> EvidenceDirLock:
        self.evidence_dir.mkdir(parents=True, exist_ok=True)
        with _LOCK_REGISTRY_GUARD:
            if self.lock_path in _ACTIVE_LOCK_PATHS:
                raise EvidenceProvenanceError(
                    "EVIDENCE_DIR_LOCKED",
                    f"Evidence directory {self.evidence_dir.name!r} is already locked by an active writer",
                )
            _ACTIVE_LOCK_PATHS.add(self.lock_path)
            self._registered = True

        try:
            fh = self.lock_path.open("a+b")
            if fh.tell() == 0:
                fh.write(b"1\n")
                fh.flush()
            fh.seek(0)
            if os.name == "nt":
                msvcrt.locking(fh.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            self._handle = fh
            return self
        except OSError as exc:
            if self._registered:
                with _LOCK_REGISTRY_GUARD:
                    _ACTIVE_LOCK_PATHS.discard(self.lock_path)
                self._registered = False
            try:
                fh.close()  # type: ignore[possibly-undefined]
            except Exception:
                pass
            raise EvidenceProvenanceError(
                "EVIDENCE_DIR_LOCKED",
                f"Evidence directory {self.evidence_dir.name!r} is locked by another process ({exc.__class__.__name__})",
            ) from exc

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        if self._handle is not None:
            try:
                self._handle.seek(0)
                if os.name == "nt":
                    msvcrt.locking(self._handle.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    fcntl.flock(self._handle.fileno(), fcntl.LOCK_UN)
            except OSError:
                pass
            try:
                self._handle.close()
            finally:
                self._handle = None
        if self._registered:
            with _LOCK_REGISTRY_GUARD:
                _ACTIVE_LOCK_PATHS.discard(self.lock_path)
            self._registered = False


def atomic_write_json(path: Path, payload: Any, *, allow_overwrite: bool = True) -> None:
    """Write formatted JSON atomically using temp file + fsync + os.replace."""
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    if not allow_overwrite and target.exists():
        raise EvidenceProvenanceError(
            "IMMUTABLE_MANIFEST_EXISTS",
            f"Refusing to overwrite existing {target.name!r}",
        )
    data = (json.dumps(payload, indent=2, sort_keys=True, allow_nan=False) + "\n").encode("utf-8")
    tmp_path = target.parent / f".{target.name}.{os.getpid()}.{uuid.uuid4().hex[:8]}.tmp"
    try:
        with tmp_path.open("wb") as fh:
            fh.write(data)
            fh.flush()
            os.fsync(fh.fileno())
        if not allow_overwrite:
            try:
                os.link(tmp_path, target)
            except FileExistsError:
                raise EvidenceProvenanceError(
                    "IMMUTABLE_MANIFEST_EXISTS",
                    f"Refusing to overwrite existing {target.name!r}",
                ) from None
            except OSError:
                # Filesystems without hardlink support (e.g., exFAT/FAT32): fall back to O_EXCL create.
                try:
                    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
                except FileExistsError:
                    raise EvidenceProvenanceError(
                        "IMMUTABLE_MANIFEST_EXISTS",
                        f"Refusing to overwrite existing {target.name!r}",
                    ) from None
                with os.fdopen(fd, "wb") as out_fh:
                    out_fh.write(data)
                    out_fh.flush()
                    os.fsync(out_fh.fileno())
        else:
            os.replace(tmp_path, target)
    finally:
        if tmp_path.exists():
            try:
                tmp_path.unlink()
            except OSError:
                pass


def append_jsonl_atomic(path: Path, rows: list[dict[str, Any]]) -> None:
    """Serialize all rows up-front, append as a single write, flush, and fsync."""
    if not rows:
        return
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        json.dumps(row, sort_keys=True, allow_nan=False) + "\n"
        for row in rows
    ]
    payload = "".join(lines).encode("utf-8")
    with target.open("ab") as fh:
        fh.write(payload)
        fh.flush()
        os.fsync(fh.fileno())


def load_jsonl_strict(path: Path) -> list[dict[str, Any]]:
    """Read a JSONL ledger strictly, failing closed on half-written or corrupt lines."""
    target = Path(path)
    if not target.exists():
        return []
    rows: list[dict[str, Any]] = []
    with target.open("r", encoding="utf-8") as fh:
        for line_no, raw in enumerate(fh, start=1):
            stripped = raw.strip()
            if not stripped:
                continue
            try:
                obj = json.loads(
                    stripped,
                    object_pairs_hook=_unique_object_pairs,
                    parse_constant=_reject_nonfinite,
                )
            except (ValueError, json.JSONDecodeError) as exc:
                raise EvidenceProvenanceError(
                    "CORRUPT_LEDGER_LINE",
                    f"Corrupt or half-written ledger record at line {line_no} of {target.name}: {exc}",
                ) from exc
            if not isinstance(obj, dict):
                raise EvidenceProvenanceError(
                    "CORRUPT_LEDGER_LINE",
                    f"Ledger line {line_no} of {target.name} is not a JSON object",
                )
            rows.append(obj)
    return rows


def next_run_id(records: list[dict[str, Any]]) -> str:
    """Return the next monotonic ``run-NNNN`` identifier."""
    max_num = 0
    for row in records:
        match = _RUN_ID_RE.match(str(row.get("run_id", "")))
        if match:
            max_num = max(max_num, int(match.group(1)))
    return f"run-{max_num + 1:04d}"


def canonical_sample_key(row: dict[str, Any]) -> tuple[str | None, str | None, str | None, str]:
    """Return canonical deduplication/selection key for a ledger sample row."""
    return (
        row.get("record_type"),
        row.get("suite"),
        row.get("bucket"),
        str(row.get("sample_id") or ""),
    )


def build_attempt_metadata(
    existing_records: list[dict[str, Any]],
    sample_id: str,
    run_id: str,
) -> dict[str, Any]:
    """Compute monotonic ``attempt_index``, ``attempt_key``, and retry supersession links."""
    prior: list[tuple[int, dict[str, Any]]] = [
        (idx, r)
        for idx, r in enumerate(existing_records)
        if r.get("sample_id") == sample_id
    ]
    for _, r in prior:
        if r.get("status") == "PASS":
            raise EvidenceProvenanceError(
                "DUPLICATE_SAMPLE_KEY",
                f"Sample {sample_id!r} already has a PASS row; refusing duplicate PASS attempt",
            )
    attempt_index = len(prior) + 1
    meta: dict[str, Any] = {
        "attempt_index": attempt_index,
        "attempt_key": f"{sample_id}@{run_id}#{attempt_index}",
    }
    if prior:
        prev_idx, prev_row = prior[-1]
        if prev_row.get("status") in ("FAIL", "TIMEOUT"):
            meta["supersedes_run_id"] = prev_row.get("run_id")
            meta["supersedes_row_index"] = prev_idx
    return meta


def _check_row_provenance(
    row: dict[str, Any],
    idx: int,
    *,
    expected_digest: str | None,
    expected_provenance: dict[str, Any] | None,
    require_provenance: bool,
) -> str | None:
    row_digest = row.get("provenance_digest")
    row_ver = row.get("provenance_schema_version")
    if not row_digest or row_ver != PROVENANCE_SCHEMA_VERSION:
        if require_provenance:
            raise EvidenceProvenanceError(
                "LEGACY_OR_MISSING_ROW_PROVENANCE",
                f"Ledger row {idx} (sample_id={row.get('sample_id')!r}) lacks v2 provenance attribution",
            )
        return None

    anchor = expected_digest or str(row_digest)
    if str(row_digest) != anchor:
        raise EvidenceProvenanceError(
            "MIXED_PROVENANCE_ROW",
            f"Ledger row {idx} provenance_digest {str(row_digest)[:16]}... != expected {anchor[:16]}...",
        )
    if expected_provenance is not None:
        exp_checks = {
            "qa_package": expected_provenance["qa_target"]["app_package"],
            "app_apk_sha256": expected_provenance["app_apk"]["sha256"],
            "test_apk_sha256": expected_provenance["test_apk"]["sha256"],
            "inputs_digest": expected_provenance["inputs_digest"],
            "builtin_payloads_digest": expected_provenance["builtin_payloads_digest"],
            "device_alias": expected_provenance["device_condition"]["device_alias"],
            "host_page_size": expected_provenance["device_condition"]["host_page_size"],
        }
        for key, expected_val in exp_checks.items():
            if row.get(key) != expected_val:
                raise EvidenceProvenanceError(
                    "MIXED_PROVENANCE_ROW",
                    f"Ledger row {idx} field {key!r}={row.get(key)!r} != expected {expected_val!r}",
                )
    return anchor


def validate_and_select_ledger(
    records: list[dict[str, Any]],
    *,
    expected_provenance: dict[str, Any] | None = None,
    expected_provenance_digest: str | None = None,
    require_provenance: bool = True,
) -> dict[str, Any]:
    """Validate ledger rows and compute canonical retry/PASS selection maps."""
    anchor_digest = expected_provenance_digest
    has_v2_rows = any(bool(r.get("provenance_digest")) for r in records)
    enforce_prov = require_provenance or has_v2_rows

    seen_attempt_keys: set[str] = set()
    passed_by_key: dict[tuple[str | None, str | None, str | None, str], dict[str, Any]] = {}
    latest_by_key: dict[tuple[str | None, str | None, str | None, str], dict[str, Any]] = {}
    history_by_key: dict[tuple[str | None, str | None, str | None, str], list[dict[str, Any]]] = {}

    for idx, row in enumerate(records):
        row_anchor = _check_row_provenance(
            row,
            idx,
            expected_digest=anchor_digest,
            expected_provenance=expected_provenance,
            require_provenance=enforce_prov,
        )
        if row_anchor is not None and anchor_digest is None:
            anchor_digest = row_anchor

        att_key = row.get("attempt_key")
        if att_key is not None:
            att_str = str(att_key)
            if att_str in seen_attempt_keys:
                raise EvidenceProvenanceError(
                    "DUPLICATE_ATTEMPT_KEY",
                    f"Duplicate attempt_key {att_str!r} at ledger row {idx}",
                )
            seen_attempt_keys.add(att_str)

        sup_idx = row.get("supersedes_row_index")
        if sup_idx is not None:
            if not isinstance(sup_idx, int) or sup_idx < 0 or sup_idx >= idx:
                raise EvidenceProvenanceError(
                    "INVALID_SUPERSESSION_LINK",
                    f"Row {idx} has out-of-range supersedes_row_index={sup_idx!r}",
                )
            prev = records[sup_idx]
            if prev.get("sample_id") != row.get("sample_id") or prev.get("status") == "PASS":
                raise EvidenceProvenanceError(
                    "INVALID_SUPERSESSION_LINK",
                    f"Row {idx} supersedes invalid target row {sup_idx}",
                )

        skey = canonical_sample_key(row)
        history_by_key.setdefault(skey, []).append(row)
        latest_by_key[skey] = row
        if row.get("status") == "PASS":
            if skey in passed_by_key:
                raise EvidenceProvenanceError(
                    "DUPLICATE_SAMPLE_KEY",
                    f"Duplicate PASS row for sample key {skey!r} at ledger row {idx}",
                )
            passed_by_key[skey] = row

    passed_sample_ids = {skey[3] for skey in passed_by_key}
    return {
        "provenance_digest": anchor_digest,
        "latest_by_key": latest_by_key,
        "passed_by_key": passed_by_key,
        "passed_sample_ids": passed_sample_ids,
        "history_by_key": history_by_key,
    }


def _load_manifest_strict(manifest_path: Path) -> dict[str, Any]:
    try:
        raw = manifest_path.read_text(encoding="utf-8")
        obj = json.loads(
            raw,
            object_pairs_hook=_unique_object_pairs,
            parse_constant=_reject_nonfinite,
        )
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        raise EvidenceProvenanceError(
            "CORRUPT_MANIFEST",
            f"Unable to parse {manifest_path.name}: {exc}",
        ) from exc
    if not isinstance(obj, dict):
        raise EvidenceProvenanceError("CORRUPT_MANIFEST", f"{manifest_path.name} is not a JSON object")
    return obj


def prepare_evidence_session(
    evidence_dir: Path,
    *,
    resume: bool,
    expected_provenance_bundle: dict[str, Any],
) -> dict[str, Any]:
    """Validate whether ``evidence_dir`` can start fresh or safely ``--resume``."""
    ev_dir = Path(evidence_dir)
    manifest_path = ev_dir / "candidate-manifest.json"
    ledger_path = ev_dir / "ledger.jsonl"
    summary_path = ev_dir / "summary.json"
    existing_artifacts = [p.name for p in (manifest_path, ledger_path, summary_path) if p.exists()]

    if not resume:
        if existing_artifacts:
            raise EvidenceProvenanceError(
                "EXISTING_EVIDENCE_DIR_REQUIRES_RESUME_OR_NEW_DIR",
                f"Evidence directory {ev_dir.name!r} already contains {existing_artifacts}. "
                "Use a new --evidence-dir for a fresh run, or pass --resume to continue the same verified candidate.",
            )
        return {
            "is_resume": False,
            "existing_records": [],
            "selection": validate_and_select_ledger([], require_provenance=True),
            "next_run_id": "run-0001",
            "manifest_unchanged": False,
        }

    if not manifest_path.is_file():
        raise EvidenceProvenanceError(
            "RESUME_MANIFEST_MISSING",
            f"--resume requires {manifest_path.name!r} in {ev_dir.name!r}",
        )

    manifest = _load_manifest_strict(manifest_path)
    schema_ver = manifest.get("schema_version")
    recorded_prov = manifest.get("provenance")
    recorded_digest = manifest.get("provenance_digest")
    if schema_ver != PROVENANCE_SCHEMA_VERSION or not isinstance(recorded_prov, dict) or not recorded_digest:
        raise EvidenceProvenanceError(
            "LEGACY_SCHEMA_NO_PROVENANCE",
            f"Existing {manifest_path.name!r} uses legacy schema ({schema_ver!r}) without full candidate/device provenance. "
            "Keep historical records read-only and start a new --evidence-dir for Wave 02+ runs.",
        )

    if compute_digest(recorded_prov) != recorded_digest:
        raise EvidenceProvenanceError(
            "MANIFEST_DIGEST_TAMPERED",
            f"Recorded provenance_digest in {manifest_path.name!r} does not match canonical provenance bytes",
        )

    diffs = diff_provenance(recorded_prov, expected_provenance_bundle["provenance"])
    if diffs:
        preview = "; ".join(f"{d['field']}: {d['recorded']!r} -> {d['current']!r}" for d in diffs[:5])
        raise EvidenceProvenanceError(
            "RESUME_PROVENANCE_MISMATCH",
            f"--resume candidate/device provenance mismatch ({len(diffs)} field(s)): {preview}",
            diffs=diffs,
        )

    existing_records = load_jsonl_strict(ledger_path)
    selection = validate_and_select_ledger(
        existing_records,
        expected_provenance=recorded_prov,
        expected_provenance_digest=recorded_digest,
        require_provenance=True,
    )
    return {
        "is_resume": True,
        "existing_records": existing_records,
        "selection": selection,
        "next_run_id": next_run_id(existing_records),
        "manifest_unchanged": True,
        "recorded_manifest": manifest,
    }


def audit_evidence_dir_readonly(evidence_dir: Path) -> dict[str, Any]:
    """Read-only audit of any v1 legacy or v2 provenance evidence directory."""
    ev_dir = Path(evidence_dir)
    manifest_path = ev_dir / "candidate-manifest.json"
    ledger_path = ev_dir / "ledger.jsonl"
    if not manifest_path.is_file() and not ledger_path.is_file():
        return {
            "exists": False,
            "schema_version": None,
            "read_only_historical": False,
            "resumable_v2": False,
            "row_count": 0,
            "errors": ["NO_EVIDENCE_FILES"],
        }

    errors: list[str] = []
    schema_ver: int | None = None
    prov_digest: str | None = None
    recorded_prov: dict[str, Any] | None = None

    if manifest_path.is_file():
        try:
            manifest = _load_manifest_strict(manifest_path)
            if manifest.get("schema_version") == PROVENANCE_SCHEMA_VERSION and isinstance(manifest.get("provenance"), dict):
                schema_ver = PROVENANCE_SCHEMA_VERSION
                recorded_prov = manifest["provenance"]
                prov_digest = manifest.get("provenance_digest")
                if prov_digest != compute_digest(recorded_prov):
                    errors.append("MANIFEST_DIGEST_TAMPERED")
            else:
                schema_ver = 1
        except EvidenceProvenanceError as exc:
            errors.append(f"{exc.code}:{exc.detail}")
    else:
        schema_ver = 1

    rows: list[dict[str, Any]] = []
    try:
        rows = load_jsonl_strict(ledger_path)
    except EvidenceProvenanceError as exc:
        errors.append(f"{exc.code}:{exc.detail}")

    if schema_ver == PROVENANCE_SCHEMA_VERSION and not errors:
        try:
            validate_and_select_ledger(
                rows,
                expected_provenance=recorded_prov,
                expected_provenance_digest=prov_digest,
                require_provenance=True,
            )
        except EvidenceProvenanceError as exc:
            errors.append(f"{exc.code}:{exc.detail}")

    return {
        "exists": True,
        "schema_version": schema_ver,
        "read_only_historical": schema_ver == 1,
        "resumable_v2": schema_ver == PROVENANCE_SCHEMA_VERSION and not errors,
        "provenance_digest": prov_digest,
        "row_count": len(rows),
        "errors": errors,
    }
