#!/usr/bin/env python3
"""Verify exact guest inputs in a local Phase 5 APK without installing it."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def agent_from_cpio(bundle):
    raw = gzip.decompress(bundle)
    offset = 0
    found = []
    while offset + 110 <= len(raw):
        if raw[offset:offset + 6] != b"070701":
            raise ValueError("Invalid newc header")
        fields = [int(raw[offset + 6 + index * 8:offset + 14 + index * 8], 16) for index in range(13)]
        size, length = fields[6], fields[11]
        start = offset + 110
        if length < 1 or start + length > len(raw):
            raise ValueError("Invalid newc name")
        name = raw[start:start + length - 1].decode("utf-8")
        start = (start + length + 3) & ~3
        end = start + size
        if end > len(raw):
            raise ValueError("Truncated newc payload")
        if name == "opt/droidlayer/guest-agent":
            found.append(raw[start:end])
        offset = (end + 3) & ~3
        if name == "TRAILER!!!":
            break
    if len(found) != 1:
        raise ValueError("Guest agent must have exactly one archive entry")
    return found[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--agent", required=True, type=Path)
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    source = args.source_root
    with zipfile.ZipFile(args.apk) as archive:
        names = set(archive.namelist())
        manifest = archive.read("assets/runtime-artifacts-v1.sha256").decode("ascii")
        checked = []
        for line in manifest.splitlines():
            if not line.strip() or line.startswith("#"):
                continue
            expected, file_name = line.split()
            entry = file_name if file_name == "initramfs.cpio.gz" or file_name.startswith("lib/") else "assets/" + file_name
            if entry not in names:
                raise ValueError("Required runtime asset missing: " + file_name)
            actual = sha(archive.read(entry))
            if actual != expected:
                raise ValueError("Runtime manifest mismatch: " + file_name)
            checked.append({"name": file_name, "sha256": actual})
        guest_init = archive.read("guest_init")
        if guest_init != (source / "guest-assets/initramfs/init").read_bytes():
            raise ValueError("Packaged guest init is not the canonical template")
        agent = agent_from_cpio(archive.read("initramfs.cpio.gz"))
        if agent != args.agent.read_bytes():
            raise ValueError("APK contains a stale guest agent")
        if agent[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", agent, 18)[0] != 183:
            raise ValueError("Packaged guest agent is not AArch64")
        for relative in ("features/profilectl", "features/profilectl.py", "features/inputs.json", "features/bootstrap-python.sh",
                         "features/feature-session.py", "features/rootless-probe.py", "display/display-relay.py"):
            text = (source / "guest-assets" / relative).read_bytes().rstrip(b"\n")
            if text not in guest_init:
                raise ValueError("Optional feature payload not embedded: " + relative)
        if "assets/NOTICE.PHASE5" not in names or "lib/arm64-v8a/libdroidlayer_qemu.so" not in names:
            raise ValueError("Runtime or Phase 5 NOTICE is missing")
        record = {"apk": str(args.apk), "apkBytes": args.apk.stat().st_size, "apkSha256": sha(args.apk.read_bytes()),
                  "agentSha256": sha(agent), "guestInitSha256": sha(guest_init), "runtimeAssets": checked,
                  "optionalPayloadsVerified": 7, "installedOnDevice": False,
                  "runtimeValidation": "NOT_RUN; this gate only checks local build contents"}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    preserved = args.output.parent / "preserved"
    preserved.mkdir(exist_ok=True)
    (preserved / args.output.name).write_bytes(args.output.read_bytes())
    print(json.dumps({"apkBytes": record["apkBytes"], "apkSha256": record["apkSha256"], "verified": True}))


if __name__ == "__main__":
    main()
