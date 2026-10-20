package io.github.nyzeep.orchestella.runtime.contract;

import java.util.Objects;

/**
 * What one launch actually assembled, in strategy-neutral terms. This is the
 * single input the {@link FullLinuxContract.CapabilityMatrix} projects from:
 * a capability appears only when its adapter was constructed, and an absent
 * adapter is {@code false} rather than a promise for later.
 *
 * <p>A descriptor is deliberately narrow. It records observable wiring, not
 * product policy, performance, or a device verdict.
 */
public final class SessionCapabilities {
    /** The display transport that was actually constructed. */
    public enum DisplayTransport {
        NONE,
        /** RFB over the engine's display stream: pixel frames, keyboard and pointer events. */
        RFB,
        /** A real Wayland/DLFR adapter. Never inferred from an RFB stream. */
        WAYLAND
    }

    /** Whether the session can stop the runtime it launched. */
    public enum Ownership {
        CHANNEL_ONLY,
        RUNTIME_OWNED
    }

    private final FullLinuxContract.ExecutionStrategy executionStrategy;
    private final Ownership ownership;
    private final boolean terminal;
    private final boolean bridge;
    private final DisplayTransport display;
    private final boolean audioOutput;
    private final boolean clipboardSync;
    private final boolean pointerCapture;
    private final boolean sharedDirectory;
    private final boolean serviceForward;
    private final boolean hardwareAcceleration;
    private final boolean gpuAcceleration;

    private SessionCapabilities(Builder builder) {
        this.executionStrategy = builder.executionStrategy;
        this.ownership = builder.ownership;
        this.terminal = builder.terminal;
        this.bridge = builder.bridge;
        this.display = builder.display;
        this.audioOutput = builder.audioOutput;
        this.clipboardSync = builder.clipboardSync;
        this.pointerCapture = builder.pointerCapture;
        this.sharedDirectory = builder.sharedDirectory;
        this.serviceForward = builder.serviceForward;
        this.hardwareAcceleration = builder.hardwareAcceleration;
        this.gpuAcceleration = builder.gpuAcceleration;
        if (executionStrategy == FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION
                && !hardwareAcceleration) {
            throw new IllegalArgumentException(
                    "hardware virtualization must declare hardware acceleration");
        }
        if (executionStrategy == FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION
                && hardwareAcceleration) {
            throw new IllegalArgumentException(
                    "software virtualization cannot claim hardware acceleration");
        }
    }

    public static Builder builder(FullLinuxContract.ExecutionStrategy executionStrategy) {
        return new Builder(executionStrategy);
    }

    public FullLinuxContract.ExecutionStrategy executionStrategy() {
        return executionStrategy;
    }

    public Ownership ownership() {
        return ownership;
    }

    public boolean terminal() {
        return terminal;
    }

    public boolean bridge() {
        return bridge;
    }

    public DisplayTransport display() {
        return display;
    }

    public boolean hasDisplay() {
        return display != DisplayTransport.NONE;
    }

    public boolean audioOutput() {
        return audioOutput;
    }

    public boolean clipboardSync() {
        return clipboardSync;
    }

    /**
     * Android pointer capture. RFB pointer events are input to the guest, not
     * a captured host pointer, so they never set this flag.
     */
    public boolean pointerCapture() {
        return pointerCapture;
    }

    public boolean sharedDirectory() {
        return sharedDirectory;
    }

    public boolean serviceForward() {
        return serviceForward;
    }

    public boolean hardwareAcceleration() {
        return hardwareAcceleration;
    }

    public boolean gpuAcceleration() {
        return gpuAcceleration;
    }

    /** Returns the same declaration with the 9p server state replaced. */
    public SessionCapabilities withSharedDirectory(boolean wired) {
        return toBuilder().sharedDirectory(wired).build();
    }

    public Builder toBuilder() {
        return new Builder(executionStrategy)
                .ownership(ownership)
                .terminal(terminal)
                .bridge(bridge)
                .display(display)
                .audioOutput(audioOutput)
                .clipboardSync(clipboardSync)
                .pointerCapture(pointerCapture)
                .sharedDirectory(sharedDirectory)
                .serviceForward(serviceForward)
                .hardwareAcceleration(hardwareAcceleration)
                .gpuAcceleration(gpuAcceleration);
    }

    /** Fluent construction; every field starts absent. */
    public static final class Builder {
        private final FullLinuxContract.ExecutionStrategy executionStrategy;
        private Ownership ownership = Ownership.CHANNEL_ONLY;
        private boolean terminal;
        private boolean bridge;
        private DisplayTransport display = DisplayTransport.NONE;
        private boolean audioOutput;
        private boolean clipboardSync;
        private boolean pointerCapture;
        private boolean sharedDirectory;
        private boolean serviceForward;
        private boolean hardwareAcceleration;
        private boolean gpuAcceleration;

        private Builder(FullLinuxContract.ExecutionStrategy executionStrategy) {
            this.executionStrategy =
                    Objects.requireNonNull(executionStrategy, "executionStrategy");
        }

        public Builder ownership(Ownership ownership) {
            this.ownership = Objects.requireNonNull(ownership, "ownership");
            return this;
        }

        public Builder terminal(boolean terminal) {
            this.terminal = terminal;
            return this;
        }

        public Builder bridge(boolean bridge) {
            this.bridge = bridge;
            return this;
        }

        public Builder display(DisplayTransport display) {
            this.display = Objects.requireNonNull(display, "display");
            return this;
        }

        public Builder audioOutput(boolean audioOutput) {
            this.audioOutput = audioOutput;
            return this;
        }

        public Builder clipboardSync(boolean clipboardSync) {
            this.clipboardSync = clipboardSync;
            return this;
        }

        public Builder pointerCapture(boolean pointerCapture) {
            this.pointerCapture = pointerCapture;
            return this;
        }

        public Builder sharedDirectory(boolean sharedDirectory) {
            this.sharedDirectory = sharedDirectory;
            return this;
        }

        public Builder serviceForward(boolean serviceForward) {
            this.serviceForward = serviceForward;
            return this;
        }

        public Builder hardwareAcceleration(boolean hardwareAcceleration) {
            this.hardwareAcceleration = hardwareAcceleration;
            return this;
        }

        public Builder gpuAcceleration(boolean gpuAcceleration) {
            this.gpuAcceleration = gpuAcceleration;
            return this;
        }

        public SessionCapabilities build() {
            return new SessionCapabilities(this);
        }
    }
}
