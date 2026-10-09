package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.AudioStreamContract;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Protocol and channel unit tests for the AVF vsock audio stream channel (port 9104).
 *
 * <p>Conforms to ADR-0013, ADR-0018, and Ticket 48:
 * <ul>
 *   <li>Solidifies dedicated vsock audio port ({@value AvfAudioVsockProtocol#AVF_AUDIO_VSOCK_PORT});</li>
 *   <li>Validates stream establishment, streaming PCM transfer, and channel close;</li>
 *   <li>Tests disconnection and adaptive reconnection;</li>
 *   <li>Tests malformed/unaligned PCM frame filtering;</li>
 *   <li>Tests backpressure buffering and zero-CPU silence standby sleep mechanism.</li>
 * </ul>
 */
public final class AvfAudioVsockProtocolTest {

    public static void main(String[] arguments) throws Exception {
        protocolConstantsAndFormatValidation();
        streamEstablishmentAndPcmIngestion();
        disconnectionAndAdaptiveReconnection();
        filtersMalformedAndUnalignedPcmFrames();
        backpressureBufferingMaintainsBoundedMemory();
        silenceStandbySleepTransitionsToZeroCpu();
        System.out.println(
                "AVF_AUDIO_VSOCK_PROTOCOL_TEST_OK format=1 stream_ingest=1 reconnect=1"
                        + " malformed_filter=1 backpressure=1 silence_standby=1");
    }

    private static void protocolConstantsAndFormatValidation() {
        assertEquals(9104, AvfAudioVsockProtocol.AVF_AUDIO_VSOCK_PORT, "vsock audio port is 9104");
        assertEquals(9104, AvfAudioVsockProtocol.DEFAULT_PORT, "default port is 9104");
        assertEquals(48000, AvfAudioVsockProtocol.SAMPLE_RATE_HZ, "sample rate 48000 Hz");
        assertEquals(16, AvfAudioVsockProtocol.BITS_PER_SAMPLE, "bit depth 16-bit");
        assertEquals(2, AvfAudioVsockProtocol.CHANNELS, "channels stereo (2)");
        assertEquals(4, AvfAudioVsockProtocol.FRAME_SIZE_BYTES, "frame size 4 bytes");
        assertEquals(192000, AvfAudioVsockProtocol.BYTES_PER_SECOND, "byte rate 192,000 B/s");

        assertTrue(AvfAudioVsockProtocol.isValidFrameAligned(0), "0 is aligned");
        assertTrue(AvfAudioVsockProtocol.isValidFrameAligned(4), "4 is aligned");
        assertTrue(AvfAudioVsockProtocol.isValidFrameAligned(4096), "4096 is aligned");
        assertTrue(!AvfAudioVsockProtocol.isValidFrameAligned(1), "1 is not aligned");
        assertTrue(!AvfAudioVsockProtocol.isValidFrameAligned(3), "3 is not aligned");
        assertTrue(!AvfAudioVsockProtocol.isValidFrameAligned(-4), "negative is not aligned");

        assertEquals(4096, AvfAudioVsockProtocol.alignToFrameBoundary(4098), "4098 aligned to 4096");
        assertEquals(4096, AvfAudioVsockProtocol.alignToFrameBoundary(4096), "4096 aligned to 4096");
        assertEquals(0, AvfAudioVsockProtocol.alignToFrameBoundary(3), "3 aligned to 0");
    }

    private static void streamEstablishmentAndPcmIngestion() throws Exception {
        byte[] samplePcm = new byte[4096];
        for (int i = 0; i < samplePcm.length; i += 4) {
            // Fill with non-zero 16-bit stereo sine-wave pattern
            short left = (short) (i * 10);
            short right = (short) (-i * 10);
            samplePcm[i] = (byte) (left & 0xFF);
            samplePcm[i + 1] = (byte) ((left >> 8) & 0xFF);
            samplePcm[i + 2] = (byte) (right & 0xFF);
            samplePcm[i + 3] = (byte) ((right >> 8) & 0xFF);
        }

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();

            Thread guestAudioServer = new Thread(() -> {
                try (Socket client = server.accept();
                     OutputStream out = client.getOutputStream()) {
                    // Stream 4 sample chunks = 16KB
                    for (int k = 0; k < 4; k++) {
                        out.write(samplePcm);
                        out.flush();
                        Thread.sleep(10);
                    }
                } catch (Exception ignored) {
                }
            }, "guest-pulse-server");
            guestAudioServer.setDaemon(true);
            guestAudioServer.start();

            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                AvfAudioChannel channel = new AvfAudioChannel(socket.getInputStream(), socket.getOutputStream());
                assertEquals(AudioStreamContract.State.IDLE, channel.state(), "initial state IDLE");

                channel.start();
                assertEquals(AudioStreamContract.State.RUNNING, channel.state(), "state RUNNING after start");
                assertTrue(channel.isConnected(), "channel is connected");
                assertEquals(48000, channel.format().sampleRateHz(), "format 48kHz");

                InputStream pcmStream = channel.openStream();
                byte[] readBuf = new byte[4096];
                int totalRead = 0;

                while (totalRead < samplePcm.length * 4) {
                    int n = pcmStream.read(readBuf);
                    if (n < 0) {
                        break;
                    }
                    totalRead += n;
                }

                assertEquals(samplePcm.length * 4, totalRead, "all 16,384 bytes ingested through pipeline");
                channel.stop();
                assertEquals(AudioStreamContract.State.STOPPED, channel.state(), "state STOPPED after stop");
                channel.close();
            }
        }
    }

    private static void disconnectionAndAdaptiveReconnection() throws Exception {
        AtomicInteger connectionCount = new AtomicInteger(0);

        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Socket client = server.accept();
                        connectionCount.incrementAndGet();
                        OutputStream out = client.getOutputStream();
                        // Write 1KB then immediately close to trigger disconnect
                        byte[] chunk = new byte[1024];
                        out.write(chunk);
                        out.flush();
                        client.close();
                    }
                } catch (Exception ignored) {
                }
            }, "disconnect-reconnect-server");
            serverThread.setDaemon(true);
            serverThread.start();

            AvfAudioChannel channel = new AvfAudioChannel(
                    () -> new Socket(InetAddress.getLoopbackAddress(), port),
                    AvfAudioVsockProtocol.AVF_AUDIO_VSOCK_PORT);
            channel.setAutoReconnect(true);
            channel.start();

            InputStream stream = channel.openStream();
            byte[] buf = new byte[512];
            int read1 = stream.read(buf);
            assertTrue(read1 > 0, "first read succeeded");

            // Allow reconnect loop to execute
            Thread.sleep(400);

            int read2 = stream.read(buf);
            assertTrue(read2 > 0, "read succeeded after adaptive reconnection");
            assertTrue(connectionCount.get() >= 2, "server received multiple connection cycles");

            channel.close();
        }
    }

    private static void filtersMalformedAndUnalignedPcmFrames() {
        // 1. PCM data with unaligned byte count (e.g. 1007 bytes instead of multiple of 4)
        byte[] raw = new byte[1007];
        Arrays.fill(raw, (byte) 0x22);

        int validLength = AvfAudioVsockProtocol.filterMalformedBytes(raw, raw.length);
        assertEquals(1004, validLength, "filtered length drops 3 trailing unaligned bytes");
        assertTrue(validLength % 4 == 0, "filtered length is frame aligned");

        // 2. Odd length buffers
        assertEquals(0, AvfAudioVsockProtocol.filterMalformedBytes(raw, 3), "3 bytes filtered to 0");
        assertEquals(4, AvfAudioVsockProtocol.filterMalformedBytes(raw, 5), "5 bytes filtered to 4");
        assertEquals(0, AvfAudioVsockProtocol.filterMalformedBytes(raw, -1), "-1 bytes filtered to 0");

        // 3. Channel stream handling of unaligned source chunks
        ByteArrayInputStream in = new ByteArrayInputStream(raw); // 1007 bytes
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AvfAudioChannel channel = new AvfAudioChannel(in, out);
        channel.start();

        InputStream stream = channel.openStream();
        byte[] dest = new byte[2048];
        int bytesRead = 0;
        try {
            int n;
            while ((n = stream.read(dest, bytesRead, dest.length - bytesRead)) > 0) {
                bytesRead += n;
            }
        } catch (IOException ignored) {
        }

        assertEquals(1004, bytesRead, "channel pipeline delivered exactly 1004 frame-aligned bytes");
        channel.close();
    }

    private static void backpressureBufferingMaintainsBoundedMemory() throws Exception {
        // Producer produces 300KB into a 64KB buffer without consumer reading
        int testDataSize = 300 * 1024;
        byte[] largeData = new byte[testDataSize];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i & 0x7F);
        }

        ByteArrayInputStream in = new ByteArrayInputStream(largeData);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        AvfAudioChannel channel = new AvfAudioChannel(in, out);
        channel.setBufferCapacity(64 * 1024);
        channel.start();

        // Let the internal pump fill the buffer and hit backpressure
        Thread.sleep(250);

        assertTrue(channel.bufferedBytes() <= 64 * 1024, "buffer does not exceed capacity (64KB)");
        assertTrue(channel.backpressureDropCount() > 0, "backpressure drops were recorded to protect memory");

        // Consumer reads available data
        InputStream stream = channel.openStream();
        byte[] readBuf = new byte[32 * 1024];
        int read = stream.read(readBuf);
        assertTrue(read > 0, "consumer reads from bounded buffer successfully");

        channel.close();
    }

    private static void silenceStandbySleepTransitionsToZeroCpu() throws Exception {
        // 1. Pure silence detection
        byte[] silenceChunk = new byte[4096]; // all zeroes
        assertTrue(AvfAudioVsockProtocol.isSilence(silenceChunk), "zeroed PCM is detected as silence");
        assertEquals(1024, AvfAudioVsockProtocol.countSilentFrames(silenceChunk, 0, silenceChunk.length), "1024 silent frames");

        byte[] soundChunk = new byte[4096];
        soundChunk[100] = 0x12; // non-zero sample
        assertTrue(!AvfAudioVsockProtocol.isSilence(soundChunk), "non-zero PCM is not silence");

        // 2. Channel silence standby state transition
        ByteArrayInputStream in = new ByteArrayInputStream(silenceChunk);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        AvfAudioChannel channel = new AvfAudioChannel(in, out);
        channel.setSilenceStandbyThresholdFrames(500); // 500 frames threshold
        channel.start();

        Thread.sleep(150);

        assertTrue(channel.isStandby(), "channel transitioned into standby sleep after consecutive silence");
        assertTrue(channel.silenceFramesDetected() >= 500, "silence frames detected count >= threshold");

        // 3. Audio resumption clears standby
        channel.feedPcmChunk(soundChunk);
        assertTrue(!channel.isStandby(), "channel immediately woke from standby sleep upon audio arrival");

        channel.close();
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
