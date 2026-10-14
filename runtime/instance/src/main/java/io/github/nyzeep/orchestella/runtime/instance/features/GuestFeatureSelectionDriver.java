package io.github.nyzeep.orchestella.runtime.instance.features;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Drives an explicit selection only while its caller still owns the current live session. */
public final class GuestFeatureSelectionDriver {
    public record Observation(String state, boolean jobActive) {
        public Observation { Objects.requireNonNull(state, "state"); }
    }

    public interface Port {
        /** A null observation means the guest refused or does not support this extension. */
        Observation status(String profile) throws Exception;
        boolean request(String action, String profile) throws Exception;
        /** Reauthentication is transient and must not retire the session owner. */
        default boolean ready() { return true; }
    }

    @FunctionalInterface
    public interface Waiter {
        void sleep(long millis) throws InterruptedException;
    }

    private GuestFeatureSelectionDriver() { }

    public static void drive(String profile, Port port, BooleanSupplier isCurrent,
            Consumer<String> consumePending, Waiter waiter) throws Exception {
        GuestFeatureRequest.command("STATUS", profile);
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(isCurrent, "isCurrent");
        Objects.requireNonNull(consumePending, "consumePending");
        Objects.requireNonNull(waiter, "waiter");
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(45);
        while (isCurrent.getAsBoolean() && System.nanoTime() < deadline) {
            if (!awaitReady(port, isCurrent, waiter)) return;
            Observation observation = port.status(profile);
            if (observation == null || !isCurrent.getAsBoolean()) return;
            if (!observation.jobActive()) {
                if (Set.of("not-installed", "absent", "uninstalled", "planned").contains(observation.state())) {
                    if (!awaitReady(port, isCurrent, waiter) || !port.request("INSTALL", profile)) return;
                } else if ("installed".equals(observation.state())) {
                    if (!awaitReady(port, isCurrent, waiter) || !port.request("START", profile)) return;
                    if (awaitReady(port, isCurrent, waiter)) consumePending.accept(profile);
                    return;
                } else if (Set.of("ready", "awaiting-frame").contains(observation.state())) {
                    if (awaitReady(port, isCurrent, waiter)) consumePending.accept(profile);
                    return;
                } else if (!Set.of("installing", "starting", "waiting-for-base", "bootstrapping").contains(observation.state())) {
                    return;
                }
            }
            // Stop the host driver promptly; the already accepted guest transaction keeps running.
            for (int step = 0; step < 20 && isCurrent.getAsBoolean(); step++) waiter.sleep(100);
        }
    }

    private static boolean awaitReady(Port port, BooleanSupplier isCurrent, Waiter waiter)
            throws InterruptedException {
        // Covers the first transaction settle plus ordinary reauthentication without a long-lived stale wait.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (isCurrent.getAsBoolean() && !port.ready()) {
            if (System.nanoTime() >= deadline) return false;
            waiter.sleep(100);
        }
        return isCurrent.getAsBoolean() && port.ready();
    }
}
