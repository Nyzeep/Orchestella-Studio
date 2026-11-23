package io.github.nyzeep.orchestella.runtime.engineuml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable launch specification for one standalone User-Mode Linux guest process.
 *
 * <p>Builds a fixed, shell-free argv array for {@code libuml_kernel.so} and a minimal
 * environment. Secrets such as the Bridge authentication token and guest passwords never appear
 * in argv or environment variables.
 */
public final class UmlLaunchSpec {
    public static final int DEFAULT_MEMORY_MB = 512;
    public static final int MIN_MEMORY_MB = 256;
    public static final int DEFAULT_CONTROL_IN_FD = 3;
    public static final int DEFAULT_CONTROL_OUT_FD = 4;
    private static final String DEFAULT_PATH = "/system/bin:/system/xbin:/vendor/bin";

    private final String kernelPath;
    private final String stubExePath;
    private final int memMb;
    private final Path ephemeralRootDir;
    private final Path runtimeDir;
    private final int controlInFd;
    private final int controlOutFd;
    private final List<String> extraArgs;

    private UmlLaunchSpec(Builder builder) {
        this.kernelPath = requireNonBlank(builder.kernelPath, "kernelPath");
        this.stubExePath = requireNonBlank(builder.stubExePath, "stubExePath");
        if (builder.memMb < MIN_MEMORY_MB) {
            throw new IllegalArgumentException(
                    "memMb must be >= " + MIN_MEMORY_MB + ", got: " + builder.memMb);
        }
        this.memMb = builder.memMb;
        this.ephemeralRootDir = Objects.requireNonNull(builder.ephemeralRootDir, "ephemeralRootDir");
        this.runtimeDir = Objects.requireNonNull(builder.runtimeDir, "runtimeDir");
        if (builder.controlInFd < 3 || builder.controlOutFd < 3) {
            throw new IllegalArgumentException(
                    "control fds must not collide with stdio (fd >= 3 required)");
        }
        if (builder.controlInFd == builder.controlOutFd) {
            throw new IllegalArgumentException("controlInFd and controlOutFd must be distinct");
        }
        this.controlInFd = builder.controlInFd;
        this.controlOutFd = builder.controlOutFd;
        this.extraArgs = List.copyOf(builder.extraArgs);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String kernelPath() {
        return kernelPath;
    }

    public String stubExePath() {
        return stubExePath;
    }

    public int memMb() {
        return memMb;
    }

    public Path ephemeralRootDir() {
        return ephemeralRootDir;
    }

    public Path runtimeDir() {
        return runtimeDir;
    }

    public int controlInFd() {
        return controlInFd;
    }

    public int controlOutFd() {
        return controlOutFd;
    }

    public List<String> extraArgs() {
        return extraArgs;
    }

    /** Builds the shell-free kernel command line arguments. */
    public List<String> argv() {
        List<String> args = new ArrayList<>(15 + extraArgs.size());
        args.add(kernelPath);
        args.add("mem=" + memMb + "M");
        args.add("rw");
        args.add("loglevel=3");
        args.add("con=null");
        args.add("ssl=null");
        args.add("con0=fd:0,fd:1");
        args.add("ssl0=fd:" + controlInFd + ",fd:" + controlOutFd);
        args.add("seccomp=on");
        args.add("panic=1");
        args.add("stub_exe=" + stubExePath);
        args.add("umroot=" + ephemeralRootDir.toAbsolutePath());
        args.add("umrootfstype=hostfs");
        args.add("um.standalone=1");
        args.addAll(extraArgs);
        return Collections.unmodifiableList(args);
    }

    /** Builds the minimal host process environment for UML. */
    public Map<String, String> environment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("TMPDIR", runtimeDir.resolve("tmp").toAbsolutePath().toString());
        env.put("HOME", runtimeDir.toAbsolutePath().toString());
        env.put("PATH", DEFAULT_PATH);
        return Collections.unmodifiableMap(env);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static final class Builder {
        private String kernelPath;
        private String stubExePath;
        private int memMb = DEFAULT_MEMORY_MB;
        private Path ephemeralRootDir;
        private Path runtimeDir;
        private int controlInFd = DEFAULT_CONTROL_IN_FD;
        private int controlOutFd = DEFAULT_CONTROL_OUT_FD;
        private List<String> extraArgs = List.of();

        private Builder() {}

        public Builder kernelPath(String kernelPath) {
            this.kernelPath = kernelPath;
            return this;
        }

        public Builder stubExePath(String stubExePath) {
            this.stubExePath = stubExePath;
            return this;
        }

        public Builder memMb(int memMb) {
            this.memMb = memMb;
            return this;
        }

        public Builder ephemeralRootDir(Path ephemeralRootDir) {
            this.ephemeralRootDir = ephemeralRootDir;
            return this;
        }

        public Builder runtimeDir(Path runtimeDir) {
            this.runtimeDir = runtimeDir;
            return this;
        }

        public Builder controlInFd(int controlInFd) {
            this.controlInFd = controlInFd;
            return this;
        }

        public Builder controlOutFd(int controlOutFd) {
            this.controlOutFd = controlOutFd;
            return this;
        }

        public Builder extraArgs(List<String> extraArgs) {
            this.extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
            return this;
        }

        public UmlLaunchSpec build() {
            return new UmlLaunchSpec(this);
        }
    }
}
