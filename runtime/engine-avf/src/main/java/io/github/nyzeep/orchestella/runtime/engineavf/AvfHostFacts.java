package io.github.nyzeep.orchestella.runtime.engineavf;

/**
 * The pre-read host facts admission may rely on. Only a definitive
 * {@link Support#UNSUPPORTED} rejects a launch or a network plan;
 * {@link Support#UNKNOWN} is passed through so the reflective adapter, which
 * owns the platform call, makes the final decision instead of failing early on
 * an unread fact.
 */
public record AvfHostFacts(Support nonProtectedVm, Support nativeTap) {
    public enum Support {
        SUPPORTED,
        UNSUPPORTED,
        UNKNOWN
    }

    public AvfHostFacts {
        if (nonProtectedVm == null) nonProtectedVm = Support.UNKNOWN;
        if (nativeTap == null) nativeTap = Support.UNKNOWN;
    }

    /** Backwards-compatible shape: an unread TAP fact is UNKNOWN, never a guess. */
    public AvfHostFacts(Support nonProtectedVm) {
        this(nonProtectedVm, Support.UNKNOWN);
    }

    public AvfHostFacts withNativeTap(Support value) {
        return new AvfHostFacts(nonProtectedVm, value);
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
