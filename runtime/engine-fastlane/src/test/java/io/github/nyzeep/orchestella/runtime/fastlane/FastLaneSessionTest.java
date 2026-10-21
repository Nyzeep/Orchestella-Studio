package io.github.nyzeep.orchestella.runtime.fastlane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class FastLaneSessionTest {
    private static final String SHA = "b".repeat(64);

    private static FastLaneLaunchSpec spec() {
        return new FastLaneLaunchSpec(
                "fastlane-test",
                "libfastlane_payload.so",
                1024,
                FastLaneLaunchSpec.CpuTopology.ONE_CPU,
                268435456L,
                FastLaneLaunchSpec.DebugLevel.NONE,
                5000,
                5001,
                5002,
                5003,
                SHA);
    }

    @Test
    public void opensOneChannelPerPortAndStopsConfirmed() throws Exception {
        FakeHandle handle = new FakeHandle();
        FakeApi api = new FakeApi(handle);
        FastLaneSession session = new FastLaneSession(api, handle, spec());

        FastLaneChannel control = session.controlChannel();
        assertSame(control, session.controlChannel());
        assertEquals(1, api.connectCount);

        control.outputStream().write("ping".getBytes(StandardCharsets.UTF_8));
        assertEquals("ping", controlChannelText(control));

        assertEquals(FastLaneStopResult.CONFIRMED, session.stopRuntime(100L));
        assertEquals(1, handle.stopCount);
        assertFalse(session.isAlive());
        assertEquals(FastLaneStopResult.CONFIRMED, session.stopRuntime(100L));
        assertEquals(1, handle.stopCount);
        assertThrows(FastLaneException.class, session::controlChannel);
    }

    @Test
    public void unconfirmedStopIsVisibleAndRetryable() throws Exception {
        FakeHandle handle = new FakeHandle();
        FakeApi api = new FakeApi(handle);
        FastLaneSession session = new FastLaneSession(api, handle, spec());

        handle.nextStopResult = FastLaneStopResult.UNCONFIRMED;
        assertEquals(FastLaneStopResult.UNCONFIRMED, session.stopRuntime(10L));
        assertEquals(FastLaneStopResult.UNCONFIRMED, session.stopResult());
        assertEquals(1, handle.stopCount);

        handle.nextStopResult = FastLaneStopResult.CONFIRMED;
        assertEquals(FastLaneStopResult.CONFIRMED, session.stopRuntime(10L));
        assertEquals(2, handle.stopCount);
    }

    @Test
    public void connectFailureIsPropagatedAndRetryable() throws Exception {
        FakeHandle handle = new FakeHandle();
        FakeApi api = new FakeApi(handle);
        FastLaneSession session = new FastLaneSession(api, handle, spec());

        api.failConnect = true;
        FastLaneException failure = assertThrows(FastLaneException.class, session::terminalChannel);
        assertEquals(FastLaneException.VSOCK_FAILED, failure.reason());
        assertEquals(1, api.connectCount);

        api.failConnect = false;
        assertTrue(session.terminalChannel().inputStream().available() >= 0);
        assertEquals(2, api.connectCount);
    }

    @Test
    public void rejectsUnknownPort() {
        FakeHandle handle = new FakeHandle();
        FastLaneSession session = new FastLaneSession(new FakeApi(handle), handle, spec());
        FastLaneException failure = assertThrows(FastLaneException.class, () -> session.channel(6000));
        assertEquals(FastLaneException.CONFIG_INVALID, failure.reason());
    }

    @Test
    public void closeClosesChannelsWithoutThrowing() throws Exception {
        FakeHandle handle = new FakeHandle();
        FakeApi api = new FakeApi(handle);
        FastLaneSession session = new FastLaneSession(api, handle, spec());
        session.controlChannel();

        session.close();
        assertTrue(api.lastChannel.closed);
        assertEquals(1, handle.stopCount);
    }

    private static String controlChannelText(FastLaneChannel channel) throws IOException {
        InputStream input = channel.inputStream();
        byte[] bytes = input.readNBytes(4);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final class FakeApi implements FastLaneVirtualMachineApi {
        private final FakeHandle handle;
        private int connectCount;
        private boolean failConnect;
        private FakeChannel lastChannel;

        private FakeApi(FakeHandle handle) {
            this.handle = handle;
        }

        @Override
        public FastLaneHandle start(FastLaneLaunchSpec spec) {
            return handle;
        }

        @Override
        public FastLaneChannel connect(FastLaneHandle handle, int port) throws FastLaneException {
            connectCount++;
            if (failConnect) {
                throw new FastLaneException(FastLaneException.VSOCK_FAILED, "fake connect failure");
            }
            lastChannel = new FakeChannel();
            return lastChannel;
        }
    }

    private static final class FakeHandle implements FastLaneHandle {
        private int status = 1;
        private int stopCount;
        private FastLaneStopResult nextStopResult = FastLaneStopResult.CONFIRMED;

        @Override
        public String instanceId() {
            return "fastlane-test";
        }

        @Override
        public int status() {
            return status;
        }

        @Override
        public FastLaneExitInfo waitForExit() {
            return FastLaneExitInfo.normal(0L);
        }

        @Override
        public FastLaneStopResult stopRuntime(long graceMs) {
            stopCount++;
            if (nextStopResult != FastLaneStopResult.UNCONFIRMED) {
                status = 0;
            }
            return nextStopResult;
        }

        @Override
        public void close() {
            status = 0;
        }

        @Override
        public List<String> notes() {
            return new ArrayList<>();
        }
    }

    private static final class FakeChannel implements FastLaneChannel {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final ByteArrayInputStream input = new ByteArrayInputStream(new byte[0]);
        private boolean closed;

        @Override
        public InputStream inputStream() {
            return new ByteArrayInputStream(output.toByteArray());
        }

        @Override
        public OutputStream outputStream() {
            return output;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
