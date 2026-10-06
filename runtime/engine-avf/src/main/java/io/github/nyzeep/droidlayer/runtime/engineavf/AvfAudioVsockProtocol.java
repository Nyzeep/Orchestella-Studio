package io.github.nyzeep.droidlayer.runtime.engineavf;

import io.github.nyzeep.droidlayer.runtime.contract.AudioStreamContract;
import java.util.Objects;

/**
 * Protocol codec, constants, and PCM frame validation utilities for the AVF vsock
 * audio channel (port 9104).
 *
 * <p>Conforms to ADR-0013, ADR-0018, and Ticket 48:
 * <ul>
 *   <li>Solidifies dedicated vsock audio port ({@value #AVF_AUDIO_VSOCK_PORT});</li>
 *   <li>Enforces fixed 48,000 Hz 16-bit signed little-endian stereo PCM format;</li>
 *   <li>Defends against malformed, unaligned, or corrupted audio chunks;</li>
 *   <li>Provides zero-CPU silence detection and standby classification;</li>
 *   <li>Defines backpressure limits and bounded memory thresholds.</li>
 * </ul>
 */
public final class AvfAudioVsockProtocol {
    public static final int AVF_AUDIO_VSOCK_PORT = 9104;
    public static final int DEFAULT_PORT = AVF_AUDIO_VSOCK_PORT;

    public static final int SAMPLE_RATE_HZ = AudioStreamContract.DEFAULT_SAMPLE_RATE_HZ; // 48000
    public static final int BITS_PER_SAMPLE = AudioStreamContract.DEFAULT_BITS_PER_SAMPLE; // 16
    public static final int CHANNELS = AudioStreamContract.DEFAULT_CHANNELS; // 2
    public static final int BYTES_PER_SAMPLE = AudioStreamContract.DEFAULT_BYTES_PER_SAMPLE; // 2
    public static final int FRAME_SIZE_BYTES = AudioStreamContract.DEFAULT_FRAME_SIZE_BYTES; // 4
    public static final int BYTES_PER_SECOND = AudioStreamContract.DEFAULT_BYTES_PER_SECOND; // 192000

    public static final int DEFAULT_CHUNK_SIZE = 4096; // 1024 frames = ~21.3ms
    public static final int DEFAULT_BUFFER_CAPACITY = 64 * 1024; // 16,384 frames = ~341ms
    public static final int MAX_BACKPRESSURE_BUFFER = 256 * 1024; // 65,536 frames = ~1.36s

    public static final int SILENCE_THRESHOLD_FRAMES = 24000; // 500ms silence threshold
    public static final int SILENCE_AMPLITUDE_TOLERANCE = 0; // exact PCM zero

    private AvfAudioVsockProtocol() {}

    /**
     * Checks whether a byte length aligns precisely with 4-byte stereo 16-bit PCM frames.
     */
    public static boolean isValidFrameAligned(int byteLength) {
        return byteLength >= 0 && (byteLength % FRAME_SIZE_BYTES == 0);
    }

    /**
     * Truncates byte length down to the nearest complete 4-byte frame boundary.
     */
    public static int alignToFrameBoundary(int byteLength) {
        if (byteLength <= 0) {
            return 0;
        }
        return (byteLength / FRAME_SIZE_BYTES) * FRAME_SIZE_BYTES;
    }

    /**
     * Filters malformed or unaligned PCM byte buffers by dropping trailing unaligned bytes.
     *
     * @param buffer byte buffer containing PCM data
     * @param length number of bytes in buffer to validate
     * @return frame-aligned byte count
     */
    public static int filterMalformedBytes(byte[] buffer, int length) {
        if (buffer == null || length <= 0) {
            return 0;
        }
        int effective = Math.min(buffer.length, length);
        return alignToFrameBoundary(effective);
    }

    /**
     * Determines whether a segment of 16-bit PCM samples contains pure silence (all zeros).
     */
    public static boolean isSilence(byte[] pcmData, int offset, int length) {
        if (pcmData == null || length <= 0 || offset < 0 || offset + length > pcmData.length) {
            return true;
        }
        int alignedLength = alignToFrameBoundary(length);
        for (int i = offset; i < offset + alignedLength; i += 2) {
            short sample = (short) ((pcmData[i] & 0xFF) | (pcmData[i + 1] << 8));
            if (Math.abs(sample) > SILENCE_AMPLITUDE_TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Determines whether the entire byte array contains pure silence.
     */
    public static boolean isSilence(byte[] pcmData) {
        if (pcmData == null || pcmData.length == 0) {
            return true;
        }
        return isSilence(pcmData, 0, pcmData.length);
    }

    /**
     * Computes the number of consecutive silent 4-byte stereo frames in a PCM segment.
     */
    public static int countSilentFrames(byte[] pcmData, int offset, int length) {
        if (pcmData == null || length <= 0 || offset < 0 || offset + length > pcmData.length) {
            return 0;
        }
        int alignedLength = alignToFrameBoundary(length);
        int frameCount = 0;
        for (int i = offset; i < offset + alignedLength; i += FRAME_SIZE_BYTES) {
            short left = (short) ((pcmData[i] & 0xFF) | (pcmData[i + 1] << 8));
            short right = (short) ((pcmData[i + 2] & 0xFF) | (pcmData[i + 3] << 8));
            if (Math.abs(left) <= SILENCE_AMPLITUDE_TOLERANCE && Math.abs(right) <= SILENCE_AMPLITUDE_TOLERANCE) {
                frameCount++;
            } else {
                break;
            }
        }
        return frameCount;
    }

    public static boolean isValidSampleRate(int sampleRateHz) {
        return sampleRateHz == SAMPLE_RATE_HZ;
    }

    public static boolean isValidChannels(int channels) {
        return channels == CHANNELS;
    }
}
