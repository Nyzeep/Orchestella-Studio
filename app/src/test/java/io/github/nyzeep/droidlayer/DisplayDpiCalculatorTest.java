package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;

import io.github.nyzeep.droidlayer.runtime.contract.DisplayMode;
import org.junit.Test;

/**
 * TDD Unit tests for DisplayDpiCalculator (Phase 4.6 Step 1).
 * Verifies dynamic High-DPI scale calculation across device screen resolutions and densities,
 * eliminating "ant-sized fonts" on high-DPI displays like 1264x2736 @ 560dpi.
 */
public final class DisplayDpiCalculatorTest {

    private static final double EPSILON = 0.001;

    @Test
    public void testPhysicalTargetDeviceCalculatesTwoPointZeroScale() {
        // Physical device: Honor MagicOS / 1264x2736 @ 560 dpi
        double scale = DisplayDpiCalculator.calculateScale(560, 1264, 2736);
        assertEquals(2.0, scale, EPSILON);

        // In landscape orientation (2736x1264 @ 560 dpi)
        double landscapeScale = DisplayDpiCalculator.calculateScale(560, 2736, 1264);
        assertEquals(2.0, landscapeScale, EPSILON);
    }

    @Test
    public void testCommonModernPhoneDisplaysCalculateCorrectScale() {
        // Typical 1080x2400 @ 440 dpi (FHD+ OLED)
        assertEquals(2.0, DisplayDpiCalculator.calculateScale(440, 1080, 2400), EPSILON);

        // 1440x3200 @ 515 dpi (QHD+ flagship)
        assertEquals(2.0, DisplayDpiCalculator.calculateScale(515, 1440, 3200), EPSILON);

        // 720x1600 @ 320 dpi (HD+ budget device)
        assertEquals(1.5, DisplayDpiCalculator.calculateScale(320, 720, 1600), EPSILON);

        // 1280x720 @ 160 dpi (Standard MDPI desktop/tablet monitor)
        assertEquals(1.0, DisplayDpiCalculator.calculateScale(160, 1280, 720), EPSILON);

        // 1920x1080 @ 240 dpi (Standard 24-inch monitor or basic tablet)
        assertEquals(1.0, DisplayDpiCalculator.calculateScale(240, 1920, 1080), EPSILON);
    }

    @Test
    public void testLargeResolutionTabletsScaleAppropriately() {
        // High-res tablet: 2560x1600 @ 280 dpi
        assertEquals(2.0, DisplayDpiCalculator.calculateScale(280, 2560, 1600), EPSILON);
    }

    @Test
    public void testSafeFallbacksOnBoundaryOrInvalidInputs() {
        // Non-positive or zero density should safely default to 1.0
        assertEquals(1.0, DisplayDpiCalculator.calculateScale(0, 1264, 2736), EPSILON);
        assertEquals(1.0, DisplayDpiCalculator.calculateScale(-160, 1264, 2736), EPSILON);

        // Zero or negative dimensions should safely fallback to 1.0
        assertEquals(1.0, DisplayDpiCalculator.calculateScale(560, 0, 0), EPSILON);
        assertEquals(1.0, DisplayDpiCalculator.calculateScale(560, -100, -200), EPSILON);
    }

    @Test
    public void testScaleIsClampedWithinDisplayModeLimits() {
        double scale = DisplayDpiCalculator.clampScale(2.0);
        assertEquals(2.0, scale, EPSILON);

        assertEquals(DisplayMode.MIN_SCALE, DisplayDpiCalculator.clampScale(0.01), EPSILON);
        assertEquals(DisplayMode.MAX_SCALE, DisplayDpiCalculator.clampScale(10.0), EPSILON);
    }
}
