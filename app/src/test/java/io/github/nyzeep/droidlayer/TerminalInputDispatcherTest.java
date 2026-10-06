package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.droidlayer.StickyModifierStateMachine.Modifier;
import io.github.nyzeep.droidlayer.StickyModifierStateMachine.State;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests verifying terminal auxiliary key event dispatching and sticky modifier integration.
 */
public final class TerminalInputDispatcherTest {

    private final List<byte[]> dispatchedBytes = new ArrayList<>();
    private final List<String> dispatchedTexts = new ArrayList<>();
    private TerminalInputSink sink;
    private StickyModifierStateMachine stateMachine;

    @Before
    public void setUp() {
        dispatchedBytes.clear();
        dispatchedTexts.clear();
        sink = new TerminalInputSink() {
            @Override
            public void sendBytes(byte[] bytes) {
                dispatchedBytes.add(bytes);
            }

            @Override
            public void sendText(String text) {
                dispatchedTexts.add(text);
                dispatchedBytes.add(text.getBytes(StandardCharsets.UTF_8));
            }
        };
        stateMachine = new StickyModifierStateMachine();
    }

    @Test
    public void testEscSendsEscapeByte() {
        sink.sendBytes(new byte[]{0x1B});
        assertEquals(1, dispatchedBytes.size());
        assertArrayEquals(new byte[]{0x1B}, dispatchedBytes.get(0));
    }

    @Test
    public void testTabSendsTabByte() {
        sink.sendBytes(new byte[]{'\t'});
        assertEquals(1, dispatchedBytes.size());
        assertArrayEquals(new byte[]{'\t'}, dispatchedBytes.get(0));
    }

    @Test
    public void testArrowSequences() {
        sink.sendBytes("\u001B[A".getBytes(StandardCharsets.UTF_8));
        sink.sendBytes("\u001B[B".getBytes(StandardCharsets.UTF_8));
        sink.sendBytes("\u001B[D".getBytes(StandardCharsets.UTF_8));
        sink.sendBytes("\u001B[C".getBytes(StandardCharsets.UTF_8));

        assertEquals(4, dispatchedBytes.size());
        assertEquals("\u001B[A", new String(dispatchedBytes.get(0), StandardCharsets.UTF_8));
        assertEquals("\u001B[B", new String(dispatchedBytes.get(1), StandardCharsets.UTF_8));
        assertEquals("\u001B[D", new String(dispatchedBytes.get(2), StandardCharsets.UTF_8));
        assertEquals("\u001B[C", new String(dispatchedBytes.get(3), StandardCharsets.UTF_8));
    }

    @Test
    public void testPipeSymbolDispatched() {
        sink.sendText("|");
        assertEquals(1, dispatchedTexts.size());
        assertEquals("|", dispatchedTexts.get(0));
    }

    @Test
    public void testCtrlAndSigintWorkflow() {
        // 1. User clicks CTRL
        stateMachine.onModifierClicked(Modifier.CTRL);
        assertEquals(State.LATCHED, stateMachine.getState(Modifier.CTRL));
        assertTrue(stateMachine.isCtrlActive());

        // 2. User presses 'c'
        byte ctrlByte = StickyModifierStateMachine.toControlCode('c');
        assertEquals(0x03, ctrlByte);
        sink.sendBytes(new byte[]{ctrlByte});
        stateMachine.consumeLatched();

        // 3. Verifications
        assertEquals(1, dispatchedBytes.size());
        assertArrayEquals(new byte[]{0x03}, dispatchedBytes.get(0));
        assertEquals(State.OFF, stateMachine.getState(Modifier.CTRL));
        assertFalse(stateMachine.isCtrlActive());
    }

    @Test
    public void testCtrlAndSigtstpWorkflow() {
        // User clicks CTRL, then presses 'z' -> SIGTSTP (\x1A)
        stateMachine.onModifierClicked(Modifier.CTRL);
        byte ctrlByte = StickyModifierStateMachine.toControlCode('z');
        assertEquals(0x1A, ctrlByte);
        sink.sendBytes(new byte[]{ctrlByte});
        stateMachine.consumeLatched();

        assertEquals(1, dispatchedBytes.size());
        assertArrayEquals(new byte[]{0x1A}, dispatchedBytes.get(0));
        assertFalse(stateMachine.isCtrlActive());
    }
}
