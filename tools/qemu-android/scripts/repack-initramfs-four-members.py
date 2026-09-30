#!/usr/bin/env python3
"""Ticket 26 four-member deterministic initramfs transformation contract.

Replaces the four Ticket-26-owned members inside a Ticket-25 producer base
initramfs (gzip single-member newc cpio), deterministically:

    init                                        mode 0755
    opt/droidlayer/guest-agent                  mode 0755
    opt/droidlayer/openrc/droidlayer-guest-agent        mode 0755
    opt/droidlayer/openrc/droidlayer-guest-agent.conf   mode 0644

Contract (fail closed, RC 65):
  - exactly these four --member paths, each with a matching --member-sha256
    pin; fewer, more, duplicate, or unknown paths are rejected;
  - every member file must be a regular file whose actual SHA-256 equals its
    pin (a wrong hash is a rejection, never a substitution);
  - the base must be a single-member gzip stream (the embedded-input segment
    is concatenated AFTER this contract, so trailing data is rejected) whose
    newc archive contains all four member paths with the contract modes;
  - every non-member entry, its header fields and order are preserved
    byte-for-byte; member bodies are substituted and only c_filesize changes;
  - output gzip: level 9, mtime 0, OS byte 3 -> same inputs, same bytes.

This is the transformation-contract tool for synthetic/fixture verification
and for an authorized producer pipeline. Running it does not by itself change
any shipped APK identity: the resulting initramfs only enters a build through
the authorized payload path, and a new initramfs is a new component identity
under policy section 7 (lock, BUILD-INPUTS, GPL records, manifest and the
affected section 5 gates must then be re-synchronized atomically).
"""
import argparse
import gzip
import hashlib
import io
import os
import sys
import zlib

MAGIC = b'070701'
HEADER = 110
TRAILER = 'TRAILER!!!'
CONTRACT = (
    ('init', 0o755),
    ('opt/droidlayer/guest-agent', 0o755),
    ('opt/droidlayer/openrc/droidlayer-guest-agent', 0o755),
    ('opt/droidlayer/openrc/droidlayer-guest-agent.conf', 0o644),
)
CONTRACT_MODES = dict(CONTRACT)


class Rejected(ValueError):
    pass


def sha256_file(path):
    if os.path.islink(path) or not os.path.isfile(path):
        raise Rejected('missing-or-indirect-member:' + path)
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def parse_member_specs(args):
    members, pins = {}, {}
    for item in args.member:
        path, sep, value = item.partition('=')
        if not sep or not value or path in members:
            raise Rejected('invalid-or-duplicate-member-spec:' + item)
        members[path] = value
    for item in args.member_sha256:
        path, sep, value = item.partition('=')
        if (not sep or len(value) != 64
                or any(c not in '0123456789abcdef' for c in value)):
            raise Rejected('invalid-member-sha256-spec:' + item)
        if path in pins:
            raise Rejected('duplicate-member-sha256-spec:' + item)
        pins[path] = value
    expected = sorted(path for path, _ in CONTRACT)
    if sorted(members) != expected:
        raise Rejected('member-set-must-be-exactly-four-contract-members')
    if sorted(pins) != expected:
        raise Rejected('member-sha256-set-must-be-exactly-four-contract-members')
    return members, pins


def inflate_single_member(raw):
    probe = zlib.decompressobj(16 + zlib.MAX_WBITS)
    try:
        data = probe.decompress(raw)
    except zlib.error:
        raise Rejected('base-not-gzip')
    if not probe.eof:
        raise Rejected('base-gzip-truncated')
    if probe.unused_data:
        raise Rejected('base-has-trailing-data:not-a-single-gzip-member')
    return data


def iterate_newc(raw):
    pos = 0
    while True:
        if pos + HEADER > len(raw):
            raise Rejected('cpio-truncated-header')
        if raw[pos:pos + 6] != MAGIC:
            raise Rejected('cpio-non-newc-entry-at-%d' % pos)
        namesize = int(raw[pos + 94:pos + 102], 16)
        filesize = int(raw[pos + 54:pos + 62], 16)
        mode = int(raw[pos + 14:pos + 22], 16)
        name = raw[pos + HEADER:pos + HEADER + namesize - 1].decode('utf-8')
        data_start = ((pos + HEADER + namesize + 3) // 4) * 4
        body = raw[data_start:data_start + filesize]
        yield name, mode, raw[pos:pos + HEADER], raw[pos + HEADER:data_start], body
        if name == TRAILER:
            return
        pos = ((data_start + filesize + 3) // 4) * 4


def repack(base_path, members, pins):
    if os.path.islink(base_path) or not os.path.isfile(base_path):
        raise Rejected('missing-or-indirect-base:' + base_path)
    with open(base_path, 'rb') as f:
        payload = inflate_single_member(f.read())

    bodies = {}
    for path, member_file in sorted(members.items()):
        actual = sha256_file(member_file)
        if actual != pins[path]:
            raise Rejected('member-sha256-mismatch:' + path)
        with open(member_file, 'rb') as f:
            bodies[path] = f.read()

    seen = set()
    replaced = set()
    out = bytearray()
    for name, mode, header, namebytes, body in iterate_newc(payload):
        if name in seen:
            raise Rejected('cpio-duplicate-entry:' + name)
        seen.add(name)
        if name in bodies:
            if (mode & 0o7777) != CONTRACT_MODES[name]:
                raise Rejected('base-member-mode-drift:%s:0o%o' % (name, mode))
            if mode & 0o170000 != 0o100000:
                raise Rejected('base-member-not-regular:%s:0o%o' % (name, mode))
            new_body = bodies[name]
            header = bytearray(header)
            header[54:62] = ('%08x' % len(new_body)).encode('ascii')
            body = new_body
            replaced.add(name)
        out += header
        out += namebytes
        out += body
        while len(out) % 4:
            out += b'\x00'
        if name == TRAILER:
            break
    missing = sorted(set(bodies) - replaced)
    if missing:
        raise Rejected('member-not-in-base:' + ','.join(missing))
    if TRAILER not in seen:
        raise Rejected('cpio-trailer-missing')
    while len(out) % 512:
        out += b'\x00'

    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode='wb', compresslevel=9, mtime=0) as g:
        g.write(bytes(out))
    packed = bytearray(buf.getvalue())
    packed[9] = 3
    return bytes(out), bytes(packed)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description='Deterministically replace the four Ticket-26 members in a '
                    'Ticket-25 base initramfs (fail closed, RC 65).')
    parser.add_argument('--base', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--member', action='append', default=[],
                        metavar='ARCHIVE_PATH=FILE')
    parser.add_argument('--member-sha256', action='append', default=[],
                        metavar='ARCHIVE_PATH=HEX')
    args = parser.parse_args(argv)
    try:
        members, pins = parse_member_specs(args)
        raw_out, packed = repack(args.base, members, pins)
    except (Rejected, OSError, UnicodeError) as error:
        print('FOUR_MEMBER_REPACK_REJECTED ' + str(error), file=sys.stderr)
        return 65
    with open(args.output, 'wb') as f:
        f.write(packed)
    print('FOUR_MEMBER_REPACK_OK members=4 raw_bytes=%d gz_bytes=%d output=%s'
          % (len(raw_out), len(packed), args.output.replace('\\', '/')))
    return 0


if __name__ == '__main__':
    sys.exit(main())
