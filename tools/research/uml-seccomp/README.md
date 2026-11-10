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
| Kernel | **proxy**: upstream 6.12 UML has no `ncpus=`/`seccomp=`; those rows are `unknown` |

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
device/run_device.sh        on-device entry for the `unknown` rows
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

## Known limits

- Host x86_64 + glibc is not the arm64 + bionic target. The host shim marks
  the delta; those syscalls are reported separately and are not device claims.
- Upstream 6.12 UML is uniprocessor and has no `seccomp=on`; the `ncpus=2/4`
  and `seccomp-fastpath` rows are `unknown` with device commands. The
  `seccomp-fastpath` probe also requires host `close_range` (Linux 5.9+).
- The mconsole socket exists only while UML runs; the mconsole case keeps the
  guest alive with `u3_wait_ms=4000` so the host can connect.
- UML requires an exec-capable `TMPDIR`; the harness sets it to `<outdir>/tmp`.
- Fixtures read by the self-test use `console.txt`/`stderr.txt` because `*.log`
  is git-ignored; runtime output still uses `.log`.
- No root, kernel modules, SELinux changes, or ptrace-based seccomp bypass.
