package io.github.nyzeep.droidlayer.guestassets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DisplayAgentProtocolTest {

    private static final String VALID_TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesValidDisplayModeCommand() {
        DisplayAgentProtocol.DisplayCommand cmd =
                DisplayAgentProtocol.parseDisplayCommand("DROIDLAYER/DISPLAY MODE 1920 1080 1.5\n");
        assertNotNull(cmd, "Valid DISPLAY MODE command must be parsed");
        assertEquals(DisplayAgentProtocol.DisplayCommandType.MODE, cmd.type());
        assertEquals(1920, cmd.width());
        assertEquals(1080, cmd.height());
        assertEquals(1.5, cmd.scale(), 0.001);
        assertEquals(0, cmd.fps());
        assertEquals("DROIDLAYER/OK display\n", cmd.successResponse());
    }

    @Test
    void parsesValidDisplayResizeCommand() {
        DisplayAgentProtocol.DisplayCommand cmd =
                DisplayAgentProtocol.parseDisplayCommand("DROIDLAYER/DISPLAY RESIZE 1080 2400\n");
        assertNotNull(cmd, "Valid DISPLAY RESIZE command must be parsed");
        assertEquals(DisplayAgentProtocol.DisplayCommandType.RESIZE, cmd.type());
        assertEquals(1080, cmd.width());
        assertEquals(2400, cmd.height());
        assertEquals("DROIDLAYER/OK display\n", cmd.successResponse());
    }

    @Test
    void parsesValidDisplayFpsCommand() {
        DisplayAgentProtocol.DisplayCommand cmd =
                DisplayAgentProtocol.parseDisplayCommand("DROIDLAYER/DISPLAY FPS 60\n");
        assertNotNull(cmd, "Valid DISPLAY FPS command must be parsed");
        assertEquals(DisplayAgentProtocol.DisplayCommandType.FPS, cmd.type());
        assertEquals(60, cmd.fps());
        assertEquals("DROIDLAYER/OK display\n", cmd.successResponse());
    }

    @Test
    void rejectsOutOfRangeDisplayParameters() {
        assertFalse(DisplayAgentProtocol.isValidDisplayCommand("DROIDLAYER/DISPLAY RESIZE 50 1080\n"));
        assertFalse(DisplayAgentProtocol.isValidDisplayCommand("DROIDLAYER/DISPLAY RESIZE 1920 8000\n"));
        assertFalse(DisplayAgentProtocol.isValidDisplayCommand("DROIDLAYER/DISPLAY MODE 1920 1080 0.05\n"));
        assertFalse(DisplayAgentProtocol.isValidDisplayCommand("DROIDLAYER/DISPLAY MODE 1920 1080 6.0\n"));
        assertFalse(DisplayAgentProtocol.isValidDisplayCommand("DROIDLAYER/DISPLAY FPS 0\n"));
        assertFalse(DisplayAgentProtocol.isValidDisplayCommand("DROIDLAYER/DISPLAY FPS 300\n"));
    }

    @Test
    void rejectsMalformedDisplayCommands() {
        assertFalse(DisplayAgentProtocol.isValidDisplayCommand("DROIDLAYER/DISPLAY\n"));
        assertFalse(DisplayAgentProtocol.isValidDisplayCommand("DROIDLAYER/DISPLAY UNKNOWN 100 200\n"));
        assertFalse(DisplayAgentProtocol.isValidDisplayCommand("DROIDLAYER/DISPLAY RESIZE abc def\n"));
        assertFalse(DisplayAgentProtocol.isValidDisplayCommand("NOT_DROIDLAYER/DISPLAY RESIZE 100 100\n"));
    }

    @Test
    void buildsWlrRandrCommand() {
        String command = DisplayAgentProtocol.buildWlrRandrCommand("WL-1", 1080, 2400, 1.25);
        assertTrue(command.contains("wlr-randr"), "Command must invoke wlr-randr");
        assertTrue(command.contains("--output WL-1"), "Command must specify output");
        assertTrue(command.contains("--custom-mode 1080x2400"), "Command must specify custom mode");
        assertTrue(command.contains("--scale 1.25"), "Command must specify scale");
    }

    @Test
    void parsesAndDispatchesInputEvents() {
        DisplayAgentProtocol.InputEvent pointer =
                DisplayAgentProtocol.parseInputEvent("INPUT/POINTER MOVE 100.0 200.0 5.0 -3.0 0 1000000\n");
        assertNotNull(pointer);
        assertEquals(DisplayAgentProtocol.InputEventType.POINTER, pointer.type());

        DisplayAgentProtocol.InputEvent touch =
                DisplayAgentProtocol.parseInputEvent("INPUT/TOUCH DOWN 0 0.5 0.5 1000000\n");
        assertNotNull(touch);
        assertEquals(DisplayAgentProtocol.InputEventType.TOUCH, touch.type());

        DisplayAgentProtocol.InputEvent key =
                DisplayAgentProtocol.parseInputEvent("INPUT/KEY PRESS 30 1 1000000\n");
        assertNotNull(key);
        assertEquals(DisplayAgentProtocol.InputEventType.KEY, key.type());

        DisplayAgentProtocol.InputEvent text =
                DisplayAgentProtocol.parseInputEvent("INPUT/TEXT base64:5L2g5aW9 1000000\n");
        assertNotNull(text);
        assertEquals(DisplayAgentProtocol.InputEventType.TEXT, text.type());
        assertEquals("你好", text.textPayload());
    }

    @Test
    void verifiesFrameDataHeaderSerialization() {
        byte[] header = DisplayAgentProtocol.encodeFrameHeader(1, 1920, 1080, 1920 * 4, 1, 123456789L, 1920 * 1080 * 4);
        assertNotNull(header);
        assertEquals(36, header.length, "Frame header must be fixed size 36 bytes");

        DisplayAgentProtocol.FrameHeader decoded = DisplayAgentProtocol.decodeFrameHeader(header);
        assertNotNull(decoded);
        assertEquals(DisplayAgentProtocol.FRAME_MAGIC, decoded.magic());
        assertEquals(1, decoded.frameNumber());
        assertEquals(1920, decoded.width());
        assertEquals(1080, decoded.height());
        assertEquals(1920 * 4, decoded.stride());
        assertEquals(1, decoded.format());
        assertEquals(123456789L, decoded.timestampNs());
        assertEquals(1920 * 1080 * 4, decoded.payloadLength());
    }

    @Test
    void compilesAndServesDisplayAgentProcess() throws Exception {
        Path executable = compileDisplayAgent();
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Files.writeString(tokenFile, VALID_TOKEN + "\n", StandardCharsets.US_ASCII);

        String sessionInput = "DROIDLAYER/HELLO 1 1 " + VALID_TOKEN + "\n"
                + "DROIDLAYER/DISPLAY RESIZE 1280 720\n"
                + "DROIDLAYER/DISPLAY FPS 60\n"
                + "DROIDLAYER/PING\n";

        ProcessResult result = runAgent(executable, tokenFile, sessionInput);

        assertEquals(0, result.exitCode(), result.standardError());
        assertTrue(result.standardOutput().contains("DROIDLAYER/READY 1\n"));
        assertTrue(result.standardOutput().contains("DROIDLAYER/OK display\n"));
        assertTrue(result.standardOutput().contains("DROIDLAYER/PONG 1\n"));
    }

    private Path compileDisplayAgent() throws Exception {
        Path projectDirectory = Path.of(System.getProperty("guestAssetsProjectDir"));
        Path source = projectDirectory.resolve("agent/display-agent.c");
        Path executable = temporaryDirectory.resolve(isWindows() ? "display-agent.exe" : "display-agent");
        String compiler = System.getenv().getOrDefault("CC", "gcc");
        ProcessResult result =
                runProcess(
                        List.of(
                                compiler,
                                "-std=c11",
                                "-Wall",
                                "-Wextra",
                                "-Werror",
                                source.toString(),
                                "-o",
                                executable.toString()),
                        null);
        assertEquals(0, result.exitCode(), result.standardError());
        return executable;
    }

    private static ProcessResult runAgent(
            Path executable, Path tokenFile, String input, String... additionalArguments)
            throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("--token-file");
        command.add(tokenFile.toString());
        for (String additionalArgument : additionalArguments) {
            command.add(additionalArgument);
        }
        return runProcess(command, input);
    }

    private static ProcessResult runProcess(List<String> command, String input) throws Exception {
        Process process = new ProcessBuilder(new ArrayList<>(command)).start();
        if (input != null) {
            process.getOutputStream().write(input.getBytes(StandardCharsets.US_ASCII));
        }
        process.getOutputStream().close();
        String standardOutput = readFully(process.getInputStream());
        String standardError = readFully(process.getErrorStream());
        int exitCode = process.waitFor();
        return new ProcessResult(
                exitCode,
                standardOutput.replace("\r\n", "\n"),
                standardError.replace("\r\n", "\n"));
    }

    private static String readFully(java.io.InputStream input) throws IOException {
        return new String(input.readAllBytes(), StandardCharsets.US_ASCII);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private record ProcessResult(int exitCode, String standardOutput, String standardError) {}
}
