package io.github.nyzeep.orchestella.runtime.fastlane.capability;

import java.util.Objects;

/** Explicit requirements; absence of a requirement is not a capability claim. */
public final class RequirementProfile {
    private final boolean terminal;
    private final boolean persistentStorage;
    private final boolean tcpProxy;
    private final boolean sealedToolbox;
    private final boolean distccOffload;
    private final boolean exportImport;
    private final boolean systemd;
    private final boolean docker;
    private final boolean nativeNetwork;
    private final boolean gpu;
    private final boolean nativePackageInstall;

    private RequirementProfile(Builder builder) {
        terminal = builder.terminal;
        persistentStorage = builder.persistentStorage;
        tcpProxy = builder.tcpProxy;
        sealedToolbox = builder.sealedToolbox;
        distccOffload = builder.distccOffload;
        exportImport = builder.exportImport;
        systemd = builder.systemd;
        docker = builder.docker;
        nativeNetwork = builder.nativeNetwork;
        gpu = builder.gpu;
        nativePackageInstall = builder.nativePackageInstall;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RequirementProfile none() {
        return builder().build();
    }

    public static RequirementProfile interactiveShell() {
        return builder().terminal(true).persistentStorage(true).build();
    }

    public static RequirementProfile compile() {
        return builder()
                .terminal(true)
                .persistentStorage(true)
                .sealedToolbox(true)
                .build();
    }

    /** Compile plus the optional QEMU-to-Fast-Lane distcc offload. */
    public static RequirementProfile compileWithDistccOffload() {
        return builder()
                .terminal(true)
                .persistentStorage(true)
                .sealedToolbox(true)
                .distccOffload(true)
                .build();
    }

    public static RequirementProfile fullLinux() {
        return builder()
                .terminal(true)
                .persistentStorage(true)
                .nativeNetwork(true)
                .systemd(true)
                .docker(true)
                .nativePackageInstall(true)
                .build();
    }

    public static RequirementProfile ofWorkload(Workload workload) {
        Objects.requireNonNull(workload, "workload");
        return switch (workload) {
            case INTERACTIVE_SHELL -> interactiveShell();
            case COMPILE -> compile();
            case FULL_LINUX -> fullLinux();
            case UNKNOWN -> none();
        };
    }

    /** True when any requirement cannot be satisfied by the Fast Lane contract. */
    public boolean requiresFullLinux() {
        return systemd || docker || nativeNetwork || gpu || nativePackageInstall;
    }

    public boolean terminal() {
        return terminal;
    }

    public boolean persistentStorage() {
        return persistentStorage;
    }

    public boolean tcpProxy() {
        return tcpProxy;
    }

    public boolean sealedToolbox() {
        return sealedToolbox;
    }

    public boolean distccOffload() {
        return distccOffload;
    }

    public boolean exportImport() {
        return exportImport;
    }

    public boolean systemd() {
        return systemd;
    }

    public boolean docker() {
        return docker;
    }

    public boolean nativeNetwork() {
        return nativeNetwork;
    }

    public boolean gpu() {
        return gpu;
    }

    public boolean nativePackageInstall() {
        return nativePackageInstall;
    }

    /** Mutable builder; each setter returns this builder. */
    public static final class Builder {
        private boolean terminal;
        private boolean persistentStorage;
        private boolean tcpProxy;
        private boolean sealedToolbox;
        private boolean distccOffload;
        private boolean exportImport;
        private boolean systemd;
        private boolean docker;
        private boolean nativeNetwork;
        private boolean gpu;
        private boolean nativePackageInstall;

        public Builder terminal(boolean value) {
            terminal = value;
            return this;
        }

        public Builder persistentStorage(boolean value) {
            persistentStorage = value;
            return this;
        }

        public Builder tcpProxy(boolean value) {
            tcpProxy = value;
            return this;
        }

        public Builder sealedToolbox(boolean value) {
            sealedToolbox = value;
            return this;
        }

        public Builder distccOffload(boolean value) {
            distccOffload = value;
            return this;
        }

        public Builder exportImport(boolean value) {
            exportImport = value;
            return this;
        }

        public Builder systemd(boolean value) {
            systemd = value;
            return this;
        }

        public Builder docker(boolean value) {
            docker = value;
            return this;
        }

        public Builder nativeNetwork(boolean value) {
            nativeNetwork = value;
            return this;
        }

        public Builder gpu(boolean value) {
            gpu = value;
            return this;
        }

        public Builder nativePackageInstall(boolean value) {
            nativePackageInstall = value;
            return this;
        }

        public RequirementProfile build() {
            return new RequirementProfile(this);
        }
    }
}
