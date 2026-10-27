package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Contract tests for the lexical {@link StoragePaths} helpers shared by storage and tar code. */
public final class StoragePathsTest {

    @Test
    public void isSafeRelativeRejectsEmptyAbsoluteAndTrailingSlash() {
        for (String path : List.of("", "/", "/a", "a/", "a/b/", "//a", "//")) {
            assertFalse(path, StoragePaths.isSafeRelative(path));
        }
        assertFalse(StoragePaths.isSafeRelative(null));
    }

    @Test
    public void isSafeRelativeRejectsDotComponentsAndEmptySegments() {
        for (String path : List.of(".", "..", "./a", "../a", "a/.", "a/..", "a/./b", "a/../b", "a//b", "a//")) {
            assertFalse(path, StoragePaths.isSafeRelative(path));
        }
    }

    @Test
    public void isSafeRelativeAcceptsPlainNestedSegments() {
        for (String path : List.of("a", "a/b", "a/b/c", "a.b/c-d_e", "a b/c", "a/...", " a", "...")) {
            assertTrue(path, StoragePaths.isSafeRelative(path));
        }
    }

    @Test
    public void isSafeRelativeIsLexicalOnlySoBackslashAndDriveFormsPass() {
        // The check is purely syntactic: containment is enforced by
        // StorageLayout.resolve via normalize() plus startsWith(root).
        for (String path : Arrays.asList("a\\b", "C:\\escape", "..\\escape", "a\\..\\..\\escape")) {
            assertTrue(path, StoragePaths.isSafeRelative(path));
        }
    }

    @Test
    public void isSafeRelativeRejectsNulAndOverlongPathsAtFourThousandNinetySix() {
        assertFalse(StoragePaths.isSafeRelative("a\u0000b"));
        assertTrue(StoragePaths.isSafeRelative("a".repeat(4096)));
        assertFalse(StoragePaths.isSafeRelative("a".repeat(4097)));
    }

    @Test
    public void componentsSplitsOnEverySlashIncludingEmptySegments() {
        assertEquals(List.of("a", "b"), StoragePaths.components("a/b"));
        assertEquals(List.of("a", "b", "c"), StoragePaths.components("a/b/c"));
        assertEquals(List.of("a"), StoragePaths.components("a"));
        assertEquals(List.of(""), StoragePaths.components(""));
        assertEquals(List.of("a", ""), StoragePaths.components("a/"));
        assertEquals(List.of("", "a"), StoragePaths.components("/a"));
        assertEquals(List.of("a", "", "b"), StoragePaths.components("a//b"));
    }

    @Test
    public void baseNameReturnsTextAfterTheLastSlash() {
        assertEquals("c", StoragePaths.baseName("a/b/c"));
        assertEquals("a", StoragePaths.baseName("a"));
        assertEquals("", StoragePaths.baseName(""));
        assertEquals("", StoragePaths.baseName("a/"));
        assertEquals("", StoragePaths.baseName("/"));
        assertEquals("b", StoragePaths.baseName("a//b"));
        assertEquals("x", StoragePaths.baseName("C" + ":/x"));
        // Only '/' is a separator here; backslash is part of the name.
        assertEquals("a\\b", StoragePaths.baseName("a\\b"));
    }
}
