# Vendored AOSP policy inputs

The files under `profile/vendor/<ref>/` are verbatim copies of files from the
Android Open Source Project `platform/bionic` repository, fetched on
2026-11-10 from:

- `https://android.googlesource.com/platform/bionic/+/refs/heads/<ref>/libc/SYSCALLS.TXT`
- `.../libc/SECCOMP_ALLOWLIST_COMMON.TXT`
- `.../libc/SECCOMP_ALLOWLIST_APP.TXT`
- `.../libc/SECCOMP_BLOCKLIST_COMMON.TXT`
- `.../libc/SECCOMP_BLOCKLIST_APP.TXT`
- `.../libc/kernel/uapi/asm-generic/unistd.h`
- `.../libc/kernel/uapi/asm-x86/asm/unistd_64.h`

They are used only to generate the research profiles in `profile/generated/`.
AOSP is licensed under the Apache License, Version 2.0
(https://www.apache.org/licenses/LICENSE-2.0). The original copyright and
license notices in each file are preserved verbatim. This directory is not
part of the product build and is not installed on any device.
