package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for MobileAuxiliaryKeyBar logic and WindowInsets calculation.
 */
public final class MobileAuxiliaryKeyBarTest {

    private ByteArrayOutputStream ptyStream;
    private TerminalInputSink inputSink;

    @Before
    public void setUp() {
        ptyStream = new ByteArrayOutputStream();
        inputSink = new TerminalInputSink() {
            @Override
            public void sendBytes(byte[] bytes) {
                if (bytes != null) {
                    ptyStream.write(bytes, 0, bytes.length);
                }
            }
        };
    }

    @Test
    public void testCalculateTargetBottomPaddingWithImeExpanded() {
        // Soft keyboard is visible with 850px height, nav bar is 48px
        int targetPadding = MobileAuxiliaryKeyBar.calculateTargetBottomPadding(850, 48);
        assertEquals(850, targetPadding);
    }

    @Test
    public void testCalculateTargetBottomPaddingWithImeRetracted() {
        // Soft keyboard is closed (imeBottom = 0), nav bar is 48px
        int targetPadding = MobileAuxiliaryKeyBar.calculateTargetBottomPadding(0, 48);
        assertEquals(48, targetPadding);
    }

    @Test
    public void testCalculateTargetBottomPaddingWithFullscreenEdgeToEdge() {
        // Both keyboard and nav bar are 0 (e.g., gesture navigation or full screen)
        int targetPadding = MobileAuxiliaryKeyBar.calculateTargetBottomPadding(0, 0);
        assertEquals(0, targetPadding);
    }

    @Test
    public void testCalculateTargetBottomPaddingWithNegativeValuesSafeguard() {
        int targetPadding = MobileAuxiliaryKeyBar.calculateTargetBottomPadding(-1, -10);
        assertEquals(0, targetPadding);
    }
}
