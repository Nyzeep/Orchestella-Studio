package io.github.nyzeep.orchestella.runtime.instance.duallane;

/** Typed admission rejection; callers branch on the fixed code. */
public final class LaneRejectedException extends Exception {
    private static final long serialVersionUID = 1L;
    private final LaneRejectCode code;

    public LaneRejectedException(LaneRejectCode code) {
        super(code.name());
        this.code = code;
    }

    public LaneRejectCode code() {
        return code;
    }
}
