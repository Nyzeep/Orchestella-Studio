package io.github.nyzeep.orchestella.terminal;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class TerminalProtocolResponseTest {
    @Test public void deviceAttributeResponseEchoCannotGenerateAnotherReply() {
        TerminalScreenModel model = new TerminalScreenModel(80, 24);
        ArrayDeque<String> serialEcho = new ArrayDeque<>();
        List<String> responses = new ArrayList<>();
        model.setResponseSink(response -> {
            responses.add(response);
            serialEcho.add(response);
        });
        model.append("\u001b[c");
        for (int i = 0; i < 8 && !serialEcho.isEmpty(); i++) {
            model.append(serialEcho.remove());
        }
        assertEquals("One query must not become a serial echo feedback loop",
                Arrays.asList("\u001b[?1;2c"), responses);
        assertTrue(serialEcho.isEmpty());
    }

    @Test public void echoedReportsAndUnsupportedQueriesAreNotRequests() {
        TerminalScreenModel model = new TerminalScreenModel(80, 24);
        List<String> responses = new ArrayList<>();
        model.setResponseSink(responses::add);
        model.append("\u001b[?1;2c\u001b[>41;320;0c\u001b[0n\u001b[24;80R");
        model.append("\u001b[1c\u001b[0;0c\u001b[>5n\u001b[>6n\u001b[5;1n\u001b[6;1n\u001b[6 n");
        assertTrue("Only supported query grammar may write bytes to guest input", responses.isEmpty());
    }

    @Test public void validQueriesStillReturnCapabilitiesAndActualCursorPosition() {
        TerminalScreenModel model = new TerminalScreenModel(80, 24);
        List<String> responses = new ArrayList<>();
        model.setResponseSink(responses::add);
        model.append("\u001b[12;40H\u001b[c\u001b[0c\u001b[5n\u001b[6n");
        assertEquals(Arrays.asList("\u001b[?1;2c", "\u001b[?1;2c", "\u001b[0n", "\u001b[12;40R"), responses);
    }

    @Test public void fragmentedEchoedCapabilityReportRemainsSilent() {
        TerminalScreenModel model = new TerminalScreenModel(80, 24);
        List<String> responses = new ArrayList<>();
        model.setResponseSink(responses::add);
        for (char ch : "\u001b[?1;2c".toCharArray()) model.append(String.valueOf(ch));
        assertTrue(responses.isEmpty());
    }
}
