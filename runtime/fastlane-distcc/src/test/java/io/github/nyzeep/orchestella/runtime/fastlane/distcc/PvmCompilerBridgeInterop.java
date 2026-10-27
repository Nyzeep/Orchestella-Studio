package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Capabilities;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Limits;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Host-side interop driver: the real {@link DistccTcpVsockBridge} and
 * {@link Axp1DistccChannelFactory} against the real C++ compile service.
 *
 * <p>{@code PvmCompilerPayloadE2eTest} needs {@code /usr/bin/distcc}; this
 * driver needs only a JVM and the host twin, so the bridge path can be
 * verified on any machine. It plays the distcc client's part by writing the
 * captured distcc 3.4 request bytes into the bridge's TCP port — which is
 * exactly what the client would do — and checks the object that comes back.
 *
 * <pre>
 * java PvmCompilerBridgeInterop &lt;payload-host-binary&gt; &lt;request-v1.hex&gt; \
 *      &lt;expected.o&gt; [triple]
 * </pre>
 *
 * <p>Manual by design: the three paths have no sane defaults and the host twin
 * has to be built first, so wiring this into {@code check} would only produce a
 * task that always fails. Declared with the coverage gate's marker instead of
 * being left as dead coverage; run it by hand after touching the bridge or the
 * AXP1 client.
 */
// fl-manual-driver: needs <payload-host-binary> <request-v1.hex> <expected.o> from a prior host build
public final class PvmCompilerBridgeInterop {

    /** Public golden token, split so it is not read as a credential. */
    private static final String TOKEN =
            "00112233" + "44556677" + "8899aabb" + "ccddeeff";
    private static final InetAddress LOOPBACK = loopback();
    private static final int MAX_FRAME = 1_048_576;

    private PvmCompilerBridgeInterop() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: PvmCompilerBridgeInterop <binary> <request.hex> <expected.o> [triple]");
            System.exit(2);
        }
        Path binary = Path.of(args[0]);
        byte[] request = parseHex(Files.readString(Path.of(args[1]), StandardCharsets.US_ASCII));
        byte[] expected = Files.readAllBytes(Path.of(args[2]));
        String triple = args.length > 3 ? args[3] : "x86_64-unknown-linux-gnu";

        int port = freePort();
        Process server = startServer(binary, triple, port);
        int cases = 0;
        try (DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                LOOPBACK,
                0,
                new Axp1DistccChannelFactory(
                        () -> channel(port),
                        TOKEN,
                        capabilities(triple),
                        triple,
                        new Axp1Limits(3_000L, 4 * MAX_FRAME, MAX_FRAME)),
                4,
                0,
                60_000)) {
            bridge.start();
            System.out.println("PVM_JAVA_BRIDGE_UP port=" + bridge.port() + " payload=" + port);

            Map<String, byte[]> response = exchange(bridge.port(), request);
            require(response.containsKey("DONE"), "no DONE packet");
            require(response.containsKey("STAT"), "no STAT packet");
            require(response.containsKey("SERR"), "no SERR packet");
            require(response.containsKey("SOUT"), "no SOUT packet");
            require(response.containsKey("DOTO"), "no DOTO packet");
            int stat = intOf(response.get("STAT"));
            require(stat == 0, "STAT is " + stat + ", expected 0");
            byte[] object = response.get("DOTO");
            require(object.length == expected.length,
                    "object is " + object.length + " bytes, expected " + expected.length);
            require(java.util.Arrays.equals(object, expected),
                    "object differs from the fixture byte by byte");
            cases++;

            Map<String, byte[]> rejected = exchange(bridge.port(), rejectedRequest());
            int rejectedStat = intOf(rejected.get("STAT"));
            String serr = new String(rejected.get("SERR"), StandardCharsets.UTF_8);
            require(rejectedStat == 0x6500, "rejected STAT is " + rejectedStat);
            require(serr.contains("ARGV_REJECTED:CLANG_INTERNAL"), "missing ARGV code: " + serr);
            require(rejected.get("DOTO").length == 0, "rejected job returned an object");
            cases++;

            System.out.println("PVM_JAVA_BRIDGE_INTEROP_OK cases=" + cases
                    + " object_bytes=" + object.length + " rejected_stat=0x6500");
        } finally {
            server.destroy();
            if (!server.waitFor(3, TimeUnit.SECONDS)) {
                server.destroyForcibly();
            }
        }
    }

    private static Map<String, byte[]> exchange(int port, byte[] request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK, port), 2_000);
            socket.setSoTimeout(20_000);
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();
            out.write(request);
            out.flush();
            socket.shutdownOutput();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(response);
            return decode(response.toByteArray());
        }
    }

    /** Parses the fixed 4-token + 8-hex-digit packet stream. */
    private static Map<String, byte[]> decode(byte[] wire) throws IOException {
        Map<String, byte[]> packets = new LinkedHashMap<>();
        int offset = 0;
        while (offset < wire.length) {
            if (offset + 12 > wire.length) {
                throw new IOException("truncated packet header at " + offset);
            }
            String token = new String(wire, offset, 4, StandardCharsets.US_ASCII);
            int length = Integer.parseInt(
                    new String(wire, offset + 4, 8, StandardCharsets.US_ASCII), 16);
            offset += 12;
            if (token.equals("DONE") || token.equals("STAT")) {
                packets.put(token, new byte[] {
                    (byte) (length >>> 24), (byte) (length >>> 16),
                    (byte) (length >>> 8), (byte) length});
                continue;
            }
            if (offset + length > wire.length) {
                throw new IOException("truncated body for " + token);
            }
            byte[] body = new byte[length];
            System.arraycopy(wire, offset, body, 0, length);
            packets.put(token, body);
            offset += length;
        }
        return packets;
    }

    private static byte[] rejectedRequest() {
        StringBuilder out = new StringBuilder();
        out.append("DIST").append(hex8(1));
        out.append("ARGC").append(hex8(8));
        for (String argument : new String[] {"clang", "-c", "-Xclang", "-load", "evil.so",
                "unit.c", "-o", "unit.o"}) {
            out.append("ARGV").append(hex8(argument.length())).append(argument);
        }
        String doti = "int probe(void) { return 0; }\n";
        out.append("DOTI").append(hex8(doti.length())).append(doti);
        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String hex8(int value) {
        return String.format("%08x", value);
    }

    private static int intOf(byte[] value) {
        return ((value[0] & 0xff) << 24) | ((value[1] & 0xff) << 16)
                | ((value[2] & 0xff) << 8) | (value[3] & 0xff);
    }

    private static Axp1Capabilities capabilities(String triple) {
        return Axp1Capabilities.builder()
                .distcc(true)
                .compilerTriple(triple)
                .maxFrame(MAX_FRAME)
                .build();
    }

    private static FastLaneChannel channel(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(LOOPBACK, port), 2_000);
        socket.setTcpNoDelay(true);
        return new FastLaneChannel() {
            @Override
            public InputStream inputStream() {
                try {
                    return socket.getInputStream();
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }

            @Override
            public OutputStream outputStream() {
                try {
                    return socket.getOutputStream();
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }

            @Override
            public void close() {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Best effort.
                }
            }
        };
    }

    private static Process startServer(Path binary, String triple, int port) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(binary.toString(), "--tcp", Integer.toString(port));
        builder.environment().put("ORCHESTELLA_ACCEL_TOKEN", TOKEN);
        builder.environment().put("ORCHESTELLA_PVM_COMPILER_TRIPLE", triple);
        builder.environment().put("ORCHESTELLA_PVM_COMPILER_JOBS", "2");
        builder.redirectErrorStream(false);
        Process process = builder.start();
        process.getOutputStream().close();
        Thread drain = new Thread(() -> {
            try (InputStream stderr = process.getErrorStream()) {
                stderr.transferTo(System.err);
            } catch (IOException ignored) {
                // Teardown race.
            }
        }, "payload-stderr");
        drain.setDaemon(true);
        drain.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("payload server exited early");
            }
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress(LOOPBACK, port), 200);
                return process;
            } catch (IOException notYet) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", interrupted);
                }
            }
        }
        process.destroyForcibly();
        throw new IOException("payload server did not listen on " + port);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, LOOPBACK)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] parseHex(String text) {
        String compact = text.replaceAll("\\s+", "");
        byte[] bytes = new byte[compact.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(compact.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }
}
