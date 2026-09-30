#!/usr/bin/env python3
"""Ticket 26 APK post-build record (policy section 3 output-side keys).

Two fail-closed phases with a fixed order:
  1. emit    -- read an ACTUAL APK (zip) plus the build-time lock that was
               verified before the build, and derive a post-build record from
               the real bytes: output path/size/sha256, ABI layout, every
               packaged ELF's DT_NEEDED closure, observed packaging mode, and
               the runtime-artifacts manifest entries.
  2. verify  -- compare that record against separately approved expectations.
               A missing expected value, a digest mismatch, path drift, an
               unexpected/missing native entry, a DT_NEEDED mismatch, or an
               incomplete record/manifest is a rejection (RC 65), never an
               inference. A historical APK digest is never accepted as the
               expectation for a future build; expectations are a separate
               approved input.

This module records and verifies; it does not run builds, sign, or modify any
packaging identity. Lock/APK identity changes still follow policy section 7.
"""
import argparse
import hashlib
import json
import posixpath
import sys
import zipfile

SCHEMA = 'apk-postbuild-v1'
RECORD_KEYS = ('schema_version', 'apk_output_path', 'apk_bytes', 'apk_sha256',
               'prebuild_lock_path', 'prebuild_lock_sha256', 'lock_pins',
               'abi', 'native_entries', 'packaging_mode_observed',
               'manifest_name', 'manifest_entries')
BIONIC_PLACEHOLDER_NOTE = 'unpackaged DT_NEEDED must be allowed by approved expectations'


class Rejected(ValueError):
    pass


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def parse_elf64(data):
    """Minimal ELF64 little-endian reader: class/machine/type + DT_NEEDED."""
    if len(data) < 64 or data[:4] != b'\x7fELF':
        raise Rejected('not-elf')
    if data[4] != 2:
        raise Rejected('elf-not-64bit')
    if data[5] != 1:
        raise Rejected('elf-not-little-endian')
    e_type = int.from_bytes(data[16:18], 'little')
    e_machine = int.from_bytes(data[18:20], 'little')
    e_shoff = int.from_bytes(data[40:48], 'little')
    e_shentsize = int.from_bytes(data[58:60], 'little')
    e_shnum = int.from_bytes(data[60:62], 'little')
    if e_shoff == 0 or e_shnum == 0 or e_shentsize != 64:
        raise Rejected('elf-sections-missing')
    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * 64
        if off + 64 > len(data):
            raise Rejected('elf-section-table-truncated')
        sections.append({
            'type': int.from_bytes(data[off + 4:off + 8], 'little'),
            'offset': int.from_bytes(data[off + 24:off + 32], 'little'),
            'size': int.from_bytes(data[off + 32:off + 40], 'little'),
            'link': int.from_bytes(data[off + 40:off + 44], 'little'),
        })
    dynamic = [s for s in sections if s['type'] == 6]
    if not dynamic:
        raise Rejected('elf-dynamic-section-missing')
    dyn = dynamic[0]
    if not 0 <= dyn['link'] < len(sections):
        raise Rejected('elf-dynamic-strtab-missing')
    strsec = sections[dyn['link']]
    strtab = data[strsec['offset']:strsec['offset'] + strsec['size']]
    needed = []
    for off in range(dyn['offset'], dyn['offset'] + dyn['size'], 16):
        if off + 16 > len(data):
            raise Rejected('elf-dynamic-truncated')
        tag = int.from_bytes(data[off:off + 8], 'little')
        if tag == 0:
            break
        if tag == 1:
            val = int.from_bytes(data[off + 8:off + 16], 'little')
            end = strtab.find(b'\x00', val)
            if end < 0:
                raise Rejected('elf-dt-needed-unterminated')
            needed.append(strtab[val:end].decode('ascii', 'strict'))
    return {'etype': {2: 'ET_EXEC', 3: 'ET_DYN'}.get(e_type, 'ET_OTHER'),
            'machine': {183: 'AArch64'}.get(e_machine, 'EM_%d' % e_machine),
            'dt_needed': needed}


def read_lock_pins(text):
    pins = {}
    for line in text.splitlines():
        if not line or line.startswith('#'):
            continue
        if '=' not in line:
            raise Rejected('invalid-lock-line')
        key, value = line.split('=', 1)
        if key in pins:
            raise Rejected('duplicate-lock-key:' + key)
        pins[key] = value
    for key in ('apk_abi', 'apk_entry', 'apk_packaging_mode', 'runtime_data'):
        if key not in pins:
            raise Rejected('lock-missing-key:' + key)
    return pins


def emit(apk_path, lock_path):
    import os
    if os.path.islink(apk_path) or not os.path.isfile(apk_path):
        raise Rejected('missing-or-indirect-apk:' + apk_path)
    if os.path.islink(lock_path) or not os.path.isfile(lock_path):
        raise Rejected('missing-or-indirect-lock:' + lock_path)
    lock_bytes = open(lock_path, 'rb').read()
    pins = read_lock_pins(lock_bytes.decode('utf-8'))
    with open(apk_path, 'rb') as f:
        apk_bytes = f.read()
    record = {
        'schema_version': SCHEMA,
        'apk_output_path': apk_path.replace('\\', '/'),
        'apk_bytes': len(apk_bytes),
        'apk_sha256': sha256_bytes(apk_bytes),
        'prebuild_lock_path': lock_path.replace('\\', '/'),
        'prebuild_lock_sha256': sha256_bytes(lock_bytes),
        'lock_pins': {k: pins[k] for k in
                      ('apk_abi', 'apk_entry', 'apk_packaging_mode', 'runtime_data')},
    }
    entries = {}
    manifest_name = None
    manifest_lines = []
    with zipfile.ZipFile(apk_path) as z:
        infos = z.infolist()
        for info in infos:
            name = info.filename
            if name.endswith('/'):
                continue
            data = z.read(name)
            if name in ('runtime-artifacts-v1.sha256', 'assets/runtime-artifacts-v1.sha256'):
                manifest_name = name
                for line in data.decode('utf-8').splitlines():
                    if line.strip():
                        digest, _, target = line.partition('  ')
                        manifest_lines.append({'sha256': digest,
                                               'name': target.strip()})
            if name.startswith('lib/') and name.endswith('.so'):
                if posixpath.dirname(name) != 'lib/' + pins['apk_abi']:
                    raise Rejected('native-entry-outside-locked-abi:' + name)
                elf = parse_elf64(data)
                if elf['machine'] != 'AArch64':
                    raise Rejected('native-entry-not-AArch64:' + name)
                entries[name] = {
                    'path': name,
                    'bytes': len(data),
                    'sha256': sha256_bytes(data),
                    'stored': info.compress_type == zipfile.ZIP_STORED,
                    'elf': elf,
                }
        if not entries:
            raise Rejected('no-native-entries')
        entry = entries.get(pins['apk_entry'])
        if entry is None:
            raise Rejected('locked-apk-entry-missing:' + pins['apk_entry'])
        packaged_names = {posixpath.basename(p) for p in entries}
        needed_union = sorted({n for e in entries.values()
                               for n in e['elf']['dt_needed']})
        unresolved = [n for n in needed_union if n not in packaged_names]
        record['abi'] = pins['apk_abi']
        record['native_entries'] = [entries[k] for k in sorted(entries)]
        record['dt_needed_closure'] = {
            'needed': needed_union,
            'packaged': sorted(packaged_names),
            'unpackaged': unresolved,
        }
        if all(e['stored'] for e in entries.values()):
            record['packaging_mode_observed'] = 'extractNativeLibs:false'
        elif not any(e['stored'] for e in entries.values()):
            record['packaging_mode_observed'] = 'extractNativeLibs:true'
        else:
            raise Rejected('mixed-native-entry-storage')
        record['manifest_name'] = manifest_name
        namelist = set(z.namelist())
        for row in manifest_lines:
            target_name = row['name']
            if target_name == manifest_name or ('assets/' + target_name) == manifest_name:
                raise Rejected('manifest-self-referencing')
            if target_name in namelist:
                member = z.read(target_name)
            elif ('assets/' + target_name) in namelist:
                member = z.read('assets/' + target_name)
            else:
                raise Rejected('manifest-member-missing:' + target_name)
            if sha256_bytes(member) != row['sha256']:
                raise Rejected('manifest-entry-digest-mismatch:' + target_name)
        record['manifest_entries'] = manifest_lines
    if manifest_name is None:
        raise Rejected('runtime-artifacts-manifest-missing')
    return record


def require(mapping, key, why):
    if key not in mapping or mapping[key] in (None, '', [], {}):
        raise Rejected(why + ':' + key)
    return mapping[key]


def verify(record, expectations):
    if require(record, 'schema_version', 'record-incomplete') != SCHEMA:
        raise Rejected('record-schema-unknown')
    for key in RECORD_KEYS:
        if key != 'schema_version':
            require(record, key, 'record-incomplete')
    scalar_keys = ('expected_apk_output_path', 'expected_apk_sha256',
                   'expected_abi', 'expected_apk_entry',
                   'expected_packaging_mode', 'expected_manifest_name')
    list_keys = ('expected_native_entries', 'expected_manifest_entries',
                 'allowed_unpackaged_dt_needed')
    for key in scalar_keys:
        require(expectations, key, 'missing-expected-value')
    for key in list_keys:
        if key not in expectations:
            raise Rejected('missing-expected-value:' + key)
    if record['apk_output_path'] != expectations['expected_apk_output_path']:
        raise Rejected('apk-path-drift')
    if record['apk_sha256'] != expectations['expected_apk_sha256']:
        raise Rejected('apk-sha256-mismatch')
    if record['apk_bytes'] != expectations.get('expected_apk_bytes',
                                               record['apk_bytes']):
        raise Rejected('apk-bytes-mismatch')
    if record['abi'] != expectations['expected_abi']:
        raise Rejected('abi-mismatch')
    if record['packaging_mode_observed'] != expectations['expected_packaging_mode']:
        raise Rejected('packaging-mode-mismatch')
    expected_entries = {e['path']: e for e in expectations['expected_native_entries']}
    actual_entries = {e['path']: e for e in record['native_entries']}
    for path in sorted(set(expected_entries) - set(actual_entries)):
        raise Rejected('expected-native-entry-missing:' + path)
    for path in sorted(set(actual_entries) - set(expected_entries)):
        raise Rejected('unexpected-native-entry:' + path)
    for path, expected in sorted(expected_entries.items()):
        actual = actual_entries[path]
        if actual['sha256'] != expected['sha256']:
            raise Rejected('native-entry-sha256-mismatch:' + path)
        require(expected, 'dt_needed', 'missing-expected-value')
        if sorted(actual['elf']['dt_needed']) != sorted(expected['dt_needed']):
            raise Rejected('dt-needed-mismatch:' + path)
    allowed = set(expectations['allowed_unpackaged_dt_needed'])
    extra = [n for n in record['dt_needed_closure']['unpackaged']
             if n not in allowed]
    if extra:
        raise Rejected('dt-needed-unresolved:' + ','.join(sorted(extra)))
    if record['manifest_name'] != expectations['expected_manifest_name']:
        raise Rejected('manifest-name-mismatch')
    expected_manifest = expectations['expected_manifest_entries']
    if len(record['manifest_entries']) != len(expected_manifest):
        raise Rejected('manifest-entry-count-mismatch')
    expected_names = sorted(e['name'] for e in expected_manifest)
    actual_names = sorted(e['name'] for e in record['manifest_entries'])
    if expected_names != actual_names:
        raise Rejected('manifest-entry-set-mismatch')
    return True


def main():
    parser = argparse.ArgumentParser(
        description='Emit a post-build APK record from actual bytes, or verify it '
                    'against approved expectations (fail closed, RC 65).')
    sub = parser.add_subparsers(dest='command', required=True)
    p_emit = sub.add_parser('emit')
    p_emit.add_argument('--apk', required=True)
    p_emit.add_argument('--lock', required=True)
    p_emit.add_argument('--out', required=True)
    p_verify = sub.add_parser('verify')
    p_verify.add_argument('--record', required=True)
    p_verify.add_argument('--expectations', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'emit':
            record = emit(args.apk, args.lock)
            with open(args.out, 'w', encoding='utf-8', newline='\n') as f:
                json.dump(record, f, sort_keys=True, indent=1)
                f.write('\n')
            print('APK_POSTBUILD_RECORD_EMITTED apk_sha256=%s native_entries=%d'
                  % (record['apk_sha256'], len(record['native_entries'])))
        else:
            with open(args.record, 'r', encoding='utf-8') as f:
                record = json.load(f)
            with open(args.expectations, 'r', encoding='utf-8') as f:
                expectations = json.load(f)
            verify(record, expectations)
            print('APK_POSTBUILD_RECORD_VERIFIED')
    except (Rejected, OSError, UnicodeError, zipfile.BadZipFile,
            KeyError, ValueError) as error:
        print('APK_POSTBUILD_REJECTED ' + str(error), file=sys.stderr)
        return 65
    return 0


if __name__ == '__main__':
    sys.exit(main())
