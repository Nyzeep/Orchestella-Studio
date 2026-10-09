package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class ViewportAspectCalculatorTest {

    @Test
    public void aspectPreservation16x9In20x9ViewportPillarboxes() {
        // Content: 1920x1080 (16:9 = 1.7777)
        // Viewport: 2400x1080 (20:9 = 2.2222, wider than content) -> pillarbox with black bars left & right
        ViewportAspectCalculator.ViewportMapping mapping =
                ViewportAspectCalculator.calculate(1920, 1080, 2400, 1080);

        assertNotNull(mapping);
        assertEquals(1920, mapping.contentWidth());
        assertEquals(1080, mapping.contentHeight());
        assertEquals(2400, mapping.viewportWidth());
        assertEquals(1080, mapping.viewportHeight());

        assertEquals(1920, mapping.destWidth());
        assertEquals(1080, mapping.destHeight());
        assertEquals(240, mapping.destX()); // (2400 - 1920) / 2
        assertEquals(0, mapping.destY());

        assertTrue("Should be pillarboxed with bars on left/right", mapping.isPillarbox());
        assertFalse("Should not be letterboxed", mapping.isLetterbox());
        assertFalse("Not an exact fit", mapping.isExactFit());
    }

    @Test
    public void aspectPreservation16x9In4x3ViewportLetterboxes() {
        // Content: 1920x1080 (16:9 = 1.7777)
        // Viewport: 1440x1080 (4:3 = 1.3333, narrower than content) -> letterbox with bars top & bottom
        ViewportAspectCalculator.ViewportMapping mapping =
                ViewportAspectCalculator.calculate(1920, 1080, 1440, 1080);

        assertEquals(1440, mapping.destWidth());
        assertEquals(810, mapping.destHeight()); // 1440 * (1080 / 1920) = 810
        assertEquals(0, mapping.destX());
        assertEquals(135, mapping.destY()); // (1080 - 810) / 2 = 135

        assertTrue("Should be letterboxed with bars on top/bottom", mapping.isLetterbox());
        assertFalse("Should not be pillarboxed", mapping.isPillarbox());
        assertFalse("Not an exact fit", mapping.isExactFit());
    }

    @Test
    public void exactAspectRatioMatchProducesZeroPadding() {
        // Content: 1920x1080, Viewport: 1920x1080
        ViewportAspectCalculator.ViewportMapping mapping =
                ViewportAspectCalculator.calculate(1920, 1080, 1920, 1080);

        assertEquals(0, mapping.destX());
        assertEquals(0, mapping.destY());
        assertEquals(1920, mapping.destWidth());
        assertEquals(1080, mapping.destHeight());
        assertTrue("Exact match should be exact fit", mapping.isExactFit());
        assertFalse(mapping.isLetterbox());
        assertFalse(mapping.isPillarbox());
    }

    @Test
    public void squareContentInWidescreenAndTallViewport() {
        // Content 1000x1000 (1:1) in 2000x1000 viewport -> pillarbox
        ViewportAspectCalculator.ViewportMapping wide =
                ViewportAspectCalculator.calculate(1000, 1000, 2000, 1000);
        assertEquals(1000, wide.destWidth());
        assertEquals(1000, wide.destHeight());
        assertEquals(500, wide.destX());
        assertEquals(0, wide.destY());
        assertTrue(wide.isPillarbox());

        // Content 1000x1000 (1:1) in 1000x2000 viewport -> letterbox
        ViewportAspectCalculator.ViewportMapping tall =
                ViewportAspectCalculator.calculate(1000, 1000, 1000, 2000);
        assertEquals(1000, tall.destWidth());
        assertEquals(1000, tall.destHeight());
        assertEquals(0, tall.destX());
        assertEquals(500, tall.destY());
        assertTrue(tall.isLetterbox());
    }

    @Test
    public void portraitViewportWithLandscapeContent() {
        // Typical mobile vertical orientation: 1080x2400 (9:20)
        // Desktop landscape content: 1920x1080 (16:9)
        ViewportAspectCalculator.ViewportMapping portrait =
                ViewportAspectCalculator.calculate(1920, 1080, 1080, 2400);

        assertEquals(1080, portrait.destWidth());
        int expectedHeight = (int) Math.round(1080.0 * 1080.0 / 1920.0); // 608
        assertEquals(expectedHeight, portrait.destHeight());
        assertEquals(0, portrait.destX());
        assertEquals((2400 - expectedHeight) / 2, portrait.destY());
        assertTrue(portrait.isLetterbox());
    }

    @Test
    public void coordinateMappingForwardAndBackward() {
        // 1920x1080 in 2400x1080 -> destX=240, destY=0, destWidth=1920, destHeight=1080
        ViewportAspectCalculator.ViewportMapping mapping =
                ViewportAspectCalculator.calculate(1920, 1080, 2400, 1080);

        // Outside in black bar
        assertFalse(mapping.isInsideContent(100, 500));
        assertTrue(mapping.isInsideContent(240, 0));
        assertTrue(mapping.isInsideContent(1200, 540));
        assertTrue(mapping.isInsideContent(2159, 1079));
        assertFalse(mapping.isInsideContent(2161, 540));

        // Coordinate transformation: viewport -> content
        float[] cOrigin = mapping.mapViewportToContent(240, 0);
        assertEquals(0f, cOrigin[0], 0.001f);
        assertEquals(0f, cOrigin[1], 0.001f);

        float[] cCenter = mapping.mapViewportToContent(1200, 540); // 240 + 960
        assertEquals(960f, cCenter[0], 0.001f);
        assertEquals(540f, cCenter[1], 0.001f);

        // Coordinate transformation: content -> viewport
        float[] vCenter = mapping.mapContentToViewport(960, 540);
        assertEquals(1200f, vCenter[0], 0.001f);
        assertEquals(540f, vCenter[1], 0.001f);
    }

    @Test
    public void scaleTypeModesSupport() {
        // FILL_STRETCH
        ViewportAspectCalculator.ViewportMapping stretch =
                ViewportAspectCalculator.calculate(1920, 1080, 2400, 1080,
                        ViewportAspectCalculator.ScaleType.FILL_STRETCH);
        assertEquals(0, stretch.destX());
        assertEquals(0, stretch.destY());
        assertEquals(2400, stretch.destWidth());
        assertEquals(1080, stretch.destHeight());
        assertFalse(stretch.isLetterbox());
        assertFalse(stretch.isPillarbox());

        // CENTER_CROP
        ViewportAspectCalculator.ViewportMapping crop =
                ViewportAspectCalculator.calculate(1920, 1080, 2400, 1080,
                        ViewportAspectCalculator.ScaleType.CENTER_CROP);
        // Crop scales by viewportWidth / contentWidth = 2400 / 1920 = 1.25
        // destWidth = 2400, destHeight = 1080 * 1.25 = 1350
        // destX = 0, destY = (1080 - 1350) / 2 = -135
        assertEquals(2400, crop.destWidth());
        assertEquals(1350, crop.destHeight());
        assertEquals(0, crop.destX());
        assertEquals(-135, crop.destY());
    }

    @Test
    public void rejectsNonPositiveDimensions() {
        try {
            ViewportAspectCalculator.calculate(0, 1080, 1920, 1080);
            fail("Zero content width should fail");
        } catch (IllegalArgumentException expected) {
        }

        try {
            ViewportAspectCalculator.calculate(1920, -10, 1920, 1080);
            fail("Negative content height should fail");
        } catch (IllegalArgumentException expected) {
        }

        try {
            ViewportAspectCalculator.calculate(1920, 1080, 0, 1080);
            fail("Zero viewport width should fail");
        } catch (IllegalArgumentException expected) {
        }

        try {
            ViewportAspectCalculator.calculate(1920, 1080, 1920, -5);
            fail("Negative viewport height should fail");
        } catch (IllegalArgumentException expected) {
        }
    }
}
