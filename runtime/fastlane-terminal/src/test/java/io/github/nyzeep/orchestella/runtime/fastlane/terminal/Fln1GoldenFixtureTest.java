package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Byte-for-byte contract test against the single canonical
 * {@code protocol/fln1-golden.json}. The C++ payload test reads the same file.
 */
public final class Fln1GoldenFixtureTest {
    private static final int LOCAL_SESSION = 7;

    private static Map<String, GoldenFixture.Frame> frames;

    @BeforeClass
    public static void loadFixture() {
        frames = new LinkedHashMap<>();
        for (GoldenFixture.Frame frame : GoldenFixture.load()) {
            frames.put(frame.name(), frame);
        }
    }

    @Test
    public void fixtureCoversTheRequiredFrameSet() {
        List<String> required = List.of(
                "HELLO", "HELLO_OK", "OPEN_PTY", "PTY_OPENED", "PTY_DATA",
                "RESIZE", "RESIZED", "SIGNAL", "CLOSE", "CLOSED", "EOF", "EXIT",
                "WINDOW", "ERROR", "PING", "PONG");
        for (String name : required) {
            assertTrue("missing fixture frame " + name, frames.containsKey(name));
        }
    }

    @Test
    public void hostEncodesAndGuestDecodesTheGoldenWire() throws Exception {
        Fln1TerminalCodec host = new Fln1TerminalCodec(TerminalMux.Role.HOST);
        Fln1TerminalCodec guest = new Fln1TerminalCodec(TerminalMux.Role.GUEST);

        byte[] hello = host.encode(new TerminalFrame(
                TerminalFrameType.HELLO, 0,
                Fln1Payloads.encodeHello(new Fln1Payloads.Hello(1, 1, "test"))));
        assertBytes("HELLO", hello);
        assertEquals(TerminalFrameType.HELLO, guest.decode(hello).type());

        byte[] helloOk = guest.encode(new TerminalFrame(
                TerminalFrameType.HELLO, 0,
                Fln1Payloads.encodeHelloOk(new Fln1Payloads.HelloOk(
                        1, 0x3F, 65_536, 262_144))));
        assertBytes("HELLO_OK", helloOk);
        TerminalFrame decodedHello = host.decode(helloOk);
        assertEquals(TerminalFrameType.HELLO, decodedHello.type());

        byte[] open = host.encode(new TerminalFrame(
                TerminalFrameType.OPEN_PTY, LOCAL_SESSION,
                Fln1Payloads.encodeOpenPty(new Fln1Payloads.OpenPty(
                        120, 40, new String[] {"sh", "-c", "echo hi"}))));
        assertBytes("OPEN_PTY", open);
        assertEquals(0, wireSession(open));
        assertEquals(1, guest.decode(open).sessionId());

        byte[] opened = guest.encode(new TerminalFrame(
                TerminalFrameType.OPEN_PTY, 1, Fln1Payloads.encodePtyOpened(4242, 262_144)));
        assertBytes("PTY_OPENED", opened);
        assertEquals(LOCAL_SESSION, host.decode(opened).sessionId());

        assertBytes("PTY_DATA", host.encode(new TerminalFrame(
                TerminalFrameType.DATA, LOCAL_SESSION, "hello\n".getBytes("UTF-8"))));

        assertBytes("RESIZE", host.encode(new TerminalFrame(
                TerminalFrameType.RESIZE, LOCAL_SESSION,
                Fln1Payloads.encodeResize(new Fln1Payloads.Resize(132, 50)))));

        assertBytes("SIGNAL", host.encode(new TerminalFrame(
                TerminalFrameType.SIGNAL, LOCAL_SESSION, Fln1Payloads.encodeSignal(15))));

        assertBytes("CLOSE", host.encode(new TerminalFrame(
                TerminalFrameType.CLOSE, LOCAL_SESSION, Fln1Payloads.encodeClose(false))));

        assertBytes("WINDOW", host.encode(new TerminalFrame(
                TerminalFrameType.WINDOW, LOCAL_SESSION, Fln1Payloads.encodeWindow(65_536))));

        assertBytes("PING", host.encode(new TerminalFrame(
                TerminalFrameType.PING, 0, nonce(42))));
        assertBytes("PONG", guest.encode(new TerminalFrame(
                TerminalFrameType.PONG, 0, nonce(42))));

        assertBytes("RESIZED", guest.encode(new TerminalFrame(
                TerminalFrameType.RESIZED, 1, new byte[0])));
        assertBytes("CLOSED", guest.encode(new TerminalFrame(
                TerminalFrameType.CLOSED, 1, Fln1Payloads.encodeClosed(0))));
        assertBytes("EOF", guest.encode(new TerminalFrame(
                TerminalFrameType.EOF, 1, new byte[0])));
        assertBytes("EXIT", guest.encode(new TerminalFrame(
                TerminalFrameType.EXIT, 1, Fln1Payloads.encodeExit(
                        new Fln1Payloads.Exit(0, 0)))));
        assertBytes("ERROR", guest.encode(new TerminalFrame(
                TerminalFrameType.ERROR, 0,
                Fln1Payloads.encodeError(new Fln1Payloads.Error(3, "bad token")))));
    }

    @Test
    public void decoderAcceptsEveryGoldenFrame() throws Exception {
        Fln1TerminalCodec host = new Fln1TerminalCodec(TerminalMux.Role.HOST);
        Fln1TerminalCodec guest = new Fln1TerminalCodec(TerminalMux.Role.GUEST);
        host.encode(new TerminalFrame(
                TerminalFrameType.OPEN_PTY, LOCAL_SESSION,
                frame("OPEN_PTY").payload()));
        guest.decode(frame("OPEN_PTY").frame());
        host.decode(frame("HELLO_OK").frame());
        host.decode(frame("PTY_OPENED").frame());

        assertDecoded(guest, "HELLO", 0);
        assertDecoded(host, "HELLO_OK", 0);
        assertDecoded(host, "PING", 0);
        assertDecoded(guest, "PONG", 0);
        assertDecoded(host, "PTY_DATA", LOCAL_SESSION);
        assertDecoded(guest, "RESIZE", 1);
        assertDecoded(host, "RESIZED", LOCAL_SESSION);
        assertDecoded(guest, "SIGNAL", 1);
        assertDecoded(guest, "CLOSE", 1);
        assertDecoded(host, "CLOSED", LOCAL_SESSION);
        assertDecoded(host, "EOF", LOCAL_SESSION);
        assertDecoded(host, "EXIT", LOCAL_SESSION);
        assertDecoded(guest, "WINDOW", 1);
        assertDecoded(host, "ERROR", 0);
    }

    @Test
    public void streamReaderHandlesShortReadsOfEveryGoldenFrame() throws Exception {
        Fln1TerminalCodec host = new Fln1TerminalCodec(TerminalMux.Role.HOST);
        Fln1TerminalCodec guest = new Fln1TerminalCodec(TerminalMux.Role.GUEST);
        host.encode(new TerminalFrame(
                TerminalFrameType.OPEN_PTY, LOCAL_SESSION,
                frame("OPEN_PTY").payload()));
        guest.decode(frame("OPEN_PTY").frame());
        guest.decode(frame("HELLO").frame());
        TerminalFrame opened = host.read(shortReads(frame("PTY_OPENED").frame()));
        assertEquals(LOCAL_SESSION, opened.sessionId());
        TerminalFrame data = host.read(shortReads(frame("PTY_DATA").frame()));
        assertEquals(TerminalFrameType.DATA, data.type());
        assertArrayEquals(frame("PTY_DATA").payload(), data.payload());
    }

    private static void assertDecoded(
            Fln1TerminalCodec codec, String name, int expectedSession) throws Exception {
        GoldenFixture.Frame expected = frame(name);
        TerminalFrame decoded = codec.decode(expected.frame());
        assertEquals(expectedSession, decoded.sessionId());
        assertArrayEquals(expected.payload(), decoded.payload());
    }

    private static void assertBytes(String name, byte[] actual) {
        GoldenFixture.Frame expected = frame(name);
        assertEquals(name + " wire bytes", toHex(expected.frame()), toHex(actual));
        assertEquals(name + " payload bytes", toHex(expected.payload()),
                toHex(java.util.Arrays.copyOfRange(actual, 14, actual.length)));
    }

    private static GoldenFixture.Frame frame(String name) {
        GoldenFixture.Frame frame = frames.get(name);
        if (frame == null) {
            throw new IllegalStateException("fixture frame missing: " + name);
        }
        return frame;
    }

    private static int wireSession(byte[] frame) {
        return java.nio.ByteBuffer.wrap(frame, 6, 4).getInt();
    }

    private static ByteArrayInputStream shortReads(byte[] bytes) {
        return new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(length, 1));
            }
        };
    }

    private static byte[] nonce(long value) {
        return java.nio.ByteBuffer.allocate(8)
                .order(java.nio.ByteOrder.BIG_ENDIAN).putLong(value).array();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(Character.forDigit((value >> 4) & 0xF, 16));
            text.append(Character.forDigit(value & 0xF, 16));
        }
        return text.toString();
    }
}
