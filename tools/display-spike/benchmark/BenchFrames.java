package io.github.nyzeep.orchestella.spike.w405bench;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.github.nyzeep.orchestella.DesktopPresentationGate;
import io.github.nyzeep.orchestella.DoubleBufferedRenderer;
import io.github.nyzeep.orchestella.ViewportAspectCalculator;
import io.github.nyzeep.orchestella.runtime.contract.DisplayChannel.FrameData;

final class BenchFrames {
    static final int WIDTH = 960;
    static final int HEIGHT = 540;
    private static final int STRIDE = WIDTH * 4;
    private static final int HEADER_BYTES = 64;
    private static final int SLOT_BYTES = 4 * 1024 * 1024;
    static final int BAR_BYTES = 2 * SLOT_BYTES;
    private final DoubleBufferedRenderer.SurfacePresenter presenter;
    private final ViewportAspectCalculator.ViewportMapping viewport;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final DesktopPresentationGate gate = new DesktopPresentationGate();
    private final DoubleBufferedRenderer rfbRenderer;
    private final CountDownLatch initialPacket = new CountDownLatch(1);
    private final AtomicLong firstSwapNs = new AtomicLong();
    private final AtomicLong presentedCount = new AtomicLong();
    private final AtomicLong invalidCount = new AtomicLong();
    private final AtomicLong sharedFrames = new AtomicLong();
    private final byte[] generation;
    private volatile long baselinePixels;
    private volatile long startNs = Long.MAX_VALUE;
    private volatile long endNs = Long.MIN_VALUE;
    private volatile boolean rfbFailed;

    BenchFrames(DoubleBufferedRenderer.SurfacePresenter presenter, int width, int height, byte[] generation) {
        this.presenter = presenter;
        this.generation = generation.clone();
        viewport = ViewportAspectCalculator.calculate(WIDTH, HEIGHT, width, height);
        rfbRenderer = new DoubleBufferedRenderer(presenter, callback -> ui.post(
                () -> Choreographer.getInstance().postFrameCallback(time -> callback.run())));
        rfbRenderer.setViewportSize(width, height);
        rfbRenderer.setPresentationObserver(frame -> recordSwap(
                frame.asReadOnlyBuffer(), frame.stride(), frame.timestampNs()));
    }

    void acceptRfb(FrameData frame) {
        if (frame.width() != WIDTH || frame.height() != HEIGHT
                || frame.format() != FrameData.FORMAT_RGBA_8888) {
            invalidCount.incrementAndGet();
            return;
        }
        initial(frame.asReadOnlyBuffer(), frame.stride());
        rfbRenderer.onFrameAvailable(frame);
    }

    void markRfbFailure() {
        rfbFailed = true;
        initialPacket.countDown();
    }

    void awaitInitial() throws Exception {
        if (!initialPacket.await(35, TimeUnit.SECONDS) || rfbFailed) {
            throw new java.io.IOException("initial-frame");
        }
    }

    void start(String generationHex, long start, long end) {
        gate.observe(generationHex, generationHex, WIDTH, HEIGHT, start);
        endNs = end;
        startNs = start;
    }

    int acceptShared(ByteBuffer mapping, int seq) throws Exception {
        int slot = ((seq - 1) & 1) * SLOT_BYTES;
        ByteBuffer header = mapping.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        if (header.getInt(slot) != 0x35303457 || header.getInt(slot + 4) != 1
                || header.getInt(slot + 8) != seq
                || header.getInt(slot + 12) != WIDTH || header.getInt(slot + 16) != HEIGHT
                || header.getInt(slot + 20) != STRIDE || header.getInt(slot + 24) != 1
                || (header.getInt(slot + 28) & ~1) != 0
                || header.getLong(slot + 48) != 0 || header.getLong(slot + 56) != 0) {
            throw new java.io.IOException("frame-header");
        }
        byte[] packetGeneration = new byte[16];
        header.position(slot + 32);
        header.get(packetGeneration);
        if (!Arrays.equals(generation, packetGeneration)) throw new java.io.IOException("frame-generation");
        ByteBuffer stable = mapping.asReadOnlyBuffer();
        stable.position(slot + HEADER_BYTES);
        stable.limit(slot + HEADER_BYTES + STRIDE * HEIGHT);
        ByteBuffer pixels = stable.slice();
        long timestamp = System.nanoTime();
        initial(pixels, STRIDE);
        if (timestamp >= startNs && timestamp <= endNs) {
            sharedFrames.incrementAndGet();
        }
        DesktopPresentationGate.Confirmation confirmation = null;
        if (timestamp >= startNs && firstSwapNs.get() == 0 && fingerprint(pixels, STRIDE) != baselinePixels) {
            byte[] firstPixels = new byte[STRIDE * HEIGHT];
            pixels.duplicate().get(firstPixels);
            confirmation = gate.begin(FrameData.fromOwned(WIDTH, HEIGHT, STRIDE,
                    FrameData.FORMAT_RGBA_8888, firstPixels, seq, timestamp));
            if (confirmation == null) return seq;
        }
        CountDownLatch vsync = new CountDownLatch(1);
        ui.post(() -> Choreographer.getInstance().postFrameCallback(time -> vsync.countDown()));
        if (!vsync.await(3, TimeUnit.SECONDS)) throw new java.io.IOException("vsync-timeout");
        presenter.presentRgba(pixels, WIDTH, HEIGHT, STRIDE, viewport);
        if (header.getInt(slot + 8) != seq) throw new java.io.IOException("frame-reused-before-ack");
        boolean accepted = presenter.lastPresentationSucceeded();
        if (confirmation != null) gate.finish(confirmation, accepted);
        if (accepted) recordSwap(pixels, STRIDE, timestamp);
        return seq;
    }

    private void initial(ByteBuffer pixels, int stride) {
        if (initialPacket.getCount() != 0) {
            baselinePixels = fingerprint(pixels, stride);
            initialPacket.countDown();
        }
    }

    private void recordSwap(ByteBuffer pixels, int stride, long receivedNs) {
        long now = System.nanoTime();
        if (receivedNs < startNs || now > endNs) return;
        if (firstSwapNs.get() == 0) {
            if (fingerprint(pixels, stride) == baselinePixels) return;
            firstSwapNs.compareAndSet(0, now);
        }
        presentedCount.incrementAndGet();
    }

    private static long fingerprint(ByteBuffer pixels, int stride) {
        long sample = 0xcbf29ce484222325L; // FNV-1a hash basis
        for (int y = 4; y < HEIGHT; y += 16) {
            for (int x = 4; x < WIDTH; x += 16) {
                sample ^= pixels.getInt(y * stride + x * 4) >>> 8;
                sample *= 0x100000001b3L;
            }
        }
        return sample;
    }

    String measure() throws java.io.IOException {
        if (rfbFailed) throw new java.io.IOException("rfb-stopped");
        long first = firstSwapNs.get();
        if (first == 0) throw new java.io.IOException("no-changed-frame");
        return "latency_ms=" + BenchRun.decimal((first - startNs) / 1e6)
                + " frames=" + presentedCount.get()
                + " invalid_frames=" + invalidCount.get()
                + " renderer_drops=" + rfbRenderer.getDroppedFrameCount()
                + " shared_frames=" + sharedFrames.get()
                + " shared_payload_bytes=" + sharedFrames.get() * STRIDE * HEIGHT;
    }

    void close() {
        rfbRenderer.release();
    }
}
