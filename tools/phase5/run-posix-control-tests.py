#!/usr/bin/env python3
"""Build fresh x86_64 musl test agents and run process contracts in existing WSL.

Example (all paths are explicit, existing local inputs; no downloads):
  python -B tools/phase5/run-posix-control-tests.py --source guest-assets/agent/guest-agent.c \
    --ndk-llvm D:/AndroidSDK/ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64 \
    --musl-sysroot <existing-x86_64-musl-sysroot> --provenance <input-provenance.json> \
    --output-root <approved-external-posix-validation> --wsl-distro Ubuntu-24.04

Every invocation uses new temp/evidence/preserved run directories. Test macros never
enter the production AArch64 producer. No system packages, network, or device actions.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def linux_path(path):
    value = Path(path).resolve().as_posix()
    if len(value) < 3 or value[1:3] != ':/':
        raise ValueError('Windows drive path required for WSL input')
    return '/mnt/' + value[0].lower() + '/' + value[3:]


def save(paths, name, value):
    data = value if isinstance(value, bytes) else (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    for directory in paths:
        with (directory / name).open('xb') as stream:
            stream.write(data)


def captured(command, environment, timeout):
    try:
        return subprocess.run(command, env=environment, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        return subprocess.CompletedProcess(command, 124, error.stdout or b'',
            (error.stderr or b'') + b'\nRunner process exceeded its explicit deadline.\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--ndk-llvm', required=True, type=Path)
    parser.add_argument('--musl-sysroot', required=True, type=Path)
    parser.add_argument('--provenance', required=True, type=Path)
    parser.add_argument('--output-root', required=True, type=Path)
    parser.add_argument('--wsl-distro', default='Ubuntu-24.04')
    parser.add_argument('--quality-tests', action='store_true', help='Run existing HELLO and compile-time POWER mock scripts unchanged')
    args = parser.parse_args()
    if os.name != 'nt':
        parser.error('Windows NDK compiler + existing WSL launcher required')
    source, llvm, sysroot, provenance, root = (p.resolve() for p in
        (args.source, args.ndk_llvm, args.musl_sysroot, args.provenance, args.output_root))
    repo = Path(__file__).resolve().parents[2]
    if root.is_relative_to(repo) or repo.is_relative_to(root):
        parser.error('Output must be a separate approved external root')
    if any(not p.is_file() for p in (source, provenance)):
        parser.error('Source and provenance must exist')
    chain = json.loads(provenance.read_text(encoding='utf-8-sig'))
    if chain.get('status') != 'READY' or chain.get('architecture') != 'x86_64':
        parser.error('Existing verified x86_64 musl provenance required')
    for relative, record in chain['required_files'].items():
        path = sysroot / relative
        if path.stat().st_size != record['bytes'] or sha(path) != record['sha256']:
            parser.error('Pinned toolchain input mismatch: ' + relative)
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    temp, evidence, preserved = (root / name / stamp for name in ('temp', 'evidence', 'preserved'))
    for directory in (temp, evidence, preserved):
        directory.mkdir(parents=True, exist_ok=False)
    environment = os.environ.copy()
    environment.update(TMP=str(temp), TEMP=str(temp), TMPDIR=str(temp), PYTHONDONTWRITEBYTECODE='1')
    paths = (evidence, preserved)
    tests = Path(__file__).resolve().parent / 'tests'
    shutil.copyfile(tests / 'synthetic-profilectl', temp / 'synthetic-profilectl')
    snapshot = temp / 'guest-agent.c'
    snapshot.write_bytes(source.read_bytes())
    source_sha = sha(snapshot)
    if sha(source) != source_sha:
        raise RuntimeError('Source changed while capturing test snapshot')
    compiler = llvm / 'bin/clang.exe'
    readelf = llvm / 'bin/llvm-readelf.exe'
    builtins = llvm / 'lib/clang/18/lib/linux/libclang_rt.builtins-x86_64-android.a'
    library = sysroot / 'usr/lib'
    report = {'status': 'BUILDING', 'run_id': stamp, 'source': str(source),
              'snapshot': str(snapshot), 'snapshot_sha256': source_sha,
              'source_sha256_after_build': None, 'binaries': {},
              'compiler_sha256': sha(compiler), 'builtins_sha256': sha(builtins),
              'provenance': str(provenance), 'provenance_sha256': sha(provenance),
              'output_root': str(root), 'temporary': str(temp), 'evidence': str(evidence),
              'preserved': str(preserved), 'wsl_distro': args.wsl_distro,
              'scope': 'fresh x86_64 test binaries only; ARM64 and devices NOT_RUN'}
    common = [str(compiler), '--target=x86_64-linux-musl', '--sysroot=' + str(sysroot),
              '-static', '-fuse-ld=lld', '-nostdinc', '-isystem', str(sysroot / 'usr/include'),
              '-std=c11', '-Os', '-Wall', '-Wextra', '-Werror', '-fno-ident',
              '-Wl,--build-id=none', '-nostdlib', '-Wl,-e,_start',
              '-DORCHESTELLA_PROFILECTL_PATH="' + linux_path(temp / 'synthetic-profilectl') + '"',
              str(library / 'crt1.o'), str(library / 'crti.o')]
    tail = ['-Wl,--start-group', str(library / 'libc.a'), str(builtins),
            '-Wl,--end-group', str(library / 'crtn.o')]
    for variant, defines in (('stdin', []), ('tcp', ['-DDROIDLAYER_TEST_AF_INET'])):
        output = temp / ('guest-agent-' + variant)
        command = common + defines + [str(snapshot)] + tail + ['-o', str(output)]
        result = captured(command, environment, 60)
        save(paths, 'compile-' + variant + '.json', {'argv': command, 'returncode': result.returncode,
             'stdout': result.stdout.decode('utf-8', 'replace'), 'stderr': result.stderr.decode('utf-8', 'replace')})
        if result.returncode:
            raise RuntimeError('Strict ' + variant + ' compile failed; see evidence')
        data = output.read_bytes()
        if data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', data, 18)[0] != 62:
            raise RuntimeError('Static ELF64 little-endian x86_64 required')
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        if size < 56 or offset + size * count > len(data):
            raise RuntimeError('Invalid ELF program headers')
        for index in range(count):
            kind, flags = struct.unpack_from('<II', data, offset + size * index)
            if kind in (2, 3) or (kind == 0x6474e551 and flags & 1):
                raise RuntimeError('Dynamic ELF or executable stack rejected')
        result = subprocess.run([str(readelf), '-h', '-l', '-d', str(output)], env=environment,
                                capture_output=True, timeout=30)
        save(paths, 'readelf-' + variant + '.log.txt', result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError('ELF inspection failed')
        report['binaries'][variant] = {'path': str(output), 'sha256': sha(output),
            'bytes': len(data), 'defines': ['ORCHESTELLA_PROFILECTL_PATH'] +
            (['DROIDLAYER_TEST_AF_INET'] if variant == 'tcp' else []),
            'production_binary': False}
    report['source_sha256_after_build'] = sha(source)
    if report['source_sha256_after_build'] != source_sha:
        raise RuntimeError('Source changed during build; fresh test snapshot retained')
    report['status'] = 'READY'
    build_record = evidence / ('static-build-' + stamp + '.json')
    save(paths, build_record.name, report)
    suite_args = ['--work-dir', linux_path(temp), '--evidence-dir', linux_path(evidence),
                  '--preserved-dir', linux_path(preserved), '--build-record', linux_path(build_record)]
    results = {}
    for label, script, extra in [('preflight', tests / 'posix_control_suite.py', suite_args + ['--preflight']),
                                 ('control-suite', tests / 'posix_control_suite.py', suite_args)]:
        command = ['wsl.exe', '-d', args.wsl_distro, '--exec', '/usr/bin/env',
                   'PYTHONDONTWRITEBYTECODE=1', 'TMPDIR=' + linux_path(temp),
                   '/usr/bin/python3', '-B', linux_path(script), *extra]
        result = captured(command, environment, 180)
        results[label] = {'argv': command, 'returncode': result.returncode}
        save(paths, label + '-launch.json', {**results[label], 'stdout': result.stdout.decode('utf-8', 'replace'),
                                            'stderr': result.stderr.decode('utf-8', 'replace')})
        save(paths, label + '.log.txt', result.stdout + result.stderr)
        print(result.stdout.decode('utf-8', 'replace'), end='', flush=True)
        if label == 'preflight' and result.returncode:
            break
    if args.quality_tests and results.get('preflight', {}).get('returncode') == 0:
        config = {'source': str(source), 'source_sha256': source_sha, 'ndk_llvm': str(llvm),
                  'musl_sysroot': str(sysroot), 'temporary': str(temp), 'evidence': str(evidence),
                  'preserved': str(preserved), 'helper': linux_path(temp / 'synthetic-profilectl')}
        config_path = evidence / ('quality-toolchain-' + stamp + '.json')
        save(paths, config_path.name, config)
        command = ['wsl.exe', '-d', args.wsl_distro, '--exec', '/usr/bin/env',
                   'PYTHONDONTWRITEBYTECODE=1', 'TMPDIR=' + linux_path(temp),
                   '/usr/bin/python3', '-B', linux_path(tests / 'posix_quality_contracts.py'),
                   '--config', linux_path(config_path), '--hello-test', linux_path(source.parents[1] / 'scripts/test-guest-agent-hello.py'),
                   '--power-test', linux_path(source.parents[1] / 'scripts/test-guest-agent-power.py')]
        result = captured(command, environment, 180)
        results['quality'] = {'argv': command, 'returncode': result.returncode}
        save(paths, 'quality-launch.json', {**results['quality'], 'stdout': result.stdout.decode('utf-8', 'replace'),
                                          'stderr': result.stderr.decode('utf-8', 'replace')})
        save(paths, 'quality.log.txt', result.stdout + result.stderr)
        print(result.stdout.decode('utf-8', 'replace'), end='', flush=True)
    report['results'] = results
    report['source_unchanged_at_finish'] = sha(source) == source_sha
    report['status'] = 'PASS' if report['source_unchanged_at_finish'] and all(v['returncode'] == 0 for v in results.values()) else 'FAIL'
    save(paths, 'run-summary-' + stamp + '.json', report)
    print(json.dumps({'status': report['status'], 'run_id': stamp, 'evidence': str(evidence),
                      'source_sha256': source_sha}, ensure_ascii=False), flush=True)
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
