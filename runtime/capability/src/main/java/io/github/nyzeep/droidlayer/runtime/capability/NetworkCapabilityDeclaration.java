package io.github.nyzeep.droidlayer.runtime.capability;

import io.github.nyzeep.droidlayer.runtime.contract.FullLinuxContract;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The network and port-forwarding capability matrix as DATA (ADR-0002: capability
 * differences are only ever expressed as capability-matrix data, never strategy
 * branches), declared per execution strategy with an honest verification level
 * and evidence provenance (Ticket 36 AC-4).
 *
 * <p>Verification levels are ordered: {@code CONTRACT_VERIFIED} means the
 * strategy-neutral contract suite passes on that strategy's implementation;
 * {@code DEVICE_VERIFIED} means an end-to-end run was additionally observed on
 * a real device. A capability is never claimed beyond what was observed — in
 * particular the AVF profile's device-level network smoke is a HITL prototype
 * item (Ticket 17) and is deliberately NOT declared here yet.
 *
 * <p>The capability identifiers intentionally mirror the vocabulary the shared
 * contract uses ({@code NetworkContract}, {@code PortForwardingEngine}); if the
 * contract's capability enumeration is ever extended through the architecture
 * change gate, these entries migrate onto it without changing the data.
 */
public final class NetworkCapabilityDeclaration {
    private NetworkCapabilityDeclaration() {}

    /** Network-surface capability identifiers, strategy-neutral. */
    public enum NetworkCapability {
        OUTBOUND_NETWORK,
        PORT_FORWARDING
    }

    /** How strongly a capability is verified for one strategy, ordered. */
    public enum VerificationLevel {
        /** The shared contract suite passes on this strategy's implementation. */
        CONTRACT_VERIFIED,
        /** {@code CONTRACT_VERIFIED} plus an observed real-device end-to-end run. */
        DEVICE_VERIFIED
    }

    /** One capability-matrix entry: capability + level + evidence provenance. */
    public static final class Entry {
        private final NetworkCapability capability;
        private final VerificationLevel level;
        private final String evidenceTicket;
        private final String note;

        private Entry(
                NetworkCapability capability,
                VerificationLevel level,
                String evidenceTicket,
                String note) {
            this.capability = Objects.requireNonNull(capability, "capability");
            this.level = Objects.requireNonNull(level, "level");
            this.evidenceTicket = Objects.requireNonNull(evidenceTicket, "evidenceTicket");
            this.note = Objects.requireNonNull(note, "note");
        }

        public NetworkCapability capability() {
            return capability;
        }

        public VerificationLevel level() {
            return level;
        }

        /** The ticket whose evidence backs the declared level. */
        public String evidenceTicket() {
            return evidenceTicket;
        }

        /** Provenance note; diagnostics data, never UI copy. */
        public String note() {
            return note;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Entry)) {
                return false;
            }
            Entry entry = (Entry) o;
            return capability == entry.capability
                    && level == entry.level
                    && evidenceTicket.equals(entry.evidenceTicket)
                    && note.equals(entry.note);
        }

        @Override
        public int hashCode() {
            return Objects.hash(capability, level, evidenceTicket, note);
        }

        @Override
        public String toString() {
            return "Entry{" + capability + ", " + level
                    + ", evidence=" + evidenceTicket + ", " + note + "}";
        }
    }

    private static final List<Entry> SOFTWARE_VIRTUALIZATION = List.of(
            new Entry(
                    NetworkCapability.OUTBOUND_NETWORK,
                    VerificationLevel.DEVICE_VERIFIED,
                    "26/27",
                    "real-device apk update end-to-end (HONOR 80 GT, SLIRP outbound NAT)"),
            new Entry(
                    NetworkCapability.PORT_FORWARDING,
                    VerificationLevel.CONTRACT_VERIFIED,
                    "29",
                    "port-forwarding contract suite green on the QEMU implementation"));

    private static final List<Entry> HARDWARE_VIRTUALIZATION = List.of(
            new Entry(
                    NetworkCapability.OUTBOUND_NETWORK,
                    VerificationLevel.CONTRACT_VERIFIED,
                    "36",
                    "Ticket 27 outbound network contract green on the AVF implementation"
                            + " (crosvm network backend switch + guest DNS key assembly)"),
            new Entry(
                    NetworkCapability.PORT_FORWARDING,
                    VerificationLevel.CONTRACT_VERIFIED,
                    "36",
                    "Ticket 29 forwarding contract suite re-run green on the AVF vsock"
                            + " forwarding engine (same suite, no rewritten scenarios)"));

    /**
     * The immutable network capability entries declared for one strategy.
     */
    public static List<Entry> forStrategy(FullLinuxContract.ExecutionStrategy strategy) {
        List<Entry> source;
        if (strategy == FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION) {
            source = SOFTWARE_VIRTUALIZATION;
        } else if (strategy == FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION) {
            source = HARDWARE_VIRTUALIZATION;
        } else {
            throw new IllegalArgumentException("unknown execution strategy: " + strategy);
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    /** The capability identifiers declared for one strategy, in declaration order. */
    public static List<NetworkCapability> capabilities(
            FullLinuxContract.ExecutionStrategy strategy) {
        List<NetworkCapability> result = new ArrayList<>();
        for (Entry entry : forStrategy(strategy)) {
            result.add(entry.capability());
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Whether a capability meets at least the requested verification level for
     * one strategy. {@code DEVICE_VERIFIED} implies {@code CONTRACT_VERIFIED}.
     */
    public static boolean isVerified(
            FullLinuxContract.ExecutionStrategy strategy,
            NetworkCapability capability,
            VerificationLevel required) {
        for (Entry entry : forStrategy(strategy)) {
            if (entry.capability() != capability) {
                continue;
            }
            if (required == VerificationLevel.CONTRACT_VERIFIED) {
                return true;
            }
            return entry.level() == VerificationLevel.DEVICE_VERIFIED;
        }
        return false;
    }
}
