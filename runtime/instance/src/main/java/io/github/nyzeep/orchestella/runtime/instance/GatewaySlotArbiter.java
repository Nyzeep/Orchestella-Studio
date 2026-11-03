package io.github.nyzeep.orchestella.runtime.instance;

import java.util.Objects;

/**
 * Decides who holds the derived gateway slot of an instance, and describes that decision in a
 * stable short sentence.
 *
 * <p>The module is a pure function over {@link GatewaySlotEvidence}: it does not probe the OS, start
 * threads, touch the file system or mutate the process table. It exists because the production
 * start path only asks one question — "is the derived port bindable?" — and that question cannot
 * distinguish a previous gateway of <em>this</em> instance from an unrelated listener. The decision
 * order below makes that distinction explicit and is evaluated strictly in this sequence:
 *
 * <ol>
 *   <li>{@link GatewaySlotEvidence#processTableReadable()} is false &rarr;
 *       {@link GatewaySlotVerdict#EVIDENCE_UNAVAILABLE};</li>
 *   <li>{@link GatewaySlotEvidence#portBound()} is false &rarr; {@link GatewaySlotVerdict#FREE};</li>
 *   <li>the snapshot carries the identity of this instance &rarr;
 *       {@link GatewaySlotVerdict#ORPHAN_OF_THIS_INSTANCE};</li>
 *   <li>otherwise &rarr; {@link GatewaySlotVerdict#HELD_BY_UNKNOWN}.</li>
 * </ol>
 *
 * <p>The identity string is {@code net-<instanceId>.yaml}: the very file name that the production
 * start path writes into the runtime directory and passes to gvproxy through {@code -config}, so it
 * appears verbatim in that process's argument vector. The derived port is shared by all instances
 * with the same {@link String#hashCode()}, therefore a bound port alone is never evidence of
 * ownership.
 *
 * <p><b>Intentionally not wired into production.</b> As of ticket Q3 the product has no
 * cross-process gateway ownership surface: every stop path funnels into
 * {@code RuntimeProcessSupervisor.stop()}, which only knows handles held by this process, so only
 * the decision table (not the evidence collection, not the reclaim action) is portable. This type
 * is therefore exercised solely by {@code GatewaySlotArbiterContractTest} and is kept as a deep
 * module on purpose; it is not dead code. Adding a production caller is a separate decision.
 *
 * <p>The decision is kept out of the start path for three reasons:
 *
 * <ul>
 *   <li><b>No handle exists across processes.</b> A reclaimed gateway would have to be signalled
 *       through {@code android.system.Os.kill} or {@code ProcessHandle.of(pid)}, and
 *       {@code app/src/main} has no precedent for either; the whole ownership chain today is built
 *       on {@code java.lang.Process}.</li>
 *   <li><b>A wrong kill is unrecoverable.</b> Killing another instance's gvproxy tears down the
 *       netdev socket its QEMU is connected to, and that QEMU aborts without retrying — so a
 *       misclassification is worse than the current refusal to start.</li>
 *   <li><b>The confirmation chain is unverifiable here.</b> Reclaiming needs
 *       kill &rarr; re-probe &rarr; still bound means {@code HELD_BY_UNKNOWN}, and that sequence only
 *       holds on a real device with a real {@code /proc}; no JVM test can falsify it.</li>
 * </ul>
 */
public final class GatewaySlotArbiter {

    /** First port of the derived range, matching {@code NetworkGatewayDaemon.listenPortFor}. */
    public static final int PORT_BASE = 24100;

    /** Number of ports in the derived range, i.e. the modulus applied to the id hash. */
    public static final int PORT_SLOTS = 800;

    private GatewaySlotArbiter() {
    }

    /**
     * Derives the gateway port of an instance.
     *
     * <p>Mirrors {@code NetworkGatewayDaemon.listenPortFor} expression for expression, including
     * the null case, so that the two can never disagree:
     * {@code PORT_BASE + Math.floorMod(instanceId != null ? instanceId.hashCode() : 0, PORT_SLOTS)}.
     * {@link String#hashCode()} is fixed by the language specification, hence the same id always
     * maps to the same port across processes and restarts.
     *
     * @param instanceId instance identifier, may be null
     * @return a port in {@code [PORT_BASE, PORT_BASE + PORT_SLOTS - 1]}
     */
    public static int derivedPort(String instanceId) {
        return PORT_BASE + Math.floorMod(instanceId != null ? instanceId.hashCode() : 0, PORT_SLOTS);
    }

    /**
     * Derives the gvproxy configuration file name of an instance.
     *
     * <p>Mirrors {@code NetworkGatewayDaemon}, which writes {@code runtimeDirectory/net-<id>.yaml}
     * and passes that path to gvproxy: the name is therefore an identity string visible in the
     * argument vector of a running gateway.
     *
     * @param instanceId instance identifier, must not be null
     * @return the file name, never an absolute path
     */
    public static String configFileName(String instanceId) {
        return "net-" + Objects.requireNonNull(instanceId, "instanceId") + ".yaml";
    }

    /**
     * Derives the gvproxy API socket directory name of an instance.
     *
     * <p>Mirrors {@code NetworkGatewayDaemon}, which resolves
     * {@code filesDir/net-api/<hex(hashCode)>/api.sock} with an unsigned hexadecimal hash so that
     * negative hashes stay readable as directory names.
     *
     * @param instanceId instance identifier, must not be null
     * @return the directory name, never an absolute path
     */
    public static String apiSocketDirectoryName(String instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        return Integer.toUnsignedString(instanceId.hashCode(), 16);
    }

    /**
     * Classifies the gateway slot of an instance from the given evidence.
     *
     * @param instanceId instance identifier used to derive the identity string
     * @param evidence immutable probe snapshot, must not be null
     * @return the verdict; never null, never a guess when evidence is missing
     */
    public static GatewaySlotVerdict verdict(String instanceId, GatewaySlotEvidence evidence) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(evidence, "evidence");
        if (!evidence.processTableReadable()) {
            return GatewaySlotVerdict.EVIDENCE_UNAVAILABLE;
        }
        if (!evidence.portBound()) {
            return GatewaySlotVerdict.FREE;
        }
        if (evidence.hasIdentityFor(configFileName(instanceId))) {
            return GatewaySlotVerdict.ORPHAN_OF_THIS_INSTANCE;
        }
        return GatewaySlotVerdict.HELD_BY_UNKNOWN;
    }

    /**
     * Renders a verdict as a short, stable sentence suitable for a log line or a user message.
     *
     * <p>The text carries the derived port but no absolute path and no pid, so it is safe to show
     * to a user and stable enough to assert on. The port is derived here; callers do not pass one.
     *
     * @param instanceId instance identifier used to derive the port
     * @param verdict verdict to describe, must not be null
     * @return a non-empty sentence
     */
    public static String describe(String instanceId, GatewaySlotVerdict verdict) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(verdict, "verdict");
        int port = derivedPort(instanceId);
        return switch (verdict) {
            case FREE -> "gateway slot is free";
            case ORPHAN_OF_THIS_INSTANCE ->
                    "port " + port + " is held by a previous gateway of this instance";
            case HELD_BY_UNKNOWN -> "port " + port + " is in use by an unidentified process";
            case EVIDENCE_UNAVAILABLE -> "cannot determine gateway slot owner for port " + port;
        };
    }
}
