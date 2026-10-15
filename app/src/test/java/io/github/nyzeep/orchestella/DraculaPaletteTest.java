package io.github.nyzeep.orchestella;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DraculaPaletteTest {
    @After
    public void restoreDefaultPalette() {
        DraculaPalette.setPaletteOverride(null, 0, 0);
    }

    @Test
    public void lightPaletteAppliesToBothAnsiAndIndexedEscapeSequences() {
        int[] lightAnsi = {
                0xFF24292F, 0xFFCF222E, 0xFF1A7F37, 0xFF9A6700,
                0xFF0969DA, 0xFF8250DF, 0xFF1B7C83, 0xFF6E7781,
                0xFF57606A, 0xFFA40E26, 0xFF116329, 0xFF633C01,
                0xFF0550AE, 0xFF6639BA, 0xFF0E5C63, 0xFF24292F
        };
        DraculaPalette.setPaletteOverride(lightAnsi, 0xFF1F2328, 0xFFFFFFFF);
        assertEquals(0xFFFFFFFF, DraculaPalette.themedBackground());
        assertEquals(0xFF1F2328, DraculaPalette.themedForeground());
        for (int index = 0; index < 16; index++) {
            assertEquals("ANSI index " + index, lightAnsi[index], DraculaPalette.getAnsi16(index));
            assertEquals("indexed ANSI color " + index, lightAnsi[index], DraculaPalette.get256(index));
        }
    }

    @Test
    public void invalidIndicesUseCurrentForegroundAndExtendedColorsStayStandard() {
        DraculaPalette.setPaletteOverride(new int[16], 0xFF123456, 0xFFFDF6E3);
        assertEquals(0xFF123456, DraculaPalette.get256(-1));
        assertEquals(0xFF123456, DraculaPalette.get256(256));
        assertEquals(0xFF000000, DraculaPalette.get256(16));
        assertEquals(0xFFFFFFFF, DraculaPalette.get256(231));
        assertEquals(0xFF080808, DraculaPalette.get256(232));
        assertEquals(0xFFEEEEEE, DraculaPalette.get256(255));
    }

    @Test
    public void overridesAreCopiedAndClearingRestoresLegacyDracula() {
        int[] colors = new int[16];
        colors[1] = 0xFF112233;
        DraculaPalette.setPaletteOverride(colors, 0xFF334455, 0xFF556677);
        colors[1] = 0xFFFF0000;
        assertEquals(0xFF112233, DraculaPalette.getAnsi16(1));
        DraculaPalette.setPaletteOverride(null, 0, 0);
        assertEquals(DraculaPalette.BACKGROUND, DraculaPalette.themedBackground());
        assertEquals(DraculaPalette.FOREGROUND, DraculaPalette.themedForeground());
        assertEquals(DraculaPalette.COLOR_RED, DraculaPalette.get256(1));
    }
}
