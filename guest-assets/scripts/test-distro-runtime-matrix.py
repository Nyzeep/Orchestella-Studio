#!/usr/bin/env python3
"""Execute all catalog distro provisioning paths against synthetic package/service backends.

These tests prove command routing, signed-manager defaults, failure propagation and retries.
They do not claim the distro repositories or real ARM64 package transactions succeeded.
"""
from pathlib import Path
import os
import shlex
import shutil
import subprocess
import sys
import tempfile

assets = Path(__file__).resolve().parent.parent
work = Path(os.environ.get("GUEST_RUNTIME_TEST_ROOT", ""))
if not os.environ.get("GUEST_RUNTIME_TEST_ROOT") or not work.is_dir():
    raise SystemExit("GUEST_RUNTIME_TEST_ROOT must name an approved existing evidence directory")
fixture = Path(tempfile.mkdtemp(prefix="distro-runtime-matrix-", dir=work))
script = fixture / "orchestella-guest-provision.sh"
shutil.copyfile(assets / "scripts/orchestella-guest-provision.sh", script)
functions = fixture / "runtime-functions.sh"
shutil.copyfile(assets / "initramfs/runtime-functions.sh", functions)


def unix(path):
    return subprocess.check_output(["cygpath", "-u", str(path)], text=True).strip() if os.name == "nt" else str(path)


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")
    path.chmod(0o755)


profiles = [
    ("debian", "13", "apt"), ("ubuntu", "24.04", "apt"), ("fedora", "43", "dnf"),
    ("archarm", "rolling", "pacman"), ("opensuse-leap", "16.0", "zypper"),
    ("almalinux", "9", "dnf"), ("rocky", "9.6", "dnf"),
]
diagnostics_only = "--failure-diagnostics-only" in sys.argv
if diagnostics_only:
    profiles = [profile for profile in profiles if profile[0] in ("almalinux", "opensuse-leap")]
checks = []
for distro, version, manager in profiles:
    profile = fixture / distro
    root = profile / "root"
    commands = profile / "bin"
    write(root / ".orchestella-provision-fixture", "synthetic\n")
    write(root / "etc/os-release", f"ID={distro}\nVERSION_ID={version}\n")
    write(root / "proc/1/comm", "systemd\n")
    write(root / "usr/lib/systemd/systemd", chr(127) + "ELFsynthetic-init\n")
    if distro == "fedora":
        write(root / "usr/lib/systemd/system/dbus-broker.service", "[Install]\nAlias=dbus.service\n")
    write(root / "etc/apt/sources.list", "deb http://example.invalid/repo stable main\n")
    write(root / "etc/ssl/certs/ca-certificates.crt", "synthetic CA store\n")
    write(root / "etc/pacman.d/gnupg/pubring.gpg", "synthetic provisioned keyring\n")
    (root / "usr/sbin").mkdir(parents=True)
    backend = """#!/bin/sh
name=${0##*/}
printf '%s %s\\n' "$name" "$*" >> "$ORCHESTELLA_PROVISION_ROOT/commands"
case "$name" in
    timeout)
        budget=$1; shift
        case " $* " in
            *' update '*|*' makecache '*|*' refresh '*)
                [ "$budget" = 1800 ] || exit 88
                if [ -f "$ORCHESTELLA_PROVISION_ROOT/timeout-refresh" ]; then
                    exit "$(cat "$ORCHESTELLA_PROVISION_ROOT/timeout-refresh")"
                fi ;;
            *' install '*|*' -Syu '*|*' reinstall '*)
                [ "$budget" = 1800 ] || exit 88
                if [ -f "$ORCHESTELLA_PROVISION_ROOT/timeout-install" ]; then
                    exit "$(cat "$ORCHESTELLA_PROVISION_ROOT/timeout-install")"
                fi ;;
        esac
        exec "$@" ;;
    dpkg-query) [ -f "$ORCHESTELLA_PROVISION_ROOT/inventory" ] || exit 1; printf installed; exit 0 ;;
    rpm)
        if [ "$1" = -q ]; then
            case "$2" in
                curl-minimal|coreutils-single) [ -f "$ORCHESTELLA_PROVISION_ROOT/minimal-rpm-variants" ] || exit 1 ;;
                wget1-wget) [ -f "$ORCHESTELLA_PROVISION_ROOT/wget1-provider" ] || exit 1 ;;
                *) [ -f "$ORCHESTELLA_PROVISION_ROOT/inventory" ] || exit 1 ;;
            esac
        fi
        exit 0 ;;
    pacman)
        if [ "$1" = -Q ]; then [ -f "$ORCHESTELLA_PROVISION_ROOT/inventory" ] || exit 1; exit 0; fi ;;
    systemctl)
        case " $* " in
            *' is-active '*) [ ! -f "$ORCHESTELLA_PROVISION_ROOT/services-failed" ] || exit 3 ;;
        esac
        exit 0 ;;
    sshd) [ ! -f "$ORCHESTELLA_PROVISION_ROOT/config-failed" ] || exit 1; exit 0 ;;
    ssh-keygen)
        while [ "$#" -gt 0 ]; do
            if [ "$1" = -f ]; then shift; printf 'synthetic key\\n' > "$1"; break; fi
            shift
        done
        exit 0 ;;
    locale) printf 'en_US.utf8\\n'; exit 0 ;;
    sync)
        current_state="$ORCHESTELLA_PROVISION_ROOT/var/lib/orchestella/provision/state"
        current_log="$ORCHESTELLA_PROVISION_ROOT/var/log/orchestella-provision.log.txt"
        grep -Fqx 'status=failed' "$current_state" || exit 9
        tail -n 1 "$current_log" | grep -Fq 'ORCHESTELLA_GUEST_PROVISION status=failed' || exit 9
        [ ! -f "$ORCHESTELLA_PROVISION_ROOT/opt/orchestella/.firstboot-done" ] || exit 9
        printf 'failure-state-and-report-visible\\n' >> "$ORCHESTELLA_PROVISION_ROOT/failure-sync-events"
        if [ -f "$ORCHESTELLA_PROVISION_ROOT/sync-failed" ]; then exit 5; fi
        printf 'SYNTHETIC_SYNC_COMPLETED_BEFORE_NOTIFICATION\\n'
        exit 0 ;;
    dbus-uuidgen|dpkg|locale-gen|update-ca-certificates|update-ca-trust|pacman-key) exit 0 ;;
esac
case " $* " in
    *' update '*|*' makecache '*|*' refresh '*)
        if [ -f "$ORCHESTELLA_PROVISION_ROOT/refresh-exit-code" ]; then
            printf 'SYNTHETIC_PACKAGE_ERROR code=%s\\n' "$(cat "$ORCHESTELLA_PROVISION_ROOT/refresh-exit-code")" >&2
            exit "$(cat "$ORCHESTELLA_PROVISION_ROOT/refresh-exit-code")"
        fi
        [ ! -f "$ORCHESTELLA_PROVISION_ROOT/offline" ] || exit 100 ;;
esac
case " $* " in
    *' install '*|*' -Syu '*|*' -S '*|*' reinstall '*)
        [ ! -f "$ORCHESTELLA_PROVISION_ROOT/offline" ] || exit 100
        [ ! -f "$ORCHESTELLA_PROVISION_ROOT/install-failed" ] || exit 100
        printf 'installed\\n' > "$ORCHESTELLA_PROVISION_ROOT/inventory" ;;
esac
exit 0
"""
    names = ["timeout", "dpkg-query", "rpm", "pacman", "systemctl", "sshd", "ssh-keygen", "locale", "dbus-uuidgen",
             "dpkg", "locale-gen", "update-ca-certificates", "update-ca-trust", "pacman-key", "apt-get", "dnf", "microdnf", "zypper"]
    for name in names:
        write(commands / name, backend)
    if diagnostics_only:
        write(commands / "sync", backend)
    catalog_id = "arch" if distro == "archarm" else "opensuse" if distro.startswith("opensuse") else distro
    cmd = (f"export PATH={shlex.quote(unix(commands))}:$PATH; "
           f"export ORCHESTELLA_EXPECTED_DISTRO={shlex.quote(catalog_id)}; "
           f"export ORCHESTELLA_PROVISION_ROOT={shlex.quote(unix(root))}; "
           f"sh {shlex.quote(unix(script))}")
    state = root / "var/lib/orchestella/provision/state"
    done = root / "opt/orchestella/.firstboot-done"

    def run(mode="--native"):
        return subprocess.run(["sh", "-c", cmd + " " + mode], text=True, capture_output=True, timeout=30)

    def expect(label, status, stage=None):
        result = run()
        data = dict(line.split("=", 1) for line in state.read_text().splitlines())
        assert data["status"] == status, (distro, label, result, data)
        if stage:
            assert data["stage"] == stage, (distro, label, data)
        assert done.exists() == (status == "ready"), (distro, label, data)
        assert (result.returncode == 0) == (status == "ready"), (distro, label, result)
        checks.append(f"{distro}:{label}")

    if diagnostics_only:
        for code in (1, 4, 124):
            write(root / "refresh-exit-code", str(code))
            result = run()
            data = dict(line.split("=", 1) for line in state.read_text().splitlines())
            assert result.returncode != 0 and data["status"] == "failed" and not done.exists()
            assert data["stage"] == "repository-refresh"
            assert data["operation_exit_code"] == str(code), (distro, "original_operation_code", data)
            assert data.get("last_operation_exit_code") == str(code), (distro, "last_operation_code_missing_or_reset", data)
            assert data.get("last_operation_stage") == "repository-refresh", (distro, data)
            assert f"operation_exit_code={code}" in result.stdout and f"last_operation_exit_code={code}" in result.stdout
            assert (data["reason"] == "operation-timeout") == (code == 124), (distro, data)
            # Only fixed numeric diagnostic fields go to console. Package stderr remains
            # local, for the main driver's bounded and redacted failure-tail capture.
            assert "SYNTHETIC_PACKAGE_ERROR" not in result.stdout
            assert f"SYNTHETIC_PACKAGE_ERROR code={code}" in (root / "var/log/orchestella-provision.log.txt").read_text()
            assert (root / "failure-sync-events").exists(), (distro, "failure_not_synced_before_notification", result)
            assert "SYNTHETIC_SYNC_COMPLETED_BEFORE_NOTIFICATION" in result.stdout
            assert result.stdout.index("SYNTHETIC_SYNC_COMPLETED_BEFORE_NOTIFICATION") < result.stdout.index("ORCHESTELLA_GUEST_PROVISION status=failed")
            checks.append(f"{distro}:refresh_failure_{code}_persists_and_reports_actual_return")
        write(root / "sync-failed", "synthetic flush failure\n")
        write(root / "refresh-exit-code", "4")
        result = run()
        data = dict(line.split("=", 1) for line in state.read_text().splitlines())
        assert result.returncode != 0 and data["status"] == "failed" and data["last_operation_exit_code"] == "4"
        assert "ORCHESTELLA_GUEST_PROVISION_DURABILITY_ERROR reason=sync-failed" in result.stderr
        assert not done.exists()
        checks.append(f"{distro}:flush_failure_is_explicit_and_does_not_replace_package_return")
        (root / "sync-failed").unlink()
        # Execute the same EXIT cleanup functions without mounting or changing a real guest.
        # This models interruption at a running stage and verifies the persistence ordering.
        prelude = profile / "failure-functions.sh"
        prelude.write_text(script.read_text().split("read_os_field() {", 1)[0], encoding="utf-8")
        (root / "failure-sync-events").unlink()
        cleanup_cmd = (f"export PATH={shlex.quote(unix(commands))}:$PATH; "
                       f"export ORCHESTELLA_PROVISION_ROOT={shlex.quote(unix(root))}; "
                       f". {shlex.quote(unix(prelude))}; stage=synthetic-interruption; exit 143")
        result = subprocess.run(["sh", "-c", cleanup_cmd], text=True, capture_output=True, timeout=10)
        data = dict(line.split("=", 1) for line in state.read_text().splitlines())
        assert result.returncode == 143 and data["status"] == "failed" and data["operation_exit_code"] == "143"
        assert data["stage"] == "synthetic-interruption" and (root / "failure-sync-events").exists()
        assert result.stdout.index("SYNTHETIC_SYNC_COMPLETED_BEFORE_NOTIFICATION") < result.stdout.index("ORCHESTELLA_GUEST_PROVISION status=failed")
        checks.append(f"{distro}:unexpected_cleanup_syncs_failure_state_and_log_before_notification")
        (root / "refresh-exit-code").unlink()
        expect("retry_after_failure_does_not_reuse_stale_exit_code", "ready", "verified")
        data = dict(line.split("=", 1) for line in state.read_text().splitlines())
        assert data["operation_exit_code"] == "0" and data["last_operation_exit_code"] == "0"
        checks.append(f"{distro}:successful_retry_clears_failure_without_broadcasting_raw_stderr")
        continue

    (root / "install-failed").touch()
    expect("package_failure_never_ready", "failed", "package-install")
    (root / "install-failed").unlink()
    expect("retry_uses_real_distro_manager_and_becomes_ready", "ready", "verified")
    transaction_text = (root / "commands").read_text()
    if manager == "apt":
        assert "apt-get" in transaction_text and "--no-install-recommends" in transaction_text
        assert "systemd-sysv" in transaction_text
    elif manager == "dnf":
        assert "dnf" in transaction_text and "systemd-udev" in transaction_text and "--setopt=retries=0" in transaction_text
        assert "openssh-clients" in transaction_text and "dbus-tools" in transaction_text
        if distro == "fedora":
            assert " dnf5 " in transaction_text and " wget2-wget " in transaction_text
            assert "enable dbus-broker.service sshd.service" in transaction_text
            assert "ExecStart=/usr/bin/sshd -D" in (root / "etc/systemd/system/sshd.service.d/orchestella.conf").read_text()
    elif manager == "pacman":
        assert "pacman -Syu --noconfirm --needed base systemd" in transaction_text
        assert "pacman -Sy " not in transaction_text
    elif manager == "zypper":
        assert "zypper --non-interactive install --no-recommends systemd" in transaction_text
        assert "dbus-1-tools" in transaction_text and "openssh-server openssh-clients" in transaction_text
        assert "ca-certificates-mozilla" in transaction_text and " udev " in transaction_text
    assert "--allow-untrusted" not in transaction_text and "--nogpgcheck" not in transaction_text
    assert "trusted=yes" not in transaction_text and "Verify-Peer=false" not in transaction_text
    checks.append(f"{distro}:signed_manager_no_verification_bypass")
    if manager in ("dnf", "zypper"):
        assert "reposdir=" not in transaction_text and "--reposd-dir" not in transaction_text
        checks.append(f"{distro}:missing_injected_input_preserves_vendor_repository_fallback")
    before = (root / "commands").read_text()
    expect("verified_retry_is_idempotent", "ready", "verified")
    subsequent = (root / "commands").read_text()[len(before):]
    assert "apt-get " not in subsequent and "dnf " not in subsequent and " install " not in subsequent
    assert "pacman -S" not in subsequent and "locale-gen" not in subsequent
    (root / "services-failed").touch()
    expect("service_failure_not_ready", "failed", "service-verification")
    (root / "services-failed").unlink()
    expect("service_retry_keeps_verified_packages", "ready", "verified")
    (root / "var/lib/orchestella/provision/packages-v2").unlink()
    done.unlink()
    (root / "offline").touch()
    expected_stage = "package-install" if manager == "pacman" else "repository-refresh"
    expect("network_failure_not_ready", "failed", expected_stage)
    (root / "offline").unlink()
    expect("network_restored_retry", "ready", "verified")
    # Inventory failure overrides an otherwise valid baseline checkpoint.
    (root / "inventory").unlink()
    (root / "install-failed").touch()
    expect("missing_inventory_invalidates_checkpoint", "failed", "package-install")
    (root / "install-failed").unlink()
    expect("inventory_repaired_by_manager", "ready", "verified")
    # CPU-heavy transactions get a finite 1800-second budget, distinct from network retries.
    timeout_stage = "package-install" if manager == "pacman" else "repository-refresh"
    timeout_file = root / ("timeout-install" if manager == "pacman" else "timeout-refresh")
    for code in (124, 143):
        (root / "var/lib/orchestella/provision/packages-v2").unlink(missing_ok=True)
        done.unlink(missing_ok=True)
        write(timeout_file, str(code))
        expect(f"transaction_timeout_{code}_never_reports_offline_or_ready", "failed", timeout_stage)
        data = dict(line.split("=", 1) for line in state.read_text().splitlines())
        assert data["reason"] == "operation-timeout" and data["operation_exit_code"] == str(code)
        assert data["operation_timeout_seconds"] == "1800"
    timeout_file.unlink()
    expect("transaction_timeout_retry_recovers_without_reformat", "ready", "verified")
    if manager == "dnf":
        # Minimal RPM roots can start with microdnf, then install the full dnf baseline.
        (commands / "dnf").unlink()
        (root / "var/lib/orchestella/provision/packages-v2").unlink()
        done.unlink()
        expect("minimal_rpm_microdnf_bootstraps_full_manager", "ready", "verified")
        assert "microdnf" in (root / "commands").read_text()
        write(commands / "dnf", backend)
        (root / "minimal-rpm-variants").touch()
        (root / "var/lib/orchestella/provision/packages-v2").unlink()
        done.unlink()
        before_variants = (root / "commands").read_text()
        expect("installed_signed_minimal_variants_avoid_package_conflicts", "ready", "verified")
        variant_commands = (root / "commands").read_text()[len(before_variants):]
        install_lines = [line for line in variant_commands.splitlines() if line.startswith("dnf ") and " install " in line]
        assert install_lines and all(" curl-minimal " in line and " coreutils-single " in line for line in install_lines)
        assert all("--allowerasing" not in line for line in install_lines)
    if manager in ("dnf", "zypper"):
        ids = ["fedora", "updates"] if distro == "fedora" else ["repo-oss"] if manager == "zypper" else ["baseos", "appstream"]
        file_name = catalog_id + ".repo"
        injected = profile / "injected-input"
        repo_text = "".join(f"[{repo}]\nname=Synthetic signed {repo}\nbaseurl=https://example.invalid/{repo}/\nenabled=1\ngpgcheck=1\ngpgkey=file:///etc/pki/rpm-gpg/fixture-key\n\n" for repo in ids)
        write(injected / file_name, repo_text)
        vendor_dir = root / ("etc/zypp/repos.d" if manager == "zypper" else "etc/yum.repos.d")
        vendor = vendor_dir / "vendor-duplicate.repo"
        custom = vendor_dir / "user-custom.repo"
        write(vendor, repo_text)
        write(custom, "[user-custom]\nname=User-owned original\nbaseurl=https://user.invalid/\ngpgcheck=1\n")
        write(root / "etc/zypp/zypp.conf", "[main]\ngpgcheck = true\ndownload.connect_timeout = 60\ndownload.max_silent_tries = 5\n")
        vendor_bytes = vendor.read_bytes(); custom_bytes = custom.read_bytes()
        zypp_bytes = (root / "etc/zypp/zypp.conf").read_bytes()
        cache = root / ("var/cache/zypp/raw/cached-sentinel" if manager == "zypper" else "var/cache/dnf/cached-sentinel")
        write(cache, "completed metadata download retained\n")
        stage_cmd = (f". {shlex.quote(unix(functions))}; fail() {{ printf '%s\\n' \"$1\"; exit 1; }}; "
                     f"stage_guest_package_repositories {shlex.quote(unix(root))} {shlex.quote(unix(injected))}")
        subprocess.run(["sh", "-c", stage_cmd], check=True)
        owned = root / f"etc/orchestella/provision-repos/{catalog_id}"
        assert (owned / file_name).read_bytes() == (injected / file_name).read_bytes()
        (root / "var/lib/orchestella/provision/packages-v2").unlink(missing_ok=True)
        done.unlink(missing_ok=True)
        prior = (root / "commands").read_text()
        expect("owned_repository_transaction_preserves_vendor_files_and_cache", "ready", "verified")
        isolated = (root / "commands").read_text()[len(prior):]
        real_commands = [line for line in isolated.splitlines() if line.startswith(("dnf ", "microdnf ", "zypper "))]
        assert real_commands and all(unix(owned) in line for line in real_commands)
        assert all("--force" not in line and " clean" not in line for line in real_commands)
        assert vendor.read_bytes() == vendor_bytes and custom.read_bytes() == custom_bytes
        assert (root / "etc/zypp/zypp.conf").read_bytes() == zypp_bytes and cache.read_text() == "completed metadata download retained\n"
        if manager == "dnf":
            assert all("--setopt=reposdir=" in line and "--setopt=timeout=20" in line and "--setopt=retries=0" in line for line in real_commands)
        else:
            assert all("--reposd-dir" in line and "--servicesd-dir" in line for line in real_commands)
            assert any("--no-refresh refresh repo-oss" in line for line in real_commands)
            assert any("--no-refresh install --repo repo-oss" in line for line in real_commands)
            assert "openh264" not in "\n".join(real_commands)
            config = (root / "var/lib/orchestella/provision/zypp-baseline.conf").read_text()
            assert "download.connect_timeout = 20" in config and "download.transfer_timeout = 20" in config
            assert "download.max_silent_tries = 1" in config and "download.max_silent_tries = 0" not in config
        checks.append(f"{distro}:signed_owned_repo_ids_only_no_service_refresh_or_cache_reset")
        for fault_name, invalid_repo in [
            ("signature_disabled", repo_text.replace("gpgcheck=1", "gpgcheck=0")),
            ("tls_disabled", repo_text + "sslverify=0\n"),
            ("unknown_id", repo_text.replace("[" + ids[0] + "]", "[unknown-repo]")),
        ]:
            write(injected / file_name, invalid_repo)
            result = subprocess.run(["sh", "-c", stage_cmd], capture_output=True)
            assert result.returncode != 0 and (owned / file_name).read_text() == repo_text
            assert vendor.read_bytes() == vendor_bytes and custom.read_bytes() == custom_bytes
            checks.append(f"{distro}:rejects_injected_{fault_name}_without_adopting_vendor_config")
        write(injected / file_name, repo_text)
        # A changed owned copy cannot bypass the input marker's checksum on a later retry.
        write(owned / file_name, repo_text + "# changed after staging\n")
        done.unlink()
        expect("changed_managed_config_fails_before_network", "failed", "repository-configuration")
        subprocess.run(["sh", "-c", stage_cmd], check=True)
        write(root / "timeout-install", "137")
        (root / "var/lib/orchestella/provision/packages-v2").unlink(missing_ok=True)
        expect("isolated_install_timeout_is_explicit_and_retryable", "failed", "package-install")
        data = dict(line.split("=", 1) for line in state.read_text().splitlines())
        assert data["reason"] == "operation-timeout" and data["operation_exit_code"] == "137"
        (root / "timeout-install").unlink()
        expect("owned_transaction_retry_reuses_metadata_and_recovers", "ready", "verified")
        assert cache.read_text() == "completed metadata download retained\n"
        assert vendor.read_bytes() == vendor_bytes and custom.read_bytes() == custom_bytes

output = work / ("distro-failure-diagnostics-results.txt" if diagnostics_only else "distro-runtime-matrix-results.txt")
output.write_text("\n".join(checks) + f"\nPASS distros={len(profiles)} checks={len(checks)}\n"
                  + "type=synthetic-package-and-service-backends\nactual_ARM64_installation=main-session-device-gate\n", encoding="utf-8")
shutil.copyfile(output, output.with_name(output.stem + ".preserved.txt"))
shutil.rmtree(fixture)
label = "DISTRO_FAILURE_DIAGNOSTICS_TEST_OK" if diagnostics_only else "DISTRO_RUNTIME_MATRIX_TEST_OK"
print(f"{label} distros={len(profiles)} checks={len(checks)}")
