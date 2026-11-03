package io.github.nyzeep.orchestella.runtime.instance;

import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of one process entry: its pid and its command line.
 *
 * <p>The command line is copied on construction, so later mutations of the source list cannot
 * change the identity this reference stands for. {@link #toString()} deliberately omits the
 * arguments (they can be arbitrarily long and may carry private absolute paths) and reports only
 * the pid and the number of arguments.
 */
public final class ProcessRef {

    private final int pid;
    private final List<String> cmdline;

    /**
     * Creates a snapshot.
     *
     * @param pid process identifier
     * @param cmdline command line split into its arguments; copied, must not contain null
     */
    public ProcessRef(int pid, List<String> cmdline) {
        this.pid = pid;
        this.cmdline = List.copyOf(Objects.requireNonNull(cmdline, "cmdline"));
    }

    /** @return the process identifier */
    public int pid() {
        return pid;
    }

    /** @return the immutable argument vector, never null and never containing null */
    public List<String> cmdline() {
        return cmdline;
    }

    /**
     * Reports whether any single argument contains {@code needle}.
     *
     * @param needle substring to look for; a null or empty needle yields false
     * @return true when one argument contains the needle
     */
    public boolean cmdlineContains(String needle) {
        if (needle == null || needle.isEmpty()) {
            return false;
        }
        for (String argument : cmdline) {
            if (argument.contains(needle)) {
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
        if (!(other instanceof ProcessRef reference)) {
            return false;
        }
        return pid == reference.pid && cmdline.equals(reference.cmdline);
    }

    @Override
    public int hashCode() {
        return 31 * pid + cmdline.hashCode();
    }

    @Override
    public String toString() {
        return "ProcessRef{pid=" + pid + ", argvCount=" + cmdline.size() + "}";
    }
}
