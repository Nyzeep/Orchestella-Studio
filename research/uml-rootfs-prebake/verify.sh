#!/usr/bin/env bash
# verify.sh — prove that an unpacked pre-baked rootfs matches its manifest and
# is actually first-boot-clean.
#
# Checks:
#   1. manifest has every required field and self-consistent checksums
#   2. the artifact (tar.gz or ext4) matches the manifest sha256 and size
#   3. the rootfs contains no package-manager queue, cache or lock
#   4. no product provisioning marker is present
#   5. init-zero-install / ready.sh are present, executable and invoke no
#      package manager or network client
#   6. package database, certificate bundle, init checksums and whole-tree digest match
#
# Exit code: 0 all checks pass, 1 any check fails.

set -uo pipefail

ROOT=""
MANIFEST=""
ARTIFACT=""
FAILS=0
CHECKS=0

log()  { printf '%s\n' "$*"; }
pass() { CHECKS=$((CHECKS + 1)); printf 'PASS  %s\n' "$*"; }
fail() { CHECKS=$((CHECKS + 1)); FAILS=$((FAILS + 1)); printf 'FAIL  %s\n' "$*"; }

usage() {
    cat >&2 <<'EOF'
Usage: verify.sh --root DIR --manifest FILE [--artifact FILE]

  --root DIR        Unpacked rootfs directory
  --manifest FILE   manifest.json produced by build-rootfs.sh
  --artifact FILE   Artifact to checksum (default: every outputs[] path
                    resolved next to the manifest)
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --root) ROOT="${2:-}"; shift 2 ;;
        --manifest) MANIFEST="${2:-}"; shift 2 ;;
        --artifact) ARTIFACT="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) printf 'unknown argument: %s\n' "$1" >&2; usage; exit 2 ;;
    esac
done

[ -n "$ROOT" ] || { usage; exit 2; }
[ -n "$MANIFEST" ] || { usage; exit 2; }
[ -d "$ROOT" ] || { printf 'rootfs not found: %s\n' "$ROOT" >&2; exit 2; }
[ -f "$MANIFEST" ] || { printf 'manifest not found: %s\n' "$MANIFEST" >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { printf 'python3 is required\n' >&2; exit 2; }

# ------------------------------------------------------------ manifest shape

manifest_ok=1
python3 - "$MANIFEST" <<'PY' || manifest_ok=0
import json, re, sys

path = sys.argv[1]
try:
    m = json.load(open(path, encoding="utf-8"))
except Exception as exc:
    print("FAIL  manifest is not valid JSON: %s" % exc)
    sys.exit(1)

errors = []
def need(obj, key, where):
    if key not in obj:
        errors.append("missing %s.%s" % (where, key))
        return None
    return obj[key]

sha = re.compile(r"^[0-9a-f]{64}$")
if need(m, "schemaVersion", "$") != 1:
    errors.append("schemaVersion must be 1")
for key in ("distro", "build", "inputs", "provisioning", "artifactContent", "outputs"):
    need(m, key, "$")

d = m.get("distro", {})
for key in ("name", "version", "arch", "libc"):
    need(d, key, "distro")
if d.get("name") not in ("alpine", "debian"):
    errors.append("distro.name must be alpine or debian")
if d.get("arch") not in ("aarch64", "x86_64"):
    errors.append("distro.arch must be aarch64 or x86_64")
if d.get("libc") not in ("musl", "glibc"):
    errors.append("distro.libc must be musl or glibc")

b = m.get("build", {})
for key in ("sourceDateEpoch", "startedAtUtc", "durationSeconds", "host", "tool"):
    need(b, key, "build")

inp = m.get("inputs", {})
base = inp.get("base", {})
for key in ("url", "sha256"):
    need(base, key, "inputs.base")
if base.get("sha256") and not sha.match(base["sha256"]):
    errors.append("inputs.base.sha256 is not a sha256")
pkgs = inp.get("packageSet", [])
if not isinstance(pkgs, list) or not pkgs:
    errors.append("inputs.packageSet must be a non-empty array")
else:
    for i, p in enumerate(pkgs):
        if not isinstance(p, dict) or "name" not in p or "version" not in p:
            errors.append("inputs.packageSet[%d] needs name and version" % i)

pr = m.get("provisioning", {})
if pr.get("mode") != "build-time":
    errors.append("provisioning.mode must be build-time")
fb = pr.get("firstBoot", {})
if fb.get("packageManagerInvocations") != 0:
    errors.append("provisioning.firstBoot.packageManagerInvocations must be 0")
if fb.get("networkRequired") is not False:
    errors.append("provisioning.firstBoot.networkRequired must be false")
if fb.get("init") != "/init-zero-install":
    errors.append("provisioning.firstBoot.init must be /init-zero-install")
if fb.get("ready") != "/ready.sh":
    errors.append("provisioning.firstBoot.ready must be /ready.sh")

ac = m.get("artifactContent", {})
for key in ("initSha256", "readySha256", "packageDatabaseSha256", "treeSha256"):
    value = need(ac, key, "artifactContent")
    if value and not sha.match(value):
        errors.append("artifactContent.%s is not a sha256" % key)

outs = m.get("outputs", [])
if not isinstance(outs, list) or not outs:
    errors.append("outputs must be a non-empty array")
else:
    for i, o in enumerate(outs):
        for key in ("path", "format", "sha256", "sizeBytes"):
            if key not in o:
                errors.append("outputs[%d] missing %s" % (i, key))
        if o.get("sha256") and not sha.match(o["sha256"]):
            errors.append("outputs[%d].sha256 is not a sha256" % i)
        if o.get("format") not in ("tar.gz", "ext4"):
            errors.append("outputs[%d].format must be tar.gz or ext4" % i)

if errors:
    for err in errors:
        print("FAIL  manifest: %s" % err)
    sys.exit(1)
print("PASS  manifest shape")
PY

if [ "$manifest_ok" -eq 1 ]; then
    pass "manifest required fields"
else
    fail "manifest required fields"
    printf '\n%d checks, %d failure(s)\n' "$CHECKS" "$FAILS"
    exit 1
fi

# ------------------------------------------------------------ artifact hashes

check_artifact() {
    path="$1"; expected="${2:-}"; size="${3:-}"
    [ -f "$path" ] || { fail "artifact missing: $path"; return; }
    actual="$(sha256sum "$path" | awk '{print $1}')"
    actual_size="$(stat -c%s "$path")"
    if [ -n "$expected" ] && [ "$actual" != "$expected" ]; then
        fail "artifact sha256 mismatch: $path ($actual != $expected)"
    elif [ -n "$expected" ]; then
        pass "artifact sha256: $(basename "$path")"
    fi
    if [ -n "$size" ] && [ "$actual_size" != "$size" ]; then
        fail "artifact size mismatch: $path ($actual_size != $size)"
    fi
}

if [ -n "$ARTIFACT" ]; then
    check_artifact "$ARTIFACT"
else
    # Resolve outputs next to the manifest; a build writes both together.
    while IFS=$'\t' read -r name expected size; do
        check_artifact "$(dirname "$MANIFEST")/$name" "$expected" "$size"
    done < <(python3 - "$MANIFEST" <<'PY'
import json, sys
for o in json.load(open(sys.argv[1], encoding="utf-8"))["outputs"]:
    print("%s\t%s\t%s" % (o["path"], o["sha256"], o["sizeBytes"]))
PY
)
fi

# ------------------------------------------------------------ first-boot state

[ -x "$ROOT/init-zero-install" ] && pass "init-zero-install is executable" \
    || fail "init-zero-install missing or not executable"
[ -x "$ROOT/ready.sh" ] && pass "ready.sh is executable" \
    || fail "ready.sh missing or not executable"

for script in init-zero-install ready.sh; do
    [ -f "$ROOT/$script" ] || continue
    # Ignore comments: this verifier's own text mentions package managers.
    body="$(grep -v '^[[:space:]]*#' "$ROOT/$script" || true)"
    if printf '%s\n' "$body" | grep -Eq '(^|[^[:alnum:]_])(apk|apk\.static|apt|apt-get|dpkg|pacman|yum|dnf|zypper)([^[:alnum:]_]|$)'; then
        fail "$script invokes a package manager"
    else
        pass "$script has no package-manager invocation"
    fi
    if printf '%s\n' "$body" | grep -Eq '(^|[^[:alnum:]_])(wget|curl|nc|ncat|ssh|scp|ping|dhclient|udhcpc|ifconfig|route|netstat|ss|ip)([[:space:]]|$)'; then
        fail "$script contains a network client/configuration command"
    else
        pass "$script performs no network action"
    fi
done

[ ! -e "$ROOT/opt/orchestella/.firstboot-done" ] && pass "no product firstboot-done marker" \
    || fail "product firstboot-done marker is present"
[ ! -e "$ROOT/var/lib/orchestella/provision/state" ] && pass "no provisioning state file" \
    || fail "provisioning state file is present"

[ ! -s "$ROOT/etc/machine-id" ] && pass "machine-id is empty" \
    || fail "machine-id is not empty"
[ ! -s "$ROOT/etc/resolv.conf" ] && pass "resolv.conf is empty (offline image)" \
    || fail "resolv.conf is not empty"
[ "$(cat "$ROOT/etc/hostname" 2>/dev/null)" = "orchestella" ] && pass "hostname is fixed" \
    || fail "hostname is not fixed to orchestella"

ls "$ROOT"/etc/ssh/ssh_host_* >/dev/null 2>&1 \
    && fail "per-instance ssh host keys are baked into the image" \
    || pass "no baked ssh host keys"

[ -s "$ROOT/etc/ssl/certs/ca-certificates.crt" ] && pass "certificate bundle is present" \
    || fail "certificate bundle missing"

# Package-manager queues/caches/locks.
case "$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["distro"]["name"])' "$MANIFEST")" in
    alpine)
        [ -z "$(find "$ROOT/var/cache/apk" -type f 2>/dev/null | head -1)" ] \
            && pass "Alpine apk cache is empty" || fail "Alpine apk cache is not empty"
        [ -z "$(find "$ROOT" -name '*.apk' -type f 2>/dev/null | head -1)" ] \
            && pass "no .apk files in image" || fail "unpacked .apk files remain in image"
        [ ! -e "$ROOT/lib/apk/db/lock" ] && pass "Alpine apk db lock absent" \
            || fail "Alpine apk db lock is present"
        [ ! -e "$ROOT/var/log/apk.log" ] && pass "Alpine apk log absent" \
            || fail "Alpine apk log is present"
        ;;
    debian)
        [ -z "$(find "$ROOT/var/lib/apt/lists" -type f 2>/dev/null | head -1)" ] \
            && pass "Debian apt lists are empty" || fail "Debian apt lists are not empty"
        [ -z "$(find "$ROOT/var/cache/apt/archives" -name '*.deb' -type f 2>/dev/null | head -1)" ] \
            && pass "Debian apt archive cache is empty" || fail "Debian .deb cache is not empty"
        [ -z "$(find "$ROOT/var/lib/dpkg/updates" -type f 2>/dev/null | head -1)" ] \
            && pass "Debian dpkg updates queue is empty" || fail "Debian dpkg updates queue is not empty"
        for lock in var/lib/dpkg/lock var/lib/dpkg/lock-frontend \
                    var/lib/apt/lists/lock var/cache/apt/archives/lock; do
            [ ! -e "$ROOT/$lock" ] && pass "Debian lock absent: $lock" \
                || fail "Debian lock present: $lock"
        done
        ;;
esac

# ------------------------------------------------------------ content hashes

python3 - "$ROOT" "$MANIFEST" <<'PY'
import hashlib, json, os, stat, sys

root, manifest_path = sys.argv[1], sys.argv[2]
m = json.load(open(manifest_path, encoding="utf-8"))
errors = []

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def tree_digest(base):
    h = hashlib.sha256()
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames.sort()
        for name in list(dirnames):
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, base)
            st = os.lstat(path)
            mode = stat.S_IMODE(st.st_mode)
            if os.path.islink(path):
                dirnames.remove(name)
                h.update(("L %o %s -> %s\n" % (mode, rel, os.readlink(path))).encode())
            else:
                h.update(("D %o %s\n" % (mode, rel)).encode())
        for name in sorted(filenames):
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, base)
            st = os.lstat(path)
            mode = stat.S_IMODE(st.st_mode)
            if stat.S_ISLNK(st.st_mode):
                line = "L %o %s -> %s" % (mode, rel, os.readlink(path))
            elif stat.S_ISREG(st.st_mode):
                line = "F %o %d %s %s" % (mode, st.st_size, sha256(path), rel)
            elif stat.S_ISDIR(st.st_mode):
                line = "D %o %s" % (mode, rel)
            else:
                line = "O %o %s" % (mode, rel)
            h.update((line + "\n").encode())
    return h.hexdigest()

ac = m["artifactContent"]
if tree_digest(root) != ac["treeSha256"]:
    errors.append("rootfs tree digest does not match manifest (extra, missing or modified file)")
for key, rel in (("initSha256", "init-zero-install"),
                 ("readySha256", "ready.sh")):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        errors.append("%s missing" % rel)
    elif sha256(path) != ac[key]:
        errors.append("%s sha256 does not match manifest" % rel)

db_rel = "lib/apk/db/installed" if m["distro"]["name"] == "alpine" else "var/lib/dpkg/status"
db = os.path.join(root, db_rel)
if not os.path.isfile(db):
    errors.append("%s missing" % db_rel)
elif sha256(db) != ac["packageDatabaseSha256"]:
    errors.append("package database sha256 does not match manifest")

cert = os.path.join(root, m["provisioning"]["certificates"]["bundlePath"].lstrip("/"))
if not os.path.isfile(cert):
    errors.append("certificate bundle missing")
elif sha256(cert) != m["provisioning"]["certificates"]["sha256"]:
    errors.append("certificate bundle sha256 does not match manifest")

# Package count: the manifest must describe exactly the installed package set.
def parse_alpine():
    out = []
    stanza = {}
    for line in open(os.path.join(root, "lib/apk/db/installed"), encoding="utf-8", errors="replace"):
        line = line.rstrip("\n")
        if not line:
            if stanza.get("P") and stanza.get("V"):
                out.append((stanza["P"], stanza["V"]))
            stanza = {}
        elif len(line) > 2 and line[1] == ":":
            stanza[line[0]] = line[2:]
    if stanza.get("P") and stanza.get("V"):
        out.append((stanza["P"], stanza["V"]))
    return sorted(out)

def parse_debian():
    out = []
    stanza = {}
    for line in open(os.path.join(root, "var/lib/dpkg/status"), encoding="utf-8", errors="replace"):
        line = line.rstrip("\n")
        if not line:
            if "install ok installed" in stanza.get("Status", ""):
                out.append((stanza.get("Package"), stanza.get("Version")))
            stanza = {}
        elif ": " in line:
            k, v = line.split(": ", 1)
            stanza[k] = v
    if "install ok installed" in stanza.get("Status", ""):
        out.append((stanza.get("Package"), stanza.get("Version")))
    return sorted((a, b) for a, b in out if a and b)

installed = parse_alpine() if m["distro"]["name"] == "alpine" else parse_debian()
declared = sorted((p["name"], p["version"]) for p in m["inputs"]["packageSet"])
if installed != declared:
    errors.append("package set mismatch: installed=%d declared=%d" % (len(installed), len(declared)))

if errors:
    for err in errors:
        print("FAIL  %s" % err)
    sys.exit(1)
PY
py_rc=$?
if [ "$py_rc" -eq 0 ]; then pass "content checksums"; else fail "content checksums"; fi

# ------------------------------------------------------------ summary

printf '\n%d checks, %d failure(s)\n' "$CHECKS" "$FAILS"
if [ "$FAILS" -ne 0 ]; then
    exit 1
fi
printf 'VERIFY_OK\n'
