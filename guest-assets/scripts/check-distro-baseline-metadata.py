#!/usr/bin/env python3
"""Compare every baseline package against already-downloaded official ARM64 indexes.

No network access. InRelease/RPM trust status is inherited verbatim from the metadata
owner, not upgraded by this name/architecture check. RPM signatures still run in guest.
"""
from pathlib import Path
import contextlib
import gzip
import hashlib
import json
import lzma
import os
import re
import shutil
import subprocess
import tarfile
import xml.etree.ElementTree as ET

assets = Path(__file__).resolve().parent.parent
metadata = Path(os.environ.get("DISTRO_METADATA_ROOT", ""))
work = Path(os.environ.get("GUEST_RUNTIME_TEST_ROOT", ""))
if not metadata.is_dir() or not work.is_dir():
    raise SystemExit("DISTRO_METADATA_ROOT and GUEST_RUNTIME_TEST_ROOT must be existing approved directories")
source = (assets / "scripts/orchestella-guest-provision.sh").read_text()
package_lists = re.findall(r"packages='([^']+)'", source)
assert len(package_lists) == 5, "Package profile shape changed; update the metadata checker"
apt, alpine, rpm, arch, suse = [text.split() for text in package_lists]
profiles = {
    "debian": (apt, ["debian-trixie"]),
    "ubuntu": (apt, ["ubuntu-noble", "ubuntu-noble-universe"]),
    "fedora": ([{"dnf": "dnf5", "wget": "wget2-wget"}.get(name, name) for name in rpm], ["fedora43"]),
    "arch": (arch, ["archlinuxarm-core", "archlinuxarm-extra"]),
    "opensuse": (suse, ["leap16"]),
    "almalinux": (rpm, ["almalinux9-baseos", "almalinux9-appstream"]),
    "rocky": (rpm, ["rocky9-baseos", "rocky9-appstream"]),
}
# Signed minimal variants are valid installed alternatives, but the full default must also exist.
wanted = set().union(*(set(packages) for packages, _ in profiles.values()))
wanted.update({"curl-minimal", "coreutils-single", "wget1-wget"})


def verify_file(info):
    path = Path(info["path"])
    assert path.is_file(), f"metadata file missing: {path.name}"
    if "size" in info:
        assert path.stat().st_size == info["size"], f"metadata size mismatch: {path.name}"
    algorithm = info.get("checksum_type", "sha256")
    expected = info.get("checksum", info.get("sha256"))
    assert expected, "metadata digest missing"
    with path.open("rb") as stream:
        actual = hashlib.file_digest(stream, algorithm).hexdigest()
    assert actual == expected, f"metadata digest mismatch: {path.name}"
    return path


def rpm_inventory(path):
    process = None
    if path.suffix == ".gz":
        stream = gzip.open(path, "rb")
    elif path.suffix == ".zst":
        compiler = os.environ.get("ZSTD", shutil.which("zstd"))
        assert compiler, "Existing zstd command is required for Leap metadata"
        process = subprocess.Popen([compiler, "-dc", str(path)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        stream = process.stdout
    else:
        stream = path.open("rb")
    found = {}
    with contextlib.closing(stream):
        events = ET.iterparse(stream, events=("start", "end"))
        _, root = next(events)
        for event, node in events:
            if event == "end" and node.tag.rsplit("}", 1)[-1] == "package":
                fields = {child.tag.rsplit("}", 1)[-1]: child for child in node}
                name = fields["name"].text
                if name in wanted and fields["arch"].text in ("aarch64", "noarch"):
                    version = fields["version"].attrib
                    found[name] = {"architecture": fields["arch"].text, "version": version,
                                   "package_checksum": fields["checksum"].text,
                                   "location": fields["location"].attrib.get("href")}
                node.clear()
                root.clear()
    if process:
        error = process.stderr.read().decode(errors="replace")
        assert process.wait() == 0, error
    return found


def apt_inventory(path):
    found = {}
    with lzma.open(path, "rt", encoding="utf-8") as stream:
        fields = {}
        for line in stream:
            if line == "\n":
                name = fields.get("Package")
                if name in wanted and fields.get("Architecture") in ("arm64", "all"):
                    found[name] = {"architecture": fields["Architecture"], "version": fields["Version"],
                                   "package_checksum": fields.get("SHA256"), "location": fields.get("Filename")}
                fields = {}
            elif line[:1] not in (" ", "\t") and ": " in line:
                key, value = line.rstrip("\n").split(": ", 1)
                fields[key] = value
    return found


def arch_inventory(path):
    found = {}
    with tarfile.open(path, "r:*") as archive:
        for member in archive:
            if member.isfile() and member.name.endswith("/desc"):
                fields = {}
                key = None
                for line in archive.extractfile(member).read().decode().splitlines():
                    if line.startswith("%") and line.endswith("%"):
                        key = line.strip("%")
                        fields[key] = []
                    elif line and key:
                        fields[key].append(line)
                name = fields.get("NAME", [None])[0]
                if name in wanted:
                    found[name] = {"architecture": fields.get("ARCH", [None])[0],
                                   "version": fields.get("VERSION", [None])[0],
                                   "package_checksum": fields.get("SHA256SUM", [None])[0],
                                   "package_pgpsig_present": bool(fields.get("PGPSIG")),
                                   "location": fields.get("FILENAME", [None])[0]}
    return found


repositories = {}
for name in sorted({repo for _, repos in profiles.values() for repo in repos}):
    record_path = metadata / name / "result.json"
    if not record_path.is_file():
        repositories[name] = {"status": "NOT_AVAILABLE", "packages": {}}
        continue
    record = json.loads(record_path.read_text())
    packages = {}
    if record.get("primary", {}).get("verified"):
        path = verify_file(record["primary"])
        if name.startswith(("debian", "ubuntu")):
            assert record.get("inrelease_signature", {}).get("returncode") == 0, "APT InRelease signature not verified"
            packages = apt_inventory(path)
        else:
            packages = rpm_inventory(path)
    elif "database" in record:
        packages = arch_inventory(verify_file(record["database"]))
    repositories[name] = {"status": record.get("status"), "source": record.get("baseurl"),
                          "signature_status": record.get("signature_status", "InRelease checked by metadata owner"),
                          "packages": packages}
results = {}
for distro, (packages, repos) in profiles.items():
    inventory = {}
    for repo in repos:
        inventory.update(repositories[repo]["packages"])
    missing = sorted(set(packages) - inventory.keys())
    results[distro] = {"baseline": packages, "missing": missing, "matches": inventory,
                      "repositories": repos, "status": "ALL_BASELINE_NAMES_PRESENT" if not missing else "MISSING_OR_UNCHECKED"}
output = work / "distro-baseline-metadata-results.json"
output.write_text(json.dumps({"profiles": results, "repositories": repositories,
                             "runtime_validation": "not performed by metadata name check"}, indent=2), encoding="utf-8")
shutil.copyfile(output, work / "distro-baseline-metadata-results.preserved.json")
for distro, result in results.items():
    print(distro, result["status"], "missing=" + ",".join(result["missing"]))
raise SystemExit(0 if all(not result["missing"] for result in results.values()) else 2)
