package io.github.nyzeep.orchestella.runtime.fastlane.storage;

/** Cancellation seam; a cancelled export/import must not commit anything. */
@FunctionalInterface
public interface CancellationToken {
    boolean isCancelled();

    static CancellationToken none() {
        return () -> false;
    }
}
