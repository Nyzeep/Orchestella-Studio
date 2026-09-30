#!/usr/bin/env python3
"""Synthetic-fixture tests for record-apk-postbuild.py (policy section 3).

Builds a synthetic prebuild lock, synthetic AArch64 ELF64 members and a
synthetic APK in the given work root, then drives the fixed order
PREBUILD -> BUILD -> EMIT -> APPROVE -> VERIFY with positive paths and
fail-closed negatives. Every negative must exit RC 65 with the expected
rejection reason. All fixtures are synthetic; nothing here pins or produces a
real APK identity.
Usage: python test-record-apk-postbuild.py <work-root>
"""
import hashlib
import json
import os
import struct
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.join(HERE, 'record-apk-postbuild.py')
ENTRY = 'lib/arm64-v8a/libdroidlayer_qemu.so'

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
    flags = ('--apk', '--lock', '--record', '--expectations')
    i = 0
    while i < len(args):
        a = args[i]
        if a in flags and i + 1 < len(args):
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
           'input_sha256': _hash_inputs(args)}
    CASE_LOG.append(rec)
    return rec['rc'], rec['stdout'], rec['stderr']


def sha(data):
    return hashlib.sha256(data).hexdigest()


def build_elf(dt_needed, machine=183, etype=3):
    dynstr = bytearray(b'\x00')
    offs = []
    for name in dt_needed:
        offs.append(len(dynstr))
        dynstr += name.encode('ascii') + b'\x00'
    dyn = [(1, o) for o in offs] + [(10, len(dynstr)), (0, 0)]
    dyn_bytes = b''.join(struct.pack('<QQ', t, v) for t, v in dyn)
    eh = bytearray(64)
    eh[0:4] = b'\x7fELF'
    eh[4] = 2
    eh[5] = 1
    eh[6] = 1
    eh[16:18] = struct.pack('<H', etype)
    eh[18:20] = struct.pack('<H', machine)
    shoff = 64 + len(dynstr) + len(dyn_bytes)
    eh[40:48] = struct.pack('<Q', shoff)
    eh[58:60] = struct.pack('<H', 64)
    eh[60:62] = struct.pack('<H', 3)

    def shdr(kind, off, size, link, entsize):
        s = bytearray(64)
        s[4:8] = struct.pack('<I', kind)
        s[24:32] = struct.pack('<Q', off)
        s[32:40] = struct.pack('<Q', size)
        s[40:44] = struct.pack('<I', link)
        s[56:64] = struct.pack('<Q', entsize)
        return bytes(s)

    sections = (shdr(0, 0, 0, 0, 0)
                + shdr(3, 64, len(dynstr), 0, 0)
                + shdr(6, 64 + len(dynstr), len(dyn_bytes), 1, 16))
    return bytes(eh) + bytes(dynstr) + dyn_bytes + sections


def build_apk(path, so_bytes, image_bytes, initramfs_bytes, manifest_bytes,
              extra=None):
    with zipfile.ZipFile(path, 'w') as z:
        z.writestr(zipfile.ZipInfo(ENTRY), so_bytes, zipfile.ZIP_DEFLATED)
        z.writestr(zipfile.ZipInfo('Image'), image_bytes, zipfile.ZIP_DEFLATED)
        z.writestr(zipfile.ZipInfo('initramfs.cpio.gz'), initramfs_bytes,
                   zipfile.ZIP_DEFLATED)
        z.writestr(zipfile.ZipInfo('runtime-artifacts-v1.sha256'),
                   manifest_bytes, zipfile.ZIP_DEFLATED)
        for name, data in (extra or {}).items():
            z.writestr(zipfile.ZipInfo(name), data, zipfile.ZIP_DEFLATED)


def write_expectations(path, record, **overrides):
    exp = {
        'schema_version': 'apk-postbuild-v1',
        'expected_apk_output_path': record['apk_output_path'],
        'expected_apk_sha256': record['apk_sha256'],
        'expected_abi': 'arm64-v8a',
        'expected_apk_entry': ENTRY,
        'expected_native_entries': [
            {'path': e['path'], 'sha256': e['sha256'],
             'dt_needed': e['elf']['dt_needed']}
            for e in record['native_entries']],
        'expected_packaging_mode': record['packaging_mode_observed'],
        'expected_manifest_name': record['manifest_name'],
        'expected_manifest_entries': record['manifest_entries'],
        'allowed_unpackaged_dt_needed': ['libc.so', 'libm.so', 'libdl.so'],
    }
    exp.update(overrides)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(exp, f, indent=1)
    return exp


def main():
    global WORK_ROOT
    work = os.path.abspath(sys.argv[1])
    WORK_ROOT = work
    os.makedirs(work, exist_ok=True)

    print('== PHASE PREBUILD: synthetic locked input fixture ==')
    lock = ('# synthetic prebuild lock fixture (not the production lock)\n'
            'schema_version=1\nqemu_version=11.1.1\n'
            'apk_abi=arm64-v8a\napk_entry=%s\n'
            'apk_packaging_mode=extractNativeLibs:true\nruntime_data=none\n'
            % ENTRY)
    lock_path = os.path.join(work, 'synthetic-qemu-jni.lock')
    with open(lock_path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(lock)

    print('== PHASE BUILD: synthetic APK fixture ==')
    so_bytes = build_elf(['libc.so'])
    image_bytes = b'SYNTHETIC-IMAGE-BYTES'
    initramfs_bytes = b'SYNTHETIC-INITRAMFS-GZ-BYTES'
    manifest = ('%s  Image\n%s  initramfs.cpio.gz\n'
                % (sha(image_bytes), sha(initramfs_bytes))).encode('ascii')
    apk_path = os.path.join(work, 'app-debug.apk')
    build_apk(apk_path, so_bytes, image_bytes, initramfs_bytes, manifest)

    print('== PHASE EMIT: record from actual bytes ==')
    record_path = os.path.join(work, 'record.json')
    rc, out, err = run(['emit', '--apk', apk_path, '--lock', lock_path,
                        '--out', record_path])
    check('emit-positive', rc == 0 and 'APK_POSTBUILD_RECORD_EMITTED' in out,
          err or out)
    if rc != 0:
        return finish()
    with open(record_path, 'r', encoding='utf-8') as f:
        record = json.load(f)
    check('record-abi-and-closure',
          record['abi'] == 'arm64-v8a'
          and record['native_entries'][0]['elf']['dt_needed'] == ['libc.so']
          and record['dt_needed_closure']['unpackaged'] == ['libc.so']
          and record['packaging_mode_observed'] == 'extractNativeLibs:true',
          json.dumps(record['dt_needed_closure']))
    check('record-manifest-verified', len(record['manifest_entries']) == 2,
          json.dumps(record['manifest_entries']))

    print('== PHASE APPROVE+VERIFY: expectations derived from the record ==')
    exp_path = os.path.join(work, 'expectations.json')
    write_expectations(exp_path, record)
    rc, out, err = run(['verify', '--record', record_path,
                        '--expectations', exp_path])
    check('verify-positive', rc == 0 and 'APK_POSTBUILD_RECORD_VERIFIED' in out,
          err or out)

    def verify_negative(label, record_mut=None, exp_mut=None, expect=None):
        rec_path = os.path.join(work, 'neg-record-%s.json' % label)
        exp2_path = os.path.join(work, 'neg-exp-%s.json' % label)
        rec = json.loads(json.dumps(record))
        if record_mut:
            record_mut(rec)
        with open(rec_path, 'w', encoding='utf-8') as f:
            json.dump(rec, f, indent=1)
        # expectations are always derived from the ORIGINAL record; a negative
        # that mutates the record therefore fails on the record side only
        write_expectations(exp2_path, record)
        if exp_mut:
            with open(exp2_path, 'r', encoding='utf-8') as f:
                exp = json.load(f)
            exp_mut(exp)
            with open(exp2_path, 'w', encoding='utf-8') as f:
                json.dump(exp, f, indent=1)
        rc, out, err = run(['verify', '--record', rec_path,
                            '--expectations', exp2_path])
        ok = rc == 65 and expect in err
        check('neg:%s' % label, ok, 'rc=%d stderr=%r' % (rc, err.strip()))

    print('== NEGATIVES: fail-closed verify cases ==')
    verify_negative('wrong-digest',
                    exp_mut=lambda e: e.update(expected_apk_sha256='0' * 64),
                    expect='apk-sha256-mismatch')
    verify_negative('path-drift',
                    exp_mut=lambda e: e.update(
                        expected_apk_output_path='other/app-debug.apk'),
                    expect='apk-path-drift')
    verify_negative('missing-expected-value',
                    exp_mut=lambda e: e.pop('expected_apk_sha256'),
                    expect='missing-expected-value:expected_apk_sha256')
    verify_negative('extra-native-entry',
                    exp_mut=lambda e: e.update(expected_native_entries=[]),
                    expect='unexpected-native-entry')
    verify_negative('missing-native-entry',
                    exp_mut=lambda e: e['expected_native_entries'].append(
                        {'path': 'lib/arm64-v8a/libabsent.so',
                         'sha256': '1' * 64, 'dt_needed': ['libc.so']}),
                    expect='expected-native-entry-missing:lib/arm64-v8a/libabsent.so')
    verify_negative('dt-needed-extra',
                    exp_mut=lambda e: e['expected_native_entries'][0]
                    .update(dt_needed=['libc.so', 'libdrift.so']),
                    expect='dt-needed-mismatch')
    verify_negative('dt-needed-unresolved',
                    exp_mut=lambda e: e.update(allowed_unpackaged_dt_needed=[]),
                    expect='dt-needed-unresolved')
    verify_negative('abi-mismatch',
                    exp_mut=lambda e: e.update(expected_abi='x86_64'),
                    expect='abi-mismatch')
    verify_negative('packaging-mode',
                    exp_mut=lambda e: e.update(
                        expected_packaging_mode='extractNativeLibs:false'),
                    expect='packaging-mode-mismatch')
    verify_negative('manifest-count',
                    exp_mut=lambda e: e['expected_manifest_entries'].append(
                        {'sha256': '2' * 64, 'name': 'QEMU-BUILD-INPUTS'}),
                    expect='manifest-entry-count-mismatch')
    verify_negative('record-incomplete',
                    record_mut=lambda r: r.pop('apk_sha256'),
                    expect='record-incomplete:apk_sha256')
    verify_negative('record-schema',
                    record_mut=lambda r: r.update(schema_version='other-v9'),
                    expect='record-schema-unknown')

    print('== NEGATIVES: fail-closed emit cases ==')
    def emit_negative(label, apk=None, lock=None, expect=None, fixture=None):
        apk_path2 = os.path.join(work, 'neg-apk-%s.apk' % label)
        lock_path2 = lock or lock_path
        if fixture:
            build_apk(apk_path2, **fixture)
            apk_path2_arg = apk_path2
        else:
            apk_path2_arg = apk
        rc, out, err = run(['emit', '--apk', apk_path2_arg,
                            '--lock', lock_path2,
                            '--out', os.path.join(work, 'neg-rec-%s.json' % label)])
        ok = rc == 65 and expect in err
        check('neg:%s' % label, ok, 'rc=%d stderr=%r' % (rc, err.strip()))

    emit_negative('apk-missing', apk=os.path.join(work, 'no-such.apk'),
                  expect='missing-or-indirect-apk')
    emit_negative('not-elf',
                  fixture=dict(so_bytes=b'not an elf at all',
                               image_bytes=image_bytes,
                               initramfs_bytes=initramfs_bytes,
                               manifest_bytes=manifest),
                  expect='not-elf')
    emit_negative('wrong-abi-entry',
                  fixture=dict(so_bytes=so_bytes, image_bytes=image_bytes,
                               initramfs_bytes=initramfs_bytes,
                               manifest_bytes=manifest,
                               extra={'lib/x86_64/libother.so': b'\x7fELF-junk'}),
                  expect='native-entry-outside-locked-abi')
    lock_entrymissing = os.path.join(work, 'synthetic-lock-entrymissing.lock')
    with open(lock_entrymissing, 'w', encoding='utf-8', newline='\n') as f:
        f.write(lock.replace('apk_entry=%s' % ENTRY,
                             'apk_entry=lib/arm64-v8a/libabsent.so'))
    rc, out, err = run(['emit', '--apk', apk_path, '--lock', lock_entrymissing,
                        '--out', os.path.join(work, 'neg-rec-entrymissing.json')])
    check('neg:entry-missing', rc == 65 and 'locked-apk-entry-missing' in err,
          'rc=%d stderr=%r' % (rc, err.strip()))
    os.remove(lock_entrymissing)

    lock_missing = lock.replace('apk_abi=arm64-v8a\n', '')
    lp = os.path.join(work, 'synthetic-lock-missingkey.lock')
    with open(lp, 'w', encoding='utf-8', newline='\n') as f:
        f.write(lock_missing)
    rc, out, err = run(['emit', '--apk', apk_path, '--lock', lp,
                        '--out', os.path.join(work, 'neg-rec-lockkey.json')])
    check('neg:lock-missing-key', rc == 65 and 'lock-missing-key:apk_abi' in err,
          'rc=%d stderr=%r' % (rc, err.strip()))

    bad_manifest = ('%s  Image\n%s  Image\n' % ('f' * 64, sha(image_bytes))) \
        .encode('ascii')
    emit_negative('manifest-digest',
                  fixture=dict(so_bytes=so_bytes, image_bytes=image_bytes,
                               initramfs_bytes=initramfs_bytes,
                               manifest_bytes=bad_manifest),
                  expect='manifest-entry-digest-mismatch')
    self_ref = (manifest.decode('ascii')
                + '%s  runtime-artifacts-v1.sha256\n' % sha(manifest)) \
        .encode('ascii')
    emit_negative('manifest-self-reference',
                  fixture=dict(so_bytes=so_bytes, image_bytes=image_bytes,
                               initramfs_bytes=initramfs_bytes,
                               manifest_bytes=self_ref),
                  expect='manifest-self-referencing')
    no_manifest = dict(so_bytes=so_bytes, image_bytes=image_bytes,
                       initramfs_bytes=initramfs_bytes, manifest_bytes=manifest)
    apk_nm = os.path.join(work, 'neg-apk-nomanifest.apk')
    with zipfile.ZipFile(apk_nm, 'w') as z:
        z.writestr(zipfile.ZipInfo(ENTRY), so_bytes, zipfile.ZIP_DEFLATED)
        z.writestr(zipfile.ZipInfo('Image'), image_bytes, zipfile.ZIP_DEFLATED)
        z.writestr(zipfile.ZipInfo('initramfs.cpio.gz'), initramfs_bytes,
                   zipfile.ZIP_DEFLATED)
    rc, out, err = run(['emit', '--apk', apk_nm, '--lock', lock_path,
                        '--out', os.path.join(work, 'neg-rec-nomanifest.json')])
    check('neg:manifest-missing',
          rc == 65 and 'runtime-artifacts-manifest-missing' in err,
          'rc=%d stderr=%r' % (rc, err.strip()))

    return finish()


def finish():
    if CASE_LOG and WORK_ROOT:
        with open(os.path.join(WORK_ROOT, 'case-log.jsonl'), 'w',
                  encoding='utf-8', newline='\n') as f:
            for rec in CASE_LOG:
                f.write(json.dumps(rec, sort_keys=True) + '\n')
    if not failures:
        print('POSTBUILD_TEST_OK positives=%d negatives=%d'
              % (counts['positive'], counts['negative']))
        return 0
    print('POSTBUILD_TEST_FAILED cases=%s' % ','.join(failures))
    return 1


if __name__ == '__main__':
    sys.exit(main())
