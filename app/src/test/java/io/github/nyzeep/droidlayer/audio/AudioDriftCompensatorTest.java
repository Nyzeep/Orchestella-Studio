package io.github.nyzeep.droidlayer.audio;

import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM unit tests for {@link AudioDriftCompensator}.
 *
 * <p>Validates watermark tracking, target latency window (40ms ~ 80ms),
 * high watermark micro-drop sampling (>120ms), and underrun smooth padding.
 */
public class AudioDriftCompensatorTest {

    private static final int BYTES_PER_SECOND = 192000; // 48kHz, 16-bit, stereo
    private static final int BYTES_PER_FRAME = 4;
    private AudioDriftCompensator compensator;

    @Before
    public void setUp() {
        compensator = new AudioDriftCompensator();
    }

    @Test
    public void testInitialStateIsEmpty() {
        assertEquals(0, compensator.getAvailableBytes());
        assertEquals(0.0, compensator.getWatermarkMs(), 0.001);
        assertEquals(0, compensator.getDroppedFramesCount());
        assertEquals(0, compensator.getUnderrunCount());
        assertEquals(0, compensator.getPaddedFramesCount());
    }

    @Test
    public void testNormalThroughputWithinTargetWindow() {
        // Write 60ms of audio (target window is 40ms ~ 80ms)
        // 60ms * 192 bytes/ms = 11,520 bytes
        int bytes60ms = (int) AudioDriftCompensator.durationMsToBytes(60.0);
        byte[] pcmData = generateSinePcm(bytes60ms, 440.0f);

        int written = compensator.write(pcmData, 0, pcmData.length);
        assertEquals(bytes60ms, written);
        assertEquals(60.0, compensator.getWatermarkMs(), 0.1);
        assertTrue(compensator.isWithinTargetWindow());

        // Read out in 20ms chunks (3840 bytes)
        int bytes20ms = (int) AudioDriftCompensator.durationMsToBytes(20.0);
        byte[] readBuffer = new byte[bytes20ms];

        int read = compensator.read(readBuffer, 0, bytes20ms);
        assertEquals(bytes20ms, read);
        assertEquals(40.0, compensator.getWatermarkMs(), 0.1);
        assertTrue(compensator.isWithinTargetWindow());

        read = compensator.read(readBuffer, 0, bytes20ms);
        assertEquals(bytes20ms, read);
        assertEquals(20.0, compensator.getWatermarkMs(), 0.1);
        assertFalse(compensator.isWithinTargetWindow()); // below 40ms

        assertEquals(0, compensator.getDroppedFramesCount());
        assertEquals(0, compensator.getUnderrunCount());
    }

    @Test
    public void testFastClockHighWatermarkTriggerMicroDrop() {
        // High watermark threshold is 120ms (23,040 bytes)
        // Write 140ms of audio (26,880 bytes) repeatedly to simulate sustained fast producer
        int bytes140ms = (int) AudioDriftCompensator.durationMsToBytes(140.0);
        byte[] pcmData = generateSinePcm(bytes140ms, 440.0f);

        compensator.write(pcmData, 0, pcmData.length);
        assertTrue("Watermark should exceed 120ms", compensator.getWatermarkMs() > 120.0);

        // Sustained fast producer writes again without consumer reading enough
        compensator.write(pcmData, 0, 4096);
        compensator.write(pcmData, 4096, 4096);

        // Micro-drop should have triggered
        assertTrue("Micro-drop should have dropped frames", compensator.getDroppedFramesCount() > 0);
        // Drops should be aligned with frame size (4 bytes)
        long droppedFrames = compensator.getDroppedFramesCount();
        assertTrue("Dropped frames should be >= 128", droppedFrames >= 128);
    }

    @Test
    public void testSlowClockUnderrunSmoothPadding() {
        // Producer is dry; consumer asks for 40ms of audio (7680 bytes)
        int requestedBytes = (int) AudioDriftCompensator.durationMsToBytes(40.0);
        byte[] readBuffer = new byte[requestedBytes];

        int read = compensator.read(readBuffer, 0, requestedBytes);
        // Compensator pads with silence to prevent buffer underrun pops
        assertEquals(requestedBytes, read);
        assertTrue(compensator.getUnderrunCount() > 0);
        assertTrue(compensator.getPaddedFramesCount() > 0);

        // Validate that padded data is smooth silence (all zeros or smoothly ramped to 0)
        ByteBuffer buf = ByteBuffer.wrap(readBuffer).order(ByteOrder.LITTLE_ENDIAN);
        short lastSample = 0;
        while (buf.hasRemaining()) {
            lastSample = buf.getShort();
        }
        assertEquals(0, lastSample);
    }

    @Test
    public void testPartialUnderrunSmoothTransition() {
        // Write 10ms of audio (1920 bytes)
        int bytes10ms = (int) AudioDriftCompensator.durationMsToBytes(10.0);
        byte[] pcmData = generateSinePcm(bytes10ms, 440.0f);
        compensator.write(pcmData, 0, pcmData.length);

        // Read 30ms of audio (5760 bytes) -> 10ms real audio + 20ms padded silence
        int bytes30ms = (int) AudioDriftCompensator.durationMsToBytes(30.0);
        byte[] readBuffer = new byte[bytes30ms];

        int read = compensator.read(readBuffer, 0, bytes30ms);
        assertEquals(bytes30ms, read);
        assertEquals(1, compensator.getUnderrunCount());
        assertTrue(compensator.getPaddedFramesCount() > 0);
    }

    @Test
    public void testJitterBurstHandlingAndRecovery() {
        // Burst 1: 100ms
        int bytes100ms = (int) AudioDriftCompensator.durationMsToBytes(100.0);
        byte[] pcmBurst = generateSinePcm(bytes100ms, 440.0f);
        compensator.write(pcmBurst, 0, pcmBurst.length);

        // Consumer reads 40ms
        int bytes40ms = (int) AudioDriftCompensator.durationMsToBytes(40.0);
        byte[] readBuffer = new byte[bytes40ms];
        compensator.read(readBuffer, 0, bytes40ms);

        // Watermark should be 60ms (within 40ms ~ 80ms)
        assertEquals(60.0, compensator.getWatermarkMs(), 0.5);
        assertTrue(compensator.isWithinTargetWindow());
    }

    @Test
    public void testResetClearsAllBuffersAndCounters() {
        byte[] data = generateSinePcm(4096, 440.0f);
        compensator.write(data, 0, data.length);
        assertTrue(compensator.getAvailableBytes() > 0);

        compensator.reset();
        assertEquals(0, compensator.getAvailableBytes());
        assertEquals(0.0, compensator.getWatermarkMs(), 0.001);
        assertEquals(0, compensator.getDroppedFramesCount());
        assertEquals(0, compensator.getUnderrunCount());
        assertEquals(0, compensator.getPaddedFramesCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWriteUnalignedThrows() {
        byte[] unaligned = new byte[5]; // not multiple of 4 bytes
        compensator.write(unaligned, 0, unaligned.length);
    }

    private static byte[] generateSinePcm(int numBytes, float frequencyHz) {
        ByteBuffer buffer = ByteBuffer.allocate(numBytes).order(ByteOrder.LITTLE_ENDIAN);
        int totalFrames = numBytes / BYTES_PER_FRAME;
        for (int i = 0; i < totalFrames; i++) {
            double angle = 2.0 * Math.PI * frequencyHz * i / 48000.0;
            short sample = (short) (Math.sin(angle) * 16000);
            buffer.putShort(sample); // Left
            buffer.putShort(sample); // Right
        }
        return buffer.array();
    }
}
