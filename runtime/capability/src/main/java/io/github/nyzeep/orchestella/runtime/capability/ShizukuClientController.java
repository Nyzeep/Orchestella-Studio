package io.github.nyzeep.orchestella.runtime.capability;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

/** Ordinary Shizuku client consent only. Never grants Android permissions or controls a VM. */
public final class ShizukuClientController {
    public enum Installation { INSTALLED, MISSING, UNKNOWN }
    public enum Status {
        STOPPED, NOT_INSTALLED, BINDER_PENDING, UNAUTHORIZED, REQUESTING,
        DENIED, DENIED_REMEMBERED, AUTHORIZED, DEAD, UNSUPPORTED, ERROR, TIMED_OUT
    }
    public record Facts(Installation installation, boolean binderAlive, int serverUid,
            int serverVersion, boolean granted, boolean deniedRemembered) {
        public Facts { Objects.requireNonNull(installation, "installation"); }
    }
    public record Snapshot(Status status, int serverUid, int serverVersion, long epoch, int requestCode) {
        public boolean canRequestPermission() {
            return status == Status.UNAUTHORIZED || status == Status.DENIED || status == Status.TIMED_OUT;
        }
    }
    public interface Events {
        void binderReceived();
        void binderDied();
        void permissionResult(int requestCode, boolean granted);
    }
    public interface Registration { void close(); }
    public interface Transport {
        Registration observe(Events events);
        Facts read();
        void requestPermission(int requestCode);
    }

    private final Transport transport;
    private final Consumer<Snapshot> observer;
    private Snapshot snapshot = new Snapshot(Status.STOPPED, -1, -1, 0, -1);
    private boolean active;
    private Registration registration;
    private static final AtomicInteger REQUEST_IDS = new AtomicInteger(0x4f520000);
    private int pendingRequest = -1;
    private long pendingEpoch;
    private boolean denied;
    private long lifecycle;
    private long epoch;

    public ShizukuClientController(Transport transport, Consumer<Snapshot> observer) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public Snapshot snapshot() { return snapshot; }

    public void start() {
        if (active) return;
        active = true;
        long generation = ++lifecycle;
        retireRequest();
        ++epoch;
        try {
            registration = transport.observe(new Events() {
                private boolean current() { return active && lifecycle == generation; }
                public void binderReceived() {
                    if (!current()) return;
                    retireRequest();
                    ++epoch;
                    refresh();
                }
                public void binderDied() {
                    if (!current()) return;
                    retireRequest();
                    ++epoch;
                    publish(Status.DEAD, -1, -1);
                }
                public void permissionResult(int code, boolean granted) {
                    if (!current() || code != pendingRequest || pendingEpoch != epoch) return;
                    pendingRequest = -1;
                    denied = !granted;
                    refresh();
                }
            });
            refresh();
        } catch (RuntimeException failure) {
            retireRequest();
            publish(Status.ERROR, -1, -1);
        }
    }

    public void refresh() {
        if (!active) return;
        try {
            Facts facts = transport.read();
            if (!facts.binderAlive() || facts.installation() != Installation.INSTALLED || facts.granted()) {
                retireRequest();
            }
            Status status = facts.installation() == Installation.MISSING ? Status.NOT_INSTALLED
                    : facts.installation() == Installation.UNKNOWN ? Status.ERROR
                    : !facts.binderAlive() ? Status.BINDER_PENDING
                    : facts.serverVersion() < 11 ? Status.UNSUPPORTED
                    : facts.granted() ? Status.AUTHORIZED
                    : pendingRequest != -1 ? Status.REQUESTING
                    : facts.deniedRemembered() ? Status.DENIED_REMEMBERED
                    : denied ? Status.DENIED : Status.UNAUTHORIZED;
            publish(status, facts.binderAlive() ? facts.serverUid() : -1,
                    facts.binderAlive() ? facts.serverVersion() : -1);
        } catch (RuntimeException failure) {
            retireRequest();
            publish(Status.ERROR, -1, -1);
        }
    }

    public void requestPermission() {
        if (!active || !snapshot.canRequestPermission()) return;
        refresh();
        if (!snapshot.canRequestPermission()) return;
        int code = REQUEST_IDS.incrementAndGet();
        if (code <= 0) {
            publish(Status.ERROR, -1, -1);
            return;
        }
        pendingRequest = code;
        pendingEpoch = snapshot.epoch();
        publish(Status.REQUESTING, snapshot.serverUid(), snapshot.serverVersion());
        try {
            transport.requestPermission(code);
        } catch (RuntimeException failure) {
            pendingRequest = -1;
            publish(Status.ERROR, -1, -1);
        }
    }

    /** Called once by the visible UI deadline, using the original SDK request code. */
    public void requestTimedOut(int requestCode) {
        if (!active || pendingRequest == -1 || pendingRequest != requestCode) return;
        retireRequest();
        publish(Status.TIMED_OUT, snapshot.serverUid(), snapshot.serverVersion());
    }

    public void stop() {
        if (!active) return;
        active = false;
        ++lifecycle;
        retireRequest();
        Registration closing = registration;
        registration = null;
        try {
            if (closing != null) closing.close();
        } finally {
            publish(Status.STOPPED, -1, -1);
        }
    }

    private void retireRequest() {
        pendingRequest = -1;
        denied = false;
    }

    private void publish(Status status, int uid, int version) {
        snapshot = new Snapshot(status, uid, version, epoch, pendingRequest);
        observer.accept(snapshot);
    }
}
