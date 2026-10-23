package io.github.nyzeep.orchestella.runtime.instance.duallane;

/** Typed start failure; the code is fixed and contains no host detail. */
public final class LaneStartException extends Exception {
    private static final long serialVersionUID = 1L;
    private final String code;

    public LaneStartException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
