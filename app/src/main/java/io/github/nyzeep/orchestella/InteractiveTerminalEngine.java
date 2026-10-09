package io.github.nyzeep.orchestella;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Core PTY interactive line editor and real-time keystroke echo engine.
 *
 * <p>Conforms to Phase 4.7 Task 3 and Matt Pocock deep-module principles:
 * <ul>
 *   <li>Real-time Keystroke Echo: Every key typed, deleted, or navigated is immediately reflected on screen;</li>
 *   <li>Starship / Windows Terminal styled prompt representation (&lt;user&gt;@&lt;host&gt;:&lt;path&gt; ❯);</li>
 *   <li>Command history recall (Up/Down arrow navigation);</li>
 *   <li>Standard VT100 control sequences for Tab, Ctrl+C (SIGINT), Ctrl+D (EOF), Ctrl+L (FF);</li>
 *   <li>Pure JVM design: 100% testable without Android OS stubs or mocks.</li>
 * </ul>
 */
public final class InteractiveTerminalEngine {

    /**
     * Immutable prompt metadata describing the active shell context.
     */
    public static final class PromptInfo {
        private final String user;
        private final String host;
        private final String workingDirectory;
        private final String symbol;
        private final boolean root;

        public PromptInfo(String user, String host, String workingDirectory, String symbol, boolean root) {
            this.user = user != null && !user.isEmpty() ? user : "orchestella";
            this.host = host != null && !host.isEmpty() ? host : "guest";
            this.workingDirectory = workingDirectory != null && !workingDirectory.isEmpty() ? workingDirectory : "~";
            this.symbol = symbol != null && !symbol.isEmpty() ? symbol : "❯";
            this.root = root;
        }

        public static PromptInfo defaultUser() {
            return new PromptInfo("orchestella", "guest", "~", "❯", false);
        }

        public static PromptInfo rootUser() {
            return new PromptInfo("root", "orchestella", "~", "❯", true);
        }

        public String getUser() {
            return user;
        }

        public String getHost() {
            return host;
        }

        public String getWorkingDirectory() {
            return workingDirectory;
        }

        public String getSymbol() {
            return symbol;
        }

        public boolean isRoot() {
            return root;
        }

        public String getContextIdentifier() {
            return user + "@" + host + ":" + workingDirectory + (root ? "#" : "$");
        }

        public String toPromptString() {
            return "[" + user + "@" + host + " " + workingDirectory + "] " + symbol + " ";
        }

        @Override
        public String toString() {
            return toPromptString();
        }
    }

    @FunctionalInterface
    public interface LineChangeListener {
        void onLineChanged(PromptInfo prompt, String lineBuffer, int cursorPosition);
    }

    @FunctionalInterface
    public interface LineCommitListener {
        void onLineCommitted(PromptInfo prompt, String command);
    }

    @FunctionalInterface
    public interface ClearRequestListener {
        void onClearRequested();
    }

    @FunctionalInterface
    public interface TabCompletionHandler {
        List<String> complete(String currentBuffer, int cursorPosition);
    }

    @FunctionalInterface
    public interface TabCompletionListener {
        void onCompletionCandidates(String buffer, int cursorPosition, List<String> candidates);
    }

    private PromptInfo prompt;
    private final StringBuilder lineBuffer = new StringBuilder();
    private int cursorPosition = 0;
    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    private String savedUncommittedLine = "";

    private Consumer<byte[]> byteSink;
    private LineChangeListener changeListener;
    private LineCommitListener commitListener;
    private ClearRequestListener clearListener;
    private TabCompletionHandler tabCompletionHandler;
    private TabCompletionListener tabCompletionListener;

    public InteractiveTerminalEngine() {
        this(PromptInfo.rootUser());
    }

    public InteractiveTerminalEngine(PromptInfo prompt) {
        this.prompt = Objects.requireNonNull(prompt, "prompt");
    }

    public void setByteSink(Consumer<byte[]> byteSink) {
        this.byteSink = byteSink;
    }

    public void setLineChangeListener(LineChangeListener changeListener) {
        this.changeListener = changeListener;
    }

    public void setLineCommitListener(LineCommitListener commitListener) {
        this.commitListener = commitListener;
    }

    public void setClearRequestListener(ClearRequestListener clearListener) {
        this.clearListener = clearListener;
    }

    public void setTabCompletionHandler(TabCompletionHandler tabCompletionHandler) {
        this.tabCompletionHandler = tabCompletionHandler;
    }

    public void setTabCompletionListener(TabCompletionListener tabCompletionListener) {
        this.tabCompletionListener = tabCompletionListener;
    }

    public PromptInfo getPrompt() {
        return prompt;
    }

    public void setPrompt(PromptInfo prompt) {
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        notifyLineChanged();
    }

    public String getBuffer() {
        return lineBuffer.toString();
    }

    public int getCursorPosition() {
        return cursorPosition;
    }

    public List<String> getHistory() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    /**
     * Handles a single typed character, inserting it at the current cursor position
     * and echoing it immediately.
     */
    public void handleKeyTyped(char c) {
        if (c >= 0x20 && c != 0x7F) {
            lineBuffer.insert(cursorPosition, c);
            cursorPosition++;
            notifyLineChanged();
        }
    }

    /**
     * Handles bulk text insertion (e.g. from IME commit, clipboard paste, or symbol button).
     */
    public void handleTextInserted(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        lineBuffer.insert(cursorPosition, text);
        cursorPosition += text.length();
        notifyLineChanged();
    }

    /**
     * Handles backspace keystroke, deleting the character preceding the cursor.
     */
    public void handleBackspace() {
        if (cursorPosition > 0) {
            lineBuffer.deleteCharAt(cursorPosition - 1);
            cursorPosition--;
            notifyLineChanged();
        }
    }

    /**
     * Handles delete keystroke, deleting the character at the current cursor position.
     */
    public void handleDelete() {
        if (cursorPosition < lineBuffer.length()) {
            lineBuffer.deleteCharAt(cursorPosition);
            notifyLineChanged();
        }
    }

    /**
     * Moves the cursor left by one position.
     */
    public void handleArrowLeft() {
        if (cursorPosition > 0) {
            cursorPosition--;
            notifyLineChanged();
        }
    }

    /**
     * Moves the cursor right by one position.
     */
    public void handleArrowRight() {
        if (cursorPosition < lineBuffer.length()) {
            cursorPosition++;
            notifyLineChanged();
        }
    }

    /**
     * Recalls the previous command from history (Up arrow).
     */
    public void handleArrowUp() {
        if (history.isEmpty()) {
            return;
        }
        if (historyIndex == -1) {
            savedUncommittedLine = lineBuffer.toString();
            historyIndex = history.size() - 1;
        } else if (historyIndex > 0) {
            historyIndex--;
        }
        applyHistoryItem(history.get(historyIndex));
    }

    /**
     * Recalls the next command from history or restores uncommitted input (Down arrow).
     */
    public void handleArrowDown() {
        if (historyIndex == -1) {
            return;
        }
        if (historyIndex < history.size() - 1) {
            historyIndex++;
            applyHistoryItem(history.get(historyIndex));
        } else {
            historyIndex = -1;
            applyHistoryItem(savedUncommittedLine);
            savedUncommittedLine = "";
        }
    }

    private void applyHistoryItem(String command) {
        lineBuffer.setLength(0);
        lineBuffer.append(command);
        cursorPosition = lineBuffer.length();
        notifyLineChanged();
    }

    /**
     * Handles Tab key, triggering smart completion if a handler is attached,
     * or dispatching \t byte (0x09) to the backend VM stream as fallback.
     */
    public void handleTab() {
        if (tabCompletionHandler != null) {
            List<String> candidates = tabCompletionHandler.complete(lineBuffer.toString(), cursorPosition);
            if (candidates != null && candidates.size() == 1) {
                String candidate = candidates.get(0);
                lineBuffer.setLength(0);
                lineBuffer.append(candidate);
                cursorPosition = lineBuffer.length();
                notifyLineChanged();
                return;
            } else if (candidates != null && candidates.size() > 1) {
                String commonPrefix = CommandCompletionEngine.findLongestCommonPrefix(candidates);
                if (commonPrefix != null && commonPrefix.length() > cursorPosition && commonPrefix.startsWith(lineBuffer.substring(0, cursorPosition))) {
                    String afterCursor = lineBuffer.substring(cursorPosition);
                    lineBuffer.setLength(0);
                    lineBuffer.append(commonPrefix).append(afterCursor);
                    cursorPosition = commonPrefix.length();
                    notifyLineChanged();
                }
                if (tabCompletionListener != null) {
                    tabCompletionListener.onCompletionCandidates(lineBuffer.toString(), cursorPosition, candidates);
                }
                return;
            }
        }
        sendRawBytes(new byte[]{'\t'});
    }

    /**
     * Helper to format candidate listing and redrawn prompt line for display in terminal output.
     */
    public String renderCompletionListing(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        String listing = CommandCompletionEngine.formatCandidatesListing(candidates);
        return "\r\n" + listing + "\r\n" + prompt.toPromptString() + lineBuffer.toString();
    }

    /**
     * Parses standard ANSI escape sequences for cursor positioning and navigation.
     *
     * <p>Supported sequences:
     * <ul>
     *   <li>\e[A: Arrow Up (History recall)</li>
     *   <li>\e[B: Arrow Down (History forward)</li>
     *   <li>\e[C: Arrow Right</li>
     *   <li>\e[D: Arrow Left</li>
     *   <li>\e[3~: Delete key</li>
     * </ul>
     */
    public void handleAnsiSequence(String sequence) {
        if (sequence == null || sequence.isEmpty()) {
            return;
        }
        switch (sequence) {
            case "\u001b[A":
            case "\\e[A":
                handleArrowUp();
                break;
            case "\u001b[B":
            case "\\e[B":
                handleArrowDown();
                break;
            case "\u001b[C":
            case "\\e[C":
                handleArrowRight();
                break;
            case "\u001b[D":
            case "\\e[D":
                handleArrowLeft();
                break;
            case "\u001b[3~":
            case "\\e[3~":
                handleDelete();
                break;
            default:
                break;
        }
    }

    /**
     * Commits the current line buffer, sends command + \r to the backend PTY,
     * appends the command to history, and resets the line buffer.
     */
    public void handleEnter() {
        String command = lineBuffer.toString();
        if (!command.isEmpty()) {
            if (history.isEmpty() || !history.get(history.size() - 1).equals(command)) {
                history.add(command);
                if (history.size() > 500) {
                    history.remove(0);
                }
            }
        }
        historyIndex = -1;
        savedUncommittedLine = "";

        if (commitListener != null) {
            commitListener.onLineCommitted(prompt, command);
        }

        String toSend = command.endsWith("\n")
                ? command.substring(0, command.length() - 1) + "\r"
                : command + "\r";
        sendRawBytes(toSend.getBytes(StandardCharsets.UTF_8));

        lineBuffer.setLength(0);
        cursorPosition = 0;
        notifyLineChanged();
    }

    /**
     * Handles Ctrl+C (SIGINT): dispatches 0x03 byte to the backend, aborts the current
     * line buffer, and clears the prompt.
     */
    public void handleCtrlC() {
        sendRawBytes(new byte[]{0x03});
        String abortedLine = lineBuffer.toString() + "^C";
        if (commitListener != null) {
            commitListener.onLineCommitted(prompt, abortedLine);
        }
        lineBuffer.setLength(0);
        cursorPosition = 0;
        historyIndex = -1;
        savedUncommittedLine = "";
        notifyLineChanged();
    }

    /**
     * Handles Ctrl+D (EOF): if buffer is empty, sends 0x04.
     */
    public void handleCtrlD() {
        if (lineBuffer.length() == 0) {
            sendRawBytes(new byte[]{0x04});
        }
    }

    /**
     * Handles Ctrl+L (Form Feed): sends 0x0C and triggers terminal clear.
     */
    public void handleCtrlL() {
        sendRawBytes(new byte[]{0x0C});
        if (clearListener != null) {
            clearListener.onClearRequested();
        }
        notifyLineChanged();
    }

    /**
     * Sets the buffer content and cursor position directly (e.g. from an EditText sync).
     */
    public void syncBuffer(String text, int cursor) {
        lineBuffer.setLength(0);
        if (text != null) {
            lineBuffer.append(text);
        }
        cursorPosition = Math.max(0, Math.min(cursor, lineBuffer.length()));
        notifyLineChanged();
    }

    /**
     * Returns the formatted line buffer with a visible block cursor marker.
     */
    public String getFormattedLineWithCursor() {
        StringBuilder sb = new StringBuilder();
        int len = lineBuffer.length();
        for (int i = 0; i < len; i++) {
            if (i == cursorPosition) {
                sb.append('▋');
            }
            sb.append(lineBuffer.charAt(i));
        }
        if (cursorPosition >= len) {
            sb.append('▋');
        }
        return sb.toString();
    }

    private void sendRawBytes(byte[] bytes) {
        if (byteSink != null && bytes != null && bytes.length > 0) {
            byteSink.accept(bytes);
        }
    }

    private void notifyLineChanged() {
        if (changeListener != null) {
            changeListener.onLineChanged(prompt, lineBuffer.toString(), cursorPosition);
        }
    }
}
