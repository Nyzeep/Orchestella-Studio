package io.github.nyzeep.orchestella.prototype.usbfd;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runnable host model probe; synthetic file handles and model IDs cannot prove Android USB FDs. */
public final class UsbFdModelProbe {
    private UsbFdModelProbe() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) { throw new IllegalArgumentException("Supply an existing external owned temp directory"); }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path batch = Path.of("D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/C/implementation/temp").toAbsolutePath().normalize();
        if (!root.startsWith(batch) || !Files.isDirectory(root) || !root.toRealPath().equals(root)) {
            throw new SecurityException("Probe output is outside the owned external temp boundary");
        }
        Path fixture = Files.createTempFile(root, "usb-model-", ".txt");
        Files.writeString(fixture, "synthetic-fd-model");
        Resource actual = new Resource(fixture, 100);
        try (UsbFdSession session = new UsbFdSession()) {
            String request = session.requestPermission();
            session.permissionResult(request, true, () -> actual);
            UsbFdSession.ProbeLease lease = session.borrowForProbe();
            if (lease.fd() != 101) { throw new IllegalStateException("Model duplicate ownership failed"); }
            session.detached();
            try { actual.input.read(); throw new IllegalStateException("Handle was not closed"); }
            catch (IOException expected) { /* Actual synthetic file handle was closed. */ }
            boolean revoked = false;
            try { lease.fd(); } catch (IllegalStateException expected) { revoked = true; }
            if (!revoked || session.state() != UsbFdSession.State.DETACHED) {
                throw new IllegalStateException("Lease was not revoked");
            }
        }
        System.out.println("{\"hostModel\":\"VERIFIED\",\"resources\":\"actual-synthetic-file-handles\",\"fdNumbers\":\"MODEL_IDS\",\"androidUsbHost\":\"NOT_RUN\",\"nativePassthrough\":\"ACR_REQUIRED\"}");
        System.out.println("ANDROID_PROBE_NOT_RUN request UsbManager permission for a selected device; match device and request nonce; openDevice only after grant; retain UsbDeviceConnection; duplicate ParcelFileDescriptor for a bounded descriptor-only probe; deny stale callback; close duplicate then connection on detach/cancel/stop; record no descriptor payload or device serial. Native VMM attachment remains blocked pending ACR.");
    }

    private static final class Resource implements UsbFdSession.Descriptor {
        private final Path file;
        private final int modelId;
        private final FileInputStream input;
        Resource(Path file, int modelId) throws IOException {
            this.file = file;
            this.modelId = modelId;
            input = new FileInputStream(file.toFile());
        }
        @Override public int fd() { return modelId; }
        @Override public UsbFdSession.Descriptor duplicate() throws IOException { return new Resource(file, modelId + 1); }
        @Override public void close() throws IOException { input.close(); }
    }
}
