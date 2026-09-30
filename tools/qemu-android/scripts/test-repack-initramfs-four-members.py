#!/usr/bin/env python3
"""Synthetic-fixture tests for repack-initramfs-four-members.py.

Builds a synthetic Ticket-25-shaped base initramfs (gzip single-member newc
cpio containing the four contract member paths plus decoy entries), then
drives the deterministic four-member transformation with positive paths and
fail-closed negatives. Every negative must exit RC 65 with the expected
rejection reason. All fixtures are synthetic; the base is not any Ticket 25
asset and the output is not a real payload.
Usage: python test-repack-initramfs-four-members.py <work-root>
"""
import gzip
import hashlib
import io
import importlib.util
import json
import os
import struct
import subprocess
import sys

# importing the module under test must not drop __pycache__ into the tree
sys.dont_write_bytecode = True

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.join(HERE, 'repack-initramfs-four-members.py')
spec = importlib.util.spec_from_file_location('repack_four', MODULE)
repack_four = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repack_four)

MEMBERS = [
    ('init', 0o100755, b'SYNTHETIC-OLD-INIT'),
    ('opt/droidlayer/guest-agent', 0o100755, b'SYNTHETIC-OLD-AGENT'),
    ('opt/droidlayer/openrc/droidlayer-guest-agent', 0o100755, b'SYNTHETIC-OLD-SVC'),
    ('opt/droidlayer/openrc/droidlayer-guest-agent.conf', 0o100644, b'SYNTHETIC-OLD-CONF'),
]
NEW_BODIES = {
    'init': b'SYNTHETIC-NEW-INIT-BYTES',
    'opt/droidlayer/guest-agent': b'SYNTHETIC-NEW-AGENT-BYTES',
    'opt/droidlayer/openrc/droidlayer-guest-agent': b'SYNTHETIC-NEW-SVC-BYTES',
    'opt/droidlayer/openrc/droidlayer-guest-agent.conf': b'SYNTHETIC-NEW-CONF-BYTES',
}

failures = []
counts = {'positive': 0, 'negative': 0}
CASE_LOG = []
WORK_ROOT = None


def check(label, ok, detail=''):
    if ok:
        counts['negative' if label.startswith('neg:') else 'positive'] += 1
        print('PASS %s' % label)
    else:
        failures.append(label)
        print('FAIL %s :: %s' % (label, detail))


def _hash_inputs(args):
    inputs = {}
    i = 0
    while i < len(args):
        a = args[i]
        if a == '--base' and i + 1 < len(args):
            path = args[i + 1]
        elif a.startswith('--member='):
            path = a.partition('=')[2].partition('=')[2]
        else:
            path = None
        if path:
            try:
                with open(path, 'rb') as f:
                    inputs[path] = hashlib.sha256(f.read()).hexdigest()
            except OSError:
                inputs[path] = 'missing'
        i += 1
    return inputs


def run(args):
    p = subprocess.run([sys.executable, MODULE] + args, capture_output=True)
    rec = {'argv': args, 'rc': p.returncode,
           'stdout': p.stdout.decode('utf-8', 'replace'),
           'stderr': p.stderr.decode('utf-8', 'replace'),
           'member_file_sha256': _hash_inputs(args)}
    CASE_LOG.append(rec)
    return rec['rc'], rec['stdout'], rec['stderr']


def entry(name, mode, body, ino=1):
    name_bytes = name.encode('utf-8') + b'\x00'
    fields = [ino, mode, 0, 0, 0, 1, len(body), 0, 0, 0, 0, len(name_bytes), 0]
    header = b'070701' + b''.join(b'%08x' % f for f in fields)
    out = header + name_bytes
    while len(out) % 4:
        out += b'\x00'
    out += body
    while len(out) % 4:
        out += b'\x00'
    return out


def build_cpio(members=MEMBERS, extra=None, trailer=True):
    out = bytearray()
    ino = 1
    for name, mode, body in members:
        out += entry(name, mode, body, ino)
        ino += 1
    for name, mode, body in (extra or []):
        out += entry(name, mode, body, ino)
        ino += 1
    if trailer:
        out += entry('TRAILER!!!', 0, b'')
    while len(out) % 512:
        out += b'\x00'
    return bytes(out)


# Member modes model REAL cpio archives (permission bits OR S_IFREG type
# bits), so every positive below exercises the real-form baseline.
REGULAR = 0o100000


def write_base(path, **kwargs):
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode='wb', compresslevel=9, mtime=0) as g:
        g.write(build_cpio(**kwargs))
    packed = bytearray(buf.getvalue())
    packed[9] = 3
    with open(path, 'wb') as f:
        f.write(bytes(packed))
    return path


def write_members(work):
    files, pins = {}, {}
    for name, _, _ in MEMBERS:
        flat = name.replace('/', '_')
        path = os.path.join(work, 'member-%s' % flat)
        with open(path, 'wb') as f:
            f.write(NEW_BODIES[name])
        files[name] = path
        pins[name] = hashlib.sha256(NEW_BODIES[name]).hexdigest()
    return files, pins


def member_args(files, pins, only=None, override_pins=None):
    args = []
    for name in (only or sorted(files)):
        args.append('--member=%s=%s' % (name, files[name]))
    for name in (only or sorted(files)):
        value = (override_pins or pins)[name]
        args.append('--member-sha256=%s=%s' % (name, value))
    return args


def parse_entries(raw):
    return {name: (mode, body) for name, mode, _, _, body
            in repack_four.iterate_newc(raw)}


def main():
    global WORK_ROOT
    work = os.path.abspath(sys.argv[1])
    WORK_ROOT = work
    os.makedirs(work, exist_ok=True)
    base = write_base(os.path.join(work, 'base.cpio.gz'),
                      extra=[('bin/busybox', 0o100755, b'BUSYBOX')])
    files, pins = write_members(work)
    out1 = os.path.join(work, 'out1.cpio.gz')
    out2 = os.path.join(work, 'out2.cpio.gz')

    print('== POSITIVE: deterministic four-member transformation ==')
    rc, out, err = run(['--base', base, '--output', out1]
                       + member_args(files, pins))
    check('repack-positive', rc == 0 and 'FOUR_MEMBER_REPACK_OK' in out,
          err or out)
    if rc != 0:
        return finish()
    rc, out, err = run(['--base', base, '--output', out2]
                       + member_args(files, pins))
    b1 = open(out1, 'rb').read()
    b2 = open(out2, 'rb').read()
    check('deterministic', rc == 0 and b1 == b2, 'outputs differ')

    probe = __import__('zlib').decompressobj(16 + 15)
    raw = probe.decompress(b1)
    check('single-gzip-member', probe.eof and not probe.unused_data,
          'eof=%s unused=%r' % (probe.eof, probe.unused_data[:16]))
    entries = parse_entries(raw)
    check('members-replaced',
          entries['init'][1] == NEW_BODIES['init']
          and entries['opt/droidlayer/guest-agent'][1] == NEW_BODIES['opt/droidlayer/guest-agent']
          and entries['opt/droidlayer/openrc/droidlayer-guest-agent'][1]
          == NEW_BODIES['opt/droidlayer/openrc/droidlayer-guest-agent']
          and entries['opt/droidlayer/openrc/droidlayer-guest-agent.conf'][1]
          == NEW_BODIES['opt/droidlayer/openrc/droidlayer-guest-agent.conf'],
          'member bodies not substituted')
    check('non-member-preserved',
          entries['bin/busybox'] == (0o100755, b'BUSYBOX')
          and entries['init'][0] == 0o100755
          and entries['opt/droidlayer/openrc/droidlayer-guest-agent.conf'][0] == 0o100644,
          'decoy entry or mode drift')
    check('trailer-present', 'TRAILER!!!' in entries, 'trailer missing')

    print('== POSITIVE: real-form baseline (type bits preserved) ==')
    check('real-form-baseline',
          all(entries[name][0] == (REGULAR | perm)
              for name, perm in repack_four.CONTRACT_MODES.items())
          and (entries['bin/busybox'][0] & 0o170000) == REGULAR,
          'regular-file type bits not preserved on members/decoy')

    print('== NEGATIVES: fail-closed contract cases ==')
    def negative(label, base_path=None, extra_args=None, expect=None,
                 members_override=None, pins_override=None):
        outn = os.path.join(work, 'neg-out-%s.cpio.gz' % label)
        args = ['--base', base_path or base, '--output', outn]
        args += member_args(files, pins_override or pins,
                            only=members_override)
        args += (extra_args or [])
        rc, out, err = run(args)
        ok = rc == 65 and expect in err
        check('neg:%s' % label, ok, 'rc=%d stderr=%r' % (rc, err.strip()))

    negative('member-file-missing', members_override=[
        'opt/droidlayer/guest-agent',
        'opt/droidlayer/openrc/droidlayer-guest-agent',
        'opt/droidlayer/openrc/droidlayer-guest-agent.conf'],
        extra_args=['--member=init=%s' % os.path.join(work, 'no-such-init'),
                    '--member-sha256=init=%s' % ('a' * 64)],
        expect='missing-or-indirect-member')
    negative('wrong-hash', pins_override=dict(pins, init='b' * 64),
             expect='member-sha256-mismatch:init')
    negative('three-of-four', members_override=[
        'init', 'opt/droidlayer/guest-agent',
        'opt/droidlayer/openrc/droidlayer-guest-agent'],
        expect='member-set-must-be-exactly-four-contract-members')
    negative('five-paths',
             extra_args=['--member=bin/busybox=%s' % files['init'],
                         '--member-sha256=bin/busybox=%s' % pins['init']],
             expect='member-set-must-be-exactly-four-contract-members')

    plain = os.path.join(work, 'base-plain.cpio')
    with open(plain, 'wb') as f:
        f.write(build_cpio())
    negative('base-not-gzip', base_path=plain, expect='base-not-gzip')

    concat = os.path.join(work, 'base-concat.cpio.gz')
    with open(concat, 'wb') as f:
        f.write(open(base, 'rb').read())
        f.write(open(base, 'rb').read())
    negative('base-trailing-data', base_path=concat,
             expect='base-has-trailing-data')

    noconf = os.path.join(work, 'base-noconf.cpio.gz')
    write_base(noconf,
               members=[m for m in MEMBERS if not m[0].endswith('.conf')],
               extra=[('bin/busybox', 0o100755, b'BUSYBOX')])
    negative('member-not-in-base', base_path=noconf,
             expect='member-not-in-base')

    badmode = os.path.join(work, 'base-badmode.cpio.gz')
    write_base(badmode,
               members=[(n, 0o100644 if n == 'opt/droidlayer/openrc/'
                         'droidlayer-guest-agent' else m, b)
                        for n, m, b in MEMBERS],
               extra=[('bin/busybox', 0o100755, b'BUSYBOX')])
    negative('mode-drift', base_path=badmode, expect='base-member-mode-drift')

    notrailer = os.path.join(work, 'base-notrailer.cpio.gz')
    with open(notrailer, 'wb') as f:
        f.write(gzip.compress(build_cpio(trailer=False), 9))
    negative('no-trailer', base_path=notrailer, expect='cpio-non-newc-entry')

    dup = os.path.join(work, 'base-dup.cpio.gz')
    with open(dup, 'wb') as f:
        f.write(gzip.compress(build_cpio(extra=[('init', 0o100755, b'DUP-INIT')]), 9))
    negative('duplicate-entry', base_path=dup, expect='cpio-duplicate-entry')

    rc, out, err = run(['--base', os.path.join(work, 'missing-base.cpio.gz'),
                        '--output', os.path.join(work, 'neg-out-missbase.cpio.gz')]
                       + member_args(files, pins))
    check('neg:base-missing', rc == 65 and 'missing-or-indirect-base' in err,
          'rc=%d stderr=%r' % (rc, err.strip()))

    return finish()


def finish():
    if CASE_LOG and WORK_ROOT:
        with open(os.path.join(WORK_ROOT, 'case-log.jsonl'), 'w',
                  encoding='utf-8', newline='\n') as f:
            for rec in CASE_LOG:
                f.write(json.dumps(rec, sort_keys=True) + '\n')
    if not failures:
        print('FOURMEMBER_TEST_OK positives=%d negatives=%d'
              % (counts['positive'], counts['negative']))
        return 0
    print('FOURMEMBER_TEST_FAILED cases=%s' % ','.join(failures))
    return 1


if __name__ == '__main__':
    sys.exit(main())
