package io.github.nyzeep.orchestella.runtime.engineuml;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks active root-volume reservations across standalone UML launches so a new
 * launch cannot reuse a root volume whose previous owner is still running or in
 * an unconfirmed stop state.
 */
public final class UmlRootReservationRegistry {
    private static final UmlRootReservationRegistry DEFAULT_INSTANCE =
            new UmlRootReservationRegistry();

    /** Exception thrown when a root volume is still reserved by an active or unconfirmed owner. */
    public static final class RootVolumeBusyException extends IOException {
        private static final long serialVersionUID = 1L;

        private final String occupyingInstanceId;
        private final String occupyingGeneration;

        public RootVolumeBusyException(
                Path rootPath, String occupyingInstanceId, String occupyingGeneration) {
            super("Root volume busy for path=" + rootPath
                    + " owner=" + occupyingInstanceId
                    + " generation=" + occupyingGeneration);
            this.occupyingInstanceId = occupyingInstanceId;
            this.occupyingGeneration = occupyingGeneration;
        }

        public String occupyingInstanceId() {
            return occupyingInstanceId;
        }

        public String occupyingGeneration() {
            return occupyingGeneration;
        }
    }

    /** Exclusive reservation token for one normalized root path. */
    public static final class Reservation {
        private final Path normalizedPath;
        private final String instanceId;
        private final String generation;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Reservation(Path normalizedPath, String instanceId, String generation) {
            this.normalizedPath = normalizedPath;
            this.instanceId = instanceId;
            this.generation = generation;
        }

        public Path normalizedPath() {
            return normalizedPath;
        }

        public String instanceId() {
            return instanceId;
        }

        public String generation() {
            return generation;
        }

        public boolean isReleased() {
            return released.get();
        }

        boolean markReleased() {
            return released.compareAndSet(false, true);
        }
    }

    private final ConcurrentMap<Path, Reservation> reservations = new ConcurrentHashMap<>();

    public static UmlRootReservationRegistry defaultRegistry() {
        return DEFAULT_INSTANCE;
    }

    /**
     * Acquires an exclusive reservation for {@code rootPath}, failing closed if
     * an unreleased reservation already owns that path.
     */
    public Reservation acquire(Path rootPath, String instanceId, String generation)
            throws RootVolumeBusyException {
        Objects.requireNonNull(rootPath, "rootPath");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(generation, "generation");
        Path normalized = rootPath.toAbsolutePath().normalize();
        Reservation candidate = new Reservation(normalized, instanceId, generation);
        Reservation existing = reservations.putIfAbsent(normalized, candidate);
        if (existing != null) {
            throw new RootVolumeBusyException(
                    normalized, existing.instanceId(), existing.generation());
        }
        return candidate;
    }

    /** Returns true when an unreleased reservation currently owns {@code rootPath}. */
    public boolean isReserved(Path rootPath) {
        if (rootPath == null) {
            return false;
        }
        Reservation current = reservations.get(rootPath.toAbsolutePath().normalize());
        return current != null && !current.isReleased();
    }

    /** Releases {@code reservation} idempotently; ignores null or foreign tokens. */
    public boolean release(Reservation reservation) {
        if (reservation == null) {
            return false;
        }
        if (!reservation.markReleased()) {
            return false;
        }
        return reservations.remove(reservation.normalizedPath(), reservation);
    }
}
