package io.github.nyzeep.droidlayer.runtime.engineavf;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Terminal stream read/write over the AVF vsock terminal channel: raw bytes
 * in, raw bytes out — the transport-only terminal behavior the contract
 * requires (the channel stays byte-transparent for the root shell).
 */
public final class AvfTerminalStreamContractTest {
    public static void main(String[] arguments) throws Exception {
        terminalBytesRoundTripOverVsock();
        largeChunkSurvivesTheChannel();
        closingTheGuestSideEndsTheHostStream();
        System.out.println("AVF_TERMINAL_STREAM_CONTRACT_TEST_OK echo=1 large_chunk=1 guest_eof=1");
    }

    private static AvfVirtualMachineEngine.AvfVmSession session(SimulatedAvfGuest guest)
            throws Exception {
        AvfVirtualMachineEngine engine =
                new AvfVirtualMachineEngine(AvfVirtualMachineApiFixture.apiOver(guest));
        return engine.launch(AvfLaunchSpec.builder(
                        "terminal-contract-1", "/kernel", "/initramfs", "/root.raw")
                .build());
    }

    private static void terminalBytesRoundTripOverVsock() throws Exception {
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                9100, 9101)) {
            AvfVirtualMachineEngine.AvfVmSession session = session(guest);
            session.terminalChannel().outputStream()
                    .write("uname -a\n".getBytes(StandardCharsets.UTF_8));
            session.terminalChannel().outputStream().flush();
            assertEquals("uname -a\n", readLine(session.terminalChannel().inputStream()),
                    "terminal echo preserves the exact bytes and line");
            session.closeChannels();
        }
    }

    private static void largeChunkSurvivesTheChannel() throws Exception {
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                9100, 9101)) {
            AvfVirtualMachineEngine.AvfVmSession session = session(guest);
            StringBuilder expected = new StringBuilder();
            StringBuilder chunk = new StringBuilder();
            for (int index = 0; index < 100; index++) {
                chunk.append("0123456789");
            }
            for (int line = 0; line < 8; line++) {
                expected.append(chunk).append('\n');
            }
            byte[] payload = expected.toString().getBytes(StandardCharsets.UTF_8);
            // One oversized write must not depend on the reader draining first:
            // the echo guest drains, so just write it all, then read it all.
            session.terminalChannel().outputStream().write(payload);
            session.terminalChannel().outputStream().flush();
            StringBuilder actual = new StringBuilder();
            while (actual.length() < expected.length()) {
                String line = readLine(session.terminalChannel().inputStream());
                if (line.isEmpty()) {
                    fail("terminal stream ended before the full echo arrived");
                }
                actual.append(line);
            }
            assertEquals(expected.toString(), actual.toString(), "1000-byte lines survive");
            session.closeChannels();
        }
    }

    private static void closingTheGuestSideEndsTheHostStream() throws Exception {
        String token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        SimulatedAvfGuest guest = new SimulatedAvfGuest(token, 9100, 9101);
        try {
            AvfVirtualMachineEngine.AvfVmSession session = session(guest);
            // The channel must exist while the guest is alive; only then does a
            // guest-side death show up as EOF on the host stream.
            AvfVirtualMachineApi.AvfVsockChannel terminal = session.terminalChannel();
            guest.close();
            int end = terminal.inputStream().read();
            assertEquals(-1, end, "guest-side close is observed as EOF by the host");
        } finally {
            guest.close();
        }
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                return line + "\n";
            }
            line.append((char) b);
        }
        return line.toString();
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            fail(description + ": expected length="
                    + (expected == null ? "null" : Integer.valueOf(expected.toString().length()))
                    + ", actual length="
                    + (actual == null ? "null" : Integer.valueOf(actual.toString().length())));
        }
    }

    private static void fail(String reason) {
        System.out.println("AVF_TERMINAL_STREAM_CONTRACT_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
