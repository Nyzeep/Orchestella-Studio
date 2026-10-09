package io.github.nyzeep.orchestella.runtime.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Strategy-neutral Bridge v1 handshake contract.
 *
 * <p>The public seam is one request line and one response line. Transport adapters are responsible
 * only for moving those bytes; they do not appear in this contract.
 */
public final class BridgeHandshake {
    public static final int VERSION = 1;
    public static final int TOKEN_HEX_LENGTH = 64;

    private BridgeHandshake() {}

    public static Result negotiate(String requestLine, String expectedToken) {
        if (!isCanonicalToken(expectedToken)) {
            throw new IllegalArgumentException("expected token must be 64 lowercase hexadecimal characters");
        }
        Request request = parse(requestLine);
        if (request == null) {
            return Result.rejected(Reason.MALFORMED_HANDSHAKE);
        }
        if (!MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.US_ASCII),
                request.token.getBytes(StandardCharsets.US_ASCII))) {
            return Result.rejected(Reason.UNAUTHORIZED);
        }
        if (request.minimumVersion > VERSION || request.maximumVersion < VERSION) {
            return Result.rejected(Reason.UNSUPPORTED_VERSION);
        }
        return Result.accepted(VERSION);
    }

    public static boolean isCanonicalToken(String token) {
        if (token == null || token.length() != TOKEN_HEX_LENGTH) {
            return false;
        }
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (!(character >= '0' && character <= '9')
                    && !(character >= 'a' && character <= 'f')) {
                return false;
            }
        }
        return true;
    }

    private static Request parse(String requestLine) {
        if (requestLine == null || !requestLine.endsWith("\n")) {
            return null;
        }
        String line = requestLine.substring(0, requestLine.length() - 1);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            return null;
        }
        String[] fields = line.trim().split("\\s+");
        if (fields.length != 4 || (!"ORCHESTELLA/HELLO".equals(fields[0]) && !"DROIDLAYER/HELLO".equals(fields[0]))) {
            return null;
        }
        try {
            int minimumVersion = Integer.parseInt(fields[1]);
            int maximumVersion = Integer.parseInt(fields[2]);
            if (minimumVersion < 0 || maximumVersion < minimumVersion || !isCanonicalToken(fields[3])) {
                return null;
            }
            return new Request(minimumVersion, maximumVersion, fields[3]);
        } catch (NumberFormatException malformedNumber) {
            return null;
        }
    }

    public enum Reason {
        MALFORMED_HANDSHAKE("malformed-handshake"),
        UNAUTHORIZED("unauthorized"),
        UNSUPPORTED_VERSION("unsupported-version");

        private final String wireValue;

        Reason(String wireValue) {
            this.wireValue = wireValue;
        }
    }

    public static final class Result {
        private final Integer negotiatedVersion;
        private final Reason rejection;

        private Result(Integer negotiatedVersion, Reason rejection) {
            this.negotiatedVersion = negotiatedVersion;
            this.rejection = rejection;
        }

        public static Result accepted(int version) {
            return new Result(version, null);
        }

        public static Result rejected(Reason reason) {
            return new Result(null, Objects.requireNonNull(reason, "reason"));
        }

        public boolean accepted() {
            return negotiatedVersion != null;
        }

        public int negotiatedVersion() {
            if (!accepted()) {
                throw new IllegalStateException("rejected handshakes do not negotiate a version");
            }
            return negotiatedVersion;
        }

        public Reason rejection() {
            return rejection;
        }

        public String wireResponse() {
            return accepted()
                    ? "ORCHESTELLA/READY " + negotiatedVersion + "\n"
                    : "ORCHESTELLA/ERROR " + rejection.wireValue + "\n";
        }
    }

    private static final class Request {
        private final int minimumVersion;
        private final int maximumVersion;
        private final String token;

        private Request(int minimumVersion, int maximumVersion, String token) {
            this.minimumVersion = minimumVersion;
            this.maximumVersion = maximumVersion;
            this.token = token;
        }
    }
}
