package io.github.nyzeep.orchestella.runtime.contract;

/** Contract test suite for strategy-neutral InputEvent serialization and deserialization. */
public final class InputEventSerializationTest {
    public static void main(String[] arguments) {
        pointerMotionRoundTrip();
        pointerButtonRoundTrip();
        pointerScrollRoundTrip();
        touchEventRoundTrip();
        touchEventValidatesNormalizedCoordinatesAndId();
        keyEventRoundTrip();
        keyEventModifierChecks();
        textCommitEventRoundTripWithAsciiAndUnicode();
        textCommitEventRoundTripWithChineseAndEmoji();
        gestureEventRoundTrip();
        rejectsOversizedSerializedPayload();
        rejectsOversizedDeserializedWire();
        rejectsMalformedWireStrings();
        System.out.println("INPUT_EVENT_SERIALIZATION_TEST_OK");
    }

    private static void gestureEventRoundTrip() {
        GestureEvent pinch = GestureEvent.pinch(1.5, 400.0, 300.0, 7000000L);
        assertEquals(InputEvent.Type.GESTURE, pinch.eventType(), "gesture type");
        assertEquals(GestureEvent.GestureType.PINCH, pinch.gestureType(), "pinch gestureType");
        assertEquals(1.5, pinch.scaleFactor(), "scaleFactor");
        assertEquals(400.0, pinch.focusX(), "focusX");
        assertEquals(300.0, pinch.focusY(), "focusY");
        assertEquals(pinch, InputEventSerialization.deserialize(pinch.serialize()), "pinch roundtrip");

        GestureEvent tap = GestureEvent.twoFingerTap(120.0, 80.0, 7500000L);
        assertEquals(GestureEvent.GestureType.TWO_FINGER_TAP, tap.gestureType(), "twoFingerTap gestureType");
        assertEquals(tap, InputEventSerialization.deserialize(tap.serialize()), "two finger tap roundtrip");
    }

    private static void pointerMotionRoundTrip() {
        PointerEvent original = PointerEvent.motion(100.5, 200.25, 12.0, -8.5, 1000000L);
        assertEquals(InputEvent.Type.POINTER, original.eventType(), "pointer type");
        assertEquals(PointerEvent.Action.MOVE, original.action(), "move action");
        assertEquals(100.5, original.x(), "x");
        assertEquals(200.25, original.y(), "y");
        assertEquals(12.0, original.deltaX(), "deltaX");
        assertEquals(-8.5, original.deltaY(), "deltaY");
        assertEquals(0, original.button(), "no button on move");
        assertEquals(1000000L, original.timestampNs(), "timestampNs");

        String wire = original.serialize();
        InputEvent deserialized = InputEventSerialization.deserialize(wire);

        assertTrue(deserialized instanceof PointerEvent, "deserialized is PointerEvent");
        PointerEvent p = (PointerEvent) deserialized;
        assertEquals(original, p, "equals check");
        assertEquals(original.hashCode(), p.hashCode(), "hashCode check");
    }

    private static void pointerButtonRoundTrip() {
        PointerEvent down = PointerEvent.buttonDown(500.0, 300.0, PointerEvent.BUTTON_LEFT, 2000000L);
        assertEquals(PointerEvent.Action.BUTTON_DOWN, down.action(), "button down action");
        assertEquals(PointerEvent.BUTTON_LEFT, down.button(), "left button");
        assertEquals(down, InputEventSerialization.deserialize(down.serialize()), "button down roundtrip");

        PointerEvent up = PointerEvent.buttonUp(500.0, 300.0, PointerEvent.BUTTON_RIGHT, 2500000L);
        assertEquals(PointerEvent.Action.BUTTON_UP, up.action(), "button up action");
        assertEquals(PointerEvent.BUTTON_RIGHT, up.button(), "right button");
        assertEquals(up, InputEventSerialization.deserialize(up.serialize()), "button up roundtrip");
    }

    private static void pointerScrollRoundTrip() {
        PointerEvent scroll = PointerEvent.scroll(640.0, 480.0, 0.0, 15.0, 3000000L);
        assertEquals(PointerEvent.Action.SCROLL, scroll.action(), "scroll action");
        assertEquals(0.0, scroll.deltaX(), "scroll deltaX");
        assertEquals(15.0, scroll.deltaY(), "scroll deltaY");
        assertEquals(scroll, InputEventSerialization.deserialize(scroll.serialize()), "scroll roundtrip");
    }

    private static void touchEventRoundTrip() {
        TouchEvent down = TouchEvent.down(0, 0.25, 0.75, 4000000L);
        assertEquals(InputEvent.Type.TOUCH, down.eventType(), "touch type");
        assertEquals(TouchEvent.Action.DOWN, down.action(), "touch down action");
        assertEquals(0, down.pointerId(), "pointerId");
        assertEquals(0.25, down.x(), "x normalized");
        assertEquals(0.75, down.y(), "y normalized");
        assertEquals(down, InputEventSerialization.deserialize(down.serialize()), "touch down roundtrip");

        TouchEvent move = TouchEvent.move(1, 0.5, 0.5, 4100000L);
        assertEquals(move, InputEventSerialization.deserialize(move.serialize()), "touch move roundtrip");

        TouchEvent up = TouchEvent.up(0, 0.3, 0.8, 4200000L);
        assertEquals(up, InputEventSerialization.deserialize(up.serialize()), "touch up roundtrip");
    }

    private static void touchEventValidatesNormalizedCoordinatesAndId() {
        assertThrows(IllegalArgumentException.class,
                () -> TouchEvent.down(-1, 0.5, 0.5, 1000L),
                "negative pointerId must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> TouchEvent.down(0, -0.01, 0.5, 1000L),
                "x < 0 must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> TouchEvent.down(0, 1.01, 0.5, 1000L),
                "x > 1 must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> TouchEvent.down(0, 0.5, -0.01, 1000L),
                "y < 0 must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> TouchEvent.down(0, 0.5, 1.01, 1000L),
                "y > 1 must be rejected");
    }

    private static void keyEventRoundTrip() {
        KeyEvent down = KeyEvent.press(KeyEvent.KEY_A, KeyEvent.MOD_CTRL | KeyEvent.MOD_SHIFT, 5000000L);
        assertEquals(InputEvent.Type.KEY, down.eventType(), "key type");
        assertEquals(KeyEvent.Action.PRESS, down.action(), "press action");
        assertEquals(KeyEvent.KEY_A, down.keyCode(), "key code");
        assertEquals(KeyEvent.MOD_CTRL | KeyEvent.MOD_SHIFT, down.modifiers(), "modifiers");
        assertEquals(down, InputEventSerialization.deserialize(down.serialize()), "key press roundtrip");

        KeyEvent up = KeyEvent.release(KeyEvent.KEY_ESC, 0, 5100000L);
        assertEquals(KeyEvent.Action.RELEASE, up.action(), "release action");
        assertEquals(KeyEvent.KEY_ESC, up.keyCode(), "esc key code");
        assertEquals(0, up.modifiers(), "no modifiers");
        assertEquals(up, InputEventSerialization.deserialize(up.serialize()), "key release roundtrip");
    }

    private static void keyEventModifierChecks() {
        int mods = KeyEvent.MOD_CTRL | KeyEvent.MOD_ALT | KeyEvent.MOD_SUPER;
        KeyEvent event = KeyEvent.press(KeyEvent.KEY_ENTER, mods, 6000000L);
        assertTrue(event.hasModifier(KeyEvent.MOD_CTRL), "has ctrl");
        assertTrue(event.hasModifier(KeyEvent.MOD_ALT), "has alt");
        assertTrue(event.hasModifier(KeyEvent.MOD_SUPER), "has super");
        assertFalse(event.hasModifier(KeyEvent.MOD_SHIFT), "no shift");
    }

    private static void textCommitEventRoundTripWithAsciiAndUnicode() {
        TextCommitEvent ascii = new TextCommitEvent("ls -la /home/droidlayer\n", 7000000L);
        assertEquals(InputEvent.Type.TEXT_COMMIT, ascii.eventType(), "text commit type");
        assertEquals("ls -la /home/droidlayer\n", ascii.text(), "text content");

        String wire = ascii.serialize();
        InputEvent deserialized = InputEventSerialization.deserialize(wire);
        assertEquals(ascii, deserialized, "ascii text commit roundtrip");
    }

    private static void textCommitEventRoundTripWithChineseAndEmoji() {
        String complexText = "你好，Linux 世界！🚀 终端与图形桌面 💻 100% 不掉字。";
        TextCommitEvent event = new TextCommitEvent(complexText, 8000000L);
        assertEquals(complexText, event.text(), "unicode complex text");

        String wire = event.serialize();
        InputEvent deserialized = InputEventSerialization.deserialize(wire);
        assertTrue(deserialized instanceof TextCommitEvent, "is TextCommitEvent");
        assertEquals(complexText, ((TextCommitEvent) deserialized).text(), "deserialized unicode string matches exactly");
        assertEquals(event, deserialized, "equals check");
    }

    private static void rejectsOversizedSerializedPayload() {
        // String that exceeds 2048 chars when serialized
        String hugeString = "a".repeat(2040);
        assertThrows(IllegalArgumentException.class,
                () -> new TextCommitEvent(hugeString, 9000000L).serialize(),
                "serializing payload > 2048 characters must be rejected");
    }

    private static void rejectsOversizedDeserializedWire() {
        String oversizedWire = "INPUT/KEY PRESS 30 0 1000\n" + "x".repeat(2050);
        assertThrows(IllegalArgumentException.class,
                () -> InputEventSerialization.deserialize(oversizedWire),
                "deserializing wire > 2048 characters must be rejected");
    }

    private static void rejectsMalformedWireStrings() {
        String[] malformed = new String[] {
            null,
            "",
            "   ",
            "UNKNOWN/EVENT 1 2 3\n",
            "INPUT/POINTER\n",
            "INPUT/POINTER MOVE 100\n",
            "INPUT/POINTER MOVE abc def 0 0 0 100\n",
            "INPUT/TOUCH\n",
            "INPUT/TOUCH DOWN 0 2.5 0.5 100\n",
            "INPUT/KEY\n",
            "INPUT/KEY INVALID 30 0 100\n",
            "INPUT/TEXT\n",
            "INPUT/TEXT malformed-base64:??? 100\n"
        };

        for (String bad : malformed) {
            assertThrows(IllegalArgumentException.class,
                    () -> InputEventSerialization.deserialize(bad),
                    "malformed wire must be rejected: " + bad);
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static <T extends Throwable> T assertThrows(
            Class<T> type, ThrowingRunnable operation, String description) {
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return type.cast(throwable);
            }
            throw new AssertionError(description + ": unexpected exception", throwable);
        }
        throw new AssertionError(description + ": expected exception " + type.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
