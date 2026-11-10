# Fixtures — provenance and expected values

All fixtures are **synthetic** unless a row says "sanitised excerpt". No real
device serial, IMEI, Android ID, screenshot, full device log, LAN/public IP,
MAC address, personal path or account identifier is present. Device evidence
stays in the external evidence root; the repository only keeps the sanitised
summary already published in `docs/research/`.

| File | Kind | Origin / shape | Expected in self-test |
| --- | --- | --- | --- |
| `uml-console-ok.txt` | synthetic | UML ext4/hostfs console with `printk.time=1` timestamps, `UMARM_BOOT_OK`, `BENCH boot_uptime_ms` | T1=40, T2=120, T3=400 ms |
| `uml-exit-ok.txt` | synthetic | `uml-exit.txt` shape from `research/uml-runner/README.md` | `duration_ms=3182`, exit 0 |
| `uml-console-missing-duration.txt` | synthetic | Same console without `BENCH boot_uptime_ms` and without an exit file | T3/duration `unknown` |
| `uml-console-real-20261029-excerpt.txt` | **sanitised excerpt** | `docs/research/2026-10-29-uml-device-round-go-no-go.zh-CN.md` §5.1; device identifiers and paths already removed | T3=400 ms, duration=3182 ms; T1/T2 unknown |
| `uml-exit-real-20261029-excerpt.txt` | **sanitised excerpt** | Same report §5.1 exit summary | `duration_ms=3182`, exit 0 |
| `uml-console-20261107-synthetic.txt` | synthetic | Field shape and median order of `docs/research/2026-11-07-uml-fabric-device-round.zh-CN.md` §1; **not** a real log | T2=340, T3=350, duration=612 ms |
| `uml-console-bootprobe.txt` | synthetic | 11-07 `bootprobe.sh` marker shape (`BOOTPROBE ... uptime=`) | T2=340, T3=350 ms from `BOOTPROBE` |
| `uml-console-u1-ready-ok.txt` | synthetic | U1 fast-shell marker shape (`U1_INFO`, `U1_T2_READY`, `U1_READY`; from `research/uml-fastboot/initramfs/um-command.sh`); **not** a real log | T3=110 ms from `U1_READY`; T2=60 ms via VFS fallback (`U1_T2_READY` is not a protocol marker) |
| `uml-console-u2-zero-install-ok.txt` | synthetic | U2 zero-install marker shape (`INIT_ZERO_INSTALL`, `T2_MOUNTS_READY`, `T3_READY ... action=`, bare `READY`; from `research/uml-rootfs-prebake/`), modelled on the Wave 1 cross-test excerpt; **not** a real log | T2=90 ms from `T2_MOUNTS_READY` (beats the VFS timestamp), T3=110 ms from `T3_READY`; bare `READY` is gated and adds no value |
| `uml-console-bare-ready-ambiguous.txt` | synthetic | Bare `READY` line without any `T3_READY`/`INIT_ZERO_INSTALL` context | T3 `unknown`; bare `READY` maps to no lane metric |
| `uml-console-u1-ready-no-uptime.txt` | synthetic | U1 console with `U1_READY` missing the uptime field | T3 `unknown`; never estimated |
| `uml-console-u2-ready-bad-unit.txt` | synthetic | U2 console with `uptime=0.09ms` / `uptime=abc` (value with unit suffix / non-numeric) | T2/T3 `unknown` + `invalid ... uptime=` warnings; no guessing |
| `uml-exit-20261107-synthetic.txt` | synthetic | 11-07 median `duration_ms` order | `duration_ms=612` |
| `qemu-serial-systemd.txt` | synthetic | QEMU serial with kernel timestamps, guest markers and `systemd-analyze` total | T1=31, T2=510, T3=620, T4=1730 ms |
| `qemu-serial-markers-only.txt` | synthetic | QEMU serial without `systemd-analyze` | T4=900 ms from `multi-user.target` timestamp |
| `qemu-serial-systemd-no-total.txt` | synthetic | Truncated `Startup finished in ... =` line | T4 `unknown` + warning; no guessing |
| `systemd-analyze-old-style.txt` | synthetic | firmware/loader/kernel/initrd/userspace five-component form | total=8492 ms |
| `systemd-analyze-min-unit.txt` | synthetic | `1min 2.500s` component and total | total=62750 ms; kernel=250 ms |
| `dmesg-ok.txt` | synthetic | `dmesg` syslog-prefixed kernel timestamps | T1=20, T2=26 ms (fallback only) |
| `proot-timing-ok.txt` | synthetic | Canonical `BOOT_TIMING v1` pair | P0=50.101 ms |
| `proot-timing-bad-unit.txt` | synthetic | Malformed `ready_monotonic_ms` | P0 `unknown` + warning |
| `proot-timing-shorthand.txt` | synthetic | `p0_ms=` shorthand | P0=50.101 ms |
| `qemu-timing-ok.txt` | synthetic | Host QEMU spawn→T4 pair | Q0=1730 ms |
| `uml-timing-ok.txt` | synthetic | Host UML spawn→T3 pair | `t3_from_spawn_ms=400` |

The real-log regression is limited to the 2026-10-29 excerpt because the
2026-11-07/11-08 raw console and exit files are external evidence, not tracked
in this repository. Their format compatibility is covered by the synthetic
11-07 fixture, not by a real-log assertion; see the U0 report's `not-tested`
section.
