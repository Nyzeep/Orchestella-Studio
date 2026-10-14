package io.github.nyzeep.orchestella;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resilient stream provider for runtime packaged assets and resources.
 *
 * <p>When executed on an Android device, large assets (like the 52MB Linux kernel Image)
 * compressed with deflate by AAPT can exceed Android native AssetManager's uncompressed buffer
 * allocation limits (UNCOMPRESS_DATA_MAX). Furthermore, ClassLoader resources in Dalvik/ART can
 * have idiosyncratic resolution when reading from APK roots.
 *
 * <p>This provider first inspects the application's source APK archive directly via {@link ZipFile},
 * streaming exact unbuffered bytes without memory limits. If unavailable (such as in pure JVM
 * unit test environments), it falls back to {@link android.content.res.AssetManager#open(String)}
 * or {@link Class#getResourceAsStream(String)}.
 */
public final class PackagedAssetStreams {
    private PackagedAssetStreams() {}

    private static final class AutoClosingZipInputStream extends FilterInputStream {
        private final ZipFile zipFile;

        AutoClosingZipInputStream(InputStream in, ZipFile zipFile) {
            super(in);
            this.zipFile = zipFile;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                zipFile.close();
            }
        }
    }

    public static InputStream openAsset(Context context, String name) throws IOException {
        String sourceDir = getSourceDir(context);
        if (sourceDir != null && new File(sourceDir).isFile()) {
            ZipFile zip = null;
            try {
                zip = new ZipFile(sourceDir);
                ZipEntry entry = zip.getEntry("assets/" + name);
                if (entry == null) {
                    entry = zip.getEntry(name);
                }
                if (entry != null) {
                    return new AutoClosingZipInputStream(zip.getInputStream(entry), zip);
                }
                zip.close();
            } catch (IOException ignored) {
                if (zip != null) {
                    try {
                        zip.close();
                    } catch (IOException e) {
                        ignored.addSuppressed(e);
                    }
                }
            }
        }
        return context.getAssets().open(name);
    }

    public static InputStream openResource(Context context, String name) throws IOException {
        String sourceDir = getSourceDir(context);
        if (sourceDir != null && new File(sourceDir).isFile()) {
            ZipFile zip = null;
            try {
                zip = new ZipFile(sourceDir);
                ZipEntry entry = zip.getEntry(name);
                if (entry == null) {
                    entry = zip.getEntry("assets/" + name);
                }
                if (entry != null) {
                    return new AutoClosingZipInputStream(zip.getInputStream(entry), zip);
                }
                zip.close();
            } catch (IOException ignored) {
                if (zip != null) {
                    try {
                        zip.close();
                    } catch (IOException e) {
                        ignored.addSuppressed(e);
                    }
                }
            }
        }

        InputStream in = PackagedAssetStreams.class.getResourceAsStream("/" + name);
        if (in != null) {
            return in;
        }
        try {
            return context.getAssets().open(name);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String getSourceDir(Context context) {
        if (context == null) return null;
        try {
            ApplicationInfo appInfo = context.getApplicationInfo();
            if (appInfo != null && appInfo.sourceDir != null) {
                return appInfo.sourceDir;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
