package io.github.nyzeep.orchestella.runtime.contract.sharing;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ReadOnlyStagerTest {
    private static final String ABC_HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final URI ROOT = URI.create("content://com.android.externalstorage.documents/tree/primary%3ADownload");
    private static final URI ABC = URI.create(ROOT + "/document/primary%3ADownload%2Fabc.txt");

    @Test public void selectedNonseekableBytesBecomeVerifiedReadOnlyGeneration() throws Exception {
        Path cache = Files.createTempDirectory("phase5-owned-staging-");
        DownloadsSelection scope = explicitScope(true, ROOT, Set.of(ABC));
        ReadOnlyStager.SelectedFile file = new ReadOnlyStager.SelectedFile(ABC, "abc.txt", "text/plain", 3,
                ABC_HASH, ReadOnlyStager.Kind.FILE, () -> new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)) {
                    @Override public boolean markSupported() { return false; }
                });
        try (ReadOnlyStager stager = new ReadOnlyStager(cache, new ReadOnlyStager.Limits(4, 100, 200, 400, 128))) {
            assertNull(stager.current());
            ReadOnlyStager.Generation generation = stager.stage(scope, List.of(file), new ReadOnlyStager.Cancellation());
            assertSame(generation, stager.current());
            assertEquals(ABC_HASH, generation.files().get(0).sha256());
            assertEquals(3, generation.files().get(0).bytes());
            try (InputStream input = generation.open("abc.txt")) {
                assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
            }
            assertThrows(ReadOnlyFileSystemException.class, () -> generation.write("abc.txt", new byte[1]));
            ReadOnlyStager.GuestShareSpec spec = generation.guestShareSpec();
            assertTrue(spec.readOnly());
            assertEquals(ReadOnlyStager.MountStatus.UNVERIFIED, spec.mountStatus());
            assertEquals(generation.id(), spec.generationId());
            assertEquals(List.of("abc.txt"), spec.catalogNames());
            assertEquals("/mnt/downloads", spec.guestMountPoint());
        }
        try (Stream<Path> paths = Files.list(cache)) { assertEquals(0, paths.count()); }
    }

    @Test public void uriScopeRejectsUnapprovedUnknownDirectoryAndEscapedSources() throws Exception {
        assertThrows(SecurityException.class, () -> explicitScope(false, ROOT, Set.of(ABC)));
        for (URI bad : new URI[] {
                URI.create("content://evil.documents/document/primary%3ADownload%2Fx"),
                URI.create(ROOT + "/document/primary%3ADocuments%2Fx"),
                URI.create(ROOT + "/document/primary%3ADownload%2F..%2Fx"),
                URI.create(ROOT + "/document/primary%3ADownload%2F%252e%252e%2Fx"),
                URI.create(ROOT + "/document/primary%3ADownload%2F.git%2Fobjects%2Fsentinel"),
                URI.create(ROOT + "/document/primary%3ADownload%2F.env"),
                URI.create(ROOT + "/document/primary%3ADownload%2Fx?token=synthetic"),
                URI.create(ROOT + "/document/primary%3ADownload%2F%00x"),
                URI.create(ROOT + "/document/primary%3ADownload%2Fx%5Cy"),
                URI.create(ROOT + "/document/primary%3ADownload%2F%c0%af")}) {
            assertThrows(SecurityException.class, () -> explicitScope(true, ROOT, Set.of(bad)));
        }
        assertThrows(SecurityException.class, () -> explicitScope(true,
                URI.create("content://com.android.providers.downloads.documents/tree/unknown"), Set.of(ABC)));
        Path owned = Files.createTempDirectory("phase5-source-boundary-");
        Path downloads = Files.createDirectory(owned.resolve("Downloads"));
        Path outside = Files.writeString(owned.resolve("outside.txt"), "synthetic");
        assertThrows(SecurityException.class, () -> explicitScope(true, downloads.toUri(), Set.of(outside.toUri())));
        assertThrows(SecurityException.class, () -> explicitScope(true, downloads.toUri(), Set.of(downloads.toUri())));
        Path directory = Files.createDirectory(downloads.resolve("directory"));
        assertThrows(SecurityException.class, () -> explicitScope(true, downloads.toUri(), Set.of(directory.toUri())));
    }

    @Test public void descriptorAdmissionFailsBeforeAnySupplierReads() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        ReadOnlyStager.InputSupplier forbidden = () -> { opens.incrementAndGet(); throw new AssertionError("must not read rejected source"); };
        Path cache = Files.createTempDirectory("phase5-admission-");
        DownloadsSelection scope = explicitScope(true, ROOT, Set.of(ABC));
        try (ReadOnlyStager stager = new ReadOnlyStager(cache, new ReadOnlyStager.Limits(1, 10, 10, 10, 32))) {
            for (String name : new String[] {"../escape", "nested/file", "back\\slash", ".git", ".env", "%2e%2e", "x:ads", "NUL", "tail.", "line\nname"}) {
                assertThrows(SecurityException.class, () -> stager.stage(scope,
                        List.of(selected(ABC, name, 3, ABC_HASH, forbidden)), new ReadOnlyStager.Cancellation()));
            }
            ReadOnlyStager.SelectedFile unknown = new ReadOnlyStager.SelectedFile(ABC, "abc.txt", "text/plain", 3,
                    ABC_HASH, ReadOnlyStager.Kind.UNKNOWN, forbidden);
            assertThrows(SecurityException.class, () -> stager.stage(scope, List.of(unknown), new ReadOnlyStager.Cancellation()));
            ReadOnlyStager.SelectedFile noMime = new ReadOnlyStager.SelectedFile(ABC, "abc.txt", null, 3,
                    ABC_HASH, ReadOnlyStager.Kind.FILE, forbidden);
            assertThrows(SecurityException.class, () -> stager.stage(scope, List.of(noMime), new ReadOnlyStager.Cancellation()));
            assertThrows(IOException.class, () -> stager.stage(scope, List.of(selected(ABC, "abc.txt", 11, ABC_HASH, forbidden)), new ReadOnlyStager.Cancellation()));
            ReadOnlyStager.Cancellation cancelled = new ReadOnlyStager.Cancellation();
            cancelled.cancel();
            assertThrows(InterruptedIOException.class, () -> stager.stage(scope, List.of(selected(ABC, "abc.txt", 3, ABC_HASH, forbidden)), cancelled));
            assertEquals(0, opens.get());
            assertNull(stager.current());
        }
    }

    @Test public void failedReplacementPreservesPreviousGenerationAndNoPendingBytes() throws Exception {
        Path cache = Files.createTempDirectory("phase5-rollback-");
        DownloadsSelection scope = explicitScope(true, ROOT, Set.of(ABC));
        try (ReadOnlyStager stager = new ReadOnlyStager(cache, new ReadOnlyStager.Limits(4, 20, 20, 100, 128))) {
            ReadOnlyStager.Generation first = stager.stage(scope, List.of(abc()), new ReadOnlyStager.Cancellation());
            Path session;
            try (Stream<Path> paths = Files.list(cache)) { session = paths.findFirst().orElseThrow(); }
            ReadOnlyStager.SelectedFile shortFile = selected(ABC, "abc.txt", 8, null, () -> new ByteArrayInputStream(new byte[2]));
            assertThrows(IOException.class, () -> stager.stage(scope, List.of(shortFile), new ReadOnlyStager.Cancellation()));
            ReadOnlyStager.SelectedFile corrupt = selected(ABC, "abc.txt", 3, ABC_HASH, () -> new ByteArrayInputStream(new byte[3]));
            assertThrows(IOException.class, () -> stager.stage(scope, List.of(corrupt), new ReadOnlyStager.Cancellation()));
            assertSame(first, stager.current());
            assertEquals(3, stager.storedBytes());
            try (Stream<Path> paths = Files.list(session)) { assertEquals(1, paths.count()); }
            try (InputStream input = first.open("abc.txt")) { assertEquals("abc", new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
            assertThrows(SecurityException.class, () -> first.open("../outside.txt"));
        }
    }

    @Test public void actualSocketTruncationNeverPublishesGeneration() throws Exception {
        Path cache = Files.createTempDirectory("phase5-broken-source-");
        DownloadsSelection scope = explicitScope(true, ROOT, Set.of(ABC));
        try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             Socket input = new Socket("127.0.0.1", server.getLocalPort());
             Socket sender = server.accept();
             ReadOnlyStager stager = new ReadOnlyStager(cache, new ReadOnlyStager.Limits(4, 100, 100, 100, 128))) {
            input.setSoTimeout(2000);
            sender.getOutputStream().write(new byte[] {1, 2, 3});
            sender.shutdownOutput();
            ReadOnlyStager.SelectedFile source = selected(ABC, "abc.txt", 50, null, input::getInputStream);
            assertThrows(IOException.class, () -> stager.stage(scope, List.of(source), new ReadOnlyStager.Cancellation()));
            assertNull(stager.current());
            assertEquals(0, stager.storedBytes());
        }
    }

    @Test public void cancellationUnblocksActualSocketAndKeepsPendingCatalogInvisible() throws Exception {
        Path cache = Files.createTempDirectory("phase5-cancel-source-");
        DownloadsSelection scope = explicitScope(true, ROOT, Set.of(ABC));
        CountDownLatch opened = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             Socket input = new Socket("127.0.0.1", server.getLocalPort());
             Socket sender = server.accept();
             ReadOnlyStager stager = new ReadOnlyStager(cache, new ReadOnlyStager.Limits(4, 100, 100, 100, 128))) {
            input.setSoTimeout(3000);
            sender.getOutputStream().write(new byte[] {1});
            ReadOnlyStager.Cancellation cancellation = new ReadOnlyStager.Cancellation();
            ReadOnlyStager.SelectedFile source = selected(ABC, "abc.txt", -1, null, () -> {
                opened.countDown(); return input.getInputStream();
            });
            FutureTask<Exception> task = new FutureTask<>(() -> {
                try { stager.stage(scope, List.of(source), cancellation); return null; }
                catch (Exception failure) { return failure; }
            });
            Thread worker = new Thread(task, "synthetic-stream-interrupt");
            worker.setDaemon(true);
            worker.start();
            assertTrue(opened.await(2, TimeUnit.SECONDS));
            assertNull("pending generation has no catalog", stager.current());
            cancellation.cancel();
            assertTrue(task.get(2, TimeUnit.SECONDS) instanceof IOException);
            assertNull(stager.current());
            assertEquals(0, stager.storedBytes());
        }
    }

    @Test public void quotaIncludesRetainedGenerationsAndReleaseRevokesReadHandles() throws Exception {
        Path cache = Files.createTempDirectory("phase5-retained-quota-");
        DownloadsSelection scope = explicitScope(true, ROOT, Set.of(ABC));
        try (ReadOnlyStager stager = new ReadOnlyStager(cache, new ReadOnlyStager.Limits(1, 4, 4, 5, 128))) {
            ReadOnlyStager.Generation first = stager.stage(scope, List.of(abc()), new ReadOnlyStager.Cancellation());
            assertThrows(IOException.class, () -> stager.stage(scope, List.of(abc()), new ReadOnlyStager.Cancellation()));
            InputStream reader = first.open("abc.txt");
            first.close();
            assertThrows(IOException.class, reader::read);
            assertThrows(IllegalStateException.class, () -> first.open("abc.txt"));
            assertEquals(0, stager.storedBytes());
            assertNotNull(stager.stage(scope, List.of(abc()), new ReadOnlyStager.Cancellation()));
        }
    }

    @Test public void unknownSizeQuotaAndWholeSelectionAdmissionAreEnforced() throws Exception {
        Path cache = Files.createTempDirectory("phase5-stream-quota-");
        DownloadsSelection scope = explicitScope(true, ROOT, Set.of(ABC));
        try (ReadOnlyStager stager = new ReadOnlyStager(cache, new ReadOnlyStager.Limits(1, 4, 4, 4, 128))) {
            Path fixture = Files.write(Files.createTempFile("phase5-quota-source-", ".bin"), new byte[5]);
            ReadOnlyStager.SelectedFile source = selected(ABC, "abc.txt", -1, null, () -> Files.newInputStream(fixture));
            assertThrows(IOException.class, () -> stager.stage(scope, List.of(source), new ReadOnlyStager.Cancellation()));
            assertNull(stager.current());
            assertEquals(0, stager.storedBytes());
            java.net.URI second = URI.create(ROOT + "/document/primary%3ADownload%2Fsecond.txt");
            DownloadsSelection pair = explicitScope(true, ROOT, Set.of(ABC, second));
            ReadOnlyStager.SelectedFile forbidden = selected(second, "second.txt", 1, null, () -> { throw new AssertionError("no partial admission"); });
            assertThrows(SecurityException.class, () -> stager.stage(pair, List.of(abc(), forbidden), new ReadOnlyStager.Cancellation()));
            assertThrows(SecurityException.class, () -> stager.stage(pair, List.of(abc()), new ReadOnlyStager.Cancellation()));
        }
        try (ReadOnlyStager stager = new ReadOnlyStager(cache, new ReadOnlyStager.Limits(2, 4, 8, 8, 128))) {
            java.net.URI second = URI.create(ROOT + "/document/primary%3ADownload%2Fsecond.txt");
            DownloadsSelection pair = explicitScope(true, ROOT, Set.of(ABC, second));
            ReadOnlyStager.SelectedFile caseAlias = selected(second, "ABC.TXT", 3, ABC_HASH,
                    () -> { throw new AssertionError("case aliases must be rejected before source read"); });
            assertThrows(SecurityException.class, () -> stager.stage(pair, List.of(abc(), caseAlias), new ReadOnlyStager.Cancellation()));
        }
    }

    @Test public void absentRevokedOrChangedProofNeverPublishesSource() throws Exception {
        assertThrows(SecurityException.class, () -> DownloadsSelection.authorize(true, ROOT, Set.of(ABC), null));
        assertThrows(SecurityException.class, () -> DownloadsSelection.authorize(true, ROOT, Set.of(ABC), (root, file) -> null));
        assertThrows(SecurityException.class, () -> DownloadsSelection.authorize(true, ROOT, Set.of(ABC), (root, file) -> {
            throw new SecurityException("synthetic provider proof refusal");
        }));
        java.util.concurrent.atomic.AtomicReference<String> version = new java.util.concurrent.atomic.AtomicReference<>("held-object-version-1");
        DownloadsSelection selection = DownloadsSelection.authorize(true, ROOT, Set.of(ABC), (root, file) -> version.get());
        Path cache = Files.createTempDirectory("phase5-source-version-");
        try (ReadOnlyStager stager = new ReadOnlyStager(cache, new ReadOnlyStager.Limits(1, 10, 10, 10, 128))) {
            ReadOnlyStager.SelectedFile changing = selected(ABC, "abc.txt", 3, ABC_HASH, () -> {
                version.set("held-object-version-2");
                return new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8));
            });
            assertThrows(SecurityException.class, () -> stager.stage(selection, List.of(changing), new ReadOnlyStager.Cancellation()));
            assertNull(stager.current());
            assertEquals(0, stager.storedBytes());
        }
    }

    private static DownloadsSelection explicitScope(boolean authorized, URI root, Set<URI> files) throws IOException {
        // Content provider proof is a synthetic boundary fixture, never an Android provider claim.
        return DownloadsSelection.authorize(authorized, root, files, (boundary, file) -> "synthetic-held-source-version-1");
    }

    private static ReadOnlyStager.SelectedFile abc() {
        return selected(ABC, "abc.txt", 3, ABC_HASH, () -> new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
    }
    private static ReadOnlyStager.SelectedFile selected(URI uri, String name, long bytes, String hash, ReadOnlyStager.InputSupplier input) {
        return new ReadOnlyStager.SelectedFile(uri, name, "text/plain", bytes, hash, ReadOnlyStager.Kind.FILE, input);
    }
}
