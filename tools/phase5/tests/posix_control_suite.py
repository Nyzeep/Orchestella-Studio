"""Actual Linux process validation; only this approved artifact root may be written."""
import argparse
import ctypes
import datetime as dt
import errno
import hashlib
import json
import os
from pathlib import Path
import platform
import sys

ROOT = TEMP = EVIDENCE = PRESERVED = BUILD_RECORD = None

def preserve(name, data):
    if isinstance(data, str):
        data = data.encode('utf-8')
    (EVIDENCE / name).write_bytes(data)
    (PRESERVED / name).write_bytes(data)

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def confine_agent_system_mutations():
    """DrvFS cannot be written under Landlock here; deny all agent system-mutating calls instead."""
    if platform.machine() != 'x86_64':
        raise RuntimeError('x86_64 seccomp syscall mapping required')
    libc = ctypes.CDLL(None, use_errno=True)
    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_uint16), ('jt', ctypes.c_uint8),
                    ('jf', ctypes.c_uint8), ('k', ctypes.c_uint32)]
    class Program(ctypes.Structure):
        _fields_ = [('length', ctypes.c_uint16), ('filters', ctypes.POINTER(Filter))]
    denied = {83: 'mkdir', 258: 'mkdirat', 87: 'unlink', 263: 'unlinkat',
              82: 'rename', 264: 'renameat', 316: 'renameat2', 86: 'link', 265: 'linkat',
              88: 'symlink', 266: 'symlinkat', 90: 'chmod', 91: 'fchmod',
              268: 'fchmodat', 452: 'fchmodat2', 92: 'chown', 93: 'fchown',
              94: 'lchown', 260: 'fchownat', 133: 'mknod', 259: 'mknodat',
              165: 'mount', 166: 'umount2', 155: 'pivot_root', 161: 'chroot'}
    rows = [(0x20, 0, 0, 4), (0x15, 1, 0, 0xc000003e), (0x06, 0, 0, 0x80000000),
            (0x20, 0, 0, 0), (0x35, 0, 1, 0x40000000), (0x06, 0, 0, 0x00050001)]
    for number in denied:
        rows.extend([(0x15, 0, 1, number), (0x06, 0, 0, 0x00050001)])
    # Block AF_UNIX socket creation, hence no /run bridge.sock bind can occur. AF_INET stays available.
    rows.extend([(0x15, 0, 3, 41), (0x20, 0, 0, 16), (0x15, 0, 1, 1),
                 (0x06, 0, 0, 0x00050001), (0x06, 0, 0, 0x7fff0000)])
    filters = (Filter * len(rows))(*(Filter(*row) for row in rows))
    program = Program(len(rows), filters)
    if libc.prctl(38, 1, 0, 0, 0) != 0 or libc.prctl(22, 2, ctypes.byref(program), 0, 0) != 0:
        raise OSError(ctypes.get_errno(), 'process-scoped seccomp setup')
    return {'status': 'ENFORCED', 'type': 'seccomp BPF; PR_SET_NO_NEW_PRIVS',
            'denied_syscalls': denied, 'af_unix_socket_creation': 'DENIED EPERM',
            'file_writes': 'explicit --work-dir/--evidence-dir/--preserved-dir only',
            'scope': 'runner and descendants only; no WSL or user config changes',
            'native_ipc': 'AF_UNIX and system directory setup denied; not a native /run or AF_VSOCK test'}

def preflight():
    information = {'utc': dt.datetime.now(dt.timezone.utc).isoformat(), 'platform': platform.platform(),
                   'python_version': sys.version, 'python_executable': sys.executable,
                   'uid': os.getuid(), 'gid': os.getgid(), 'cwd': os.getcwd(),
                   'tmpdir': os.environ.get('TMPDIR'), 'bytecode_disabled': sys.dont_write_bytecode,
                   'write_confinement': confine_agent_system_mutations(),
                   'runtime': 'existing Ubuntu-24.04, not created or installed by this child',
                   'network': 'tests will use loopback TCP only; no outbound network'}
    preserve('wsl-preflight.json', json.dumps(information, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(information, ensure_ascii=False, indent=2), flush=True)

import selectors
import signal
import socket
import subprocess
import time
import traceback

TOKEN = '0123456789abcdef' * 4
WRONG_TOKEN = 'fedcba9876543210' * 4
GENERATION = '0123456789abcdef' * 2
RUN_ID = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
RESULTS = {'run_id': RUN_ID, 'started_utc': dt.datetime.now(dt.timezone.utc).isoformat(),
           'execution_kind': 'real POSIX fork/exec/poll/waitpid over actual Linux processes',
           'scope': 'synthetic helper; not real profiles/packages/desktops/devices', 'cases': []}


def windows_to_linux(value):
    value = value.replace('\\', '/')
    if len(value) > 2 and value[1:3] == ':/':
        return Path('/mnt/' + value[0].lower() + '/' + value[3:])
    return Path(value)


def save_results():
    data = json.dumps(RESULTS, ensure_ascii=False, indent=2) + '\n'
    preserve('process-results-' + RUN_ID + '.json', data)


class Case:
    def __init__(self, transport, name):
        self.transport = transport
        self.name = name
        self.key = RUN_ID + '-' + transport + '-' + name
        self.issues = []
        self.assertions = 0
        self.helper_deadline_seconds = []
        self.responses = []
        self.events = []
        self.channels = []
        self.started = time.monotonic()

    def event(self, kind, **details):
        record = {'elapsed_seconds': round(time.monotonic() - self.started, 6), 'kind': kind, **details}
        self.events.append(record)
        line = json.dumps(record, ensure_ascii=False, separators=(',', ':')) + '\n'
        for directory in (EVIDENCE, PRESERVED):
            with (directory / ('process-' + self.key + '.log.txt')).open('a', encoding='utf-8') as stream:
                stream.write(line)
                stream.flush()
                os.fsync(stream.fileno())

    def check(self, condition, explanation):
        self.assertions += 1
        if not condition:
            self.issues.append(explanation)
        self.event('assertion', passed=bool(condition), explanation=explanation)

    def fixture_records(self):
        path = EVIDENCE / ('fixture-' + self.key + '.log.txt')
        return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()] if path.exists() else []

    def mode(self, value):
        (TEMP / (self.key + '.mode')).write_text(value, encoding='ascii')
        self.event('fixture-mode', value=value)

    def done(self):
        for channel in self.channels:
            channel.close()
        records = self.fixture_records()
        for record in records:
            self.check(record['fds'].get('0') == '/dev/null' and set(record['fds']) == {'0', '1', '2'},
                       'helper gets /dev/null stdin and only stdio fds')
            self.check(record['sigchld_disposition'] == 0, 'helper inherited SIGCHLD default')
            try:
                os.kill(record['pid'], 0)
                alive = True
            except ProcessLookupError:
                alive = False
            self.check(not alive, 'helper PID has exited/reaped after response and channel close')
        entry = {'name': self.name, 'transport': self.transport, 'case_id': self.key,
                 'status': 'PASS' if not self.issues else 'FAIL', 'assertions': self.assertions,
                 'issues': self.issues, 'elapsed_seconds': round(time.monotonic() - self.started, 6),
                 'helper_invocations': len(records), 'helper_deadline_seconds': self.helper_deadline_seconds,
                 'responses': self.responses, 'raw_evidence': 'process-' + self.key + '.log.txt',
                 'fixture_evidence': ('fixture-' + self.key + '.log.txt') if records else None}
        RESULTS['cases'].append(entry)
        save_results()
        print(self.transport + '/' + self.name + ': ' + entry['status'] +
              (' | ' + '; '.join(self.issues) if self.issues else ''), flush=True)


class Channel:
    def __init__(self, case):
        self.case = case
        self.buffer = b''
        self.sock = None
        self.process = None
        self.selector = None
        self.closed = False
        case.channels.append(self)
        environment = os.environ.copy()
        environment.update(ORCH_POSIX_CASE=case.key, ORCH_POSIX_TEMP=str(TEMP),
                           ORCH_POSIX_EVIDENCE=str(EVIDENCE), ORCH_POSIX_PRESERVED=str(PRESERVED),
                           PYTHONDONTWRITEBYTECODE='1', TMPDIR=str(TEMP))
        argv = [str(windows_to_linux(RESULTS['build']['binaries'][case.transport]['path'])),
                '--token-file', str(TEMP / ('synthetic-token-' + RUN_ID)), '--terminal-device', '/dev/null']
        if case.transport == 'tcp':
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as reservation:
                reservation.bind(('127.0.0.1', 0))
                self.port = reservation.getsockname()[1]
            argv += ['--vsock-control-port', str(self.port)]
        case.event('spawn', argv=argv)
        self.process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, cwd=TEMP, env=environment, start_new_session=True)
        if case.transport == 'tcp':
            deadline = time.monotonic() + 4
            while True:
                try:
                    self.sock = socket.create_connection(('127.0.0.1', self.port), timeout=0.2)
                    break
                except (ConnectionRefusedError, TimeoutError):
                    if time.monotonic() > deadline or self.process.poll() is not None:
                        raise RuntimeError('Test TCP listener did not start')
                    time.sleep(0.02)
            self.sock.setblocking(True)
            ignored = None
            for line in Path('/proc/' + str(self.process.pid) + '/status').read_text().splitlines():
                if line.startswith('SigIgn:'):
                    ignored = int(line.split()[1], 16)
            case.check(ignored is not None and bool(ignored & (1 << (signal.SIGCHLD - 1))),
                       'serve_vsock_mode parent actually ignores SIGCHLD')
        self.selector = selectors.DefaultSelector()
        self.selector.register(self.sock if self.sock is not None else self.process.stdout, selectors.EVENT_READ)

    def send(self, value):
        data = value.encode('ascii') if isinstance(value, str) else value
        if b'/POWER' in data:
            raise RuntimeError('This suite does not run POWER; use the compile-time mock quality suite')
        display = data.decode('ascii', 'replace').replace(TOKEN, '<synthetic-token>').replace(WRONG_TOKEN, '<wrong-synthetic-token>')
        self.case.event('send', text=display)
        if self.sock is not None:
            self.sock.sendall(data)
        else:
            self.process.stdin.write(data)
            self.process.stdin.flush()

    def line(self, timeout=4.5):
        limit = time.monotonic() + timeout
        while b'\n' not in self.buffer:
            remaining = limit - time.monotonic()
            if remaining <= 0 or not self.selector.select(remaining):
                raise TimeoutError('No framed control response before deadline')
            chunk = self.sock.recv(4096) if self.sock is not None else os.read(self.process.stdout.fileno(), 4096)
            if not chunk:
                raise EOFError('Control stream closed')
            self.buffer += chunk
            if len(self.buffer) > 16384:
                raise RuntimeError('Unbounded response stream')
        raw, self.buffer = self.buffer.split(b'\n', 1)
        line = raw.decode('ascii', 'replace').rstrip('\r')
        self.case.responses.append(line)
        self.case.event('receive', text=line)
        return line

    def exact(self, expected):
        actual = self.line()
        self.case.check(actual == expected, 'expected ' + expected + '; received ' + actual)
        return actual

    def hello(self):
        self.send('ORCHESTELLA/HELLO 1 1 ' + TOKEN + '\n')
        self.exact('ORCHESTELLA/READY 1')

    def ping(self):
        self.send('ORCHESTELLA/PING\n')
        self.exact('ORCHESTELLA/PONG 1')

    def error_then_ping(self, request, expected=None):
        self.send(request + '\nORCHESTELLA/PING\n')
        actual = self.line()
        self.case.check(actual == expected if expected else actual.startswith('ORCHESTELLA/ERROR '),
                        'rejected control request remains an ERROR: ' + request)
        self.exact('ORCHESTELLA/PONG 1')
        return actual

    def json_then_ping(self, request, action, profile, arguments=None):
        self.send(request + '\nORCHESTELLA/PING\n')
        actual = self.line()
        prefix = 'ORCHESTELLA/OK features '
        self.case.check(actual.startswith(prefix), 'FEATURES helper success returns OK and JSON')
        if actual.startswith(prefix):
            decoded = json.loads(actual[len(prefix):])
            self.case.check(decoded == {'synthetic': True, 'action': action, 'profile': profile, 'args': arguments or []},
                            'helper argv and JSON match the fixed requested action/profile')
        self.exact('ORCHESTELLA/PONG 1')

    def eof(self, expected_code=None):
        try:
            unexpected = self.line(2)
            self.case.check(False, 'unauthorized channel sent another response: ' + unexpected)
        except (EOFError, ConnectionResetError):
            self.case.check(True, 'unauthorized channel closes or resets before queued FEATURES execution')
        if self.sock is None and expected_code is not None:
            self.case.check(self.process.wait(timeout=2) == expected_code, 'expected initial handshake exit code ' + str(expected_code))

    def close(self):
        if self.closed:
            return
        self.closed = True
        if self.sock is not None:
            self.sock.close()
        if self.process is None:
            return
        try:
            self.process.stdin.close()
        except (BrokenPipeError, ValueError):
            pass
        if self.sock is None:
            try:
                self.process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                pass
        if self.process.poll() is None:
            os.killpg(self.process.pid, signal.SIGTERM)
            try:
                self.process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid, signal.SIGKILL)
                self.process.wait(timeout=1)
        stderr = self.process.stderr.read().decode('utf-8', 'replace')
        self.case.event('process-exit', returncode=self.process.returncode, stderr=stderr,
                        termination='server teardown' if self.sock is not None else 'stdin EOF')
        if self.selector is not None:
            self.selector.close()
        self.process.stdout.close()
        self.process.stderr.close()


def exercise(case):
    name = case.name
    mode = name.removeprefix('helper-') if name.startswith('helper-') else 'success'
    if name in ('display-nonzero', 'display-json-nonzero', 'confirm-nonzero', 'confirm-delay'):
        mode = name.split('-', 1)[1]
    case.mode(mode)
    channel = Channel(case)
    if name in ('wrong-token', 'unauth-features'):
        if name == 'wrong-token':
            channel.send('ORCHESTELLA/HELLO 1 1 ' + WRONG_TOKEN + '\nORCHESTELLA/FEATURES 1 INSTALL podman\n')
            channel.exact('ORCHESTELLA/ERROR unauthorized')
            channel.eof(3)
        else:
            channel.send('ORCHESTELLA/FEATURES 1 INSTALL podman\nORCHESTELLA/HELLO 1 1 ' + TOKEN + '\n')
            channel.exact('ORCHESTELLA/ERROR malformed-handshake')
            channel.eof(1)
        case.check(not case.fixture_records(), 'helper never executes before valid token HELLO')
        return
    if name == 'sole-reader':
        channel.send('ORCHESTELLA/HELLO 1 1 ' + TOKEN + '\nORCHESTELLA/FEATURES 1 PROBE podman\nORCHESTELLA/PING\n'
                     'ORCHESTELLA/FEATURES 1 STATUS docker-cli\nORCHESTELLA/PING\n')
        channel.exact('ORCHESTELLA/READY 1')
        for action, profile in [('probe', 'podman'), ('status', 'docker-cli')]:
            line = channel.line()
            case.check(line.startswith('ORCHESTELLA/OK features '), 'pipelined FEATURES preserved after HELLO')
            if line.startswith('ORCHESTELLA/OK features '):
                case.check(json.loads(line.split(' ', 2)[2])['action'] == action, 'pipelined reply ordering preserved')
            channel.exact('ORCHESTELLA/PONG 1')
        case.check(len(case.fixture_records()) == 2, 'exactly two helper executions; no extra reader consumes bytes')
        return
    channel.hello()
    if name == 'features-success':
        for action, profile in [('PROBE', 'podman'), ('INSTALL', 'desktop-labwc'), ('STATUS', 'code-server')]:
            channel.json_then_ping('ORCHESTELLA/FEATURES 1 ' + action + ' ' + profile, action.lower(), profile)
    elif name == 'allowed-actions':
        for profile in ('desktop-labwc', 'podman', 'code-server', 'docker-cli'):
            channel.json_then_ping('ORCHESTELLA/FEATURES 1 PROBE ' + profile, 'probe', profile)
        for action in ('START', 'STOP', 'CANCEL'):
            channel.json_then_ping('ORCHESTELLA/FEATURES 1 ' + action + ' podman', action.lower(), 'podman')
        for action in ('EXPOSE', 'UNEXPOSE'):
            channel.json_then_ping('ORCHESTELLA/FEATURES 1 ' + action + ' code-server', action.lower(), 'code-server')
    elif name == 'whitelist-reject':
        for request in ('ORCHESTELLA/FEATURES 1 EXEC podman', 'ORCHESTELLA/FEATURES 1 PROBE /bin/sh',
                        'ORCHESTELLA/FEATURES 1 PROBE podman;touch', 'ORCHESTELLA/FEATURES 1 PROBE $(id)',
                        'ORCHESTELLA/FEATURES 1 PROBE podman ignored', 'ORCHESTELLA/FEATURES 1 RESIZE desktop-labwc',
                        'ORCHESTELLA/FEATURES 1 EXPOSE podman', 'ORCHESTELLA/FEATURES 1 UNEXPOSE docker-cli',
                        'ORCHESTELLA/EXEC /bin/sh'):
            channel.error_then_ping(request)
        case.check(not case.fixture_records(), 'whitelist rejects arbitrary action/profile/shell text without invoking helper')
    elif name == 'versions-and-display-reject':
        for request in ('ORCHESTELLA/FEATURES 0 PROBE podman', 'ORCHESTELLA/FEATURES 2 STATUS code-server'):
            channel.error_then_ping(request, 'ORCHESTELLA/ERROR unsupported-feature-version')
        for request in ('ORCHESTELLA/DISPLAY UNKNOWN', 'DROIDLAYER/DISPLAY UNKNOWN',
                        'ORCHESTELLA/DISPLAY MODE 99 600 1.0', 'ORCHESTELLA/DISPLAY MODE 4096 4096 1.0',
                        'ORCHESTELLA/DISPLAY MODE 800 600 nan', 'ORCHESTELLA/DISPLAY MODE 800 600 9.0',
                        'ORCHESTELLA/DISPLAY LAUNCH ignored', 'ORCHESTELLA/DISPLAY FPS 60'):
            channel.error_then_ping(request)
        case.check(not case.fixture_records(), 'unsupported version/display args never invoke helper')
    elif name.startswith('display-'):
        requests = [('ORCHESTELLA/DISPLAY LAUNCH', ['start', 'desktop-labwc']),
                    ('ORCHESTELLA/DISPLAY MODE 800 600 1.25', ['resize', 'desktop-labwc', '800', '600', '1.250']),
                    ('DROIDLAYER/DISPLAY RESIZE 640 480', ['resize', 'desktop-labwc', '640', '480', '1.000'])]
        for request, expected_args in requests:
            channel.send(request + '\nORCHESTELLA/PING\n')
            actual = channel.line()
            case.check(actual == 'ORCHESTELLA/OK display' if mode == 'success' else actual.startswith('ORCHESTELLA/ERROR display-'),
                       'DISPLAY uses real helper exit status, not unconditional OK')
            channel.exact('ORCHESTELLA/PONG 1')
            case.check(case.fixture_records()[-1]['argv'] == expected_args, 'DISPLAY dispatch receives normalized fixed helper argv')
    elif name == 'confirm-success':
        channel.json_then_ping('ORCHESTELLA/FEATURES 1 CONFIRM-FRAME desktop-labwc ' + GENERATION,
                               'confirm-frame', 'desktop-labwc', [GENERATION])
    elif name == 'confirm-reject':
        requests = ['ORCHESTELLA/FEATURES 1 CONFIRM-FRAME desktop-labwc ' + GENERATION.upper(),
                    'ORCHESTELLA/FEATURES 1 CONFIRM-FRAME desktop-labwc ' + GENERATION[:-1],
                    'ORCHESTELLA/FEATURES 1 CONFIRM-FRAME desktop-labwc ' + GENERATION + '0',
                    'ORCHESTELLA/FEATURES 1 CONFIRM-FRAME desktop-labwc ' + GENERATION + ' ignored',
                    'ORCHESTELLA/FEATURES 1 CONFIRM-FRAME podman ' + GENERATION,
                    'ORCHESTELLA/FEATURES 2 CONFIRM-FRAME desktop-labwc ' + GENERATION,
                    'ORCHESTELLA/FEATURES 1 CONFIRM-FRAME desktop-labwc',
                    'ORCHESTELLA/FEATURES 1 CONFIRM-FRAME podman']
        for request in requests:
            before = len(case.fixture_records())
            channel.error_then_ping(request)
            case.check(len(case.fixture_records()) == before, 'invalid confirm generation/profile rejected before helper dispatch: ' + request)
    elif name in ('confirm-nonzero', 'confirm-delay') or name.startswith('helper-'):
        request = ('ORCHESTELLA/FEATURES 1 CONFIRM-FRAME desktop-labwc ' + GENERATION
                   if name.startswith('confirm-') else 'ORCHESTELLA/FEATURES 1 PROBE podman')
        started = time.monotonic()
        channel.send(request + '\nORCHESTELLA/PING\nORCHESTELLA/PING\nORCHESTELLA/PING\n')
        expected = 'ORCHESTELLA/ERROR display-confirm-unavailable' if name.startswith('confirm-') else 'ORCHESTELLA/ERROR features-unavailable'
        channel.exact(expected)
        elapsed = time.monotonic() - started
        case.helper_deadline_seconds.append(round(elapsed, 6))
        case.check(elapsed <= 3.8, 'helper failure/deadline response is bounded to <=3.8 seconds including launch')
        for _ in range(3):
            channel.exact('ORCHESTELLA/PONG 1')
        case.check(len(case.fixture_records()) == 1, 'failed/timeout helper actually executed once')
        if mode in ('delay', 'stdout-close-hang'):
            case.check(elapsed >= 1.8, 'real delayed helper reached agent deadline, not immediate exec failure')
        case.mode('success')
        channel.json_then_ping('ORCHESTELLA/FEATURES 1 STATUS podman', 'status', 'podman')
    elif name == 'fragmented-input':
        channel.send('ORCHESTELLA/FEA')
        time.sleep(0.03)
        channel.send('TURES 1 PROBE podman\r\nORCHESTELLA/PI')
        time.sleep(0.03)
        channel.send('NG\r\n')
        line = channel.line()
        case.check(line.startswith('ORCHESTELLA/OK features '), 'fragmented CRLF FEATURES produces one success')
        channel.exact('ORCHESTELLA/PONG 1')
        case.check(len(case.fixture_records()) == 1, 'fragmented request executed exactly once')
    elif name == 'repeated-hello':
        channel.hello()
        channel.json_then_ping('ORCHESTELLA/FEATURES 1 STATUS docker-cli', 'status', 'docker-cli')
    else:
        raise RuntimeError('Unknown case ' + name)


def run_suite():
    build = json.loads(BUILD_RECORD.read_text(encoding='utf-8'))
    if build['status'] != 'READY':
        raise RuntimeError('Static builds not ready')
    RESULTS['build'] = {key: build[key] for key in ('binaries', 'snapshot_sha256', 'snapshot', 'source_sha256_after_build')}
    RESULTS['runner_sha256'] = sha(__file__)
    RESULTS['fixture_sha256'] = sha(TEMP / 'synthetic-profilectl')
    RESULTS['environment'] = {'kernel': platform.release(), 'python': sys.version, 'uid': os.getuid(),
                               'runtime': 'existing Ubuntu-24.04', 'tmpdir': os.environ.get('TMPDIR')}
    for variant in ('stdin', 'tcp'):
        binary = windows_to_linux(build['binaries'][variant]['path'])
        if sha(binary) != build['binaries'][variant]['sha256']:
            raise RuntimeError('Built binary hash changed before execution')
        binary.chmod(0o755)
    (TEMP / 'synthetic-profilectl').chmod(0o755)
    (TEMP / ('synthetic-token-' + RUN_ID)).write_text(TOKEN + '\n', encoding='ascii')
    RESULTS['system_mutation_filter'] = confine_agent_system_mutations()
    RESULTS['status'] = 'RUNNING'
    save_results()
    names = ['unauth-features', 'wrong-token', 'features-success', 'allowed-actions', 'whitelist-reject',
             'versions-and-display-reject', 'display-success', 'display-nonzero', 'display-json-nonzero',
             'confirm-success', 'confirm-reject', 'confirm-nonzero', 'confirm-delay',
             'helper-empty', 'helper-malformed', 'helper-multiline', 'helper-nonzero', 'helper-json-nonzero',
             'helper-oversize', 'helper-delay', 'helper-stdout-close-hang', 'helper-signal',
             'sole-reader', 'fragmented-input', 'repeated-hello']
    for transport in ('stdin', 'tcp'):
        for name in names:
            case = Case(transport, name)
            try:
                exercise(case)
            except BaseException as error:
                case.issues.append(type(error).__name__ + ': ' + str(error))
                case.event('exception', error=type(error).__name__ + ': ' + str(error), traceback=traceback.format_exc())
            finally:
                case.done()
    RESULTS['transport_equivalence'] = []
    for name in names:
        paired = [case for case in RESULTS['cases'] if case['name'] == name]
        equivalent = paired[0]['responses'] == paired[1]['responses'] and paired[0]['helper_invocations'] == paired[1]['helper_invocations']
        RESULTS['transport_equivalence'].append({'case': name, 'equivalent': equivalent})
    RESULTS['passed'] = sum(case['status'] == 'PASS' for case in RESULTS['cases'])
    RESULTS['failed'] = sum(case['status'] == 'FAIL' for case in RESULTS['cases'])
    RESULTS['test_count'] = len(RESULTS['cases'])
    RESULTS['assertion_count'] = sum(case['assertions'] for case in RESULTS['cases'])
    RESULTS['helper_invocations'] = sum(case['helper_invocations'] for case in RESULTS['cases'])
    RESULTS['status'] = 'PASS' if not RESULTS['failed'] and all(row['equivalent'] for row in RESULTS['transport_equivalence']) else 'FAIL'
    RESULTS['finished_utc'] = dt.datetime.now(dt.timezone.utc).isoformat()
    save_results()
    print('TOTAL ' + str(RESULTS['test_count']) + ': PASS=' + str(RESULTS['passed']) + ' FAIL=' + str(RESULTS['failed']) +
          ' assertions=' + str(RESULTS['assertion_count']) + ' helpers=' + str(RESULTS['helper_invocations']), flush=True)
    return 0 if RESULTS['status'] == 'PASS' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--work-dir', required=True, type=Path)
    parser.add_argument('--evidence-dir', required=True, type=Path)
    parser.add_argument('--preserved-dir', required=True, type=Path)
    parser.add_argument('--build-record', required=True, type=Path)
    parser.add_argument('--preflight', action='store_true')
    options = parser.parse_args()
    TEMP, EVIDENCE, PRESERVED = (p.resolve() for p in
        (options.work_dir, options.evidence_dir, options.preserved_dir))
    ROOT = TEMP.parent.parent
    BUILD_RECORD = options.build_record.resolve()
    if not all(p.is_dir() for p in (TEMP, EVIDENCE, PRESERVED)):
        raise SystemExit('Explicit output directories must already exist')
    if options.preflight:
        preflight()
    else:
        raise SystemExit(run_suite())
