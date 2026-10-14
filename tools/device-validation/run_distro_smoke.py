#!/usr/bin/env python3
"""Finite, offline-host QA orchestration. Only the main session may run against adb.

The guest's explicit baseline/DNS probes use its normal outbound NAT. No LAN forwards,
services API, app lifecycle commands, or root disk uploads. Explicit failed-QA resume
requires original ownership evidence; the default creates a fresh instance directory.
"""
import argparse
import codecs
import concurrent.futures
import hashlib
import ipaddress
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import select
import shlex
import signal
import socket
import subprocess
import threading
import time
import uuid
import zipfile

APPROVED = Path("D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/quality-20261013/device-smoke")
DISTROS = {"ubuntu", "debian", "fedora", "arch", "opensuse", "almalinux", "rocky"}
RAW_BYTES = 8589934592
MARKER = "ORCHESTELLA_EMPTY_ROOT_VOLUME_V2\n"
MIB = 1024 * 1024
PKG = {"ubuntu": "apt-get", "debian": "apt-get", "fedora": "dnf", "arch": "pacman", "opensuse": "zypper", "almalinux": "dnf", "rocky": "dnf"}
DNS = {"ubuntu": "ports.ubuntu.com", "debian": "deb.debian.org", "fedora": "download.fedoraproject.org", "arch": "ca.us.mirror.archlinuxarm.org", "opensuse": "download.opensuse.org", "almalinux": "repo.almalinux.org", "rocky": "dl.rockylinux.org"}
STOP = threading.Event()
PROGRESS_COMMAND = ("printf '\\nQA_PROGRESS_DIAG\\n'; cat /proc/1/comm; "
                    "cat /var/lib/orchestella/provision/state; "
                    "tail -n20 /var/log/orchestella-provision.log.txt; "
                    "printf '\\nQA_PROGRESS_DIAG_END\\n'\r")
RECOVERY_DIAGNOSTIC_COMMAND = ("printf '\\nQA_RECOVERY_DIAG\\n'; cat /proc/1/comm; "
                               "cat /var/lib/orchestella/provision/state; "
                               "tail -n40 /var/log/orchestella-provision.log.txt; "
                               "journalctl --no-pager -b -1 -n60; cat /etc/fstab; "
                               "printf '\\nQA_RECOVERY_DIAG_END\\n'\r")
RECOVERY_RETRY_COMMAND = ("printf '\\nQA_RECOVERY_DIAG\\n'; "
                          "orchestella-guest-provision --retry; qa_status=$?; "
                          "printf '\\nQA_RECOVERY_RETRY_RC=%s\\nQA_RECOVERY_STATE_BEGIN\\n' \"$qa_status\"; "
                          "cat /var/lib/orchestella/provision/state; "
                          "printf '\\nQA_RECOVERY_STATE_END\\n'; "
                          "tail -n40 /var/log/orchestella-provision.log.txt; "
                          "journalctl --no-pager -b -1 -n60; cat /etc/fstab; "
                          "printf '\\nQA_RECOVERY_DIAG_END rc=%s\\n' \"$qa_status\"\r")
SHELL_PROMPT_MARKER = re.compile(r'\x1b\]133;([BC])(?:;[^\x07\x1b]*)?(?:\x07|\x1b\\)')


class GateError(Exception):
    pass


def need(condition, reason):
    if not condition:
        raise GateError(reason)


def sha_file(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def safe_file(root, name):
    need(isinstance(name, str) and "\\" not in name, "invalid-relative-file")
    p = PurePosixPath(name)
    need(not p.is_absolute() and all(x not in (".", "..") for x in name.split("/")), "invalid-relative-file")
    path = root.joinpath(*p.parts)
    for parent in [path, *path.parents]:
        if parent == root.parent:
            break
        need(not parent.is_symlink(), "symlink-file")
    need(path.is_file() and path.resolve().is_relative_to(root), "missing-or-outside-file")
    return path


def redact(text, secrets):
    for value in secrets:
        text = text.replace(value, "<redacted>")
    text = re.sub(r"(?i)((?:bridge[._-]?token|guest[._-]?password|password|token)\s*[=:]\s*)[^\s]+", r"\1<redacted>", text)
    return text


def load_batch(args):
    root = Path(args.plan).resolve().parent
    approved = APPROVED.resolve(strict=True)
    need(root.is_relative_to(approved), "plan-outside-approved-root")
    plan_file = safe_file(root, Path(args.plan).name)
    public = plan_file.read_text(encoding="utf-8")
    plan = json.loads(public)
    need(plan.get("schemaVersion") == 1 and isinstance(plan.get("instances"), list), "plan-schema")
    apk = Path(args.apk).resolve(strict=True)
    need(re.fullmatch(r"[0-9a-f]{64}", args.apk_sha256) is not None, "explicit-apk-hash-required")
    need(sha_file(apk) == args.apk_sha256 == plan["apk"]["sha256"] and apk.stat().st_size == plan["apk"]["bytes"], "apk-hash-size-mismatch")
    with zipfile.ZipFile(apk) as z:
        names = [e.filename for e in z.infolist()]
        required = {"Image", "initramfs.cpio.gz", "QEMU-BUILD-INPUTS", "QEMU-GPL-SOURCES.md", "LICENSE.GPL-2.0", "NOTICE.QEMU", "lib/arm64-v8a/libdroidlayer_qemu.so"}
        def location(name):
            return name if name.startswith("lib/") or name == "initramfs.cpio.gz" else "assets/" + name
        trust = {}
        manifest_path = "assets/runtime-artifacts-v1.sha256"
        need(names.count(manifest_path) == 1, "apk-manifest-entry")
        for line in z.read(manifest_path).decode("ascii").splitlines():
            match = re.fullmatch(r"([0-9a-f]{64})  ([^\r\n]+)", line)
            need(match is not None, "apk-manifest-schema")
            digest, name = match.groups()
            need(name not in trust and not name.startswith("/") and "\\" not in name and all(s not in ("", ".", "..") for s in name.split("/")), "apk-manifest-path")
            need(name != "runtime-artifacts-v1.sha256", "apk-manifest-self-reference")
            trust[name] = digest
        need(required <= trust.keys(), "apk-manifest-incomplete")
        for name in required:
            entry = location(name)
            need(names.count(entry) == 1, "apk-artifact-entry")
            with z.open(entry) as stream:
                need(hashlib.file_digest(stream, "sha256").hexdigest() == trust[name], "apk-artifact-hash")
        for name in [manifest_path, "assets/Image", "initramfs.cpio.gz", "guest_init", "proxy_relay"]:
            need(names.count(name) == 1 and name in plan["packagedEntries"], "apk-metadata-entry")
            info = plan["packagedEntries"][name]
            with z.open(name) as stream:
                need(hashlib.file_digest(stream, "sha256").hexdigest() == info["sha256"] and z.getinfo(name).file_size == info["bytes"], "apk-metadata-mismatch")
        gv = "lib/arm64-v8a/libgvproxy.so"
        need(names.count(gv) == 1, "apk-gvproxy-entry")
        with z.open(gv) as stream:
            native_hashes = {"libgvproxy.so": hashlib.file_digest(stream, "sha256").hexdigest(), "libdroidlayer_qemu.so": trust["lib/arm64-v8a/libdroidlayer_qemu.so"]}
    need(plan.get("privateCredentials") == "private/credentials.json", "private-credentials-path")
    credentials = json.loads(safe_file(root, plan["privateCredentials"]).read_text(encoding="utf-8"))
    need(credentials.get("schemaVersion") == 1, "credentials-schema")
    secret_rows = credentials["instances"]
    need(len({c["instanceId"] for c in secret_rows}) == len(secret_rows), "duplicate-private-instance")
    private = {c["instanceId"]: c for c in secret_rows}
    secrets = []
    ids, phone_dirs, sockets = set(), set(), set()
    rows = []
    for row in plan["instances"]:
        distro, instance = row["distro"], row["instanceId"]
        need(distro in DISTROS and distro not in ids and str(uuid.UUID(instance)) == instance, "distro-instance-identity")
        ids.add(distro)
        c = private.get(instance)
        need(c is not None and c["distro"] == distro and c["username"] == "root", "private-instance-mismatch")
        need(re.fullmatch(r"[0-9a-f]{64}", c["bridgeToken"]) is not None and re.fullmatch(r"[A-Za-z0-9]{24}", c["guestPassword"]) is not None, "private-credential-format")
        secrets += [c["bridgeToken"], c["guestPassword"]]
        expected = "files/quality-smoke/" + distro + "-" + instance[:8]
        need(row["relativePhoneDir"] == expected and expected not in phone_dirs, "phone-directory-scope")
        phone_dirs.add(expected)
        for field in ["controlSocket", "terminalSocket", "gatewayId"]:
            value = row[field]
            need(re.fullmatch(r"quality-smoke-[a-z-]+-" + re.escape(distro + "-" + instance[:8]), value) is not None and value not in sockets and len(value) < 100, "socket-scope")
            sockets.add(value)
        need(row["cpuCores"] == 2 and row["gpuAccelerationEnabled"] is False and row["baselineMandatory"] is True and row["autoUpdate"] is False and row["mirrorSource"] == "OFFICIAL", "hardware-provision-metadata")
        need(row["rawBytes"] == RAW_BYTES and row["marker"] == MARKER and row["phoneRootVolume"] == "root-volume.raw" and row["pushHostRaw"] is False, "root-volume-metadata")
        for kind, name in [("kernel", "Image"), ("initramfs", "initramfs-embedded.cpio.gz"), ("baseInitramfs", "initramfs.cpio.gz")]:
            info = row[kind]
            need(info["path"] == f"{distro}/runtime/{name}", "runtime-path")
            artifact = safe_file(root, info["path"])
            need(artifact.stat().st_size == info["bytes"] and sha_file(artifact) == info["sha256"], "runtime-hash-size")
        need(row["kernel"]["sha256"] == trust["Image"] and row["baseInitramfs"]["sha256"] == trust["initramfs.cpio.gz"], "runtime-trust-anchor")
        minimum = max(1024, math.ceil(2 * row["initramfs"]["bytes"] / MIB) + 512)
        need(row.get("memoryMinimumMegabytes", minimum) == minimum and minimum <= row["ramMegabytes"] <= 4096, "ram-too-small-reprepare-required")
        need(distro != "arch" or row["ramMegabytes"] >= 3072, "arch-ram-reprepare-required")
        row = dict(row, _credentials=c)
        rows.append(row)
    need(len(private) == len(rows) and len(set(secrets)) == len(secrets), "credential-set-mismatch")
    need(all(s not in public for s in secrets) and "bridgeToken" not in public and "guestPassword" not in public, "public-secret-leak")
    selected = args.distros.split(",") if args.distros else [r["distro"] for r in rows]
    need(len(selected) == len(set(selected)) and set(selected) <= ids and selected, "selected-distros")
    return root, plan, [r for r in rows if r["distro"] in selected], secrets, native_hashes


def load_resume(args, plan, rows):
    old_plan_arg, old_results_arg = getattr(args, 'resume_plan', None), getattr(args, 'resume_results', None)
    need(bool(old_plan_arg) == bool(old_results_arg), 'resume-requires-plan-and-results')
    assembly_resume = getattr(args, 'resume_assembly', False)
    recovery = getattr(args, 'recovery_diagnostics', False)
    recovery_retry = getattr(args, 'recovery_retry', False)
    need(not recovery_retry or recovery, 'recovery-retry-requires-recovery-diagnostics')
    if recovery:
        need(bool(old_plan_arg) and bool(old_results_arg) and getattr(args, 'distros', None) == 'opensuse'
             and len(rows) == 1 and rows[0]['distro'] == 'opensuse' and not assembly_resume,
             'recovery-diagnostics-requires-opensuse-failed-resume')
    if not old_plan_arg:
        need(not assembly_resume, 'assembly-resume-requires-explicit-resume')
        need('resumeFromPlanSha256' not in plan, 'rebootstrap-plan-requires-explicit-resume')
        return rows
    need(not assembly_resume or len(rows) == 1 and rows[0]['distro'] == 'arch', 'assembly-resume-arch-only')
    approved = APPROVED.resolve(strict=True)
    old_path = Path(old_plan_arg).absolute()
    old_root = old_path.parent.resolve(strict=True)
    need(old_root.is_relative_to(approved), 'resume-plan-scope')
    old_path = safe_file(old_root, old_path.name)
    old_plan = json.loads(old_path.read_text(encoding='utf-8'))
    need(old_plan.get('schemaVersion') == 1 and old_plan.get('privateCredentials') == 'private/credentials.json', 'resume-old-schema')
    need(plan.get('resumeFromPlanSha256') == sha_file(old_path)
         and plan.get('resumeFromApkSha256') == old_plan['apk']['sha256']
         and re.fullmatch(r'[0-9a-f]{64}', old_plan['apk']['sha256']) is not None, 'resume-source-plan-apk-link')
    old_rows = old_plan['instances']
    old_creds = json.loads(safe_file(old_root, 'private/credentials.json').read_text(encoding='utf-8'))
    need(old_creds.get('schemaVersion') == 1, 'resume-old-credentials-schema')
    need(len({r['distro'] for r in old_rows}) == len(old_rows)
         and len({c['instanceId'] for c in old_creds['instances']}) == len(old_creds['instances']), 'resume-duplicate-identity')
    run_root = Path(old_results_arg).absolute()
    need(run_root.is_dir() and not run_root.is_symlink() and run_root.resolve().is_relative_to(approved)
         and re.fullmatch(r'qa-run-[0-9a-f]{12}', run_root.name) is not None, 'resume-results-scope')
    run_root = run_root.resolve(strict=True)
    if (run_root / 'results.json').exists():
        summary = json.loads(safe_file(run_root, 'results.json').read_text(encoding='utf-8'))
        need(summary.get('schemaVersion') == 1 and summary.get('apkSha256') == old_plan['apk']['sha256'], 'resume-summary-apk')
    for row in rows:
        matches = [r for r in old_rows if r['distro'] == row['distro']]
        need(len(matches) == 1, 'resume-old-distro-missing')
        old = matches[0]
        for field in ['distro', 'version', 'instanceId', 'relativePhoneDir', 'rawBytes', 'phoneRootVolume',
                      'controlSocket', 'terminalSocket', 'gatewayId', 'rootfs', 'cpuCores', 'ramMegabytes']:
            need(old.get(field) == row.get(field), 'resume-instance-metadata-mismatch')
        credentials = [c for c in old_creds['instances'] if c['instanceId'] == row['instanceId']]
        need(len(credentials) == 1 and credentials[0] == row['_credentials'], 'resume-credentials-changed')
        for kind, name in [('kernel', 'Image'), ('initramfs', 'initramfs-embedded.cpio.gz')]:
            info = old[kind]
            need(info['path'] == f"{row['distro']}/runtime/{name}", 'resume-old-runtime-path')
            original = safe_file(old_root, info['path'])
            need(original.stat().st_size == info['bytes'] and sha_file(original) == info['sha256'], 'resume-old-runtime-hash')
        evidence = safe_file(run_root, row['distro'] + '/result.json')
        result = json.loads(evidence.read_text(encoding='utf-8'))
        completed_diagnostic = (row['distro'] == 'opensuse' and result.get('diagnosticOnly') is True
                                and result.get('baselinePassed') is False and result.get('diagnosticsCollected') is True
                                and result.get('diagnosticOperationSucceeded') is True
                                and result.get('controlledPoweroffVerified') is True)
        eligible_stage = result.get('failedStage') in {'hello', 'baseline', 'probe', 'poweroff-reauth', 'poweroff',
                                                       'recovery-diagnostics', 'recovery-retry'}
        need(result.get('passed') is False and result.get('rawRetained') is True
             and result.get('distro') == row['distro'] and result.get('instanceId') == row['instanceId']
             and result.get('phoneDirectory') == row['relativePhoneDir']
             and (eligible_stage or completed_diagnostic), 'resume-result-not-owned-failed-vm')
        if completed_diagnostic:
            need(all(re.fullmatch(r'[0-9a-f]{64}', result.get(key, '')) is not None
                     for key in ('apkSha256', 'planSha256', 'launchSha256')), 'resume-diagnostic-source-hashes')
        # Historical cleanup timeouts remain evidence; current phone PID guards
        # must prove the old processes are gone before any files are archived.
        need(any(c.get('command') == 'qemu' and c.get('code') == 0 for c in result.get('commands', [])), 'resume-qemu-never-started')
        if 'apkSha256' in result:
            need(result['apkSha256'] == old_plan['apk']['sha256'], 'resume-result-apk')
        if 'planSha256' in result:
            need(result['planSha256'] == sha_file(old_path), 'resume-result-plan')
        launch = safe_file(run_root, row['distro'] + '/launch.sh')
        content = launch.read_text(encoding='utf-8')
        definition = re.search(r'(?m)^D=(.+)$', content)
        need(definition is not None, 'resume-old-launch-scope')
        phone = shlex.split(definition.group(1))
        need(len(phone) == 1 and re.fullmatch(r'/data/(?:user/\d+|data)/' + re.escape(args.package)
             + '/' + re.escape(row['relativePhoneDir']), phone[0]) is not None, 'resume-old-launch-phone')
        launch_hash = sha_file(launch)
        if 'launchSha256' in result:
            need(result['launchSha256'] == launch_hash, 'resume-result-launch')
        row['_resume'] = {'old': old, 'launchSha256': launch_hash, 'phone': phone[0],
                          'sourceResult': str(evidence.relative_to(approved)).replace('\\', '/'),
                          'sourceResultSha256': sha_file(evidence), 'history': 'previous-run-' + uuid.uuid4().hex,
                          'allowAssemblyResume': assembly_resume,
                          'requiresCurrentCleanupVerification': True}
        if 'cleanupError' in result:
            row['_resume']['historicalCleanupError'] = result['cleanupError']
        if recovery:
            row['_recoveryDiagnostics'] = True
        if recovery_retry:
            row['_recoveryRetry'] = True
    return rows


def gateway_config(port):
    # NetworkGatewayDaemon.configuration(), empty forwards; services intentionally omitted.
    return (f'log-level: info\ninterfaces:\n  qemu: "tcp://127.0.0.1:{port}"\n'
            'stack:\n  mtu: 1500\n  subnet: 192.168.127.0/24\n  gatewayIP: 192.168.127.1\n'
            '  deviceIP: 192.168.127.15\n  hostIP: 192.168.127.254\n'
            '  gatewayMacAddress: 5a:94:ef:e4:0c:dd\n  forwards: {}\n')


def qemu_args(row, phone, native, port):
    control, terminal = row["controlSocket"], row["terminalSocket"]
    cmdline = ('console=ttyAMA0 rdinit=/init panic=-1 droidlayer.root_device=/dev/vda '
               'droidlayer.input_tag=droidlayer-input droidlayer.bridge_device=/dev/vport0p1 '
               'droidlayer.terminal_device=/dev/ttyAMA0 orchestella.guest_ip=192.168.127.15 '
               'orchestella.guest_gw=192.168.127.1 orchestella.guest_mask=255.255.255.0 '
               'orchestella.guest_dns=' + row.get('_guestDns', '223.5.5.5') + ' orchestella.proxy_enabled=0')
    if row.get('_recoveryDiagnostics'):
        need(row['distro'] == 'opensuse' and '_resume' in row, 'recovery-diagnostics-unowned-scope')
        cmdline += ' orchestella.recovery=1'
    return [native + '/libdroidlayer_qemu.so', '-machine', 'virt,gic-version=3', '-accel', 'tcg,thread=multi',
            '-cpu', 'cortex-a57', '-smp', '2', '-m', str(row['ramMegabytes']) + 'M', '-nodefaults',
            '-display', 'none', '-monitor', 'none', '-no-reboot',
            '-chardev', f'socket,id=terminal,path={terminal},abstract=on,server=on,wait=off', '-serial', 'chardev:terminal',
            '-kernel', phone + '/Image', '-initrd', phone + '/initramfs-embedded.cpio.gz', '-append', cmdline,
            '-drive', f'if=none,id=root,file={phone}/root-volume.raw,format=raw,cache=writeback',
            '-device', 'virtio-blk-device,drive=root', '-device', 'virtio-gpu-device',
            '-netdev', f'socket,id=orchestella-net,connect=127.0.0.1:{port}', '-device', 'virtio-net-device,netdev=orchestella-net',
            '-device', 'virtio-serial-device', '-chardev', f'socket,id=control,path={control},abstract=on,server=on,wait=off',
            '-device', 'virtserialport,chardev=control,name=droidlayer.bridge',
            '-chardev', f'socket,id=netproxy,path={control.replace("control", "proxy")},abstract=on,server=on,wait=off',
            '-device', 'virtserialport,chardev=netproxy,name=orchestella.proxy']


def launch_script(row, home, native, staging, port, native_hashes):
    phone = home + '/' + row['relativePhoneDir']
    q = shlex.quote
    checks = []
    for filename, digest in [("Image", row['kernel']['sha256']), ("initramfs-embedded.cpio.gz", row['initramfs']['sha256'])]:
        checks.append(f'[ "$(toybox sha256sum {q(phone + "/" + filename)} | cut -d " " -f 1)" = {q(digest)} ] || exit 70')
    for filename, digest in native_hashes.items():
        checks.append(f'[ "$(toybox sha256sum {q(native + "/" + filename)} | cut -d " " -f 1)" = {q(digest)} ] || exit 71')
    resume_prepare = ''
    if '_resume' in row:
        previous = row['_resume']
        old = previous['old']
        old_checks = []
        staged_checks = []
        for filename, digest in [('Image', row['kernel']['sha256']), ('initramfs-embedded.cpio.gz', row['initramfs']['sha256'])]:
            staged_checks.append(f'[ "$(toybox sha256sum {q(staging + "/" + filename)} | cut -d " " -f 1)" = {q(digest)} ] || exit 70')
        for filename, digest in native_hashes.items():
            staged_checks.append(f'[ "$(toybox sha256sum {q(native + "/" + filename)} | cut -d " " -f 1)" = {q(digest)} ] || exit 71')
        for filename, digest in [('launch.sh', previous['launchSha256']),
                                 ('Image', old['kernel']['sha256']),
                                 ('initramfs-embedded.cpio.gz', old['initramfs']['sha256'])]:
            old_checks.append(f'[ "$(toybox sha256sum {q(phone + "/" + filename)} | cut -d " " -f 1)" = {q(digest)} ] || exit 80')
        resume_prepare = f'''resume)
  cd {q(home)}
  for X in files files/quality-smoke "$D"; do [ -d "$X" ] && [ ! -L "$X" ] || exit 81; done
  [ "$D" = {q(previous['phone'])} ] || exit 81
  for X in Image initramfs-embedded.cpio.gz launch.sh root-volume.raw net.yaml qemu.pid gvproxy.pid qemu.stdout.log.txt gvproxy.stdout.log.txt; do
    [ -f "$D/$X" ] && [ ! -L "$D/$X" ] || exit 82
  done
  {'; '.join(old_checks)}
  {'; '.join(staged_checks)}
  [ "$(toybox stat -c %s "$D/root-volume.raw")" = {RAW_BYTES} ] || exit 83
  [ "$(toybox od -An -tx1 -j 1080 -N 2 "$D/root-volume.raw" | tr -d ' \\n')" = 53ef ] || exit 83
  for N in qemu gvproxy; do
    read P S < "$D/$N.pid"
    case "$P:$S" in *[!0-9:]*|:*) exit 84;; esac
    [ -n "$P" ] && [ -n "$S" ] && [ "$P" -gt 1 ] && [ "$S" -gt 0 ] || exit 84
    if [ -d "/proc/$P" ] || kill -0 "$P" 2>/dev/null; then
      [ -r "/proc/$P/stat" ] || exit 84
      CURRENT=$(awk '{{print $22}}' /proc/$P/stat)
      [ "$CURRENT" != "$S" ] || exit 84
    fi
  done
  INODE=$(toybox stat -c %i "$D/root-volume.raw")
  SIZE=$(toybox stat -c %s "$D/root-volume.raw")
  H="$D/{previous['history']}"
  mkdir "$H" || exit 85
  chmod 0700 "$H"
  for X in Image initramfs-embedded.cpio.gz launch.sh net.yaml qemu.pid gvproxy.pid qemu.stdout.log.txt gvproxy.stdout.log.txt; do
    mv "$D/$X" "$H/$X"
  done
  (set -C; cat "$T/Image" > "$D/Image"; cat "$T/initramfs-embedded.cpio.gz" > "$D/initramfs-embedded.cpio.gz"; cat "$T/launch.sh" > "$D/launch.sh")
  {'; '.join(checks)}
  (set -C; printf '%s' {q(gateway_config(port))} > "$D/net.yaml")
  chmod 0600 "$D/Image" "$D/initramfs-embedded.cpio.gz" "$D/launch.sh" "$D/net.yaml"
  [ "$(toybox stat -c %i "$D/root-volume.raw")" = "$INODE" ] && [ "$(toybox stat -c %s "$D/root-volume.raw")" = "$SIZE" ] || exit 86
  printf 'QA_RESUMED raw-preserved=1\\n'
  ;;
'''
    return f'''#!/system/bin/sh
set -eu
umask 077
D={q(phone)}
T={q(staging)}
Q={q(native + '/libdroidlayer_qemu.so')}
G={q(native + '/libgvproxy.so')}
owned() {{
  [ -f "$D/$1.pid" ] || return 1
  read P S < "$D/$1.pid"
  case "$P:$S" in *[!0-9:]*|:*) return 1;; esac
  [ -r "/proc/$P/stat" ] || return 1
  [ "$(awk '{{print $22}}' /proc/$P/stat)" = "$S" ] || return 1
  C=$(tr '\\000' ' ' < /proc/$P/cmdline)
  case "$1:$C" in qemu:"$Q "*"$D/root-volume.raw"*|gvproxy:"$G "*"$D/net.yaml"*) return 0;; *) return 1;; esac
}}
start_record() {{
  P=$1
  S=$(awk '{{print $22}}' /proc/$P/stat)
  (set -C; printf '%s %s\\n' "$P" "$S" > "$D/$2.pid")
  printf 'QA_PID %s %s\\n' "$2" "$P"
}}
case "${{1:-}}" in
prepare)
  cd {q(home)}
  [ -d files ] && [ ! -L files ] || exit 72
  [ ! -L files/quality-smoke ] || exit 72
  if [ ! -d files/quality-smoke ]; then mkdir files/quality-smoke; fi
  mkdir "$D" || exit 73
  chmod 0700 "$D"
  (set -C; cat "$T/Image" > "$D/Image"; cat "$T/initramfs-embedded.cpio.gz" > "$D/initramfs-embedded.cpio.gz"; cat "$T/launch.sh" > "$D/launch.sh")
  {'; '.join(checks)}
  (set -C; printf '%s' {q(MARKER)} > "$D/root-volume.raw")
  toybox truncate -s {RAW_BYTES} "$D/root-volume.raw"
  [ "$(toybox stat -c %s "$D/root-volume.raw")" = {RAW_BYTES} ] || exit 74
  (set -C; printf '%s' {q(gateway_config(port))} > "$D/net.yaml")
  chmod 0600 "$D"/*
  printf 'QA_PREPARED\\n'
  ;;
{resume_prepare}gateway)
  [ ! -e "$D/gvproxy.pid" ] && [ ! -e "$D/gvproxy.stdout.log.txt" ] || exit 75
  (set -C; nohup "$G" -config "$D/net.yaml" </dev/null > "$D/gvproxy.stdout.log.txt" 2>&1 & start_record $! gvproxy)
  ;;
qemu)
  owned gvproxy || exit 76
  [ ! -e "$D/qemu.pid" ] && [ ! -e "$D/qemu.stdout.log.txt" ] || exit 75
  (set -C; nohup {shlex.join(qemu_args(row, phone, native, port))} </dev/null > "$D/qemu.stdout.log.txt" 2>&1 & start_record $! qemu)
  ;;
status)
  for N in qemu gvproxy; do if owned "$N"; then printf 'QA_ALIVE %s\\n' "$N"; fi; done
  ;;
stop)
  for N in qemu gvproxy; do
    if owned "$N"; then kill -TERM "$P"; fi
  done
  sleep 2
  for N in qemu gvproxy; do
    if owned "$N"; then kill -KILL "$P"; fi
  done
  ;;
*) exit 64;;
esac
'''


class Adb:
    def __init__(self, args, secrets, history, deadline):
        self.args, self.secrets, self.history, self.deadline = args, secrets, history, deadline

    def call(self, label, *argv, timeout=30, check=True):
        started = time.monotonic()
        cleanup = label.startswith(('cleanup-', 'capture-', 'remove-'))
        if not cleanup:
            need(not STOP.is_set() and started < self.deadline, 'guest-deadline-or-stop')
            timeout = min(timeout, max(0.1, self.deadline - started))
        env = dict(os.environ, MSYS_NO_PATHCONV='1', MSYS2_ARG_CONV_EXCL='*')
        try:
            result = subprocess.run([self.args.adb, '-s', self.args.serial, *argv], stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=timeout, env=env, shell=False)
        except subprocess.TimeoutExpired:
            self.history.append({'command': label, 'code': 'timeout', 'seconds': round(time.monotonic() - started, 3)})
            raise GateError(label + '-adb-timeout') from None
        record = {'command': label, 'code': result.returncode, 'seconds': round(time.monotonic() - started, 3)}
        text = (result.stdout + result.stderr).decode('utf-8', 'replace')
        if result.returncode:
            record['diagnostic'] = redact(text[-8192:], self.secrets)
        self.history.append(record)
        if check and result.returncode:
            raise GateError(label + '-adb-code-' + str(result.returncode))
        return text

    def runas(self, label, command, **kwargs):
        return self.call(label, 'shell', shlex.join(['run-as', self.args.package, '/system/bin/sh', '-c', command]), **kwargs)

    def shell(self, label, command, **kwargs):
        return self.call(label, 'shell', shlex.join(['/system/bin/sh', '-c', command]), **kwargs)


class Console:
    def __init__(self, connection, output, secrets, append=False):
        self.connection, self.secrets = connection, secrets
        self.text, self.error = '', None
        self.bytes_received, self.append = 0, append
        self.ended = threading.Event()
        self.characters, self.prompt_generation = 0, 0
        self.prompt_at, self.prompt_available, self.marker_tail = 0.0, False, ''
        self.prompt_offset = 0
        self.lock = threading.Lock()
        self.stopped = threading.Event()
        self.output = output
        self.thread = threading.Thread(target=self.read, daemon=True)
        self.thread.start()

    def read(self):
        decoder = codecs.getincrementaldecoder('utf-8')('replace')
        pending = ''
        try:
            if self.append:
                need(self.output.is_file() and not self.output.is_symlink(), 'console-retry-log-invalid')
            with self.output.open('a' if self.append else 'x', encoding='utf-8') as log:
                while not self.stopped.is_set():
                    try:
                        data = self.connection.recv(65536)
                    except socket.timeout:
                        continue
                    if not data:
                        break
                    text = decoder.decode(data)
                    with self.lock:
                        self.bytes_received += len(data)
                        marked = self.marker_tail + text
                        for marker in SHELL_PROMPT_MARKER.finditer(marked):
                            if marker.end() <= len(self.marker_tail):
                                continue
                            self.prompt_available = marker.group(1) == 'B'
                            if self.prompt_available:
                                self.prompt_generation += 1
                                self.prompt_at = time.monotonic()
                                self.prompt_offset = self.characters - len(self.marker_tail) + marker.end()
                        self.marker_tail = marked[-256:]
                        self.characters += len(text)
                        self.text = (self.text + text)[-1024 * 1024:]
                    pending += text
                    while '\n' in pending:
                        line, pending = pending.split('\n', 1)
                        log.write(redact(line, self.secrets) + '\n')
                    if len(pending) > 16384:
                        log.write(redact(pending[:-256], self.secrets))
                        pending = pending[-256:]
                    log.flush()
                log.write(redact(pending + decoder.decode(b'', final=True), self.secrets))
        except Exception as error:
            self.error = type(error).__name__
        finally:
            self.ended.set()

    def prompt_seen(self):
        with self.lock:
            return re.search(r'\x1b\]133;B(?:;[^\x07\x1b]*)?(?:\x07|\x1b\\)', self.text) is not None

    def fresh_prompt(self, max_age=60):
        with self.lock:
            return self.prompt_available and time.monotonic() - self.prompt_at <= max_age

    def claim_progress_prompt(self, max_age=60):
        with self.lock:
            if not self.prompt_available or max_age is not None and time.monotonic() - self.prompt_at > max_age:
                return None
            start = max(0, self.prompt_offset - (self.characters - len(self.text)))
            tail = self.text[start:].replace('\x00', '')
            if re.search(r'(?im)(?:password|passphrase|login)\s*:|(?:enter|input|read) [^\n]{0,80}:|\[(?:y/n|yes/no)\]|\(yes/no\)', tail):
                return None  # Never type diagnostic text into another input request.
            self.prompt_available = False
            return self.characters

    def snapshot(self, since=None):
        with self.lock:
            start = 0 if since is None else max(0, since - (self.characters - len(self.text)))
            text = re.sub(r'\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)', '', self.text[start:])
            return re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', text).replace('\r', '').replace('\x00', '')

    def close(self):
        self.stopped.set()
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.connection.close()
        self.thread.join(timeout=3)
        need(not self.thread.is_alive(), 'console-reader-stop-timeout')


class ReadonlyProgress:
    """Fixed diagnostics on the existing terminal; requests are never read as commands."""
    def __init__(self, console, output, secrets, result, start, deadline, interval=60):
        self.console, self.output, self.secrets = console, output, secrets
        self.result, self.start, self.deadline, self.interval = result, start, deadline, interval
        self.last_sent, self.sequence, self.pending = time.monotonic(), 0, None
        result.update(readonlyProgressUsed=False, progressSamples=[])

    def finish(self, status, text):
        sample = self.pending
        self.pending = None
        info = {'sequence': sample['sequence'], 'source': sample['source'], 'status': status,
                'startedSeconds': round(sample['time'] - self.start, 3),
                'seconds': round(time.monotonic() - sample['time'], 3),
                'output': redact(text[-16384:], self.secrets)}
        name = f"progress-{sample['sequence']:04d}.json"
        with (self.output / name).open('x', encoding='utf-8') as file:
            file.write(json.dumps(info, ensure_ascii=False, indent=2) + '\n')
        self.result['progressSamples'].append({'file': name, 'status': status, 'source': sample['source']})

    def poll(self):
        now = time.monotonic()
        if self.pending:
            text = self.console.snapshot(since=self.pending['offset'])
            match = re.search(r'(?ms)^QA_PROGRESS_DIAG\n(.*?)^QA_PROGRESS_DIAG_END\s*(?:\n|$)', text)
            if match:
                self.finish('complete', match.group(1))
            elif now - self.pending['time'] >= 15 or now >= self.deadline or STOP.is_set():
                self.finish('timeout', text)
            else:
                return
        if now >= self.deadline or STOP.is_set():
            return
        if re.search(r'ORCHESTELLA_GUEST_PROVISION status=ready\b', self.console.snapshot()):
            return
        request = self.output / 'progress.request'
        requested = request.is_file() and not request.is_symlink()
        automatic = self.interval > 0 and now - self.last_sent >= self.interval
        if not (requested or automatic) or not self.console.fresh_prompt():
            return
        offset = self.console.claim_progress_prompt()
        if offset is None:
            return
        self.sequence += 1
        if requested:
            processed = self.output / f'progress.request.{self.sequence:04d}.{uuid.uuid4().hex}.processed'
            need(not processed.exists(), 'progress-processed-already-exists')
            request.rename(processed)  # Atomic, unique receipt; never consume file contents.
        self.pending = {'sequence': self.sequence, 'source': 'request' if requested else 'automatic',
                        'time': now, 'offset': offset}
        self.last_sent = now
        self.result['readonlyProgressUsed'] = True
        self.console.connection.sendall(PROGRESS_COMMAND.encode('ascii'))

    def capture_failure(self):
        started = time.monotonic()
        stop = min(self.deadline, started + 15)
        status, text, sent = 'skipped-no-available-prompt', '', False
        offset = None
        try:
            if started >= self.deadline or STOP.is_set():
                status = 'skipped-deadline-or-stop'
            elif self.pending:
                offset = self.pending['offset']  # Finish the existing fixed command; never overlap.
                sent = True
            else:
                offset = self.console.claim_progress_prompt(max_age=None)
                if offset is not None:
                    self.result['readonlyProgressUsed'] = True
                    self.console.connection.sendall(PROGRESS_COMMAND.encode('ascii'))
                    sent = True
            if offset is not None:
                status = 'timeout'
                while time.monotonic() < stop and not STOP.is_set():
                    text = self.console.snapshot(since=offset)
                    match = re.search(r'(?ms)^QA_PROGRESS_DIAG\n(.*?)^QA_PROGRESS_DIAG_END\s*(?:\n|$)', text)
                    if match:
                        text, status = match.group(1), 'complete'
                        break
                    if self.console.error:
                        status = 'console-reader-error'
                        break
                    time.sleep(0.1)
                if self.pending:
                    self.finish(status, text)
        except Exception as error:
            status = 'capture-error-' + type(error).__name__
        info = {'status': status, 'readonlyCommandSent': sent,
                'seconds': round(time.monotonic() - started, 3),
                'output': redact(text[-16384:], self.secrets)}
        with (self.output / 'failure-progress.json').open('x', encoding='utf-8') as file:
            file.write(json.dumps(info, ensure_ascii=False, indent=2) + '\n')
        self.result['failureProgress'] = {'file': 'failure-progress.json', 'status': status}

    def close(self):
        if self.pending:
            self.finish('interrupted', self.console.snapshot(since=self.pending['offset']))


class Control:
    def __init__(self, connection):
        self.connection, self.buffer = connection, b''
        self.response_observed = False
        self.initial_hello_frames, self.initial_hello_write_waits = 0, 0

    def exchange(self, command, expected, deadline, retry=False, on_poll=None, initial_hello=False):
        need(not initial_hello or retry and expected == 'ORCHESTELLA/READY 1'
             and re.fullmatch(r'ORCHESTELLA/HELLO 1 1 [0-9a-f]{64}\n', command) is not None,
             'initial-hello-command-invalid')
        packet = command.encode('ascii')
        sent, offset, interval, write_deadline, accepted = None, None, 1.0, 0.0, False
        while time.monotonic() < deadline and not STOP.is_set():
            if on_poll:
                on_poll()
            now = time.monotonic()
            if initial_hello:
                if offset is None and (sent is None or now - sent >= interval):
                    offset, write_deadline = 0, min(deadline, now + 10)
                readable, writable, _ = select.select([self.connection], [self.connection] if offset is not None else [],
                                                      [], min(0.2, max(0, deadline - now)))
            else:
                if sent is None or retry and now - sent >= 1:
                    self.connection.sendall(packet)
                    sent = now
                readable, writable, _ = select.select([self.connection], [], [], min(0.25, max(0, deadline - now)))
            # Read replies before retransmitting. Only initial HELLO tolerates write pressure.
            if readable:
                data = self.connection.recv(8192)
                need(bool(data), 'control-eof')
                self.response_observed = True
                self.buffer += data
                need(len(self.buffer) < 65536, 'control-message-too-large')
                while b'\n' in self.buffer:
                    line, self.buffer = self.buffer.split(b'\n', 1)
                    response = line.decode('ascii', 'replace').strip()
                    if response == expected:
                        if not initial_hello or offset in (None, 0):
                            return True
                        accepted = True  # Complete an already-partial repeat before another command.
                    if response.startswith('ORCHESTELLA/ERROR '):
                        reason = response[len('ORCHESTELLA/ERROR '):]
                        allowed = {'malformed-handshake', 'unauthorized', 'unsupported-version',
                                   'malformed-command', 'power-unavailable', 'resize-unavailable',
                                   'notify-unavailable', 'open-url-unavailable', 'display-unavailable',
                                   'clipboard-unavailable', 'payload-too-large'}
                        raise GateError('control-rejected-' + reason if reason in allowed else 'control-rejected')
                    # Duplicate READY from retried HELLO cannot acknowledge RESIZE/POWER.
            if initial_hello and writable and offset is not None:
                try:
                    count = self.connection.send(packet[offset:])
                except (socket.timeout, BlockingIOError, InterruptedError):
                    self.initial_hello_write_waits += 1  # send(), unlike sendall(), retains a known byte offset.
                else:
                    need(count > 0, 'control-eof')
                    offset += count
                    if offset == len(packet):
                        offset, sent = None, time.monotonic()
                        self.initial_hello_frames += 1
                        interval = min(15.0, 2.0 ** min(self.initial_hello_frames - 1, 4))
                        if accepted:
                            return True
            if initial_hello and offset is not None and time.monotonic() >= write_deadline:
                raise GateError('initial-hello-write-timeout')
        raise GateError('control-timeout')


def connect_forward(port, deadline):
    while time.monotonic() < deadline and not STOP.is_set():
        try:
            connection = socket.create_connection(('127.0.0.1', port), timeout=1)
            connection.settimeout(0.5)
            return connection
        except OSError:
            time.sleep(0.2)
    raise GateError('forward-connect-timeout')


def probe_command(distro, nonce):
    manager, ssh = PKG[distro], ('ssh' if distro in ('ubuntu', 'debian') else 'sshd')
    checks = {
        'pid1': 'cat /proc/1/comm; test "$(cat /proc/1/comm)" = systemd',
        'cgroup2': 'stat -fc %T /sys/fs/cgroup; test "$(stat -fc %T /sys/fs/cgroup)" = cgroup2fs',
        'services': f'systemctl is-active dbus.service && systemctl is-active {ssh}.service',
        'package': f'command -v {manager} && {manager} --version',
        'dns': 'getent hosts ' + DNS[distro],
        'write_sync': f'(umask 077; set -C; printf qa-{nonce} > /root/quality-smoke-{nonce}) && sync && test "$(cat /root/quality-smoke-{nonce})" = qa-{nonce}',
        'state': 'cat /var/lib/orchestella/provision/state; grep -qx status=ready /var/lib/orchestella/provision/state',
    }
    body = [f"printf '\\nQA_BEGIN_{nonce}\\n'", "qrc=0", "qc() { qn=$1; shift; sh -c \"$1\"; qs=$?; printf 'QA_CHECK_%s=%s\\n' \"$qn\" \"$qs\"; [ \"$qs\" -eq 0 ] || qrc=1; }"]
    body += ['qc ' + name + ' ' + shlex.quote(command) for name, command in checks.items()]
    body.append(f"printf 'QA_END_{nonce}=%s\\n' \"$qrc\"")
    return 'sh -c ' + shlex.quote('; '.join(body)) + '\r', list(checks)


def evaluate_probe(text, nonce, checks):
    match = re.search(r'(?m)^QA_BEGIN_' + nonce + r'\n(.*?)^QA_END_' + nonce + r'=(\d+)\s*$', text, re.S)
    if not match:
        return None
    output, code = match.groups()
    seen = dict(re.findall(r'(?m)^QA_CHECK_([a-z0-9_]+)=(\d+)\s*$', output))
    return {'passed': code == '0' and all(seen.get(name) == '0' for name in checks), 'checks': seen, 'output': output}


def collect_recovery_diagnostics(console, output, secrets, result, deadline, on_poll, retry=False):
    """Fixed owner operation; only explicit retry runs provisioning, never infers distro readiness."""
    end = min(deadline, time.monotonic() + 60)
    offset = None
    while time.monotonic() < end and not STOP.is_set():
        on_poll()
        need(not console.error, 'recovery-console-reader-error')
        offset = console.claim_progress_prompt(max_age=None)  # Complete, unconsumed OSC133 B only.
        if offset is not None:
            break
        time.sleep(0.1)
    need(offset is not None, 'recovery-canonical-prompt-timeout')
    need(result.get('rootRestartObserved') is True, 'recovery-root-restart-not-observed')
    result['readonlyRecoveryDiagnosticUsed'] = not retry
    if retry:
        result['recoveryRetryStarted'] = True
    console.connection.sendall((RECOVERY_RETRY_COMMAND if retry else RECOVERY_DIAGNOSTIC_COMMAND).encode('ascii'))
    end = deadline if retry else min(deadline, time.monotonic() + 60)
    pattern = (r'(?ms)^QA_RECOVERY_DIAG\n(.*?)^QA_RECOVERY_DIAG_END rc=([0-9]{1,3})[ \t]*(?:\n|$)'
               if retry else r'(?ms)^QA_RECOVERY_DIAG\n(.*?)^QA_RECOVERY_DIAG_END\s*(?:\n|$)')
    while time.monotonic() < end and not STOP.is_set():
        on_poll()
        need(not console.error, 'recovery-console-reader-error')
        text = console.snapshot(since=offset)
        match = re.search(pattern, text)
        if match:
            info = {'diagnosticOnly': True, 'baselinePassed': False, 'diagnosticsCollected': True,
                    'output': redact(match.group(1), secrets)}
            if retry:
                codes = re.findall(r'(?m)^QA_RECOVERY_RETRY_RC=([0-9]{1,3})[ \t]*$', match.group(1))
                states = re.findall(r'(?ms)^QA_RECOVERY_STATE_BEGIN\n(.*?)^QA_RECOVERY_STATE_END[ \t]*(?:\n|$)', match.group(1))
                statuses = re.findall(r'(?m)^status=([a-z-]+)[ \t]*$', states[0]) if len(states) == 1 else []
                checkpoint = (len(codes) == 1 and codes[0] == match.group(2) == '0'
                              and len(statuses) == 1 and statuses[0] in {'awaiting-init', 'ready'})
                result.update(retryExitCode=int(match.group(2)), retryState=statuses[0] if len(statuses) == 1 else 'unknown',
                              retryCheckpointReached=checkpoint)
                info.update(ownerOperation='baseline-retry', retryExitCode=result['retryExitCode'],
                            retryState=result['retryState'], retryCheckpointReached=checkpoint)
                if not checkpoint:
                    result.update(reason='recovery-retry-checkpoint-failed', failedStage='recovery-retry')
            with (output / 'recovery-diagnostics.json').open('x', encoding='utf-8') as file:
                file.write(json.dumps(info, ensure_ascii=False, indent=2) + '\n')
            result.update(diagnosticsCollected=True, recoveryDiagnosticFile='recovery-diagnostics.json')
            return
        time.sleep(0.1)
    raise GateError('recovery-retry-timeout' if retry else 'recovery-diagnostic-timeout')


def smoke_one(args, root, row, secrets, native_hashes, run_root, port):
    distro, nonce = row['distro'], uuid.uuid4().hex[:16]
    out = run_root / distro
    out.mkdir()
    start, deadline = time.monotonic(), time.monotonic() + args.timeout
    history, forwards = [], []
    adb = Adb(args, secrets, history, deadline)
    staging = '/data/local/tmp/quality-smoke-' + run_root.name + '-' + distro
    console, control, progress, script, phone = None, None, None, None, None
    prepared, staging_owned, passed, stage = False, False, False, 'preflight'
    resuming = '_resume' in row
    diagnostic_only = row.get('_recoveryDiagnostics', False)
    recovery_retry = row.get('_recoveryRetry', False)
    need(not recovery_retry or diagnostic_only and resuming and distro == 'opensuse', 'recovery-retry-unowned-scope')
    result = {'distro': distro, 'instanceId': row['instanceId'], 'ramMegabytes': row['ramMegabytes'], 'guestDns': row.get('_guestDns', '223.5.5.5'), 'passed': False,
              'apkSha256': args.apk_sha256, 'planSha256': sha_file(Path(args.plan)), 'resumed': resuming}
    if diagnostic_only:
        result.update(diagnosticOnly=True, baselinePassed=False, distroPassed=False, diagnosticsCollected=False)
    if recovery_retry:
        result.update(ownerOperation='baseline-retry', retryCheckpointReached=False)
    if resuming:
        result['resumeSourceResult'] = row['_resume']['sourceResult']
        result['resumeSourceResultSha256'] = row['_resume']['sourceResultSha256']
        result['previousPhoneFiles'] = row['_resume']['history']
        result['requiresCurrentCleanupVerification'] = True
        if 'historicalCleanupError' in row['_resume']:
            result['historicalCleanupError'] = row['_resume']['historicalCleanupError']
    try:
        home = adb.runas('app-home', 'pwd').strip()
        need(re.fullmatch(r'/data/(?:user/\d+|data)/' + re.escape(args.package), home) is not None, 'app-home-scope')
        phone = home + '/' + row['relativePhoneDir']
        script = phone + '/launch.sh'
        source = out / 'launch.sh'
        source.write_text(launch_script(row, home, args.native_dir, staging, port, native_hashes), encoding='utf-8', newline='\n')
        result['launchSha256'] = sha_file(source)
        stage = 'stage-inputs'
        adb.shell('fresh-staging', 'umask 077; mkdir ' + shlex.quote(staging) + ' && chmod 0711 ' + shlex.quote(staging))
        staging_owned = True
        for local, name in [(safe_file(root, row['kernel']['path']), 'Image'), (safe_file(root, row['initramfs']['path']), 'initramfs-embedded.cpio.gz'), (source, 'launch.sh')]:
            adb.call('push-' + name, 'push', str(local), staging + '/' + name, timeout=min(180, args.timeout))
        adb.shell('staging-readable', 'chmod 0644 ' + shlex.quote(staging) + '/*')
        stage = 'resume-inputs' if resuming else 'stage-inputs'
        mode = 'resume' if resuming else 'prepare'
        response = adb.runas('resume-owned-phone' if resuming else 'prepare-fresh-phone',
                            '/system/bin/sh ' + shlex.quote(staging + '/launch.sh') + ' ' + mode, timeout=180)
        need(('QA_RESUMED raw-preserved=1' if resuming else 'QA_PREPARED') in response, 'phone-prepare-failed')
        if resuming:
            result['rootRawPreserved'] = True
            result['currentCleanupVerified'] = True
        prepared = True
        adb.shell('remove-staged-payload', 'rm -f ' + shlex.join([staging + '/Image', staging + '/initramfs-embedded.cpio.gz']), timeout=15)
        def remote(mode, **kwargs):
            return adb.runas(mode, '/system/bin/sh ' + shlex.quote(script) + ' ' + mode, **kwargs)
        for field in ['terminalSocket', 'controlSocket']:
            value = adb.call('forward-' + field, 'forward', 'tcp:0', 'localabstract:' + row[field]).strip()
            need(value.isdigit() and 0 < int(value) < 65536, 'forward-port-invalid')
            forwards.append(int(value))
        stage = 'gateway'
        existing_listeners = adb.runas('gateway-port-preflight', 'cat /proc/net/tcp /proc/net/tcp6', check=False)
        network_table_available = history[-1]['code'] == 0
        result['networkTableAvailable'] = network_table_available
        if network_table_available:
            need(re.search(r':%04X\s+\S+\s+0A\b' % port, existing_listeners, re.I) is None, 'gateway-port-already-bound')
        need(re.search(r'QA_PID gvproxy \d+', remote('gateway')) is not None, 'gateway-no-pid')
        gateway_started = time.monotonic()
        ready_deadline = min(deadline, gateway_started + 10)
        while time.monotonic() < ready_deadline:
            status = remote('status')
            log = adb.runas('gateway-listener-log', 'tail -c 8192 ' + shlex.quote(phone + '/gvproxy.stdout.log.txt'), check=False)
            if re.search(r'address already in use|(?:bind|listen).*permission denied|panic:|fatal', log, re.I):
                raise GateError('gateway-bind-or-start-failed')
            alive = 'QA_ALIVE gvproxy' in status
            # nohup can briefly retain its wrapper cmdline before the native exec.
            if not alive and time.monotonic() - gateway_started >= 2:
                raise GateError('gateway-exited')
            # Android may deny /proc/net to an untrusted app. The daemon's actual
            # waiting-for-clients log is sufficient; never connect its one-client listener.
            listening = re.search(r'waiting.*clients', log, re.I) is not None
            if alive and not listening and network_table_available:
                listing = adb.runas('gateway-listener-table', 'cat /proc/net/tcp /proc/net/tcp6', check=False)
                network_table_available = history[-1]['code'] == 0
                result['networkTableAvailable'] = network_table_available
                if network_table_available:
                    listening = re.search(r':%04X\s+\S+\s+0A\b' % port, listing, re.I) is not None
            if alive and listening:
                break
            time.sleep(0.4)
        else:
            raise GateError('gateway-listener-timeout')
        stage = 'qemu'
        need(re.search(r'QA_PID qemu \d+', remote('qemu')) is not None, 'qemu-no-pid')
        channel_deadline = min(deadline, time.monotonic() + 30)
        hello_deadline = min(deadline, time.monotonic() + getattr(args, 'startup_timeout', 3900))
        result['initialChannelAttempts'] = 0
        def poll_wait():
            if console.ended.is_set():
                need(console.bytes_received != 0 or console.error not in (None, 'ConnectionResetError', 'ConnectionAbortedError', 'BrokenPipeError'),
                     'terminal-remote-not-ready')
                raise GateError('terminal-channel-ended')
            if resuming:
                observed = console.snapshot()
                allow_assembly = row['_resume']['allowAssemblyResume']
                assembly_marker = 'DROIDLAYER_ROOT_VOLUME_RESUME phase=assembly preserving-filesystem' in observed
                need('ORCHESTELLA_GUEST_ASSEMBLY phase=format' not in observed, 'resume-attempted-format')
                need(allow_assembly or not re.search(r'ORCHESTELLA_GUEST_ASSEMBLY phase=unpack\b|DROIDLAYER_ROOT_VOLUME_RESUME\b', observed),
                     'resume-attempted-reassembly')
                if 'ORCHESTELLA_GUEST_ASSEMBLY phase=unpack' in observed:
                    need(allow_assembly and assembly_marker, 'resume-assembly-marker-not-observed')
                if allow_assembly and assembly_marker:
                    result['rootAssemblyResumeObserved'] = True
                if 'DROIDLAYER_ROOT_VOLUME_RESTART version=1' in observed:
                    result['rootRestartObserved'] = True
            if not diagnostic_only:
                progress.poll()
        stage = 'hello'
        while True:
            result['initialChannelAttempts'] += 1
            try:
                console = Console(connect_forward(forwards[0], channel_deadline), out / 'terminal.log.txt', secrets,
                                  append=result['initialChannelAttempts'] > 1)
                control = Control(connect_forward(forwards[1], channel_deadline))
                progress = ReadonlyProgress(console, out, secrets, result, start, deadline, getattr(args, 'progress_interval', 60))
                control.exchange('ORCHESTELLA/HELLO 1 1 ' + row['_credentials']['bridgeToken'] + '\n',
                                 'ORCHESTELLA/READY 1', hello_deadline, retry=True, on_poll=poll_wait, initial_hello=True)
                poll_wait()
                break
            except (GateError, ConnectionResetError, ConnectionAbortedError, BrokenPipeError) as error:
                transport_failure = not isinstance(error, GateError) or str(error) in {
                    'control-eof', 'terminal-remote-not-ready', 'forward-connect-timeout'}
                observed = control is not None and control.response_observed
                if control:
                    control.connection.close()
                if console:
                    console.close()  # Join the old reader before reconnecting to the same forward.
                received = console.bytes_received if console else 0
                if not transport_failure or observed or received:
                    raise
                console, control, progress = None, None, None
                if time.monotonic() >= channel_deadline or STOP.is_set():
                    raise GateError('initial-channels-not-ready') from None
                result['initialChannelReadiness'] = 'early-empty-eof-retry'
                time.sleep(min(0.2, max(0, channel_deadline - time.monotonic())))
        result['initialHelloAcknowledged'] = True
        control.exchange('ORCHESTELLA/RESIZE 80 24\n', 'ORCHESTELLA/OK resize', min(deadline, time.monotonic() + 15))
        if diagnostic_only:
            stage = 'recovery-retry' if recovery_retry else 'recovery-diagnostics'
            collect_recovery_diagnostics(console, out, secrets, result, deadline, poll_wait, retry=recovery_retry)
        else:
            stage = 'baseline'
            while time.monotonic() < deadline and not STOP.is_set():
                poll_wait()
                text = console.snapshot()
                if re.search(r'ORCHESTELLA_GUEST_PROVISION status=(?:failed|action-required|action_required)\b|Kernel panic(?: - not syncing)?', text):
                    try:
                        progress.capture_failure()
                    except Exception as error:
                        result['failureProgressError'] = type(error).__name__
                    raise GateError('guest-baseline-failed')
                # Recovery bootstrap is expected for minimal roots; await native handoff.
                # Canonical Starship prompts use U+276F and OSC133 B rather than '#'.
                prompt = console.prompt_seen() or re.search(r'(?m)^[^\n]{0,180}[#$❯] ?$', text)
                if result['readonlyProgressUsed']:
                    prompt = console.fresh_prompt()
                if re.search(r'ORCHESTELLA_GUEST_PROVISION status=ready\b', text) and prompt and progress.pending is None:
                    break
                if console.error:
                    raise GateError('console-reader-' + console.error)
                time.sleep(0.5)
            else:
                raise GateError('baseline-or-prompt-timeout')
            if resuming:
                need(result.get('rootRestartObserved') is True
                     or row['_resume']['allowAssemblyResume'] and result.get('rootAssemblyResumeObserved') is True,
                     'resume-root-restart-not-observed')
            stage = 'probe'
            command, checks = probe_command(distro, nonce)
            console.connection.sendall(command.encode('utf-8'))
            while time.monotonic() < deadline and not STOP.is_set():
                verdict = evaluate_probe(console.snapshot(), nonce, checks)
                if verdict is not None:
                    result['probe'] = {'checks': verdict['checks'], 'output': redact(verdict['output'], secrets)}
                    need(verdict['passed'], 'full-probe-failed')
                    break
                time.sleep(0.5)
            else:
                raise GateError('full-probe-timeout')
        # Native handoff can restart the guest agent and discard recovery-time auth.
        stage = 'poweroff-reauth'
        control.exchange('ORCHESTELLA/HELLO 1 1 ' + row['_credentials']['bridgeToken'] + '\n',
                         'ORCHESTELLA/READY 1', min(deadline, time.monotonic() + 60), retry=True)
        result['poweroffReauthenticated'] = True
        stage = 'poweroff'
        control.exchange('ORCHESTELLA/POWER poweroff\n', 'ORCHESTELLA/OK power', min(deadline, time.monotonic() + 15))
        stop_deadline = min(deadline, time.monotonic() + 60)
        while time.monotonic() < stop_deadline and 'QA_ALIVE qemu' in remote('status'):
            time.sleep(1)
        need('QA_ALIVE qemu' not in remote('status'), 'controlled-poweroff-timeout')
        result['controlledPoweroffVerified'] = True
        passed = not recovery_retry or result['retryCheckpointReached'] is True
    except Exception as error:
        result['reason'] = str(error) if isinstance(error, GateError) else type(error).__name__
        result['failedStage'] = stage
    finally:
        if progress:
            try:
                progress.close()
            except Exception as error:
                result['progressCaptureError'] = type(error).__name__
        if prepared and script:
            try:
                adb.runas('cleanup-owned-pids', '/system/bin/sh ' + shlex.quote(script) + ' stop', timeout=15)
                for name in ['qemu', 'gvproxy']:
                    text = adb.runas('capture-' + name, 'tail -c 32768 ' + shlex.quote(phone + '/' + name + '.stdout.log.txt'), check=False)
                    (out / (name + '.log.txt')).write_text(redact(text, secrets), encoding='utf-8')
            except Exception as error:
                result['cleanupError'] = type(error).__name__
                passed = False
        if control:
            result['initialHelloFrameCount'] = control.initial_hello_frames
            result['initialHelloWriteWaits'] = control.initial_hello_write_waits
            control.connection.close()
        if console:
            console.close()
            result['lastConsole'] = redact(console.snapshot()[-8192:], secrets)
        for local_port in forwards:
            try:
                adb.call('remove-owned-forward', 'forward', '--remove', 'tcp:' + str(local_port), timeout=10)
            except Exception as error:
                result['forwardCleanupError'] = type(error).__name__
                passed = False
        if staging_owned:
            try:
                # Exact staging files only. Never remove an app-private directory, disk, or log.
                files = [staging + '/' + name for name in ['Image', 'initramfs-embedded.cpio.gz', 'launch.sh']]
                adb.shell('remove-owned-staging', 'rm -f ' + shlex.join(files) + '; rmdir ' + shlex.quote(staging), timeout=10, check=False)
            except Exception:
                pass
        if diagnostic_only:
            result['diagnosticOperationSucceeded'] = passed and result['diagnosticsCollected']
        result.update(passed=passed and not diagnostic_only, seconds=round(time.monotonic() - start, 3), commands=history,
                      phoneDirectory=row['relativePhoneDir'], rawRetained=prepared)
        (out / 'result.json').write_text(redact(json.dumps(result, ensure_ascii=False, indent=2), secrets) + '\n', encoding='utf-8')
        success_label = ' recovery retry checkpoint reached' if recovery_retry else (' diagnostics collected' if diagnostic_only else ' passed')
        print(distro + (success_label if passed else ' failed ' + result.get('failedStage', 'cleanup')), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ['plan', 'apk', 'apk-sha256', 'serial', 'native-dir', 'output']:
        parser.add_argument('--' + flag, required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--package', default='io.github.nyzeep.orchestella')
    parser.add_argument('--distros')
    parser.add_argument('--timeout', type=int, default=1800)
    parser.add_argument('--startup-timeout', type=int, default=3900,
                        help='Initial HELLO budget for verified large assembly; always capped by the guest overall timeout')
    parser.add_argument('--parallel', type=int, choices=[1, 2], default=2)
    parser.add_argument('--port-base', type=int, default=24000)
    parser.add_argument('--guest-dns', default='223.5.5.5', help='Explicit IPv4 resolver from the observed current Android network')
    parser.add_argument('--resume-plan', help='Original trusted QA plan; requires --resume-results and a linked same-instance new plan')
    parser.add_argument('--resume-results', help='Original qa-run directory containing failed per-distro results and launch scripts')
    parser.add_argument('--resume-assembly', action='store_true', help='Explicit Arch-only interrupted assembly resume; format stays forbidden')
    parser.add_argument('--recovery-diagnostics', action='store_true', help='Explicit openSUSE owned-QA recovery inspection; never marks baseline or distro passed')
    parser.add_argument('--recovery-retry', action='store_true', help='Explicit fixed foreground provisioning --retry; requires openSUSE recovery diagnostics and owned resume')
    parser.add_argument('--progress-interval', type=int, default=60,
                        help='Fixed readonly progress sampling interval; 0 disables automatic sampling (requests remain enabled)')
    args = parser.parse_args()
    need(re.fullmatch(r'[a-z][a-z0-9_.]+', args.package) is not None, 'package-syntax')
    need(re.fullmatch(r'/data/app/[A-Za-z0-9_./+=~-]+/lib/arm64', args.native_dir) is not None and '..' not in args.native_dir.split('/'), 'native-directory-scope')
    need(60 <= args.timeout <= 7200 and 1024 <= args.port_base <= 65000, 'deadline-or-port-range')
    need(60 <= args.startup_timeout <= 7200, 'startup-timeout-range')
    need(args.progress_interval == 0 or 60 <= args.progress_interval <= 7200, 'progress-interval-range')
    root, plan, rows, secrets, native_hashes = load_batch(args)
    rows = load_resume(args, plan, rows)
    try:
        resolver = ipaddress.IPv4Address(args.guest_dns)
    except ipaddress.AddressValueError:
        raise GateError('guest-dns-literal-required') from None
    need(not resolver.is_unspecified and not resolver.is_loopback and not resolver.is_multicast
         and str(resolver) != '255.255.255.255', 'guest-dns-not-usable')
    rows = [dict(row, _guestDns=str(resolver)) for row in rows]
    output = Path(args.output).resolve(strict=True)
    need(output.is_relative_to(APPROVED.resolve(strict=True)) and output.is_dir(), 'output-outside-approved-root')
    run_root = output / ('qa-run-' + uuid.uuid4().hex[:12])
    run_root.mkdir()
    def interrupt(signum, frame):
        STOP.set()
        raise KeyboardInterrupt
    signal.signal(signal.SIGINT, interrupt)
    signal.signal(signal.SIGTERM, interrupt)
    results = []
    regular = [r for r in rows if r['distro'] != 'arch' and not r.get('exclusiveScheduling', False)]
    exclusive = [r for r in rows if r not in regular]
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.parallel) as pool:
            futures = [pool.submit(smoke_one, args, root, row, secrets, native_hashes, run_root, args.port_base + i) for i, row in enumerate(regular)]
            results += [future.result() for future in futures]
        for i, row in enumerate(exclusive, len(regular)):
            results.append(smoke_one(args, root, row, secrets, native_hashes, run_root, args.port_base + i))
    except KeyboardInterrupt:
        STOP.set()
        raise GateError('interrupted-cleanup-pending')
    summary = {'schemaVersion': 1, 'apkSha256': args.apk_sha256, 'passed': all(r['passed'] for r in results), 'instances': results}
    if args.recovery_diagnostics:
        summary.update(diagnosticOnly=True, baselinePassed=False, distroPassed=False,
                       diagnosticsCollected=all(r.get('diagnosticsCollected') is True for r in results),
                       operationSucceeded=all(r.get('diagnosticOperationSucceeded') is True for r in results))
        if args.recovery_retry:
            summary['ownerOperation'] = 'baseline-retry'
    (run_root / 'results.json').write_text(redact(json.dumps(summary, ensure_ascii=False, indent=2), secrets) + '\n', encoding='utf-8')
    return 0 if (summary['operationSucceeded'] if args.recovery_diagnostics else summary['passed']) else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print('smoke-driver-failed ' + (str(error) if isinstance(error, GateError) else type(error).__name__), flush=True)
        raise SystemExit(1)
