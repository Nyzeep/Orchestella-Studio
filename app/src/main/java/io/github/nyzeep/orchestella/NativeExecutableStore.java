package io.github.nyzeep.orchestella;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resolves an app-packaged native executable to a real, executable file.
 *
 * <p>Payload builds ship uncompressed native libraries
 * ({@code useLegacyPackaging=false}, the AVF requirement for loading the
 * payload straight from the APK), which leaves {@code nativeLibraryDir}
 * empty: nothing there can be handed to {@code ProcessBuilder}. The
 * flag-off build keeps the historical extracted layout, so the direct
 * path still wins when it exists and this store only fills the gap —
 * it extracts the named {@code lib/arm64-v8a} entry from the APK into
 * app-private storage and sets the exec bit. Extraction is idempotent:
 * a cached copy is reused while its size still matches the APK entry.
 */
public final class NativeExecutableStore {

    private static final String APK_LIB_PREFIX = "lib/arm64-v8a/";
    private static final String EXTRACT_DIR = "native-exec";

    private NativeExecutableStore() { }

    /** Returns an executable path for the packaged {@code lib<name>.so}. */
    public static String ensure(Context context, String soName) throws IOException {
        File packaged = new File(context.getApplicationInfo().nativeLibraryDir, soName);
        if (packaged.isFile()) {
            return packaged.getAbsolutePath();
        }
        String entryName = APK_LIB_PREFIX + soName;
        long expectedSize = apkEntrySize(context, entryName);
        if (expectedSize < 0) {
            throw new IOException(soName + " missing from both nativeLibraryDir and the APK");
        }
        File target = extractedFile(context, soName);
        if (!target.isFile() || target.length() != expectedSize) {
            File dir = target.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            File partial = new File(dir, soName + ".partial");
            extract(context, entryName, partial);
            try {
                Os.chmod(partial.getAbsolutePath(), 0700);
            } catch (Exception chmodFailure) {
                partial.delete();
                throw new IOException("cannot chmod extracted " + soName, chmodFailure);
            }
            if (!partial.renameTo(target)) {
                partial.delete();
                throw new IOException("cannot finalize extracted " + soName);
            }
        }
        return target.getAbsolutePath();
    }

    private static File extractedFile(Context context, String soName) {
        return new File(new File(context.getFilesDir(), EXTRACT_DIR), soName);
    }

    private static long apkEntrySize(Context context, String entryName) throws IOException {
        try (ZipFile zip = new ZipFile(context.getApplicationInfo().sourceDir)) {
            ZipEntry entry = zip.getEntry(entryName);
            return entry == null ? -1L : entry.getSize();
        }
    }

    private static void extract(Context context, String entryName, File target)
            throws IOException {
        try (ZipFile zip = new ZipFile(context.getApplicationInfo().sourceDir);
             InputStream in = zip.getInputStream(zip.getEntry(entryName));
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IOException("APK entry vanished: " + entryName);
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
