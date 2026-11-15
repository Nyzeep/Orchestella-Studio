package io.github.nyzeep.orchestella.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;

/**
 * Kotlin baseline for the Rust VT prototype comparison (research/rust-vt-bench).
 *
 * <p>Both sides must read byte-identical fixtures, so every fixture is checked
 * against the sha256 recorded in the shared {@code SHA256SUMS} before it is fed
 * to the model. The headline configuration is {@code shellIntegration=false}
 * because the Rust prototype has no ShellCommandTranscript; the production
 * configuration ({@code shellIntegration=true}) is measured alongside it.
 *
 * <p>Per fixture: 2 warmup rounds, then 5 measured rounds on a fresh
 * {@code 90x30} model with a fresh UTF-8 decode. Allocation is sampled from
 * {@code com.sun.management.ThreadMXBean}; a JVM without that capability fails
 * instead of reporting a fabricated number. Snapshot cost is the median of 100
 * timed {@code snapshot()} calls after 20 warmup calls; {@code snapshot()} is
 * the full history + visible-screen rebuild, which is the row range the Rust
 * side's {@code copy_rows_us} measures.
 *
 * <p>Writes {@code out/kotlin.json} in the schema {@code orchestella.vtbench/1}
 * and prints a Markdown table plus each fixture's sha256 to stdout. Fixture and
 * output directories can be overridden with {@code -Drust.vt.fixtures=} /
 * {@code RUST_VT_FIXTURES} and {@code -Drust.vt.out=} / {@code RUST_VT_OUT};
 * the defaults assume the Gradle test working directory ({@code app/}).
 */
public final class RustBaselineBenchTest {
    private static final String[] FIXTURE_IDS = {"plain", "ansi_color", "cursor", "wide", "osc"};
    private static final int COLUMNS = 90;
    private static final int ROWS = 30;
    private static final int WARMUP_ROUNDS = 2;
    private static final int MEASURED_ROUNDS = 5;
    private static final int SNAPSHOT_WARMUP = 20;
    private static final int SNAPSHOT_ROUNDS = 100;
    private static final double BYTES_PER_MIB = 1048576.0;

    private static final String DEFAULT_FIXTURES = "../research/rust-vt-bench/fixtures/generated";
    private static final String DEFAULT_OUT = "../research/rust-vt-bench/out";

    private static final class Measured {
        final double mbPerS;
        final double allocBytesPerMb;
        final double snapshotUs;
        final int snapshotChars;
        final int historyRows;

        Measured(double mbPerS, double allocBytesPerMb, double snapshotUs, int snapshotChars, int historyRows) {
            this.mbPerS = mbPerS;
            this.allocBytesPerMb = allocBytesPerMb;
            this.snapshotUs = snapshotUs;
            this.snapshotChars = snapshotChars;
            this.historyRows = historyRows;
        }
    }

    private static final class FixtureResult {
        final String id;
        final long bytes;
        final String sha256;
        final Measured headline;
        final Measured production;

        FixtureResult(String id, long bytes, String sha256, Measured headline, Measured production) {
            this.id = id;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.headline = headline;
            this.production = production;
        }
    }

    /**
     * Reflective handle to {@code com.sun.management.ThreadMXBean}.
     *
     * <p>Unit tests compile against {@code android.jar}, which has no
     * {@code java.lang.management}, while the test JVM is a full JDK 17.
     * Reflection is the only way to read the per-thread allocation counter
     * without changing build files. Every failure path calls {@code fail}
     * instead of inventing a number.
     */
    private static final class Allocations {
        private final Object bean;
        private final Method getThreadAllocatedBytes;

        private Allocations(Object bean, Method getThreadAllocatedBytes) {
            this.bean = bean;
            this.getThreadAllocatedBytes = getThreadAllocatedBytes;
        }

        long sample(long threadId) {
            try {
                return ((Number) getThreadAllocatedBytes.invoke(bean, threadId)).longValue();
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("getThreadAllocatedBytes failed: " + failure, failure);
            }
        }

        static Allocations open() {
            try {
                Class<?> factory = Class.forName("java.lang.management.ManagementFactory");
                Object bean = factory.getMethod("getThreadMXBean").invoke(null);
                Class<?> extended = Class.forName("com.sun.management.ThreadMXBean");
                if (!extended.isInstance(bean)) {
                    fail("com.sun.management.ThreadMXBean unavailable: allocation measurement cannot be trusted");
                    throw new AssertionError("unreachable");
                }
                Object supported = extended.getMethod("isThreadAllocatedMemorySupported").invoke(bean);
                if (!Boolean.TRUE.equals(supported)) {
                    fail("ThreadMXBean.isThreadAllocatedMemorySupported() is false: allocations cannot be measured");
                }
                Object enabled = extended.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean);
                if (!Boolean.TRUE.equals(enabled)) {
                    extended.getMethod("setThreadAllocatedMemoryEnabled", boolean.class).invoke(bean, true);
                }
                return new Allocations(bean, extended.getMethod("getThreadAllocatedBytes", long.class));
            } catch (ReflectiveOperationException failure) {
                fail("com.sun.management.ThreadMXBean unavailable: " + failure);
                throw new AssertionError("unreachable", failure);
            }
        }
    }

    @Test
    public void measuresAllFixtures() throws Exception {
        Path fixturesDir = resolve("rust.vt.fixtures", "RUST_VT_FIXTURES", DEFAULT_FIXTURES);
        Path outDir = resolve("rust.vt.out", "RUST_VT_OUT", DEFAULT_OUT);

        // CI checkouts without the generated payload skip instead of failing; the
        // sha256 assertions below stay hard requirements once fixtures exist.
        assumeTrue("fixtures missing: " + fixturesDir, hasFixtures(fixturesDir));
        assumeTrue("SHA256SUMS missing: " + fixturesDir, Files.isRegularFile(fixturesDir.resolve("SHA256SUMS")));

        Map<String, String> expectedSums = readSums(fixturesDir.resolve("SHA256SUMS"));
        Allocations allocator = Allocations.open();

        List<FixtureResult> results = new ArrayList<>();
        for (String id : FIXTURE_IDS) {
            try {
                results.add(measureFixture(fixturesDir, id, expectedSums, allocator));
            } catch (AssertionError failure) {
                throw new AssertionError("fixture " + id + ": " + failure.getMessage(), failure);
            } catch (Exception failure) {
                throw new AssertionError("fixture " + id + ": " + failure, failure);
            }
        }

        Files.createDirectories(outDir);
        Path jsonPath = outDir.resolve("kotlin.json");
        Files.write(jsonPath, renderJson(results).getBytes(StandardCharsets.UTF_8));

        printReport(results, jsonPath);
    }

    private static FixtureResult measureFixture(
            Path fixturesDir, String id, Map<String, String> expectedSums, Allocations allocator)
            throws Exception {
        Path path = fixturesDir.resolve(id + ".bin");
        assertTrue("fixture " + id + ": missing " + path, Files.isRegularFile(path));
        byte[] bytes = Files.readAllBytes(path);
        assertTrue("fixture " + id + ": empty file " + path, bytes.length > 0);

        String actualSha = sha256(bytes);
        String expectedSha = expectedSums.get(id + ".bin");
        assertTrue("fixture " + id + ": no SHA256SUMS entry for " + id + ".bin", expectedSha != null);
        assertEquals("fixture " + id + ": sha256 differs from SHA256SUMS", expectedSha, actualSha);

        Measured headline = measure(bytes, false, allocator);
        Measured production = measure(bytes, true, allocator);

        // A zero-byte or non-finite result means the harness, not the engine,
        // produced the number, so it must never reach kotlin.json.
        assertTrue("fixture " + id + ": headline snapshot is empty", headline.snapshotChars > 0);
        assertTrue("fixture " + id + ": production snapshot is empty", production.snapshotChars > 0);
        assertTrue("fixture " + id + ": headline throughput is not a positive finite number",
                Double.isFinite(headline.mbPerS) && headline.mbPerS > 0.0);
        assertTrue("fixture " + id + ": production throughput is not a positive finite number",
                Double.isFinite(production.mbPerS) && production.mbPerS > 0.0);
        return new FixtureResult(id, bytes.length, actualSha, headline, production);
    }

    private static Measured measure(byte[] bytes, boolean shellIntegration, Allocations allocator) {
        long threadId = Thread.currentThread().getId();
        double megabytes = bytes.length / BYTES_PER_MIB;

        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            TerminalScreenModel model = new TerminalScreenModel(COLUMNS, ROWS, shellIntegration);
            model.append(new String(bytes, StandardCharsets.UTF_8));
        }

        double[] mbPerSecond = new double[MEASURED_ROUNDS];
        double[] allocPerMb = new double[MEASURED_ROUNDS];
        double[] snapshotUs = new double[MEASURED_ROUNDS];
        int snapshotChars = 0;
        int historyRows = 0;

        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            TerminalScreenModel model = new TerminalScreenModel(COLUMNS, ROWS, shellIntegration);
            long allocatedBefore = allocator.sample(threadId);
            long start = System.nanoTime();
            model.append(new String(bytes, StandardCharsets.UTF_8));
            long elapsedNanos = System.nanoTime() - start;
            long allocatedAfter = allocator.sample(threadId);

            double seconds = elapsedNanos / 1_000_000_000.0;
            mbPerSecond[round] = seconds > 0.0 ? megabytes / seconds : 0.0;
            allocPerMb[round] = megabytes > 0.0 ? (allocatedAfter - allocatedBefore) / megabytes : 0.0;

            for (int i = 0; i < SNAPSHOT_WARMUP; i++) model.snapshot();
            long[] samples = new long[SNAPSHOT_ROUNDS];
            for (int i = 0; i < SNAPSHOT_ROUNDS; i++) {
                long snapshotStart = System.nanoTime();
                model.snapshot();
                samples[i] = System.nanoTime() - snapshotStart;
            }
            snapshotUs[round] = median(samples) / 1000.0;

            // Text length and history are read outside the timed window so the
            // measurement stays a pure snapshot() cost.
            TerminalScreenModel.Snapshot display = model.snapshot();
            snapshotChars = display.text().length();
            historyRows = display.historyRows;
        }

        return new Measured(median(mbPerSecond), median(allocPerMb), median(snapshotUs), snapshotChars, historyRows);
    }

    private static boolean hasFixtures(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.bin")) {
            return entries.iterator().hasNext();
        }
    }

    private static Map<String, String> readSums(Path path) throws Exception {
        Map<String, String> sums = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) continue;
            String name = parts[1].startsWith("*") ? parts[1].substring(1) : parts[1];
            sums.put(name, parts[0].toLowerCase(Locale.ROOT));
        }
        return sums;
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private static String renderJson(List<FixtureResult> results) {
        StringBuilder out = new StringBuilder();
        out.append("{\"schema\":\"orchestella.vtbench/1\",\"impl\":\"kotlin\",\n");
        out.append(" \"jvm\":\"").append(escape(jvm())).append("\",\n");
        out.append(" \"jvm_args\":\"").append(escape(jvmArgs())).append("\",\n");
        out.append(" \"model\":\"").append(COLUMNS).append('x').append(ROWS)
                .append(" core(shellIntegration=false)\",\n");
        out.append(" \"fixtures\":[\n");
        for (int i = 0; i < results.size(); i++) {
            FixtureResult result = results.get(i);
            out.append("  {\"id\":\"").append(result.id).append("\",\"bytes\":").append(result.bytes)
                    .append(",\"sha256\":\"").append(result.sha256).append("\",\n");
            out.append("   \"mb_per_s\":").append(number(result.headline.mbPerS))
                    .append(",\"alloc_bytes_per_mb\":").append(number(result.headline.allocBytesPerMb))
                    .append(",\n");
            out.append("   \"snapshot_us\":").append(number(result.headline.snapshotUs))
                    .append(",\"snapshot_chars\":").append(result.headline.snapshotChars)
                    .append(",\"history_rows\":").append(result.headline.historyRows).append(",\n");
            out.append("   \"mb_per_s_production\":").append(number(result.production.mbPerS))
                    .append(",\"alloc_bytes_per_mb_production\":").append(number(result.production.allocBytesPerMb))
                    .append(",\"snapshot_us_production\":").append(number(result.production.snapshotUs))
                    .append("}");
            out.append(i + 1 < results.size() ? ",\n" : "\n");
        }
        out.append(" ]}\n");
        return out.toString();
    }

    private static void printReport(List<FixtureResult> results, Path jsonPath) {
        System.out.println();
        System.out.println("rust-vt baseline: kotlin " + COLUMNS + "x" + ROWS
                + " core, warmup=" + WARMUP_ROUNDS + ", iters=" + MEASURED_ROUNDS
                + ", snapshot iters=" + SNAPSHOT_ROUNDS);
        System.out.println("jvm: " + jvm());
        System.out.println("jvm_args: " + jvmArgs());
        System.out.println("json: " + jsonPath.toAbsolutePath());
        printTable("headline (shellIntegration=false)", results, false);
        printTable("production (shellIntegration=true)", results, true);
        System.out.println();
        for (FixtureResult result : results) {
            System.out.println("sha256 " + result.id + " " + result.sha256);
        }
        System.out.flush();
    }

    private static void printTable(String title, List<FixtureResult> results, boolean production) {
        System.out.println();
        System.out.println("### " + title);
        System.out.println();
        System.out.println("| fixture | bytes | MiB/s | alloc bytes/MB | snapshot us | snapshot chars | history rows |");
        System.out.println("| --- | ---: | ---: | ---: | ---: | ---: | ---: |");
        for (FixtureResult result : results) {
            Measured measured = production ? result.production : result.headline;
            System.out.println(String.format(Locale.ROOT, "| %s | %d | %.2f | %.0f | %.2f | %d | %d |",
                    result.id, result.bytes, measured.mbPerS, measured.allocBytesPerMb,
                    measured.snapshotUs, measured.snapshotChars, measured.historyRows));
        }
    }

    private static String jvm() {
        return System.getProperty("java.version", "unknown") + " / " + System.getProperty("java.vm.version", "unknown");
    }

    private static String jvmArgs() {
        // Same compile-classpath constraint as Allocations: read the launch
        // arguments reflectively from the real JDK running the test.
        try {
            Class<?> factory = Class.forName("java.lang.management.ManagementFactory");
            Object runtime = factory.getMethod("getRuntimeMXBean").invoke(null);
            Object arguments = Class.forName("java.lang.management.RuntimeMXBean")
                    .getMethod("getInputArguments").invoke(runtime);
            StringBuilder joined = new StringBuilder();
            for (Object argument : (List<?>) arguments) {
                if (joined.length() > 0) joined.append(' ');
                joined.append(argument);
            }
            return joined.toString();
        } catch (ReflectiveOperationException | ClassCastException failure) {
            return "unavailable: " + failure;
        }
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "0.000000";
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static Path resolve(String property, String environment, String fallback) {
        String value = System.getProperty(property);
        if (value == null) value = System.getenv(environment);
        if (value == null || value.trim().isEmpty()) value = fallback;
        return Paths.get(value.trim()).toAbsolutePath().normalize();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (ch < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    else out.append(ch);
            }
        }
        return out.toString();
    }
}
