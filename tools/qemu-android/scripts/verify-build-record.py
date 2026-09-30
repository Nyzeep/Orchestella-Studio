#!/usr/bin/env python3
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import sys


class Rejected(ValueError):
    pass


def read(path):
    if path.is_symlink() or not path.is_file():
        raise Rejected('missing-or-indirect-input:' + path.name)
    return path.read_text(encoding='utf-8')


def fields(text):
    result = {}
    for line in text.splitlines():
        if not line or line.startswith('#'):
            continue
        if '=' not in line:
            raise Rejected('invalid-record-line')
        key, value = line.split('=', 1)
        if key in result or not value:
            raise Rejected('duplicate-or-empty-key:' + key)
        result[key] = value
    return result


def required(values, key):
    if key not in values:
        raise Rejected('missing-key:' + key)
    return values[key]


def verify(repository, record_path=None, gpl_path=None):
    tool = repository / 'tools/qemu-android'
    lock_path = tool / 'qemu-jni.lock'
    build_path = tool / 'ci/build-in-container.sh'
    record_path = record_path or tool / 'gpl/QEMU-BUILD-INPUTS'
    gpl_path = gpl_path or tool / 'gpl/QEMU-GPL-SOURCES.md'
    lock_text, build_text, record_text, gpl_text = (read(path) for path in (lock_path, build_path, record_path, gpl_path))
    lock, record = fields(lock_text), fields(record_text)
    ordered = required(lock, 'configure_args').split(',')
    if len(ordered) != 9 or len(set(ordered)) != 9:
        raise Rejected('configure-arguments-incomplete')
    matches = re.findall(r'"\$src/qemu-11\.1\.1/configure"\s+(.*?)\s*\)\s*\|\|', build_text, re.S)
    if len(matches) != 1:
        raise Rejected('ambiguous-build-configure')
    actual = shlex.split(matches[0].replace('\\\n', ' '))
    if actual != ordered:
        raise Rejected('build-configure-drift')
    gpl_args = re.findall(r'^- Configure arguments: `([^`]+)`$', gpl_text, re.M)
    if len(gpl_args) != 1 or shlex.split(gpl_args[0]) != ordered:
        raise Rejected('gpl-configure-drift')
    reference = re.findall(r'\((\d+) keys,\s*\n# sha256 ([0-9a-f]{64})\)', record_text)
    if len(reference) != 1:
        raise Rejected('missing-or-ambiguous-lock-reference')
    count, historical_hash = reference[0]
    current_hash = hashlib.sha256(lock_path.read_bytes()).hexdigest()
    print(json.dumps({'lock_sha256': current_hash, 'lock_keys': len(lock),
                      'record_lock_sha256': historical_hash, 'record_lock_keys': int(count),
                      'record_date': required(record, 'date_utc'), 'ordered_configure': ordered}, sort_keys=True))
    if historical_hash != current_hash or int(count) != len(lock):
        raise Rejected('lock-reference-mismatch: historical record is not a current prebuild record')
    if required(record, 'configure_args').split(',') != ordered:
        raise Rejected('record-configure-drift')
    common = ('component_id', 'qemu_version', 'qemu_target_list', 'qemu_archive_url',
              'qemu_archive_sha256', 'qemu_signature_url', 'qemu_signer_fingerprint',
              'qemu_git_tag', 'qemu_git_commit', 'patches', 'builder_image',
              'builder_platform', 'ubuntu_snapshot', 'android_ndk_revision',
              'android_ndk_archive_url', 'android_ndk_metadata_sha1',
              'android_ndk_archive_sha256', 'android_api_level', 'source_date_epoch',
              'python_hash_seed', 'build_network_policy', 'meson_tool_member',
              'meson_tool_sha256', 'apk_abi', 'apk_entry', 'apk_packaging_mode', 'runtime_data')
    for key in common:
        if required(lock, key) != required(record, key):
            raise Rejected('record-identity-drift:' + key)
    for key, value in record.items():
        if key in lock and key != 'configure_args' and value != lock[key]:
            raise Rejected('record-identity-drift:' + key)
    for key in ('qemu_archive_url', 'qemu_archive_sha256', 'qemu_signature_url',
                'qemu_signer_fingerprint', 'qemu_git_tag', 'qemu_git_commit',
                'builder_image', 'ubuntu_snapshot', 'android_ndk_revision',
                'android_ndk_metadata_sha1', 'android_ndk_archive_sha256'):
        if lock[key] not in gpl_text:
            raise Rejected('gpl-identity-missing:' + key)
    entry = required(record, 'build_entrypoint')
    if entry != 'tools/qemu-android/ci/build-in-container.sh':
        raise Rejected('historical-build-entrypoint: current build needs its own execution record')
    build_hash = hashlib.sha256(build_path.read_bytes()).hexdigest()
    if required(record, 'build_entrypoint_sha256') != build_hash:
        raise Rejected('build-entrypoint-hash-mismatch')
    if entry not in gpl_text or build_hash not in gpl_text:
        raise Rejected('gpl-entrypoint-mismatch')
    print('BUILD_RECORD_READY ordered_configure=9 current_lock_reference=verified')


def main():
    parser = argparse.ArgumentParser(description='Fail closed before using a current build-input record; historical records remain immutable.')
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument('--record', type=Path, help='Explicit prospective build record; never overwrites the shipped record.')
    parser.add_argument('--gpl', type=Path, help='Explicit prospective GPL source record matched to the build record.')
    args = parser.parse_args()
    try:
        if bool(args.record) != bool(args.gpl):
            raise Rejected('prospective-record-and-gpl-must-be-paired')
        verify(args.repo.resolve(strict=True), args.record, args.gpl)
    except (Rejected, OSError, UnicodeError) as error:
        print('BUILD_RECORD_REJECTED ' + str(error), file=sys.stderr)
        return 65
    return 0


if __name__ == '__main__':
    sys.exit(main())
