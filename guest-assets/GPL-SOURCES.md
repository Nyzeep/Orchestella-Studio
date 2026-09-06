# GPL corresponding-source manifest

This manifest covers the GPL-licensed programs shipped by the Ticket 25 guest
asset build. `inputs/inputs.lock` is the machine-readable authority for every
version, URL and SHA256 below. The build refuses to unpack an archive until its
SHA256 matches.

## Linux kernel

- Program: Linux kernel `Image`
- Version: `7.2.3`
- License: `GPL-2.0-only WITH Linux-syscall-note`
- Corresponding source:
  `https://cdn.kernel.org/pub/linux/kernel/v7.x/linux-7.2.3.tar.xz`
- SHA256: `8ba259e8e7b13ec6ef0941c8a39ad90b24bd4a4d6c0010ba6bafb794550ecd03`
- Final build configuration: generated artifact `kernel.config`
- Complete build entry: `guest-assets/build.sh`
- Config inputs: `guest-assets/kernel/droidlayer.fragment` and
  `guest-assets/kernel/required.config`

## BusyBox

- Program: static BusyBox in `initramfs.cpio.gz`
- Version: `1.37.0`
- License: `GPL-2.0-only`
- Corresponding source: `https://busybox.net/downloads/busybox-1.37.0.tar.bz2`
- SHA256: `3311dff32e746499f4df0d5df04d7eb396382d7e108bb9250e7b519b837043a4`
- Final build configuration: generated artifact `busybox.config`
- Complete build entry: `guest-assets/build.sh`

## e2fsprogs

- Program: static `mke2fs` / `mkfs.ext4` in `initramfs.cpio.gz`
- Version: `1.47.2`
- License: `GPL-2.0-only` for `mke2fs`; its linked in-tree support libraries
  retain the notices contained in the corresponding source archive
- Corresponding source:
  `https://mirrors.edge.kernel.org/pub/linux/kernel/people/tytso/e2fsprogs/v1.47.2/e2fsprogs-1.47.2.tar.xz`
- SHA256: `08242e64ca0e8194d9c1caad49762b19209a06318199b63ce74ae4ef2d74e63c`
- Complete build entry: `guest-assets/build.sh` (`mke2fs.static` target)

The pinned `mke2fs.static` link rule uses the in-tree support library,
`libext2fs`, `libcom_err`, `libe2p`, `libblkid`, and `libuuid`. Optional
libmagic and libarchive linkage is disabled by the build. The delivery copies
the complete upstream `NOTICE` (including GPLv2 and Library GPLv2 text), the
exact `lib/uuid/COPYING` BSD notice, and the leading license/copyright blocks
from all five source files compiled into `libcom_err`. These are emitted as
`NOTICE.e2fsprogs`, `LICENSE.e2fsprogs-libuuid`, and
`ATTRIBUTION.e2fsprogs-libcom_err` respectively.

## musl and compiler runtime

- Program: musl C library statically linked into initramfs executables
- Version: `1.2.5`
- License: MIT
- Corresponding source: `https://musl.libc.org/releases/musl-1.2.5.tar.gz`
- SHA256: `a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4`
- Complete build entry: `guest-assets/build.sh`

The GNU AArch64 cross compiler is used for the kernel and to bootstrap the musl
toolchain. Any statically linked `libgcc` runtime code is covered by the GCC
Runtime Library Exception; glibc is not linked into the delivered initramfs
executables.

The generated delivery directory includes this manifest, GPLv2 license text
copied from the verified BusyBox source, the e2fsprogs notices and attribution
files described above, musl's license, and `SOURCE-OFFER.txt`. Release
packaging must preserve those files. The written offer remains valid for at
least three years after the last applicable object-code distribution. No
Podroid, Termux, or other GPL reference-project code or binaries are used.

The guest agent and DroidLayer provisioning scripts are original project code
under the repository's Apache-2.0 license. The official Alpine rootfs remains a
separately verified Provisioning input; it is not embedded in or redistributed
as part of the reproducible initramfs delivery.
