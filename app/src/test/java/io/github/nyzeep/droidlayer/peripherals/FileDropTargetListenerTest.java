package io.github.nyzeep.droidlayer.peripherals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for FileDropTargetListener:
 * - Drop URI staging into inbound directory
 * - Directory traversal vulnerability prevention (path sanitization)
 * - Notification callback on completion
 * - Non-blocking asynchronous processing
 */
public final class FileDropTargetListenerTest {

    private File tempInboundDir;
    private FakeContentResolver contentResolver;
    private RecordingNotificationListener notificationListener;
    private ExecutorService testExecutor;
    private FileDropTargetListener listener;

    private static final class FakeContentResolver implements FileDropTargetListener.FileContentResolverSeam {
        final Map<String, byte[]> dataMap = new HashMap<>();
        final Map<String, String> nameMap = new HashMap<>();

        void registerFile(String uri, String name, byte[] data) {
            dataMap.put(uri, data);
            nameMap.put(uri, name);
        }

        @Override
        public InputStream openInputStream(String uriString) throws IOException {
            byte[] data = dataMap.get(uriString);
            if (data == null) {
                throw new IOException("URI not found: " + uriString);
            }
            return new ByteArrayInputStream(data);
        }

        @Override
        public String queryDisplayName(String uriString) {
            return nameMap.get(uriString);
        }

        @Override
        public long querySize(String uriString) {
            byte[] data = dataMap.get(uriString);
            return data != null ? data.length : 0L;
        }
    }

    private static final class RecordingNotificationListener implements FileDropTargetListener.StagingNotificationListener {
        final List<StagedResult> results = Collections.synchronizedList(new ArrayList<>());

        static final class StagedResult {
            final String fileName;
            final long bytesWritten;
            final boolean success;
            final String errorMessage;

            StagedResult(String fileName, long bytesWritten, boolean success, String errorMessage) {
                this.fileName = fileName;
                this.bytesWritten = bytesWritten;
                this.success = success;
                this.errorMessage = errorMessage;
            }
        }

        @Override
        public void onFileStaged(String fileName, long bytesWritten, boolean success, String errorMessage) {
            results.add(new StagedResult(fileName, bytesWritten, success, errorMessage));
        }
    }

    @Before
    public void setUp() throws IOException {
        tempInboundDir = Files.createTempDirectory("droidlayer_inbound_test").toFile();
        contentResolver = new FakeContentResolver();
        notificationListener = new RecordingNotificationListener();
        testExecutor = Executors.newSingleThreadExecutor();

        listener = new FileDropTargetListener(
                tempInboundDir,
                contentResolver,
                testExecutor,
                notificationListener
        );
    }

    @After
    public void tearDown() {
        testExecutor.shutdown();
        if (tempInboundDir != null && tempInboundDir.exists()) {
            File[] files = tempInboundDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            tempInboundDir.delete();
        }
    }

    @Test
    public void testSuccessfulFileStaging() throws Exception {
        String testContent = "Orchestella Studio 2026 Linux Payload";
        byte[] bytes = testContent.getBytes(StandardCharsets.UTF_8);
        String uri = "content://com.android.providers.downloads.documents/document/123";
        contentResolver.registerFile(uri, "project-notes.txt", bytes);

        listener.processUrisDirect(Collections.singletonList(uri));

        // Wait up to 3 seconds for asynchronous staging to complete
        long deadline = System.currentTimeMillis() + 3000L;
        while (notificationListener.results.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }

        assertEquals(1, notificationListener.results.size());
        RecordingNotificationListener.StagedResult result = notificationListener.results.get(0);
        assertTrue(result.success);
        assertEquals("project-notes.txt", result.fileName);
        assertEquals(bytes.length, result.bytesWritten);

        File targetFile = new File(tempInboundDir, "project-notes.txt");
        assertTrue(targetFile.exists());
        byte[] readBack = Files.readAllBytes(targetFile.toPath());
        assertEquals(testContent, new String(readBack, StandardCharsets.UTF_8));
    }

    @Test
    public void testDirectoryTraversalPrevention() throws Exception {
        byte[] bytes = "malicious payload".getBytes(StandardCharsets.UTF_8);
        String uri = "content://evil.provider/data";
        // Malicious name attempting directory traversal
        contentResolver.registerFile(uri, "../../etc/passwd", bytes);

        listener.processUrisDirect(Collections.singletonList(uri));

        long deadline = System.currentTimeMillis() + 3000L;
        while (notificationListener.results.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }

        assertEquals(1, notificationListener.results.size());
        RecordingNotificationListener.StagedResult result = notificationListener.results.get(0);
        assertTrue(result.success);
        // Traversal path separators must be sanitized
        assertFalse(result.fileName.contains(".."));
        assertFalse(result.fileName.contains("/"));
        assertFalse(result.fileName.contains("\\"));

        // File must reside inside tempInboundDir, never outside
        File targetFile = new File(tempInboundDir, result.fileName);
        assertTrue(targetFile.exists());
        assertEquals(tempInboundDir.getCanonicalPath(), targetFile.getParentFile().getCanonicalPath());
    }

    @Test
    public void testSanitizeFilenameHelper() {
        assertEquals("hello.txt", FileDropTargetListener.sanitizeFilename("hello.txt"));
        assertEquals("_etc_passwd", FileDropTargetListener.sanitizeFilename("../../etc/passwd"));
        assertEquals("report_2026.pdf", FileDropTargetListener.sanitizeFilename("report:2026.pdf"));
        assertTrue(FileDropTargetListener.sanitizeFilename(null).startsWith("dropped_file_"));
        assertTrue(FileDropTargetListener.sanitizeFilename("   ").startsWith("dropped_file_"));
    }
}
