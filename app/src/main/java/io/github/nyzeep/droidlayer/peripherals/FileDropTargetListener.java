package io.github.nyzeep.droidlayer.peripherals;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.View;
import io.github.nyzeep.droidlayer.runtime.contract.BridgeClient;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SAF drag-and-drop file staging listener.
 *
 * <p>Conforms to ADR-0018 and Ticket 46:
 * <ul>
 *   <li>Implements {@link View.OnDragListener} responding to {@link DragEvent#ACTION_DROP};</li>
 *   <li>Secures cross-app read permissions via {@code Activity.requestDragAndDropPermissions};</li>
 *   <li>Asynchronously reads URI data streams without blocking the main UI thread;</li>
 *   <li>Writes into the staging pool ({@code /storage/emulated/0/Download/Orchestella-Shared/Inbound/});</li>
 *   <li>Applies defensive filename sanitization against directory traversal;</li>
 *   <li>Pure JVM testable via {@link FileContentResolverSeam} and {@link StagingNotificationListener}.</li>
 * </ul>
 */
public class FileDropTargetListener implements View.OnDragListener {

    public static final String DEFAULT_INBOUND_PATH =
            "/storage/emulated/0/Download/Orchestella-Shared/Inbound";

    /** Seam interface abstracting URI content resolving and metadata extraction. */
    public interface FileContentResolverSeam {
        InputStream openInputStream(String uriString) throws IOException;
        String queryDisplayName(String uriString);
        long querySize(String uriString);
    }

    /** Listener notified upon staging completion or failure. */
    @FunctionalInterface
    public interface StagingNotificationListener {
        void onFileStaged(String fileName, long bytesWritten, boolean success, String errorMessage);
    }

    private final File inboundDirectory;
    private final FileContentResolverSeam contentResolver;
    private final ExecutorService executorService;
    private final StagingNotificationListener notificationListener;
    private Activity attachedActivity;

    /**
     * Constructs a listener with explicit seams (pure JVM test friendly).
     */
    public FileDropTargetListener(
            File inboundDirectory,
            FileContentResolverSeam contentResolver,
            ExecutorService executorService,
            StagingNotificationListener notificationListener) {
        this.inboundDirectory = Objects.requireNonNull(inboundDirectory, "inboundDirectory");
        this.contentResolver = Objects.requireNonNull(contentResolver, "contentResolver");
        this.executorService = Objects.requireNonNull(executorService, "executorService");
        this.notificationListener = notificationListener;
    }

    /**
     * Constructs a listener for an Android Activity and BridgeClient.
     */
    public FileDropTargetListener(Activity activity, BridgeClient bridgeClient) {
        this(
                new File(DEFAULT_INBOUND_PATH),
                new AndroidContentResolverSeam(activity.getContentResolver()),
                Executors.newSingleThreadExecutor(),
                (fileName, bytesWritten, success, error) -> {
                    if (bridgeClient != null) {
                        try {
                            if (success) {
                                bridgeClient.notify("文件已接收", "已成功保存至 Inbound: " + fileName);
                            } else {
                                bridgeClient.notify("文件接收失败", "保存 " + fileName + " 出错: " + error);
                            }
                        } catch (IOException ignored) {}
                    }
                }
        );
        this.attachedActivity = activity;
    }

    /**
     * Attaches this drag listener to a target View.
     */
    public void attach(View view) {
        if (view != null) {
            view.setOnDragListener(this);
        }
    }

    /**
     * Detaches this listener from the View.
     */
    public void detach(View view) {
        if (view != null) {
            view.setOnDragListener(null);
        }
    }

    @Override
    public boolean onDrag(View v, DragEvent event) {
        if (event == null) {
            return false;
        }

        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
            case DragEvent.ACTION_DRAG_ENTERED:
            case DragEvent.ACTION_DRAG_LOCATION:
            case DragEvent.ACTION_DRAG_EXITED:
            case DragEvent.ACTION_DRAG_ENDED:
                return true;

            case DragEvent.ACTION_DROP:
                handleDropEvent(event);
                return true;

            default:
                return false;
        }
    }

    private void handleDropEvent(DragEvent event) {
        // Secure cross-app permissions if running inside an Activity
        DragAndDropPermissions permissions = null;
        if (attachedActivity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                permissions = attachedActivity.requestDragAndDropPermissions(event);
            } catch (Throwable ignored) {}
        }

        ClipData clipData = event.getClipData();
        if (clipData == null || clipData.getItemCount() == 0) {
            return;
        }

        List<String> uris = new ArrayList<>();
        for (int i = 0; i < clipData.getItemCount(); i++) {
            ClipData.Item item = clipData.getItemAt(i);
            Uri uri = item.getUri();
            if (uri != null) {
                uris.add(uri.toString());
            }
        }

        final DragAndDropPermissions finalPermissions = permissions;
        executorService.execute(() -> {
            try {
                stageUris(uris);
            } finally {
                if (finalPermissions != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        finalPermissions.release();
                    } catch (Throwable ignored) {}
                }
            }
        });
    }

    /**
     * Directly processes a list of URI strings in the executor (public for JVM testing).
     */
    public void processUrisDirect(List<String> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        executorService.execute(() -> stageUris(uris));
    }

    private void stageUris(List<String> uris) {
        if (!inboundDirectory.exists()) {
            inboundDirectory.mkdirs();
        }

        for (String uriString : uris) {
            String rawName = contentResolver.queryDisplayName(uriString);
            String safeName = sanitizeFilename(rawName);
            File targetFile = new File(inboundDirectory, safeName);
            File tempFile = new File(inboundDirectory, safeName + ".tmp." + System.currentTimeMillis());

            long bytesWritten = 0;
            boolean success = false;
            String errorMessage = null;

            try {
                try (InputStream in = contentResolver.openInputStream(uriString);
                     OutputStream out = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        bytesWritten += read;
                    }
                    out.flush();
                }

                // Atomic rename to final filename after stream is fully closed
                if (targetFile.exists()) {
                    targetFile.delete();
                }
                if (tempFile.renameTo(targetFile)) {
                    success = true;
                } else {
                    java.nio.file.Files.move(
                            tempFile.toPath(),
                            targetFile.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    success = true;
                }
            } catch (Exception e) {
                errorMessage = e.getMessage();
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }

            if (notificationListener != null) {
                notificationListener.onFileStaged(safeName, bytesWritten, success, errorMessage);
            }
        }
    }

    /**
     * Sanitizes filename to prevent directory traversal and invalid path characters.
     */
    public static String sanitizeFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return "dropped_file_" + System.currentTimeMillis();
        }
        String clean = filename.replace("..", "_");
        clean = clean.replaceAll("[\\\\/:*?\"<>|]", "_");
        clean = clean.replaceAll("^_+", "_");
        clean = clean.trim();
        if (clean.isEmpty() || clean.equals("_")) {
            return "dropped_file_" + System.currentTimeMillis();
        }
        return clean;
    }

    public File getInboundDirectory() {
        return inboundDirectory;
    }

    /**
     * Android framework ContentResolver seam implementation.
     */
    public static final class AndroidContentResolverSeam implements FileContentResolverSeam {
        private final ContentResolver contentResolver;

        public AndroidContentResolverSeam(ContentResolver contentResolver) {
            this.contentResolver = Objects.requireNonNull(contentResolver, "contentResolver");
        }

        @Override
        public InputStream openInputStream(String uriString) throws IOException {
            Uri uri = Uri.parse(uriString);
            InputStream in = contentResolver.openInputStream(uri);
            if (in == null) {
                throw new IOException("ContentResolver returned null stream for " + uriString);
            }
            return in;
        }

        @Override
        public String queryDisplayName(String uriString) {
            Uri uri = Uri.parse(uriString);
            try (Cursor cursor = contentResolver.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        return cursor.getString(nameIndex);
                    }
                }
            } catch (Exception ignored) {}
            return uri.getLastPathSegment();
        }

        @Override
        public long querySize(String uriString) {
            Uri uri = Uri.parse(uriString);
            try (Cursor cursor = contentResolver.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (sizeIndex != -1) {
                        return cursor.getLong(sizeIndex);
                    }
                }
            } catch (Exception ignored) {}
            return -1L;
        }
    }
}
