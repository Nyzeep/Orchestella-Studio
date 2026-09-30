#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


def main():
    repository = Path(__file__).resolve().parents[3]
    evidence = Path(os.environ['TICKET26_WORK_ROOT']).resolve(strict=True)
    fixture = Path(tempfile.mkdtemp(prefix='build-record-test-', dir=evidence))
    tool = fixture / 'tools/qemu-android'
    (tool / 'ci').mkdir(parents=True)
    (tool / 'gpl').mkdir()
    relative_files = ('qemu-jni.lock', 'ci/build-in-container.sh',
                      'gpl/QEMU-BUILD-INPUTS', 'gpl/QEMU-GPL-SOURCES.md')
    for relative in relative_files:
        (tool / relative).write_bytes((repository / 'tools/qemu-android' / relative).read_bytes())
    lock = (tool / 'qemu-jni.lock').read_text(encoding='utf-8')
    lock_hash = hashlib.sha256(lock.encode()).hexdigest()
    count = sum(bool(line and not line.startswith('#')) for line in lock.splitlines())
    ordered = next(line.split('=', 1)[1] for line in lock.splitlines() if line.startswith('configure_args='))
    build_hash = hashlib.sha256((tool / 'ci/build-in-container.sh').read_bytes()).hexdigest()
    record = (tool / 'gpl/QEMU-BUILD-INPUTS').read_text(encoding='utf-8')
    record = re.sub(r'\(125 keys,\n# sha256 [0-9a-f]{64}\)', f'({count} keys,\n# sha256 {lock_hash})', record)
    record = re.sub(r'^configure_args=.*$', 'configure_args=' + ordered, record, flags=re.M)
    record = re.sub(r'^build_entrypoint=.*$', 'build_entrypoint=tools/qemu-android/ci/build-in-container.sh', record, flags=re.M)
    record = re.sub(r'^build_entrypoint_sha256=.*$', 'build_entrypoint_sha256=' + build_hash, record, flags=re.M)
    (tool / 'gpl/QEMU-BUILD-INPUTS').write_text(record, encoding='utf-8')
    gpl = (tool / 'gpl/QEMU-GPL-SOURCES.md').read_text(encoding='utf-8')
    gpl = gpl.replace('R/evidence/c-android-incontainer-20260925.sh', 'tools/qemu-android/ci/build-in-container.sh')
    gpl = gpl.replace('f4b27d2289c642de6805fede1814dd2063d1e07e33fdbb74d0f6cf7b9337c338', build_hash)
    (tool / 'gpl/QEMU-GPL-SOURCES.md').write_text(gpl, encoding='utf-8')
    verifier = repository / 'tools/qemu-android/scripts/verify-build-record.py'
    outcomes = []

    def run(name, expected, reason):
        command = [sys.executable, str(verifier), '--repo', str(fixture)]
        hashes = {relative: hashlib.sha256((tool / relative).read_bytes()).hexdigest() for relative in relative_files}
        (fixture / (name + '.command.json')).write_text(json.dumps(command), encoding='utf-8')
        (fixture / (name + '.inputs.json')).write_text(json.dumps(hashes, indent=2), encoding='utf-8')
        result = subprocess.run(command, capture_output=True, env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'})
        (fixture / (name + '.stdout.txt')).write_bytes(result.stdout)
        (fixture / (name + '.stderr.txt')).write_bytes(result.stderr)
        (fixture / (name + '.rc')).write_text(str(result.returncode) + '\n', encoding='ascii')
        assert result.returncode == expected, f'{name}: RC={result.returncode}; {result.stderr.decode()}'
        assert reason in (result.stdout + result.stderr).decode(), f'{name}: missing reason {reason}'
        outcomes.append({'case': name, 'rc': result.returncode})

    run('consistent', 0, 'BUILD_RECORD_READY')
    if os.environ.get('BUILD_RECORD_TEST_SLICE') == 'positive':
        print(json.dumps({'fixture': str(fixture), 'results': outcomes}))
        return
    record_path = tool / 'gpl/QEMU-BUILD-INPUTS'
    lock_path = tool / 'qemu-jni.lock'
    original_lock = lock_path.read_bytes()
    lock_path.write_bytes(re.sub(rb'^configure_args=.*\n', b'', original_lock, flags=re.M))
    run('missing-configure-key', 65, 'missing-key:configure_args')
    lock_path.write_bytes(original_lock)
    record_path.write_text(record.replace(lock_hash, '0' * 64), encoding='utf-8')
    run('wrong-lock-reference', 65, 'lock-reference-mismatch')
    record_path.write_text(record, encoding='utf-8')
    build_path = tool / 'ci/build-in-container.sh'
    original_build = build_path.read_bytes()
    build_path.write_bytes(original_build.replace(b'--cpu=aarch64', b'--cpu=wrongcpu'))
    run('actual-parameter-drift', 65, 'build-configure-drift')
    build_path.write_bytes(original_build)
    gpl_path = tool / 'gpl/QEMU-GPL-SOURCES.md'
    gpl_path.write_text(gpl.replace('--prefix=/usr`', '--prefix=/different`'), encoding='utf-8')
    run('gpl-parameter-drift', 65, 'gpl-configure-drift')
    gpl_path.write_text(gpl, encoding='utf-8')
    record_path.write_text(record.replace('configure_args=' + ordered, 'configure_args=' + ordered.rsplit(',', 1)[0]), encoding='utf-8')
    run('incomplete-record-arguments', 65, 'record-configure-drift')
    record_path.write_text(record, encoding='utf-8')
    run('consistent-restored', 0, 'BUILD_RECORD_READY')
    print(json.dumps({'fixture': str(fixture), 'results': outcomes}))


if __name__ == '__main__':
    main()
