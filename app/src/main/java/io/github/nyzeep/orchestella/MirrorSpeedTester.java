package io.github.nyzeep.orchestella;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * High-performance concurrent mirror latency testing engine (ADR-0006, Phase 5.0 R2).
 *
 * <p>Measures HTTP/HTTPS round-trip latency to package repository mirrors and Docker registries,
 * sorting nodes to select the lowest-latency source automatically.
 */
public final class MirrorSpeedTester {

    private static final int DEFAULT_TIMEOUT_MS = 2500;

    public static final class MirrorCandidate {
        private final String name;
        private final String url;

        public MirrorCandidate(String name, String url) {
            this.name = Objects.requireNonNull(name, "name");
            this.url = Objects.requireNonNull(url, "url");
        }

        public String name() {
            return name;
        }

        public String url() {
            return url;
        }

        @Override
        public String toString() {
            return name + " (" + url + ")";
        }
    }

    public static final class SpeedResult {
        private final MirrorCandidate candidate;
        private final long latencyMs;
        private final boolean success;
        private final String errorMessage;

        public SpeedResult(MirrorCandidate candidate, long latencyMs, boolean success, String errorMessage) {
            this.candidate = candidate;
            this.latencyMs = latencyMs;
            this.success = success;
            this.errorMessage = errorMessage;
        }

        public MirrorCandidate candidate() {
            return candidate;
        }

        public long latencyMs() {
            return latencyMs;
        }

        public boolean isSuccess() {
            return success;
        }

        public String errorMessage() {
            return errorMessage;
        }

        @Override
        public String toString() {
            return candidate.name() + ": " + (success ? latencyMs + "ms" : "Failed (" + errorMessage + ")");
        }
    }

    private MirrorSpeedTester() {
    }

    /**
     * Probes an individual HTTP/HTTPS URL and measures round-trip connect latency.
     */
    public static SpeedResult probe(MirrorCandidate candidate, int timeoutMs) {
        long start = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(candidate.url());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Orchestella-Mirror-Probe/1.0");

            int code = conn.getResponseCode();
            long elapsed = System.currentTimeMillis() - start;
            if (code >= 200 && code < 400) {
                return new SpeedResult(candidate, elapsed, true, null);
            } else {
                return new SpeedResult(candidate, elapsed, false, "HTTP " + code);
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return new SpeedResult(candidate, elapsed, false, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Concurrently probes candidates in parallel and returns sorted results (fastest first).
     */
    public static List<SpeedResult> probeAll(List<MirrorCandidate> candidates, int timeoutMs) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(candidates.size(), 6));
        List<Future<SpeedResult>> futures = new ArrayList<>();
        try {
            for (MirrorCandidate candidate : candidates) {
                futures.add(executor.submit(() -> probe(candidate, timeoutMs)));
            }

            List<SpeedResult> results = new ArrayList<>();
            for (Future<SpeedResult> f : futures) {
                try {
                    results.add(f.get(timeoutMs + 1000L, TimeUnit.MILLISECONDS));
                } catch (Exception e) {
                    // Timeout or cancellation
                }
            }

            // Sort: successful first ordered by latencyMs ascending, then failed
            results.sort((a, b) -> {
                if (a.isSuccess() && b.isSuccess()) {
                    return Long.compare(a.latencyMs(), b.latencyMs());
                } else if (a.isSuccess()) {
                    return -1;
                } else if (b.isSuccess()) {
                    return 1;
                }
                return Long.compare(a.latencyMs(), b.latencyMs());
            });

            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    public static List<SpeedResult> probeAll(List<MirrorCandidate> candidates) {
        return probeAll(candidates, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Selects the fastest available mirror candidate, or returns the first candidate as fallback.
     */
    public static MirrorCandidate selectFastest(List<SpeedResult> results, MirrorCandidate fallback) {
        if (results != null) {
            for (SpeedResult r : results) {
                if (r.isSuccess() && r.latencyMs() > 0) {
                    return r.candidate();
                }
            }
        }
        return fallback;
    }

    /**
     * Standard pre-baked probe candidates for system package mirrors.
     */
    public static List<MirrorCandidate> defaultSystemCandidates() {
        List<MirrorCandidate> list = new ArrayList<>();
        list.add(new MirrorCandidate("阿里云开源镜像", "https://mirrors.aliyun.com"));
        list.add(new MirrorCandidate("清华大学 TUNA 镜像", "https://mirrors.tuna.tsinghua.edu.cn"));
        list.add(new MirrorCandidate("中科大 USTC 镜像", "https://mirrors.ustc.edu.cn"));
        list.add(new MirrorCandidate("华为云开源镜像", "https://repo.huaweicloud.com"));
        list.add(new MirrorCandidate("腾讯云开源镜像", "https://mirrors.cloud.tencent.com"));
        list.add(new MirrorCandidate("Debian 官方源", "http://deb.debian.org"));
        list.add(new MirrorCandidate("Ubuntu 官方源", "http://archive.ubuntu.com"));
        return Collections.unmodifiableList(list);
    }

    /**
     * Standard pre-baked probe candidates for Docker registry mirrors.
     */
    public static List<MirrorCandidate> defaultDockerCandidates() {
        List<MirrorCandidate> list = new ArrayList<>();
        list.add(new MirrorCandidate("轩辕镜像 (XuanYuan)", "https://docker.xuanyuan.me"));
        list.add(new MirrorCandidate("毫秒镜像 (1ms.run)", "https://docker.1ms.run"));
        list.add(new MirrorCandidate("DaoCloud 镜像", "https://docker.m.daocloud.io"));
        list.add(new MirrorCandidate("Docker Hub 官方源", "https://registry-1.docker.io"));
        return Collections.unmodifiableList(list);
    }
}
