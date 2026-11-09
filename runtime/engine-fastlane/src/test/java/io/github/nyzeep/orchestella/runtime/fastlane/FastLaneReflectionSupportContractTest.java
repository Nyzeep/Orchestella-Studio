package io.github.nyzeep.orchestella.runtime.fastlane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.system.virtualmachine.VirtualMachineException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Contract for the reflective invoke primitives and platform-failure mapping
 * behind {@link ReflectiveFastLaneApi} (2026-11-09 round A, seam 2): the
 * FastLaneException reason mapping is a hard boundary against the platform and
 * must survive any further refactor of the adapter.
 */
public final class FastLaneReflectionSupportContractTest {

    @Test
    public void invokeReturnsTheValueAndRecordsTheAppliedNote() throws Exception {
        List<String> notes = new ArrayList<>();
        Object value = FastLaneReflectionSupport.invoke(Provider.class, new Provider(),
                "supply", new Class<?>[0], new Object[0],
                FastLaneException.VM_CREATE_FAILED, notes);
        assertEquals("value", value);
        assertEquals(List.of("supply: applied"), notes);
    }

    @Test
    public void invokeMapsSecurityFailuresToPermissionDeniedAndKeepsTheCause()
            throws Exception {
        try {
            FastLaneReflectionSupport.invoke(Guarded.class, new Guarded(),
                    "guarded", new Class<?>[0], new Object[0],
                    FastLaneException.VM_CREATE_FAILED, new ArrayList<>());
            fail("the guarded method must surface as FastLaneException");
        } catch (FastLaneException failure) {
            assertEquals(FastLaneException.PERMISSION_DENIED, failure.reason());
            assertTrue(failure.getCause() instanceof SecurityException);
        }
    }

    @Test
    public void invokeKeepsTheCallerReasonForAnUnmappedTargetException() throws Exception {
        try {
            FastLaneReflectionSupport.invoke(Provider.class, new Provider(),
                    "rejected", new Class<?>[0], new Object[0],
                    FastLaneException.VM_CREATE_FAILED, new ArrayList<>());
            fail("the rejected method must surface as FastLaneException");
        } catch (FastLaneException failure) {
            assertEquals("an unmapped target failure keeps the caller's reason",
                    FastLaneException.VM_CREATE_FAILED, failure.reason());
        }
    }

    @Test
    public void inaccessibleMethodsFailClosedAsHiddenApiBlocked() throws Exception {
        try {
            FastLaneReflectionSupport.invoke(Provider.class, new Provider(),
                    "missing", new Class<?>[0], new Object[0],
                    FastLaneException.VM_CREATE_FAILED, new ArrayList<>());
            fail("a missing method must surface as FastLaneException");
        } catch (FastLaneException failure) {
            assertEquals(FastLaneException.HIDDEN_API_BLOCKED, failure.reason());
        }
    }

    @Test
    public void setDelegatesToInvokeWithTheSingleParameterShape() throws Exception {
        List<String> notes = new ArrayList<>();
        Provider target = new Provider();
        FastLaneReflectionSupport.set(Provider.class, target, "store", String.class,
                "payload", notes);
        assertEquals("payload", target.stored);
        assertEquals(List.of("store: applied"), notes);
    }

    @Test
    public void mappedRecognizesEveryPlatformBoundary() {
        assertEquals(FastLaneException.PERMISSION_DENIED,
                FastLaneReflectionSupport.mapped("stage", FastLaneException.VM_CREATE_FAILED,
                        new SecurityException("denied")).reason());
        assertEquals(FastLaneException.PROTECTED_UNAVAILABLE,
                FastLaneReflectionSupport.mapped("stage", FastLaneException.VM_CREATE_FAILED,
                        new UnsupportedOperationException("not supported")).reason());
        assertEquals(FastLaneException.HIDDEN_API_BLOCKED,
                FastLaneReflectionSupport.mapped("stage", FastLaneException.VM_CREATE_FAILED,
                        new ReflectiveOperationException("nope")).reason());
        assertEquals(FastLaneException.HIDDEN_API_BLOCKED,
                FastLaneReflectionSupport.mapped("stage", FastLaneException.VM_CREATE_FAILED,
                        new LinkageError("gone")).reason());
        assertEquals(FastLaneException.PAYLOAD_VERIFY_FAILED,
                FastLaneReflectionSupport.mapped("stage", FastLaneException.VM_CREATE_FAILED,
                        new VirtualMachineException("payload verification failed")).reason());
        assertEquals("unmapped boundaries keep the caller's reason",
                FastLaneException.VM_CREATE_FAILED,
                FastLaneReflectionSupport.mapped("stage", FastLaneException.VM_CREATE_FAILED,
                        new IllegalStateException("odd")).reason());
    }

    @Test
    public void unwrapIoBoundaryExposesTheTransportCauseButKeepsPlatformIdentity() {
        RuntimeException boundary = new IllegalStateException("transport died");
        assertSame(boundary, FastLaneReflectionSupport.unwrapIoBoundary(
                new IOException("adoption failed", boundary)));

        VirtualMachineException platform = new VirtualMachineException("payload has changed");
        IOException wrapped = new IOException("adoption failed", platform);
        assertSame("a platform VirtualMachineException keeps its own identity",
                wrapped, FastLaneReflectionSupport.unwrapIoBoundary(wrapped));

        RuntimeException plain = new IllegalStateException("no wrapper");
        assertSame(plain, FastLaneReflectionSupport.unwrapIoBoundary(plain));
        IOException causeless = new IOException("causeless");
        assertSame("a causeless IOException has no boundary to expose",
                causeless, FastLaneReflectionSupport.unwrapIoBoundary(causeless));
    }

    @Test
    public void payloadVerificationIsRecognizedThroughTheWholeCauseChain() {
        assertTrue(FastLaneReflectionSupport.looksLikePayloadVerificationFailure(
                new VirtualMachineException("payload verification failed")));
        assertTrue(FastLaneReflectionSupport.looksLikePayloadVerificationFailure(
                new VirtualMachineException("Payload has changed")));
        assertTrue(FastLaneReflectionSupport.looksLikePayloadVerificationFailure(
                new IOException("adoption failed",
                        new VirtualMachineException("payload changed"))));
        assertFalse(FastLaneReflectionSupport.looksLikePayloadVerificationFailure(
                new VirtualMachineException("unrelated")));
        assertFalse(FastLaneReflectionSupport.looksLikePayloadVerificationFailure(
                new IllegalStateException("odd")));
        assertFalse("a self-referential cause chain must terminate",
                FastLaneReflectionSupport.looksLikePayloadVerificationFailure(
                        new SelfCyclic()));
        assertFalse("a message-less platform exception must not crash the scan",
                FastLaneReflectionSupport.looksLikePayloadVerificationFailure(
                        new VirtualMachineException((String) null)));
    }

    @Test
    public void intFieldReadsPublicStaticIntsAndReportsMissingOnes() throws Exception {
        assertEquals(7, FastLaneReflectionSupport.intField(Fields.class, "ANSWER"));
        try {
            FastLaneReflectionSupport.intField(Fields.class, "MISSING");
            fail("a missing field must surface as NoSuchFieldException");
        } catch (NoSuchFieldException expected) {
            assertTrue(expected.getMessage().contains("MISSING"));
        }
    }

    @Test
    public void errorCarriesReasonMessageAndCause() {
        Exception cause = new IllegalStateException("root");
        FastLaneException failure = FastLaneReflectionSupport.error(
                FastLaneException.VSOCK_FAILED, "message", cause);
        assertEquals(FastLaneException.VSOCK_FAILED, failure.reason());
        assertEquals("message", failure.getMessage());
        assertSame(cause, failure.getCause());
    }

    @Test
    public void closeChannelSuppressesACloseFailureOntoThePrimary() throws Exception {
        Throwable primary = new IOException("primary");
        FastLaneSupportChannel channel = new FastLaneSupportChannel();
        channel.failure = new IOException("close failed");
        FastLaneReflectionSupport.closeChannel(channel, primary);
        assertEquals(1, primary.getSuppressed().length);
        assertSame(channel.failure, primary.getSuppressed()[0]);
        assertTrue(channel.closed);

        FastLaneSupportChannel healthy = new FastLaneSupportChannel();
        Throwable quiet = new IOException("primary");
        FastLaneReflectionSupport.closeChannel(healthy, quiet);
        assertEquals(0, quiet.getSuppressed().length);
        assertTrue(healthy.closed);
    }

    /** A cause chain whose direct self-reference must terminate the scan. */
    private static final class SelfCyclic extends RuntimeException {
        private static final long serialVersionUID = 1L;

        @Override public synchronized Throwable getCause() {
            return this;
        }
    }

    /** Channel double carrying a controllable close failure. */
    private static final class FastLaneSupportChannel implements FastLaneChannel {
        IOException failure;
        boolean closed;

        @Override public java.io.InputStream inputStream() {
            throw new UnsupportedOperationException();
        }

        @Override public java.io.OutputStream outputStream() {
            throw new UnsupportedOperationException();
        }

        @Override public void close() throws IOException {
            closed = true;
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** Reflection targets. */
    public static final class Provider {
        String stored;

        public String supply() {
            return "value";
        }

        public String rejected() {
            throw new IllegalStateException("target refused");
        }

        public void store(String value) {
            this.stored = value;
        }
    }

    public static final class Guarded {
        public String guarded() {
            throw new SecurityException("platform denied");
        }
    }

    public static final class Fields {
        public static final int ANSWER = 7;
    }
}
