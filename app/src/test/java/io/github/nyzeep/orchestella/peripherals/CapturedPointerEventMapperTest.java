package io.github.nyzeep.orchestella.peripherals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.PointerEvent;
import org.junit.Test;

/**
 * Unit tests for CapturedPointerEventMapper:
 * - Relative displacement mapping (deltaX, deltaY)
 * - Physical mouse button mask conversion (BUTTON_PRIMARY, BUTTON_SECONDARY, BUTTON_TERTIARY)
 * - Multi-button simultaneous clicks (Left + Right, Left + Middle)
 * - Ctrl + Alt release shortcut detection
 */
public final class CapturedPointerEventMapperTest {

    @Test
    public void testRelativeDisplacementMapping() {
        float dx = 12.5f;
        float dy = -8.25f;
        long timeNs = 123456789L;

        PointerEvent event = CapturedPointerEventMapper.mapRelativeMotion(dx, dy, 0, timeNs);

        assertNotNull(event);
        assertTrue(event.isRelativeMotion());
        assertEquals(PointerEvent.Action.RELATIVE_MOVE, event.action());
        assertEquals(12.5, event.deltaX(), 0.001);
        assertEquals(-8.25, event.deltaY(), 0.001);
        assertEquals(0.0, event.x(), 0.001);
        assertEquals(0.0, event.y(), 0.001);
        assertEquals(PointerEvent.BUTTON_NONE, event.button());
        assertEquals(timeNs, event.timestampNs());
    }

    @Test
    public void testSingleButtonMaskMapping() {
        // Android BUTTON_PRIMARY (1) -> PointerEvent.BUTTON_PRIMARY (1)
        int androidPrimary = 1;
        PointerEvent primaryEvent = CapturedPointerEventMapper.mapRelativeMotion(0f, 0f, androidPrimary, 1000L);
        assertEquals(PointerEvent.BUTTON_PRIMARY, primaryEvent.button());
        assertTrue(primaryEvent.hasButton(PointerEvent.BUTTON_PRIMARY));
        assertFalse(primaryEvent.hasButton(PointerEvent.BUTTON_SECONDARY));
        assertFalse(primaryEvent.hasButton(PointerEvent.BUTTON_TERTIARY));

        // Android BUTTON_SECONDARY (2) -> PointerEvent.BUTTON_SECONDARY (2)
        int androidSecondary = 2;
        PointerEvent secondaryEvent = CapturedPointerEventMapper.mapRelativeMotion(0f, 0f, androidSecondary, 1000L);
        assertEquals(PointerEvent.BUTTON_SECONDARY, secondaryEvent.button());
        assertTrue(secondaryEvent.hasButton(PointerEvent.BUTTON_SECONDARY));
        assertFalse(secondaryEvent.hasButton(PointerEvent.BUTTON_PRIMARY));

        // Android BUTTON_TERTIARY (4) -> PointerEvent.BUTTON_TERTIARY (4)
        int androidTertiary = 4;
        PointerEvent tertiaryEvent = CapturedPointerEventMapper.mapRelativeMotion(0f, 0f, androidTertiary, 1000L);
        assertEquals(PointerEvent.BUTTON_TERTIARY, tertiaryEvent.button());
        assertTrue(tertiaryEvent.hasButton(PointerEvent.BUTTON_TERTIARY));
        assertFalse(tertiaryEvent.hasButton(PointerEvent.BUTTON_PRIMARY));
    }

    @Test
    public void testCombinedButtonMasksMapping() {
        // Left + Right simultaneous click
        int combined = 1 | 2; // Primary + Secondary
        PointerEvent event = CapturedPointerEventMapper.mapRelativeMotion(5.0f, -3.0f, combined, 2000L);
        assertTrue(event.hasButton(PointerEvent.BUTTON_PRIMARY));
        assertTrue(event.hasButton(PointerEvent.BUTTON_SECONDARY));
        assertFalse(event.hasButton(PointerEvent.BUTTON_TERTIARY));
        assertEquals(combined, event.buttonMask());

        // Left + Middle simultaneous click
        int leftMiddle = 1 | 4;
        PointerEvent lmEvent = CapturedPointerEventMapper.mapRelativeMotion(0f, 0f, leftMiddle, 3000L);
        assertTrue(lmEvent.hasButton(PointerEvent.BUTTON_PRIMARY));
        assertTrue(lmEvent.hasButton(PointerEvent.BUTTON_TERTIARY));
        assertFalse(lmEvent.hasButton(PointerEvent.BUTTON_SECONDARY));

        // All 3 buttons pressed
        int allThree = 1 | 2 | 4;
        PointerEvent allEvent = CapturedPointerEventMapper.mapRelativeMotion(0f, 0f, allThree, 4000L);
        assertTrue(allEvent.hasButton(PointerEvent.BUTTON_PRIMARY));
        assertTrue(allEvent.hasButton(PointerEvent.BUTTON_SECONDARY));
        assertTrue(allEvent.hasButton(PointerEvent.BUTTON_TERTIARY));
    }

    @Test
    public void testReleaseShortcutDetection() {
        // Ctrl + Alt held -> release
        assertTrue(CapturedPointerEventMapper.isReleaseShortcut(true, true));

        // Only Ctrl held -> do not release
        assertFalse(CapturedPointerEventMapper.isReleaseShortcut(true, false));

        // Only Alt held -> do not release
        assertFalse(CapturedPointerEventMapper.isReleaseShortcut(false, true));

        // Neither held -> do not release
        assertFalse(CapturedPointerEventMapper.isReleaseShortcut(false, false));

        // MetaState bitmask tests:
        // META_CTRL_ON (0x1000) | META_ALT_ON (0x02)
        int metaBoth = 0x1000 | 0x02;
        assertTrue(CapturedPointerEventMapper.isReleaseShortcut(metaBoth));

        int metaCtrlOnly = 0x1000;
        assertFalse(CapturedPointerEventMapper.isReleaseShortcut(metaCtrlOnly));

        int metaAltOnly = 0x02;
        assertFalse(CapturedPointerEventMapper.isReleaseShortcut(metaAltOnly));

        int metaNone = 0;
        assertFalse(CapturedPointerEventMapper.isReleaseShortcut(metaNone));
    }
}
