#!/usr/bin/env python3
"""Run synthetic, offline failure/retry and init-selection probes without Gradle or a VM."""
from pathlib import Path
import hashlib
import os
import shlex
import shutil
import subprocess
import sys
import tempfile

assets = Path(__file__).resolve().parent.parent
work = Path(os.environ.get("GUEST_RUNTIME_TEST_ROOT", ""))
if not os.environ.get("GUEST_RUNTIME_TEST_ROOT") or not work.is_dir():
    raise SystemExit("GUEST_RUNTIME_TEST_ROOT must be an existing approved external evidence directory")
fixture = Path(tempfile.mkdtemp(prefix="guest-runtime-", dir=work))


def unix(path):
    if os.name == "nt":
        return subprocess.check_output(["cygpath", "-u", str(path)], text=True).strip()
    return str(path)


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")
    path.chmod(0o755)


root = fixture / "root"
stubs = fixture / "bin"
write(root / ".orchestella-provision-fixture", "synthetic fixture\n")
write(root / "etc/os-release", "ID=debian\nVERSION_ID=13\n")
write(root / "proc/1/comm", "systemd\n")
write(root / "etc/apt/sources.list", "deb http://example.invalid/debian trixie main\n")
write(root / "etc/locale.gen", "# en_US.UTF-8 UTF-8\n")
write(root / "etc/ssl/certs/ca-certificates.crt", "synthetic CA store\n")
write(root / "etc/init.d/dbus", "fixture\n")
write(root / "etc/init.d/sshd", "fixture\n")
write(root / "usr/lib/systemd/systemd", "\x7fELFsynthetic init\n")
(root / "usr/sbin").mkdir(parents=True, exist_ok=True)
write(stubs / "timeout", "#!/bin/sh\nshift\nexec \"$@\"\n")
write(stubs / "apt-get", """#!/bin/sh
printf '%s\\n' "$*" >> "$ORCHESTELLA_PROVISION_ROOT/calls"
case " $* " in
    *' update '*)
        if [ -f "$ORCHESTELLA_PROVISION_ROOT/offline" ]; then exit 100; fi
        if grep -q 'bad.invalid' "$ORCHESTELLA_PROVISION_ROOT/etc/apt/sources.list"; then exit 100; fi ;;
    *' install '*)
        [ ! -f "$ORCHESTELLA_PROVISION_ROOT/install-failed" ] || exit 100
        printf 'installed\\n' > "$ORCHESTELLA_PROVISION_ROOT/inventory"
        mkdir -p "$ORCHESTELLA_PROVISION_ROOT/etc/ssl/certs"
        printf 'synthetic CA store\\n' > "$ORCHESTELLA_PROVISION_ROOT/etc/ssl/certs/ca-certificates.crt"
        if [ -f "$ORCHESTELLA_PROVISION_ROOT/repair-init" ]; then
            printf '\\177ELFrestored' > "$ORCHESTELLA_PROVISION_ROOT/usr/lib/systemd/systemd"
        fi ;;
esac
exit 0
""")
write(stubs / "dpkg-query", """#!/bin/sh
[ -f "$ORCHESTELLA_PROVISION_ROOT/inventory" ] || exit 1
[ ! -f "$ORCHESTELLA_PROVISION_ROOT/inventory-missing" ] || exit 1
printf installed
""")
write(stubs / "systemctl", """#!/bin/sh
printf '%s\\n' "$*" >> "$ORCHESTELLA_PROVISION_ROOT/service-calls"
case " $* " in
    *' is-active '*) [ ! -f "$ORCHESTELLA_PROVISION_ROOT/service-failed" ] || exit 3 ;;
esac
exit 0
""")
write(stubs / "sshd", "#!/bin/sh\n[ ! -f \"$ORCHESTELLA_PROVISION_ROOT/ssh-invalid\" ] || exit 1\n")
write(stubs / "ssh-keygen", """#!/bin/sh
while [ "$#" -gt 0 ]; do
    if [ "$1" = -f ]; then shift; printf 'synthetic host key\\n' > "$1"; break; fi
    shift
done
""")
for cmd in ["dpkg", "update-ca-certificates", "dbus-uuidgen"]:
    write(stubs / cmd, "#!/bin/sh\nexit 0\n")
write(stubs / "locale", "#!/bin/sh\nif [ ! -f \"$ORCHESTELLA_PROVISION_ROOT/locale-missing\" ]; then printf 'en_US.utf8\\n'; fi\n")
write(stubs / "locale-gen", "#!/bin/sh\nprintf 'locale-gen\\n' >> \"$ORCHESTELLA_PROVISION_ROOT/locale-calls\"\nrm -f \"$ORCHESTELLA_PROVISION_ROOT/locale-missing\"\n")

script = fixture / "orchestella-guest-provision.sh"
shutil.copyfile(assets / "scripts/orchestella-guest-provision.sh", script)
state = root / "var/lib/orchestella/provision/state"
done = root / "opt/orchestella/.firstboot-done"
checks = []
skips = []


def recovery_shell_checks():
    # Execute the installed selector's exact control flow. ELF fixtures and the bounded
    # --version executor are synthetic because Windows cannot execute Linux ELF binaries.
    source = (assets / "initramfs/runtime-functions.sh").read_text()
    start = source.index("select_recovery_login_shell() {\n")
    finish = source.index('\nrecovery_shell="$(select_recovery_login_shell)"', start)
    selector = source[start:finish]
    shell_fixture = fixture / "recovery-shell"
    candidates = [shell_fixture / "bin/bash", shell_fixture / "usr/bin/bash"]
    selector = selector.replace("for shell_candidate in /bin/bash /usr/bin/bash; do",
                                "for shell_candidate in " + " ".join(shlex.quote(unix(p)) for p in candidates) + "; do")
    selector = selector.replace("/usr/lib/orchestella/busybox timeout 5", "timeout 5")
    if os.name == "nt":
        # MSYS marks Linux ELF fixtures non-executable. Model availability here;
        # production keeps its actual -x check, and ARM execution remains a device gate.
        selector = selector.replace('[ -x "$shell_candidate" ]', '[ -f "$shell_candidate" ]')
    write(shell_fixture / "probe-bin/timeout", """#!/bin/sh
[ "$1" = 5 ] || exit 64
shift
printf '%s\\n' "$*" >> "$RECOVERY_SELECTOR_FIXTURE/probes"
[ "$2" = --noprofile ] && [ "$3" = --norc ] && [ "$4" = --version ] || exit 64
[ -f "$1.healthy" ]
""")
    results = []

    def verify(label, content, healthy, expected, probe_count):
        for path in candidates:
            path.unlink(missing_ok=True)
            Path(str(path) + ".healthy").unlink(missing_ok=True)
        (shell_fixture / "probes").unlink(missing_ok=True)
        for index, text in enumerate(content):
            if text is not None:
                write(candidates[index], text)
            if healthy[index]:
                write(Path(str(candidates[index]) + ".healthy"), "synthetic loader succeeds\n")
        cmd = (f"export PATH={shlex.quote(unix(shell_fixture / 'probe-bin'))}:$PATH; "
               f"export RECOVERY_SELECTOR_FIXTURE={shlex.quote(unix(shell_fixture))}; "
               + selector + "\nselect_recovery_login_shell\n")
        result = subprocess.run(["sh", "-c", cmd], text=True, capture_output=True, timeout=10)
        target = unix(candidates[expected]) if expected is not None else "/usr/lib/orchestella/busybox"
        assert result.returncode == 0 and result.stdout.strip() == target, (label, result)
        probes = (shell_fixture / "probes").read_text().splitlines() if (shell_fixture / "probes").exists() else []
        assert len(probes) == probe_count, (label, probes)
        results.append(label)

    elf = chr(127) + "ELFsynthetic Bash\n"
    old_script = "#!/bin/sh\n# Orchestella minimal Linux init supervisor\n"
    verify("healthy_bin_bash_preferred_and_probed_once", [elf, elf], [True, True], 0, 1)
    verify("usr_bash_used_when_bin_bash_absent", [None, elf], [False, True], 1, 1)
    verify("damaged_script_bash_rejected_before_execution", [old_script, elf], [False, True], 1, 1)
    verify("failed_bash_loader_uses_next_healthy_candidate", [elf, elf], [False, True], 1, 2)
    verify("missing_bash_retains_private_busybox_recovery", [None, None], [False, False], None, 0)
    verify("all_bash_loader_failures_retain_private_busybox", [elf, elf], [False, False], None, 2)
    assert source.index('recovery_shell="$(select_recovery_login_shell)"') < source.index('set -- "$recovery_shell" -l')
    assert 'setsid -c "$@" < "$console"' in source
    assert 'set -- /usr/lib/orchestella/busybox sh -l' in source
    return results


def manual_recovery_checks():
    source = (assets / "initramfs/runtime-functions.sh").read_text()
    boot = source.split('cat > "$target_root/usr/sbin/orchestella-boot" <<\'EOF\'\n', 1)[1].split("EOF\n", 1)[0]
    recovery = source.split('cat > "$target_root/usr/sbin/orchestella-recovery-init" <<\'EOF\'\n', 1)[1].split("EOF\n", 1)[0]
    manual_fixture = fixture / "manual-recovery"
    command_line = manual_fixture / "cmdline"
    native_file = manual_fixture / "native-init"
    original_init = manual_fixture / "original-init"
    original_state = manual_fixture / "state"
    calls = manual_fixture / "calls"
    console = manual_fixture / "console"
    write(original_init, "Synthetic original distro init, untouched by diagnostic dispatch\n")
    write(original_state, "version=2\nstatus=awaiting-init\nstage=native-handoff\nattempt=3\n")
    preserved_init = original_init.read_bytes(); preserved_state = original_state.read_bytes()
    model = boot.replace("/proc/cmdline", shlex.quote(unix(command_line)))
    model = model.replace("/etc/orchestella/native-init", shlex.quote(unix(native_file)))
    # Model only exec syscalls; no PID1, VM, host device or guest binary is launched.
    model = model.replace("exec /usr/sbin/orchestella-recovery-init", "printf 'dispatch=recovery manual=%s\\n' \"$ORCHESTELLA_RECOVERY_MANUAL\"; exit 0")
    model = model.replace("exec /bin/busybox init", "printf 'dispatch=busybox-init manual=%s\\n' \"$ORCHESTELLA_RECOVERY_MANUAL\"; exit 0")
    model = model.replace('exec "$native"', "printf 'dispatch=%s manual=%s\\n' \"$native\" \"$ORCHESTELLA_RECOVERY_MANUAL\"; exit 0")
    results = []
    cases = [
        ("default_preserves_native_systemd", "console=ttyAMA0", "/usr/lib/systemd/systemd", "dispatch=/usr/lib/systemd/systemd manual=0"),
        ("explicit_zero_preserves_native", "orchestella.recovery=0", "/lib/systemd/systemd", "dispatch=/lib/systemd/systemd manual=0"),
        ("non_one_value_cannot_request_recovery", "orchestella.recovery=true", "/usr/lib/systemd/systemd", "dispatch=/usr/lib/systemd/systemd manual=0"),
        ("substring_does_not_request_recovery", "xorchestella.recovery=1 orchestella.recovery=10", "/usr/lib/systemd/systemd", "dispatch=/usr/lib/systemd/systemd manual=0"),
        ("ordinary_alpine_keeps_native_busybox_init", "console=hvc0", "/bin/busybox", "dispatch=busybox-init manual=0"),
        ("invalid_native_keeps_automatic_recovery_semantics", "console=ttyAMA0", "recovery", "dispatch=recovery manual=0"),
        ("exact_owner_flag_selects_private_manual_recovery", "console=ttyAMA0 orchestella.recovery=1", "/usr/lib/systemd/systemd", "dispatch=recovery manual=1"),
    ]
    for label, cmdline, native, expected in cases:
        write(command_line, cmdline + "\n"); write(native_file, native + "\n")
        result = subprocess.run(["sh", "-c", model], text=True, capture_output=True, timeout=10)
        assert result.returncode == 0 and result.stdout.strip() == expected, (label, result)
        assert original_init.read_bytes() == preserved_init and original_state.read_bytes() == preserved_state
        assert native_file.read_text() == native + "\n"
        results.append(label)
    announce_start = recovery.index('if [ "${ORCHESTELLA_RECOVERY_MANUAL:-0}" = 1 ]; then')
    announce_end = recovery.index("select_recovery_login_shell() {", announce_start)
    announcement = recovery[announce_start:announce_end].replace("/dev/console", shlex.quote(unix(console)))
    subprocess.run(["sh", "-c", "ORCHESTELLA_RECOVERY_MANUAL=1; " + announcement], check=True)
    assert "reason=owner-requested mode=manual baseline=not-ready automatic_provision=disabled" in console.read_text()
    results.append("manual_recovery_is_explicitly_not_ready")
    bootstrap = recovery.split("# One automatic attempt per ordinary recovery boot; explicit owner diagnostics preserve state.\n", 1)[1].split("while :; do\n", 1)[0]
    bootstrap = bootstrap.replace("/usr/lib/orchestella/busybox sh /opt/orchestella/firstboot.sh --bootstrap",
                                  "printf 'bootstrap\\n' >> " + shlex.quote(unix(calls)))
    calls.unlink(missing_ok=True)
    subprocess.run(["sh", "-c", "ORCHESTELLA_RECOVERY_MANUAL=1; " + bootstrap], check=True)
    assert not calls.exists() and original_state.read_bytes() == preserved_state
    results.append("owner_diagnostic_mode_never_runs_automatic_package_provision")
    guard = recovery.split("while :; do\n", -1)[-1].split("native=", 1)[0]
    # Execute the exact native-handoff condition with an awaiting-init fixture.
    condition = guard.strip().removeprefix("if ").removesuffix("then").strip().removesuffix(";")
    condition = condition.replace("/var/lib/orchestella/provision/state", shlex.quote(unix(original_state)))
    result = subprocess.run(["sh", "-c", "ORCHESTELLA_RECOVERY_MANUAL=1; if " + condition + "; then exit 99; fi"], capture_output=True)
    assert result.returncode == 0 and original_state.read_bytes() == preserved_state
    results.append("manual_recovery_does_not_auto_handoff_or_mark_ready_from_stale_state")
    subprocess.run(["sh", "-c", "ORCHESTELLA_RECOVERY_MANUAL=0; " + bootstrap], check=True)
    assert calls.read_text() == "bootstrap\n"
    results.append("ordinary_recovery_retains_one_automatic_bootstrap_attempt")
    assert "--token-file /etc/droidlayer/bridge.token" not in recovery or "droidlayer-guest-agent-launcher" in recovery
    assert "/usr/sbin/droidlayer-guest-agent-launcher" in recovery and 'recovery_shell="$(select_recovery_login_shell)"' in recovery
    return results


def identity_checks():
    identity_root = fixture / "identity-root"
    identity_input = fixture / "identity-input"
    identity_root.mkdir()
    identity_input.mkdir()
    functions = assets / "initramfs/runtime-functions.sh"
    commands = (f". {shlex.quote(unix(functions))}; fail() {{ printf '%s\\n' \"$1\"; exit 1; }}; "
                f"log() {{ :; }}; stage_pending_guest_credentials {shlex.quote(unix(identity_root))} {shlex.quote(unix(identity_input))}")
    marker = identity_root / "etc/orchestella/instance-user"
    result = subprocess.run(["sh", "-c", commands], capture_output=True, text=True, timeout=10)
    assert result.returncode == 0 and not marker.exists(), "unknown legacy identity is never invented"
    checks.append("missing_legacy_identity_not_invented")
    (identity_input / "guest.user").write_text("developer\n")
    result = subprocess.run(["sh", "-c", commands], capture_output=True, text=True, timeout=10)
    assert result.returncode == 0, result
    assert marker.read_text() == "developer\n"
    assert not (identity_root / "etc/orchestella/provision-input/guest.password").exists()
    checks.append("ordinary_identity_marker_written_without_unknown_password_reset")
    for invalid in ("root;id\n", "Developer\n", "u\n", "a" * 17 + "\n", "developer\nroot\n"):
        (identity_input / "guest.user").write_text(invalid)
        before = marker.read_bytes()
        result = subprocess.run(["sh", "-c", commands], capture_output=True, text=True, timeout=10)
        assert result.returncode != 0 and marker.read_bytes() == before, "invalid identity preserves old marker"
    checks.append("invalid_identity_refused_before_marker_replacement")
    (identity_input / "guest.user").write_text("developer\n")
    (identity_input / "guest.password").write_text("SyntheticSecret2026\n")
    (identity_input / "guest.sudo").write_text("1\n")
    result = subprocess.run(["sh", "-c", commands], capture_output=True, text=True, timeout=10)
    assert result.returncode == 0, result
    pending = identity_root / "etc/orchestella/provision-input"
    assert (pending / "guest.user").read_text() == "developer\n"
    assert (pending / "guest.sudo").read_text() == "1\n"
    checks.append("explicit_sudo_and_ordinary_user_staged_with_credentials")
    # Execute the production account block with synthetic account tools. Never invoke real useradd/chpasswd.
    source = (assets / "scripts/orchestella-guest-provision.sh").read_text()
    block = source[source.index('identity="$ROOT/etc/orchestella/provision-input"\n'):source.index('\nif [ "$baseline_verified" -eq 0 ]; then', source.index('identity="$ROOT/etc/orchestella/provision-input"\n'))]
    fake_bin = fixture / "identity-tools"
    write(fake_bin / "chpasswd", "#!/bin/sh\nwhile IFS=: read -r user ignored; do printf '%s\\n' \"$user\" >> \"$ROOT/accounts-applied\"; done\n")
    write(fake_bin / "id", "#!/bin/sh\nexit 1\n")
    write(fake_bin / "useradd", "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$ROOT/account-created\"\n")
    write(fake_bin / "visudo", "#!/bin/sh\nexit 0\n")
    state_dir = identity_root / "var/lib/orchestella/provision"
    state_dir.mkdir(parents=True, exist_ok=True)
    execute = (f"export ROOT={shlex.quote(unix(identity_root))}; export PATH={shlex.quote(unix(fake_bin))}:$PATH; "
               f"STATE_DIR={shlex.quote(unix(state_dir))}; LOG=/dev/null; distro=debian; "
               "fail_stage() { exit 1; }; " + block)
    result = subprocess.run(["sh", "-c", execute], capture_output=True, text=True, timeout=10)
    assert result.returncode == 0, result
    sudo = identity_root / "etc/sudoers.d/99-orchestella-user"
    assert "developer ALL=(ALL) NOPASSWD:ALL" in sudo.read_text()
    assert (identity_root / "accounts-applied").read_text().splitlines() == ["root", "developer"]
    assert marker.read_text() == "developer\n"
    checks.append("real_account_block_applies_ordinary_identity_and_preserves_marker")
    # An explicit no-sudo update revokes only the owned grant, not a separate user-authored file.
    (pending / "guest.user").write_text("developer\n")
    (pending / "guest.password").write_text("ChangedSyntheticSecret2026\n")
    (pending / "guest.sudo").write_text("0\n")
    user_sudo = sudo.parent / "user-policy"
    user_sudo.write_text("# User-owned configuration must survive\n")
    preserved = user_sudo.read_bytes()
    result = subprocess.run(["sh", "-c", execute], capture_output=True, text=True, timeout=10)
    assert result.returncode == 0, result
    assert not sudo.exists() and user_sudo.read_bytes() == preserved
    checks.append("explicit_no_sudo_revokes_only_managed_grant")
    applied = (identity_root / "accounts-applied").read_bytes()
    result = subprocess.run(["sh", "-c", execute], capture_output=True, text=True, timeout=10)
    assert result.returncode == 0 and (identity_root / "accounts-applied").read_bytes() == applied
    checks.append("unknown_absent_password_never_calls_account_password_tools")
    return checks


def serial_console_checks():
    # Replay the competing getty units seen in the captured Debian console and the
    # missing .device dependency seen in Fedora. Run the real installer, then resolve
    # its emitted units/drop-ins against a minimal vendor+generator filesystem.
    if os.name == "nt":
        raise SystemExit("serial console probes need Linux symlinks; run this mode with the existing WSL Python")
    source = (assets / "initramfs/init").read_text()
    start = source.index("install_serial_autologin_getty() {\n")
    finish = source.index("\nconfigure_systemd_network_dhcp() {", start)
    installer = source[start:finish]
    results = []
    failures = []

    def properties(path):
        data = {}
        for line in path.read_text().splitlines():
            if line and not line.startswith(("#", "[")) and "=" in line:
                key, value = line.split("=", 1)
                if not value:
                    data.pop(key, None)
                else:
                    data[key] = value
        return data

    for label, terminal, device_ready in [
        ("debian_duplicate_console_readers", "ttyAMA0", True),
        ("fedora_serial_device_unit_not_ready", "ttyAMA0", False),
        ("fedora_legacy_owned_console_and_generator", "ttyAMA0", True),
        ("avf_hvc0_has_one_console_owner", "hvc0", True),
    ]:
        tree = fixture / label
        local = tree / "etc/systemd/system"
        vendor = tree / "usr/lib/systemd/system"
        generated = tree / "run/systemd/generator/getty.target.wants"
        write(vendor / "console-getty.service", "[Service]\nTTYPath=/dev/console\nExecStart=-/sbin/agetty --noclear --keep-baud console 115200 vt220\n")
        write(vendor / "serial-getty@.service", "[Unit]\nBindsTo=dev-%i.device\n[Service]\nTTYPath=/dev/%I\nExecStart=-/sbin/agetty --noclear %I 115200 vt220\n")
        write(vendor / "getty@.service", "[Service]\nTTYPath=/dev/%I\nExecStart=-/sbin/agetty --noclear %I 38400 linux\n")
        write(local / "serial-getty@ttyS9.service.d/user.conf", "[Service]\nEnvironment=USER_CUSTOM=keep\n")
        custom = local / "serial-getty@ttyS9.service.d/user.conf"
        before = custom.read_bytes()
        write(tree / "etc/shadow", "root:synthetic-existing-password-hash:20000:0:99999:7:::\n")
        write(tree / "var/lib/orchestella/provision/credentials-v2", "input_sha256=synthetic-existing-fingerprint\n")
        preserved = {p: p.read_bytes() for p in [tree / "etc/shadow", tree / "var/lib/orchestella/provision/credentials-v2"]}
        if "legacy_owned" in label:
            write(local / "orchestella-console.service", "[Service]\nTTYPath=/dev/ttyAMA0\nExecStart=-/sbin/agetty --autologin root --noclear ttyAMA0 115200 vt220\n")
            (local / "getty.target.wants").mkdir()
            (local / "getty.target.wants/orchestella-console.service").symlink_to("/etc/systemd/system/orchestella-console.service")
        generated.mkdir(parents=True)
        for unit in ["console-getty.service", f"serial-getty@{terminal}.service", "getty@tty1.service"]:
            (generated / unit).symlink_to(f"/usr/lib/systemd/system/{unit}")
        # Model only the kernel command-line reader; all installation I/O is real.
        cmd = ("fail() { printf '%s\\n' \"$1\"; exit 1; }; "
               + "kernel_argument() { case \"$1\" in console) printf '%s\\n' "
               + terminal + ";; *) return 1;; esac; }; "
               + installer + "\ninstall_serial_autologin_getty " + shlex.quote(unix(tree)))
        subprocess.run(["sh", "-c", cmd], check=True, capture_output=True, text=True, timeout=10)
        assert custom.read_bytes() == before, "custom getty configuration must survive"

        def snapshot():
            return {str(p.relative_to(local)): ("link", os.readlink(p)) if p.is_symlink() else ("file", p.read_bytes())
                    for p in local.rglob("*") if p.is_symlink() or p.is_file()}

        first = snapshot()
        subprocess.run(["sh", "-c", cmd], check=True, capture_output=True, text=True, timeout=10)
        assert snapshot() == first, "console installation must be byte-idempotent"
        assert all(p.read_bytes() == content for p, content in preserved.items()), "existing passwords and input fingerprints must survive"
        units = {p.name for p in generated.iterdir()}
        for target in ["getty.target.wants", "multi-user.target.wants"]:
            if (local / target).exists():
                units.update(p.name for p in (local / target).iterdir())
        readers = []
        for unit in sorted(units):
            unit_path = local / unit
            if unit_path.is_symlink() and os.readlink(unit_path) == "/dev/null":
                continue
            if not unit_path.is_file():
                template = unit.split("@", 1)[0] + "@.service" if "@" in unit else unit
                unit_path = vendor / (template if "@" in unit else unit)
            if not unit_path.is_file():
                continue
            config = properties(unit_path)
            instance = unit.split("@", 1)[1].removesuffix(".service") if "@" in unit else ""
            dropin_names = ([unit.split("@", 1)[0] + "@.service.d"] if instance else []) + [unit + ".d"]
            for dropin in dropin_names:
                for path in sorted((local / dropin).glob("*.conf")):
                    config.update(properties(path))
            if not device_ready and any(".device" in config.get(key, "") for key in ["Requires", "BindsTo"]):
                continue
            tty = config.get("TTYPath", "").replace("%I", instance).replace("%i", instance)
            if tty == "/dev/console":
                tty = "/dev/" + terminal  # console=ttyAMA0 (QEMU), console=hvc0 (AVF)
            if tty == "/dev/" + terminal:
                readers.append((unit, config.get("ExecStart", "")))
        if len(readers) != 1 or "--autologin root" not in readers[0][1]:
            failures.append(f"{label}: expected one usable autologin reader; actual={readers}")
        else:
            results.append(label)
    # Reject unrecognized console paths before creating any units or masks.
    rejected = fixture / "rejected-console"
    cmd = ("fail() { printf '%s\\n' \"$1\"; exit 1; }; "
           "kernel_argument() { printf '%s\\n' '../../outside'; }; "
           + installer + "\ninstall_serial_autologin_getty " + shlex.quote(unix(rejected)))
    outcome = subprocess.run(["sh", "-c", cmd], capture_output=True, text=True, timeout=10)
    assert outcome.returncode != 0 and "serial-console-device-unsupported" in outcome.stdout
    assert not rejected.exists(), "invalid console input must not write any units"
    results.append("unsupported_console_refused_without_filesystem_changes")
    evidence = work / "serial-console-results.txt"
    evidence.write_text("\n".join(results + failures) + f"\nPASS count={len(results)} FAIL count={len(failures)}\nmode=real-installer-with-synthetic-vendor-and-generator-units\n", encoding="utf-8")
    shutil.copyfile(evidence, work / "serial-console-results.preserved.txt")
    assert not failures, "\n".join(failures)
    return results


def embedded_init_checks():
    # Exercise the production newc writer against the actual shipped base archive.
    # No image is repacked or published; the per-launch override lives in this fixture.
    import gzip
    import json
    repo = assets.parent
    java_source = fixture / "EmbeddedGuestInitProbe.java"
    write(java_source, """import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import io.github.nyzeep.orchestella.runtime.engineqemu.EmbeddedProvisionInput;
public final class EmbeddedGuestInitProbe {
    public static void main(String[] args) throws Exception {
        byte[] rootfs = "synthetic rootfs only".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] init = Files.readAllBytes(Path.of(args[0]));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rootfs));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EmbeddedProvisionInput.writeSegment(new ByteArrayInputStream(rootfs), rootfs.length,
                "synthetic-rootfs.tar.gz", "a".repeat(64), null, init, null, null, null,
                false, null, null, null, digest, out);
        Files.write(Path.of(args[1]), out.toByteArray());
    }
}
""")
    writer = repo / "runtime/engine-qemu/src/main/java/io/github/nyzeep/orchestella/runtime/engineqemu/EmbeddedProvisionInput.java"
    subprocess.run(["javac", "-J-XX:-UsePerfData", "-J-Djava.io.tmpdir=" + str(fixture), "-encoding", "UTF-8", "-d", str(fixture), str(writer), str(java_source)], check=True, timeout=30)
    segment = fixture / "guest-init-override.cpio.gz"
    subprocess.run(["java", "-XX:-UsePerfData", "-Djava.io.tmpdir=" + str(fixture), "-cp", str(fixture), "EmbeddedGuestInitProbe", str(assets / "initramfs/init"), str(segment)], check=True, timeout=30)
    base = repo / "app/src/main/resources/initramfs.cpio.gz"
    raw = gzip.decompress(base.read_bytes() + segment.read_bytes())
    position = 0
    inits = []
    trailers = 0
    while position < len(raw):
        while position < len(raw) and raw[position] == 0:
            position += 1
        if position == len(raw):
            break
        assert raw[position:position + 6] == b"070701", "base/override must contain valid newc headers"
        mode = int(raw[position + 14:position + 22], 16)
        size = int(raw[position + 54:position + 62], 16)
        namesize = int(raw[position + 94:position + 102], 16)
        name = raw[position + 110:position + 110 + namesize - 1].decode("utf-8")
        start = (position + 110 + namesize + 3) & ~3
        end = start + size
        assert end <= len(raw), "complete archive entry"
        if name.removeprefix("./") == "init":
            inits.append((mode, raw[start:end]))
        if name == "TRAILER!!!":
            trailers += 1
        position = (end + 3) & ~3
    expected = (assets / "initramfs/init").read_bytes()
    assert trailers == 2 and len(inits) == 2, "base archive and per-launch override must both be processed"
    assert inits[-1] == (0o100755, expected), "the executable final /init must be the current guest source"
    assert b"serial-console-mask" in inits[-1][1] and b"orchestella-console.service" in inits[-1][1]
    # The shipping chain consumes the resource copied by Gradle, then passes its bytes
    # into this same writer. A full APK check is left to the owning device/build agent.
    build = (repo / "app/build.gradle.kts").read_text()
    bootstrapper = (repo / "app/src/main/java/io/github/nyzeep/orchestella/QemuRuntimeBootstrapper.java").read_text()
    assert 'from(rootProject.file("guest-assets/initramfs/init"))' in build and 'rename { "guest_init" }' in build
    assert 'resourceStreams.apply("guest_init")' in bootstrapper and "EmbeddedProvisionInput.writeSegment(" in bootstrapper
    entry = (assets / "initramfs/runtime-entry.sh").read_text()
    runtime = (assets / "initramfs/runtime-functions.sh").read_text()
    restart = entry.split("if grep -Fqx 'provisioning_version=1'", 1)[1].split("elif grep -Fqx", 1)[0]
    assert "boot_guest_root" in restart and "install_native_guest_runtime /newroot" in entry
    assert 'install_serial_autologin_getty "$target_root"' in runtime
    report = {"checks": ["real_writer_last_init_bytes_and_executable_mode", "resource_copy_and_bootstrap_writer_chain", "existing_root_restart_installs_console_fix"],
              "currentInitSha256": hashlib.sha256(expected).hexdigest(), "archiveCount": trailers,
              "baseContainsCurrentInit": inits[0][1] == expected, "deviceValidated": False}
    evidence = work / "embedded-guest-init-results.json"
    evidence.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    shutil.copyfile(evidence, work / "embedded-guest-init-results.preserved.json")
    return report["checks"]


if "--embedded-init-only" in sys.argv:
    checks.extend(embedded_init_checks())
    shutil.rmtree(fixture)
    print(f"EMBEDDED_GUEST_INIT_TEST_OK checks={len(checks)}")
    raise SystemExit(0)


if "--serial-console-only" in sys.argv:
    checks.extend(serial_console_checks())
    shutil.rmtree(fixture)
    print(f"SERIAL_CONSOLE_TEST_OK checks={len(checks)}")
    raise SystemExit(0)


if "--identity-only" in sys.argv:
    import json
    identity_checks()
    evidence = work / "identity-results.json"
    evidence.write_text(json.dumps({"checks": checks, "passed": len(checks), "guestValidated": False}, indent=2), encoding="utf-8")
    shutil.copyfile(evidence, work / "identity-results.preserved.json")
    print(f"IDENTITY_TEST_OK checks={len(checks)} guestValidated=false")
    raise SystemExit(0)


if "--manual-recovery-only" in sys.argv:
    checks.extend(manual_recovery_checks())
    evidence = work / "manual-recovery-results.txt"
    evidence.write_text("\n".join(checks) + f"\nPASS count={len(checks)}\nmode=synthetic-exec-dispatch-with-preserved-root-state\n", encoding="utf-8")
    shutil.copyfile(evidence, work / "manual-recovery-results.preserved.txt")
    shutil.rmtree(fixture)
    print(f"MANUAL_RECOVERY_TEST_OK checks={len(checks)}")
    raise SystemExit(0)

checks.extend(recovery_shell_checks())
if "--recovery-shell-only" in sys.argv:
    evidence = work / "recovery-shell-selection-results.txt"
    evidence.write_text("\n".join(checks) + f"\nPASS count={len(checks)}\nmode=synthetic-ELF-and-loader-probe\n", encoding="utf-8")
    shutil.copyfile(evidence, work / "recovery-shell-selection-results.preserved.txt")
    shutil.rmtree(fixture)
    print(f"RECOVERY_SHELL_SELECTION_TEST_OK checks={len(checks)}")
    raise SystemExit(0)


if os.name != "nt":
    checks.extend(serial_console_checks())
else:
    skips.append("SERIAL_CONSOLE_NATIVE_SYMLINK_PROBE_SKIPPED platform=win32 run=--serial-console-only-under-WSL")


def run(mode="--native", extra=""):
    cmd = (f"export PATH={shlex.quote(unix(stubs))}:$PATH; "
           f"export ORCHESTELLA_PROVISION_ROOT={shlex.quote(unix(root))}; "
           f"{extra}sh {shlex.quote(unix(script))} {mode}")
    return subprocess.run(["sh", "-c", cmd], text=True, capture_output=True, timeout=30)


def expect(name, outcome, status, stage=None):
    data = dict(line.split("=", 1) for line in state.read_text().splitlines())
    assert data["status"] == status, (name, outcome.returncode, outcome.stdout, outcome.stderr, data)
    if stage:
        assert data["stage"] == stage, (name, data)
    assert done.exists() == (status == "ready"), (name, data)
    assert (outcome.returncode == 0) == (status in ("ready", "awaiting-init", "action-required")), (name, outcome.stderr)
    checks.append(name)


(root / "install-failed").touch()
expect("package_install_failure_not_success", run(), "failed", "package-install")
assert not (root / "usr/sbin/policy-rc.d").exists()
(root / "install-failed").unlink()
expect("retry_after_install_failure", run("--retry"), "ready", "verified")
assert "PasswordAuthentication no" in (root / "etc/ssh/sshd_config.orchestella").read_text()
assert "PermitEmptyPasswords no" in (root / "etc/ssh/sshd_config.orchestella").read_text()
checks.append("missing_credentials_never_enable_password_ssh")
calls = (root / "calls").read_text()
expect("idempotent_verified_retry", run("--retry"), "ready", "verified")
assert (root / "calls").read_text() == calls, "idempotent run performed package transactions"
(root / "inventory-missing").touch()
expect("inventory_failure_not_success", run(), "failed", "package-verification")
(root / "inventory-missing").unlink()
(root / "service-failed").touch()
expect("service_failure_not_success", run(), "failed", "service-verification")
(root / "service-failed").unlink()
(root / "var/lib/orchestella/provision/packages-v2").unlink(missing_ok=True)
(root / "offline").touch()
expect("offline_preserves_retry_state", run(), "failed", "repository-refresh")
(root / "offline").unlink()
expect("network_restored_retry", run("--retry"), "ready", "verified")
# Corrupted native init must force a reinstall even if dpkg still considers the package installed.
write(root / "usr/lib/systemd/systemd", "#!/bin/sh\n# Orchestella minimal Linux init supervisor\n")
expect("native_init_invalid_not_success", run(), "failed", "init-verification")
(root / "repair-init").touch()
expect("native_init_reinstall_retry", run("--retry"), "ready", "verified")
assert "--reinstall systemd systemd-sysv" in (root / "calls").read_text()
write(root / "proc/1/comm", "sh\n")
expect("bootstrap_requires_real_pid1", run("--bootstrap"), "awaiting-init", "native-handoff")
assert not done.exists()
write(root / "proc/1/comm", "systemd\n")
calls_before_handoff = (root / "calls").read_bytes()
(root / "offline").touch()
expect("post_handoff_services_verify", run(), "ready", "verified")
assert (root / "calls").read_bytes() == calls_before_handoff
(root / "offline").unlink()
checks.append("native_handoff_reuses_verified_baseline_even_offline")
done.unlink()
(root / "locale-missing").touch()
expect("missing_locale_repaired_without_reinstalling_packages", run(), "ready", "verified")
locale_calls = (root / "locale-calls").read_bytes()
done.unlink()
expect("generated_locale_is_not_repeated_on_handoff", run(), "ready", "verified")
assert (root / "locale-calls").read_bytes() == locale_calls
# A stale early-success flag from the old implementation is never trusted.
write(done, "")
(root / "var/lib/orchestella/provision/packages-v2").unlink(missing_ok=True)
(root / "offline").touch()
expect("legacy_early_marker_not_success", run(), "failed", "repository-refresh")
(root / "offline").unlink()
write(root / "etc/apt/sources.list", """# orchestella-mirror-policy=multi-tier
# Tier 1: unavailable
 deb http://ignored.invalid/debian trixie main
deb http://bad.invalid/debian trixie main
# Tier 2: available
deb http://good.invalid/debian trixie main
# Tier 3: available
deb http://official.invalid/debian trixie main
""")
expect("apt_tiers_fallback_sequentially", run(), "ready", "verified")
active = (root / "etc/apt/sources.list").read_text()
assert "good.invalid" in active and "bad.invalid" not in active and "official.invalid" not in active
# Package and state commands never print or record the synthetic password.
secret = "SyntheticPasswordValue2026"
write(root / "etc/orchestella/provision-input/guest.password", secret)
write(stubs / "chpasswd", "#!/bin/sh\ninput=\"$(cat)\"\nexit 0\n")
expect("pending_credentials_bypass_ready_shortcut", run(), "ready", "verified")
assert not (root / "etc/orchestella/provision-input/guest.password").exists()
assert secret not in (root / "var/log/orchestella-provision.log.txt").read_text()
assert "PasswordAuthentication yes" in (root / "etc/ssh/sshd_config.orchestella").read_text()
assert "PermitRootLogin yes" in (root / "etc/ssh/sshd_config.orchestella").read_text()
assert "PermitEmptyPasswords no" in (root / "etc/ssh/sshd_config.orchestella").read_text()
checks.append("applied_random_credentials_enable_authenticated_ssh")
# Missing/invalid SSH configuration cannot produce success.
(root / "ssh-invalid").touch()
expect("ssh_config_failure_not_success", run(), "failed", "ssh-configuration")
(root / "ssh-invalid").unlink()
# Existing ownership policy remains byte-identical; provisioning never deletes it.
write(root / "usr/sbin/policy-rc.d", "#!/bin/sh\nexit 101\n# user policy\n")
before = (root / "usr/sbin/policy-rc.d").read_bytes()
expect("user_package_policy_preserved", run(), "ready", "verified")
assert (root / "usr/sbin/policy-rc.d").read_bytes() == before
assert "restart ssh.service" in (root / "service-calls").read_text()
checks.append("managed_ssh_configuration_applied_to_running_service")
# HTTPS-only mirror bootstraps CAs using signed official APT input without overwriting user sources.
write(root / "etc/apt/sources.list", "deb https://selected.invalid/debian trixie main\n")
user_sources = (root / "etc/apt/sources.list").read_bytes()
(root / "etc/ssl/certs/ca-certificates.crt").unlink()
done.unlink()
(root / "install-failed").touch()
expect("ca_bootstrap_failure_not_success", run(), "failed", "certificate-bootstrap-install")
assert (root / "etc/apt/sources.list").read_bytes() == user_sources
(root / "install-failed").unlink()
expect("ca_bootstrap_retry_preserves_selected_mirror", run("--retry"), "ready", "verified")
assert (root / "etc/apt/sources.list").read_bytes() == user_sources
ca_list = (root / "var/lib/orchestella/provision/ca-bootstrap.list").read_text()
assert "deb http://deb.debian.org/debian/ trixie main" in ca_list
assert "trusted=yes" not in ca_list
assert "Dir::Etc::sourceparts=-" in (root / "calls").read_text()
checks.append("ca_bootstrap_retains_apt_signatures_and_sources")

# Init template functions operate on synthetic filesystem trees, never /dev or a real root volume.
function_fixture = fixture / "function-fixture"
function_fixture.mkdir()
function_path = unix(assets / "initramfs/runtime-functions.sh")
init_path = assets / "initramfs/init"
launcher_source = init_path.read_text()
launcher_begin = launcher_source.index("install_systemd_guest_agent_service() {")
launcher_end = launcher_source.index("install_official_debian_systemd_flavor() {", launcher_begin)
write(fixture / "launcher-functions.sh", launcher_source[launcher_begin:launcher_end])
# ELF headers are synthetic. chroot is replaced by a fixture-only probe.
for name, distro in [("systemd", "debian"), ("alpine", "alpine")]:
    tree = function_fixture / name
    write(tree / "etc/os-release", f"ID={distro}\n")
    write(tree / "usr/lib/systemd/systemd", "\x7fELFsynthetic-systemd")
    write(tree / "bin/busybox", "\x7fELFsynthetic-busybox")
    write(tree / "sbin/openrc", "#!/bin/sh\nexit 0\n")
    if os.name != "nt":
        (tree / "sbin/init").symlink_to("/usr/lib/systemd/systemd" if distro == "debian" else "../bin/busybox")
    else:
        # MSYS ln creates a portable link representation; no Windows symlink privilege required.
        link_env = dict(os.environ)
        link_env["MSYS"] = "winsymlinks:nativestrict"
        linked = subprocess.run(["sh", "-c", f"ln -s {'/usr/lib/systemd/systemd' if distro == 'debian' else '../bin/busybox'} {shlex.quote(unix(tree / 'sbin/init'))}"], env=link_env, capture_output=True)
        if linked.returncode:
            skips.append(f"NATIVE_SYMLINK_EXECUTION_SKIPPED distro={distro} platform=win32")
            print(skips[-1] + "; production no-write-through guard checked")
    native_path = tree / ("usr/lib/systemd/systemd" if distro == "debian" else "bin/busybox")
    original = hashlib.sha256(native_path.read_bytes()).hexdigest()
    cmd = (f". {shlex.quote(function_path)}; . {shlex.quote(unix(fixture / 'launcher-functions.sh'))}; "
           "fail() { printf '%s\\n' \"$1\"; exit 1; }; "
           "kernel_argument() { return 1; }; chroot() { return 0; }; "
           f"select_native_guest_init {shlex.quote(unix(tree))}")
    result = subprocess.run(["sh", "-c", cmd], text=True, capture_output=True, check=True)
    assert ("systemd" if distro == "debian" else "/bin/busybox") in result.stdout
    # Recovery installation must preserve native binaries and their original init link.
    # Private copy uses /bin/busybox unavailable on Git Bash, so inspect production function too.
    assert 'cat > "$target_root/sbin/init"' not in launcher_source
    assert hashlib.sha256(native_path.read_bytes()).hexdigest() == original
    checks.append(f"{distro}_native_init_selection_preserves_binary")

# OpenRC migration retains a user getty/custom service and is byte-idempotent on restart.
alpine = function_fixture / "alpine"
user_inittab = "::sysinit:/sbin/openrc sysinit\n::sysinit:/sbin/openrc boot\n::wait:/sbin/openrc default\nttyAMA0::respawn:/sbin/agetty ttyAMA0 9600\n::once:/usr/local/bin/user-startup\n"
legacy_proxy_entries = "::respawn:/usr/sbin/orchestella-proxy-relay\n::respawn:/usr/sbin/droidlayer-proxy-relay\n"
write(alpine / "etc/inittab", user_inittab + legacy_proxy_entries)
openrc_cmd = (f". {shlex.quote(function_path)}; fail() {{ exit 1; }}; "
              f"install_alpine_native_services {shlex.quote(unix(alpine))}")
subprocess.run(["sh", "-c", openrc_cmd], check=True)
managed = (alpine / "etc/inittab").read_bytes()
subprocess.run(["sh", "-c", openrc_cmd], check=True)
assert (alpine / "etc/inittab").read_bytes() == managed
assert user_inittab in managed.decode()
assert managed.count(b"/sbin/openrc default") == 1
assert managed.count(b"ttyAMA0::respawn:") == 1
assert b"::respawn:/usr/sbin/orchestella-proxy-relay" not in managed
assert b"::respawn:/usr/sbin/droidlayer-proxy-relay" not in managed
checks.append("legacy_proxy_respawn_removed_without_overwriting_custom_inittab")
checks.append("alpine_user_inittab_preserved_and_idempotent")
# Restart applies changed host credentials but does not reset guest-owned password changes.
credential_input = fixture / "credential-input"
write(credential_input / "guest.password", secret)
stage_credentials_cmd = (f". {shlex.quote(function_path)}; fail() {{ exit 1; }}; log() {{ printf '%s\\n' \"$*\"; }}; "
                         f"stage_pending_guest_credentials {shlex.quote(unix(root))} {shlex.quote(unix(credential_input))}")
subprocess.run(["sh", "-c", stage_credentials_cmd], check=True)
assert not (root / "etc/orchestella/provision-input/guest.password").exists()
checks.append("unchanged_host_credentials_not_reapplied_on_restart")
write(credential_input / "guest.password", "ChangedSyntheticPassword2026")
subprocess.run(["sh", "-c", stage_credentials_cmd], check=True)
assert (root / "etc/orchestella/provision-input/guest.password").exists()
expect("changed_host_credentials_apply_before_ready", run(), "ready", "verified")
write(credential_input / "guest.password", "short")
subprocess.run(["sh", "-c", stage_credentials_cmd], check=True)
expect("legacy_weak_credentials_require_user_action_not_ready", run(), "action-required", "credentials")
assert "PasswordAuthentication no" in (root / "etc/ssh/sshd_config.orchestella").read_text()
write(credential_input / "guest.password", "ChangedSyntheticPassword2026")
subprocess.run(["sh", "-c", stage_credentials_cmd], check=True)
expect("legacy_credential_action_recovered_without_reset", run(), "ready", "verified")
# Damaged BusyBox can only be replaced when it is the recognized old project supervisor.
write(root / "etc/os-release", "ID=alpine\nVERSION_ID=3.24.1\n")
write(root / "bin/busybox", "#!/bin/sh\n# Orchestella minimal Linux init supervisor\n")
write(root / "usr/lib/orchestella/busybox", chr(127) + "ELFverified static fixture\n")
write(root / "sbin/openrc", "#!/bin/sh\nexit 0\n")
for service in ["dbus", "sshd", "droidlayer-guest-agent", "orchestella-firstboot"]:
    write(root / f"etc/init.d/{service}", "#!/bin/sh\nexit 0\n")
write(stubs / "apk", """#!/bin/sh
printf '%s\\n' "$*" >> "$ORCHESTELLA_PROVISION_ROOT/apk-calls"
case "$1" in
    info) [ -f "$ORCHESTELLA_PROVISION_ROOT/inventory" ] || exit 1 ;;
    fix|add) printf 'installed\\n' > "$ORCHESTELLA_PROVISION_ROOT/inventory" ;;
esac
exit 0
""")
write(stubs / "rc-service", "#!/bin/sh\nexit 0\n")
if os.name == "nt":
    # Git Bash cannot create dangling absolute Unix symlinks. Model the link syscall
    # for the transaction probe; real links still require the Linux/VM acceptance gate.
    write(stubs / "ln", "#!/bin/sh\nfor target; do :; done\nprintf 'synthetic-link\\n' > \"$target\"\n")
    skips.append("OPENRC_ABSOLUTE_SYMLINK_EXECUTION_MODELED platform=win32")
expect("known_damaged_busybox_restored_before_apk_scripts", run("--bootstrap"), "awaiting-init", "native-handoff")
assert (root / "bin/busybox").read_bytes().startswith(bytes([127]) + b"ELF")
apk_calls = (root / "apk-calls").read_text()
assert apk_calls.index("fix --no-cache --reinstall busybox") < apk_calls.index("add --no-cache")
write(root / "bin/busybox", "unknown damaged binary\n")
expect("unknown_busybox_damage_preserved", run("--bootstrap"), "failed", "init-repair")
assert (root / "bin/busybox").read_text() == "unknown damaged binary\n"
checks.append("busybox_restoration_requires_recognized_corruption")
# Disabled/missing shares must not create failed systemd mounts; custom fstab rows remain.
share_root = fixture / "share-root"
write(share_root / "etc/fstab", "# user-owned fstab\ncustom /srv/user ext4 defaults 0 0\ndownloads\t/mnt/downloads\t9p\ttrans=virtio,version=9p2000.L,ro,nofail\t0\t0\nshared\t/mnt/shared\t9p\ttrans=virtio,version=9p2000.L,ro,nofail\t0\t0\n")
virtio_fixture = fixture / "virtio"
write(virtio_fixture / ".orchestella-virtio-fixture", "synthetic tags\n")
share_cmd = (f". {shlex.quote(function_path)}; fail() {{ exit 1; }}; "
             f"export ORCHESTELLA_VIRTIO_FIXTURE={shlex.quote(unix(virtio_fixture))}; "
             f"configure_fstab_mounts {shlex.quote(unix(share_root))}")
subprocess.run(["sh", "-c", share_cmd], check=True)
no_shares = (share_root / "etc/fstab").read_text()
assert "downloads\t" not in no_shares and "shared\t" not in no_shares
assert "custom /srv/user ext4 defaults 0 0" in no_shares
checks.append("absent_share_tags_never_create_mount_units")
write(virtio_fixture / "virtio9/mount_tag", "downloads\n")
subprocess.run(["sh", "-c", share_cmd], check=True)
with_share = (share_root / "etc/fstab").read_bytes()
assert b"downloads\t/mnt/downloads\t9p" in with_share and b"shared\t/mnt/shared" not in with_share
subprocess.run(["sh", "-c", share_cmd], check=True)
assert (share_root / "etc/fstab").read_bytes() == with_share
checks.append("physical_share_tag_enables_readonly_managed_mount_idempotently")

# Fail closed on digest/size/name errors before formatting.
inputs = fixture / "input"
write(inputs / "rootfs.tar.gz", "synthetic archive")
payload = (inputs / "rootfs.tar.gz").read_bytes()
write(inputs / "rootfs.file", "rootfs.tar.gz\n")
write(inputs / "rootfs.sha256", hashlib.sha256(payload).hexdigest() + "\n")
write(inputs / "rootfs.size", str(len(payload)) + "\n")
verify_cmd = (f". {shlex.quote(function_path)}; fail() {{ printf '%s\\n' \"$1\"; exit 1; }}; "
              f"verify_rootfs_input {shlex.quote(unix(inputs))}")
assert subprocess.run(["sh", "-c", verify_cmd], capture_output=True).returncode == 0
for name, invalid, expected in [
    ("rootfs.sha256", "", "rootfs-checksum-missing"),
    ("rootfs.sha256", "0" * 64 + "\n", "rootfs-checksum"),
    ("rootfs.size", "1\n", "rootfs-size-mismatch"),
    ("rootfs.file", "../rootfs.tar.gz\n", "rootfs-filename-invalid"),
]:
    saved = (inputs / name).read_text()
    write(inputs / name, invalid)
    result = subprocess.run(["sh", "-c", verify_cmd], text=True, capture_output=True)
    assert result.returncode != 0 and expected in result.stdout, (name, result)
    write(inputs / name, saved)
    checks.append(expected)
# Execute checksum progress and timeout classification without waiting 30 minutes.
# The real successful hash still reads the complete synthetic archive; only the timer is modeled.
checksum_prelude = ("log() { printf '%s\\n' \"$*\"; }; "
                    "sleep() { if [ \"$1\" = 30 ]; then command sleep 0.01; else command sleep \"$@\"; fi; }; ")
checksum_success = (checksum_prelude
                    + 'timeout() { [ "$1" = 1800 ] || return 99; shift; command sleep 0.08; "$@"; }; '
                    + verify_cmd)
result = subprocess.run(["sh", "-c", checksum_success], text=True, capture_output=True, timeout=10)
assert result.returncode == 0 and "phase=input-verification status=running heartbeat=1" in result.stdout
checks.append("full_rootfs_hash_reports_progress_without_skipping_bytes")
checksum_timeout = (checksum_prelude
                    + 'timeout() { [ "$1" = 1800 ] || return 99; return 124; }; '
                    + verify_cmd)
result = subprocess.run(["sh", "-c", checksum_timeout], text=True, capture_output=True, timeout=10)
assert result.returncode != 0 and "rootfs-checksum-timeout" in result.stdout
checks.append("rootfs_checksum_deadline_fails_before_any_format")
entry = (assets / "initramfs/runtime-entry.sh").read_text()
assert entry.index("verify_rootfs_input") < entry.index("/sbin/mkfs.ext4")
assert entry.index("existing-root-volume-unrecognized") < entry.index("/sbin/mkfs.ext4")
assert "ORCHESTELLA_EMPTY_ROOT_VOLUME_V2" in entry
checks.append("existing_root_volume_never_reformatted")
embedded_entry = launcher_source.split("# ORCHESTELLA_RUNTIME_ENTRY_BEGIN\n", 1)[1].split("# ORCHESTELLA_RUNTIME_ENTRY_END\n", 1)[0]
assert embedded_entry == entry
assert embedded_entry.splitlines()[0] == "mount -t devtmpfs devtmpfs /dev || fail mount-devtmpfs"
assert "devtmount" not in launcher_source
checks.append("full_generated_entry_bytes_and_mount_arguments_match")
terminal_hook = (assets / "shell/20-orchestella-terminal-integration.sh").read_text()
embedded_hook = launcher_source.split("<<'ORCHESTELLA_TERMINAL_HOOK_EOF'\n", 1)[1].split("ORCHESTELLA_TERMINAL_HOOK_EOF\n", 1)[0]
assert embedded_hook == terminal_hook
assert "install_guest_terminal_integration /newroot" in entry
assert '[ -n "${BASH_VERSION:-}" ] && [ -r /etc/orchestella/shell/20-orchestella-terminal-integration.sh ]' in launcher_source
checks.append("terminal_worker_hook_embedded_exactly_and_sourced_for_bash")
canonical_prompt = (assets / "shell/orchestella-prompt.sh").read_text()
prompt_anchor = 'cat > "$target_root/etc/orchestella/shell/orchestella-prompt.sh" <<\'EOF\'\n'
embedded_prompt = launcher_source.split(prompt_anchor, 1)[1].split("EOF\n", 1)[0]
assert embedded_prompt == canonical_prompt
assert "_prompt_user" in embedded_prompt and "_prompt_host" in embedded_prompt
checks.append("terminal_worker_user_host_prompt_embedded_exactly")
# Generated dispatcher/recovery path is syntactically checked and contains no success-before-handoff.
assert "select_native_guest_init" in launcher_source
assert "reason=native-init-missing-or-failed baseline=not-ready" in launcher_source
assert "exec /bin/busybox init" in launcher_source and 'exec "$native"' in launcher_source
assert "touch /opt/orchestella/.firstboot-done" not in launcher_source
checks.append("init_failure_uses_explicit_recovery_not_ready")
subprocess.run([sys.executable, str(assets / "scripts/sync-guest-init.py"), "--check"], check=True)
for path in [init_path, script, assets / "initramfs/runtime-functions.sh", assets / "initramfs/runtime-entry.sh"]:
    subprocess.run(["sh", "-n", unix(path)], check=True)
checks.append("embedded_modules_syntax_and_template_sync")
# Keep only compact, synthetic verification evidence; remove the fixture tree after completion.
evidence = work / "guest-runtime-provision-results.txt"
evidence.write_text("\n".join(checks + skips) + f"\nPASS count={len(checks)} skipped_native_symlink_execution={len(skips)}\n", encoding="utf-8")
shutil.copyfile(evidence, work / "guest-runtime-provision-results.preserved.txt")
shutil.rmtree(fixture)
print(f"GUEST_RUNTIME_PROVISION_TEST_OK checks={len(checks)} evidence={evidence}")
