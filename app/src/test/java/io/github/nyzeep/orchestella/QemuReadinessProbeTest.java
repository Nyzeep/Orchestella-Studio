package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** AX-08: readiness is the authenticated control channel, never liveness. */
public final class QemuReadinessProbeTest {
    /**
     * A canonical bridge token is exactly 64 lowercase hex characters. It is
     * assembled from 8-character groups so the source never contains a
     * token-shaped literal, and the value is a synthetic repeating pattern,
     * not a secret.
     */
    private static final String TOKEN =
            "0123abcd" + "4567ef01" + "2345abcd" + "6789ef01"
                    + "0123abcd" + "4567ef01" + "2345abcd" + "6789ef01";

    @Test
    public void absentProbeIsUnknownAndNotReady() {
        QemuReadinessProbe probe = QemuReadinessProbe.absent();

        assertEquals(QemuReadinessProbe.Readiness.UNKNOWN, probe.readiness());
        assertFalse(probe.isReady());
    }

    @Test
    public void nullSignalIsUnknown() {
        QemuReadinessProbe probe = new QemuReadinessProbe(() -> null);

        assertEquals(QemuReadinessProbe.Readiness.UNKNOWN, probe.readiness());
        assertFalse(probe.isReady());
    }

    @Test
    public void throwingSignalIsUnknown() {
        QemuReadinessProbe probe = new QemuReadinessProbe(() -> {
            throw new IllegalStateException("platform read failed");
        });

        assertEquals(QemuReadinessProbe.Readiness.UNKNOWN, probe.readiness());
        assertFalse(probe.isReady());
    }

    @Test
    public void missingBridgeClientIsUnknownNotReady() {
        QemuReadinessProbe probe = QemuReadinessProbe.ofBridgeClient(() -> null);

        assertEquals(QemuReadinessProbe.Readiness.UNKNOWN, probe.readiness());
        assertFalse(probe.isReady());
    }

    @Test
    public void unauthenticatedBridgeClientIsNotReady() {
        BridgeClient client = new BridgeClient(
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());

        QemuReadinessProbe probe = QemuReadinessProbe.ofBridgeClient(() -> client);

        assertFalse(client.isReady());
        assertEquals(QemuReadinessProbe.Readiness.NOT_READY, probe.readiness());
        assertFalse(probe.isReady());
    }

    @Test
    public void authenticatedBridgeClientIsReady() throws Exception {
        BridgeClient client = readyClient();

        QemuReadinessProbe probe = QemuReadinessProbe.ofBridgeClient(() -> client);

        assertEquals(QemuReadinessProbe.Readiness.READY, probe.readiness());
        assertTrue(probe.isReady());
    }

    /** A live session without a control channel has no readiness evidence. */
    @Test
    public void liveSessionWithoutBridgeClientIsUnknown() {
        FakeSession session = new FakeSession();

        QemuReadinessProbe probe = QemuReadinessProbe.ofSession(() -> session);

        assertTrue(session.isRuntimeAlive());
        assertEquals(QemuReadinessProbe.Readiness.UNKNOWN, probe.readiness());
        assertFalse(probe.isReady());
    }

    @Test
    public void sessionThatDoesNotExistYetIsUnknown() {
        QemuReadinessProbe probe = QemuReadinessProbe.ofSession(() -> null);

        assertEquals(QemuReadinessProbe.Readiness.UNKNOWN, probe.readiness());
    }

    @Test
    public void sessionSourceFailureIsUnknown() {
        QemuReadinessProbe probe = QemuReadinessProbe.ofSession(() -> {
            throw new IllegalStateException("session registry unavailable");
        });

        assertEquals(QemuReadinessProbe.Readiness.UNKNOWN, probe.readiness());
    }

    private static BridgeClient readyClient() throws Exception {
        BridgeClient client = new BridgeClient(
                new ByteArrayInputStream("ORCHESTELLA/READY 1\n"
                        .getBytes(StandardCharsets.US_ASCII)),
                new ByteArrayOutputStream());
        assertTrue(client.handshake(TOKEN).accepted());
        return client;
    }

    private static final class FakeSession implements RuntimeOwnedSession {
        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public OutputStream terminalInput() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream terminalOutput() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream controlInput() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream controlOutput() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void close() {
            // No resources in this fake.
        }

        @Override
        public RuntimeProcessSupervisor.StopResult stopRuntime(long graceMs) {
            return RuntimeProcessSupervisor.StopResult.CONFIRMED;
        }

        @Override
        public boolean isRuntimeAlive() {
            return true;
        }
    }
}
