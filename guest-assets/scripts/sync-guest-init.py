#!/usr/bin/env python3
"""Embed canonical guest runtime modules and synchronize the APK's guest-init template.

Use --prepare-resource before --check in a clean checkout. Preparation validates
canonical bytes and only writes the generated APK template. With no arguments,
explicit source maintenance still updates canonical init and the APK template.
"""
from pathlib import Path
import argparse
import hashlib
import os
import tempfile

parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
modes = parser.add_mutually_exclusive_group()
modes.add_argument("--check", action="store_true", help="validate canonical and APK template bytes without writing")
modes.add_argument("--prepare-resource", action="store_true", help="validate canonical before generating only the APK template")
options = parser.parse_args()

assets = Path(__file__).resolve().parent.parent
source = assets / "initramfs/init"
resource = assets.parent / "app/src/main/resources/guest_init"
begin = "# ORCHESTELLA_RUNTIME_MODULES_BEGIN\n"
end = "# ORCHESTELLA_RUNTIME_MODULES_END\n"
functions = (assets / "initramfs/runtime-functions.sh").read_text(encoding="utf-8")
terminal_hook = (assets / "shell/20-orchestella-terminal-integration.sh").read_text(encoding="utf-8")
shell_prompt = (assets / "shell/orchestella-prompt.sh").read_text(encoding="utf-8")
functions += ("\ninstall_guest_terminal_integration() {\n"
              '    mkdir -p "$1/etc/orchestella/shell"\n'
              '    cat > "$1/etc/orchestella/shell/20-orchestella-terminal-integration.sh" <<\'ORCHESTELLA_TERMINAL_HOOK_EOF\'\n'
              + terminal_hook + "ORCHESTELLA_TERMINAL_HOOK_EOF\n"
              '    chmod 0644 "$1/etc/orchestella/shell/20-orchestella-terminal-integration.sh"\n}\n')
optional = "\ninstall_guest_optional_features() {\n    mkdir -p \"$1/usr/local/lib/orchestella\" || return 1\n"
feature_inputs = [("features/" + name, name, "0755" if name != "inputs.json" else "0644")
                  for name in ("profilectl", "profilectl.py", "inputs.json", "bootstrap-python.sh", "feature-session.py", "rootless-probe.py")]
feature_inputs.append(("display/display-relay.py", "display-relay.py", "0755"))
for relative, destination, mode in feature_inputs:
    content = (assets / relative).read_text(encoding="utf-8")
    delimiter = "ORCHESTELLA_FEATURE_" + hashlib.sha256(content.encode()).hexdigest()[:16].upper()
    optional += ('    cat > "$1/usr/local/lib/orchestella/' + destination + '.next" <<\'' + delimiter + "' || return 1\n"
                 + content.rstrip("\n") + "\n" + delimiter + "\n"
                 + '    chmod ' + mode + ' "$1/usr/local/lib/orchestella/' + destination + '.next" || return 1\n'
                 + '    mv "$1/usr/local/lib/orchestella/' + destination + '.next" "$1/usr/local/lib/orchestella/' + destination + '" || return 1\n')
optional += "}\n"
functions += optional
provision = (assets / "scripts/orchestella-guest-provision.sh").read_text(encoding="utf-8")
# The installed retry entrypoint must also work while /bin/sh's BusyBox is being repaired.
provision = provision.replace("#!/bin/sh\n", "#!/usr/lib/orchestella/busybox sh\n", 1)
block = (begin + functions + "\ninstall_guest_runtime_provisioner() {\n"
         '    mkdir -p "$1/usr/sbin"\n'
         '    cat > "$1/usr/sbin/orchestella-guest-provision" <<\'ORCHESTELLA_GUEST_PROVISION_EOF\'\n'
         + provision + "ORCHESTELLA_GUEST_PROVISION_EOF\n"
         '    chmod 0755 "$1/usr/sbin/orchestella-guest-provision"\n}\n' + end)
text = source.read_text(encoding="utf-8")
if begin in text:
    start = text.index(begin)
    finish = text.index(end, start) + len(end)
else:
    # One-time migration from the previous unconditional /sbin/init overwrite implementation.
    start = text.index("install_generic_linux_init_fallback() {\n")
    finish = text.index("# Configures eth0 in the INITRAMFS", start)
updated = text[:start] + block + "\n" + text[finish:].lstrip("\n")
# Retire the old fstab helper, which tried mounting default tags even without an enabled share.
fstab_start = updated.find("configure_fstab_mounts() {\n", updated.index(end))
if fstab_start != -1:
    fstab_end = updated.index("install_guest_shell_profile() {\n", fstab_start)
    updated = updated[:fstab_start] + updated[fstab_end:]
# Remove the old init helpers that silently overwrote PID1 and applied a public password.
alpine_start = updated.index("install_official_alpine_busybox_init_fallback() {\n")
alpine_end = updated.index("is_systemd_root() {\n", alpine_start)
updated = (updated[:alpine_start]
           + 'install_official_alpine_busybox_init_fallback() {\n    install_alpine_native_services "$1"\n}\n\n'
           + updated[alpine_end:])
identity_start = updated.find("provision_guest_identity_and_credentials() {\n", updated.index(end))
if identity_start != -1:
    entry_start = updated.index("\nmount -t devtmpfs devtmpfs /dev || fail mount-devtmpfs\n", identity_start)
    updated = updated[:identity_start] + updated[entry_start + 1:]
entry_marker = "# ORCHESTELLA_RUNTIME_ENTRY_BEGIN\n"
if entry_marker in updated:
    entry_start = updated.index(entry_marker)
else:
    mount_start = updated.index("mount -t devtmpfs devtmpfs /dev || fail mount-devtmpfs\n", updated.index(end))
    # Replace the entire line, including any prefix left by an interrupted/legacy generator.
    entry_start = updated.rfind("\n", 0, mount_start) + 1
# Fix the legacy netmask loop: it used to stop after the first (255) octet.
updated = updated.replace("                esac\n                break\n            done\n            _prefix=$_acc", "                esac\n            done\n            _prefix=$_acc")
# Terminal worker owns the prompt and hook source. Embed its canonical prompt bytes,
# including the user/host identity, rather than maintaining another inline implementation.
prompt_anchor = '    cat > "$target_root/etc/orchestella/shell/orchestella-prompt.sh" <<\'EOF\'\n'
prompt_offset = updated.index(prompt_anchor) + len(prompt_anchor)
prompt_finish = updated.index("\nEOF\n", prompt_offset)
updated = updated[:prompt_offset] + shell_prompt.rstrip("\n") + updated[prompt_finish:]
# Terminal worker owns the hook source; this module only embeds and sources its exact bytes.
hook_start = "# ORCHESTELLA_TERMINAL_PROFILE_BEGIN\n"
hook_end = "# ORCHESTELLA_TERMINAL_PROFILE_END\n"
profile_hook = (hook_start
                + 'if [ -n "${BASH_VERSION:-}" ] && [ -r /etc/orchestella/shell/20-orchestella-terminal-integration.sh ]; then\n'
                + '    . /etc/orchestella/shell/20-orchestella-terminal-integration.sh\nfi\n' + hook_end)
if hook_start in updated:
    hook_offset = updated.index(hook_start)
    hook_finish = updated.index(hook_end, hook_offset) + len(hook_end)
    updated = updated[:hook_offset] + profile_hook + updated[hook_finish:]
else:
    anchor = "# Load graphics acceleration environment\n"
    hook_offset = updated.index(anchor, updated.index("install_guest_shell_profile() {\n"))
    updated = updated[:hook_offset] + profile_hook + "\n" + updated[hook_offset:]
# Locate entry again because inserting the profile hook changes its position.
entry_start = updated.index(entry_marker)
entry = (assets / "initramfs/runtime-entry.sh").read_text(encoding="utf-8")
updated = updated[:entry_start] + entry_marker + entry + "# ORCHESTELLA_RUNTIME_ENTRY_END\n"
if options.prepare_resource:
    if source.read_bytes() != updated.encode("utf-8"):
        raise SystemExit("guest-init canonical runtime modules are out of sync")
    resource.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=resource.parent, prefix=".guest_init-", delete=False) as output:
            temporary = Path(output.name)
            output.write(updated.encode("utf-8"))
        os.replace(temporary, resource)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    print("GUEST_INIT_RESOURCE_PREPARED")
elif options.check:
    if source.read_bytes() != updated.encode("utf-8"):
        raise SystemExit("guest-init canonical runtime modules are out of sync")
    if not resource.is_file():
        raise SystemExit("guest-init APK template is missing; run --prepare-resource first")
    if resource.read_bytes() != updated.encode("utf-8"):
        raise SystemExit("guest-init APK template bytes are out of sync")
    print("GUEST_INIT_TEMPLATE_SYNC_OK")
else:
    source.write_text(updated, encoding="utf-8", newline="\n")
    resource.write_text(updated, encoding="utf-8", newline="\n")
    print("GUEST_INIT_TEMPLATES_UPDATED")
