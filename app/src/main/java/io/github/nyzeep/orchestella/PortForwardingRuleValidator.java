package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import java.util.List;

/**
 * Strategy-neutral port validation and conflict detection engine.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Non-privileged port range: [1024, 65535] for unrooted Android environment;</li>
 *   <li>Numeric parsing and non-blank safety;</li>
 *   <li>Host port and protocol collision prevention against existing instance rules.</li>
 * </ul>
 */
public final class PortForwardingRuleValidator {

    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    public enum ValidationStatus {
        VALID,
        INVALID_HOST_PORT,
        INVALID_GUEST_PORT,
        CONFLICT_HOST_PORT,
        EMPTY_FIELD
    }

    public static final class Result {
        private final ValidationStatus status;
        private final String errorMessage;
        private final int parsedPort;

        private Result(ValidationStatus status, String errorMessage, int parsedPort) {
            this.status = status;
            this.errorMessage = errorMessage;
            this.parsedPort = parsedPort;
        }

        public static Result valid(int port) {
            return new Result(ValidationStatus.VALID, null, port);
        }

        public static Result error(ValidationStatus status, String errorMessage) {
            return new Result(status, errorMessage, -1);
        }

        public boolean isValid() {
            return status == ValidationStatus.VALID;
        }

        public ValidationStatus getStatus() {
            return status;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public int getParsedPort() {
            return parsedPort;
        }
    }

    private PortForwardingRuleValidator() {}

    /**
     * Validates host port string.
     */
    public static Result validateHostPort(String portText) {
        if (portText == null || portText.trim().isEmpty()) {
            return Result.error(ValidationStatus.EMPTY_FIELD, "Host port cannot be empty.");
        }
        try {
            int port = Integer.parseInt(portText.trim());
            if (port < MIN_PORT || port > MAX_PORT) {
                return Result.error(
                        ValidationStatus.INVALID_HOST_PORT,
                        "Host port must be between " + MIN_PORT + " and " + MAX_PORT + ".");
            }
            return Result.valid(port);
        } catch (NumberFormatException e) {
            return Result.error(ValidationStatus.INVALID_HOST_PORT, "Host port must be a valid number.");
        }
    }

    /**
     * Validates guest port string.
     */
    public static Result validateGuestPort(String portText) {
        if (portText == null || portText.trim().isEmpty()) {
            return Result.error(ValidationStatus.EMPTY_FIELD, "Guest port cannot be empty.");
        }
        try {
            int port = Integer.parseInt(portText.trim());
            if (port < 1 || port > MAX_PORT) {
                return Result.error(
                        ValidationStatus.INVALID_GUEST_PORT,
                        "Guest port must be between 1 and " + MAX_PORT + ".");
            }
            return Result.valid(port);
        } catch (NumberFormatException e) {
            return Result.error(ValidationStatus.INVALID_GUEST_PORT, "Guest port must be a valid number.");
        }
    }

    /**
     * Checks if host port conflicts with existing rules under the same protocol.
     */
    public static Result validateConflict(int hostPort, Protocol protocol, List<PortForwardingRule> existingRules) {
        if (existingRules != null) {
            for (PortForwardingRule rule : existingRules) {
                if (rule.hostPort() == hostPort && rule.protocol() == protocol) {
                    return Result.error(
                            ValidationStatus.CONFLICT_HOST_PORT,
                            "Host port " + hostPort + "/" + protocol + " is already mapped by another rule.");
                }
            }
        }
        return Result.valid(hostPort);
    }

    /**
     * Comprehensive validation of a new port forwarding rule.
     */
    public static Result validateRule(
            String hostPortText,
            String guestPortText,
            Protocol protocol,
            List<PortForwardingRule> existingRules) {
        Result hostResult = validateHostPort(hostPortText);
        if (!hostResult.isValid()) {
            return hostResult;
        }

        Result guestResult = validateGuestPort(guestPortText);
        if (!guestResult.isValid()) {
            return guestResult;
        }

        Result conflictResult = validateConflict(hostResult.getParsedPort(), protocol, existingRules);
        if (!conflictResult.isValid()) {
            return conflictResult;
        }

        return Result.valid(hostResult.getParsedPort());
    }
}
