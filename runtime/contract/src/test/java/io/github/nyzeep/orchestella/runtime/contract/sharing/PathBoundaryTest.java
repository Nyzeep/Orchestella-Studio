package io.github.nyzeep.orchestella.runtime.contract.sharing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Contract tests for the fail-closed {@link PathBoundary} helpers. */
public final class PathBoundaryTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private Path base() throws IOException {
        return temporary.newFolder("base").toPath().toRealPath();
    }

    private static boolean symlinksSupported(Path base) {
        try {
            Path probe = Files.createSymbolicLink(base.resolve("symlink-probe"), base);
            Files.delete(probe);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            return false;
        }
    }

    @Test
    public void directoryAcceptsCanonicalOwnedDirectories() throws IOException {
        Path base = base();
        assertEquals(base, PathBoundary.directory(base));
        Path nested = Files.createDirectory(base.resolve("nested"));
        assertEquals(nested, PathBoundary.directory(nested));
        // Only name() rejects a leading dot; a hidden directory is a valid owned root.
        Path hidden = Files.createDirectory(base.resolve(".hidden"));
        assertEquals(hidden, PathBoundary.directory(hidden));
    }

    @Test
    public void directoryRejectsNullRelativeAndUnnormalizedPaths() throws IOException {
        Path base = base();
        assertThrows(SecurityException.class, () -> PathBoundary.directory(null));
        assertThrows(SecurityException.class, () -> PathBoundary.directory(Path.of("relative", "dir")));
        assertThrows(SecurityException.class,
                () -> PathBoundary.directory(Path.of(base.toString(), "nested", "..")));
        assertThrows(SecurityException.class, () -> PathBoundary.directory(base.resolve(".")));
    }

    @Test
    public void directoryRejectsNonDirectoriesAndMissingComponents() throws IOException {
        Path base = base();
        Path file = Files.createFile(base.resolve("plain.txt"));
        assertThrows(SecurityException.class, () -> PathBoundary.directory(file));
        assertThrows(IOException.class, () -> PathBoundary.directory(base.resolve("missing")));
    }

    @Test
    public void directoryRejectsSymlinkedComponentsWhenSupported() throws IOException {
        Path base = base();
        assumeTrue("symbolic links are unavailable on this platform", symlinksSupported(base));
        Path real = Files.createDirectory(base.resolve("real"));
        Path inside = Files.createDirectory(real.resolve("inside"));
        Files.createSymbolicLink(base.resolve("link"), real);
        assertThrows(SecurityException.class, () -> PathBoundary.directory(base.resolve("link")));
        assertThrows(SecurityException.class,
                () -> PathBoundary.directory(base.resolve("link").resolve("inside")));
        assertEquals(inside.toRealPath(), PathBoundary.directory(inside));
    }

    @Test
    public void regularFileAcceptsPlainFilesAndRejectsOtherShapes() throws IOException {
        Path base = base();
        Path file = Files.createFile(base.resolve("ok.txt"));
        PathBoundary.regularFile(file);
        assertThrows(SecurityException.class, () -> PathBoundary.regularFile(base));
        assertThrows(IOException.class, () -> PathBoundary.regularFile(base.resolve("absent.txt")));
        // A root has no parent, so directory(parent) fails closed on the null parent.
        assertThrows(SecurityException.class, () -> PathBoundary.regularFile(base.getRoot()));
    }

    @Test
    public void regularFileRejectsSymlinksWhenSupported() throws IOException {
        Path base = base();
        assumeTrue("symbolic links are unavailable on this platform", symlinksSupported(base));
        Path target = Files.createFile(base.resolve("target.txt"));
        Files.createSymbolicLink(base.resolve("link.txt"), target);
        assertThrows(SecurityException.class,
                () -> PathBoundary.regularFile(base.resolve("link.txt")));
        PathBoundary.regularFile(target);
    }

    @Test
    public void nameRejectsDotPrefixesTrailingDotsAndSeparators() {
        for (String name : List.of(
                ".", "..", ".hidden", "a/b", "a\\b", "a:b", "a?b", "a*b", "a|b", "a<b", "a>b", "a\"b")) {
            assertThrows(name, SecurityException.class, () -> PathBoundary.name(name, 240));
        }
    }

    @Test
    public void nameRejectsWindowsReservedDeviceNamesButNotLongerStems() {
        for (String name : List.of(
                "CON", "con", "NUL", "nul.txt", "COM1", "com1.tar.gz", "LPT9", "AUX", "PRN")) {
            assertThrows(name, SecurityException.class, () -> PathBoundary.name(name, 240));
        }
        for (String name : List.of("CONSOLE", "COM0", "COM10", "LPT0", "nul_backup", "con-1")) {
            assertEquals(name, PathBoundary.name(name, 240));
        }
    }

    @Test
    public void nameEnforcesUtf8ByteLimitTrailingSpaceAndEmptiness() {
        assertThrows(SecurityException.class, () -> PathBoundary.name(null, 240));
        assertThrows(SecurityException.class, () -> PathBoundary.name("", 240));
        assertEquals("abcd", PathBoundary.name("abcd", 4));
        assertThrows(SecurityException.class, () -> PathBoundary.name("abcde", 4));
        // The limit is bytes, not chars: two two-byte code points fit 4 bytes only.
        assertEquals("\u00e9\u00e9", PathBoundary.name("\u00e9\u00e9", 4));
        assertThrows(SecurityException.class, () -> PathBoundary.name("\u00e9\u00e9", 3));
        assertEquals("a".repeat(255), PathBoundary.name("a".repeat(255), 255));
        assertThrows(SecurityException.class, () -> PathBoundary.name("a".repeat(300), 255));
        assertThrows(SecurityException.class, () -> PathBoundary.name("a ", 240));
        assertThrows(SecurityException.class, () -> PathBoundary.name("a.", 240));
        assertThrows(SecurityException.class, () -> PathBoundary.name("a..", 240));
        // A leading space is not covered by the name rules and is accepted.
        assertEquals(" a", PathBoundary.name(" a", 240));
    }

    @Test
    public void nameRejectsControlNonNfcAndSurrogateForms() {
        assertThrows(SecurityException.class, () -> PathBoundary.name("a\nb", 240));
        assertThrows(SecurityException.class, () -> PathBoundary.name("a\u0000b", 240));
        assertThrows(SecurityException.class, () -> PathBoundary.name("\u202Eevil", 240));
        assertThrows(SecurityException.class, () -> PathBoundary.name("cafe\u0301.png", 240));
        assertEquals("caf\u00e9.png", PathBoundary.name("caf\u00e9.png", 240));
    }

    @Test
    public void decodeRejectsEncodedDelimitersTraversalAndMalformedInput() {
        assertThrows(SecurityException.class, () -> PathBoundary.decode(null, false));
        for (String raw : List.of("%2F", "a%2Fb", "/a%2fb", "a%5Cb", "a%25b", "%2E%2E", "%2e%2e/x", "%2E")) {
            assertThrows(raw, SecurityException.class, () -> PathBoundary.decode(raw, false));
        }
        assertThrows(SecurityException.class, () -> PathBoundary.decode("a%2F..%2Fb", true));
        assertThrows(SecurityException.class, () -> PathBoundary.decode("a%2F%2E%2Fb", true));
        for (String raw : List.of("%ZZ", "abc%2", "abc%", "%FF", "%00", "a\\b", "a%b", "\u0001")) {
            assertThrows(raw, SecurityException.class, () -> PathBoundary.decode(raw, true));
        }
    }

    @Test
    public void decodeAcceptsLexicalPathsAndEncodedSlashOnlyWhenAllowed() {
        assertEquals("a/b", PathBoundary.decode("a/b", false));
        assertEquals("a/b", PathBoundary.decode("a%2Fb", true));
        assertEquals("a b", PathBoundary.decode("a%20b", false));
        assertEquals("\u00e9", PathBoundary.decode("%C3%A9", false));
        assertEquals("a/", PathBoundary.decode("a/", false));
        assertEquals("a./b", PathBoundary.decode("a%2E%2Fb", true));
        // Empty segments are not traversals, so doubled slashes survive lexically.
        assertEquals("a//b", PathBoundary.decode("a//b", false));
        assertEquals("a//b", PathBoundary.decode("a%2F%2Fb", true));
        // decode() only decodes; absolute-form rejection belongs to the caller.
        assertEquals("/etc/passwd", PathBoundary.decode("/etc/passwd", false));
    }
}
