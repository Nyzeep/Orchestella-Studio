package io.github.nyzeep.orchestella.runtime.contract;

import io.github.nyzeep.orchestella.runtime.contract.display.DisplayViewportBudget;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Transport-neutral abstraction for the guest graphics output channel presented on the host
 * (decoded frames plus guest-bound input control). The interface name does not imply a wire
 * protocol: the current production data plane is RFB 3.8 CPU-copy (see ADR-0017).
 *
 * <p>Conforms to ADR-0002 and ADR-0017: decouples UX display rendering from whether the underlying
 * data channel is transported over UNIX LocalSockets (QEMU software mode) or vsock port 9103 (AVF hardware mode).
 */
public interface DisplayChannel extends AutoCloseable {
    /** Connection lifecycle states. */
    enum ConnectionState {
        CONNECTED,
        DISCONNECTED,
        ERROR
    }

    /**
     * Returns the current lifecycle state of this channel.
     *
     * @return current connection state
     */
    ConnectionState state();

    /**
     * Checks if the channel is currently in the {@link ConnectionState#CONNECTED} state.
     *
     * @return true if connected
     */
    default boolean isConnected() {
        return state() == ConnectionState.CONNECTED;
    }

    /**
     * Adds an observer for connection state transitions.
     *
     * @param listener state transition listener
     */
    void addStateListener(StateListener listener);

    /**
     * Removes a previously registered state listener.
     *
     * @param listener state transition listener
     */
    void removeStateListener(StateListener listener);

    /**
     * Registers a callback for receiving decoded video/compositor frame buffers.
     *
     * @param listener frame listener
     */
    void registerFrameListener(FrameListener listener);

    /**
     * Unregisters a frame callback.
     *
     * @param listener frame listener
     */
    void unregisterFrameListener(FrameListener listener);

    /**
     * Submits a strategy-neutral input event over the channel down to the guest Wayland compositor.
     *
     * @param event input event (pointer, touch, key, text commit)
     * @return true if accepted and queued/sent, false if disconnected or rejected
     */
    boolean sendInputEvent(InputEvent event);

    /**
     * Closes this channel and releases associated buffers and transport handles.
     */
    @Override
    void close();

    /** Observer for channel state transitions and error events. */
    @FunctionalInterface
    interface StateListener {
        void onStateChanged(ConnectionState newState, String message);
    }

    /** Callback for new frame buffers ready to be presented on the host SurfaceView. */
    @FunctionalInterface
    interface FrameListener {
        void onFrameAvailable(FrameData frame);
    }

    /**
     * Immutable container for pixel frame buffer data exchanged between guest compositor and host renderer.
     */
    final class FrameData {
        public static final int FORMAT_RGBA_8888 = 1;
        public static final int FORMAT_RGBX_8888 = 2;
        public static final int FORMAT_BGRA_8888 = 3;
        public static final int FORMAT_BGRX_8888 = 4;

        private final int width;
        private final int height;
        private final int stride;
        private final int format;
        private final byte[] data;
        private final long frameId;
        private final long timestampNs;

        public FrameData(
                int width,
                int height,
                int stride,
                int format,
                byte[] data,
                long frameId,
                long timestampNs) {
            this(width, height, stride, format, data, frameId, timestampNs, true);
        }

        private FrameData(
                int width,
                int height,
                int stride,
                int format,
                byte[] data,
                long frameId,
                long timestampNs,
                boolean cloneData) {
            if (width <= 0 || width > DisplayViewportBudget.MAX_EDGE
                    || height <= 0 || height > DisplayViewportBudget.MAX_EDGE
                    || (long) width * height > DisplayViewportBudget.MAX_PIXELS) {
                throw new IllegalArgumentException("frame dimensions exceed the display budget");
            }
            if (stride < (long) width * 4 || (stride & 3) != 0
                    || (long) stride * height > DisplayViewportBudget.MAX_BYTES) {
                throw new IllegalArgumentException("invalid frame stride or byte budget");
            }
            if (format < FORMAT_RGBA_8888 || format > FORMAT_BGRX_8888) {
                throw new IllegalArgumentException("unsupported frame format");
            }
            Objects.requireNonNull(data, "data");
            if (data.length != (long) stride * height) {
                throw new IllegalArgumentException("frame payload does not match stride and height");
            }
            this.width = width;
            this.height = height;
            this.stride = stride;
            this.format = format;
            this.data = cloneData ? data.clone() : data;
            this.frameId = frameId;
            this.timestampNs = timestampNs;
        }

        /**
         * Wraps an exclusively owned pixel buffer without defensive copying.
         *
         * <p>Callers MUST NOT mutate {@code ownedData} after passing it to this factory.
         */
        public static FrameData fromOwned(
                int width,
                int height,
                int stride,
                int format,
                byte[] ownedData,
                long frameId,
                long timestampNs) {
            return new FrameData(width, height, stride, format, ownedData, frameId, timestampNs, false);
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int stride() {
            return stride;
        }

        public int format() {
            return format;
        }

        public byte[] data() {
            return data.clone();
        }

        /** Returns an uncloned read-only {@link ByteBuffer} view over the internal pixel array. */
        public ByteBuffer asReadOnlyBuffer() {
            return ByteBuffer.wrap(data).asReadOnlyBuffer();
        }

        /**
         * Unpacks pixels into 32-bit ARGB integers ({@code 0xAARRGGBB}) without cloning {@code data}.
         */
        public void unpackArgbInto(int[] outPixels) {
            Objects.requireNonNull(outPixels, "outPixels");
            int requiredPixels = width * height;
            if (outPixels.length < requiredPixels) {
                throw new IllegalArgumentException(
                        "outPixels length " + outPixels.length + " < " + requiredPixels);
            }
            for (int y = 0; y < height; y++) {
                int rowStart = y * stride;
                int destRowStart = y * width;
                for (int x = 0; x < width; x++) {
                    int src = rowStart + (x * 4);
                    int dest = destRowStart + x;

                    int b0 = data[src] & 0xFF;
                    int b1 = data[src + 1] & 0xFF;
                    int b2 = data[src + 2] & 0xFF;
                    int b3 = data[src + 3] & 0xFF;

                    int argb;
                    switch (format) {
                        case FORMAT_RGBA_8888:
                            argb = (b3 << 24) | (b0 << 16) | (b1 << 8) | b2;
                            break;
                        case FORMAT_RGBX_8888:
                            argb = (0xFF << 24) | (b0 << 16) | (b1 << 8) | b2;
                            break;
                        case FORMAT_BGRA_8888:
                            argb = (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
                            break;
                        case FORMAT_BGRX_8888:
                            argb = (0xFF << 24) | (b2 << 16) | (b1 << 8) | b0;
                            break;
                        default:
                            argb = (b3 << 24) | (b0 << 16) | (b1 << 8) | b2;
                            break;
                    }
                    outPixels[dest] = argb;
                }
            }
        }

        public long frameId() {
            return frameId;
        }

        public long timestampNs() {
            return timestampNs;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof FrameData)) {
                return false;
            }
            FrameData that = (FrameData) o;
            return width == that.width
                    && height == that.height
                    && stride == that.stride
                    && format == that.format
                    && frameId == that.frameId
                    && timestampNs == that.timestampNs
                    && Arrays.equals(data, that.data);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(width, height, stride, format, frameId, timestampNs);
            result = 31 * result + Arrays.hashCode(data);
            return result;
        }

        @Override
        public String toString() {
            return "FrameData{"
                    + "width=" + width
                    + ", height=" + height
                    + ", stride=" + stride
                    + ", format=" + format
                    + ", frameId=" + frameId
                    + ", timestampNs=" + timestampNs
                    + ", bytes=" + data.length
                    + '}';
        }
    }
}
