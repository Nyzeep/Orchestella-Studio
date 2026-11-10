#!/usr/bin/env python3
"""Generate manifest.json for a pre-baked rootfs.

The manifest is the bridge between a build input set and a shipped archive.
It records the exact package versions, repository indexes, locale set,
certificate bundle and artifact checksums so verify.sh can prove that an
unpacked rootfs is the one the manifest describes.
"""

import argparse
import hashlib
import json
import os
import stat
import sys


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def tree_digest(root):
    """Stable digest of path, type, mode, size and content for every entry.

    mtimes are intentionally excluded: the archive already clamps them to
    SOURCE_DATE_EPOCH. Including them would add no integrity value and would
    make the digest depend on extraction order.
    """
    h = hashlib.sha256()
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for name in list(dirnames):
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            st = os.lstat(path)
            mode = stat.S_IMODE(st.st_mode)
            if os.path.islink(path):
                dirnames.remove(name)
                h.update(("L %o %s -> %s\n" % (mode, rel, os.readlink(path))).encode())
            else:
                h.update(("D %o %s\n" % (mode, rel)).encode())
        for name in sorted(filenames):
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            st = os.lstat(path)
            mode = stat.S_IMODE(st.st_mode)
            if stat.S_ISLNK(st.st_mode):
                line = "L %o %s -> %s" % (mode, rel, os.readlink(path))
            elif stat.S_ISREG(st.st_mode):
                line = "F %o %d %s %s" % (mode, st.st_size, sha256_file(path), rel)
            elif stat.S_ISDIR(st.st_mode):
                line = "D %o %s" % (mode, rel)
            else:
                line = "O %o %s" % (mode, rel)
            h.update((line + "\n").encode())
    return h.hexdigest()


def parse_alpine_packages(root):
    db = os.path.join(root, "lib/apk/db/installed")
    packages = []
    if not os.path.isfile(db):
        raise SystemExit("missing Alpine package database: %s" % db)
    with open(db, "r", encoding="utf-8", errors="replace") as fh:
        stanza = {}
        for line in fh:
            line = line.rstrip("\n")
            if not line:
                if stanza.get("P") and stanza.get("V"):
                    packages.append({"name": stanza["P"], "version": stanza["V"]})
                stanza = {}
                continue
            if len(line) > 2 and line[1] == ":":
                stanza[line[0]] = line[2:]
        if stanza.get("P") and stanza.get("V"):
            packages.append({"name": stanza["P"], "version": stanza["V"]})
    return sorted(packages, key=lambda p: p["name"])


def parse_debian_packages(root):
    status = os.path.join(root, "var/lib/dpkg/status")
    packages = []
    if not os.path.isfile(status):
        raise SystemExit("missing Debian package database: %s" % status)
    with open(status, "r", encoding="utf-8", errors="replace") as fh:
        stanza = {}
        for line in fh:
            line = line.rstrip("\n")
            if not line:
                if stanza.get("Package") and stanza.get("Version") \
                        and "install ok installed" in stanza.get("Status", ""):
                    packages.append({
                        "name": stanza["Package"],
                        "version": stanza["Version"],
                    })
                stanza = {}
                continue
            if ": " in line:
                key, value = line.split(": ", 1)
                stanza[key] = value
        if stanza.get("Package") and stanza.get("Version") \
                and "install ok installed" in stanza.get("Status", ""):
            packages.append({"name": stanza["Package"], "version": stanza["Version"]})
    return sorted(packages, key=lambda p: p["name"])


def package_db_sha(root, distro):
    rel = "lib/apk/db/installed" if distro == "alpine" else "var/lib/dpkg/status"
    return sha256_file(os.path.join(root, rel))


def parse_repositories(path):
    repos = []
    if not path:
        return repos
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split(None, 1)
            url = parts[0]
            index = parts[1].strip() if len(parts) > 1 else None
            repos.append({"url": url, "indexSha256": index})
    return repos


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    ap.add_argument("--distro", required=True, choices=["alpine", "debian"])
    ap.add_argument("--version", required=True)
    ap.add_argument("--arch", required=True)
    ap.add_argument("--libc", required=True, choices=["musl", "glibc"])
    ap.add_argument("--source-date-epoch", required=True, type=int)
    ap.add_argument("--started-at", required=True)
    ap.add_argument("--duration-seconds", required=True, type=int)
    ap.add_argument("--host", required=True)
    ap.add_argument("--tool", required=True)
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--base-sha256", required=True)
    ap.add_argument("--repository-index", default="")
    ap.add_argument("--locales", default="")
    ap.add_argument("--artifact", action="append", default=[])
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    if args.distro == "alpine":
        packages = parse_alpine_packages(args.root)
    else:
        packages = parse_debian_packages(args.root)

    outputs = []
    for artifact in args.artifact:
        if not os.path.isfile(artifact):
            raise SystemExit("artifact not found: %s" % artifact)
        name = os.path.basename(artifact)
        if name.endswith(".tar.gz"):
            fmt = "tar.gz"
        elif name.endswith(".ext4"):
            fmt = "ext4"
        else:
            fmt = os.path.splitext(name)[1].lstrip(".") or "unknown"
        outputs.append({
            "path": name,
            "format": fmt,
            "sha256": sha256_file(artifact),
            "sizeBytes": os.path.getsize(artifact),
        })

    cert_rel = "etc/ssl/certs/ca-certificates.crt"
    cert_path = os.path.join(args.root, cert_rel)
    if not os.path.isfile(cert_path):
        raise SystemExit("certificate bundle missing: %s" % cert_path)

    locales = [x for x in args.locales.split() if x]
    manifest = {
        "schemaVersion": 1,
        "distro": {
            "name": args.distro,
            "version": args.version,
            "arch": args.arch,
            "libc": args.libc,
        },
        "build": {
            "sourceDateEpoch": args.source_date_epoch,
            "startedAtUtc": args.started_at,
            "durationSeconds": args.duration_seconds,
            "host": args.host,
            "tool": args.tool,
        },
        "inputs": {
            "base": {
                "url": args.base_url,
                "sha256": args.base_sha256,
            },
            "repositories": parse_repositories(args.repository_index),
            "packageSet": packages,
        },
        "provisioning": {
            "mode": "build-time",
            "firstBoot": {
                "packageManagerInvocations": 0,
                "networkRequired": False,
                "init": "/init-zero-install",
                "ready": "/ready.sh",
            },
            "locale": {
                "default": "C.UTF-8",
                "generated": locales,
            },
            "certificates": {
                "bundlePath": "/" + cert_rel,
                "sha256": sha256_file(cert_path),
                "sizeBytes": os.path.getsize(cert_path),
            },
        },
        "artifactContent": {
            "initSha256": sha256_file(os.path.join(args.root, "init-zero-install")),
            "readySha256": sha256_file(os.path.join(args.root, "ready.sh")),
            "packageDatabaseSha256": package_db_sha(args.root, args.distro),
            "treeSha256": tree_digest(args.root),
        },
        "outputs": outputs,
    }

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, sort_keys=True)
        fh.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
