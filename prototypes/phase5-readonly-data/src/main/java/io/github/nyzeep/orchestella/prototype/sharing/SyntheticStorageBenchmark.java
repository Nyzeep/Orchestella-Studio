package io.github.nyzeep.orchestella.prototype.sharing;

import io.github.nyzeep.orchestella.runtime.contract.sharing.DownloadsSelection;
import io.github.nyzeep.orchestella.runtime.contract.sharing.ReadOnlyStager;
import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** PROTOTYPE benchmark on newly generated external files only. Never measures guest 9p or USB. */
public final class SyntheticStorageBenchmark {
    private static final long LARGE_BYTES = 64L * 1024 * 1024;
    private static final int SMALL_COUNT = 1024;
    private static final int SMALL_BYTES = 1024;
    private static final OperatingSystemMXBean OS = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
    private SyntheticStorageBenchmark() { }
    @FunctionalInterface private interface Operation { String run() throws Exception; }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) { throw new IllegalArgumentException("Supply a new external benchmark directory"); }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path approved = Path.of("D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013/C/benchmarks/temp").toAbsolutePath().normalize();
        if (!root.startsWith(approved) || !Files.isDirectory(root) || !root.toRealPath().equals(root)) {
            throw new SecurityException("Benchmark root is outside the owned external boundary");
        }
        Path downloads = Files.createDirectory(root.resolve("Downloads"));
        Path small = Files.createDirectory(downloads.resolve("small"));
        Path cache = Files.createDirectory(root.resolve("owned-catalog-cache"));
        Path peer = Files.createDirectory(root.resolve("synthetic-peer"));
        Path large = downloads.resolve("large.bin");
        List<Path> inputs = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        String[] largeHash = new String[1];
        measure("large-sequential-create", LARGE_BYTES, 1, "NEW_SYNTHETIC_FILE_OS_CACHE_UNCONTROLLED", () -> {
            largeHash[0] = generate(large, LARGE_BYTES, 0x504835L);
            verify(largeHash[0], hash(large));
            return largeHash[0];
        });
        inputs.add(large);
        hashes.add(largeHash[0]);
        measure("large-sequential-first-open", LARGE_BYTES, 1, "APP_CACHE_BYPASSED_OS_CACHE_UNCONTROLLED", () -> checkedHash(large, largeHash[0]));
        measure("large-sequential-repeat-open", LARGE_BYTES, 1, "REPEAT_OPEN_LIKELY_OS_WARM_NOT_GUARANTEED", () -> checkedHash(large, largeHash[0]));
        byte[][] appCache = new byte[1][];
        measure("application-cache-fill", LARGE_BYTES, 1, "APP_CACHE_MISS_OS_CACHE_UNCONTROLLED", () -> {
            appCache[0] = Files.readAllBytes(large);
            String digest = HexFormat.of().formatHex(digest().digest(appCache[0]));
            verify(largeHash[0], digest);
            return digest;
        });
        measure("application-cached-read", LARGE_BYTES, 1, "APP_CACHE_HIT_MEMORY_ONLY", () -> {
            String digest = HexFormat.of().formatHex(digest().digest(appCache[0]));
            verify(largeHash[0], digest);
            return digest;
        });
        appCache[0] = null;
        measure("small-file-create", (long) SMALL_COUNT * SMALL_BYTES, SMALL_COUNT, "NEW_SYNTHETIC_FILES_OS_CACHE_UNCONTROLLED", () -> {
            MessageDigest aggregate = digest();
            for (int i = 0; i < SMALL_COUNT; i++) {
                Path path = small.resolve(String.format(java.util.Locale.ROOT, "small-%04d.bin", i));
                String expected = generate(path, SMALL_BYTES, i);
                verify(expected, hash(path));
                inputs.add(path);
                hashes.add(expected);
                aggregate.update(HexFormat.of().parseHex(expected));
            }
            return HexFormat.of().formatHex(aggregate.digest());
        });
        measure("small-file-stat", 0, SMALL_COUNT, "METADATA_REPEAT_OS_CACHE_UNCONTROLLED", () -> {
            long bytes = 0;
            for (int i = 1; i < inputs.size(); i++) {
                BasicFileAttributes attributes = Files.readAttributes(inputs.get(i), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()) { throw new IOException("Synthetic stat encountered non-file"); }
                bytes += attributes.size();
            }
            if (bytes != (long) SMALL_COUNT * SMALL_BYTES) { throw new IOException("Synthetic stat size mismatch"); }
            return "not-applicable-metadata-verified";
        });
        measure("synthetic-tree-traverse", 0, SMALL_COUNT + 1, "GENERATED_DATASET_ONLY_OS_CACHE_UNCONTROLLED", () -> {
            // This exact tree was generated above; production staging never uses Files.walk.
            try (Stream<Path> paths = Files.walk(downloads)) {
                if (paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).count() != SMALL_COUNT + 1L) {
                    throw new IOException("Synthetic traversal count mismatch");
                }
            }
            return "not-applicable-count-verified";
        });
        Set<URI> uris = new HashSet<>();
        List<ReadOnlyStager.SelectedFile> descriptors = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            Path input = inputs.get(i);
            uris.add(input.toUri());
            descriptors.add(new ReadOnlyStager.SelectedFile(input.toUri(), input.getFileName().toString(),
                    "application/octet-stream", Files.size(input), hashes.get(i), ReadOnlyStager.Kind.FILE,
                    () -> Files.newInputStream(input, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)));
        }
        DownloadsSelection selection = DownloadsSelection.authorize(true, downloads.toUri(), uris, SyntheticStorageBenchmark::syntheticVersion);
        try (ReadOnlyStager stager = new ReadOnlyStager(cache,
                new ReadOnlyStager.Limits(2048, 96L * 1024 * 1024, 96L * 1024 * 1024, 192L * 1024 * 1024, 128))) {
            ReadOnlyStager.Generation[] published = new ReadOnlyStager.Generation[1];
            measure("selected-file-stage-and-verify", LARGE_BYTES + (long) SMALL_COUNT * SMALL_BYTES, inputs.size(),
                    "BOUNDED_STREAM_AND_STAGED_REREAD_OS_CACHE_UNCONTROLLED", () -> {
                published[0] = stager.stage(selection, descriptors, new ReadOnlyStager.Cancellation());
                verify(largeHash[0], published[0].files().get(0).sha256());
                if (published[0].guestShareSpec().mountStatus() != ReadOnlyStager.MountStatus.UNVERIFIED) {
                    throw new IOException("Prototype cannot claim mounted guest");
                }
                return published[0].files().get(0).sha256();
            });
            Path copied = peer.resolve("large-copy.bin");
            measure("copy-forward-host-filesystem", LARGE_BYTES, 1, "SIMULATED_PEER_NO_GUEST_NO_SYNC", () -> {
                try (InputStream input = published[0].open("large.bin")) { Files.copy(input, copied); }
                return checkedHash(copied, largeHash[0]);
            });
            Path returned = downloads.resolve("returned-copy.bin");
            measure("copy-return-host-filesystem", LARGE_BYTES, 1, "SIMULATED_PEER_STANDALONE_COPY_NO_SHARED_WRITE", () -> {
                try (InputStream input = Files.newInputStream(copied)) { Files.copy(input, returned); }
                return checkedHash(returned, largeHash[0]);
            });
            measure("interrupt-truncated-localhost-stream", 65536, 1, "ACTUAL_TCP_TRUNCATION_ROLLBACK_NO_RETRY", () -> {
                try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
                     Socket source = new Socket("127.0.0.1", server.getLocalPort()); Socket sender = server.accept()) {
                    source.setSoTimeout(2000);
                    Thread producer = new Thread(() -> {
                        try { sender.getOutputStream().write(new byte[65536]); sender.shutdownOutput(); }
                        catch (IOException error) { throw new IllegalStateException(error); }
                    }, "synthetic-truncation-source");
                    producer.setDaemon(true);
                    producer.start();
                    DownloadsSelection one = DownloadsSelection.authorize(true, downloads.toUri(), Set.of(large.toUri()), SyntheticStorageBenchmark::syntheticVersion);
                    ReadOnlyStager.SelectedFile broken = new ReadOnlyStager.SelectedFile(large.toUri(), "large.bin", "application/octet-stream",
                            LARGE_BYTES, largeHash[0], ReadOnlyStager.Kind.FILE, source::getInputStream);
                    boolean rejected = false;
                    try { stager.stage(one, List.of(broken), new ReadOnlyStager.Cancellation()); }
                    catch (IOException expected) { rejected = true; }
                    if (!rejected || stager.current() != published[0]) { throw new IOException("Interrupted generation exposed"); }
                    return checkedHash(copied, largeHash[0]);
                }
            });
            measure("interrupt-cancel-blocked-localhost-stream", 1, 1, "ACTUAL_TCP_CANCEL_ROLLBACK_NO_RETRY", () -> {
                try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
                     Socket source = new Socket("127.0.0.1", server.getLocalPort()); Socket sender = server.accept()) {
                    source.setSoTimeout(5000);
                    sender.getOutputStream().write(1);
                    CountDownLatch opened = new CountDownLatch(1);
                    ReadOnlyStager.Cancellation cancellation = new ReadOnlyStager.Cancellation();
                    DownloadsSelection one = DownloadsSelection.authorize(true, downloads.toUri(), Set.of(large.toUri()), SyntheticStorageBenchmark::syntheticVersion);
                    ReadOnlyStager.SelectedFile blocked = new ReadOnlyStager.SelectedFile(large.toUri(), "large.bin", "application/octet-stream",
                            -1, null, ReadOnlyStager.Kind.FILE, () -> { opened.countDown(); return source.getInputStream(); });
                    FutureTask<Exception> task = new FutureTask<>(() -> {
                        try { stager.stage(one, List.of(blocked), cancellation); return null; }
                        catch (Exception error) { return error; }
                    });
                    Thread worker = new Thread(task, "synthetic-benchmark-cancel");
                    worker.setDaemon(true);
                    worker.start();
                    if (!opened.await(2, TimeUnit.SECONDS)) { throw new IOException("Synthetic stream did not open"); }
                    cancellation.cancel();
                    if (!(task.get(2, TimeUnit.SECONDS) instanceof IOException) || stager.current() != published[0]) {
                        throw new IOException("Cancelled generation exposed");
                    }
                    return largeHash[0];
                }
            });
        }
        System.out.println("BOUNDARY guest9p=NOT_RUN virtiofs=NOT_RUN AndroidUSB=NOT_RUN osColdCache=NOT_CONTROLLED readOnlyGuestMount=UNVERIFIED");
    }

    /** Synthetic single-process fixture only, explicitly not an Android held-FD identity proof. */
    private static String syntheticVersion(URI root, URI source) throws IOException {
        if (!"file".equals(source.getScheme())) { throw new SecurityException("Synthetic fixture must be local"); }
        Path path = Path.of(source);
        if (!path.startsWith(Path.of(root)) || Files.isSymbolicLink(path)) { throw new SecurityException("Synthetic fixture escaped"); }
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return "synthetic-single-owner:" + attributes.size() + ":" + attributes.lastModifiedTime().toMillis()
                + ":" + attributes.creationTime().toMillis();
    }

    private static void measure(String name, long bytes, int files, String cache, Operation operation) throws Exception {
        long cpu = OS == null ? -1 : OS.getProcessCpuTime();
        long start = System.nanoTime();
        String hash = operation.run();
        long elapsed = System.nanoTime() - start;
        long cpuElapsed = OS == null || cpu < 0 ? -1 : OS.getProcessCpuTime() - cpu;
        long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        System.out.println("METRIC {\"operation\":\"" + name + "\",\"bytes\":" + bytes + ",\"files\":" + files
                + ",\"wallNanos\":" + elapsed + ",\"processCpuNanos\":" + cpuElapsed + ",\"heapUsedBytes\":" + heap
                + ",\"cacheCondition\":\"" + cache + "\",\"sha256\":\"" + hash + "\",\"integrity\":\"VERIFIED\"}");
    }
    private static MessageDigest digest() throws Exception { return MessageDigest.getInstance("SHA-256"); }
    private static String generate(Path path, long bytes, long seed) throws Exception {
        MessageDigest hash = digest();
        Random random = new Random(seed);
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            long remaining = bytes;
            while (remaining > 0) {
                random.nextBytes(buffer);
                int count = (int) Math.min(remaining, buffer.length);
                output.write(buffer, 0, count);
                hash.update(buffer, 0, count);
                remaining -= count;
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    private static String hash(Path path) throws Exception {
        MessageDigest hash = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) { hash.update(buffer, 0, count); }
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    private static String checkedHash(Path path, String expected) throws Exception {
        String actual = hash(path);
        verify(expected, actual);
        return actual;
    }
    private static void verify(String expected, String actual) throws IOException {
        if (!expected.equals(actual)) { throw new IOException("Synthetic consistency check failed"); }
    }
}
