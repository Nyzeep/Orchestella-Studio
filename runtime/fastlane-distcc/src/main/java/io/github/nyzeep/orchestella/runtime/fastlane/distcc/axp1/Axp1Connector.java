package io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1;

import java.io.IOException;
import java.util.Objects;

/**
 * Pre-handshake reconnect policy: 100 ms, doubling, capped at 2 s, three
 * retries by default. A connection that completed its handshake is never
 * retried here; data-plane failures belong to the caller.
 */
public final class Axp1Connector {
    public static final long INITIAL_BACKOFF_MS = 100L;
    public static final long MAX_BACKOFF_MS = 2_000L;
    public static final int DEFAULT_RETRIES = 3;

    @FunctionalInterface
    public interface TransportFactory {
        Axp1Transport open() throws IOException;
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final TransportFactory factory;
    private final Sleeper sleeper;
    private final int maxRetries;

    public Axp1Connector(TransportFactory factory) {
        this(factory, Thread::sleep, DEFAULT_RETRIES);
    }

    public Axp1Connector(TransportFactory factory, Sleeper sleeper, int maxRetries) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        this.maxRetries = maxRetries;
    }

    public Axp1Connection connect(
            Axp1Role role,
            String token,
            Axp1Capabilities capabilities,
            Axp1Limits limits) throws Axp1Exception {
        Axp1Exception last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                try {
                    sleeper.sleep(backoffMillis(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new Axp1Exception(Axp1Error.CLOSED, "reconnect interrupted");
                }
            }
            Axp1Transport transport = null;
            try {
                transport = factory.open();
                Axp1Connection connected =
                        Axp1Connection.connect(transport, role, token, capabilities, limits);
                last = null;
                return connected;
            } catch (Axp1Exception failure) {
                last = failure;
                if (!retryable(failure.code())) {
                    throw failure;
                }
            } catch (IOException failure) {
                last = new Axp1Exception(Axp1Error.CLOSED, "transport open failed", failure);
            } finally {
                if (transport != null && last != null) {
                    try {
                        transport.close();
                    } catch (IOException ignored) {
                        // The failed attempt owns nothing.
                    }
                }
            }
        }
        throw Objects.requireNonNull(last, "at least one attempt must have run");
    }

    /** Only transport-level failures before HELLO_ACK are retryable. */
    static boolean retryable(Axp1Error code) {
        return code == Axp1Error.TIMEOUT || code == Axp1Error.CLOSED;
    }

    static long backoffMillis(int attempt) {
        long delay = INITIAL_BACKOFF_MS << Math.max(0, attempt - 1);
        return Math.min(MAX_BACKOFF_MS, delay);
    }
}
