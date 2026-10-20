package io.github.nyzeep.orchestella.runtime.capability;

/**
 * The single seam through which App-visible platform access is observed.
 * The reflective Android adapter is the only production implementation;
 * a second (future) adapter can implement the same typed contract without
 * teaching callers another class shape or failure vocabulary.
 */
public interface AvfPlatformAccess {
    /** Reads the platform surface once; never starts a VM or grants a permission. */
    AvfPlatformFacts observe();

    /** Whether the platform class/method shape can be resolved at all. */
    boolean isReflectionAvailable();
}
