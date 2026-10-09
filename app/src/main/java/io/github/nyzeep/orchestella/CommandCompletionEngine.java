package io.github.nyzeep.orchestella;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure JVM deep-module Linux command and path auto-completion engine.
 *
 * <p>Conforms to Phase 4.9 Task 2 and Matt Pocock deep-module principles:
 * <ul>
 *   <li>Lightweight built-in dictionary of common Linux commands (cd, ls, cat, mkdir, rm, cp, mv, apt, apk, systemctl, uname, ps, top, grep, chmod, chown, etc.);</li>
 *   <li>Built-in virtual path completion for standard Linux filesystem roots (/bin, /boot, /dev, /etc, /home, /opt, /proc, /root, /sys, /tmp, /usr, /var);</li>
 *   <li>Pipeline and multi-command aware: accurately parses commands at line start or after pipes (|) and separators (&amp;&amp;, ||, ;);</li>
 *   <li>Longest Common Prefix (LCP) calculation for ambiguous matches;</li>
 *   <li>Seamless integration with {@link InteractiveTerminalEngine.TabCompletionHandler};</li>
 *   <li>100% testable without Android framework or emulator stubs.</li>
 * </ul>
 */
public final class CommandCompletionEngine implements InteractiveTerminalEngine.TabCompletionHandler {

    /**
     * Built-in default Linux core commands dictionary.
     */
    public static final List<String> DEFAULT_COMMANDS = List.of(
            "apk", "apt", "awk", "bash", "cat", "cd", "chmod", "chown",
            "clear", "cp", "curl", "df", "dmesg", "docker", "du", "echo",
            "export", "find", "free", "grep", "head", "history", "ifconfig",
            "ip", "journalctl", "kill", "less", "ls", "mkdir", "more", "mv",
            "nano", "ps", "pwd", "rm", "sed", "sh", "source", "ssh", "sudo",
            "systemctl", "tail", "tar", "top", "touch", "uname", "vim", "wc",
            "wget", "which", "whoami"
    );

    /**
     * Built-in default root filesystem directory candidates for path completion.
     */
    public static final List<String> DEFAULT_ROOT_PATHS = List.of(
            "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/mnt",
            "/opt", "/proc", "/root", "/run", "/sys", "/tmp", "/usr", "/var"
    );

    private final Set<String> commandLexicon = new TreeSet<>();
    private final Set<String> pathLexicon = new TreeSet<>();

    public CommandCompletionEngine() {
        this(DEFAULT_COMMANDS, DEFAULT_ROOT_PATHS);
    }

    public CommandCompletionEngine(Collection<String> initialCommands) {
        this(initialCommands, DEFAULT_ROOT_PATHS);
    }

    public CommandCompletionEngine(Collection<String> initialCommands, Collection<String> initialPaths) {
        if (initialCommands != null) {
            commandLexicon.addAll(initialCommands);
        }
        if (initialPaths != null) {
            pathLexicon.addAll(initialPaths);
        }
    }

    /**
     * Registers an additional custom command into the completion dictionary.
     */
    public synchronized void registerCommand(String command) {
        if (command != null && !command.trim().isEmpty()) {
            commandLexicon.add(command.trim());
        }
    }

    /**
     * Registers a collection of commands into the dictionary.
     */
    public synchronized void registerCommands(Collection<String> commands) {
        if (commands != null) {
            for (String cmd : commands) {
                registerCommand(cmd);
            }
        }
    }

    /**
     * Registers a custom path candidate into the path dictionary.
     */
    public synchronized void registerPath(String path) {
        if (path != null && !path.trim().isEmpty()) {
            pathLexicon.add(path.trim());
        }
    }

    /**
     * Returns an unmodifiable snapshot of registered commands.
     */
    public synchronized List<String> getRegisteredCommands() {
        return Collections.unmodifiableList(new ArrayList<>(commandLexicon));
    }

    /**
     * Returns an unmodifiable snapshot of registered paths.
     */
    public synchronized List<String> getRegisteredPaths() {
        return Collections.unmodifiableList(new ArrayList<>(pathLexicon));
    }

    /**
     * Evaluates the buffer and cursor position, returning a detailed {@link Result}.
     */
    public synchronized Result evaluate(String buffer, int cursorPosition) {
        if (buffer == null) {
            return Result.empty();
        }

        int safeCursor = Math.max(0, Math.min(cursorPosition, buffer.length()));
        String beforeCursor = buffer.substring(0, safeCursor);
        String afterCursor = buffer.substring(safeCursor);

        // Find preceding command boundary (pipe '|', semicolon ';', '&&', '||')
        int boundaryIndex = -1;
        int boundaryLen = 0;
        int pipeIdx = beforeCursor.lastIndexOf('|');
        int semiIdx = beforeCursor.lastIndexOf(';');
        int andIdx = beforeCursor.lastIndexOf("&&");
        int orIdx = beforeCursor.lastIndexOf("||");

        if (pipeIdx > boundaryIndex) {
            boundaryIndex = pipeIdx;
            boundaryLen = 1;
        }
        if (semiIdx > boundaryIndex) {
            boundaryIndex = semiIdx;
            boundaryLen = 1;
        }
        if (andIdx > boundaryIndex) {
            boundaryIndex = andIdx;
            boundaryLen = 2;
        }
        if (orIdx > boundaryIndex) {
            boundaryIndex = orIdx;
            boundaryLen = 2;
        }

        int segmentStart = boundaryIndex >= 0 ? boundaryIndex + boundaryLen : 0;
        String currentSegment = beforeCursor.substring(segmentStart);

        // Find the active token immediately before the cursor
        int tokenStartInSegment = currentSegment.length();
        while (tokenStartInSegment > 0 && !Character.isWhitespace(currentSegment.charAt(tokenStartInSegment - 1))) {
            tokenStartInSegment--;
        }

        String token = currentSegment.substring(tokenStartInSegment);
        int absoluteTokenStart = segmentStart + tokenStartInSegment;
        String prefixBeforeToken = buffer.substring(0, absoluteTokenStart);

        if (token.isEmpty()) {
            return Result.empty();
        }

        // Determine if this is a path token or a command token
        boolean isPathToken = token.startsWith("/") || token.startsWith("./") || token.startsWith("../");
        boolean isCommandToken = !isPathToken && isFirstWordInSegment(currentSegment, tokenStartInSegment);

        List<String> matches = new ArrayList<>();
        if (isCommandToken) {
            for (String cmd : commandLexicon) {
                if (cmd.startsWith(token)) {
                    matches.add(cmd);
                }
            }
        } else if (isPathToken) {
            for (String p : pathLexicon) {
                if (p.startsWith(token)) {
                    matches.add(p);
                }
            }
        } else {
            // Check commands first, then paths
            for (String cmd : commandLexicon) {
                if (cmd.startsWith(token)) {
                    matches.add(cmd);
                }
            }
            if (matches.isEmpty()) {
                for (String p : pathLexicon) {
                    if (p.startsWith(token)) {
                        matches.add(p);
                    }
                }
            }
        }

        if (matches.isEmpty()) {
            return Result.empty();
        }

        String commonPrefix = findLongestCommonPrefix(matches);
        List<String> completedLines = new ArrayList<>(matches.size());
        for (String match : matches) {
            completedLines.add(prefixBeforeToken + match + afterCursor);
        }

        String lcpCompletedLine = prefixBeforeToken + commonPrefix + afterCursor;
        int lcpCursorPos = prefixBeforeToken.length() + commonPrefix.length();

        return new Result(
                token,
                commonPrefix,
                matches,
                completedLines,
                lcpCompletedLine,
                lcpCursorPos,
                matches.size() == 1
        );
    }

    private static boolean isFirstWordInSegment(String segment, int tokenStartInSegment) {
        String beforeToken = segment.substring(0, tokenStartInSegment).trim();
        return beforeToken.isEmpty();
    }

    /**
     * Implements {@link InteractiveTerminalEngine.TabCompletionHandler}.
     * Returns full replacement lines matching candidate tokens.
     */
    @Override
    public List<String> complete(String currentBuffer, int cursorPosition) {
        Result result = evaluate(currentBuffer, cursorPosition);
        if (result.matches().isEmpty()) {
            return Collections.emptyList();
        }
        return result.completedLines();
    }

    /**
     * Computes the longest common prefix of a collection of strings.
     */
    public static String findLongestCommonPrefix(List<String> strings) {
        if (strings == null || strings.isEmpty()) {
            return "";
        }
        if (strings.size() == 1) {
            return strings.get(0);
        }

        String prefix = strings.get(0);
        for (int i = 1; i < strings.size(); i++) {
            String s = strings.get(i);
            while (!s.startsWith(prefix)) {
                prefix = prefix.substring(0, prefix.length() - 1);
                if (prefix.isEmpty()) {
                    return "";
                }
            }
        }
        return prefix;
    }

    /**
     * Formats multiple candidate tokens into a clean, space-separated or columnar listing.
     */
    public static String formatCandidatesListing(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        // Extract raw leaf tokens if candidates are full command lines
        List<String> leafTokens = new ArrayList<>(candidates.size());
        for (String c : candidates) {
            int lastSpace = c.lastIndexOf(' ');
            if (lastSpace >= 0 && lastSpace < c.length() - 1) {
                leafTokens.add(c.substring(lastSpace + 1));
            } else {
                leafTokens.add(c);
            }
        }
        return String.join("  ", leafTokens);
    }

    /**
     * Detailed immutable result of a completion evaluation.
     */
    public static final class Result {
        private final String inputToken;
        private final String commonPrefixToken;
        private final List<String> matches;
        private final List<String> completedLines;
        private final String commonPrefixLine;
        private final int commonPrefixCursorPosition;
        private final boolean isSingleMatch;

        public Result(
                String inputToken,
                String commonPrefixToken,
                List<String> matches,
                List<String> completedLines,
                String commonPrefixLine,
                int commonPrefixCursorPosition,
                boolean isSingleMatch) {
            this.inputToken = Objects.requireNonNull(inputToken, "inputToken");
            this.commonPrefixToken = Objects.requireNonNull(commonPrefixToken, "commonPrefixToken");
            this.matches = Collections.unmodifiableList(new ArrayList<>(matches));
            this.completedLines = Collections.unmodifiableList(new ArrayList<>(completedLines));
            this.commonPrefixLine = Objects.requireNonNull(commonPrefixLine, "commonPrefixLine");
            this.commonPrefixCursorPosition = commonPrefixCursorPosition;
            this.isSingleMatch = isSingleMatch;
        }

        public static Result empty() {
            return new Result("", "", Collections.emptyList(), Collections.emptyList(), "", 0, false);
        }

        public String inputToken() {
            return inputToken;
        }

        public String commonPrefixToken() {
            return commonPrefixToken;
        }

        public List<String> matches() {
            return matches;
        }

        public List<String> completedLines() {
            return completedLines;
        }

        public String commonPrefixLine() {
            return commonPrefixLine;
        }

        public int commonPrefixCursorPosition() {
            return commonPrefixCursorPosition;
        }

        public boolean isSingleMatch() {
            return isSingleMatch;
        }

        public boolean hasCommonPrefixExtension() {
            return commonPrefixToken.length() > inputToken.length();
        }
    }
}
