package com.warecovery.pro;

import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Watches WhatsApp media directories for incoming view-once photos/videos.
 * 
 * When WhatsApp downloads a view-once image or video, it briefly writes the
 * file to the Media folder. This observer detects the file creation, copies
 * it to our private app directory, and stores a reference in the database
 * before WhatsApp auto-deletes it after viewing.
 */
public class ViewOnceWatcher {

    private static final String TAG = "WARecovery_VOW";
    private static ViewOnceWatcher instance;
    private final List<FileObserver> observers = new ArrayList<>();
    private final File backupDir;
    private final DatabaseHelper dbHelper;

    public static ViewOnceWatcher getInstance(android.content.Context context) {
        if (instance == null) {
            instance = new ViewOnceWatcher(context);
        }
        return instance;
    }

    private ViewOnceWatcher(android.content.Context context) {
        dbHelper = DatabaseHelper.getInstance(context);
        // Private app backup directory: /data/data/com.warecovery.pro/files/viewonce/
        backupDir = new File(context.getFilesDir(), "viewonce");
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }
    }

    /**
     * Start watching all known WhatsApp media directories.
     */
    public void startWatching() {
        stopWatching(); // clean up any existing observers

        // WhatsApp stores media in two possible base locations
        String[] basePaths = {
            // Android 11+ scoped storage path
            Environment.getExternalStorageDirectory() + "/Android/media/com.whatsapp/WhatsApp/Media",
            // Legacy path (Android 10 and below)
            Environment.getExternalStorageDirectory() + "/WhatsApp/Media"
        };

        // Sub-folders where view-once media appears
        String[] subFolders = {
            "WhatsApp Images",
            "WhatsApp Video",
            "WhatsApp Voice Notes",
            "WhatsApp Audio",
            ".Statuses"
        };

        for (String base : basePaths) {
            File baseDir = new File(base);
            if (!baseDir.exists()) continue;

            // Watch the base media directory itself
            addObserver(baseDir);

            // Watch each known sub-folder
            for (String sub : subFolders) {
                File subDir = new File(baseDir, sub);
                if (subDir.exists()) {
                    addObserver(subDir);
                }
            }
        }

        Log.i(TAG, "ViewOnceWatcher started with " + observers.size() + " directory observers");
    }

    public void stopWatching() {
        for (FileObserver obs : observers) {
            obs.stopWatching();
        }
        observers.clear();
    }

    private void addObserver(File dir) {
        int events = FileObserver.CREATE | FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO;

        FileObserver observer;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            observer = new FileObserver(dir, events) {
                @Override
                public void onEvent(int event, String path) {
                    if (path != null) handleFileEvent(dir, path, event);
                }
            };
        } else {
            observer = new FileObserver(dir.getAbsolutePath(), events) {
                @Override
                public void onEvent(int event, String path) {
                    if (path != null) handleFileEvent(dir, path, event);
                }
            };
        }

        observer.startWatching();
        observers.add(observer);
    }

    private void handleFileEvent(File dir, String fileName, int event) {
        try {
            // Only process image/video files
            String lower = fileName.toLowerCase();
            if (!isMediaFile(lower)) return;

            File sourceFile = new File(dir, fileName);

            // Wait briefly for the file to finish writing
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            if (!sourceFile.exists() || sourceFile.length() == 0) return;

            // Skip very large files (> 50MB) to avoid storage issues
            if (sourceFile.length() > 50 * 1024 * 1024) return;

            // Create a unique backup filename
            String backupName = System.currentTimeMillis() + "_" + fileName;
            File destFile = new File(backupDir, backupName);

            // Copy the file to our private backup directory
            copyFile(sourceFile, destFile);

            // Determine type
            String type = lower.endsWith(".mp4") || lower.endsWith(".3gp") ? "video" : "image";

            // Store reference in database
            dbHelper.insertViewOnce(
                "View-Once",   // contact unknown from file watcher
                type,
                null,          // thumbnail generated later
                destFile.getAbsolutePath(),
                System.currentTimeMillis()
            );

            Log.i(TAG, "View-once media captured: " + fileName + " (" + sourceFile.length() / 1024 + " KB)");

        } catch (Exception e) {
            Log.e(TAG, "Error handling file event: " + fileName, e);
        }
    }

    private boolean isMediaFile(String name) {
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
               name.endsWith(".mp4") || name.endsWith(".3gp") || name.endsWith(".webp") ||
               name.endsWith(".gif");
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream inStream = new FileInputStream(src);
             FileOutputStream outStream = new FileOutputStream(dst);
             FileChannel inChannel = inStream.getChannel();
             FileChannel outChannel = outStream.getChannel()) {
            inChannel.transferTo(0, inChannel.size(), outChannel);
        }
    }
}
