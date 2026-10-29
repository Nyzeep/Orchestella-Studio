package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the user-facing rule for every background image: fill the target completely, crop the
 * overflow, and never stretch the picture. A regression here shows up as a squashed wallpaper or a
 * terminal background that changes proportions when the keyboard opens.
 */
public final class CoverCropGeometryTest {

    private static final float EPSILON = 0.0001f;

    @Test
    public void widerSourceCropsLeftAndRightInsteadOfSqueezingWidth() {
        // A 16:9 photo shown on a portrait phone: the picture must stay 16:9 and lose its sides.
        CoverCropGeometry.Placement placement = CoverCropGeometry.center(1920, 1080, 1080, 1920);

        assertEquals("Scale must follow the taller side so the target is fully covered",
                1920f / 1080f, placement.scale(), EPSILON);
        assertEquals("Scaled width overflows the 1080 target", 3413.33f, placement.drawWidth(), 0.01f);
        assertEquals("Scaled height exactly covers the target height", 1920f, placement.drawHeight(), EPSILON);
        assertEquals("Overflow is split evenly on the left", -1166.67f, placement.drawLeft(), 0.01f);
        assertEquals("Nothing overflows vertically, so the top edge is not cropped", 0f, placement.drawTop(), EPSILON);
        assertEquals("No source row is dropped", 1080, placement.cropHeight());
        assertTrue("Horizontal crop drops source columns", placement.cropWidth() < 1920);
        assertTrue("The crop starts past the left edge", placement.cropLeft() > 0);
    }

    @Test
    public void tallerSourceCropsTopAndBottomInsteadOfSqueezingHeight() {
        // A 1080x2400 screenshot on a 1080x1920 page: full width, trimmed vertically.
        CoverCropGeometry.Placement placement = CoverCropGeometry.center(1080, 2400, 1080, 1920);

        assertEquals("1:1 scale already covers the width", 1f, placement.scale(), EPSILON);
        assertEquals("Width matches the target exactly", 1080f, placement.drawWidth(), EPSILON);
        assertEquals("Height overflows the 1920 target", 2400f, placement.drawHeight(), EPSILON);
        assertEquals("Overflow is split evenly on top", -240f, placement.drawTop(), EPSILON);
        assertEquals("No source column is dropped", 1080, placement.cropWidth());
        assertEquals("The visible band starts below the top edge", 240, placement.cropTop());
        assertEquals("The visible band is the target height at 1:1", 1920, placement.cropHeight());
    }

    @Test
    public void extremeStripStillCoversWithoutDistortion() {
        // A 4000x200 banner on a portrait page: it must be blown up until it covers, not stretched.
        CoverCropGeometry.Placement placement = CoverCropGeometry.center(4000, 200, 1080, 1920);

        assertEquals("Cover scale is driven by the 200px height", 1920f / 200f, placement.scale(), EPSILON);
        assertEquals("Only a narrow slice of the strip stays visible",
                1080f / (1920f / 200f), placement.cropWidth(), 1f);
        assertEquals("Every row stays visible", 200, placement.cropHeight());
        assertEquals("Scaled source is far wider than the target", 38400f, placement.drawWidth(), 1f);
        assertTrue("Crop starts near the middle of the strip", placement.cropLeft() > 1500);
    }

    @Test
    public void scaleIsUniformOnBothAxesForEveryCommonRatio() {
        int[][] cases = {
                {1920, 1080, 1080, 1920},  // landscape photo, portrait page
                {1080, 2400, 1080, 1920},  // tall screenshot, shorter page
                {4000, 200, 1080, 1920},   // extreme strip
                {1080, 1920, 1440, 3120},  // phone page on a tablet page
                {512, 512, 1080, 1920}     // square icon as full-bleed art
        };
        for (int[] c : cases) {
            CoverCropGeometry.Placement placement = CoverCropGeometry.center(c[0], c[1], c[2], c[3]);
            float scaleX = placement.drawWidth() / c[0];
            float scaleY = placement.drawHeight() / c[1];
            assertEquals("Distortion guard: x and y scale must be identical for " + c[0] + "x" + c[1]
                            + " into " + c[2] + "x" + c[3],
                    scaleX, scaleY, EPSILON);
            assertTrue("The scaled image must cover the whole target width",
                    placement.drawWidth() >= c[2] - EPSILON);
            assertTrue("The scaled image must cover the whole target height",
                    placement.drawHeight() >= c[3] - EPSILON);
        }
    }

    @Test
    public void centredAnchorKeepsEqualOverflowOnBothSides() {
        CoverCropGeometry.Placement placement = CoverCropGeometry.center(2000, 1000, 1000, 1000);

        assertEquals("Scale is driven by the wider axis", 1f, placement.scale(), EPSILON);
        assertEquals("Half the horizontal overflow is clipped on each side",
                -500f, placement.drawLeft(), EPSILON);
        assertEquals("Left and right crops are symmetric", 500, placement.cropLeft());
        assertEquals("Visible band is exactly the target width", 1000, placement.cropWidth());
    }

    @Test
    public void topAnchorPinsTheTopEdgeAndCropsOnlyBelow() {
        CoverCropGeometry.Placement placement = CoverCropGeometry.compute(
                1080, 2400, 1080, 1920, CoverCropGeometry.Anchor.TOP_CENTER);

        assertEquals("The top edge stays at the target origin", 0f, placement.drawTop(), EPSILON);
        assertEquals("No row is dropped from the top", 0, placement.cropTop());
        assertEquals("The overflow is trimmed at the bottom", 1920, placement.cropHeight());
        assertEquals("Horizontal fit is unchanged by the vertical anchor", 1080f, placement.drawWidth(), EPSILON);
    }

    @Test
    public void zeroSizedTargetDegradesToEmptyPlacementInsteadOfCrashing() {
        CoverCropGeometry.Placement placement = CoverCropGeometry.center(1920, 1080, 0, 0);

        assertEquals("No scale is applied to a zero-size slot", 0f, placement.scale(), EPSILON);
        assertEquals("Nothing is drawn", 0f, placement.drawWidth(), EPSILON);
        assertFalse("An empty slot cannot be covered", placement.coversTarget());
        assertTrue("Crop stays inside the source", placement.cropWidth() >= 0);
    }

    @Test
    public void zeroSizedSourceDegradesToEmptyPlacementInsteadOfNaN() {
        CoverCropGeometry.Placement placement = CoverCropGeometry.center(0, 0, 1080, 1920);

        assertEquals("0/0 must not produce a NaN scale", 0f, placement.scale(), EPSILON);
        assertEquals("No draw rect is derived from an empty source", 0f, placement.drawHeight(), EPSILON);
        assertFalse("An empty source cannot cover the slot", placement.coversTarget());
    }

    @Test
    public void nullAnchorFallsBackToCentre() {
        CoverCropGeometry.Placement centred = CoverCropGeometry.compute(
                1080, 2400, 1080, 1920, null);

        assertEquals("A missing anchor must behave like the documented default",
                CoverCropGeometry.center(1080, 2400, 1080, 1920).drawTop(), centred.drawTop(), EPSILON);
    }
}
