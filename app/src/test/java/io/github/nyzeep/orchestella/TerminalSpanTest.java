package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * JVM tests for the bold-default-foreground softening: boot/system logs arrive as
 * ANSI bold with no explicit color and used to render as glaring near-white blocks.
 * The terminal keeps the bold font but softens only that combination; explicit
 * colors, non-bold text and inverse spans keep the exact palette values.
 */
public class TerminalSpanTest {

    private static final int[] TEST_ANSI = new int[16];
    private static final int TEST_FG = 0xFFF8F8F2;
    private static final int TEST_BG = 0xFF101014;

    static {
        for (int i = 0; i < TEST_ANSI.length; i++) TEST_ANSI[i] = 0xFF000000 | (0x111111 * (i + 1));
    }

    @After
    public void resetPaletteOverride() {
        DraculaPalette.setPaletteOverride(null, 0, 0);
    }

    @Test
    public void boldDefaultForegroundIsSoftenedTowardBackground() {
        DraculaPalette.setPaletteOverride(TEST_ANSI, TEST_FG, TEST_BG);
        TerminalSpan span = new TerminalSpan("systemd message", null, null, true, false, false, false);
        int effective = span.getEffectiveForegroundColor();
        assertFalse("softened color must differ from the raw foreground", effective == TEST_FG);
        // Blend 25% toward background: each channel sits between fg and bg, closer to fg.
        int r = (effective >> 16) & 0xFF;
        int g = (effective >> 8) & 0xFF;
        int b = effective & 0xFF;
        assertTrue(r > ((TEST_BG >> 16) & 0xFF) && r < ((TEST_FG >> 16) & 0xFF));
        assertTrue(g > ((TEST_BG >> 8) & 0xFF) && g < ((TEST_FG >> 8) & 0xFF));
        assertTrue(b > (TEST_BG & 0xFF) && b < (TEST_FG & 0xFF));
        // And exactly at the 75/25 blend point.
        int expected = blend25(TEST_FG, TEST_BG);
        assertEquals(expected, effective);
    }

    @Test
    public void nonBoldDefaultForegroundKeepsRawThemeColor() {
        DraculaPalette.setPaletteOverride(TEST_ANSI, TEST_FG, TEST_BG);
        TerminalSpan span = new TerminalSpan("plain output", null, null, false, false, false, false);
        assertEquals(TEST_FG, span.getEffectiveForegroundColor());
    }

    @Test
    public void boldWithExplicitColorKeepsTheExactColor() {
        DraculaPalette.setPaletteOverride(TEST_ANSI, TEST_FG, TEST_BG);
        int explicit = 0xFF50FA7B;
        TerminalSpan span = new TerminalSpan("bold green", explicit, null, true, false, false, false);
        assertEquals(explicit, span.getEffectiveForegroundColor());
    }

    @Test
    public void inverseSpansKeepBackgroundAsForeground() {
        DraculaPalette.setPaletteOverride(TEST_ANSI, TEST_FG, TEST_BG);
        TerminalSpan span = new TerminalSpan("inverse", null, 0xFFBD93F9, true, false, true, true);
        assertEquals(0xFFBD93F9, span.getEffectiveForegroundColor());
    }

    @Test
    public void withoutOverrideTheFallbackPaletteStillSoftens() {
        // No override: legacy Dracula defaults must produce a softened value too.
        TerminalSpan span = new TerminalSpan("boot line", null, null, true, false, false, false);
        int effective = span.getEffectiveForegroundColor();
        assertFalse(effective == DraculaPalette.themedForeground());
    }

    private static int blend25(int fg, int bg) {
        int r = (int) Math.round(((fg >> 16) & 0xFF) * 0.75 + ((bg >> 16) & 0xFF) * 0.25);
        int g = (int) Math.round(((fg >> 8) & 0xFF) * 0.75 + ((bg >> 8) & 0xFF) * 0.25);
        int b = (int) Math.round((fg & 0xFF) * 0.75 + (bg & 0xFF) * 0.25);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
