#!/usr/bin/env python3
"""Run existing quality scripts with a strict Windows cross-compiler bridge.

Script expectations are executed intact. Only assertions are counted, and child
agents inherit process-local seccomp to prevent /run IPC setup on the WSL host.
The POWER script may execute only binaries compiled with ORCHESTELLA_TEST_POWER.
"""
import argparse
import ast
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import traceback


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def linux_path(value):
    value = str(value).replace('\\', '/')
    if len(value) > 2 and value[1:3] == ':/':
        return Path('/mnt/' + value[0].lower() + '/' + value[3:])
    return Path(value)


def windows_path(value):
    path = str(value)
    if path.startswith('/mnt/') and len(path) > 7 and path[6] == '/':
        return path[5].upper() + ':/' + path[7:]
    raise ValueError('Explicit mounted Windows path required: ' + path)


def preserve(config, name, value):
    data = (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    for key in ('evidence', 'preserved'):
        with (linux_path(config[key]) / name).open('xb') as stream:
            stream.write(data)


def cross_compile(config, arguments):
    allowed = {'-std=c11', '-Os', '-Wall', '-Wextra', '-Werror', '-DORCHESTELLA_TEST_POWER'}
    source = linux_path(config['source']).resolve()
    output = None
    flags = []
    helper = config['helper']
    actual_sources = []
    index = 0
    while index < len(arguments):
        value = arguments[index]
        if value == '-o' and index + 1 < len(arguments):
            output = Path(arguments[index + 1]).resolve()
            index += 2
            continue
        if value in allowed:
            flags.append(value)
        elif value.startswith('-DORCHESTELLA_PROFILECTL_PATH="') and value.endswith('"'):
            helper = value[len('-DORCHESTELLA_PROFILECTL_PATH="'):-1]
            candidate = linux_path(helper).resolve()
            if not candidate.is_file() or not candidate.is_relative_to(linux_path(config['temporary']).resolve()):
                raise ValueError('Synthetic helper must be inside explicit temporary output')
            helper = str(candidate)
        elif Path(value).resolve() == source:
            actual_sources.append(value)
        else:
            raise ValueError('Unexpected compile argument')
        index += 1
    temporary = linux_path(config['temporary']).resolve()
    if len(actual_sources) != 1 or output is None or not output.is_relative_to(temporary):
        raise ValueError('Quality compiler source/output scope mismatch')
    if sha(source) != config['source_sha256']:
        raise ValueError('Source changed before quality compile')
    llvm = str(config['ndk_llvm']).replace('\\', '/')
    sysroot = str(config['musl_sysroot']).replace('\\', '/')
    library = sysroot + '/usr/lib/'
    command = [str(linux_path(llvm + '/bin/clang.exe')), '--target=x86_64-linux-musl',
               '--sysroot=' + sysroot, '-static', '-fuse-ld=lld', '-nostdinc',
               '-isystem', sysroot + '/usr/include', '-fno-ident', '-Wl,--build-id=none',
               '-nostdlib', '-Wl,-e,_start', *flags,
               '-DORCHESTELLA_PROFILECTL_PATH="' + helper + '"',
               library + 'crt1.o', library + 'crti.o', windows_path(source),
               '-Wl,--start-group', library + 'libc.a',
               llvm + '/lib/clang/18/lib/linux/libclang_rt.builtins-x86_64-android.a',
               '-Wl,--end-group', library + 'crtn.o', '-o', windows_path(output)]
    environment = os.environ.copy()
    environment.update(TMP=windows_path(temporary), TEMP=windows_path(temporary),
                       TMPDIR=windows_path(temporary), PYTHONDONTWRITEBYTECODE='1')
    result = subprocess.run(command, env=environment, capture_output=True, timeout=60)
    key = hashlib.sha256(str(output).encode('utf-8')).hexdigest()[:20]
    record = {'argv': command, 'source_sha256': sha(source), 'returncode': result.returncode,
              'flags': flags, 'output': str(output),
              'stdout': result.stdout.decode('utf-8', 'replace'),
              'stderr': result.stderr.decode('utf-8', 'replace')}
    if result.returncode == 0:
        output.chmod(0o755)
        record['binary_sha256'] = sha(output)
    preserve(config, 'quality-compile-' + key + '.json', record)
    sys.stdout.buffer.write(result.stdout)
    sys.stderr.buffer.write(result.stderr)
    return result.returncode


class CountAssertions(ast.NodeTransformer):
    def visit_Assert(self, node):
        return [ast.copy_location(ast.Expr(ast.Call(ast.Name('_count_assertion', ast.Load()), [], [])), node), node]


def run_quality(config, scripts):
    if platform.machine() != 'x86_64':
        raise RuntimeError('Existing x86_64 WSL required')
    from posix_control_suite import confine_agent_system_mutations
    temp = linux_path(config['temporary']).resolve()
    config_path = os.environ['ORCH_QUALITY_CONFIG']
    wrapper = temp / 'cc-wrapper.py'
    wrapper.write_text('#!/usr/bin/python3 -B\nimport os, runpy, sys\n'
                       + 'sys.argv = [' + repr(str(Path(__file__).resolve()))
                       + ', "--config", os.environ["ORCH_QUALITY_CONFIG"], "--cc-compile", *sys.argv[1:]]\n'
                       + 'runpy.run_path(sys.argv[0], run_name="__main__")\n', encoding='utf-8')
    wrapper.chmod(0o755)
    results = []
    real_run = subprocess.run
    for family, script in scripts:
        output_dir = temp / ('quality-' + family)
        output_dir.mkdir()
        counts = {'assertions': 0, 'executed_cases': 0}
        namespace = {'__name__': '__main__', '__file__': str(script),
                     '_count_assertion': lambda: counts.__setitem__('assertions', counts['assertions'] + 1)}
        def checked_run(command, *arguments, **keywords):
            executable = Path(command[0]).resolve()
            if executable != wrapper and executable.is_relative_to(output_dir):
                key = hashlib.sha256(str(executable).encode('utf-8')).hexdigest()[:20]
                record = json.loads((linux_path(config['evidence']) / ('quality-compile-' + key + '.json')).read_text())
                if record['returncode'] or sha(executable) != record['binary_sha256']:
                    raise RuntimeError('Fresh compiled agent identity mismatch')
                wire = keywords.get('input', '')
                power_requested = '/POWER' in (wire.decode('ascii', 'replace') if isinstance(wire, bytes) else wire)
                if (family == 'power' or power_requested) and '-DORCHESTELLA_TEST_POWER' not in record['flags']:
                    raise RuntimeError('POWER may only execute compile-time mock binaries')
                counts['executed_cases'] += 1
                keywords['preexec_fn'] = confine_agent_system_mutations
                keywords['start_new_session'] = True
            return real_run(command, *arguments, **keywords)
        old_environment = os.environ.copy()
        os.environ.update(CC=str(wrapper), GUEST_RUNTIME_TEST_ROOT=str(output_dir),
                          PYTHONDONTWRITEBYTECODE='1', TMPDIR=str(temp),
                          ORCH_QUALITY_CONFIG=config_path)
        stdout, stderr = io.StringIO(), io.StringIO()
        error = None
        try:
            subprocess.run = checked_run
            tree = CountAssertions().visit(ast.parse(script.read_text(encoding='utf-8'), filename=str(script)))
            ast.fix_missing_locations(tree)
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                exec(compile(tree, str(script), 'exec'), namespace)
        except BaseException:
            error = traceback.format_exc()
        finally:
            subprocess.run = real_run
            os.environ.clear()
            os.environ.update(old_environment)
        passed = len(namespace.get('checks', []))
        record = {'family': family, 'script': str(script), 'script_sha256': sha(script),
                  'source_sha256': config['source_sha256'], 'status': 'FAIL' if error else 'PASS',
                  **counts, 'passed_cases': passed, 'failed_cases': counts['executed_cases'] - passed,
                  'stdout': stdout.getvalue(), 'stderr': stderr.getvalue(), 'error': error,
                  'expectations_changed_by_runner': False, 'assertion_counter_only': True,
                  'power_execution': 'compile-time-mock-only', 'host_power_commands_executed': 0,
                  'ARM64_guest_execution': 'NOT_RUN'}
        preserve(config, 'quality-' + family + '-results.json', record)
        for artifact in output_dir.iterdir():
            if artifact.is_file() and artifact.name.endswith('.txt'):
                for location in ('evidence', 'preserved'):
                    target = linux_path(config[location]) / ('quality-' + family + '-' + artifact.name)
                    with target.open('xb') as stream:
                        stream.write(artifact.read_bytes())
        results.append(record)
        print(json.dumps({k: record[k] for k in ('family', 'status', 'executed_cases', 'passed_cases',
                                                'failed_cases', 'assertions')}, ensure_ascii=False), flush=True)
        if error:
            print(error, flush=True)
    preserve(config, 'quality-contract-summary.json', results)
    return 0 if all(row['status'] == 'PASS' for row in results) else 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('--cc-compile', nargs=argparse.REMAINDER)
    parser.add_argument('--hello-test', type=Path)
    parser.add_argument('--power-test', type=Path)
    options = parser.parse_args()
    config = json.loads(options.config.read_text(encoding='utf-8'))
    if options.cc_compile is not None:
        return cross_compile(config, options.cc_compile)
    if options.hello_test is None or options.power_test is None:
        parser.error('Explicit existing HELLO and POWER script paths required')
    os.environ['ORCH_QUALITY_CONFIG'] = str(options.config.resolve())
    return run_quality(config, [('hello', options.hello_test.resolve()), ('power', options.power_test.resolve())])


if __name__ == '__main__':
    raise SystemExit(main())
