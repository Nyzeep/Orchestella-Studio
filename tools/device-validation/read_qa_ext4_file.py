#!/usr/bin/env python3
"""Read two bounded diagnostics from a stopped QA ext4 image; never replay a journal.

Only the main device owner may invoke the CLI, after verifying current launch/PID
ownership and that the VM has stopped. No mount, raw-image export, credentials,
xattrs, symlink following, network client, or device write is provided. Ext4 can
be tested locally by importing Ext4 with a synthetic fixture's read_at callback.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import struct
import subprocess
import time
import uuid

PACKAGE = "io.github.nyzeep.orchestella"
APPROVED = Path("D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/quality-20261013/device-smoke")
DISTROS = "ubuntu|debian|fedora|arch|opensuse|almalinux|rocky"
QA_ROOT = re.compile(r"files/quality-smoke/(" + DISTROS + r")-([0-9a-f]{8})")
FILES = {"/var/log/orchestella-provision.log.txt": "provisionLog",
         "/var/lib/orchestella/provision/state": "provisionState"}
RAW_BYTES, LIMIT, IO_BLOCK = 8589934592, 32768, 4096


class GateError(Exception):
    """Only fixed, non-sensitive reason codes are passed to this exception."""


def need(ok, code):
    if not ok:
        raise GateError(code)


def uint(data, offset, bits=32):
    return struct.unpack_from({16: "<H", 32: "<I", 64: "<Q"}[bits], data, offset)[0]


def plain_path(path):
    for part in (path, *path.parents):
        need(not part.is_symlink() and not getattr(part, "is_junction", lambda: False)(), "host-symlink-or-junction")


class AdbSource:
    def __init__(self, adb, serial, qa_root):
        need(QA_ROOT.fullmatch(qa_root) is not None, "qa-root-scope")
        need(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", serial) is not None, "serial-syntax")
        executable = Path(adb).absolute()
        plain_path(executable)
        need(executable.is_file(), "adb-path-missing")
        self.base = [str(executable), "-s", serial, "exec-out"]
        self.raw = qa_root + "/root-volume.raw"
        self.deadline = time.monotonic() + 60
        self.cache, self.calls, self.byte_count = {}, 0, 0
        # The exact relative path and every parent must be ordinary app-private
        # files/directories. PID and launch ownership remain the caller's gate.
        checks = [f'[ -d {p} ] && [ ! -L {p} ]' for p in ("files", "files/quality-smoke", qa_root)]
        checks += [f'[ -f {self.raw} ] && [ ! -L {self.raw} ]',
                   f'[ "$(/system/bin/stat -c %s {self.raw})" = "{RAW_BYTES}" ]']
        self.command(["/system/bin/sh", "-c", " && ".join(checks)], 0)

    def command(self, argv, expected):
        left = self.deadline - time.monotonic()
        need(left > 0 and self.calls < 512, "transport-budget")
        self.calls += 1
        try:
            result = subprocess.run(self.base + [shlex.join(["run-as", PACKAGE, *argv])], stdin=subprocess.DEVNULL,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=False,
                                    timeout=left, env=dict(os.environ, MSYS_NO_PATHCONV="1", MSYS2_ARG_CONV_EXCL="*"))
        except subprocess.TimeoutExpired:
            raise GateError("adb-read-timeout") from None
        except OSError:
            raise GateError("adb-execution-failed") from None
        # Never echo binary stdout, stderr, command arguments, or a raw path.
        need(result.returncode == 0, "adb-read-or-scope-failed")
        need(len(result.stdout) == expected, "binary-transport-length-mismatch")
        self.byte_count += len(result.stdout)
        need(self.byte_count <= 8 * 1024 * 1024, "transport-byte-budget")
        return result.stdout

    def read(self, offset, length, fresh=False):
        need(0 <= offset <= RAW_BYTES and 0 <= length <= 1024 * 1024 and offset + length <= RAW_BYTES, "raw-read-bounds")
        if not length:
            return b""
        first, end = offset // IO_BLOCK, (offset + length + IO_BLOCK - 1) // IO_BLOCK
        blocks = []
        while first < end:
            if not fresh and first in self.cache:
                blocks.append(self.cache[first])
                first += 1
                continue
            count = min(16, end - first)
            # Android exec-out merges remote stderr into stdout; quiet dd keeps
            # the transport strictly binary. The owner verified toybox support.
            data = self.command(["/system/bin/dd", "if=" + self.raw, "bs=4096", "skip=" + str(first), "count=" + str(count), "status=none"], count * IO_BLOCK)
            for n in range(count):
                block = data[n * IO_BLOCK:(n + 1) * IO_BLOCK]
                if first + n in self.cache:
                    need(hashlib.sha256(block).digest() == hashlib.sha256(self.cache[first + n]).digest(), "metadata-changed-during-read")
                self.cache[first + n] = block
                blocks.append(block)
            first += count
        start = offset % IO_BLOCK
        return b"".join(blocks)[start:start + length]


class Ext4:
    def __init__(self, read_at):
        self.read_at = read_at
        s = self.read(1024, 1024)
        need(uint(s, 56, 16) == 0xEF53, "not-ext4-superblock")
        log = uint(s, 24)
        need(log <= 2, "unsupported-ext4-block-size")
        self.block_size = 1024 << log
        self.incompat = uint(s, 96)
        need(self.incompat & 0x40 and self.incompat & 2, "extents-and-filetype-required")
        # META_BG requires different descriptor placement; bigalloc, encryption
        # and casefold need interpretations this small diagnostics reader lacks.
        need(not self.incompat & ~0xE7C6 and not uint(s, 100) & 0x200, "unsupported-ext4-feature")
        self.blocks = uint(s, 4) | (uint(s, 336) << 32 if self.incompat & 0x80 else 0)
        self.first = uint(s, 20)
        self.per_group, self.inodes_per_group = uint(s, 32), uint(s, 40)
        self.inodes, self.inode_size = uint(s, 0), uint(s, 88, 16)
        self.desc_size = uint(s, 254, 16) if self.incompat & 0x80 else 32
        need(self.first == (1 if self.block_size == 1024 else 0) and 1 < self.blocks <= RAW_BYTES // self.block_size, "ext4-block-geometry")
        need(0 < self.per_group <= self.block_size * 8 and 0 < self.inodes_per_group <= self.block_size * 8, "ext4-group-geometry")
        need(self.inode_size in (128, 256, 512, 1024, 2048, 4096) and self.inode_size <= self.block_size, "ext4-inode-size")
        need(self.desc_size >= (64 if self.incompat & 0x80 else 32) and self.desc_size <= self.block_size
             and self.desc_size & (self.desc_size - 1) == 0, "ext4-descriptor-size")
        self.groups = (self.blocks - self.first + self.per_group - 1) // self.per_group
        need(2 <= self.inodes <= self.groups * self.inodes_per_group, "ext4-inode-count")
        self.status = "needs-recovery-readonly-no-replay" if self.incompat & 4 or not uint(s, 58, 16) & 1 else "clean-readonly"
        self.super_hash = hashlib.sha256(s).digest()
        self.inode_cache = {}

    def read(self, offset, length):
        data = self.read_at(offset, length)
        need(isinstance(data, bytes) and len(data) == length, "filesystem-read-length")
        return data

    def block(self, number):
        need(0 < number < self.blocks, "ext4-block-pointer")
        return self.read(number * self.block_size, self.block_size)

    def inode(self, number):
        need(1 <= number <= self.inodes, "ext4-inode-number")
        if number not in self.inode_cache:
            group, index = divmod(number - 1, self.inodes_per_group)
            desc = self.read((self.first + 1) * self.block_size + group * self.desc_size, self.desc_size)
            table = uint(desc, 8) | (uint(desc, 40) << 32 if self.incompat & 0x80 else 0)
            offset = table * self.block_size + index * self.inode_size
            need(0 < table < self.blocks and offset + self.inode_size <= self.blocks * self.block_size, "ext4-inode-table")
            data = self.read(offset, self.inode_size)
            self.inode_cache[number] = (data, offset)
        return self.inode_cache[number][0]

    def size(self, inode):
        return uint(inode, 4) | uint(inode, 108) << 32

    def extents(self, inode, first, end):
        flags = uint(inode, 32)
        need(not flags & 0x10000000, "inline-data-unsupported")
        need(not flags & (0x800 | 4), "encrypted-or-compressed-inode-unsupported")
        need(flags & 0x80000, "legacy-indirect-inode-unsupported")
        seen, result = set(), []

        def visit(node, expected_depth=None, lower=0, upper=1 << 32):
            need(len(node) >= 12, "extent-header-length")
            magic, entries, maximum, depth = struct.unpack_from("<HHHH", node)
            need(magic == 0xF30A and depth <= 5 and (expected_depth is None or depth == expected_depth), "extent-header")
            need(entries <= maximum <= (len(node) - 12) // 12, "extent-entry-bound")
            previous = lower - 1
            for n in range(entries):
                at = 12 + n * 12
                logical = uint(node, at)
                need(previous < logical < upper and logical >= lower, "extent-order")
                previous = logical
                if depth:
                    next_key = uint(node, at + 12) if n + 1 < entries else upper
                    need(logical < next_key <= upper, "extent-index-order")
                    child = uint(node, at + 4) | uint(node, at + 8, 16) << 32
                    need(0 < child < self.blocks, "extent-index-pointer")
                    if logical < end and first < next_key:
                        need(child not in seen and len(seen) < 512, "extent-tree-cycle-or-budget")
                        seen.add(child)
                        visit(self.block(child), depth - 1, logical, next_key)
                else:
                    encoded = uint(node, at + 4, 16)
                    unwritten = encoded > 32768
                    length = encoded - 32768 if unwritten else encoded
                    physical = uint(node, at + 8) | uint(node, at + 6, 16) << 32
                    need(length > 0 and logical + length <= upper and 0 < physical < self.blocks
                         and physical + length <= self.blocks, "extent-leaf-bounds")
                    if n + 1 < entries:
                        need(logical + length <= uint(node, at + 12), "extent-leaf-overlap")
                    if logical < end and first < logical + length:
                        result.append((logical, length, physical, unwritten))
        visit(inode[40:100])
        return result

    def contents(self, inode, offset, length):
        need(0 <= offset <= self.size(inode) and 0 <= length <= 1024 * 1024
             and offset + length <= self.size(inode), "file-window-bounds")
        if not length:
            # Validate even empty files instead of silently accepting inline data.
            self.extents(inode, 0, 0)
            return b""
        size = self.block_size
        first, end = offset // size, (offset + length + size - 1) // size
        output = bytearray(length)  # Missing and unwritten extents stay zero.
        previous = first
        for logical, count, physical, unwritten in self.extents(inode, first, end):
            lo, hi = max(first, logical), min(end, logical + count)
            need(lo >= previous, "extent-tree-overlap")
            previous = hi
            if unwritten:
                continue
            begin, finish = max(offset, lo * size), min(offset + length, hi * size)
            data = self.read(physical * size + begin - logical * size, finish - begin)
            output[begin - offset:finish - offset] = data
        return bytes(output)

    def lookup(self, directory, name):
        need(uint(directory, 0, 16) & 0xF000 == 0x4000, "path-component-not-directory")
        size = self.size(directory)
        need(0 < size <= 1024 * 1024 and size % self.block_size == 0, "directory-size-budget")
        wanted = name.encode("ascii")
        for offset in range(0, size, self.block_size):
            block = self.contents(directory, offset, self.block_size)
            at = 0
            while at < len(block):
                need(at + 8 <= len(block), "directory-entry-header")
                number, record, namelen = struct.unpack_from("<IHB", block, at)
                need(record >= 8 and record % 4 == 0 and at + record <= len(block)
                     and namelen <= record - 8, "directory-entry-bounds")
                if number and block[at + 8:at + 8 + namelen] == wanted:
                    return self.inode(number)
                at += record
        raise GateError("file-not-found")

    def diagnostic(self, path, limit=LIMIT):
        need(path in FILES and isinstance(limit, int) and 1 <= limit <= LIMIT, "diagnostic-allowlist-or-limit")
        inode = self.inode(2)
        for name in path.strip("/").split("/"):
            inode = self.lookup(inode, name)
            need(uint(inode, 0, 16) & 0xF000 != 0xA000, "symlink-component-refused")
        need(uint(inode, 0, 16) & 0xF000 == 0x8000, "diagnostic-not-regular-file")
        size = self.size(inode)
        length = min(size, limit)
        text, sensitive = sanitize(self.contents(inode, size - length, length))
        return {"status": "sensitive-content-owner-review-required" if sensitive else "ok",
                "fileSize": size, "dataBytes": length, "truncated": length < size, "limitedText": text}

    def verify_metadata(self):
        need(hashlib.sha256(self.read_at(1024, 1024)).digest() == self.super_hash, "superblock-changed-during-read")
        for data, offset in self.inode_cache.values():
            need(hashlib.sha256(self.read_at(offset, len(data))).digest() == hashlib.sha256(data).digest(), "inode-changed-during-read")


def sanitize(data):
    text = data.decode("utf-8", "replace").replace("\x00", "")
    text = re.sub(r"\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b\[[0-?]*[ -/]*[@-~]", "", text)
    text = re.sub(r"[\x01-\x08\x0b-\x1f\x7f]", "", text)
    text = re.sub(r"(?im)(\b(?:[\w.-]*(?:token|password|passwd|passphrase|secret|credential|private[-_]?key|api[-_]?key)|key)\b[\"']?\s*[=:]\s*).*$", r"\1<redacted>", text)
    text = re.sub(r"(?im)^.*(?:\bchpasswd\b|\bAuthorization\s*:|--(?:password|token|secret|key)\b).*$", "<redacted-sensitive-command>", text)
    sensitive = bool(re.search(r"(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])|-----BEGIN [^-\n]*PRIVATE KEY-----", text))
    return ("" if sensitive else text), sensitive


def save(output, distro, result):
    stem = "qa-ext4-diagnostics-" + distro + "-" + uuid.uuid4().hex[:12]
    serialised = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    readable = "filesystemStatus=" + result["filesystemStatus"] + "\n"
    for name, item in result["files"].items():
        readable += "\n" + name + " status=" + item["status"] + "\n" + item.get("limitedText", "") + "\n"
    # Parent is the caller-approved private quality root. Exclusive create never
    # overwrites an existing evidence file; POSIX permissions are owner-only.
    for suffix, content in ((".json", serialised), (".txt", readable)):
        target = output / (stem + suffix)
        fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(content)
    return stem


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise GateError("invalid-cli-arguments")  # Do not echo arbitrary input.


def main(argv=None):
    parser = Parser(description=__doc__, allow_abbrev=False)
    for flag in ("adb", "serial", "qa-root", "output"):
        parser.add_argument("--" + flag, required=True)
    parser.add_argument("--package", choices=[PACKAGE], default=PACKAGE)
    parser.add_argument("--file", choices=list(FILES), action="append")
    parser.add_argument("--limit", type=int, default=LIMIT, help="Tail bytes per file; maximum and default 32768")
    args = parser.parse_args(argv)
    match = QA_ROOT.fullmatch(args.qa_root)
    need(match is not None and 1 <= args.limit <= LIMIT, "qa-root-or-limit-scope")
    output = Path(args.output).absolute()
    plain_path(output)
    plain_path(APPROVED)
    need(output.is_dir() and APPROVED.is_dir() and output.resolve(strict=True).is_relative_to(APPROVED.resolve(strict=True)), "output-outside-existing-quality-root")
    selected = args.file or list(FILES)
    need(len(selected) == len(set(selected)), "duplicate-file-selection")
    source = AdbSource(args.adb, args.serial, args.qa_root)
    fs = Ext4(source.read)
    result = {"filesystemStatus": fs.status, "files": {}}
    for path in selected:
        try:
            result["files"][FILES[path]] = fs.diagnostic(path, args.limit)
        except GateError as error:
            need(str(error) in {"file-not-found", "path-component-not-directory", "symlink-component-refused",
                               "diagnostic-not-regular-file", "inline-data-unsupported",
                               "legacy-indirect-inode-unsupported", "encrypted-or-compressed-inode-unsupported"}, str(error))
            result["files"][FILES[path]] = {"status": str(error), "limitedText": ""}
    # Fresh dd calls bypass the cache, and compare SHA-256 before publishing.
    fs.read_at = lambda offset, length: source.read(offset, length, fresh=True)
    fs.verify_metadata()
    stem = save(output, match.group(1), result)
    print(json.dumps({"filesystemStatus": fs.status, "artifact": stem,
                      "files": {name: {k: v for k, v in item.items() if k != "limitedText"} for name, item in result["files"].items()}}, ensure_ascii=False))
    return 0 if all(item["status"] == "ok" for item in result["files"].values()) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(json.dumps({"filesystemStatus": "refused", "reason": str(error) if isinstance(error, GateError) else type(error).__name__}))
        raise SystemExit(1)
