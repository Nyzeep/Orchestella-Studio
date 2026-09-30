#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile


def workflow_prefix(text, job, step):
    sections = re.split(r'^  ([a-z0-9-]+):\n', text, flags=re.M)
    jobs = dict(zip(sections[1::2], sections[2::2]))
    block = jobs[job]
    marker = '      - name: ' + step + '\n        run: |\n'
    assert block.count(marker) == 1, 'ambiguous step: ' + job
    rest = block.split(marker, 1)[1]
    lines = []
    for line in rest.splitlines():
        if line and not line.startswith('          '):
            break
        command = line[10:] if line else ''
        if 'mkdir -p ' in command or 'fetch-ci-inputs.sh' in command:
            break
        lines.append(command)
    assert lines, 'empty prefetch prefix: ' + job
    prefix = '\n'.join(lines) + '\n'
    assert not re.search(r'\b(curl|docker|git|ninja|make)\b', prefix), 'unsafe prefix'
    return prefix


def main():
    repository = Path(__file__).resolve().parents[3]
    root = Path(os.environ['TICKET26_WORK_ROOT']).resolve(strict=True)
    fixture = Path(tempfile.mkdtemp(prefix='native-prebuild-test-', dir=root))
    tool = fixture / 'tools/qemu-android'
    paths = ['qemu-jni.lock', 'ci/build-in-container.sh', 'gpl/QEMU-BUILD-INPUTS',
             'gpl/QEMU-GPL-SOURCES.md', 'scripts/verify-qemu-jni-lock.sh',
             'scripts/verify-build-record.py', 'scripts/verify-native-prebuild.sh',
             'scripts/prepare-qemu-payload.sh', 'vendor/dtc-b6910bec.tar',
             'vendor/proxy-libintl-0.4.tar']
    for relative in paths:
        source = repository / 'tools/qemu-android' / relative
        if not source.is_file():
            continue
        target = tool / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read_bytes())
    lock_path = tool / 'qemu-jni.lock'
    lock_text = lock_path.read_text(encoding='utf-8')
    lock_hash = hashlib.sha256(lock_path.read_bytes()).hexdigest()
    count = sum(bool(line and not line.startswith('#')) for line in lock_text.splitlines())
    args = next(line.split('=', 1)[1] for line in lock_text.splitlines() if line.startswith('configure_args='))
    builder_hash = hashlib.sha256((tool / 'ci/build-in-container.sh').read_bytes()).hexdigest()
    record_path = tool / 'gpl/QEMU-BUILD-INPUTS'
    old_record = record_path.read_text(encoding='utf-8')
    consistent = re.sub(r'\(125 keys,\n# sha256 [0-9a-f]{64}\)', f'({count} keys,\n# sha256 {lock_hash})', old_record)
    consistent = re.sub(r'^configure_args=.*$', 'configure_args=' + args, consistent, flags=re.M)
    consistent = re.sub(r'^build_entrypoint=.*$', 'build_entrypoint=tools/qemu-android/ci/build-in-container.sh', consistent, flags=re.M)
    consistent = re.sub(r'^build_entrypoint_sha256=.*$', 'build_entrypoint_sha256=' + builder_hash, consistent, flags=re.M)
    gpl_path = tool / 'gpl/QEMU-GPL-SOURCES.md'
    gpl = gpl_path.read_text(encoding='utf-8').replace('R/evidence/c-android-incontainer-20260925.sh', 'tools/qemu-android/ci/build-in-container.sh')
    gpl = gpl.replace('f4b27d2289c642de6805fede1814dd2063d1e07e33fdbb74d0f6cf7b9337c338', builder_hash)
    record_path.write_text(consistent, encoding='utf-8')
    gpl_path.write_text(gpl, encoding='utf-8')
    workflow = repository / '.github/workflows/qemu-android.yml'
    text = workflow.read_text(encoding='utf-8')
    entries = {
        'provenance-lock': workflow_prefix(text, 'provenance-lock', 'Verify lock schema and completeness'),
        'native-build-two-clean': workflow_prefix(text, 'native-build-two-clean', 'Provision pinned inputs'),
        'elf-closure-16kb': workflow_prefix(text, 'elf-closure-16kb', 'Provision pinned inputs and build once'),
        'apk-packaging-signature-tamper': workflow_prefix(text, 'apk-packaging-signature-tamper', 'Build QEMU payload in the pinned builder container'),
    }
    entries['prepare'] = 'CI_REPO=' + shlex.quote(str(fixture)) + ' bash ' + shlex.quote(str(tool / 'scripts/prepare-qemu-payload.sh')) + ' --verify-prebuild\n'
    outcomes = []
    workflow_hash = hashlib.sha256(workflow.read_bytes()).hexdigest()

    def execute(entry, prefix, case, expected, reason):
        script = prefix.replace('/workspace', shlex.quote(str(fixture))).replace('cd repo\n', 'cd ' + shlex.quote(str(fixture)) + '\n')
        script = 'set -euo pipefail\n' + script + 'printf "NEXT_ACQUISITION_SENTINEL\\n"\nexit 73\n'
        name = entry + '-' + case
        script_path = fixture / (name + '.sh')
        script_path.write_text(script, encoding='utf-8')
        hashes = {path: hashlib.sha256((tool / path).read_bytes()).hexdigest() for path in paths if (tool / path).is_file()}
        command = ['bash', str(script_path)]
        (fixture / (name + '.command.json')).write_text(json.dumps({'argv': command, 'workflow_sha256': workflow_hash, 'input_hashes': hashes}, indent=2), encoding='utf-8')
        result = subprocess.run(command, capture_output=True, cwd=fixture,
                                env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'})
        (fixture / (name + '.stdout.txt')).write_bytes(result.stdout)
        (fixture / (name + '.stderr.txt')).write_bytes(result.stderr)
        (fixture / (name + '.rc')).write_text(str(result.returncode) + '\n', encoding='ascii')
        output = (result.stdout + result.stderr).decode('utf-8')
        assert result.returncode == expected, f'{name}: RC {result.returncode}: {output}'
        assert reason in output, f'{name}: missing {reason}: {output}'
        if case == 'consistent':
            markers = ['DROIDLAYER_QEMU_JNI_LOCK_OK', 'BUILD_RECORD_READY', 'NATIVE_PREBUILD_READY', 'NEXT_ACQUISITION_SENTINEL']
            assert all(marker in output for marker in markers), f'{name}: real verifier calls absent: {output}'
            assert [output.index(marker) for marker in markers] == sorted(output.index(marker) for marker in markers), name
        else:
            assert 'NEXT_ACQUISITION_SENTINEL' not in output, name + ': rejected input reached acquisition'
            if case in ('missing-field', 'wrong-source-hash'):
                assert 'BUILD_RECORD_READY' not in output and '"lock_sha256"' not in output, name + ': lock failure did not stop before P5'
        outcomes.append({'entry': entry, 'case': case, 'rc': result.returncode})

    for entry, prefix in entries.items():
        lock_path.write_text(lock_text, encoding='utf-8')
        record_path.write_text(consistent, encoding='utf-8')
        execute(entry, prefix, 'consistent', 73, 'NEXT_ACQUISITION_SENTINEL')
        record_path.write_text(old_record, encoding='utf-8')
        execute(entry, prefix, 'historical-record', 65, 'lock-reference-mismatch')
        record_path.write_text(consistent.replace(lock_hash, '0' * 64), encoding='utf-8')
        execute(entry, prefix, 'wrong-record-hash', 65, 'lock-reference-mismatch')
        record_path.write_text(consistent, encoding='utf-8')
        lock_path.write_text(re.sub(r'^qemu_archive_sha256=.*\n', '', lock_text, flags=re.M), encoding='utf-8')
        execute(entry, prefix, 'missing-field', 65, 'missing required key: qemu_archive_sha256')
        lock_path.write_text(lock_text.replace('qemu_archive_sha256=079ffbff', 'qemu_archive_sha256=179ffbff'), encoding='utf-8')
        execute(entry, prefix, 'wrong-source-hash', 65, 'unexpected value for: qemu_archive_sha256')
    prepare = tool / 'scripts/prepare-qemu-payload.sh'
    for case, record_bytes, expected_fragment in (
        ('consistent', consistent.encode(), 'PREPARE_PAYLOAD_DIE: lock missing at /pkgbuild/qemu-jni.lock'),
        ('historical-record', old_record.encode(), 'lock-reference-mismatch')):
        record_path.write_bytes(record_bytes)
        lock_path.write_text(lock_text, encoding='utf-8')
        name = 'prepare-default-' + case
        command = ['bash', str(prepare)]
        inputs = {path: hashlib.sha256((tool / path).read_bytes()).hexdigest() for path in paths if (tool / path).is_file()}
        (fixture / (name + '.command.json')).write_text(json.dumps({'argv': command, 'input_hashes': inputs}, indent=2), encoding='utf-8')
        result = subprocess.run(command, cwd=fixture, capture_output=True,
                                env={**os.environ, 'CI_REPO': str(fixture), 'PYTHONDONTWRITEBYTECODE': '1'})
        (fixture / (name + '.stdout.txt')).write_bytes(result.stdout)
        (fixture / (name + '.stderr.txt')).write_bytes(result.stderr)
        (fixture / (name + '.rc')).write_text(str(result.returncode) + '\n', encoding='ascii')
        output = (result.stdout + result.stderr).decode('utf-8')
        assert result.returncode == 1 and expected_fragment in output, name + ': ' + output
        assert 'DROIDLAYER_QEMU_JNI_LOCK_OK' in output, name
        if case == 'consistent':
            assert 'BUILD_RECORD_READY' in output and 'NATIVE_PREBUILD_READY' in output, name
        else:
            assert 'BUILD_RECORD_READY' not in output and 'NATIVE_PREBUILD_READY' not in output, name
        outcomes.append({'entry': 'prepare-default', 'case': case, 'rc': result.returncode})
    print(json.dumps({'fixture': str(fixture), 'scope': 'actual workflow prefetch prefixes and actual prepare default branch; no fetch/build/postprocess', 'results': outcomes}))


if __name__ == '__main__':
    main()
