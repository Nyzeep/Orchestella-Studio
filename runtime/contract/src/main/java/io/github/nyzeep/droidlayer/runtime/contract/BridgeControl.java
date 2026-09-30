package io.github.nyzeep.droidlayer.runtime.contract;

/** Strategy-neutral control behavior for an authenticated Bridge v1 byte stream. */
public final class BridgeControl {
    private BridgeControl() {}

    /** Strategy-neutral power actions supported by the Bridge v1 control channel. */
    public enum PowerAction {
        SHUTDOWN("shutdown"),
        POWEROFF("poweroff");

        private final String wireValue;

        PowerAction(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static PowerAction fromWire(String value) {
            if (value == null) {
                return null;
            }
            for (PowerAction action : values()) {
                if (action.wireValue.equals(value)) {
                    return action;
                }
            }
            return null;
        }
    }

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
        if (isPowerCommand(requestLine)) {
            PowerRequest req = parsePower(requestLine);
            if (req == null) {
                return Result.rejected("DROIDLAYER/ERROR malformed-command\n");
            }
            return Result.rejected("DROIDLAYER/ERROR power-unavailable\n");
        }
        ResizeRequest resize = parseResize(requestLine);
        if (resize == null) {
            return dispatch(requestLine);
        }
        return terminal.resize(resize.columns, resize.rows)
                ? Result.accepted("DROIDLAYER/OK resize\n")
                : Result.rejected("DROIDLAYER/ERROR resize-unavailable\n");
    }

    /**
     * Dispatches a control request that may operate on the power management seam.
     */
    public static Result dispatch(String requestLine, Power power) {
        if (power == null) {
            throw new IllegalArgumentException("power");
        }
        if (isPowerCommand(requestLine)) {
            PowerRequest req = parsePower(requestLine);
            if (req == null) {
                return Result.rejected("DROIDLAYER/ERROR malformed-command\n");
            }
            return power.power(req.action)
                    ? Result.accepted("DROIDLAYER/OK power\n")
                    : Result.rejected("DROIDLAYER/ERROR power-unavailable\n");
        }
        ResizeRequest resize = parseResize(requestLine);
        if (resize != null) {
            return Result.rejected("DROIDLAYER/ERROR resize-unavailable\n");
        }
        return dispatch(requestLine);
    }

    /**
     * Dispatches a control request that may operate on either the terminal or power management seams.
     */
    public static Result dispatch(String requestLine, Terminal terminal, Power power) {
        if (terminal == null) {
            throw new IllegalArgumentException("terminal");
        }
        if (power == null) {
            throw new IllegalArgumentException("power");
        }
        if (isPowerCommand(requestLine)) {
            PowerRequest req = parsePower(requestLine);
            if (req == null) {
                return Result.rejected("DROIDLAYER/ERROR malformed-command\n");
            }
            return power.power(req.action)
                    ? Result.accepted("DROIDLAYER/OK power\n")
                    : Result.rejected("DROIDLAYER/ERROR power-unavailable\n");
        }
        ResizeRequest resize = parseResize(requestLine);
        if (resize != null) {
            return terminal.resize(resize.columns, resize.rows)
                    ? Result.accepted("DROIDLAYER/OK resize\n")
                    : Result.rejected("DROIDLAYER/ERROR resize-unavailable\n");
        }
        return dispatch(requestLine);
    }

    private static boolean isPowerCommand(String requestLine) {
        if (requestLine == null) {
            return false;
        }
        return requestLine.startsWith("DROIDLAYER/POWER ")
                || "DROIDLAYER/POWER\n".equals(requestLine)
                || "DROIDLAYER/POWER\r\n".equals(requestLine);
    }

    private static PowerRequest parsePower(String requestLine) {
        if (requestLine == null || !requestLine.endsWith("\n")) {
            return null;
        }
        String line = requestLine.substring(0, requestLine.length() - 1);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        String[] fields = line.split(" ", -1);
        if (fields.length != 2 || !"DROIDLAYER/POWER".equals(fields[0])) {
            return null;
        }
        PowerAction action = PowerAction.fromWire(fields[1]);
        if (action == null) {
            return null;
        }
        return new PowerRequest(action);
    }

    /** Adapter for strategy-neutral power management actions (shutdown/poweroff). */
    @FunctionalInterface
    public interface Power {
        boolean power(PowerAction action);
    }

    private static final class PowerRequest {
        private final PowerAction action;

        private PowerRequest(PowerAction action) {
            this.action = action;
        }
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
