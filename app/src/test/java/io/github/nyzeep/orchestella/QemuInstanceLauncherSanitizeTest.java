package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class QemuInstanceLauncherSanitizeTest {

    @Test
    public void testSanitizeSpecMapsGpuAndKeepsPasstNetworkPlane() {
        String[] original = new String[]{
                "/path/to/libdroidlayer_qemu.so",
                "-machine", "virt,gic-version=3",
                "-m", "1024M",
                "-device", "virtio-gpu-gl-device",
                "-netdev", "stream,id=orchestella-net,server=off,addr.type=unix,addr.path=/x/passt.sock",
                "-device", "virtio-net-pci,netdev=orchestella-net",
                "-device", "virtio-serial-device"
        };

        String[] sanitized = QemuInstanceLauncher.sanitizeSpecForTargetBinary(original);

        String[] expected = new String[]{
                "/path/to/libdroidlayer_qemu.so",
                "-machine", "virt,gic-version=3",
                "-m", "1024M",
                "-device", "virtio-gpu-device",
                "-netdev", "stream,id=orchestella-net,server=off,addr.type=unix,addr.path=/x/passt.sock",
                "-device", "virtio-net-pci,netdev=orchestella-net",
                "-device", "virtio-serial-device"
        };

        assertArrayEquals(expected, sanitized);
    }

    @Test
    public void testSanitizeSpecHandlesNullAndEmpty() {
        assertEquals(0, QemuInstanceLauncher.sanitizeSpecForTargetBinary(null).length);
        assertEquals(0, QemuInstanceLauncher.sanitizeSpecForTargetBinary(new String[0]).length);
    }
}
