package io.github.nyzeep.droidlayer.runtime.contract;

import java.util.Arrays;
import java.util.Objects;

/**
 * Transport-neutral abstraction for a Wayland graphics and control channel.
 *
 * <p>Conforms to ADR-0002 and ADR-0017: decouples UX display rendering from whether the underlying
 * data channel is transported over UNIX LocalSockets (QEMU software mode) or vsock port 9103 (AVF hardware mode).
 */
public interface WaylandChannel extends AutoCloseable {
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
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("dimensions must be positive: " + width + "x" + height);
            }
            if (stride < width * 4) {
                throw new IllegalArgumentException("stride cannot be less than width * 4: " + stride);
            }
            this.width = width;
            this.height = height;
            this.stride = stride;
            this.format = format;
            this.data = Objects.requireNonNull(data, "data").clone();
            this.frameId = frameId;
            this.timestampNs = timestampNs;
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
