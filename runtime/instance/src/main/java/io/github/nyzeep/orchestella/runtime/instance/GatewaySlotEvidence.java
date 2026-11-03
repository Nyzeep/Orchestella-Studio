package io.github.nyzeep.orchestella.runtime.instance;

import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of everything the arbiter is allowed to look at.
 *
 * <p>This type carries evidence only; it performs no probing, so the production collector and the
 * contract test feed it through the same door. The two flags describe the OS state as observed
 * from outside this process:
 *
 * <ul>
 *   <li>{@code portBound} — the derived TCP port could not be bound on loopback, i.e. something
 *       is listening on it;</li>
 *   <li>{@code apiSocketPresent} — the gvproxy unix API socket of this instance exists on disk;</li>
 *   <li>{@code processes} — the process table snapshot, empty when nothing could be read;</li>
 *   <li>{@code processTableReadable} — whether the snapshot is trustworthy at all. When this is
 *       false the other fields carry no information and must not be read as "free".</li>
 * </ul>
 */
public final class GatewaySlotEvidence {

    private final boolean portBound;
    private final boolean apiSocketPresent;
    private final List<ProcessRef> processes;
    private final boolean processTableReadable;

    /**
     * Creates a snapshot.
     *
     * @param portBound whether the derived port is bound
     * @param apiSocketPresent whether this instance's gvproxy API socket exists
     * @param processes process table snapshot; copied, must not contain null
     * @param processTableReadable whether the snapshot could be read completely
     */
    public GatewaySlotEvidence(
            boolean portBound,
            boolean apiSocketPresent,
            List<ProcessRef> processes,
            boolean processTableReadable) {
        this.portBound = portBound;
        this.apiSocketPresent = apiSocketPresent;
        this.processes = List.copyOf(Objects.requireNonNull(processes, "processes"));
        this.processTableReadable = processTableReadable;
    }

    /**
     * Evidence of an idle slot as seen by a complete probe: port unbound, no socket, empty table.
     *
     * @return a readable snapshot that says the slot is free
     */
    public static GatewaySlotEvidence free() {
        return new GatewaySlotEvidence(false, false, List.of(), true);
    }

    /**
     * Evidence of a probe that could not run, e.g. because the process table was unreadable.
     *
     * <p>The port flag is false here, which is exactly why the arbiter must consult
     * {@link #processTableReadable()} first: an unread probe is not an idle port.
     *
     * @return a snapshot marked as not readable
     */
    public static GatewaySlotEvidence unavailable() {
        return new GatewaySlotEvidence(false, false, List.of(), false);
    }

    /** @return whether the derived port is bound */
    public boolean portBound() {
        return portBound;
    }

    /** @return whether this instance's gvproxy API socket exists */
    public boolean apiSocketPresent() {
        return apiSocketPresent;
    }

    /** @return the immutable process table snapshot, never null and never containing null */
    public List<ProcessRef> processes() {
        return processes;
    }

    /** @return whether the process table snapshot is trustworthy */
    public boolean processTableReadable() {
        return processTableReadable;
    }

    /**
     * Reports whether any process in the snapshot carries the given identity string in its
     * command line.
     *
     * @param configFileName identity string, normally {@code net-<instanceId>.yaml}; a null or
     *     empty value yields false
     * @return true when a matching process exists
     */
    public boolean hasIdentityFor(String configFileName) {
        if (configFileName == null || configFileName.isEmpty()) {
            return false;
        }
        for (ProcessRef process : processes) {
            if (process.cmdlineContains(configFileName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GatewaySlotEvidence evidence)) {
            return false;
        }
        return portBound == evidence.portBound
                && apiSocketPresent == evidence.apiSocketPresent
                && processTableReadable == evidence.processTableReadable
                && processes.equals(evidence.processes);
    }

    @Override
    public int hashCode() {
        int result = Boolean.hashCode(portBound);
        result = 31 * result + Boolean.hashCode(apiSocketPresent);
        result = 31 * result + Boolean.hashCode(processTableReadable);
        return 31 * result + processes.hashCode();
    }

    @Override
    public String toString() {
        return "GatewaySlotEvidence{portBound=" + portBound
                + ", apiSocketPresent=" + apiSocketPresent
                + ", processTableReadable=" + processTableReadable
                + ", processes=" + processes
                + '}';
    }
}
