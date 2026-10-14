package io.github.nyzeep.orchestella.runtime.contract.display;

import io.github.nyzeep.orchestella.runtime.contract.InputEvent;
import io.github.nyzeep.orchestella.runtime.contract.KeyEvent;
import io.github.nyzeep.orchestella.runtime.contract.PointerEvent;
import io.github.nyzeep.orchestella.runtime.contract.TextCommitEvent;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Writer-confined state. RFB has keysym/pointer support, not touch or Wayland text-input-v3. */
final class RfbInputEncoder {
    private static final int MAX_TEXT_CODE_POINTS = 1024;
    private static final int MODIFIERS = KeyEvent.MOD_SHIFT | KeyEvent.MOD_CTRL | KeyEvent.MOD_ALT | KeyEvent.MOD_SUPER;
    private static final int[] MODIFIER_CODES = {42, 29, 56, 125};
    private final Map<Integer, Integer> held = new LinkedHashMap<>();
    private final Set<Integer> syntheticModifiers = new LinkedHashSet<>();
    private double x;
    private double y;
    private double wheelX;
    private double wheelY;
    private int buttons;

    static boolean supports(InputEvent event, RfbDisplayChannel.InputCapabilities capabilities) {
        if (event instanceof KeyEvent key) {
            return capabilities.keyboard() && keysym(key.keyCode()) != 0 && (key.modifiers() & ~MODIFIERS) == 0;
        }
        if (event instanceof TextCommitEvent text) {
            if ((!capabilities.unicodeText() && !capabilities.keyboard()) || text.text().isEmpty() || text.text().length() > MAX_TEXT_CODE_POINTS * 2) {
                return false;
            }
            int count = 0;
            for (int offset = 0; offset < text.text().length();) {
                int codepoint = text.text().codePointAt(offset);
                if ((codepoint >= 0xd800 && codepoint <= 0xdfff) || ++count > MAX_TEXT_CODE_POINTS
                        || (!capabilities.unicodeText() && !(codepoint >= 32 && codepoint <= 126 || codepoint == 9 || codepoint == 10 || codepoint == 13))) {
                    return false;
                }
                offset += Character.charCount(codepoint);
            }
            return true;
        }
        if (event instanceof PointerEvent pointer) {
            if (!capabilities.pointer() || !Double.isFinite(pointer.x()) || !Double.isFinite(pointer.y())
                    || !Double.isFinite(pointer.deltaX()) || !Double.isFinite(pointer.deltaY())) {
                return false;
            }
            return switch (pointer.action()) {
                case BUTTON_DOWN, BUTTON_UP -> pointer.button() >= 272 && pointer.button() <= 274;
                case RELATIVE_MOVE -> (pointer.buttonMask() & ~7) == 0;
                case SCROLL -> Math.abs(pointer.deltaX()) <= 32 && Math.abs(pointer.deltaY()) <= 32;
                case MOVE -> pointer.button() == PointerEvent.BUTTON_NONE;
            };
        }
        return false;
    }

    byte[] encode(InputEvent event, int width, int height) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (event instanceof KeyEvent key) {
            keyboard(bytes, key);
        } else if (event instanceof TextCommitEvent text) {
            text(bytes, text.text());
        } else if (event instanceof PointerEvent pointer) {
            pointer(bytes, pointer, width, height);
        } else {
            throw new IllegalArgumentException("unsupported input event");
        }
        return bytes.toByteArray();
    }

    private void keyboard(ByteArrayOutputStream bytes, KeyEvent event) {
        int code = event.keyCode();
        int symbol = keysym(code);
        int modifier = modifier(code);
        if (event.isPressed()) {
            if (modifier != 0) {
                held.put(code, symbol);
                if (!syntheticModifiers.remove(code)) { key(bytes, true, symbol); }
                synchronizeModifiers(bytes, event.modifiers());
            } else {
                synchronizeModifiers(bytes, event.modifiers());
                held.put(code, symbol);
                key(bytes, true, symbol);
            }
        } else {
            Integer pressed = held.remove(code);
            syntheticModifiers.remove(code);
            key(bytes, false, pressed == null ? symbol : pressed);
            synchronizeModifiers(bytes, event.modifiers() & ~modifier);
        }
    }

    private void synchronizeModifiers(ByteArrayOutputStream bytes, int desired) {
        int explicit = 0;
        for (int code : held.keySet()) { explicit |= modifier(code); }
        for (int code : MODIFIER_CODES) {
            boolean needSynthetic = (desired & modifier(code)) != 0 && (explicit & modifier(code)) == 0;
            if (needSynthetic && syntheticModifiers.add(code)) {
                key(bytes, true, keysym(code));
            } else if (!needSynthetic && syntheticModifiers.remove(code)) {
                key(bytes, false, keysym(code));
            }
        }
    }

    private void text(ByteArrayOutputStream bytes, String value) {
        Set<Integer> modifiers = new LinkedHashSet<>(syntheticModifiers);
        for (int code : held.keySet()) {
            if (modifier(code) != 0) { modifiers.add(code); }
        }
        // Committed text is literal even while a virtual Ctrl/Super/Shift latch is held.
        for (int code : modifiers) { key(bytes, false, keysym(code)); }
        for (int offset = 0; offset < value.length();) {
            int codepoint = value.codePointAt(offset);
            int symbol = switch (codepoint) {
                case 9 -> 0xff09;
                case 10, 13 -> 0xff0d;
                default -> codepoint >= 32 && codepoint <= 126 ? codepoint : 0x01000000 | codepoint;
            };
            key(bytes, true, symbol);
            key(bytes, false, symbol);
            offset += Character.charCount(codepoint);
        }
        for (int code : modifiers) { key(bytes, true, keysym(code)); }
    }

    private void pointer(ByteArrayOutputStream bytes, PointerEvent event, int width, int height) {
        if (event.action() == PointerEvent.Action.RELATIVE_MOVE) {
            x = clamp(x + event.deltaX(), width);
            y = clamp(y + event.deltaY(), height);
            buttons = ((event.buttonMask() & 1) != 0 ? 1 : 0)
                    | ((event.buttonMask() & 2) != 0 ? 4 : 0) | ((event.buttonMask() & 4) != 0 ? 2 : 0);
        } else {
            x = clamp(event.x(), width);
            y = clamp(event.y(), height);
        }
        if (event.action() == PointerEvent.Action.BUTTON_DOWN) {
            buttons |= button(event.button());
        } else if (event.action() == PointerEvent.Action.BUTTON_UP) {
            buttons &= ~button(event.button());
        }
        if (event.action() != PointerEvent.Action.SCROLL) {
            pointerPacket(bytes, buttons);
            return;
        }
        wheelY += event.deltaY();
        wheelX += event.deltaX();
        // Positive Y is up; positive X is right. Fractional wheel motion is accumulated.
        while (Math.abs(wheelY) >= 1) {
            pointerPacket(bytes, buttons | (wheelY > 0 ? 8 : 16));
            pointerPacket(bytes, buttons);
            wheelY += wheelY > 0 ? -1 : 1;
        }
        while (Math.abs(wheelX) >= 1) {
            pointerPacket(bytes, buttons | (wheelX > 0 ? 64 : 32));
            pointerPacket(bytes, buttons);
            wheelX += wheelX > 0 ? -1 : 1;
        }
    }

    private void pointerPacket(ByteArrayOutputStream bytes, int mask) {
        bytes.write(5);
        bytes.write(mask);
        int px = (int) Math.round(x);
        int py = (int) Math.round(y);
        bytes.write(px >>> 8);
        bytes.write(px);
        bytes.write(py >>> 8);
        bytes.write(py);
    }

    private static double clamp(double value, int size) { return Math.max(0, Math.min(size - 1.0, value)); }
    private static int button(int code) { return code == 272 ? 1 : code == 273 ? 4 : 2; }

    private static int modifier(int code) {
        return switch (code) {
            case 42, 54 -> KeyEvent.MOD_SHIFT;
            case 29, 97 -> KeyEvent.MOD_CTRL;
            case 56, 100 -> KeyEvent.MOD_ALT;
            case 125, 126 -> KeyEvent.MOD_SUPER;
            default -> 0;
        };
    }

    private static void key(ByteArrayOutputStream bytes, boolean down, int symbol) {
        bytes.write(4);
        bytes.write(down ? 1 : 0);
        bytes.write(0);
        bytes.write(0);
        bytes.write(symbol >>> 24);
        bytes.write(symbol >>> 16);
        bytes.write(symbol >>> 8);
        bytes.write(symbol);
    }

    /** Explicit evdev mapping to X11 base keysyms (US printable layout); unknown keys are rejected. */
    private static int keysym(int code) {
        if (code >= 2 && code <= 11) { return "1234567890".charAt(code - 2); }
        if (code >= 16 && code <= 25) { return "qwertyuiop".charAt(code - 16); }
        if (code >= 30 && code <= 38) { return "asdfghjkl".charAt(code - 30); }
        if (code >= 44 && code <= 50) { return "zxcvbnm".charAt(code - 44); }
        if (code >= 59 && code <= 68) { return 0xffbe + code - 59; }
        if (code >= 183 && code <= 194) { return 0xffca + code - 183; }
        return switch (code) {
            case 1 -> 0xff1b;
            case 12 -> '-';
            case 13 -> '=';
            case 14 -> 0xff08;
            case 15 -> 0xff09;
            case 26 -> '[';
            case 27 -> ']';
            case 28 -> 0xff0d;
            case 29 -> 0xffe3;
            case 39 -> ';';
            case 40 -> '\'';
            case 41 -> '`';
            case 42 -> 0xffe1;
            case 43 -> '\\';
            case 51 -> ',';
            case 52 -> '.';
            case 53 -> '/';
            case 54 -> 0xffe2;
            case 55 -> 0xffaa;
            case 56 -> 0xffe9;
            case 57 -> ' ';
            case 58 -> 0xffe5;
            case 69 -> 0xff7f;
            case 70 -> 0xff14;
            case 71 -> 0xffb7;
            case 72 -> 0xffb8;
            case 73 -> 0xffb9;
            case 74 -> 0xffad;
            case 75 -> 0xffb4;
            case 76 -> 0xffb5;
            case 77 -> 0xffb6;
            case 78 -> 0xffab;
            case 79 -> 0xffb1;
            case 80 -> 0xffb2;
            case 81 -> 0xffb3;
            case 82 -> 0xffb0;
            case 83 -> 0xffae;
            case 86 -> '<';
            case 87 -> 0xffc8;
            case 88 -> 0xffc9;
            case 96 -> 0xff8d;
            case 97 -> 0xffe4;
            case 98 -> 0xffaf;
            case 99 -> 0xff61;
            case 100 -> 0xffea;
            case 102 -> 0xff50;
            case 103 -> 0xff52;
            case 104 -> 0xff55;
            case 105 -> 0xff51;
            case 106 -> 0xff53;
            case 107 -> 0xff57;
            case 108 -> 0xff54;
            case 109 -> 0xff56;
            case 110 -> 0xff63;
            case 111 -> 0xffff;
            case 119 -> 0xff13;
            case 125 -> 0xffeb;
            case 126 -> 0xffec;
            case 127 -> 0xff67;
            default -> 0;
        };
    }
}
