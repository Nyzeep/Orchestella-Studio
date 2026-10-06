package io.github.nyzeep.droidlayer.runtime.contract;

/**
 * Tests strategy-neutral relative pointer motion modeling, button mask flags,
 * and lossless wire serialization for unconstrained pointer capture.
 */
public final class PointerEventRelativeMotionTest {
    public static void main(String[] arguments) {
        verifiesPhysicalButtonMaskConstants();
        constructsRelativeMotionWithDisplacementAndButtons();
        distinguishesRelativeMotionFromAbsoluteMove();
        serializesAndDeserializesRelativeMotionLosslessly();
        preservesCombinedButtonMasksAcrossSerialization();
        preservesBackwardCompatibilityForStandardPointerEvents();
        System.out.println("POINTER_EVENT_RELATIVE_MOTION_TEST_OK");
    }

    private static void verifiesPhysicalButtonMaskConstants() {
        assertEquals(1, PointerEvent.BUTTON_PRIMARY, "BUTTON_PRIMARY mask = 1");
        assertEquals(2, PointerEvent.BUTTON_SECONDARY, "BUTTON_SECONDARY mask = 2");
        assertEquals(4, PointerEvent.BUTTON_TERTIARY, "BUTTON_TERTIARY mask = 4");

        // Linux evdev codes
        assertEquals(272, PointerEvent.BUTTON_LEFT, "Linux BTN_LEFT");
        assertEquals(273, PointerEvent.BUTTON_RIGHT, "Linux BTN_RIGHT");
        assertEquals(274, PointerEvent.BUTTON_MIDDLE, "Linux BTN_MIDDLE");

        assertEquals(PointerEvent.BUTTON_LEFT, PointerEvent.toLinuxButton(PointerEvent.BUTTON_PRIMARY), "primary to left");
        assertEquals(PointerEvent.BUTTON_RIGHT, PointerEvent.toLinuxButton(PointerEvent.BUTTON_SECONDARY), "secondary to right");
        assertEquals(PointerEvent.BUTTON_MIDDLE, PointerEvent.toLinuxButton(PointerEvent.BUTTON_TERTIARY), "tertiary to middle");
        assertEquals(PointerEvent.BUTTON_NONE, PointerEvent.toLinuxButton(0), "zero mask to none");

        assertEquals(PointerEvent.BUTTON_PRIMARY, PointerEvent.fromLinuxButton(PointerEvent.BUTTON_LEFT), "left to primary");
        assertEquals(PointerEvent.BUTTON_SECONDARY, PointerEvent.fromLinuxButton(PointerEvent.BUTTON_RIGHT), "right to secondary");
        assertEquals(PointerEvent.BUTTON_TERTIARY, PointerEvent.fromLinuxButton(PointerEvent.BUTTON_MIDDLE), "middle to tertiary");
        assertEquals(0, PointerEvent.fromLinuxButton(0), "unknown to 0");
    }

    private static void constructsRelativeMotionWithDisplacementAndButtons() {
        PointerEvent rel = PointerEvent.relativeMotion(15.5, -24.25, PointerEvent.BUTTON_PRIMARY, 1000000L);
        assertTrue(rel.isRelativeMotion(), "is relative motion");
        assertEquals(PointerEvent.Action.RELATIVE_MOVE, rel.action(), "action is RELATIVE_MOVE");
        assertEquals(15.5, rel.deltaX(), "deltaX");
        assertEquals(-24.25, rel.deltaY(), "deltaY");
        assertEquals(0.0, rel.x(), "x is 0.0 in relative mode");
        assertEquals(0.0, rel.y(), "y is 0.0 in relative mode");
        assertEquals(PointerEvent.BUTTON_PRIMARY, rel.button(), "button code/mask");
        assertEquals(PointerEvent.BUTTON_PRIMARY, rel.buttonMask(), "buttonMask alias");
        assertTrue(rel.hasButton(PointerEvent.BUTTON_PRIMARY), "has primary button");
        assertFalse(rel.hasButton(PointerEvent.BUTTON_SECONDARY), "no secondary button");
        assertFalse(rel.hasButton(PointerEvent.BUTTON_TERTIARY), "no tertiary button");
        assertEquals(1000000L, rel.timestampNs(), "timestamp");

        PointerEvent relNoButton = PointerEvent.relativeMotion(5.0, 10.0, 2000000L);
        assertTrue(relNoButton.isRelativeMotion(), "is relative motion without button");
        assertEquals(PointerEvent.BUTTON_NONE, relNoButton.button(), "button none");
        assertFalse(relNoButton.hasButton(PointerEvent.BUTTON_PRIMARY), "no button pressed");
    }

    private static void distinguishesRelativeMotionFromAbsoluteMove() {
        PointerEvent absMove = PointerEvent.motion(500.0, 300.0, 2.0, -1.0, 1500000L);
        assertFalse(absMove.isRelativeMotion(), "absolute move is not relative motion");

        PointerEvent buttonDown = PointerEvent.buttonDown(500.0, 300.0, PointerEvent.BUTTON_LEFT, 1600000L);
        assertFalse(buttonDown.isRelativeMotion(), "button down is not relative motion");

        PointerEvent scroll = PointerEvent.scroll(500.0, 300.0, 0.0, 15.0, 1700000L);
        assertFalse(scroll.isRelativeMotion(), "scroll is not relative motion");
    }

    private static void serializesAndDeserializesRelativeMotionLosslessly() {
        PointerEvent original = PointerEvent.relativeMotion(123.456, -789.012, PointerEvent.BUTTON_PRIMARY, 987654321L);
        String wire = InputEventSerialization.serialize(original);

        assertTrue(wire.startsWith("INPUT/POINTER RELATIVE_MOVE "), "wire starts with correct prefix and action");
        assertTrue(wire.endsWith("\n"), "wire line ends with newline");

        InputEvent deserialized = InputEventSerialization.deserialize(wire);
        assertTrue(deserialized instanceof PointerEvent, "deserialized type is PointerEvent");

        PointerEvent p = (PointerEvent) deserialized;
        assertTrue(p.isRelativeMotion(), "deserialized event is relative motion");
        assertEquals(PointerEvent.Action.RELATIVE_MOVE, p.action(), "deserialized action");
        assertEquals(123.456, p.deltaX(), "deserialized deltaX");
        assertEquals(-789.012, p.deltaY(), "deserialized deltaY");
        assertEquals(0.0, p.x(), "deserialized x");
        assertEquals(0.0, p.y(), "deserialized y");
        assertEquals(PointerEvent.BUTTON_PRIMARY, p.button(), "deserialized button");
        assertEquals(987654321L, p.timestampNs(), "deserialized timestamp");
        assertEquals(original, p, "original equals deserialized");
        assertEquals(original.hashCode(), p.hashCode(), "hash code matches");
    }

    private static void preservesCombinedButtonMasksAcrossSerialization() {
        int combinedMask = PointerEvent.BUTTON_PRIMARY | PointerEvent.BUTTON_SECONDARY;
        PointerEvent original = PointerEvent.relativeMotion(-30.5, 42.0, combinedMask, 5555555L);
        String wire = InputEventSerialization.serialize(original);

        InputEvent deserialized = InputEventSerialization.deserialize(wire);
        PointerEvent p = (PointerEvent) deserialized;

        assertTrue(p.hasButton(PointerEvent.BUTTON_PRIMARY), "has primary button");
        assertTrue(p.hasButton(PointerEvent.BUTTON_SECONDARY), "has secondary button");
        assertFalse(p.hasButton(PointerEvent.BUTTON_TERTIARY), "does not have tertiary button");
        assertEquals(combinedMask, p.buttonMask(), "combined button mask preserved");
        assertEquals(original, p, "equals check with combined mask");
    }

    private static void preservesBackwardCompatibilityForStandardPointerEvents() {
        PointerEvent originalMotion = PointerEvent.motion(640.0, 480.0, 5.0, -3.0, 123456L);
        String motionWire = InputEventSerialization.serialize(originalMotion);
        InputEvent roundtripMotion = InputEventSerialization.deserialize(motionWire);
        assertEquals(originalMotion, roundtripMotion, "motion roundtrip");
        assertFalse(((PointerEvent) roundtripMotion).isRelativeMotion(), "motion is not relative");

        PointerEvent originalDown = PointerEvent.buttonDown(100.0, 200.0, PointerEvent.BUTTON_LEFT, 234567L);
        String downWire = InputEventSerialization.serialize(originalDown);
        InputEvent roundtripDown = InputEventSerialization.deserialize(downWire);
        assertEquals(originalDown, roundtripDown, "button down roundtrip");

        PointerEvent originalUp = PointerEvent.buttonUp(100.0, 200.0, PointerEvent.BUTTON_RIGHT, 345678L);
        String upWire = InputEventSerialization.serialize(originalUp);
        InputEvent roundtripUp = InputEventSerialization.deserialize(upWire);
        assertEquals(originalUp, roundtripUp, "button up roundtrip");

        PointerEvent originalScroll = PointerEvent.scroll(100.0, 200.0, 0.0, 10.0, 456789L);
        String scrollWire = InputEventSerialization.serialize(originalScroll);
        InputEvent roundtripScroll = InputEventSerialization.deserialize(scrollWire);
        assertEquals(originalScroll, roundtripScroll, "scroll roundtrip");
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
}
