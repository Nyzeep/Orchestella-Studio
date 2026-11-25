package io.github.nyzeep.orchestella.runtime.contract.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.DisplayMode;
import org.junit.Test;

public final class DisplayViewportBudgetTest {

    @Test
    public void keepsStandardResolutionsWithinBudgetUnchanged() {
        for (int[] size : new int[][] {{1920, 1080}, {1280, 720}, {2560, 1440}, {1080, 2400}, {4096, 1024}}) {
            DisplayViewportBudget.FittedViewport fitted =
                    DisplayViewportBudget.fitWithinBudget(size[0], size[1], 1.5f);
            assertEquals(size[0], fitted.width());
            assertEquals(size[1], fitted.height());
            assertEquals(1.5, fitted.displayScale(), 1e-6);
            assertEquals(1.5, fitted.effectiveScale(), 1e-6);
            assertFalse(fitted.downscaled());
            assertTrue(DisplayViewportBudget.isWithinBudget(fitted.width(), fitted.height()));
        }
    }

    @Test
    public void scalesDownTwoKPlusPortraitAndFourKWhilePreservingAspectRatioAndEvenEdges() {
        DisplayViewportBudget.FittedViewport phone2k =
                DisplayViewportBudget.fitWithinBudget(1440, 3120, 2.0);
        assertEquals(1390, phone2k.width());
        assertEquals(3014, phone2k.height());
        assertTrue(phone2k.downscaled());
        assertInvariants(phone2k);
        assertAspectRatioClose(1440, 3120, phone2k, 0.005);

        DisplayViewportBudget.FittedViewport uhd =
                DisplayViewportBudget.fitWithinBudget(3840, 2160, 2.0f);
        assertEquals(2730, uhd.width());
        assertEquals(1536, uhd.height());
        assertTrue(uhd.downscaled());
        assertInvariants(uhd);
        assertAspectRatioClose(3840, 2160, uhd, 0.005);
    }

    @Test
    public void alignsOddEdgesToEvenWithoutExceedingBudget() {
        DisplayViewportBudget.FittedViewport oddPortrait =
                DisplayViewportBudget.fitWithinBudget(1081, 2401, 1.0);
        assertEquals(1080, oddPortrait.width());
        assertEquals(2400, oddPortrait.height());
        assertTrue(oddPortrait.downscaled());
        assertInvariants(oddPortrait);

        DisplayViewportBudget.FittedViewport minOdd =
                DisplayViewportBudget.fitWithinBudget(101, 101, 1.0);
        assertEquals(100, minOdd.width());
        assertEquals(100, minOdd.height());
        assertInvariants(minOdd);
    }

    @Test
    public void handlesSubMinimumAndExtremeAspectRatiosWithinBounds() {
        for (int[] size : new int[][] {{60, 80}, {50, 50}, {1, 1}, {5000, 100}, {100, 5000}, {50, 3000}, {8000, 400}}) {
            DisplayViewportBudget.FittedViewport fitted =
                    DisplayViewportBudget.fitWithinBudget(size[0], size[1], 1.25);
            assertInvariants(fitted);
        }

        DisplayViewportBudget.FittedViewport subMin = DisplayViewportBudget.fitWithinBudget(60, 80, 1.0);
        assertEquals(100, subMin.width());
        assertEquals(132, subMin.height());
        assertFalse(subMin.downscaled());
    }

    @Test
    public void fitWithinBudgetIsIdempotentAndClampsScales() {
        DisplayViewportBudget.FittedViewport first =
                DisplayViewportBudget.fitWithinBudget(1440, 3120, 2.0);
        DisplayViewportBudget.FittedViewport second =
                DisplayViewportBudget.fitWithinBudget(first.width(), first.height(), first.displayScale());
        assertEquals(first.width(), second.width());
        assertEquals(first.height(), second.height());
        assertFalse(second.downscaled());

        assertEquals(1.0, DisplayViewportBudget.fitWithinBudget(1280, 720, Double.NaN).displayScale(), 1e-6);
        assertEquals(1.0, DisplayViewportBudget.fitWithinBudget(1280, 720, Double.POSITIVE_INFINITY).displayScale(), 1e-6);
        assertEquals(DisplayMode.MIN_SCALE, DisplayViewportBudget.fitWithinBudget(1280, 720, 0.01).displayScale(), 1e-6);
        assertEquals(DisplayMode.MAX_SCALE, DisplayViewportBudget.fitWithinBudget(1280, 720, 9.0).displayScale(), 1e-6);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroWidth() {
        DisplayViewportBudget.fitWithinBudget(0, 1080, 1.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeHeight() {
        DisplayViewportBudget.fitWithinBudget(1080, -10, 1.0);
    }

    private static void assertInvariants(DisplayViewportBudget.FittedViewport fitted) {
        assertTrue(fitted.width() >= DisplayViewportBudget.MIN_EDGE);
        assertTrue(fitted.width() <= DisplayViewportBudget.MAX_EDGE);
        assertTrue(fitted.height() >= DisplayViewportBudget.MIN_EDGE);
        assertTrue(fitted.height() <= DisplayViewportBudget.MAX_EDGE);
        assertEquals(0, fitted.width() & 1);
        assertEquals(0, fitted.height() & 1);
        assertTrue((long) fitted.width() * fitted.height() <= DisplayViewportBudget.MAX_PIXELS);
        assertTrue(DisplayViewportBudget.isWithinBudget(fitted.width(), fitted.height()));
    }

    private static void assertAspectRatioClose(
            int rawW, int rawH, DisplayViewportBudget.FittedViewport fitted, double maxRelativeError) {
        double rawAspect = (double) rawW / rawH;
        double fittedAspect = (double) fitted.width() / fitted.height();
        double relativeError = Math.abs(fittedAspect - rawAspect) / rawAspect;
        assertTrue("aspect ratio drift too large: " + relativeError, relativeError <= maxRelativeError);
    }
}
