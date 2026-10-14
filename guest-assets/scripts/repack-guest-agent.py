#!/usr/bin/env python3
"""Replace one verified static AArch64 guest agent in the existing runtime bundle."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import struct


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require_static_aarch64(data):
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("guest agent must be a 64-bit little-endian ELF")
    if struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError("guest agent must target AArch64")
    header_offset = struct.unpack_from("<Q", data, 32)[0]
    header_size, header_count = struct.unpack_from("<HH", data, 54)
    if header_size < 56 or header_offset + header_size * header_count > len(data):
        raise ValueError("invalid ELF program headers")
    for index in range(header_count):
        kind = struct.unpack_from("<I", data, header_offset + index * header_size)[0]
        if kind in (2, 3):
            raise ValueError("guest agent must have no dynamic loader or dynamic segment")


def replace_agent(archive, agent):
    raw = gzip.decompress(archive)
    offset = 0
    output = bytearray()
    replacements = 0
    preserved = []
    while offset + 110 <= len(raw):
        header = raw[offset:offset + 110]
        if header[:6] != b"070701":
            raise ValueError("expected a newc archive without CRC entries")
        fields = [int(header[6 + index * 8:14 + index * 8], 16) for index in range(13)]
        size, name_size = fields[6], fields[11]
        name_start = offset + 110
        name_end = name_start + name_size
        if name_size < 1 or name_end > len(raw) or raw[name_end - 1] != 0:
            raise ValueError("invalid cpio name")
        name_bytes = raw[name_start:name_end]
        name = name_bytes[:-1].decode("utf-8")
        payload_start = (name_end + 3) & ~3
        payload_end = payload_start + size
        if payload_end > len(raw):
            raise ValueError("truncated cpio entry")
        payload = raw[payload_start:payload_end]
        if name == "opt/droidlayer/guest-agent":
            if fields[1] & 0o170000 != 0o100000 or fields[4] != 1:
                raise ValueError("guest agent entry must be a regular file without hard links")
            replacements += 1
            fields[6] = len(agent)
            fields[5] = 0
            payload = agent
        elif name != "TRAILER!!!":
            preserved.append({"path": name, "sha256": digest(payload), "mode": fields[1]})
        output.extend(b"070701" + b"".join(f"{value:08x}".encode() for value in fields))
        output.extend(name_bytes)
        output.extend(b"\0" * (-len(output) % 4))
        output.extend(payload)
        output.extend(b"\0" * (-len(output) % 4))
        offset = (payload_end + 3) & ~3
        if name == "TRAILER!!!":
            if any(raw[offset:]):
                raise ValueError("unexpected non-padding data after cpio trailer")
            break
    else:
        raise ValueError("missing cpio trailer")
    if replacements != 1:
        raise ValueError("runtime bundle must contain exactly one guest agent")
    output.extend(b"\0" * (-len(output) % 512))
    return gzip.compress(bytes(output), compresslevel=9, mtime=0), preserved


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--agent", required=True, type=Path)
    parser.add_argument("--agent-sha256", required=True)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--source-sha256", required=True)
    parser.add_argument("--evidence", required=True, type=Path)
    args = parser.parse_args()
    if not args.evidence.is_dir():
        raise ValueError("evidence directory must already exist in an approved resource root")
    archive, agent, source = args.bundle.read_bytes(), args.agent.read_bytes(), args.source.read_bytes()
    if digest(agent) != args.agent_sha256 or digest(source) != args.source_sha256:
        raise ValueError("compiled agent or source does not match the pinned build input")
    require_static_aarch64(agent)
    manifest = args.manifest.read_text(encoding="utf-8")
    lines = manifest.splitlines(keepends=True)
    matching = [index for index, line in enumerate(lines) if line.split()[1:] == ["initramfs.cpio.gz"]]
    if len(matching) != 1 or lines[matching[0]].split()[0] != digest(archive):
        raise ValueError("existing bundle must match its runtime manifest")
    replacement, preserved = replace_agent(archive, agent)
    # Read the result again before publishing: all other payloads and modes stay identical.
    _, repeated = replace_agent(replacement, agent)
    if repeated != preserved:
        raise ValueError("a non-agent runtime entry changed during repack")
    original = args.evidence / "initramfs-before-agent-update.cpio.gz"
    if original.exists() and original.read_bytes() != archive:
        raise ValueError("evidence backup exists for another runtime revision")
    original.write_bytes(archive)
    staged = args.bundle.with_name(args.bundle.name + ".partial")
    staged.write_bytes(replacement)
    staged.replace(args.bundle)
    ending = "\r\n" if lines[matching[0]].endswith("\r\n") else "\n"
    lines[matching[0]] = f"{digest(replacement)}  initramfs.cpio.gz{ending}"
    args.manifest.write_text("".join(lines), encoding="utf-8", newline="")
    evidence = {
        "sourceSha256": digest(source),
        "agentSha256": digest(agent),
        "agentBytes": len(agent),
        "previousBundleSha256": digest(archive),
        "bundleSha256": digest(replacement),
        "preservedEntryCount": len(preserved),
        "elf": "AArch64 static, no PT_INTERP or PT_DYNAMIC",
    }
    (args.evidence / "guest-agent-runtime-update.json").write_text(
        json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence))


if __name__ == "__main__":
    main()
