package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.contract.SharedDirectoryContract;
import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Ticket 07: the launcher starts the 9p server only when a share is
 * configured, treats a failure as a share-only degradation, and declares
 * service forwarding unsupported for AVF v1.
 */
public final class AvfShareWiringTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void noShareMeansNoServer() {
        FakeSession session = new FakeSession(false);
        AvfInstanceLauncher.startShareIfConfigured(session, null);
        assertEquals("no share must not start a 9p server", 0, session.starts);
        assertFalse(session.capabilities().sharedDirectory());
    }

    @Test
    public void configuredShareStartsOneServerAndIsDeclared() throws Exception {
        FakeSession session = new FakeSession(false);
        AvfInstanceLauncher.startShareIfConfigured(session, share());
        assertEquals(1, session.starts);
        assertTrue(session.isShareServing());
        assertTrue("a started server is declared in the descriptor",
                session.capabilities().sharedDirectory());
        assertTrue("the matrix projects the 9p capability",
                session.capabilityMatrix().supportsSharedDirectory());
    }

    @Test
    public void shareFailureKeepsTheLaunchAliveAndWithdrawsTheClaim() throws Exception {
        FakeSession session = new FakeSession(true);
        AvfInstanceLauncher.startShareIfConfigured(session, share());
        assertEquals(1, session.starts);
        assertFalse("a failed server must not be declared",
                session.capabilities().sharedDirectory());
        assertFalse(session.capabilityMatrix().supportsSharedDirectory());
        assertEquals("the failure carries a typed reason",
                AvfEngineException.VSOCK_CONNECT_FAILED, session.shareFailureReason());
    }

    @Test
    public void localShareFailureIsStillShareOnly() throws Exception {
        FakeSession session = new FakeSession(false);
        session.runtimeFailure = true;
        AvfInstanceLauncher.startShareIfConfigured(session, share());
        assertFalse(session.capabilities().sharedDirectory());
        assertEquals(AvfEngineException.INVALID_LAUNCH_CONFIG, session.shareFailureReason());
    }

    @Test
    public void serviceForwardIsTypedUnsupported() throws Exception {
        AvfInstanceLauncher.AvfSession session =
                new AvfInstanceLauncher.AvfSession(null, null, null);
        try {
            session.openServiceForward(18080);
            fail("AVF v1 must not pretend to forward");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("vsock→TCP relay"));
            assertEquals(AvfInstanceLauncher.SERVICE_FORWARD_UNSUPPORTED, expected.getMessage());
        }
        assertFalse("forwarding is not a declared capability",
                session.capabilityMatrix().capabilities().stream()
                        .anyMatch(capability -> capability.name().contains("FORWARD")));
    }

    @Test
    public void launcherDoesNotHardcodeTheShareOrAudioPorts() throws Exception {
        java.nio.file.Path direct = java.nio.file.Paths.get("src", "main", "java",
                "io", "github", "nyzeep", "orchestella", "AvfInstanceLauncher.java");
        java.nio.file.Path sourcePath = Files.exists(direct) ? direct
                : java.nio.file.Paths.get("app", "src", "main", "java",
                        "io", "github", "nyzeep", "orchestella", "AvfInstanceLauncher.java");
        String source = new String(Files.readAllBytes(sourcePath),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse("the 9p port must come from AvfLaunchSpec", source.contains("9102"));
        assertFalse("there is no AVF audio port in production", source.contains("9104"));
    }

    private SharedDirectoryContract.SharedDirectorySpec share() throws Exception {
        return SharedDirectoryContract.SharedDirectorySpec.defaultDownloads(
                temporaryFolder.newFolder().toPath().toAbsolutePath().toString());
    }

    /** Test seam: counts the one owner call and simulates a typed start failure. */
    private static final class FakeSession extends AvfInstanceLauncher.AvfSession {
        int starts;
        boolean serving;
        boolean runtimeFailure;
        private final boolean fail;

        FakeSession(boolean fail) {
            super(null, null, null);
            this.fail = fail;
        }

        @Override
        void startShareServer() throws AvfEngineException {
            starts++;
            if (runtimeFailure) {
                throw new IllegalStateException("malformed export path");
            }
            if (fail) {
                throw new AvfEngineException(AvfEngineException.VSOCK_CONNECT_FAILED,
                        "share port unreachable");
            }
            serving = true;
        }

        @Override
        boolean isShareServing() {
            return serving;
        }
    }
}
