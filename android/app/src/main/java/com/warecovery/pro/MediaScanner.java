package com.warecovery.pro;

import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Advanced Real-Time Media Scanner that recursively watches all WhatsApp directories.
 * Intercepts voice notes, photos, videos, and documents instantly upon creation (0ms latency),
 * saving persistent copies before any sender deletion or ephemeral timer expires.
 */
public class MediaScanner {

    private static final String TAG = "WARecovery_Media";

    private static final String[] WHATSAPP_MEDIA_PATHS = {
            "/Android/media/com.whatsapp/WhatsApp/Media",
            "/Android/media/com.whatsapp.w4b/WhatsApp Business/Media",
            "/WhatsApp/Media",
            "/WhatsApp Business/Media"
    };

    private final DatabaseHelper dbHelper;
    private final Map<String, FileObserver> observers;
    private final Set<String> processedFiles;
    private final String backupDir;
    private ScheduledExecutorService periodicScanner;
    private boolean isRunning = false;

    public MediaScanner(android.content.Context context) {
        this.dbHelper = DatabaseHelper.getInstance(context);
        this.observers = new ConcurrentHashMap<>();
        this.processedFiles = Collections_synchronizedSet();

        File backupFile = new File(context.getFilesDir(), "media_backup");
        if (!backupFile.exists()) backupFile.mkdirs();
        this.backupDir = backupFile.getAbsolutePath();
    }

    private static Set<String> Collections_synchronizedSet() {
        return java.util.Collections.synchronizedSet(new HashSet<>());
    }

    /**
     * Start real-time monitoring and recursive background watcher.
     */
    public void startScanning() {
        if (isRunning) return;
        isRunning = true;

        // 1. Initial scan
        performFullScan();

        // 2. Set up recursive FileObservers
        setupAllObservers();

        // 3. Periodic fast sync heartbeat (every 3 seconds) to ensure 100% capture rate
        periodicScanner = Executors.newSingleThreadScheduledExecutor();
        periodicScanner.scheduleWithFixedDelay(this::fastIncrementalScan, 2, 3, TimeUnit.SECONDS);

        Log.i(TAG, "MediaScanner active with recursive observers and 3s heartbeat sync");
    }

    /**
     * Stop all watchers and background executors.
     */
    public void stopScanning() {
        isRunning = false;
        if (periodicScanner != null) {
            periodicScanner.shutdownNow();
            periodicScanner = null;
        }
        for (FileObserver observer : observers.values()) {
            try { observer.stopWatching(); } catch (Exception ignored) {}
        }
        observers.clear();
        Log.i(TAG, "MediaScanner stopped");
    }

    private void setupAllObservers() {
        for (String basePath : WHATSAPP_MEDIA_PATHS) {
            String fullPath = Environment.getExternalStorageDirectory() + basePath;
            File dir = new File(fullPath);
            if (dir.exists() && dir.isDirectory()) {
                watchDirectoryRecursive(dir);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void watchDirectoryRecursive(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;

        String path = dir.getAbsolutePath();
        if (!observers.containsKey(path)) {
            try {
                FileObserver observer = new FileObserver(path,
                        FileObserver.CREATE | FileObserver.CLOSE_WRITE |
                        FileObserver.MOVED_TO) {
                    @Override
                    public void onEvent(int event, String fileName) {
                        if (fileName == null) return;
                        File file = new File(dir, fileName);

                        if (file.isDirectory()) {
                            watchDirectoryRecursive(file);
                        } else if (isMediaFile(file)) {
                            processNewFile(file);
                        }
                    }
                };
                observer.startWatching();
                observers.put(path, observer);
            } catch (Exception ignored) {}
        }

        File[] subdirs = dir.listFiles();
        if (subdirs != null) {
            for (File sub : subdirs) {
                if (sub.isDirectory()) {
                    watchDirectoryRecursive(sub);
                }
            }
        }
    }

    public void performFullScan() {
        for (String basePath : WHATSAPP_MEDIA_PATHS) {
            String fullPath = Environment.getExternalStorageDirectory() + basePath;
            File dir = new File(fullPath);
            if (dir.exists()) {
                scanDirectory(dir, 0);
            }
        }
    }

    private void fastIncrementalScan() {
        long now = System.currentTimeMillis();
        for (String basePath : WHATSAPP_MEDIA_PATHS) {
            String fullPath = Environment.getExternalStorageDirectory() + basePath;
            File dir = new File(fullPath);
            if (dir.exists()) {
                scanDirectory(dir, now - 60000); // Check files from last 60 seconds
            }
        }
    }

    private void scanDirectory(File dir, long minModifiedTime) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                watchDirectoryRecursive(file);
                scanDirectory(file, minModifiedTime);
            } else if (isMediaFile(file) && (minModifiedTime == 0 || file.lastModified() >= minModifiedTime)) {
                processNewFile(file);
            }
        }
    }

    private synchronized void processNewFile(File file) {
        String absPath = file.getAbsolutePath();
        if (processedFiles.contains(absPath) || !file.exists() || file.length() == 0) return;
        processedFiles.add(absPath);

        try {
            String mediaType = getMediaType(file);
            String mimeType = getMimeType(file);

            // Instant persistent backup
            String backupPath = backupFile(file);
            if (backupPath == null) return;

            // If voice audio note, save to Voice table
            if ("voice".equals(mediaType) || "audio".equals(mediaType)) {
                int duration = getAudioDuration(file);
                dbHelper.insertVoiceNote("Voice Note", backupPath, duration, file.lastModified(), false);
                dbHelper.updateLatestMessageVoiceAudio(null, backupPath);
            }

            // Also register in general media table
            dbHelper.insertMedia(
                    null,
                    mediaType,
                    backupPath,
                    file.getName(),
                    file.length(),
                    mimeType,
                    null,
                    file.lastModified(),
                    false
            );

            Log.i(TAG, "✅ [0ms Real-Time Capture] Successfully saved: " + file.getName() + " -> " + backupPath);

        } catch (Exception e) {
            Log.e(TAG, "Error processing new file: " + file.getName(), e);
        }
    }

    private int getAudioDuration(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                return Integer.parseInt(durationStr) / 1000;
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    retriever.close();
                } else {
                    retriever.release();
                }
            } catch (Exception ignored) {}
        }
        return 0;
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
}
