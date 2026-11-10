#!/usr/bin/env python3
"""Validate UML research artifact manifests and build GPL source bundles.

The manifest format is ``tools/research/uml-artifacts/manifest.schema.json``.
This tool is research-scope: it never touches the product build, signing or the
release workflow, and it never creates a GitHub Release.

What it checks
--------------
* the manifest matches the JSON Schema (a small, dependency-free validator is
  embedded below; the project does not depend on ``jsonschema`` in CI);
* repository-relative pins (defconfig, initramfs ``init``, build scripts, GPL
  source bundle script) exist and match their sha256;
* artifacts under ``artifactRoot`` are verified when present, and can be
  required with ``--require-artifacts`` (a clean checkout does not carry the
  multi-hundred-megabyte build outputs);
* a rootfs manifest points at an existing U2 manifest whose output digests and
  first-boot contract agree with the entry;
* GPL-2.0 source obligations are recorded, cover every component in the set,
  and the kernel source component matches the declared source pin.

Usage
-----
    python3 tools/research/uml-artifacts/check_uml_artifacts.py
    python3 tools/research/uml-artifacts/check_uml_artifacts.py --self-test
    python3 tools/research/uml-artifacts/check_uml_artifacts.py \
        --manifest tools/research/uml-artifacts/manifests/fast-arm64-up-20261110.json \
        --artifact-root /path/to/build/output/artifacts --require-artifacts

    tools/research/uml-artifacts/build-source-bundle.sh \
        --manifest tools/research/uml-artifacts/manifests/fast-arm64-up-20261110.json \
        --out /tmp/uml-source.tar.gz [--kernel-source /path/to/linux-...tar.xz]

Standard library only (Python 3.9+).
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
import shutil
import sys
import tarfile
import tempfile
from pathlib import Path
from typing import Any, Iterable

HERE = Path(__file__).resolve().parent
SCHEMA_PATH = HERE / "manifest.schema.json"
DEFAULT_MANIFESTS = HERE / "manifests"
REPO_DEFAULT = HERE.parents[2]

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
SET_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{2,80}$")
DATE_TIME_RE = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$"
)
# Strings that would leak a developer-local path or a checkout path into a
# committed manifest. Reports and evidence must use repository-relative paths.
LOCAL_PATH_MARKERS = ("/workspace", "/home/", "/Users/", "/root/", "C:\\", "file://")


# ---------------------------------------------------------------------------
# JSON Schema subset validator
# ---------------------------------------------------------------------------

_TYPE_CHECKS = {
    "object": lambda v: isinstance(v, dict),
    "array": lambda v: isinstance(v, list),
    "string": lambda v: isinstance(v, str),
    "integer": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
    "boolean": lambda v: isinstance(v, bool),
    "null": lambda v: v is None,
}


def _json_pointer(root: Any, ref: str) -> Any:
    if not ref.startswith("#/"):
        raise ValueError(f"only local $ref values are supported, got {ref!r}")
    node = root
    for token in ref[2:].split("/"):
        token = token.replace("~1", "/").replace("~0", "~")
        node = node[token]
    return node


def _format_ok(value: str, fmt: str) -> bool:
    if fmt == "date-time":
        return bool(DATE_TIME_RE.match(value))
    # Unknown formats are annotations only in this project.
    return True


def validate_json(value: Any, schema: Any, root: Any, path: str = "$") -> list[str]:
    """Return a list of human-readable schema violations (empty means valid).

    Supported keywords: type, required, properties, additionalProperties,
    items, enum, const, pattern, minLength, minItems, minimum, maximum,
    uniqueItems, format (date-time), allOf, oneOf and local $ref.
    """
    errors: list[str] = []
    if schema is True:
        return errors
    if not isinstance(schema, dict):
        return [f"{path}: schema node is not an object"]

    if "$ref" in schema:
        return validate_json(value, _json_pointer(root, schema["$ref"]), root, path)

    for sub in schema.get("allOf", []):
        errors.extend(validate_json(value, sub, root, path))

    if "oneOf" in schema:
        matches = 0
        first_errors: list[str] = []
        for sub in schema["oneOf"]:
            sub_errors = validate_json(value, sub, root, path)
            if not sub_errors:
                matches += 1
            elif not first_errors:
                first_errors = sub_errors
        if matches != 1:
            errors.append(f"{path}: oneOf matched {matches} branches (expected 1)")
            errors.extend(first_errors[:3])

    if "const" in schema and value != schema["const"]:
        errors.append(f"{path}: expected const {schema['const']!r}, got {value!r}")

    if "enum" in schema and value not in schema["enum"]:
        errors.append(f"{path}: {value!r} is not one of {schema['enum']!r}")

    expected_type = schema.get("type")
    if expected_type is not None:
        types = expected_type if isinstance(expected_type, list) else [expected_type]
        if not any(_TYPE_CHECKS[t](value) for t in types):
            errors.append(f"{path}: expected type {expected_type!r}, got {type(value).__name__}")
            return errors

    if isinstance(value, dict):
        properties = schema.get("properties", {})
        for required in schema.get("required", []):
            if required not in value:
                errors.append(f"{path}: required property {required!r} is missing")
        if schema.get("additionalProperties") is False:
            for key in value:
                if key not in properties:
                    errors.append(f"{path}: unexpected property {key!r}")
        additional = schema.get("additionalProperties")
        for key, child in value.items():
            if key in properties:
                errors.extend(validate_json(child, properties[key], root, f"{path}.{key}"))
            elif isinstance(additional, dict):
                errors.extend(validate_json(child, additional, root, f"{path}.{key}"))
    elif isinstance(value, list):
        if schema.get("minItems") is not None and len(value) < schema["minItems"]:
            errors.append(f"{path}: expected at least {schema['minItems']} items, got {len(value)}")
        if schema.get("uniqueItems") is True:
            seen: list[str] = []
            for item in value:
                marker = json.dumps(item, sort_keys=True)
                if marker in seen:
                    errors.append(f"{path}: duplicate array item")
                    break
                seen.append(marker)
        items = schema.get("items")
        if items is not None:
            for index, item in enumerate(value):
                errors.extend(validate_json(item, items, root, f"{path}[{index}]"))
    elif isinstance(value, str):
        if schema.get("minLength") is not None and len(value) < schema["minLength"]:
            errors.append(f"{path}: shorter than {schema['minLength']} characters")
        if "pattern" in schema and re.search(schema["pattern"], value) is None:
            errors.append(f"{path}: does not match {schema['pattern']!r}")
        if "format" in schema and not _format_ok(value, schema["format"]):
            errors.append(f"{path}: does not match format {schema['format']!r}")
    elif isinstance(value, int) and not isinstance(value, bool):
        if schema.get("minimum") is not None and value < schema["minimum"]:
            errors.append(f"{path}: less than minimum {schema['minimum']}")
        if schema.get("maximum") is not None and value > schema["maximum"]:
            errors.append(f"{path}: greater than maximum {schema['maximum']}")
    return errors


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


class Report:
    """Collect pass/fail/info lines for one manifest."""

    def __init__(self, label: str) -> None:
        self.label = label
        self.passes = 0
        self.failures: list[str] = []
        self.infos: list[str] = []
        self.warnings: list[str] = []

    def check(self, condition: bool, message: str, detail: str = "") -> bool:
        if condition:
            self.passes += 1
            return True
        self.failures.append(message + (f": {detail}" if detail else ""))
        return False

    def info(self, message: str) -> None:
        self.infos.append(message)

    def warn(self, message: str) -> None:
        self.warnings.append(message)

    def merge(self, other: "Report") -> None:
        self.passes += other.passes
        self.failures.extend(other.failures)
        self.infos.extend(other.infos)
        self.warnings.extend(other.warnings)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_json(path: Path) -> Any:
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def relative_path_ok(value: str) -> bool:
    """Reject absolute paths, drive letters, backslashes and ``..`` segments."""
    if not value or value.startswith("/") or "\\" in value:
        return False
    if re.match(r"^[A-Za-z]:", value):
        return False
    parts = value.split("/")
    return all(part not in ("", ".", "..") for part in parts)


def resolve_under(base: Path, relative: str) -> Path:
    if not relative_path_ok(relative):
        raise ValueError(f"unsafe relative path: {relative!r}")
    resolved = (base / relative).resolve()
    base_resolved = base.resolve()
    if resolved != base_resolved and base_resolved not in resolved.parents:
        raise ValueError(f"path escapes {base}: {relative!r}")
    return resolved


def walk_strings(value: Any) -> Iterable[str]:
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for key, child in value.items():
            yield key
            yield from walk_strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk_strings(child)


def _check_file_pin(
    report: Report, base: Path, pin: dict[str, Any], label: str, must_exist: bool
) -> Path | None:
    relative = pin["path"]
    try:
        path = resolve_under(base, relative)
    except ValueError as exc:
        report.check(False, f"{label} path", str(exc))
        return None
    if not path.is_file():
        if must_exist:
            report.check(False, f"{label} exists", str(path))
        else:
            report.info(f"{label} not present (not committed): {relative}")
        return None
    actual = sha256_file(path)
    report.check(
        actual == pin["sha256"],
        f"{label} sha256",
        f"expected {pin['sha256']}, got {actual} ({relative})",
    )
    return path


# ---------------------------------------------------------------------------
# Manifest checks
# ---------------------------------------------------------------------------


def check_manifest(
    document: dict[str, Any],
    manifest_path: Path,
    repo_root: Path,
    schema: dict[str, Any],
    artifact_root_override: Path | None = None,
    require_artifacts: bool = False,
) -> Report:
    report = Report(str(manifest_path))

    schema_errors = validate_json(document, schema, schema)
    if schema_errors:
        for error in schema_errors:
            report.check(False, "schema", error)
        return report
    report.check(True, "schema")

    set_id = document["setId"]
    report.check(bool(SET_ID_RE.match(set_id)), "setId format", set_id)
    report.check(
        bool(DATE_TIME_RE.match(document["createdAt"])), "createdAt format", document["createdAt"]
    )
    report.check(
        relative_path_ok(document["artifactRoot"]),
        "artifactRoot is repository-relative",
        document["artifactRoot"],
    )

    for text in walk_strings(document):
        marker = next((item for item in LOCAL_PATH_MARKERS if item in text), None)
        if marker:
            report.check(False, "portability scan", f"found local path marker {marker!r} in {text!r}")
            break
    else:
        report.check(True, "portability scan")

    kind = document["kind"]
    source = document.get("source")
    if kind == "kernel-set":
        report.check(source is not None, "kernel-set source block")
    if source is not None:
        report.check(
            re.match(r"^\d+\.\d+", source["version"]) is not None,
            "kernel version format",
            source["version"],
        )

    env = document["build"]["env"]
    report.check("SOURCE_DATE_EPOCH" in env, "build.env pins SOURCE_DATE_EPOCH")
    if kind == "kernel-set":
        for key in (
            "KBUILD_BUILD_TIMESTAMP",
            "KBUILD_BUILD_USER",
            "KBUILD_BUILD_HOST",
            "KBUILD_BUILD_VERSION",
        ):
            report.check(key in env, f"build.env pins {key}")

    _check_file_pin(
        report, repo_root, document["build"]["script"], "build.script", must_exist=True
    )
    report.check(
        bool(document["build"]["command"].strip()), "build.command is non-empty"
    )

    artifact_base = (
        artifact_root_override
        if artifact_root_override is not None
        else (
            repo_root / document["artifactRoot"]
            if relative_path_ok(document["artifactRoot"])
            else repo_root / "__invalid_artifact_root__"
        )
    )
    report.check(
        artifact_root_override is not None or relative_path_ok(document["artifactRoot"]),
        "artifactRoot resolution",
    )

    seen_ids: set[str] = set()
    seen_paths: set[str] = set()
    components: dict[str, list[dict[str, Any]]] = {}
    for artifact in document["artifacts"]:
        artifact_id = artifact["id"]
        report.check(artifact_id not in seen_ids, "artifact id is unique", artifact_id)
        seen_ids.add(artifact_id)
        report.check(
            artifact["path"] not in seen_paths, "artifact path is unique", artifact["path"]
        )
        seen_paths.add(artifact["path"])
        components.setdefault(artifact["component"], []).append(artifact)

        expected_roles = {
            "kernel": {"kernel", "kernel-no-initramfs", "config"},
            "stub": {"stub"},
            "initramfs": {"initramfs"},
            "rootfs": {"rootfs-archive", "rootfs-ext4"},
        }[artifact["component"]]
        report.check(
            artifact["role"] in expected_roles,
            "artifact role matches component",
            f"{artifact_id}: {artifact['role']} for {artifact['component']}",
        )

        try:
            path = resolve_under(artifact_base, artifact["path"])
        except ValueError as exc:
            report.check(False, f"artifact {artifact_id} path", str(exc))
            continue
        if not path.is_file():
            if require_artifacts:
                report.check(False, f"artifact {artifact_id} present", str(path))
            else:
                report.info(f"artifact {artifact_id} not present (not committed): {path}")
            continue
        actual_size = path.stat().st_size
        report.check(
            actual_size == artifact["sizeBytes"],
            f"artifact {artifact_id} size",
            f"expected {artifact['sizeBytes']}, got {actual_size}",
        )
        actual_sha = sha256_file(path)
        report.check(
            actual_sha == artifact["sha256"],
            f"artifact {artifact_id} sha256",
            f"expected {artifact['sha256']}, got {actual_sha}",
        )

    if kind == "kernel-set":
        kernel_binaries = [
            artifact
            for artifact in components.get("kernel", [])
            if artifact["role"] in ("kernel", "kernel-no-initramfs")
        ]
        report.check(
            bool(kernel_binaries), "kernel-set has a kernel binary artifact"
        )
        report.check(
            relative_path_ok(document["kernel"]["defconfig"]["path"]),
            "kernel.defconfig is repository-relative",
        )
        defconfig = _check_file_pin(
            report, repo_root, document["kernel"]["defconfig"], "kernel.defconfig", must_exist=True
        )
        if defconfig is not None:
            text = defconfig.read_text(encoding="utf-8", errors="replace")
            report.check(
                "CONFIG_" in text, "kernel.defconfig contains CONFIG_ symbols", str(defconfig)
            )
            for symbol in ("CONFIG_IKCONFIG=y", "CONFIG_IKCONFIG_PROC=y"):
                if symbol not in text:
                    report.warn(
                        f"kernel.defconfig does not contain {symbol}; embedded config "
                        "provenance (extract-ikconfig) will be unavailable"
                    )
        effective = document["kernel"].get("effectiveConfig")
        if effective is not None:
            _check_file_pin(
                report,
                artifact_base,
                effective,
                "kernel.effectiveConfig",
                must_exist=require_artifacts,
            )

    stub = document.get("stub")
    if stub is not None:
        stub_artifacts = components.get("stub", [])
        report.check(bool(stub_artifacts), "stub block has a stub artifact")
        for artifact in stub_artifacts:
            report.check(
                artifact.get("pAlign") == stub["pAlign"],
                "stub pAlign matches stub block",
                f"{artifact['id']}: {artifact.get('pAlign')} != {stub['pAlign']}",
            )
        if stub["pAlign"] < 16384:
            report.warn(
                f"stub pAlign is {stub['pAlign']}, below the 16 KB lane requirement; "
                "record a 16 KB re-link before a 16 KB host gate"
            )

    initramfs = document.get("initramfs")
    if initramfs is not None:
        report.check(
            bool(components.get("initramfs")), "initramfs block has an initramfs artifact"
        )
        for artifact in components.get("initramfs", []):
            report.check(
                artifact["format"] == initramfs["format"],
                "initramfs artifact format matches block",
                f"{artifact['id']}: {artifact['format']} != {initramfs['format']}",
            )
        report.check(
            relative_path_ok(initramfs["init"]["path"]),
            "initramfs.init is repository-relative",
        )
        _check_file_pin(report, repo_root, initramfs["init"], "initramfs.init", must_exist=True)

    rootfs = document.get("rootfs")
    if rootfs is not None:
        report.check(bool(components.get("rootfs")), "rootfs block has a rootfs artifact")
        _check_rootfs(report, rootfs, repo_root)

    _check_gpl(report, document, repo_root)
    return report


def _check_rootfs(report: Report, rootfs: dict[str, Any], repo_root: Path) -> None:
    manifest_path = repo_root / rootfs["manifestPath"]
    if not relative_path_ok(rootfs["manifestPath"]) or not manifest_path.is_file():
        report.check(False, "rootfs.manifestPath exists", str(manifest_path))
        return
    actual = sha256_file(manifest_path)
    report.check(
        actual == rootfs["manifestSha256"],
        "rootfs.manifestSha256",
        f"expected {rootfs['manifestSha256']}, got {actual}",
    )
    try:
        upstream = load_json(manifest_path)
    except (OSError, json.JSONDecodeError) as exc:
        report.check(False, "rootfs manifest parses", str(exc))
        return
    report.check(upstream.get("schemaVersion") == 1, "rootfs manifest schemaVersion")

    distro = upstream.get("distro", {})
    for key in ("name", "version", "arch", "libc"):
        report.check(
            distro.get(key) == rootfs["distro"][key],
            f"rootfs distro.{key} matches U2 manifest",
            f"{distro.get(key)!r} != {rootfs['distro'][key]!r}",
        )

    upstream_outputs = {entry.get("sha256"): entry for entry in upstream.get("outputs", [])}
    for output in rootfs["outputs"]:
        match = upstream_outputs.get(output["sha256"])
        report.check(
            match is not None,
            "rootfs output sha256 appears in U2 manifest",
            output["path"],
        )
        if match is not None:
            report.check(
                match.get("sizeBytes") == output["sizeBytes"],
                "rootfs output size matches U2 manifest",
                f"{match.get('sizeBytes')} != {output['sizeBytes']}",
            )
            report.check(
                match.get("format") == output["format"],
                "rootfs output format matches U2 manifest",
            )

    first_boot = upstream.get("provisioning", {}).get("firstBoot", {})
    report.check(
        first_boot.get("packageManagerInvocations") == 0,
        "U2 manifest records zero first-boot package-manager invocations",
    )
    report.check(
        first_boot.get("networkRequired") is False,
        "U2 manifest records no first-boot network requirement",
    )


def _check_gpl(
    report: Report,
    document: dict[str, Any],
    repo_root: Path,
) -> None:
    gpl = document["gpl"]
    report.check(bool(gpl["obligations"]), "gpl.obligations is non-empty")

    required = set()
    if document["kind"] == "kernel-set":
        required.add("kernel")
    if "stub" in document:
        required.add("stub")
    if "initramfs" in document:
        required.add("initramfs")
    if "rootfs" in document:
        required.add("rootfs")

    covered: set[str] = set()
    for component in gpl["sourceComponents"]:
        covered.update(component["covers"])
        report.check(
            bool(component["url"]), "gpl source component has a URL", component["name"]
        )
    missing = sorted(required - covered)
    report.check(not missing, "gpl source components cover every component", ", ".join(missing))

    source = document.get("source")
    if source is not None and "kernel" in required:
        kernel_components = [
            component for component in gpl["sourceComponents"] if "kernel" in component["covers"]
        ]
        report.check(bool(kernel_components), "gpl has a kernel source component")
        report.check(
            any(
                component["revision"] == source["commit"]
                and component.get("sha256") == source["tarballSha256"]
                for component in kernel_components
            ),
            "gpl kernel component matches source commit and tarball sha256",
        )
        report.check(
            any(source["project"] in component["url"] for component in kernel_components),
            "gpl kernel component URL contains the source project",
        )

    script_paths = {pin["path"] for pin in gpl["buildScripts"]}
    report.check(
        document["build"]["script"]["path"] in script_paths,
        "gpl.buildScripts includes build.script",
        document["build"]["script"]["path"],
    )
    for pin in gpl["buildScripts"]:
        _check_file_pin(report, repo_root, pin, "gpl.buildScripts entry", must_exist=True)

    _check_file_pin(
        report, repo_root, gpl["bundle"]["script"], "gpl.bundle.script", must_exist=True
    )
    report.check(
        gpl["bundle"]["format"] == "tar.gz", "gpl.bundle format is tar.gz"
    )


# ---------------------------------------------------------------------------
# GPL source bundle
# ---------------------------------------------------------------------------


def _write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def _copy_into(stage: Path, source: Path, relative: str) -> Path:
    target = stage / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    shutil.copymode(source, target)
    return target


def _bundle_readme(document: dict[str, Any], manifest_name: str, kernel_included: bool) -> str:
    has_kernel = document.get("source") is not None
    lines = [
        "UML research artifact source bundle",
        "===================================",
        "",
        f"Set:              {document['setId']}",
        f"Kind:             {document['kind']}",
        f"Generated from:   {manifest_name}",
        "",
        "This bundle is a research-side corresponding-source deliverable. It is",
        "not a release, is not signed, and makes no distribution commitment.",
        "",
        "Contents",
        "--------",
        "- SOURCE-PIN.json: source pins, toolchain, build command and file digests.",
        "- NOTICE.md: artifact list, license focus and source component attribution.",
        "- configs/: committed defconfigs and initramfs init scripts.",
        "- build-scripts/: scripts that control compilation and installation.",
        "- rootfs-manifests/: U2 pre-bake manifests when the set contains a rootfs.",
    ]
    if has_kernel:
        lines.append(
            f"- upstream/: kernel source archive "
            f"({'included' if kernel_included else 'NOT included'})."
        )
    lines.extend(
        [
            "- SHA256SUMS: digests of every file above.",
            "",
            "Recorded GPL-2.0 obligations (record only; not a legal conclusion)",
            "---------------------------------------------------------------",
        ]
    )
    lines.extend(f"- {item}" for item in document["gpl"]["obligations"])
    lines.extend(
        [
            "",
            "Rebuild",
            "-------",
            f"Build script: {document['build']['script']['path']}",
            f"Command:      {document['build']['command']}",
            "Environment:",
        ]
    )
    lines.extend(f"  {key}={value}" for key, value in sorted(document["build"]["env"].items()))
    lines.extend(
        [
            "",
            "Source components",
            "-----------------",
        ]
    )
    for component in document["gpl"]["sourceComponents"]:
        lines.append(
            f"- {component['name']} ({component['license']}): {component['url']} "
            f"@ {component['revision']}"
        )
    lines.append("")
    if has_kernel and not kernel_included:
        lines.append(
            "The kernel source archive was not included at bundle time. A bundle used"
        )
        lines.append(
            "for conveyance must add it (build-source-bundle.sh --kernel-source)."
        )
    return "\n".join(lines)


def _bundle_notice(document: dict[str, Any], manifest_name: str) -> str:
    lines = [
        "NOTICE — UML research artifact set",
        "==================================",
        "",
        f"Set:            {document['setId']}",
        f"Manifest:       {manifest_name}",
        f"License focus:  {document['gpl']['license']}",
        "",
        "Artifacts recorded by this set:",
    ]
    for artifact in document["artifacts"]:
        lines.append(
            f"- {artifact['id']} ({artifact['component']}/{artifact['role']}) "
            f"sha256={artifact['sha256']} size={artifact['sizeBytes']}"
        )
    lines.extend(["", "Source components:"])
    for component in document["gpl"]["sourceComponents"]:
        lines.append(
            f"- {component['name']} — {component['license']} — "
            f"{component['url']} @ {component['revision']}"
        )
    lines.extend(
        [
            "",
            "The GPL-2.0 text is not embedded in this research bundle. A bundle used for",
            "conveyance must include the license text, copyright notices and any NOTICE",
            "material required by the packaged rootfs. See:",
            "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html",
        ]
    )
    return "\n".join(lines)


def build_source_bundle(
    document: dict[str, Any],
    manifest_path: Path,
    repo_root: Path,
    out_path: Path,
    kernel_source: Path | None,
) -> tuple[str, int]:
    """Assemble a deterministic .tar.gz and return (sha256, file count)."""
    manifest_sha = sha256_file(manifest_path)
    stage = Path(tempfile.mkdtemp(prefix="uml-source-bundle-"))
    try:
        files: dict[str, str] = {}
        pins: list[dict[str, str]] = []

        def add_file(source: Path, bundle_rel: str) -> None:
            target = _copy_into(stage, source, bundle_rel)
            files[bundle_rel] = sha256_file(target)
            pins.append({"path": bundle_rel, "sha256": files[bundle_rel]})

        def add_pin_if_present(pin: dict[str, Any], base: Path, bundle_rel: str) -> bool:
            try:
                source = resolve_under(base, pin["path"])
            except ValueError:
                return False
            if not source.is_file():
                return False
            add_file(source, bundle_rel)
            return True

        kernel_included = False
        if kernel_source is not None:
            source_pin = document.get("source")
            if source_pin is None:
                raise ValueError("this manifest has no kernel source pin for --kernel-source")
            expected = source_pin["tarballSha256"]
            actual = sha256_file(kernel_source)
            if expected is not None and actual != expected:
                raise ValueError(
                    f"kernel source sha256 {actual} does not match manifest {expected}"
                )
            add_file(kernel_source, f"upstream/{kernel_source.name}")
            kernel_included = True

        if document["kind"] == "kernel-set":
            add_pin_if_present(
                document["kernel"]["defconfig"],
                repo_root,
                f"configs/{document['kernel']['defconfig']['path']}",
            )
            if "effectiveConfig" in document["kernel"]:
                add_pin_if_present(
                    document["kernel"]["effectiveConfig"],
                    repo_root / document["artifactRoot"],
                    f"configs/{document['kernel']['effectiveConfig']['path']}",
                )
            if "initramfs" in document:
                add_pin_if_present(
                    document["initramfs"]["init"],
                    repo_root,
                    f"configs/{document['initramfs']['init']['path']}",
                )
        if "rootfs" in document:
            try:
                rootfs_manifest = resolve_under(repo_root, document["rootfs"]["manifestPath"])
            except ValueError as exc:
                raise ValueError(f"unsafe rootfs manifest path: {exc}") from exc
            add_file(rootfs_manifest, f"rootfs-manifests/{rootfs_manifest.name}")

        for pin in document["gpl"]["buildScripts"]:
            add_pin_if_present(pin, repo_root, f"build-scripts/{pin['path']}")

        pin_document = {
            "bundleVersion": 1,
            "manifestSha256": manifest_sha,
            "setId": document["setId"],
            "kind": document["kind"],
            "artifactRoot": document["artifactRoot"],
            "notes": document.get("notes", []),
            "source": document.get("source"),
            "toolchain": document["toolchain"],
            "build": document["build"],
            "artifacts": document["artifacts"],
            "rootfs": document.get("rootfs"),
            "gplSourceComponents": document["gpl"]["sourceComponents"],
            "kernelSourceIncluded": kernel_included,
            "files": pins,
        }
        source_pin_text = json.dumps(pin_document, indent=2, sort_keys=True) + "\n"
        _write_text(stage / "SOURCE-PIN.json", source_pin_text)
        files["SOURCE-PIN.json"] = sha256_bytes(source_pin_text.encode("utf-8"))
        readme_text = _bundle_readme(document, manifest_path.name, kernel_included)
        _write_text(stage / "README.md", readme_text)
        files["README.md"] = sha256_bytes(readme_text.encode("utf-8"))
        notice_text = _bundle_notice(document, manifest_path.name)
        _write_text(stage / "NOTICE.md", notice_text)
        files["NOTICE.md"] = sha256_bytes(notice_text.encode("utf-8"))

        sums = "".join(
            f"{digest}  {name}\n" for name, digest in sorted(files.items())
        )
        _write_text(stage / "SHA256SUMS", sums)

        out_path.parent.mkdir(parents=True, exist_ok=True)
        _tar_stage(stage, out_path)
        return sha256_file(out_path), len(files) + 1
    finally:
        shutil.rmtree(stage, ignore_errors=True)


def _tar_stage(stage: Path, out_path: Path) -> None:
    entries = sorted(stage.rglob("*"), key=lambda item: item.relative_to(stage).as_posix())
    with open(out_path, "wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0, compresslevel=9) as gz:
            with tarfile.open(fileobj=gz, mode="w", format=tarfile.USTAR_FORMAT) as archive:
                for entry in entries:
                    arcname = entry.relative_to(stage).as_posix()
                    info = archive.gettarinfo(str(entry), arcname=arcname)
                    info.uid = 0
                    info.gid = 0
                    info.uname = "root"
                    info.gname = "root"
                    info.mtime = 0
                    if info.isdir():
                        info.mode = 0o755
                        archive.addfile(info)
                    elif info.isreg():
                        info.mode = 0o755 if entry.stat().st_mode & 0o111 else 0o644
                        with open(entry, "rb") as handle:
                            archive.addfile(info, handle)
                    else:
                        raise ValueError(f"unsupported entry type in bundle: {entry}")


def verify_source_bundle(
    bundle_path: Path, expected_manifest: Path | None = None
) -> tuple[int, list[str]]:
    errors: list[str] = []
    temp = Path(tempfile.mkdtemp(prefix="uml-source-verify-"))
    try:
        try:
            with tarfile.open(bundle_path, "r:gz") as archive:
                members = archive.getmembers()
                for member in members:
                    name = member.name
                    if not relative_path_ok(name.rstrip("/")):
                        errors.append(f"unsafe archive member: {name!r}")
                        continue
                    if not (member.isdir() or member.isreg()):
                        errors.append(f"unsupported archive member type: {name!r}")
                        continue
                if errors:
                    return 0, errors
                if hasattr(tarfile, "data_filter"):
                    archive.extractall(temp, filter="data")
                else:
                    archive.extractall(temp)
        except (tarfile.TarError, OSError, EOFError) as exc:
            return 0, [f"cannot read bundle: {exc}"]

        sums_path = temp / "SHA256SUMS"
        if not sums_path.is_file():
            return 0, ["bundle has no SHA256SUMS"]
        listed: dict[str, str] = {}
        for line in sums_path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            match = re.match(r"^([0-9a-f]{64})  (.+)$", line)
            if not match:
                errors.append(f"malformed SHA256SUMS line: {line!r}")
                continue
            listed[match.group(2)] = match.group(1)

        actual_files = {
            item.relative_to(temp).as_posix()
            for item in temp.rglob("*")
            if item.is_file() and item.name != "SHA256SUMS"
        }
        for name in sorted(actual_files - set(listed)):
            errors.append(f"file not listed in SHA256SUMS: {name}")
        for name in sorted(set(listed) - actual_files):
            errors.append(f"file listed but missing: {name}")
        for name, expected in sorted(listed.items()):
            path = temp / name
            if path.is_file() and sha256_file(path) != expected:
                errors.append(f"sha256 mismatch: {name}")

        source_pin_path = temp / "SOURCE-PIN.json"
        if not source_pin_path.is_file():
            errors.append("bundle has no SOURCE-PIN.json")
        else:
            try:
                pin_document = load_json(source_pin_path)
            except json.JSONDecodeError as exc:
                errors.append(f"SOURCE-PIN.json is not valid JSON: {exc}")
                pin_document = {}
            if expected_manifest is not None and pin_document.get("manifestSha256") != sha256_file(
                expected_manifest
            ):
                errors.append("SOURCE-PIN.manifestSha256 does not match the expected manifest")
        return len(actual_files), errors
    finally:
        shutil.rmtree(temp, ignore_errors=True)


# ---------------------------------------------------------------------------
# Self-test
# ---------------------------------------------------------------------------


def _synthetic_manifest(repo: Path) -> tuple[dict[str, Any], Path]:
    def write(relative: str, data: bytes) -> str:
        path = repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return sha256_bytes(data)

    def pin(relative: str, data: bytes) -> dict[str, str]:
        return {"path": relative, "sha256": write(relative, data)}

    script_sha = write("scripts/build.sh", b"#!/bin/sh\nset -eu\n")
    bundle_sha = write("scripts/bundle.sh", b"#!/bin/sh\nset -eu\n")
    defconfig = pin("scripts/defconfig", b"CONFIG_UML=y\nCONFIG_HOSTFS=y\n")
    init = pin("scripts/init", b"#!/bin/sh\nexec /bin/sh\n")
    effective = pin("out/kernel.config", b"CONFIG_UML=y\n")
    kernel_sha = write("out/kernel", b"kernel-image")
    stub_sha = write("out/stub", b"stub-image")
    initramfs_sha = write("out/initramfs.cpio.gz", b"initramfs-image")
    tarball_sha = write("upstream/linux-test.tar.gz", b"linux-source-archive")

    manifest = {
        "schemaVersion": 1,
        "kind": "kernel-set",
        "setId": "self-test-set",
        "createdAt": "2026-11-10T00:00:00Z",
        "artifactRoot": "out",
        "notes": ["synthetic fixture for --self-test"],
        "source": {
            "project": "example/linux",
            "tag": "v1",
            "commit": "a" * 40,
            "version": "7.2.0-rc4",
            "license": "GPL-2.0-only",
            "tarballSha256": tarball_sha,
        },
        "toolchain": {
            "name": "example-ndk",
            "version": "r1",
            "compiler": "clang 1.0",
            "buildContainer": "ubuntu:24.04",
            "hostArch": "x86_64",
            "byteReproducible": True,
            "reproducibilityNote": "synthetic",
        },
        "build": {
            "script": {"path": "scripts/build.sh", "sha256": script_sha},
            "command": "sh scripts/build.sh",
            "env": {
                "SOURCE_DATE_EPOCH": "0",
                "KBUILD_BUILD_TIMESTAMP": "Thu Jan 1 00:00:00 UTC 1970",
                "KBUILD_BUILD_USER": "orchestella",
                "KBUILD_BUILD_HOST": "u1-fastboot",
                "KBUILD_BUILD_VERSION": "1",
            },
        },
        "kernel": {"defconfig": defconfig, "effectiveConfig": effective},
        "stub": {"pAlign": 16384},
        "initramfs": {
            "format": "cpio.gz",
            "init": init,
            "sources": [{"name": "busybox", "version": "1.0", "sha256": "c" * 64}],
        },
        "artifacts": [
            {
                "id": "kernel",
                "component": "kernel",
                "role": "kernel",
                "path": "kernel",
                "sha256": kernel_sha,
                "sizeBytes": len(b"kernel-image"),
                "format": "elf",
            },
            {
                "id": "stub",
                "component": "stub",
                "role": "stub",
                "path": "stub",
                "sha256": stub_sha,
                "sizeBytes": len(b"stub-image"),
                "format": "elf",
                "pAlign": 16384,
            },
            {
                "id": "initramfs",
                "component": "initramfs",
                "role": "initramfs",
                "path": "initramfs.cpio.gz",
                "sha256": initramfs_sha,
                "sizeBytes": len(b"initramfs-image"),
                "format": "cpio.gz",
            },
        ],
        "gpl": {
            "license": "GPL-2.0-only",
            "obligations": ["ship complete corresponding source"],
            "sourceComponents": [
                {
                    "name": "linux",
                    "license": "GPL-2.0-only",
                    "url": "https://example.invalid/example/linux",
                    "revision": "a" * 40,
                    "sha256": tarball_sha,
                    "covers": ["kernel", "stub"],
                },
                {
                    "name": "busybox",
                    "license": "GPL-2.0-only",
                    "url": "https://example.invalid/busybox",
                    "revision": "1.0",
                    "covers": ["initramfs"],
                },
            ],
            "buildScripts": [{"path": "scripts/build.sh", "sha256": script_sha}],
            "bundle": {
                "script": {"path": "scripts/bundle.sh", "sha256": bundle_sha},
                "format": "tar.gz",
            },
        },
    }
    manifest_path = repo / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest, manifest_path


def _self_test_check(report: Report, name: str, condition: bool, detail: str = "") -> None:
    if not condition:
        report.check(False, f"self-test {name}", detail)
    else:
        report.passes += 1


def self_test() -> int:
    schema = load_json(SCHEMA_PATH)
    report = Report("self-test")

    with tempfile.TemporaryDirectory(prefix="uml-artifacts-self-test-") as tmp:
        repo = Path(tmp) / "repo"
        repo.mkdir()
        manifest, manifest_path = _synthetic_manifest(repo)
        clean = check_manifest(manifest, manifest_path, repo, schema)
        report.merge(clean)
        _self_test_check(report, "valid manifest passes", not clean.failures, "; ".join(clean.failures))

        # Schema rejects unknown properties and missing required blocks.
        bad_schema = json.loads(json.dumps(manifest))
        bad_schema["unexpected"] = True
        errors = validate_json(bad_schema, schema, schema)
        _self_test_check(report, "schema rejects unknown property", bool(errors), str(errors))

        bad_env = json.loads(json.dumps(manifest))
        bad_env["build"]["env"]["KBUILD_BUILD_VERSION"] = 1
        errors = validate_json(bad_env, schema, schema)
        _self_test_check(report, "schema checks additionalProperties values", bool(errors), str(errors))

        missing_block = json.loads(json.dumps(manifest))
        del missing_block["kernel"]
        errors = validate_json(missing_block, schema, schema)
        _self_test_check(report, "schema requires kernel for kernel-set", bool(errors), str(errors))

        # A tampered artifact digest is a failure.
        tampered = json.loads(json.dumps(manifest))
        tampered["artifacts"][0]["sha256"] = "0" * 64
        bad = check_manifest(tampered, manifest_path, repo, schema)
        _self_test_check(report, "tampered artifact sha256 fails", bool(bad.failures))

        # Missing artifact is allowed in a clean checkout unless required.
        missing_artifact = json.loads(json.dumps(manifest))
        missing_artifact["artifacts"][0]["path"] = "absent-kernel"
        lenient = check_manifest(missing_artifact, manifest_path, repo, schema)
        strict = check_manifest(
            missing_artifact, manifest_path, repo, schema, require_artifacts=True
        )
        _self_test_check(report, "missing artifact is informational by default", not lenient.failures)
        _self_test_check(report, "missing artifact fails with --require-artifacts", bool(strict.failures))

        # Path traversal is rejected before touching the filesystem.
        traversal = json.loads(json.dumps(manifest))
        traversal["artifacts"][0]["path"] = "../outside"
        bad = check_manifest(traversal, manifest_path, repo, schema)
        _self_test_check(report, "path traversal is rejected", bool(bad.failures))

        # Defconfig digest mismatch is a failure.
        bad_defconfig = json.loads(json.dumps(manifest))
        bad_defconfig["kernel"]["defconfig"]["sha256"] = "1" * 64
        bad = check_manifest(bad_defconfig, manifest_path, repo, schema)
        _self_test_check(report, "defconfig sha256 mismatch fails", bool(bad.failures))

        # Missing GPL coverage for the initramfs is a failure.
        bad_gpl = json.loads(json.dumps(manifest))
        bad_gpl["gpl"]["sourceComponents"] = [
            component
            for component in bad_gpl["gpl"]["sourceComponents"]
            if "initramfs" not in component["covers"]
        ]
        bad = check_manifest(bad_gpl, manifest_path, repo, schema)
        _self_test_check(report, "gpl coverage gap fails", bool(bad.failures))

        # Local absolute paths are rejected by the portability scan.
        local_path = json.loads(json.dumps(manifest))
        local_path["notes"] = ["built in /home/someone/checkout"]
        bad = check_manifest(local_path, manifest_path, repo, schema)
        _self_test_check(report, "local path marker fails", bool(bad.failures))

        # Duplicate artifact ids are a failure.
        duplicate = json.loads(json.dumps(manifest))
        duplicate["artifacts"][1]["id"] = duplicate["artifacts"][0]["id"]
        bad = check_manifest(duplicate, manifest_path, repo, schema)
        _self_test_check(report, "duplicate artifact id fails", bool(bad.failures))

        # Bundle is deterministic and self-verifying.
        first = Path(tmp) / "bundle-1.tar.gz"
        second = Path(tmp) / "bundle-2.tar.gz"
        sha_first, _ = build_source_bundle(manifest, manifest_path, repo, first, None)
        sha_second, _ = build_source_bundle(manifest, manifest_path, repo, second, None)
        _self_test_check(report, "source bundle is byte-reproducible", sha_first == sha_second)
        count, verify_errors = verify_source_bundle(first, manifest_path)
        _self_test_check(report, "source bundle verifies", not verify_errors, "; ".join(verify_errors))
        _self_test_check(report, "source bundle contains files", count >= 5, str(count))

        # Optional kernel source inclusion is pinned to the manifest digest.
        kernel_tarball = repo / "upstream" / "linux-test.tar.gz"
        with_source = Path(tmp) / "bundle-with-source.tar.gz"
        build_source_bundle(manifest, manifest_path, repo, with_source, kernel_tarball)
        _, verify_errors = verify_source_bundle(with_source, manifest_path)
        _self_test_check(
            report, "bundle with kernel source verifies", not verify_errors, "; ".join(verify_errors)
        )
        with tarfile.open(with_source, "r:gz") as archive:
            member = archive.extractfile("SOURCE-PIN.json")
            pin_document = json.loads(member.read()) if member is not None else {}
        _self_test_check(
            report,
            "bundle records kernel source inclusion",
            pin_document.get("kernelSourceIncluded") is True,
        )
        wrong_source = Path(tmp) / "wrong-kernel-source.tar.gz"
        wrong_source.write_bytes(b"not-the-pinned-source")
        try:
            build_source_bundle(manifest, manifest_path, repo, Path(tmp) / "refused.tar.gz", wrong_source)
            refused = False
        except ValueError:
            refused = True
        _self_test_check(report, "bundle rejects a mismatched kernel source", refused)

        # A modified member inside an otherwise valid archive must fail.
        tamper_dir = Path(tmp) / "tamper"
        with tarfile.open(first, "r:gz") as archive:
            if hasattr(tarfile, "data_filter"):
                archive.extractall(tamper_dir, filter="data")
            else:
                archive.extractall(tamper_dir)
        target = tamper_dir / "SOURCE-PIN.json"
        target.write_text(target.read_text(encoding="utf-8") + "\n", encoding="utf-8")
        tampered_bundle = Path(tmp) / "bundle-tampered.tar.gz"
        _tar_stage(tamper_dir, tampered_bundle)
        _, verify_errors = verify_source_bundle(tampered_bundle, manifest_path)
        _self_test_check(report, "tampered bundle fails verification", bool(verify_errors))

    if report.failures:
        for failure in report.failures:
            print(f"[FAIL] {failure}", file=sys.stderr)
        print(f"SELF_TEST_FAILED failures={len(report.failures)} checks={report.passes}")
        return 1
    print(f"SELF_TEST_OK checks={report.passes}")
    return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _print_report(report: Report) -> None:
    print(f"== {report.label}")
    print(f"[PASS] {report.passes} checks")
    for warning in report.warnings:
        print(f"[WARN] {warning}")
    for info in report.infos:
        print(f"[INFO] {info}")
    for failure in report.failures:
        print(f"[FAIL] {failure}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Validate UML research artifact manifests and GPL source bundles."
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=REPO_DEFAULT,
        help="repository root (default: the checkout containing this script)",
    )
    parser.add_argument(
        "--manifest",
        action="append",
        default=[],
        help="manifest to validate; may be repeated (default: manifests/*.json)",
    )
    parser.add_argument(
        "--manifests-dir",
        type=Path,
        default=DEFAULT_MANIFESTS,
        help="directory scanned when --manifest is not given",
    )
    parser.add_argument(
        "--artifact-root",
        type=Path,
        default=None,
        help="override artifactRoot for every manifest (for an external build output)",
    )
    parser.add_argument(
        "--require-artifacts",
        action="store_true",
        help="fail when an artifact file is missing (default: informational)",
    )
    parser.add_argument(
        "--build-source-bundle",
        type=Path,
        default=None,
        metavar="MANIFEST",
        help="assemble a GPL corresponding-source bundle from MANIFEST",
    )
    parser.add_argument("--out", type=Path, default=None, help="bundle output path")
    parser.add_argument(
        "--kernel-source",
        type=Path,
        default=None,
        help="kernel source archive to include (sha256 must match the manifest)",
    )
    parser.add_argument(
        "--verify-source-bundle",
        type=Path,
        default=None,
        metavar="FILE",
        help="verify an existing source bundle",
    )
    parser.add_argument(
        "--expect-manifest",
        type=Path,
        default=None,
        help="require the bundle's SOURCE-PIN.manifestSha256 to match this manifest",
    )
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    schema = load_json(SCHEMA_PATH)

    if args.verify_source_bundle is not None:
        count, errors = verify_source_bundle(args.verify_source_bundle, args.expect_manifest)
        for error in errors:
            print(f"[FAIL] {error}", file=sys.stderr)
        if errors:
            print(f"BUNDLE_VERIFY_FAILED files={count} errors={len(errors)}")
            return 1
        print(f"BUNDLE_VERIFY_OK files={count}")
        return 0

    # The shell entry point documents ``--manifest X --out Y`` for bundle
    # creation; treat that pair as ``--build-source-bundle X``.
    if args.build_source_bundle is None and args.out is not None:
        if len(args.manifest) != 1:
            parser.error("--out requires exactly one --manifest (or --build-source-bundle)")
        args.build_source_bundle = Path(args.manifest[0])

    if args.build_source_bundle is not None:
        manifest_path = args.build_source_bundle.resolve()
        if args.out is None:
            parser.error("--build-source-bundle requires --out")
        try:
            document = load_json(manifest_path)
        except (OSError, json.JSONDecodeError) as exc:
            print(f"error: cannot read manifest: {exc}", file=sys.stderr)
            return 2
        report = check_manifest(document, manifest_path, args.repo_root.resolve(), schema)
        _print_report(report)
        if report.failures:
            print("BUNDLE_BUILD_REFUSED: manifest validation failed")
            return 1
        try:
            digest, file_count = build_source_bundle(
                document,
                manifest_path,
                args.repo_root.resolve(),
                args.out.resolve(),
                args.kernel_source.resolve() if args.kernel_source else None,
            )
        except (OSError, ValueError) as exc:
            print(f"error: cannot build bundle: {exc}", file=sys.stderr)
            return 2
        print(f"BUNDLE_OK path={args.out} sha256={digest} files={file_count}")
        return 0

    manifest_paths = (
        [Path(item) for item in args.manifest]
        if args.manifest
        else sorted(args.manifests_dir.glob("*.json"))
    )
    if not manifest_paths:
        parser.error("no manifests found")

    total_passes = 0
    total_failures = 0
    total_infos = 0
    for manifest_path in manifest_paths:
        try:
            document = load_json(manifest_path)
        except (OSError, json.JSONDecodeError) as exc:
            print(f"[FAIL] {manifest_path}: cannot read manifest: {exc}", file=sys.stderr)
            total_failures += 1
            continue
        report = check_manifest(
            document,
            manifest_path,
            args.repo_root.resolve(),
            schema,
            artifact_root_override=args.artifact_root.resolve() if args.artifact_root else None,
            require_artifacts=args.require_artifacts,
        )
        _print_report(report)
        total_passes += report.passes
        total_failures += len(report.failures)
        total_infos += len(report.infos)

    print(
        f"SUMMARY manifests={len(manifest_paths)} passed={total_passes} "
        f"failed={total_failures} info={total_infos}"
    )
    return 1 if total_failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
