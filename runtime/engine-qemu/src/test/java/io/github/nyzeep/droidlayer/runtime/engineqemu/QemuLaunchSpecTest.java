package io.github.nyzeep.droidlayer.runtime.engineqemu;

import io.github.nyzeep.droidlayer.runtime.contract.NetworkContract;
import io.github.nyzeep.droidlayer.runtime.contract.SharedDirectoryContract;

/** Contract tests for the fixed-argument QEMU launch specification and outbound network assembly. */
public final class QemuLaunchSpecTest {
    public static void main(String[] args) throws Exception {
        testDefaultLaunchSpecWithOutboundNetwork();
        testEngineCliFormattersDirectly();
        testOverloadedLaunchSpecWithCustomDnsAndCmdline();
        testOverloadedLaunchSpecWithMmioNetDevice();
        testLaunchSpecWithSharedDirectoryPci();
        testLaunchSpecWithSharedDirectoryMmio();
        testEngineFsdevFormattersDirectly();
    }

    private static void testDefaultLaunchSpecWithOutboundNetwork() {
        String[] spec = QemuLaunchSpec.build(
                "/native/lib/arm64-v8a/libdroidlayer_qemu.so",
                "/data/runtime/Image",
                "/data/runtime/initramfs-embedded.cpio.gz",
                "/data/instances/i1/root-volume.raw",
                "droidlayer-control-i1",
                "droidlayer-terminal-i1",
                512L);

        String joined = String.join("\u0000", spec);

        // Fixed machine shape (mirrors the proven provisioning smoke harness).
        String[][] expectedPairs = {
                {"-machine", "virt,gic-version=3"},
                {"-accel", "tcg,thread=multi"},
                {"-cpu", "cortex-a57"},
                {"-smp", "2"},
                {"-m", "512"},
        };
        for (String[] pair : expectedPairs) {
            requireArg(spec, pair[0], pair[1]);
        }
        for (String flag : new String[] {"-nodefaults", "-display", "none", "-monitor", "none", "-no-reboot"}) {
            if (!joined.contains("\u0000" + flag + "\u0000") && !spec[spec.length - 1].equals(flag)
                    && !joined.startsWith(flag + "\u0000")) {
                boolean found = false;
                for (String s : spec) {
                    if (s.equals(flag)) {
                        found = true;
                    }
                }
                if (!found) {
                    fail("missing flag: " + flag);
                }
            }
        }

        // Kernel/initrd/root volume wiring.
        requireArg(spec, "-kernel", "/data/runtime/Image");
        requireArg(spec, "-initrd", "/data/runtime/initramfs-embedded.cpio.gz");
        requireArg(spec, "-drive", "if=none,id=root,file=/data/instances/i1/root-volume.raw,format=raw,cache=unsafe");
        requireArg(spec, "-device", "virtio-blk-device,drive=root");

        // Outbound SLIRP network assembly with default Android DNS fallback (8.8.8.8) (AC-1, AC-2, ADR-0008).
        requireArg(spec, "-netdev", "user,id=net0,ipv6=off,net=10.0.2.0/24,dhcpstart=10.0.2.15,dns=8.8.8.8");
        requireArg(spec, "-device", "virtio-net-pci,netdev=net0");

        // Explicit IPv6 off assertion (ADR-0008).
        if (!joined.contains("ipv6=off")) {
            fail("ipv6=off missing from netdev");
        }
        if (joined.contains("ipv6=on")) {
            fail("ipv6=on present in netdev");
        }

        // Zero inbound exposure assertion (ADR-0008, ADR-0010).
        if (joined.contains("hostfwd")) {
            fail("inbound hostfwd rule leaked into default spec");
        }

        // Bridge and terminal channels use abstract sockets served by QEMU.
        requireArg(spec, "-chardev",
                "socket,id=terminal,path=droidlayer-terminal-i1,abstract=on,server=on,wait=off");
        requireArg(spec, "-serial", "chardev:terminal");
        requireArg(spec, "-chardev",
                "socket,id=control,path=droidlayer-control-i1,abstract=on,server=on,wait=off");
        requireArg(spec, "-device", "virtserialport,chardev=control,name=droidlayer.bridge");
        requireArg(spec, "-device", "virtio-serial-device");

        // Guest provisioning cmdline with default DNS fallback (AC-2).
        String expectedDefaultCmdline = QemuLaunchSpec.GUEST_CMDLINE + " droidlayer.dns=8.8.8.8";
        requireArg(spec, "-append", expectedDefaultCmdline);

        // No 9p transport may leak into the launch (virtfs is disabled in the build).
        if (joined.contains("9p") || joined.contains("fsdev") || joined.contains("virtio-9p")) {
            fail("9p transport present");
        }
        // No shell: every element must be argument-safe.
        for (String s : spec) {
            if (s.contains(";") || s.contains("&") || s.contains("|") || s.contains("`")) {
                fail("shell metacharacter in argument");
            }
        }
        if (!spec[0].endsWith("libdroidlayer_qemu.so")) {
            fail("executable is not the packaged QEMU entry");
        }

        System.out.println("DEFAULT_LAUNCH_SPEC_OK args=" + spec.length + " outbound_net=1 default_dns=8.8.8.8 no_9p=1");
    }

    private static void testEngineCliFormattersDirectly() {
        // Formatter test without DNS
        NetworkContract.OutboundNetworkSpec noDnsSpec =
                NetworkContract.OutboundNetworkSpec.builder()
                        .dnsServer(null)
                        .cmdlineDns(null)
                        .build();
        String netdevNoDns = QemuLaunchSpec.formatNetdevArg(noDnsSpec);
        if (!netdevNoDns.equals("user,id=net0,ipv6=off,net=10.0.2.0/24,dhcpstart=10.0.2.15")) {
            fail("formatNetdevArg without DNS mismatch: " + netdevNoDns);
        }

        // Formatter test with default DNS (8.8.8.8)
        NetworkContract.OutboundNetworkSpec defaultSpec = NetworkContract.OutboundNetworkSpec.defaultSpec();
        String netdevWithDns = QemuLaunchSpec.formatNetdevArg(defaultSpec);
        if (!netdevWithDns.equals("user,id=net0,ipv6=off,net=10.0.2.0/24,dhcpstart=10.0.2.15,dns=8.8.8.8")) {
            fail("formatNetdevArg with DNS mismatch: " + netdevWithDns);
        }

        // Device formatter tests
        String pciDevice = QemuLaunchSpec.formatDeviceArg(defaultSpec);
        if (!pciDevice.equals("virtio-net-pci,netdev=net0")) {
            fail("formatDeviceArg PCI mismatch: " + pciDevice);
        }

        NetworkContract.OutboundNetworkSpec mmioSpec =
                NetworkContract.OutboundNetworkSpec.builder()
                        .deviceType(NetworkContract.DeviceType.VIRTIO_NET_DEVICE)
                        .build();
        String mmioDevice = QemuLaunchSpec.formatDeviceArg(mmioSpec);
        if (!mmioDevice.equals("virtio-net-device,netdev=net0")) {
            fail("formatDeviceArg MMIO mismatch: " + mmioDevice);
        }

        System.out.println("ENGINE_CLI_FORMATTERS_TEST_OK");
    }

    private static void testOverloadedLaunchSpecWithCustomDnsAndCmdline() {
        NetworkContract.OutboundNetworkSpec networkSpec =
                NetworkContract.OutboundNetworkSpec.withDns(NetworkContract.CLOUDFLARE_DNS);

        String[] spec = QemuLaunchSpec.build(
                "/native/lib/arm64-v8a/libdroidlayer_qemu.so",
                "/data/runtime/Image",
                "/data/runtime/initramfs-embedded.cpio.gz",
                "/data/instances/i1/root-volume.raw",
                "droidlayer-control-i1",
                "droidlayer-terminal-i1",
                512L,
                networkSpec);

        requireArg(spec, "-netdev", "user,id=net0,ipv6=off,net=10.0.2.0/24,dhcpstart=10.0.2.15,dns=1.1.1.1");
        requireArg(spec, "-device", "virtio-net-pci,netdev=net0");

        String expectedCmdline = QemuLaunchSpec.GUEST_CMDLINE + " droidlayer.dns=1.1.1.1";
        requireArg(spec, "-append", expectedCmdline);

        System.out.println("DNS_LAUNCH_SPEC_OK dns=1.1.1.1 cmdlineDns=1");
    }

    private static void testOverloadedLaunchSpecWithMmioNetDevice() {
        NetworkContract.OutboundNetworkSpec networkSpec =
                NetworkContract.OutboundNetworkSpec.builder()
                        .deviceType(NetworkContract.DeviceType.VIRTIO_NET_DEVICE)
                        .dnsServer("9.9.9.9")
                        .cmdlineDns("9.9.9.9")
                        .build();

        String[] spec = QemuLaunchSpec.build(
                "/native/lib/arm64-v8a/libdroidlayer_qemu.so",
                "/data/runtime/Image",
                "/data/runtime/initramfs-embedded.cpio.gz",
                "/data/instances/i1/root-volume.raw",
                "droidlayer-control-i1",
                "droidlayer-terminal-i1",
                512L,
                networkSpec);

        requireArg(spec, "-netdev", "user,id=net0,ipv6=off,net=10.0.2.0/24,dhcpstart=10.0.2.15,dns=9.9.9.9");
        requireArg(spec, "-device", "virtio-net-device,netdev=net0");
        requireArg(spec, "-append", QemuLaunchSpec.GUEST_CMDLINE + " droidlayer.dns=9.9.9.9");

        System.out.println("MMIO_NET_DEVICE_SPEC_OK device=virtio-net-device");
    }

    private static void testLaunchSpecWithSharedDirectoryPci() {
        SharedDirectoryContract.SharedDirectorySpec shareSpec =
                SharedDirectoryContract.SharedDirectorySpec.defaultDownloads("/storage/emulated/0/Download");

        String[] spec = QemuLaunchSpec.build(
                "/native/lib/arm64-v8a/libdroidlayer_qemu.so",
                "/data/runtime/Image",
                "/data/runtime/initramfs-embedded.cpio.gz",
                "/data/instances/i1/root-volume.raw",
                "droidlayer-control-i1",
                "droidlayer-terminal-i1",
                512L,
                shareSpec);

        String joined = String.join("\u0000", spec);

        // 9p fsdev and device assertions (ADR-0009, ADR-0010, Ticket 30)
        requireArg(spec, "-fsdev", "local,id=fsdev0,path=/storage/emulated/0/Download,security_model=none,readonly=on");
        requireArg(spec, "-device", "virtio-9p-pci,fsdev=fsdev0,mount_tag=downloads");

        if (!joined.contains("readonly=on")) {
            fail("readonly=on missing from fsdev");
        }
        if (joined.contains("readonly=off")) {
            fail("readonly=off present in fsdev");
        }

        // Verify shell safety
        for (String s : spec) {
            if (s.contains(";") || s.contains("&") || s.contains("|") || s.contains("`")) {
                fail("shell metacharacter in argument");
            }
        }
        System.out.println("SHARED_DIRECTORY_PCI_SPEC_OK");
    }

    private static void testLaunchSpecWithSharedDirectoryMmio() {
        SharedDirectoryContract.SharedDirectorySpec shareSpec =
                SharedDirectoryContract.SharedDirectorySpec.builder()
                        .hostPath("/sdcard/Download")
                        .deviceType(SharedDirectoryContract.DeviceType.VIRTIO_9P_DEVICE)
                        .build();

        String[] spec = QemuLaunchSpec.build(
                "/native/lib/arm64-v8a/libdroidlayer_qemu.so",
                "/data/runtime/Image",
                "/data/runtime/initramfs-embedded.cpio.gz",
                "/data/instances/i1/root-volume.raw",
                "droidlayer-control-i1",
                "droidlayer-terminal-i1",
                512L,
                NetworkContract.OutboundNetworkSpec.defaultSpec(),
                shareSpec);

        requireArg(spec, "-fsdev", "local,id=fsdev0,path=/sdcard/Download,security_model=none,readonly=on");
        requireArg(spec, "-device", "virtio-9p-device,fsdev=fsdev0,mount_tag=downloads");
        System.out.println("SHARED_DIRECTORY_MMIO_SPEC_OK");
    }

    private static void testEngineFsdevFormattersDirectly() {
        SharedDirectoryContract.SharedDirectorySpec pciSpec =
                SharedDirectoryContract.SharedDirectorySpec.defaultDownloads("/path/to/downloads");
        if (!QemuLaunchSpec.formatFsdevArg(pciSpec).equals("local,id=fsdev0,path=/path/to/downloads,security_model=none,readonly=on")) {
            fail("formatFsdevArg mismatch");
        }
        if (!QemuLaunchSpec.formatFsDeviceArg(pciSpec).equals("virtio-9p-pci,fsdev=fsdev0,mount_tag=downloads")) {
            fail("formatFsDeviceArg PCI mismatch");
        }

        SharedDirectoryContract.SharedDirectorySpec mmioSpec =
                SharedDirectoryContract.SharedDirectorySpec.builder()
                        .hostPath("/path/to/downloads")
                        .deviceType(SharedDirectoryContract.DeviceType.VIRTIO_9P_DEVICE)
                        .build();
        if (!QemuLaunchSpec.formatFsDeviceArg(mmioSpec).equals("virtio-9p-device,fsdev=fsdev0,mount_tag=downloads")) {
            fail("formatFsDeviceArg MMIO mismatch");
        }
        System.out.println("ENGINE_FSDEV_FORMATTERS_TEST_OK");
    }

    private static void requireArg(String[] spec, String flag, String value) {
        for (int i = 0; i < spec.length - 1; i++) {
            if (spec[i].equals(flag) && spec[i + 1].equals(value)) {
                return;
            }
        }
        fail("missing flag/value pair: " + flag + "=" + value);
    }

    private static void fail(String reason) {
        System.out.println("QEMU_LAUNCH_SPEC_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
