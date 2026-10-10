package io.github.nyzeep.orchestella.terminal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

/**
 * Pure JVM unit test for PTY multiplexing frame protocol.
 * Validates frame encoding, decoding, corrupted frame rejection,
 * and zero cross-session bleed across multi-session data streams.
 */
public class PtyMultiplexerProtocolTest {

    @Test
    public void testDataFrameEncodeAndDecode() throws IOException {
        String sessionId = "term-1";
        String payloadText = "echo 'Hello Multi-Terminal'\n";
        byte[] payloadBytes = payloadText.getBytes(StandardCharsets.UTF_8);

        byte[] frameBytes = PtyMultiplexerProtocol.encodeDataFrame(sessionId, payloadBytes);
        assertNotNull("Frame bytes must not be null", frameBytes);
        assertTrue("Frame bytes length must be greater than header size", frameBytes.length > 7);

        PtyMultiplexerProtocol.PtyFrame frame = PtyMultiplexerProtocol.decodeFrame(frameBytes);
        assertEquals(PtyMultiplexerProtocol.FRAME_TYPE_DATA, frame.type());
        assertEquals(sessionId, frame.sessionId());
        assertArrayEquals(payloadBytes, frame.payload());
        assertEquals(payloadText, frame.payloadAsString());
    }

    @Test
    public void testResizeFrameEncodeAndDecode() throws IOException {
        String sessionId = "term-2";
        int columns = 120;
        int rows = 40;

        byte[] frameBytes = PtyMultiplexerProtocol.encodeResizeFrame(sessionId, columns, rows);
        assertNotNull(frameBytes);

        PtyMultiplexerProtocol.PtyFrame frame = PtyMultiplexerProtocol.decodeFrame(frameBytes);
        assertEquals(PtyMultiplexerProtocol.FRAME_TYPE_RESIZE, frame.type());
        assertEquals(sessionId, frame.sessionId());
        assertEquals(columns, frame.resizeColumns());
        assertEquals(rows, frame.resizeRows());
    }

    @Test
    public void testCloseFrameEncodeAndDecode() throws IOException {
        String sessionId = "term-3";

        byte[] frameBytes = PtyMultiplexerProtocol.encodeCloseFrame(sessionId);
        assertNotNull(frameBytes);

        PtyMultiplexerProtocol.PtyFrame frame = PtyMultiplexerProtocol.decodeFrame(frameBytes);
        assertEquals(PtyMultiplexerProtocol.FRAME_TYPE_CLOSE, frame.type());
        assertEquals(sessionId, frame.sessionId());
        assertEquals(0, frame.payload().length);
    }

    @Test
    public void testMultiFrameStreamWithoutCrossSessionBleed() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Interleave frames from three different sessions
        out.write(PtyMultiplexerProtocol.encodeDataFrame("term-A", "output-A1\n"));
        out.write(PtyMultiplexerProtocol.encodeDataFrame("term-B", "output-B1\n"));
        out.write(PtyMultiplexerProtocol.encodeResizeFrame("term-C", 80, 24));
        out.write(PtyMultiplexerProtocol.encodeDataFrame("term-A", "output-A2\n"));
        out.write(PtyMultiplexerProtocol.encodeCloseFrame("term-B"));

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        List<PtyMultiplexerProtocol.PtyFrame> frames = PtyMultiplexerProtocol.parseFrames(in);

        assertEquals(5, frames.size());

        assertEquals("term-A", frames.get(0).sessionId());
        assertEquals("output-A1\n", frames.get(0).payloadAsString());

        assertEquals("term-B", frames.get(1).sessionId());
        assertEquals("output-B1\n", frames.get(1).payloadAsString());

        assertEquals("term-C", frames.get(2).sessionId());
        assertEquals(PtyMultiplexerProtocol.FRAME_TYPE_RESIZE, frames.get(2).type());
        assertEquals(80, frames.get(2).resizeColumns());
        assertEquals(24, frames.get(2).resizeRows());

        assertEquals("term-A", frames.get(3).sessionId());
        assertEquals("output-A2\n", frames.get(3).payloadAsString());

        assertEquals("term-B", frames.get(4).sessionId());
        assertEquals(PtyMultiplexerProtocol.FRAME_TYPE_CLOSE, frames.get(4).type());
    }

    @Test
    public void testCorruptedMagicRejection() {
        byte[] validFrame = PtyMultiplexerProtocol.encodeCloseFrame("term-1");
        // Corrupt magic byte
        validFrame[0] = 0x00;

        try {
            PtyMultiplexerProtocol.decodeFrame(validFrame);
            fail("Expected IOException for invalid magic bytes");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("magic"));
        }
    }

    @Test
    public void testTruncatedFrameRejection() {
        byte[] validFrame = PtyMultiplexerProtocol.encodeDataFrame("term-1", "some text");
        byte[] truncated = new byte[validFrame.length - 3];
        System.arraycopy(validFrame, 0, truncated, 0, truncated.length);

        try {
            PtyMultiplexerProtocol.decodeFrame(truncated);
            fail("Expected IOException for truncated frame");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
