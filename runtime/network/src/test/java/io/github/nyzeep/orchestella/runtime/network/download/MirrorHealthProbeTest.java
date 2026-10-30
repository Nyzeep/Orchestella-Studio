package io.github.nyzeep.orchestella.runtime.network.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class MirrorHealthProbeTest {

    private static List<String> rank(List<String> urls, MirrorHealthProbe.Prober prober) {
        return MirrorHealthProbe.rankMirrors(urls, 2_000, prober);
    }

    @Test
    public void fastestHealthyMirrorMovesToTheFront() {
        Map<String, Long> latency = new ConcurrentHashMap<>();
        latency.put("https://cn.example/a", 900L);
        latency.put("https://vpn.example/a", 40L);
        latency.put("https://upstream.example/a", 300L);
        List<String> ranked = rank(latency.keySet().stream().toList(), (url, timeout) -> latency.get(url));
        assertEquals("https://vpn.example/a", ranked.get(0));
        assertEquals("https://upstream.example/a", ranked.get(1));
        assertEquals("https://cn.example/a", ranked.get(2));
        assertEquals(3, ranked.size());
    }

    @Test
    public void unreachableMirrorsKeepTheirOriginalOrderAtTheTail() {
        List<String> urls = Arrays.asList(
                "https://cn1.example/a", "https://cn2.example/a", "https://upstream.example/a");
        List<String> ranked = rank(urls, (url, timeout) -> {
            if (url.equals("https://upstream.example/a")) {
                return 25L;
            }
            throw new IOException("unreachable");
        });
        assertEquals("https://upstream.example/a", ranked.get(0));
        assertEquals("https://cn1.example/a", ranked.get(1));
        assertEquals("https://cn2.example/a", ranked.get(2));
    }

    @Test
    public void equalLatencyKeepsCatalogOrder() {
        List<String> urls = Arrays.asList("https://a.example/x", "https://b.example/x");
        List<String> ranked = rank(urls, (url, timeout) -> 100L);
        assertEquals(urls, ranked);
    }

    @Test
    public void singleMirrorIsReturnedUnchangedWithoutProbing() {
        AtomicInteger probeCount = new AtomicInteger();
        List<String> ranked = rank(Collections.singletonList("https://only.example/x"), (url, timeout) -> {
            probeCount.incrementAndGet();
            return 1L;
        });
        assertEquals(Collections.singletonList("https://only.example/x"), ranked);
        assertEquals("a single candidate needs no probe", 0, probeCount.get());
    }

    @Test
    public void aStalledProbeCannotBlockTheWholeRanking() {
        List<String> urls = Arrays.asList("https://stalled.example/a", "https://fast.example/a");
        long started = System.nanoTime();
        List<String> ranked = rank(urls, (url, timeout) -> {
            if (url.contains("stalled")) {
                try {
                    Thread.sleep(timeout + 400L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
            return 10L;
        });
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertEquals("https://fast.example/a", ranked.get(0));
        assertEquals("https://stalled.example/a", ranked.get(1));
        assertTrue("ranking must respect the probe budget, took " + elapsedMs + "ms", elapsedMs < 1_500L);
    }

    @Test
    public void emptyInputStaysEmpty() {
        assertEquals(Collections.emptyList(), MirrorHealthProbe.rankMirrors(Collections.emptyList()));
    }
}
