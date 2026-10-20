package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Ticket 02: a VM that started but whose session could not be registered must
 * be stopped within a bounded window; an unconfirmed stop must stay visible in
 * the typed failure instead of being reported as a clean launch failure.
 */
public final class AvfLaunchCleanupTest {

    @Test
    public void confirmedRegistrationCleanupStopsTheVmAndReportsFailure() throws Exception {
        FakeOwnedSession session = new FakeOwnedSession(
                RuntimeProcessSupervisor.StopResult.CONFIRMED);
        IOException cause = new IOException("register failed");
        try {
            AvfInstanceLauncher.cleanupFailedRegistration(session, cause);
            fail("a failed registration must fail the launch");
        } catch (IOException expected) {
            assertTrue("the original cause must stay attached",
                    expected.getCause() == cause);
            assertTrue("the diagnostic must name the unregistered session",
                    expected.getMessage().contains("registration"));
            assertEquals("one bounded stop request", 1, session.stops.get());
            assertFalse("the VM must not be left running", session.alive);
            assertEquals("the cleanup grace must be bounded", 2000L, session.lastGraceMs);
        }
    }

    @Test
    public void unconfirmedRegistrationCleanupKeepsAVisibleDiagnostic() throws Exception {
        FakeOwnedSession session = new FakeOwnedSession(
                RuntimeProcessSupervisor.StopResult.UNCONFIRMED);
        try {
            AvfInstanceLauncher.cleanupFailedRegistration(session, new IOException("register failed"));
            fail("a failed registration must fail the launch");
        } catch (IOException expected) {
            assertTrue("an unconfirmed stop must be named in the diagnostic",
                    expected.getMessage().contains("unconfirmed"));
            assertEquals(1, session.stops.get());
            assertTrue("the runtime may still be alive; the failure must not claim otherwise",
                    session.alive);
        }
    }

    @Test
    public void closeStopsTheOwnerOnceEvenWhenCalledTwice() {
        FakeAvfSession session = new FakeAvfSession();
        session.close();
        session.close();
        assertEquals("close must stop the VM and a repeat must not stop it again",
                1, session.stops);
    }

    /** Test seam: the real session delegates its owner stop; this counts it. */
    private static final class FakeAvfSession extends AvfInstanceLauncher.AvfSession {
        int stops;

        FakeAvfSession() {
            super(null, null, null);
        }

        @Override public RuntimeProcessSupervisor.StopResult stopRuntime(long graceMs) {
            stops++;
            return RuntimeProcessSupervisor.StopResult.CONFIRMED;
        }

        @Override public boolean isRuntimeAlive() {
            return false;
        }
    }

    private static final class FakeOwnedSession implements RuntimeOwnedSession {
        final AtomicInteger stops = new AtomicInteger();
        volatile boolean alive = true;
        volatile long lastGraceMs = -1L;
        private final RuntimeProcessSupervisor.StopResult result;

        FakeOwnedSession(RuntimeProcessSupervisor.StopResult result) {
            this.result = result;
        }

        @Override public int waitFor() { return 0; }
        @Override public OutputStream terminalInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream terminalOutput() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream controlInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream controlOutput() { return new ByteArrayInputStream(new byte[0]); }
        @Override public void close() { }

        @Override public RuntimeProcessSupervisor.StopResult stopRuntime(long graceMs) {
            stops.incrementAndGet();
            lastGraceMs = graceMs;
            if (result != RuntimeProcessSupervisor.StopResult.UNCONFIRMED) alive = false;
            return result;
        }

        @Override public boolean isRuntimeAlive() { return alive; }
    }
}
