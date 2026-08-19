package com.warecovery.pro;

import android.os.Environment;
import android.os.FileObserver;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Media Scanner that monitors WhatsApp's media directories for new and deleted files.
 * Automatically backs up media files to the app's private storage before they can be deleted.
 */
public class MediaScanner {

    private static final String TAG = "WARecovery_Media";

    // WhatsApp media paths (Android 11+ scoped storage path)
    private static final String[] WHATSAPP_MEDIA_PATHS = {
            "/Android/media/com.whatsapp/WhatsApp/Media",
            "/WhatsApp/Media",  // Legacy path
            "/Android/media/com.whatsapp.w4b/WhatsApp Business/Media"  // WhatsApp Business
    };

    private static final String[] MEDIA_SUBDIRS = {
            "WhatsApp Images",
            "WhatsApp Video",
            "WhatsApp Documents",
            "WhatsApp Stickers",
            "WhatsApp Animated Gifs",
            "WhatsApp Voice Notes",
            "WhatsApp Audio"
    };

    private final android.content.Context context;
    private final DatabaseHelper dbHelper;
    private final Map<String, FileObserver> observers;
    private String backupDir;
    private boolean isRunning = false;

    public MediaScanner(android.content.Context context) {
        this.context = context;
        this.dbHelper = DatabaseHelper.getInstance(context);
        this.observers = new HashMap<>();

        // Create backup directory in app's private storage
        File backupFile = new File(context.getFilesDir(), "media_backup");
        if (!backupFile.exists()) backupFile.mkdirs();
        this.backupDir = backupFile.getAbsolutePath();
    }

    /**
     * Start monitoring all WhatsApp media directories.
     */
    public void startScanning() {
        if (isRunning) return;
        isRunning = true;

        // Initial scan of existing files
        performFullScan();

        // Set up FileObservers for real-time monitoring
        for (String basePath : WHATSAPP_MEDIA_PATHS) {
            String fullPath = Environment.getExternalStorageDirectory() + basePath;
            File dir = new File(fullPath);

            if (dir.exists() && dir.isDirectory()) {
                for (String subdir : MEDIA_SUBDIRS) {
                    File mediaDir = new File(dir, subdir);
                    if (mediaDir.exists()) {
                        watchDirectory(mediaDir);
                    }
                }
                // Also watch the base media dir for new subdirectories
                watchDirectory(dir);
                Log.i(TAG, "Watching WhatsApp media at: " + fullPath);
            }
        }

        Log.i(TAG, "Media scanner started with " + observers.size() + " watchers");
    }

    /**
     * Stop all file watchers.
     */
    public void stopScanning() {
        isRunning = false;
        for (FileObserver observer : observers.values()) {
            observer.stopWatching();
        }
        observers.clear();
        Log.i(TAG, "Media scanner stopped");
    }

    /**
     * Perform a full scan of all WhatsApp media directories.
     */
    public void performFullScan() {
        int count = 0;
        for (String basePath : WHATSAPP_MEDIA_PATHS) {
            String fullPath = Environment.getExternalStorageDirectory() + basePath;
            File dir = new File(fullPath);

            if (dir.exists()) {
                count += scanDirectory(dir);
            }
        }
        Log.i(TAG, "Full scan complete: " + count + " media files found");
    }

    private int scanDirectory(File dir) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;

        for (File file : files) {
            if (file.isDirectory()) {
                count += scanDirectory(file);
            } else if (isMediaFile(file)) {
                processNewFile(file);
                count++;
            }
        }
        return count;
    }

    private void watchDirectory(File dir) {
        String path = dir.getAbsolutePath();
        if (observers.containsKey(path)) return;

        FileObserver observer = new FileObserver(path,
                FileObserver.CREATE | FileObserver.DELETE |
                FileObserver.MOVED_FROM | FileObserver.MOVED_TO |
                FileObserver.CLOSE_WRITE) {

            @Override
            public void onEvent(int event, String fileName) {
                if (fileName == null) return;

                File file = new File(dir, fileName);

                switch (event & FileObserver.ALL_EVENTS) {
                    case FileObserver.CREATE:
                    case FileObserver.CLOSE_WRITE:
                    case FileObserver.MOVED_TO:
                        if (isMediaFile(file)) {
                            processNewFile(file);
                        }
                        break;

                    case FileObserver.DELETE:
                    case FileObserver.MOVED_FROM:
                        Log.d(TAG, "File deleted/moved: " + fileName);
                        // The file is already backed up, so we just log it
                        break;
                }
            }
        };

        observer.startWatching();
        observers.put(path, observer);
    }

    private void processNewFile(File file) {
        try {
            String mediaType = getMediaType(file);
            String mimeType = getMimeType(file);

            // Back up the file
            String backupPath = backupFile(file);
            if (backupPath == null) return;

            // Store metadata in database
            dbHelper.insertMedia(
                    null,  // contact unknown at this point
                    mediaType,
                    backupPath,
                    file.getName(),
                    file.length(),
                    mimeType,
                    null,  // thumbnail
                    file.lastModified(),
                    false
            );

            Log.d(TAG, "Processed media: " + file.getName() + " (" + mediaType + ")");

        } catch (Exception e) {
            Log.e(TAG, "Error processing file: " + file.getName(), e);
        }
    }

    private String backupFile(File source) {
        try {
            String type = getMediaType(source);
            File typeDir = new File(backupDir, type);
            if (!typeDir.exists()) typeDir.mkdirs();

            File dest = new File(typeDir, System.currentTimeMillis() + "_" + source.getName());
            
            copyFile(source, dest);
            return dest.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Failed to backup file: " + source.getName(), e);
            return null;
        }
    }

    private void copyFile(File source, File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest);
             FileChannel inChannel = fis.getChannel();
             FileChannel outChannel = fos.getChannel()) {
            inChannel.transferTo(0, inChannel.size(), outChannel);
        }
    }

    // ---- File Type Helpers ----

    private boolean isMediaFile(File file) {
        if (!file.isFile()) return false;
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
               name.endsWith(".gif") || name.endsWith(".webp") ||
               name.endsWith(".mp4") || name.endsWith(".3gp") || name.endsWith(".mkv") ||
               name.endsWith(".opus") || name.endsWith(".m4a") || name.endsWith(".mp3") ||
               name.endsWith(".aac") || name.endsWith(".ogg") ||
               name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx") ||
               name.endsWith(".xls") || name.endsWith(".xlsx") ||
               name.endsWith(".ppt") || name.endsWith(".pptx") ||
               name.endsWith(".apk") || name.endsWith(".zip");
    }

    private String getMediaType(File file) {
        String name = file.getName().toLowerCase();
        String parent = file.getParent() != null ? file.getParent().toLowerCase() : "";

        if (parent.contains("voice notes") || name.endsWith(".opus")) return "voice";
        if (parent.contains("stickers")) return "sticker";
        if (parent.contains("animated gifs") || name.endsWith(".gif")) return "gif";
        if (name.endsWith(".mp4") || name.endsWith(".3gp") || name.endsWith(".mkv")) return "video";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")) return "image";
        if (name.endsWith(".m4a") || name.endsWith(".mp3") || name.endsWith(".aac") || name.endsWith(".ogg")) return "audio";
        return "document";
    }

    private String getMimeType(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".3gp")) return "video/3gpp";
        if (name.endsWith(".opus")) return "audio/opus";
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    /**
     * Get the list of backed up media files organized by type.
     */
    public Map<String, List<File>> getBackedUpMedia() {
        Map<String, List<File>> result = new HashMap<>();
        File backupRoot = new File(backupDir);

        if (backupRoot.exists()) {
            File[] typeDirs = backupRoot.listFiles();
            if (typeDirs != null) {
                for (File typeDir : typeDirs) {
                    if (typeDir.isDirectory()) {
                        List<File> files = new ArrayList<>();
                        File[] mediaFiles = typeDir.listFiles();
                        if (mediaFiles != null) {
                            for (File f : mediaFiles) {
                                if (f.isFile()) files.add(f);
                            }
                        }
                        result.put(typeDir.getName(), files);
                    }
                }
            }
        }

        return result;
    }
}
