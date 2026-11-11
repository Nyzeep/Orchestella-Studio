# U3 · Android-like seccomp / host compatibility harness

Zero-device harness that runs a **host-arch** User-Mode-Linux kernel under an
AOSP app-domain-like seccomp filter and records which UML capabilities survive.
It is research tooling: it does not change product seccomp policy, does not
touch `app/`, and a host `pass` never replaces a device `PASS`.

## What is faithful, what is a proxy

| Item | Status |
| --- | --- |
| Filter composition | faithful to bionic `genseccomp.py`: `(SYSCALLS.TXT - BLOCKLIST_COMMON - BLOCKLIST_APP) + ALLOWLIST_COMMON + ALLOWLIST_APP` |
| Default action | faithful: `SECCOMP_RET_TRAP` (SIGSYS), no argument filters |
| AOSP source versions | faithful: one profile per `android14/15/16/17-release` + `main`, vendored verbatim |
| Architecture | **proxy**: host x86_64 runs the x86_64 profile; the device target is arm64 |
| libc | **proxy**: host UML is glibc-linked; Android UML kernels use bionic |
| Kernel | **proxy**: the upstream lane moved — UML gained `seccomp=<on/auto/off>` in v6.16 and SMP (`ncpus=`) in v6.19 (R1 §4.1); the local 6.12-era "uniprocessor, no seccomp=" note is obsolete. On device-b the Raymer arm64 fork (7.2-rc4 base) already carries both, so those rows run with `--case` rather than falling to `unknown` |

`profile/host-shims/glibc-x86_64.txt` lists the x86_64-only syscalls glibc
issues that have no arm64 counterpart (rseq/set_robust_list, access, mkdir,
rmdir, unlink, poll, epoll_create, epoll_wait). They are **not** claimed as
Android allowances; the report separates them from the AOSP profile.

## Layout

```
gen_profile.py              profile generator + --check + --emit-filter
profile/vendor/<ref>/       verbatim AOSP bionic inputs (25 policy files + uapi)
profile/generated/          versioned JSON profiles, 10 files
profile/host-shims/         x86_64 glibc proxy additions
seccomp_launch.c            no_new_privs + BPF filter; enforce / trace / baseline
run_uml_seccomp.sh          one case: assemble args, run, write harness.meta
run_with_fd.py              socketpair fd helper for the UML vector transport
mconsole_probe.py           binary mconsole client (UML's protocol, not text)
guest/init.c                static guest init with CAP probes
guest/build_guest.sh        builds rootfs dir + ubd0 ext4 image
matrix.py                   pass/fail/unknown matrix + --self-test
fixtures/matrix/            synthetic run dirs for the self-test
fixtures/matrix/cases-device-b.json  the device-b (API 35 arm64) lane doc: same 12 cases, android15 profile
device/run_device.sh        on-device entry for the `unknown` rows (adb-shell channel)
device/run_device_matrix.sh the runner-app channel: 12-case closed loop with a zero-touch plan mode
profile_sanity.py           API 35 sanity (check-only): pipeline drift + android15→16 monotonicity
collect/syscalls.bt         bpftrace sketch (this host has no bpftrace)
```

## Quick start

```sh
# 1. guest workload
tools/research/uml-seccomp/guest/build_guest.sh /tmp/uml-seccomp-guest

# 2. profiles (already checked in; --check proves they are current)
python3 tools/research/uml-seccomp/gen_profile.py --check

# 3. one case: AOSP-like filter + glibc host shim
tools/research/uml-seccomp/run_uml_seccomp.sh \
  --case hostfs-boot \
  --profile tools/research/uml-seccomp/profile/generated/aosp-app-android17-x86_64.json \
  --kernel /path/to/uml/vmlinux \
  --outdir /tmp/u3-runs/hostfs \
  --mode aosp --host-shim glibc-x86_64

# 4. matrix (all cases)
python3 tools/research/uml-seccomp/matrix.py \
  --cases tools/research/uml-seccomp/fixtures/matrix/cases.json \
  --run-dir /tmp/u3-runs/hostfs --run-dir /tmp/u3-runs/ubd0 ... \
  --mode aosp --profile tools/research/uml-seccomp/profile/generated/aosp-app-android17-x86_64.json \
  --format markdown

# denied-syscall report (trace runs are diagnostic, not enforcement)
python3 tools/research/uml-seccomp/matrix.py --cases ... \
  --run-dir /tmp/u3-runs/trace-hostfs ... --mode trace --format markdown
```

`matrix.py --self-test` is the only acceptance gate in this directory.

## Modes

| `--mode` | launcher mode | semantics |
| --- | --- | --- |
| `aosp` | `enforce` | default-deny, `SECCOMP_RET_TRAP`; a blocked host syscall kills the process with SIGSYS (159). This is the pass/fail evidence. |
| `trace` | `trace` | same allowlist, but default `SECCOMP_RET_USER_NOTIF` and every out-of-profile syscall is logged as `SECCOMP_DENY nr=N name=X` and continued. Diagnostic only — it does **not** enforce the profile. |
| `baseline` | none | no filter (control). |

`--strace` wraps the command with `strace -f -ff` and records `collected`
only if trace files were produced. `--bpftrace` runs `collect/syscalls.bt`
through `bpftrace -c` and records `collected` only if output is non-empty.
This host has neither binary, so both record `unavailable`. `trace` mode is
the actual denied-syscall collector used here (user-notification + CONTINUE,
no ptrace). Note that `aosp`/`enforce` runs only yield a bare SIGSYS: a
caught SIGSYS disposition is reset by `exec`, so the denied syscall name must
come from `trace` mode.

## Profile provenance

- Policy inputs: `https://android.googlesource.com/platform/bionic/+/refs/heads/<ref>/libc/…`
  (`SYSCALLS.TXT`, `SECCOMP_ALLOWLIST_{COMMON,APP}.TXT`,
  `SECCOMP_BLOCKLIST_{COMMON,APP}.TXT`), fetched 2026-11-10 and vendored with
  sha256 recorded in each generated profile.
- Syscall numbers: the ref's own `libc/kernel/uapi/{asm-generic/unistd.h,
  asm-x86/asm/unistd_64.h}` (vendored alongside the policy text).
- Default action and no-argument-filter semantics: `libc/seccomp/seccomp_policy.cpp`
  (`Disallow()` -> `SECCOMP_RET_TRAP`) and `libc/tools/genseccomp.py` (the
  `(args)` text is ignored).
- `android14` -> API 34, `android15` -> 35, `android16` -> 36, `android17` -> 37;
  `main` is unversioned and must not be treated as a shipped release.

Do not extrapolate a profile across Android versions: `clone3` is absent on
android14 and present from android15.

## Matrix contract

- Every case is `pass`, `fail` or `unknown`.
- `pass`: rc=0 and all declared console markers present.
- `fail`: denied syscall logged (aosp mode), timeout, SIGSYS, or non-zero exit.
- `unknown`: no run, unsupported kernel feature, harness error, a run whose
  expected marker is missing, or a mode/profile mismatch. **Every `unknown`
  case must declare a `device_entry`**; `matrix.py` exits 2 if one does not.
- `--mode aosp` is the default and only counts enforcing runs. A `baseline`
  control or a `trace` diagnostic run with the same case name is `unknown`
  (`mode-mismatch`), never silently counted as AOSP evidence.
- `--mode trace` reports out-of-profile syscalls in `denied`; those rows can
  be `pass` because trace mode continues the calls. Do not compare them to
  `aosp` rows.
- A run whose profile name or profile sha256 differs is `unknown`
  (`profile-mismatch` / `profile-hash-mismatch`), never silently mixed.

## device-b lane (API 35 / arm64, runner-app channel)

Two device channels exist and are never mixed:

| channel | entry | domain/filter | notes |
| --- | --- | --- | --- |
| adb-shell | `device/run_device.sh` | shell domain; filter installed by `seccomp_launch` from the profile | full harness semantics (fd passing, mconsole probe, trace mode); needs `cc` or a prebuilt launcher on the device |
| runner-app | `device/run_device_matrix.sh` | the runner app's own domain — on an API 35 device the kernel inherits the **real** zygote-installed API 35 app filter (R1 §5.1.3), so no `seccomp_launch` is involved | one-key closed loop: config → `am start -S --activity-clear-task` → poll `uml-exit.txt` → pull `uml-console.log` → judge; `--plan` lists the 12 cases with zero device contact |

Runner-channel case classes (device-b): 10 cases run through per-case `args`
whole-replacement configs (`init=/init` for the U3 guest init; `ncpus=N` +
`libuml_kernel_smp.so` for the SMP rows; `ubd0=${rootfs}` + `rootfs.img` for
the ext4 row); `vector-net` (needs a host socketpair fd) and `mconsole` (needs
a host-side probe client inside the run window) are structurally unservable
there and stay on the adb-shell channel. `run_device_matrix.sh --judge`
triage follows the R1 §5.1.5 three-way reading: SIGSYS = filter TRAP (check
the profile reverse set; enforce carries no name), "SECCOMP userspace
requested but not functional" = close_range/seccomp probe fatal, errno=38 /
"Function not implemented" = host-kernel absence (5.10 lacks `epoll_pwait2`
and friends; the API 35 filter allows those numbers, so it is an errno fact,
never SIGSYS).

Before any device run, `profile_sanity.py --check` re-derives the checked-in
android15 profile from the vendored `android15-release` inputs (check-only,
no regeneration) and asserts the monotonicity `android16 − android15 ==
{landlock_add_rule, landlock_create_ruleset, landlock_restrict_self, mseal}`
with an empty reverse difference (R1 §2.3, both arches); `UmlSeccompProfileSanityTest`
locks the same invariants on the JVM side so a policy widening cannot reach a
matrix silently.

## Known limits

- Host x86_64 + glibc is not the arm64 + bionic target. The host shim marks
  the delta; those syscalls are reported separately and are not device claims.
- The host harness still refuses `ncpus=`/`seccomp=` rows unless the kernel
  reports the knob (`--help` probe), so old upstream kernels keep those rows
  `unknown`; device-b's fork kernel carries both (the runner channel runs
  them with the SMP kernel image). The fast path itself needs host
  `close_range` (Linux 5.9+) — device-b's 5.10.236 satisfies it.
- The mconsole socket exists only while UML runs; the mconsole case keeps the
  guest alive with `u3_wait_ms=4000` so the host can connect.
- UML requires an exec-capable `TMPDIR`; the host harness sets it to
  `<outdir>/tmp`, the runner channel uses the app's `filesDir`.
- Fixtures read by the self-test use `console.txt`/`stderr.txt` because `*.log`
  is git-ignored; runtime output still uses `.log`.
- No root, kernel modules, SELinux changes, or ptrace-based seccomp bypass.
