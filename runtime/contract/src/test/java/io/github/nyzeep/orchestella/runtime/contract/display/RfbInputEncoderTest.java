package io.github.nyzeep.orchestella.runtime.contract.display;

import static org.junit.Assert.*;

import io.github.nyzeep.orchestella.runtime.contract.InputEvent;
import io.github.nyzeep.orchestella.runtime.contract.KeyEvent;
import io.github.nyzeep.orchestella.runtime.contract.PointerEvent;
import io.github.nyzeep.orchestella.runtime.contract.TextCommitEvent;
import io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel.InputCapabilities;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

/**
 * Byte-exact RFB input encoding tests for {@link RfbInputEncoder}.
 *
 * <p>These are synthetic wire tests: they lock the encoded client-to-server messages (keyboard
 * message type 4, pointer message type 5) without claiming a real guest or compositor. The encoder
 * is package-private, hence this test lives in the same package.
 */
public final class RfbInputEncoderTest {
    private static final InputCapabilities KEYBOARD_ONLY = new InputCapabilities(true, false, false);
    private static final InputCapabilities FULL = new InputCapabilities(true, true, true);
    private static final InputCapabilities POINTER_ONLY = new InputCapabilities(false, false, true);
    private static final InputCapabilities NOTHING = InputCapabilities.NONE;

    // ---------------------------------------------------------------- keysym mapping

    @Test
    public void mapsDigitRowKeycodesToNumberKeysyms() {
        String digits = "1234567890";
        for (int code = 2; code <= 11; code++) {
            assertArrayEquals("keycode " + code + " must map to '" + digits.charAt(code - 2) + "'",
                    keyFrame(true, digits.charAt(code - 2)), press(code));
        }
        assertArrayEquals("2 -> '1'", keyFrame(true, '1'), press(2));
        assertArrayEquals("11 -> '0'", keyFrame(true, '0'), press(11));
    }

    @Test
    public void mapsLetterRowKeycodesToQwertyKeysyms() {
        String top = "qwertyuiop";
        for (int code = 16; code <= 25; code++) {
            assertArrayEquals(keyFrame(true, top.charAt(code - 16)), press(code));
        }
        String home = "asdfghjkl";
        for (int code = 30; code <= 38; code++) {
            assertArrayEquals(keyFrame(true, home.charAt(code - 30)), press(code));
        }
        String bottom = "zxcvbnm";
        for (int code = 44; code <= 50; code++) {
            assertArrayEquals(keyFrame(true, bottom.charAt(code - 44)), press(code));
        }
        assertArrayEquals("16 -> 'q'", keyFrame(true, 'q'), press(16));
        assertArrayEquals("38 -> 'l'", keyFrame(true, 'l'), press(38));
    }

    @Test
    public void mapsFunctionKeysToX11FunctionKeysyms() {
        assertArrayEquals("F1", keyFrame(true, 0xffbe), press(59));
        assertArrayEquals("F10", keyFrame(true, 0xffc7), press(68));
        assertArrayEquals("F11", keyFrame(true, 0xffc8), press(87));
        assertArrayEquals("F12", keyFrame(true, 0xffc9), press(88));
        assertArrayEquals("F13", keyFrame(true, 0xffca), press(183));
        assertArrayEquals("F24", keyFrame(true, 0xffd5), press(194));
        for (int code = 59; code <= 68; code++) {
            assertArrayEquals(keyFrame(true, 0xffbe + code - 59), press(code));
        }
    }

    @Test
    public void mapsNavigationAndEditingKeycodes() {
        assertArrayEquals("up", keyFrame(true, 0xff52), press(103));
        assertArrayEquals("left", keyFrame(true, 0xff51), press(105));
        assertArrayEquals("right", keyFrame(true, 0xff53), press(106));
        assertArrayEquals("down", keyFrame(true, 0xff54), press(108));
        assertArrayEquals("home", keyFrame(true, 0xff50), press(102));
        assertArrayEquals("page up", keyFrame(true, 0xff55), press(104));
        assertArrayEquals("end", keyFrame(true, 0xff57), press(107));
        assertArrayEquals("page down", keyFrame(true, 0xff56), press(109));
        assertArrayEquals("delete", keyFrame(true, 0xff63), press(110));
        assertArrayEquals("backspace", keyFrame(true, 0xff08), press(14));
        assertArrayEquals("tab", keyFrame(true, 0xff09), press(15));
        assertArrayEquals("enter", keyFrame(true, 0xff0d), press(28));
    }

    @Test
    public void mapsSymbolKeycodes() {
        assertArrayEquals("minus", keyFrame(true, '-'), press(12));
        assertArrayEquals("semicolon", keyFrame(true, ';'), press(39));
        assertArrayEquals("space", keyFrame(true, ' '), press(57));
        assertArrayEquals("equal", keyFrame(true, '='), press(13));
        assertArrayEquals("left bracket", keyFrame(true, '['), press(26));
        assertArrayEquals("right bracket", keyFrame(true, ']'), press(27));
        assertArrayEquals("backslash", keyFrame(true, '\\'), press(43));
        assertArrayEquals("comma", keyFrame(true, ','), press(51));
        assertArrayEquals("period", keyFrame(true, '.'), press(52));
        assertArrayEquals("slash", keyFrame(true, '/'), press(53));
        assertArrayEquals("grave", keyFrame(true, '`'), press(41));
        assertArrayEquals("apostrophe", keyFrame(true, '\''), press(40));
        assertArrayEquals("102nd key", keyFrame(true, '<'), press(86));
    }

    @Test
    public void mapsBoundaryKeycodes() {
        assertArrayEquals("escape", keyFrame(true, 0xff1b), press(1));
        assertArrayEquals("power", keyFrame(true, 0xff13), press(119));
        assertArrayEquals("delete forward", keyFrame(true, 0xff63), press(110));
        assertArrayEquals("caps lock", keyFrame(true, 0xffe5), press(58));
        assertArrayEquals("menu", keyFrame(true, 0xff67), press(127));
        assertArrayEquals("0xffff keysym", keyFrame(true, 0xffff), press(111));
        assertArrayEquals("left shift", keyFrame(true, 0xffe1), press(42));
        assertArrayEquals("left ctrl", keyFrame(true, 0xffe3), press(29));
        assertArrayEquals("left alt", keyFrame(true, 0xffe9), press(56));
        assertArrayEquals("left super", keyFrame(true, 0xffeb), press(125));
    }

    @Test
    public void rejectsUnmappedKeycodes() {
        for (int code : new int[] {0, 200, 201, 255, 300}) {
            assertFalse("keycode " + code + " has no keysym and must be rejected",
                    RfbInputEncoder.supports(KeyEvent.press(code, KeyEvent.MOD_NONE, 0L), FULL));
        }
        // encode() itself does not re-validate; the zero keysym proves supports() is the gate.
        assertArrayEquals(keyFrame(true, 0), press(200));
    }

    // ---------------------------------------------------------------- key frame format

    @Test
    public void encodesPressAndReleaseFrameExactly() {
        RfbInputEncoder encoder = encoder();
        byte[] down = encoder.encode(KeyEvent.press(30, KeyEvent.MOD_NONE, 0L), 640, 480);
        assertArrayEquals(new byte[] {4, 1, 0, 0, 0, 0, 0, 0x61}, down);
        byte[] up = encoder.encode(KeyEvent.release(30, KeyEvent.MOD_NONE, 0L), 640, 480);
        assertArrayEquals(new byte[] {4, 0, 0, 0, 0, 0, 0, 0x61}, up);
        assertEquals("one key message is exactly 8 bytes", 8, down.length);
    }

    @Test
    public void encodesKeysymInMostSignificantBytes() {
        assertArrayEquals(new byte[] {4, 1, 0, 0, 0, 0, (byte) 0xff, (byte) 0xbe}, press(59));
        assertArrayEquals(new byte[] {4, 1, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff}, press(111));
        assertArrayEquals(new byte[] {4, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0x1b},
                release(1, KeyEvent.MOD_NONE));
    }

    // ---------------------------------------------------------------- held-key semantics

    @Test
    public void holdsPressedKeysymUntilRelease() {
        RfbInputEncoder encoder = encoder();
        assertArrayEquals(keyFrame(true, 'a'), encoder.encode(KeyEvent.press(30, KeyEvent.MOD_NONE, 1L), 640, 480));
        assertArrayEquals(keyFrame(false, 'a'), encoder.encode(KeyEvent.release(30, KeyEvent.MOD_NONE, 2L), 640, 480));
        // The held entry is consumed: a second release falls back to the current mapping.
        assertArrayEquals(keyFrame(false, 'a'), encoder.encode(KeyEvent.release(30, KeyEvent.MOD_NONE, 3L), 640, 480));
    }

    @Test
    public void releaseOfUnpressedKeyUsesCurrentMapping() {
        assertArrayEquals(keyFrame(false, ' '), release(57, KeyEvent.MOD_NONE));
        assertArrayEquals(keyFrame(false, 'a'), release(30, KeyEvent.MOD_NONE));
        assertArrayEquals(keyFrame(false, 0xff1b), release(1, KeyEvent.MOD_NONE));
    }

    @Test
    public void releaseOnlyUsesKeysymOfTheSameKeycode() {
        RfbInputEncoder encoder = encoder();
        assertArrayEquals(keyFrame(true, 'a'), encoder.encode(KeyEvent.press(30, KeyEvent.MOD_NONE, 0L), 640, 480));
        assertArrayEquals(keyFrame(true, ' '), encoder.encode(KeyEvent.press(57, KeyEvent.MOD_NONE, 0L), 640, 480));
        assertArrayEquals("unheld 's' uses its own mapping", keyFrame(false, 's'),
                encoder.encode(KeyEvent.release(31, KeyEvent.MOD_NONE, 0L), 640, 480));
        assertArrayEquals(keyFrame(false, 'a'), encoder.encode(KeyEvent.release(30, KeyEvent.MOD_NONE, 0L), 640, 480));
        assertArrayEquals(keyFrame(false, ' '), encoder.encode(KeyEvent.release(57, KeyEvent.MOD_NONE, 0L), 640, 480));
    }

    // ---------------------------------------------------------------- synthetic modifiers

    @Test
    public void synthesizesDeclaredModifierBeforeKeyPress() {
        assertArrayEquals(concat(keyFrame(true, 0xffe1), keyFrame(true, 'a')),
                press(30, KeyEvent.MOD_SHIFT));
    }

    @Test
    public void synthesizesDeclaredModifiersInFixedOrder() {
        int modifiers = KeyEvent.MOD_SHIFT | KeyEvent.MOD_CTRL | KeyEvent.MOD_ALT | KeyEvent.MOD_SUPER;
        assertArrayEquals(concat(
                        keyFrame(true, 0xffe1),
                        keyFrame(true, 0xffe3),
                        keyFrame(true, 0xffe9),
                        keyFrame(true, 0xffeb),
                        keyFrame(true, 'a')),
                press(30, modifiers));
    }

    @Test
    public void doesNotSynthesizeExplicitlyPressedModifier() {
        RfbInputEncoder encoder = encoder();
        assertArrayEquals("only the explicit shift press",
                keyFrame(true, 0xffe1),
                encoder.encode(KeyEvent.press(42, KeyEvent.MOD_SHIFT, 0L), 640, 480));
        assertArrayEquals("shift is already explicit, so only 'c' is emitted",
                keyFrame(true, 'c'),
                encoder.encode(KeyEvent.press(46, KeyEvent.MOD_SHIFT, 0L), 640, 480));
    }

    @Test
    public void doesNotRepeatModifierThatIsAlreadySynthetic() {
        RfbInputEncoder encoder = encoder();
        assertArrayEquals(concat(keyFrame(true, 0xffe1), keyFrame(true, 'a')),
                encoder.encode(KeyEvent.press(30, KeyEvent.MOD_SHIFT, 0L), 640, 480));
        assertArrayEquals("promoting the synthetic shift to explicit emits nothing",
                new byte[0],
                encoder.encode(KeyEvent.press(42, KeyEvent.MOD_SHIFT, 0L), 640, 480));
        assertArrayEquals("the promoted key still releases normally",
                keyFrame(false, 0xffe1),
                encoder.encode(KeyEvent.release(42, KeyEvent.MOD_NONE, 0L), 640, 480));
    }

    @Test
    public void releasesSyntheticModifierWhenDeclaredStateDrops() {
        RfbInputEncoder encoder = encoder();
        assertArrayEquals(concat(keyFrame(true, 0xffe1), keyFrame(true, 'a')),
                encoder.encode(KeyEvent.press(30, KeyEvent.MOD_SHIFT, 0L), 640, 480));
        assertArrayEquals("key release first, then the synthetic shift release",
                concat(keyFrame(false, 'a'), keyFrame(false, 0xffe1)),
                encoder.encode(KeyEvent.release(30, KeyEvent.MOD_NONE, 0L), 640, 480));
    }

    @Test
    public void ordersExplicitModifierAgainstDeclaredBits() {
        RfbInputEncoder encoder = encoder();
        assertArrayEquals(keyFrame(true, 0xffe1),
                encoder.encode(KeyEvent.press(42, KeyEvent.MOD_SHIFT, 0L), 640, 480));
        assertArrayEquals("declared shift is explicit, no duplicate",
                keyFrame(true, 'a'),
                encoder.encode(KeyEvent.press(30, KeyEvent.MOD_SHIFT, 0L), 640, 480));
        assertArrayEquals("shift is still declared down, so it is not released yet",
                keyFrame(false, 'a'),
                encoder.encode(KeyEvent.release(30, KeyEvent.MOD_SHIFT, 0L), 640, 480));
        assertArrayEquals(keyFrame(false, 0xffe1),
                encoder.encode(KeyEvent.release(42, KeyEvent.MOD_NONE, 0L), 640, 480));
    }

    // ---------------------------------------------------------------- text commit

    @Test
    public void textCommitEmitsPrintableAsciiPairs() {
        assertArrayEquals(concat(
                        keyFrame(true, 'A'), keyFrame(false, 'A'),
                        keyFrame(true, 'z'), keyFrame(false, 'z')),
                text("Az"));
        assertArrayEquals(concat(
                        keyFrame(true, ' '), keyFrame(false, ' '),
                        keyFrame(true, '~'), keyFrame(false, '~')),
                text(" ~"));
    }

    @Test
    public void textCommitMapsTabAndLineBreaks() {
        assertArrayEquals(concat(
                        keyFrame(true, 0xff09), keyFrame(false, 0xff09),
                        keyFrame(true, 0xff0d), keyFrame(false, 0xff0d),
                        keyFrame(true, 0xff0d), keyFrame(false, 0xff0d)),
                text("\t\n\r"));
    }

    @Test
    public void textCommitEncodesNonAsciiAsUnicodeKeysyms() {
        // U+4F60 and U+1F600 both use the 0x01000000 | codepoint form.
        assertArrayEquals(concat(
                        keyFrame(true, 0x01004F60), keyFrame(false, 0x01004F60),
                        keyFrame(true, 0x0101F600), keyFrame(false, 0x0101F600)),
                text("\u4F60\uD83D\uDE00"));
    }

    @Test
    public void textCommitLatchesHeldModifiersAroundPayload() {
        RfbInputEncoder encoder = encoder();
        assertArrayEquals(concat(keyFrame(true, 0xffe1), keyFrame(true, 'a')),
                encoder.encode(KeyEvent.press(30, KeyEvent.MOD_SHIFT, 0L), 640, 480));
        assertArrayEquals("release shift, send literal text, re-press shift",
                concat(
                        keyFrame(false, 0xffe1),
                        keyFrame(true, 'x'), keyFrame(false, 'x'),
                        keyFrame(true, 0xffe1)),
                encoder.encode(new TextCommitEvent("x", 0L), 640, 480));
        assertArrayEquals(concat(keyFrame(false, 'a'), keyFrame(false, 0xffe1)),
                encoder.encode(KeyEvent.release(30, KeyEvent.MOD_NONE, 0L), 640, 480));
    }

    // ---------------------------------------------------------------- pointer

    @Test
    public void absoluteMoveClampsToDisplayBounds() {
        RfbInputEncoder encoder = encoder();
        assertArrayEquals(pointerFrame(0, 5, 8), encoder.encode(PointerEvent.motion(5.4, 7.6, 0.0, 0.0, 0L), 100, 100));
        assertArrayEquals("negative coordinates clamp to zero, overflow clamps to size - 1",
                pointerFrame(0, 0, 99), encoder.encode(PointerEvent.motion(-10.0, 200.0, 0.0, 0.0, 0L), 100, 100));
        assertArrayEquals(pointerFrame(0, 99, 0), encoder.encode(PointerEvent.motion(99.6, -0.4, 0.0, 0.0, 0L), 100, 100));
    }

    @Test
    public void absoluteMoveRoundsCoordinatesAndFillsHighBytes() {
        // (300, 701) in a 1000x1000 display: 0x012C and 0x02BD.
        assertArrayEquals(new byte[] {5, 0, 0x01, 0x2c, 0x02, (byte) 0xbd},
                encoder().encode(PointerEvent.motion(300.4, 700.6, 0.0, 0.0, 0L), 1000, 1000));
    }

    @Test
    public void relativeMoveAccumulatesDeltasAndClamps() {
        RfbInputEncoder encoder = encoder();
        assertArrayEquals(pointerFrame(0, 10, 5),
                encoder.encode(PointerEvent.relativeMotion(10.0, 5.0, 0L), 100, 100));
        assertArrayEquals("accumulated deltas clamp to the display bounds",
                pointerFrame(0, 99, 99),
                encoder.encode(PointerEvent.relativeMotion(200.0, 200.0, 0L), 100, 100));
        assertArrayEquals("negative deltas clamp at zero",
                pointerFrame(0, 0, 0),
                encoder.encode(PointerEvent.relativeMotion(-500.0, -500.0, 0L), 100, 100));
    }

    @Test
    public void remapsPhysicalButtonMaskBits() {
        RfbInputEncoder encoder = encoder();
        assertArrayEquals("bit0 -> RFB button 1", pointerFrame(1, 0, 0),
                encoder.encode(PointerEvent.relativeMotion(0.0, 0.0, PointerEvent.BUTTON_PRIMARY, 0L), 100, 100));
        assertArrayEquals("bit1 -> RFB button 2", pointerFrame(4, 0, 0),
                encoder.encode(PointerEvent.relativeMotion(0.0, 0.0, PointerEvent.BUTTON_SECONDARY, 0L), 100, 100));
        assertArrayEquals("bit2 -> RFB button 3", pointerFrame(2, 0, 0),
                encoder.encode(PointerEvent.relativeMotion(0.0, 0.0, PointerEvent.BUTTON_TERTIARY, 0L), 100, 100));
        assertArrayEquals(pointerFrame(7, 0, 0),
                encoder.encode(PointerEvent.relativeMotion(0.0, 0.0, 7, 0L), 100, 100));
        assertArrayEquals(pointerFrame(0, 0, 0),
                encoder.encode(PointerEvent.relativeMotion(0.0, 0.0, 0, 0L), 100, 100));
    }

    @Test
    public void setsAndClearsButtonsOnDownAndUp() {
        RfbInputEncoder encoder = encoder();
        assertArrayEquals(pointerFrame(1, 10, 10),
                encoder.encode(PointerEvent.buttonDown(10, 10, PointerEvent.BUTTON_LEFT, 0L), 100, 100));
        assertArrayEquals(pointerFrame(3, 10, 10),
                encoder.encode(PointerEvent.buttonDown(10, 10, PointerEvent.BUTTON_MIDDLE, 0L), 100, 100));
        assertArrayEquals(pointerFrame(2, 10, 10),
                encoder.encode(PointerEvent.buttonUp(10, 10, PointerEvent.BUTTON_LEFT, 0L), 100, 100));
        assertArrayEquals(pointerFrame(0, 10, 10),
                encoder.encode(PointerEvent.buttonUp(10, 10, PointerEvent.BUTTON_MIDDLE, 0L), 100, 100));
        assertArrayEquals("right button is RFB button 2", pointerFrame(4, 10, 10),
                encoder.encode(PointerEvent.buttonDown(10, 10, PointerEvent.BUTTON_RIGHT, 0L), 100, 100));
    }

    // ---------------------------------------------------------------- wheel
    // RFC 6143 §7.5.5: every PointerEvent asserts "the pointer is now at (x, y)", so wheel packets
    // must carry a position. Wheel events carry deltas only (producers may fill x/y with a
    // placeholder, e.g. the relative-capture path), so the encoder reuses its tracked pointer
    // position; a scroll never relocates the cursor.

    @Test
    public void scrollUpSetsBit3AndFollowsWithPlainPointerPacket() {
        RfbInputEncoder encoder = encoder();
        encoder.encode(PointerEvent.motion(3, 4, 0.0, 0.0, 0L), 100, 100);
        assertArrayEquals(concat(pointerFrame(8, 3, 4), pointerFrame(0, 3, 4)),
                encoder.encode(PointerEvent.scroll(3, 4, 0.0, 1.0, 0L), 100, 100));
    }

    @Test
    public void scrollDownSetsBit4() {
        RfbInputEncoder encoder = encoder();
        encoder.encode(PointerEvent.motion(3, 4, 0.0, 0.0, 0L), 100, 100);
        assertArrayEquals(concat(pointerFrame(16, 3, 4), pointerFrame(0, 3, 4)),
                encoder.encode(PointerEvent.scroll(3, 4, 0.0, -1.0, 0L), 100, 100));
    }

    @Test
    public void scrollRightSetsBit6AndLeftSetsBit5() {
        RfbInputEncoder encoder = encoder();
        encoder.encode(PointerEvent.motion(3, 4, 0.0, 0.0, 0L), 100, 100);
        assertArrayEquals(concat(pointerFrame(64, 3, 4), pointerFrame(0, 3, 4)),
                encoder.encode(PointerEvent.scroll(3, 4, 1.0, 0.0, 0L), 100, 100));
        assertArrayEquals(concat(pointerFrame(32, 3, 4), pointerFrame(0, 3, 4)),
                encoder.encode(PointerEvent.scroll(3, 4, -1.0, 0.0, 0L), 100, 100));
    }

    @Test
    public void scrollEmitsOnePointerPairPerNotch() {
        RfbInputEncoder encoder = encoder();
        encoder.encode(PointerEvent.motion(3, 4, 0.0, 0.0, 0L), 100, 100);
        assertArrayEquals(concat(
                        pointerFrame(8, 3, 4), pointerFrame(0, 3, 4),
                        pointerFrame(8, 3, 4), pointerFrame(0, 3, 4)),
                encoder.encode(PointerEvent.scroll(3, 4, 0.0, 2.5, 0L), 100, 100));
    }

    @Test
    public void scrollIgnoresCarriedCoordinatesAndKeepsTrackedPointerPosition() {
        RfbInputEncoder encoder = encoder();
        encoder.encode(PointerEvent.motion(40, 30, 0.0, 0.0, 0L), 100, 100);
        // Placeholder coordinates (as sent by the relative-capture wheel path) must not teleport
        // the pointer, even though the wire packets themselves still need a position.
        assertArrayEquals(concat(pointerFrame(8, 40, 30), pointerFrame(0, 40, 30)),
                encoder.encode(PointerEvent.scroll(77, 88, 0.0, 1.0, 0L), 100, 100));
        // Before any absolute position is known, the wheel packets point at the tracked origin.
        assertArrayEquals(concat(pointerFrame(8, 0, 0), pointerFrame(0, 0, 0)),
                encoder().encode(PointerEvent.scroll(5, 1, 0.0, 1.0, 0L), 100, 100));
    }

    @Test
    public void scrollAccumulatesFractionalDeltas() {
        RfbInputEncoder encoder = encoder();
        encoder.encode(PointerEvent.motion(5, 7, 0.0, 0.0, 0L), 100, 100);
        assertEquals("0.4 is not enough for a notch", 0,
                encoder.encode(PointerEvent.scroll(99, 99, 0.0, 0.4, 0L), 100, 100).length);
        assertEquals("0.8 is still not enough", 0,
                encoder.encode(PointerEvent.scroll(99, 99, 0.0, 0.4, 0L), 100, 100).length);
        assertArrayEquals("1.2 emits exactly one notch pair at the tracked position",
                concat(pointerFrame(8, 5, 7), pointerFrame(0, 5, 7)),
                encoder.encode(PointerEvent.scroll(99, 99, 0.0, 0.4, 0L), 100, 100));
    }

    @Test
    public void scrollPreservesHeldButtonsInMask() {
        RfbInputEncoder encoder = encoder();
        encoder.encode(PointerEvent.buttonDown(3, 4, PointerEvent.BUTTON_LEFT, 0L), 100, 100);
        assertArrayEquals(concat(pointerFrame(9, 3, 4), pointerFrame(1, 3, 4)),
                encoder.encode(PointerEvent.scroll(3, 4, 0.0, 1.0, 0L), 100, 100));
    }

    // ---------------------------------------------------------------- supports(): keyboard

    @Test
    public void rejectsKeyEventsWhenKeyboardCapabilityIsOff() {
        assertFalse(RfbInputEncoder.supports(KeyEvent.press(30, KeyEvent.MOD_NONE, 0L), POINTER_ONLY));
        assertFalse(RfbInputEncoder.supports(KeyEvent.press(30, KeyEvent.MOD_NONE, 0L), NOTHING));
        assertTrue(RfbInputEncoder.supports(KeyEvent.press(30, KeyEvent.MOD_NONE, 0L), KEYBOARD_ONLY));
    }

    @Test
    public void acceptsLockStateBitsAndRejectsUnknownModifierBits() {
        assertFalse(RfbInputEncoder.supports(KeyEvent.press(200, KeyEvent.MOD_NONE, 0L), KEYBOARD_ONLY));
        // CAPS/NUM LOCK are lock-state bits: RFC 6143 §7.5.4 KeyEvent has no lock-state field and
        // the lock keys themselves travel as ordinary keycodes, so accepting the bits (and ignoring
        // them) keeps input flowing while a lock is engaged instead of dropping every keystroke.
        assertTrue(RfbInputEncoder.supports(KeyEvent.press(30, KeyEvent.MOD_CAPS_LOCK, 0L), KEYBOARD_ONLY));
        assertTrue(RfbInputEncoder.supports(KeyEvent.press(30, KeyEvent.MOD_NUM_LOCK, 0L), KEYBOARD_ONLY));
        assertTrue(RfbInputEncoder.supports(
                KeyEvent.press(30, KeyEvent.MOD_SHIFT | KeyEvent.MOD_CAPS_LOCK, 0L), KEYBOARD_ONLY));
        // Truly unknown modifier bits stay rejected.
        assertFalse(RfbInputEncoder.supports(KeyEvent.press(30, 0x40, 0L), KEYBOARD_ONLY));
        assertFalse(RfbInputEncoder.supports(
                KeyEvent.press(30, KeyEvent.MOD_SHIFT | 0x80, 0L), KEYBOARD_ONLY));
        assertTrue(RfbInputEncoder.supports(
                KeyEvent.press(30, KeyEvent.MOD_SHIFT | KeyEvent.MOD_CTRL | KeyEvent.MOD_ALT | KeyEvent.MOD_SUPER, 0L),
                KEYBOARD_ONLY));
    }

    @Test
    public void lockStateBitsProduceNoExtraWireFrames() {
        assertArrayEquals("caps lock state bit is ignored: only the bare key press is sent",
                keyFrame(true, 'a'), press(30, KeyEvent.MOD_CAPS_LOCK));
        assertArrayEquals(keyFrame(false, 'a'), release(30, KeyEvent.MOD_CAPS_LOCK));
        assertArrayEquals("num lock state bit does not perturb synthesized shift",
                concat(keyFrame(true, 0xffe1), keyFrame(true, 'a')),
                press(30, KeyEvent.MOD_SHIFT | KeyEvent.MOD_NUM_LOCK));
    }

    // ---------------------------------------------------------------- supports(): text

    @Test
    public void acceptsPrintableAsciiWithoutUnicodeTextCapability() {
        assertTrue(supportsText(" ", KEYBOARD_ONLY));
        assertTrue(supportsText("azAZ09 ~", KEYBOARD_ONLY));
        assertTrue(supportsText("\t", KEYBOARD_ONLY));
        assertTrue(supportsText("\n", KEYBOARD_ONLY));
        assertTrue(supportsText("\r", KEYBOARD_ONLY));
        assertTrue("unicodeText implies keyboard, so plain keyboard stays valid", supportsText("\u4F60", FULL));
    }

    @Test
    public void rejectsNonAsciiWithoutUnicodeTextCapability() {
        assertFalse(supportsText("\u4F60", KEYBOARD_ONLY));
        assertFalse(supportsText("\u0001", KEYBOARD_ONLY));
        assertFalse(supportsText("\u007f", KEYBOARD_ONLY));
        assertFalse(supportsText("\uD83D\uDE00", KEYBOARD_ONLY));
    }

    @Test
    public void rejectsEmptyText() {
        assertFalse(supportsText("", KEYBOARD_ONLY));
        assertFalse(supportsText("", FULL));
    }

    @Test
    public void enforcesCodePointLimit() {
        assertTrue(supportsText("a".repeat(1024), KEYBOARD_ONLY));
        assertFalse(supportsText("a".repeat(1025), KEYBOARD_ONLY));
        assertTrue("1024 supplementary code points use 2048 chars", supportsText("\uD83D\uDE00".repeat(1024), FULL));
        assertFalse(supportsText("\uD83D\uDE00".repeat(1025), FULL));
    }

    @Test
    public void rejectsLoneSurrogates() {
        assertFalse(supportsText("\uD800", FULL));
        assertFalse(supportsText("\uDC00", FULL));
        assertFalse(supportsText("a\uD800b", FULL));
        assertFalse(supportsText("a\uDC00", FULL));
    }

    @Test
    public void rejectsTextWhenNeitherCapabilityIsAvailable() {
        assertFalse(supportsText("a", NOTHING));
        assertFalse(supportsText("\u4F60", NOTHING));
        assertFalse(supportsText("\t", NOTHING));
    }

    // ---------------------------------------------------------------- supports(): pointer

    @Test
    public void rejectsPointerEventsWithoutPointerCapability() {
        assertFalse(RfbInputEncoder.supports(PointerEvent.motion(1, 1, 0, 0, 1L), KEYBOARD_ONLY));
        assertFalse(RfbInputEncoder.supports(PointerEvent.motion(1, 1, 0, 0, 1L), NOTHING));
        assertTrue(RfbInputEncoder.supports(PointerEvent.motion(1, 1, 0, 0, 1L), POINTER_ONLY));
    }

    @Test
    public void rejectsNonFinitePointerCoordinates() {
        assertFalse(supportsPointer(PointerEvent.motion(Double.NaN, 1, 0, 0, 0L)));
        assertFalse(supportsPointer(PointerEvent.motion(1, Double.POSITIVE_INFINITY, 0, 0, 0L)));
        assertFalse(supportsPointer(PointerEvent.motion(1, 1, Double.NaN, 0, 0L)));
        assertFalse(supportsPointer(PointerEvent.relativeMotion(1, Double.NEGATIVE_INFINITY, 0L)));
        assertFalse(supportsPointer(PointerEvent.scroll(1, 1, Double.POSITIVE_INFINITY, 0, 0L)));
    }

    @Test
    public void enforcesScrollDeltaLimit() {
        assertTrue(supportsPointer(PointerEvent.scroll(1, 1, 32.0, -32.0, 0L)));
        assertFalse(supportsPointer(PointerEvent.scroll(1, 1, 32.5, 0, 0L)));
        assertFalse(supportsPointer(PointerEvent.scroll(1, 1, 0, -33.0, 0L)));
        assertFalse(supportsPointer(PointerEvent.scroll(1, 1, 0, Double.NaN, 0L)));
    }

    @Test
    public void rejectsButtonCodesOutsideRfbRange() {
        assertTrue(supportsPointer(PointerEvent.buttonDown(1, 1, PointerEvent.BUTTON_LEFT, 0L)));
        assertTrue(supportsPointer(PointerEvent.buttonDown(1, 1, PointerEvent.BUTTON_MIDDLE, 0L)));
        assertTrue(supportsPointer(PointerEvent.buttonUp(1, 1, PointerEvent.BUTTON_RIGHT, 0L)));
        assertFalse(supportsPointer(PointerEvent.buttonDown(1, 1, 271, 0L)));
        assertFalse(supportsPointer(PointerEvent.buttonUp(1, 1, 275, 0L)));
        assertFalse(supportsPointer(PointerEvent.buttonDown(1, 1, PointerEvent.BUTTON_NONE, 0L)));
    }

    @Test
    public void rejectsMoveCarryingAButton() {
        assertTrue(supportsPointer(PointerEvent.motion(1, 1, 0, 0, 0L)));
        assertFalse(supportsPointer(new PointerEvent(
                PointerEvent.Action.MOVE, 1, 1, 0, 0, PointerEvent.BUTTON_LEFT, 0L)));
    }

    @Test
    public void rejectsRelativeMoveWithIllegalButtonMaskBits() {
        assertTrue(supportsPointer(PointerEvent.relativeMotion(1, 1, 0, 0L)));
        assertTrue(supportsPointer(PointerEvent.relativeMotion(1, 1, 7, 0L)));
        assertFalse(supportsPointer(PointerEvent.relativeMotion(1, 1, 8, 0L)));
        assertFalse(supportsPointer(PointerEvent.relativeMotion(1, 1, -1, 0L)));
    }

    @Test
    public void rejectsPointerEncodingWithNonPositiveFramebufferSize() {
        RfbInputEncoder encoder = encoder();
        // A non-positive framebuffer is not a legal RFB state (the decoder rejects such sizes), so
        // pointer encoding must fail fast instead of silently clamping the pointer to (0,0).
        assertThrows(IllegalArgumentException.class,
                () -> encoder.encode(PointerEvent.motion(1, 1, 0, 0, 0L), 0, 480));
        assertThrows(IllegalArgumentException.class,
                () -> encoder.encode(PointerEvent.motion(1, 1, 0, 0, 0L), 640, 0));
        assertThrows(IllegalArgumentException.class,
                () -> encoder.encode(PointerEvent.relativeMotion(1, 1, 0L), 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> encoder.encode(PointerEvent.buttonDown(1, 1, PointerEvent.BUTTON_LEFT, 0L), -1, 100));
        assertThrows(IllegalArgumentException.class,
                () -> encoder.encode(PointerEvent.scroll(1, 1, 0, 1, 0L), 100, -1));
    }

    @Test
    public void keyboardAndTextEncodingDoNotRequireFramebufferDimensions() {
        // KeyEvent/TextCommitEvent wire frames carry no coordinates, so dimensions are irrelevant.
        assertArrayEquals(keyFrame(true, 'a'),
                encoder().encode(KeyEvent.press(30, KeyEvent.MOD_NONE, 0L), 0, 0));
        assertArrayEquals(concat(keyFrame(true, 'x'), keyFrame(false, 'x')),
                encoder().encode(new TextCommitEvent("x", 0L), 0, 0));
    }

    // ---------------------------------------------------------------- supports(): non-events

    @Test
    public void rejectsNullAndForeignInputEvents() {
        assertFalse(RfbInputEncoder.supports(null, FULL));
        InputEvent foreign = new ForeignInputEvent();
        assertFalse(RfbInputEncoder.supports(foreign, FULL));
        assertThrows(IllegalArgumentException.class, () -> encoder().encode(foreign, 640, 480));
        assertThrows(IllegalArgumentException.class, () -> encoder().encode(null, 640, 480));
    }

    // ---------------------------------------------------------------- helpers

    private static RfbInputEncoder encoder() {
        return new RfbInputEncoder();
    }

    private static byte[] press(int keyCode) {
        return press(keyCode, KeyEvent.MOD_NONE);
    }

    private static byte[] press(int keyCode, int modifiers) {
        return encoder().encode(KeyEvent.press(keyCode, modifiers, 0L), 640, 480);
    }

    private static byte[] release(int keyCode, int modifiers) {
        return encoder().encode(KeyEvent.release(keyCode, modifiers, 0L), 640, 480);
    }

    private static byte[] text(String value) {
        return encoder().encode(new TextCommitEvent(value, 0L), 640, 480);
    }

    private static boolean supportsText(String value, InputCapabilities capabilities) {
        return RfbInputEncoder.supports(new TextCommitEvent(value, 0L), capabilities);
    }

    private static boolean supportsPointer(PointerEvent event) {
        return RfbInputEncoder.supports(event, POINTER_ONLY);
    }

    private static byte[] keyFrame(boolean down, int symbol) {
        return new byte[] {
            4,
            (byte) (down ? 1 : 0),
            0,
            0,
            (byte) (symbol >>> 24),
            (byte) (symbol >>> 16),
            (byte) (symbol >>> 8),
            (byte) symbol
        };
    }

    private static byte[] pointerFrame(int mask, int x, int y) {
        return new byte[] {5, (byte) mask, (byte) (x >>> 8), (byte) x, (byte) (y >>> 8), (byte) y};
    }

    private static byte[] concat(byte[]... frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] frame : frames) {
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    /** An InputEvent subtype the encoder must reject, since only key/text/pointer are RFB inputs. */
    private static final class ForeignInputEvent implements InputEvent {
        @Override
        public Type eventType() {
            return Type.GESTURE;
        }

        @Override
        public long timestampNs() {
            return 0L;
        }

        @Override
        public String serialize() {
            return "g 0\n";
        }
    }
}
