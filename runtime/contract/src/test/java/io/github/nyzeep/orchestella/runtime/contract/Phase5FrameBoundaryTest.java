package io.github.nyzeep.orchestella.runtime.contract;

import org.junit.Test;
import static org.junit.Assert.*;

public class Phase5FrameBoundaryTest {
    private void rejected(int width, int height, int stride, int format, byte[] bytes) {
        try {
            new WaylandChannel.FrameData(width, height, stride, format, bytes, 1, 1);
            fail("untrusted frame was accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void rejectsTruncationOversizeAndUnknownPixelFormatBeforeCopying() {
        rejected(10, 10, 40, 1, new byte[399]);
        rejected(10, 10, 40, 1, new byte[401]);
        rejected(10, 10, 41, 1, new byte[410]);
        rejected(10, 10, 40, 8, new byte[400]);
        rejected(Integer.MAX_VALUE, 1, 4, 1, new byte[4]);
        rejected(4096, 4096, 16384, 1, new byte[4]);
        rejected(1, 2, Integer.MAX_VALUE, 1, new byte[4]);
    }

    @Test public void preservesValidPaddedRowsAndOwnsItsPixels() {
        byte[] pixels = new byte[24];
        pixels[8] = 21;
        WaylandChannel.FrameData frame = new WaylandChannel.FrameData(2, 2, 12, 3, pixels, 1, 1);
        pixels[8] = 31;
        assertEquals(21, frame.data()[8]);
        byte[] copy = frame.data();
        copy[8] = 41;
        assertEquals(21, frame.data()[8]);
        assertEquals(12, frame.stride());
    }
}
