package io.github.nyzeep.droidlayer.runtime.contract;

import java.util.Objects;

/**
 * Thrown when a port forwarding rule conflicts with an existing active rule or
 * an already bound host port (ADR-0008, ADR-0010).
 */
public class PortConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Protocol protocol;
    private final int hostPort;
    private final String bindAddress;
    private final String conflictingRuleId;

    public PortConflictException(
            Protocol protocol,
            int hostPort,
            String bindAddress,
            String conflictingRuleId) {
        this(
                protocol,
                hostPort,
                bindAddress,
                conflictingRuleId,
                "Host port " + hostPort + " (" + protocol + ") on " + bindAddress
                        + " is already in use" + (conflictingRuleId != null ? " by rule " + conflictingRuleId : ""));
    }

    public PortConflictException(
            Protocol protocol,
            int hostPort,
            String bindAddress,
            String conflictingRuleId,
            String message) {
        super(message);
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.hostPort = hostPort;
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.conflictingRuleId = conflictingRuleId;
    }

    public Protocol protocol() {
        return protocol;
    }

    public int hostPort() {
        return hostPort;
    }

    public String bindAddress() {
        return bindAddress;
    }

    public String conflictingRuleId() {
        return conflictingRuleId;
    }
}
