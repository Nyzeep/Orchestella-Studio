package io.github.nyzeep.orchestella.runtime.contract.sharing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SymlinkBoundaryTest {
    @Test public void actualLinkedSourceAncestorAndLinkedCacheAreRejected() throws Exception {
        Path owned = Files.createTempDirectory("phase5-real-links-");
        Path downloads = Files.createDirectory(owned.resolve("Downloads"));
        Path outside = Files.createDirectory(owned.resolve("outside"));
        Files.writeString(outside.resolve("synthetic.txt"), "synthetic outside sentinel");
        Path link = downloads.resolve("linked-directory");
        directoryLink(link, outside);
        assertThrows(SecurityException.class, () -> DownloadsSelection.authorize(true,
                downloads.toUri(), Set.of(link.resolve("synthetic.txt").toUri()),
                (root, source) -> "synthetic-version-boundary"));
        Path linkedCache = owned.resolve("linked-cache");
        directoryLink(linkedCache, outside);
        assertThrows(SecurityException.class, () -> new ReadOnlyStager(linkedCache,
                new ReadOnlyStager.Limits(1, 10, 10, 10, 128)));
        assertEquals("synthetic outside sentinel", Files.readString(outside.resolve("synthetic.txt")));
    }

    private static void directoryLink(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            System.out.println("LINK_PROBE actual-directory-symlink");
        } catch (IOException unavailable) {
            if (!System.getProperty("os.name").startsWith("Windows")) { throw unavailable; }
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Synthetic directory junction probe timed out");
            }
            if (process.exitValue() != 0) { throw new IOException("Synthetic directory junction probe failed"); }
            System.out.println("LINK_PROBE actual-windows-directory-junction; file-symlink-privilege-unavailable");
        }
    }
}
