package io.github.nyzeep.orchestella.runtime.network.download;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orders a candidate mirror list by measured transfer speed before a large download starts.
 *
 * <p>The catalog lists domestic mirrors first and upstream last, which is right for a
 * domestic network and wrong for anyone on a VPN or a foreign link: the engine would spend
 * its per-mirror retry budget (10s connect + 15s read + backoff, three times) on mirrors that
 * are unreachable for that user before ever touching the one that works. Probing every
 * candidate in parallel with a short timeout costs at most {@code timeoutMs} and puts the
 * fastest healthy mirror first.
 *
 * <p>Probe results never remove a candidate: failures keep their original relative order at
 * the tail, so the engine's existing failover chain still covers a mirror that answers the
 * sample but stalls during the real transfer.
 */
public final class MirrorHealthProbe {

    public static final int DEFAULT_TIMEOUT_MS = 4_000;

    /** Returns the mirror's ranking cost in milliseconds, or throws when it is not usable. */
    @FunctionalInterface
    public interface Prober {
        long latencyMillis(String url, int timeoutMs) throws IOException;
    }

    private MirrorHealthProbe() {
    }

    public static List<String> rankMirrors(List<String> urls) {
        return rankMirrors(urls, DEFAULT_TIMEOUT_MS, MirrorHealthProbe::sampleLatency);
    }

    /**
     * Returns the same URLs, healthy ones first ordered by latency, then the rest in their
     * original order. An empty or single-element input is returned unchanged.
     */
    public static List<String> rankMirrors(List<String> urls, int timeoutMs, Prober prober) {
        Objects.requireNonNull(urls, "urls cannot be null");
        Objects.requireNonNull(prober, "prober cannot be null");
        if (urls.size() <= 1) {
            return new ArrayList<>(urls);
        }
        int budget = Math.max(1, timeoutMs);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, urls.size()), runnable -> {
            Thread thread = new Thread(runnable, "mirror-probe");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Map<String, Future<Long>> pending = new LinkedHashMap<>();
            for (String url : urls) {
                Callable<Long> task = () -> prober.latencyMillis(url, budget);
                pending.put(url, pool.submit(task));
            }
            List<String> healthy = new ArrayList<>();
            List<Long> healthyLatency = new ArrayList<>();
            List<String> unreachable = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget);
            for (Map.Entry<String, Future<Long>> entry : pending.entrySet()) {
                long remaining = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                try {
                    Long latency = entry.getValue().get(remaining, TimeUnit.MILLISECONDS);
                    // Stable insertion by latency: a later, faster mirror must not reorder ties.
                    int insertAt = healthy.size();
                    for (int i = 0; i < healthyLatency.size(); i++) {
                        if (latency < healthyLatency.get(i)) {
                            insertAt = i;
                            break;
                        }
                    }
                    healthy.add(insertAt, entry.getKey());
                    healthyLatency.add(insertAt, latency);
                } catch (TimeoutException | ExecutionException | InterruptedException failure) {
                    if (failure instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    entry.getValue().cancel(true);
                    unreachable.add(entry.getKey());
                }
            }
            List<String> ranked = new ArrayList<>(urls.size());
            ranked.addAll(healthy);
            ranked.addAll(unreachable);
            return ranked;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Bytes sampled from each candidate while ranking. Small enough to be free on a metered
     * connection, large enough that the number reflects transfer speed and not just ping.
     */
    static final int SAMPLE_BYTES = 64 * 1024;

    /**
     * Ranged-GET probe: time to first byte plus {@link #SAMPLE_BYTES} of body.
     *
     * <p>A HEAD probe ranks by round-trip only, and the 2026-10-30 device run showed why that
     * is not enough: {@code gh-proxy.com} answered HEAD in under half a second and then served
     * at 58 KB/s, while {@code cloud.debian.org} took 1.9 s to answer and then served at
     * 700 KB/s. The proxy won the ranking and turned a five-minute download into eight hours.
     * Timing a fixed sample picks the mirror that will actually finish first.
     *
     * <p>The sample is bounded: a server that ignores {@code Range} still only ever sends
     * {@code SAMPLE_BYTES} before the connection is closed.
     */
    static long sampleLatency(String url, int timeoutMs) throws IOException {
        long started = System.nanoTime();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Orchestella-MirrorProbe");
            connection.setRequestProperty("Range", "bytes=0-" + (SAMPLE_BYTES - 1));
            int code = connection.getResponseCode();
            if (code >= 400 && code != HttpURLConnection.HTTP_BAD_METHOD) {
                throw new IOException("probe returned HTTP " + code + " for " + url);
            }
            try (InputStream body = connection.getInputStream()) {
                byte[] buffer = new byte[8 * 1024];
                int read = 0;
                int count;
                while (read < SAMPLE_BYTES) {
                    int want = Math.min(buffer.length, SAMPLE_BYTES - read);
                    count = body.read(buffer, 0, want);
                    if (count < 0) {
                        break;
                    }
                    read += count;
                }
            }
            return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
