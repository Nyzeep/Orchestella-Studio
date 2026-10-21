package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class StorageLayoutTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void initializesIdempotently() throws Exception {
        final Path root = temporary.newFolder("store").toPath();
        final StorageLayout layout = StorageLayout.initialize(root);
        assertTrue(Files.isDirectory(layout.home()));
        assertTrue(Files.isDirectory(layout.workspace()));
        assertTrue(Files.isDirectory(layout.tmp()));
        assertTrue(Files.isDirectory(layout.cache()));
        assertTrue(Files.isDirectory(layout.exports()));
        final Path marker = root.resolve(StorageLayout.MARKER_NAME);
        assertEquals("version=1\n", Files.readString(marker, StandardCharsets.UTF_8));
        final StorageLayout again = StorageLayout.initialize(root);
        assertEquals(layout.home(), again.home());
        assertEquals("version=1\n", Files.readString(marker, StandardCharsets.UTF_8));
    }

    @Test
    public void rejectsUnknownVersion() throws Exception {
        final Path root = temporary.newFolder("store").toPath();
        Files.writeString(root.resolve(StorageLayout.MARKER_NAME), "version=2\n");
        assertThrows(StorageException.class, () -> StorageLayout.initialize(root));
    }

    @Test
    public void rejectsSymlinkedMarker() throws Exception {
        final Path root = temporary.newFolder("store").toPath();
        final Path real = temporary.newFile("marker").toPath();
        Files.writeString(real, "version=1\n");
        Files.createSymbolicLink(root.resolve(StorageLayout.MARKER_NAME), real);
        assertThrows(StorageException.class, () -> StorageLayout.initialize(root));
    }

    @Test
    public void rejectsSymlinkedSubdirectory() throws Exception {
        final Path root = temporary.newFolder("store").toPath();
        final Path elsewhere = temporary.newFolder("elsewhere").toPath();
        Files.createSymbolicLink(root.resolve("home"), elsewhere);
        assertThrows(StorageException.class, () -> StorageLayout.initialize(root));
    }

    @Test
    public void rejectsTraversalAndAbsolutePaths() throws Exception {
        final Path root = temporary.newFolder("store").toPath();
        final StorageLayout layout = StorageLayout.initialize(root);
        assertThrows(StorageException.class, () -> layout.resolve("../etc/passwd"));
        assertThrows(StorageException.class, () -> layout.resolve("/etc/passwd"));
        assertThrows(StorageException.class, () -> layout.resolve("home/../../etc/passwd"));
        assertThrows(StorageException.class, () -> layout.resolve("home//a"));
        assertThrows(StorageException.class, () -> layout.resolve("home/./a"));
        assertThrows(StorageException.class, () -> layout.resolve("home/"));
    }

    @Test
    public void resolveNoSymlinkRejectsSymlinkComponent() throws Exception {
        final Path root = temporary.newFolder("store").toPath();
        final StorageLayout layout = StorageLayout.initialize(root);
        Files.writeString(layout.home().resolve("a.txt"), "a");
        assertEquals(layout.home().resolve("a.txt"), layout.resolveNoSymlink("home/a.txt"));
        Files.createSymbolicLink(layout.home().resolve("link"), Path.of("/tmp"));
        assertThrows(StorageException.class,
                () -> layout.resolveNoSymlink("home/link/a.txt"));
    }
}
