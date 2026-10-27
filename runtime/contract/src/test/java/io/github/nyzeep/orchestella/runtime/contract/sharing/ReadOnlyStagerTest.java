package io.github.nyzeep.orchestella.runtime.contract.sharing;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.github.nyzeep.orchestella.runtime.contract.sharing.ReadOnlyStager.Cancellation;
import io.github.nyzeep.orchestella.runtime.contract.sharing.ReadOnlyStager.FileInfo;
import io.github.nyzeep.orchestella.runtime.contract.sharing.ReadOnlyStager.Generation;
import io.github.nyzeep.orchestella.runtime.contract.sharing.ReadOnlyStager.GuestShareSpec;
import io.github.nyzeep.orchestella.runtime.contract.sharing.ReadOnlyStager.Kind;
import io.github.nyzeep.orchestella.runtime.contract.sharing.ReadOnlyStager.Limits;
import io.github.nyzeep.orchestella.runtime.contract.sharing.ReadOnlyStager.SelectedFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Contract tests for {@link ReadOnlyStager}: exact-selection admission, byte quotas, hash and
 * identity re-checks, immutable read-only generations, cleanup on failure/close, and no
 * cross-talk between concurrent stages. The staged root is always a real directory so the
 * tests exercise the same fail-closed {@link PathBoundary} path as production.
 */
public final class ReadOnlyStagerTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private final Map<URI, String> versions = new ConcurrentHashMap<>();
    private Path downloads;
    private Path cache;

    @Before
    public void setUp() throws IOException {
        downloads = temporary.newFolder("Downloads").toPath().toRealPath();
        cache = temporary.newFolder("cache").toPath().toRealPath();
    }

    private static Limits limits() {
        return new Limits(10, 4096, 16384, 32768, 240);
    }

    private URI source(String name, String content) throws IOException {
        Path path = Files.writeString(downloads.resolve(name), content, UTF_8);
        URI uri = path.toUri();
        versions.put(uri, "version-1");
        return uri;
    }

    private DownloadsSelection selection(URI... sources) throws IOException {
        return DownloadsSelection.authorize(true, downloads.toUri(), new LinkedHashSet<>(List.of(sources)),
                (root, selected) -> versions.get(selected));
    }

    private static SelectedFile file(URI uri, String name, String mimeType, long declaredBytes, String sha256) {
        return new SelectedFile(uri, name, mimeType, declaredBytes, sha256, Kind.FILE,
                () -> Files.newInputStream(Paths.get(uri), StandardOpenOption.READ));
    }

    private static SelectedFile file(URI uri, String name) {
        return file(uri, name, "text/plain", -1, null);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private List<Path> stagedFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(cache)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        return files;
    }

    private void assertNothingWasPublished(ReadOnlyStager stager) throws IOException {
        assertNull("a failed stage must never become current", stager.current());
        assertEquals(0, stager.storedBytes());
        assertEquals("a failed stage must own no file", List.of(), stagedFiles());
    }

    @Test
    public void stagePublishesAnImmutableReadOnlyGeneration() throws Exception {
        String content = "hello\nworld";
        URI note = source("note.txt", content);
        ReadOnlyStager stager = new ReadOnlyStager(cache, limits());
        try {
            Generation generation = stager.stage(selection(note),
                    List.of(file(note, "note.txt", "text/plain", content.getBytes(UTF_8).length, sha256(content))),
                    new Cancellation());
            assertEquals(1, generation.files().size());
            FileInfo info = generation.files().get(0);
            assertEquals("note.txt", info.name());
            assertEquals("text/plain", info.mimeType());
            assertEquals(content.getBytes(UTF_8).length, info.bytes());
            assertEquals(sha256(content), info.sha256());
            assertTrue(generation.id().startsWith("generation-"));
            assertEquals(generation, stager.current());
            assertEquals(content.getBytes(UTF_8).length, stager.storedBytes());

            try (InputStream input = generation.open("note.txt")) {
                assertEquals(content, new String(input.readAllBytes(), UTF_8));
            }
            List<Path> staged = stagedFiles();
            assertEquals(1, staged.size());
            assertFalse("staged copies must be protected against in-place rewrite",
                    Files.isWritable(staged.get(0)));

            assertThrows(SecurityException.class, () -> generation.open("other.txt"));
            assertThrows(SecurityException.class, () -> generation.open("../note.txt"));
            assertThrows(SecurityException.class, () -> generation.open("sub/note.txt"));
            assertThrows(SecurityException.class, () -> generation.open("/note.txt"));
            assertThrows(SecurityException.class, () -> generation.open("."));
            assertThrows(java.nio.file.ReadOnlyFileSystemException.class,
                    () -> generation.write("note.txt", new byte[] {1}));

            GuestShareSpec spec = generation.guestShareSpec();
            assertEquals(generation.id(), spec.generationId());
            assertEquals(List.of("note.txt"), spec.catalogNames());
            assertEquals("downloads", spec.mountTag());
            assertEquals("/mnt/downloads", spec.guestMountPoint());
            assertTrue(spec.readOnly());
            assertEquals(ReadOnlyStager.MountStatus.UNVERIFIED, spec.mountStatus());

            generation.close();
            assertEquals(0, stager.storedBytes());
            assertNull(stager.current());
            assertEquals(List.of(), stagedFiles());
            assertThrows(IllegalStateException.class, () -> generation.open("note.txt"));
            assertThrows(IllegalStateException.class, generation::guestShareSpec);
            // Releasing twice is a no-op rather than a double delete.
            generation.close();
        } finally {
            stager.close();
        }
    }

    @Test
    public void admissionRejectsSelectionsThatAreNotAnExactExplicitFileSet() throws Exception {
        URI first = source("first.txt", "first");
        URI second = source("second.txt", "second");
        ReadOnlyStager stager = new ReadOnlyStager(cache, limits());
        try {
            // A source that is not part of the authorised selection.
            assertThrows(SecurityException.class,
                    () -> stager.stage(selection(first), List.of(file(second, "second.txt")), new Cancellation()));
            // Count mismatch: the selection has two entries but only one descriptor is admitted.
            assertThrows(SecurityException.class,
                    () -> stager.stage(selection(first, second), List.of(file(first, "first.txt")),
                            new Cancellation()));
            // An empty selection.
            assertThrows(SecurityException.class,
                    () -> stager.stage(selection(first), List.of(), new Cancellation()));
            // A directory or unknown kind.
            for (Kind kind : List.of(Kind.DIRECTORY, Kind.UNKNOWN)) {
                SelectedFile directory = new SelectedFile(first, "first.txt", "text/plain", -1, null, kind,
                        () -> Files.newInputStream(Paths.get(first)));
                assertThrows(kind.toString(), SecurityException.class,
                        () -> stager.stage(selection(first), List.of(directory), new Cancellation()));
            }
            // Two descriptors claiming the same destination name.
            assertThrows(SecurityException.class,
                    () -> stager.stage(selection(first, second),
                            List.of(file(first, "same.txt"), file(second, "SAME.txt")), new Cancellation()));
            // Missing, non-conforming or parameterised MIME types.
            for (String mime : new String[] {null, "text", "text/plain; charset=utf-8", ""}) {
                assertThrows(String.valueOf(mime), SecurityException.class,
                        () -> stager.stage(selection(first),
                                List.of(file(first, "first.txt", mime, -1, null)), new Cancellation()));
            }
            // Unsafe destination names.
            for (String name : List.of("../first.txt", "first.txt/../x", ".hidden", "", "a:b")) {
                assertThrows(name, SecurityException.class,
                        () -> stager.stage(selection(first), List.of(file(first, name)), new Cancellation()));
            }
            // Malformed digest declarations.
            for (String digest : new String[] {"abc", "z".repeat(64), sha256("first").toUpperCase() + "0"}) {
                assertThrows(digest, SecurityException.class,
                        () -> stager.stage(selection(first),
                                List.of(file(first, "first.txt", "text/plain", -1, digest)), new Cancellation()));
            }
            assertNothingWasPublished(stager);
        } finally {
            stager.close();
        }
    }

    @Test
    public void declaredSizesBeyondTheQuotaAreRejectedBeforeAnySourceIsOpened() throws Exception {
        URI first = source("first.txt", "first");
        AtomicBoolean opened = new AtomicBoolean();
        SelectedFile oversized = new SelectedFile(first, "first.txt", "text/plain", 4097, null, Kind.FILE, () -> {
            opened.set(true);
            return Files.newInputStream(Paths.get(first));
        });
        ReadOnlyStager stager = new ReadOnlyStager(cache, limits());
        try {
            assertThrows(IOException.class,
                    () -> stager.stage(selection(first), List.of(oversized), new Cancellation()));
            assertFalse("admission must reject before opening the source", opened.get());
            assertNothingWasPublished(stager);
        } finally {
            stager.close();
        }
    }

    @Test
    public void streamingEnforcesFileAndTotalByteQuotas() throws Exception {
        URI nine = source("nine.txt", "123456789");
        URI sixA = source("six-a.txt", "abcdef");
        URI sixB = source("six-b.txt", "ghijkl");
        ReadOnlyStager stager = new ReadOnlyStager(cache, new Limits(4, 8, 10, 1024, 240));
        try {
            // Nine actual bytes against an eight byte file limit.
            assertThrows(IOException.class,
                    () -> stager.stage(selection(nine), List.of(file(nine, "nine.txt")), new Cancellation()));
            assertNothingWasPublished(stager);
            // Declared total above the quota is caught at admission.
            assertThrows(IOException.class, () -> stager.stage(selection(sixA, sixB),
                    List.of(file(sixA, "six-a.txt", "text/plain", 6, null),
                            file(sixB, "six-b.txt", "text/plain", 6, null)), new Cancellation()));
            assertNothingWasPublished(stager);
            // Unknown sizes are caught while streaming the second file.
            assertThrows(IOException.class, () -> stager.stage(selection(sixA, sixB),
                    List.of(file(sixA, "six-a.txt"), file(sixB, "six-b.txt")), new Cancellation()));
            assertNothingWasPublished(stager);
        } finally {
            stager.close();
        }
    }

    @Test
    public void aSourceThatDoesNotMatchItsDeclarationIsRejected() throws Exception {
        URI shortSource = source("short.txt", "abc");
        URI longSource = source("long.txt", "abcdefg");
        ReadOnlyStager stager = new ReadOnlyStager(cache, limits());
        try {
            assertThrows(IOException.class, () -> stager.stage(selection(shortSource),
                    List.of(file(shortSource, "short.txt", "text/plain", 3, sha256("ab"))), new Cancellation()));
            assertNothingWasPublished(stager);
            assertThrows(IOException.class, () -> stager.stage(selection(longSource),
                    List.of(file(longSource, "long.txt", "text/plain", 5, null)), new Cancellation()));
            assertNothingWasPublished(stager);
            assertThrows(IOException.class, () -> stager.stage(selection(longSource),
                    List.of(file(longSource, "long.txt", "text/plain", -1, sha256("different"))),
                    new Cancellation()));
            assertNothingWasPublished(stager);
        } finally {
            stager.close();
        }
    }

    @Test
    public void aChangedSelectedSourceIdentityIsRejectedAtStageTime() throws Exception {
        URI note = source("note.txt", "note");
        DownloadsSelection selection = selection(note);
        versions.put(note, "version-2");
        ReadOnlyStager stager = new ReadOnlyStager(cache, limits());
        try {
            assertThrows(SecurityException.class,
                    () -> stager.stage(selection, List.of(file(note, "note.txt")), new Cancellation()));
            assertNothingWasPublished(stager);
        } finally {
            stager.close();
        }
    }

    @Test
    public void aCancelledRunPublishesNothingAndDoesNotPoisonLaterStages() throws Exception {
        URI note = source("note.txt", "note");
        ReadOnlyStager stager = new ReadOnlyStager(cache, limits());
        try {
            Cancellation cancellation = new Cancellation();
            cancellation.cancel();
            assertTrue(cancellation.isCancelled());
            assertThrows(InterruptedIOException.class,
                    () -> stager.stage(selection(note), List.of(file(note, "note.txt")), cancellation));
            assertNothingWasPublished(stager);
            Generation generation = stager.stage(selection(note), List.of(file(note, "note.txt")),
                    new Cancellation());
            assertNotNull(generation);
            assertEquals(generation, stager.current());
        } finally {
            stager.close();
        }
    }

    @Test
    public void closeCancelsAnInFlightStageAndReleasesItsFiles() throws Exception {
        URI note = source("note.txt", "note");
        BlockingSource blocking = new BlockingSource();
        SelectedFile selected = new SelectedFile(note, "note.txt", "text/plain", 1024, null, Kind.FILE,
                () -> blocking);
        ReadOnlyStager stager = new ReadOnlyStager(cache, limits());
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread staging = new Thread(() -> {
            try {
                stager.stage(selection(note), List.of(selected), new Cancellation());
            } catch (Throwable error) {
                outcome.set(error);
            }
        }, "staging-under-close");
        staging.setDaemon(true);
        try {
            staging.start();
            assertTrue("staging never reached the blocking source",
                    blocking.entered.await(10, TimeUnit.SECONDS));
            stager.close();
            staging.join(10_000);
            assertFalse("staging thread must stop after close", staging.isAlive());
            assertNotNull("close must abort the active stage", outcome.get());
            assertTrue("abort surfaces as an I/O failure: " + outcome.get(),
                    outcome.get() instanceof IOException);
            assertEquals(List.of(), stagedFiles());
        } finally {
            stager.close();
        }
    }

    @Test
    public void concurrentStagesKeepTheirOwnCataloguesAndBytes() throws Exception {
        URI alpha = source("alpha.txt", "alpha");
        URI beta = source("beta.txt", "beta!");
        ReadOnlyStager stager = new ReadOnlyStager(cache, limits());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Generation>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> stager.stage(selection(alpha),
                        List.of(file(alpha, "alpha.txt")), new Cancellation())));
                futures.add(pool.submit(() -> stager.stage(selection(beta),
                        List.of(file(beta, "beta.txt")), new Cancellation())));
            }
            for (Future<Generation> future : futures) {
                Generation generation = future.get(30, TimeUnit.SECONDS);
                assertEquals("each generation owns exactly one file", 1, generation.files().size());
                String name = generation.files().get(0).name();
                String expected = "alpha.txt".equals(name) ? "alpha" : "beta!";
                try (InputStream input = generation.open(name)) {
                    assertEquals(expected, new String(input.readAllBytes(), UTF_8));
                }
                assertThrows(SecurityException.class,
                        () -> generation.open("alpha.txt".equals(name) ? "beta.txt" : "alpha.txt"));
            }
            assertEquals(20, stager.storedBytes());
            assertEquals(4, stagedFiles().size());
        } finally {
            pool.shutdownNow();
            stager.close();
        }
        assertEquals(List.of(), stagedFiles());
    }

    @Test
    public void closeReleasesTheSessionScopeAndRefusesReuse() throws Exception {
        URI note = source("note.txt", "note");
        ReadOnlyStager stager = new ReadOnlyStager(cache, limits());
        Generation generation = stager.stage(selection(note), List.of(file(note, "note.txt")),
                new Cancellation());
        InputStream reader = generation.open("note.txt");
        stager.close();
        assertEquals("staged bytes must drop to zero once the scope closes", 0, stager.storedBytes());
        assertNull(stager.current());
        assertEquals("the session directory must be removed", List.of(), stagedFiles());
        try (Stream<Path> children = Files.list(cache)) {
            assertTrue("every staged directory must be gone", children.findAny().isEmpty());
        }
        assertThrows(IllegalStateException.class, () -> stager.stage(selection(note),
                List.of(file(note, "note.txt")), new Cancellation()));
        assertThrows(IllegalStateException.class, () -> generation.open("note.txt"));
        // Closing the scope revokes every open reader instead of leaving it dangling.
        assertThrows(IOException.class, reader::read);
        reader.close();
        stager.close();
    }

    @Test
    public void theRootMustBeAnExistingOwnedDirectory() throws Exception {
        assertThrows(IOException.class,
                () -> new ReadOnlyStager(cache.resolve("absent"), limits()));
        Path plainFile = Files.createFile(cache.resolve("plain.txt"));
        assertThrows(SecurityException.class, () -> new ReadOnlyStager(plainFile, limits()));
        assertThrows(SecurityException.class, () -> new ReadOnlyStager(Path.of("relative", "dir"), limits()));
        assertThrows(SecurityException.class,
                () -> new ReadOnlyStager(cache.resolve("sub").resolve("..").resolve("cache"), limits()));
        // A canonical owned directory remains admissible and its scope is removed on close.
        ReadOnlyStager accepted = new ReadOnlyStager(cache, limits());
        accepted.close();
        try (Stream<Path> children = Files.list(cache)) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith("selected-files-")));
        }
    }

    @Test
    public void aSymlinkedRootIsRejectedWhenThePlatformSupportsSymlinks() throws Exception {
        Path real = Files.createDirectory(cache.resolve("real"));
        try {
            Files.createSymbolicLink(cache.resolve("link"), real);
        } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            assumeTrue("symbolic links are unavailable on this platform", false);
        }
        assertThrows(SecurityException.class, () -> new ReadOnlyStager(cache.resolve("link"), limits()));
        ReadOnlyStager accepted = new ReadOnlyStager(real, limits());
        accepted.close();
    }

    @Test
    public void limitsRejectRangesOutsideTheirDeclaredBounds() {
        List<Runnable> invalid = List.of(
                () -> new Limits(0, 1, 1, 1, 240),
                () -> new Limits(10001, 1, 1, 1, 240),
                () -> new Limits(1, 0, 1, 1, 240),
                () -> new Limits(1, 1, 0, 1, 240),
                () -> new Limits(1, 1, 1, 0, 240),
                () -> new Limits(1, 1, 1, 1, 0),
                () -> new Limits(1, 1, 1, 1, 241));
        for (Runnable rejected : invalid) {
            assertThrows(IllegalArgumentException.class, rejected::run);
        }
        // Boundary values are accepted.
        new Limits(1, 1, 1, 1, 1);
        new Limits(10000, 1, 1, 1, 240);
    }

    /** A source that only returns once close() is called, so cancellation has to intervene. */
    private static final class BlockingSource extends InputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : single[0] & 255;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            entered.countDown();
            try {
                if (!released.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("blocking source timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted");
            }
            throw new IOException("source closed by cancellation");
        }

        @Override
        public void close() {
            released.countDown();
        }
    }
}
