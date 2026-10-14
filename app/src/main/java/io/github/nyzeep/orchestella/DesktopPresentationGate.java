package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;

/** Binds a successful presentation to fresh pixels of one verified guest capture generation. */
public final class DesktopPresentationGate {
    /** The local epoch also distinguishes resize and retry attempts within one guest generation. */
    public record Confirmation(String generation, long epoch) { }

    private String generation;
    private int width, height;
    private long notBeforeNs;
    private long epoch;
    private boolean inFlight;
    private boolean confirmed;

    public synchronized boolean observe(String generation, String captureGeneration, int width, int height, long nowNs) {
        if (generation == null || !generation.matches("[0-9a-f]{32}") || !generation.equals(captureGeneration)
                || width <= 0 || height <= 0 || width > 4096 || height > 4096 || (long) width * height > 4_194_304) {
            invalidate();
            return false;
        }
        if (generation.equals(this.generation) && width == this.width && height == this.height) return false;
        this.generation = generation;
        this.width = width;
        this.height = height;
        notBeforeNs = nowNs;
        epoch++;
        inFlight = false;
        confirmed = false;
        return true;
    }

    public synchronized Confirmation begin(WaylandChannel.FrameData frame) {
        if (generation == null || confirmed || inFlight || frame == null || frame.timestampNs() < notBeforeNs
                || frame.width() != width || frame.height() != height) return null;
        byte[] pixels = frame.data();
        int first = (pixels[0] & 255) << 16 | (pixels[1] & 255) << 8 | pixels[2] & 255;
        boolean varied = false;
        for (int row = 0; !varied && row < frame.height(); row++) {
            for (int column = 0; column < frame.width(); column++) {
                int offset = row * frame.stride() + column * 4;
                int color = (pixels[offset] & 255) << 16 | (pixels[offset + 1] & 255) << 8 | pixels[offset + 2] & 255;
                if (color != first) { varied = true; break; }
            }
        }
        if (!varied) return null;
        inFlight = true;
        return new Confirmation(generation, ++epoch);
    }

    public synchronized boolean isCurrent(Confirmation ticket) {
        return ticket != null && inFlight && ticket.epoch() == epoch
                && java.util.Objects.equals(generation, ticket.generation());
    }

    public synchronized void finish(Confirmation ticket, boolean accepted) {
        if (!isCurrent(ticket)) return;
        inFlight = false;
        confirmed = accepted;
    }

    public synchronized void invalidate() {
        epoch++;
        generation = null;
        confirmed = false;
        inFlight = false;
    }
}
