package io.github.nyzeep.orchestella.runtime.fastlane.capability;

/** The user-visible kind of work; unknown work is never routed to Fast Lane. */
public enum Workload {
    INTERACTIVE_SHELL,
    COMPILE,
    FULL_LINUX,
    UNKNOWN
}
