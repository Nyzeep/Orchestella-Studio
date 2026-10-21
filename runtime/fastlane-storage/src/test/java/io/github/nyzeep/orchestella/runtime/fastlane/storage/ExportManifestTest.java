package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class ExportManifestTest {
    private static final String HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    public void roundTripsAndEscapes() throws Exception {
        final ExportManifest manifest = new ExportManifest();
        manifest.add(new ExportManifest.Entry("home/a.txt", 5, HASH, 1700000000L));
        manifest.add(new ExportManifest.Entry("home/quote\"back\\slash", 0, HASH, 0));
        manifest.add(new ExportManifest.Entry("home/日本語.txt", 12, HASH, 42));
        final String json = manifest.toJson();
        final ExportManifest parsed = ExportManifest.fromJson(json);
        assertEquals(manifest.entries(), parsed.entries());
        assertEquals(manifest.totalBytes(), parsed.totalBytes());
        assertEquals(17L, parsed.totalBytes());
        assertEquals(json, parsed.toJson());
    }

    @Test
    public void rejectsDuplicatePaths() throws Exception {
        final ExportManifest manifest = new ExportManifest();
        manifest.add(new ExportManifest.Entry("home/a", 1, HASH, 0));
        assertThrows(StorageException.class,
                () -> manifest.add(new ExportManifest.Entry("home/a", 1, HASH, 0)));
    }

    @Test
    public void rejectsTotalSizeOverflow() throws Exception {
        final ExportManifest manifest = new ExportManifest();
        manifest.add(new ExportManifest.Entry("home/a", Long.MAX_VALUE, HASH, 0));
        final StorageException failure = assertThrows(StorageException.class,
                () -> manifest.add(new ExportManifest.Entry("home/b", 1, HASH, 0)));
        assertEquals(StorageFailure.LIMIT_EXCEEDED, failure.failure());
    }

    @Test
    public void rejectsMalformedJson() {
        assertThrows(StorageException.class, () -> ExportManifest.fromJson("{}"));
        assertThrows(StorageException.class, () -> ExportManifest.fromJson("not json"));
        assertThrows(StorageException.class, () -> ExportManifest.fromJson(
                "{\"version\":2,\"entries\":[],\"totalBytes\":0}"));
        assertThrows(StorageException.class, () -> ExportManifest.fromJson(
                "{\"version\":1,\"entries\":[],\"totalBytes\":1}"));
    }

    @Test
    public void rejectsUnsafeEntry() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExportManifest.Entry("../a", 1, HASH, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ExportManifest.Entry("/a", 1, HASH, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ExportManifest.Entry("home/a", 1, "ABC", 0));
    }
}
