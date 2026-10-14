package io.github.nyzeep.orchestella.prototype.usbfd;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Host-only FD ownership model. Integers are model IDs; resources are actual synthetic file handles. */
public final class UsbFdSessionTest {
    @Test public void denialNeverOpensAndGrantDetachCloseRevokeRealHandles() throws Exception {
        Path fixture = Files.writeString(Files.createTempFile("phase5-usb-model-", ".txt"), "synthetic descriptor");
        AtomicInteger opens = new AtomicInteger();
        try (UsbFdSession session = new UsbFdSession()) {
            String denied = session.requestPermission();
            session.permissionResult(denied, false, () -> { opens.incrementAndGet(); throw new AssertionError("denied must not open"); });
            assertEquals(UsbFdSession.State.DENIED, session.state());
            assertEquals(0, opens.get());
            String granted = session.requestPermission();
            FileDescriptorResource resource = new FileDescriptorResource(fixture, 50);
            session.permissionResult(granted, true, () -> resource);
            assertEquals(UsbFdSession.State.OPEN, session.state());
            UsbFdSession.ProbeLease lease = session.borrowForProbe();
            assertEquals(51, lease.fd());
            assertThrows(UnsupportedOperationException.class, session::requireNativePassthrough);
            session.detached();
            assertEquals(UsbFdSession.State.DETACHED, session.state());
            assertThrows(IOException.class, resource.input::read);
            assertThrows(IllegalStateException.class, lease::fd);
            assertThrows(IllegalStateException.class, session::borrowForProbe);
        }
    }

    @Test public void stalePermissionAfterDetachOrCloseCannotOpenNewResource() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        UsbFdSession session = new UsbFdSession();
        String request = session.requestPermission();
        session.detached();
        assertThrows(IllegalStateException.class, () -> session.permissionResult(request, true, () -> {
            opens.incrementAndGet(); throw new AssertionError("stale must not open");
        }));
        assertThrows(IllegalStateException.class, session::requestPermission);
        session.close();
        assertEquals(UsbFdSession.State.CLOSED, session.state());
        assertThrows(IllegalStateException.class, session::requestPermission);
        assertEquals(0, opens.get());
    }

    @Test public void failedOpenAndDuplicateKeepHonestStateAndCloseRealResource() throws Exception {
        Path fixture = Files.writeString(Files.createTempFile("phase5-usb-invalid-", ".txt"), "synthetic");
        FileDescriptorResource invalid = new FileDescriptorResource(fixture, -1);
        try (UsbFdSession session = new UsbFdSession()) {
            String request = session.requestPermission();
            assertThrows(IOException.class, () -> session.permissionResult(request, true, () -> invalid));
            assertEquals(UsbFdSession.State.FAILED, session.state());
            assertThrows(IOException.class, invalid.input::read);
        }
        FileInputStream actual = new FileInputStream(fixture.toFile());
        try (UsbFdSession session = new UsbFdSession()) {
            String request = session.requestPermission();
            session.permissionResult(request, true, () -> new UsbFdSession.Descriptor() {
                @Override public int fd() { return 80; }
                @Override public UsbFdSession.Descriptor duplicate() throws IOException { throw new IOException("synthetic dup refusal"); }
                @Override public void close() throws IOException { actual.close(); }
            });
            assertThrows(IOException.class, session::borrowForProbe);
            assertEquals(UsbFdSession.State.OPEN, session.state());
            assertEquals('s', actual.read());
        }
        assertThrows(IOException.class, actual::read);
    }

    static final class FileDescriptorResource implements UsbFdSession.Descriptor {
        final FileInputStream input;
        final Path fixture;
        final int modelId;
        boolean closed;
        FileDescriptorResource(Path fixture, int modelId) throws IOException {
            this.fixture = fixture;
            this.modelId = modelId;
            input = new FileInputStream(fixture.toFile());
        }
        @Override public int fd() { return modelId; }
        @Override public UsbFdSession.Descriptor duplicate() throws IOException { return new FileDescriptorResource(fixture, modelId + 1); }
        @Override public void close() throws IOException { closed = true; input.close(); }
    }
}
