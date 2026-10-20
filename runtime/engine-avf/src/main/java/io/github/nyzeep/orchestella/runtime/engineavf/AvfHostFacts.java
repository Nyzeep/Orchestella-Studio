package io.github.nyzeep.orchestella.runtime.engineavf;

/**
 * The pre-read host facts admission may rely on. Only a definitive
 * {@link Support#UNSUPPORTED} rejects a launch; {@link Support#UNKNOWN} is
 * passed through so the reflective adapter, which owns the platform call,
 * makes the final decision instead of failing early on an unread fact.
 */
public record AvfHostFacts(Support nonProtectedVm) {
    public enum Support {
        SUPPORTED,
        UNSUPPORTED,
        UNKNOWN
    }

    public AvfHostFacts {
        if (nonProtectedVm == null) nonProtectedVm = Support.UNKNOWN;
    }

    public static AvfHostFacts supported() {
        return new AvfHostFacts(Support.SUPPORTED);
    }

    public static AvfHostFacts unsupported() {
        return new AvfHostFacts(Support.UNSUPPORTED);
    }

    public static AvfHostFacts unknown() {
        return new AvfHostFacts(Support.UNKNOWN);
    }
}
