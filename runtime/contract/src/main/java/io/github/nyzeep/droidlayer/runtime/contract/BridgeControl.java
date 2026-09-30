package io.github.nyzeep.droidlayer.runtime.contract;

/** Strategy-neutral control behavior for an authenticated Bridge v1 byte stream. */
public final class BridgeControl {
    private BridgeControl() {}

    /**
     * Dispatches one already-authenticated control request. Handshake and transport setup happen at
     * their own seams before this method is called.
     */
    public static Result dispatch(String requestLine) {
        if ("DROIDLAYER/PING\n".equals(requestLine)) {
            return Result.accepted("DROIDLAYER/PONG 1\n");
        }
        return Result.rejected("DROIDLAYER/ERROR malformed-command\n");
    }

    /**
     * Dispatches a control request that may operate on the terminal channel. The terminal is an
     * adapter supplied at this seam; transport details remain outside this contract.
     */
    public static Result dispatch(String requestLine, Terminal terminal) {
        if (terminal == null) {
            throw new IllegalArgumentException("terminal");
        }
        ResizeRequest resize = parseResize(requestLine);
        if (resize == null) {
            return dispatch(requestLine);
        }
        return terminal.resize(resize.columns, resize.rows)
                ? Result.accepted("DROIDLAYER/OK resize\n")
                : Result.rejected("DROIDLAYER/ERROR resize-unavailable\n");
    }

    private static ResizeRequest parseResize(String requestLine) {
        if (requestLine == null || !requestLine.endsWith("\n")) {
            return null;
        }
        String line = requestLine.substring(0, requestLine.length() - 1);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        String[] fields = line.split(" ", -1);
        if (fields.length != 3 || !"DROIDLAYER/RESIZE".equals(fields[0])) {
            return null;
        }
        try {
            int columns = Integer.parseInt(fields[1]);
            int rows = Integer.parseInt(fields[2]);
            if (columns < 1 || columns > 1000 || rows < 1 || rows > 1000) {
                return null;
            }
            return new ResizeRequest(columns, rows);
        } catch (NumberFormatException malformedNumber) {
            return null;
        }
    }

    /** Adapter for the strategy-neutral terminal behavior required by resize. */
    @FunctionalInterface
    public interface Terminal {
        boolean resize(int columns, int rows);
    }

    private static final class ResizeRequest {
        private final int columns;
        private final int rows;

        private ResizeRequest(int columns, int rows) {
            this.columns = columns;
            this.rows = rows;
        }
    }

    /** Observable control result returned to the transport adapter. */
    public static final class Result {
        private final boolean accepted;
        private final String wireResponse;

        private Result(boolean accepted, String wireResponse) {
            this.accepted = accepted;
            this.wireResponse = wireResponse;
        }

        private static Result accepted(String wireResponse) {
            return new Result(true, wireResponse);
        }

        private static Result rejected(String wireResponse) {
            return new Result(false, wireResponse);
        }

        public boolean accepted() {
            return accepted;
        }

        public String wireResponse() {
            return wireResponse;
        }
    }
}
