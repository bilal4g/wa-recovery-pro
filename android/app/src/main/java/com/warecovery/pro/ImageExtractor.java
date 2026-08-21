package com.warecovery.pro;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Robust WhatsApp Image & Video Extractor.
 * Actively retrieves full-resolution photos, view-once media, and videos written to WhatsApp storage
 * even when the notification payload does not attach the raw bitmap.
 */
public class ImageExtractor {

    private static final String TAG = "WARecovery_ImageExtractor";

    private static final String[] MEDIA_PATHS = {
            "/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            "/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Private",
            "/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video",
            "/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/Private",
            "/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
            "/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images",
            "/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images/Private",
            "/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Video",
            "/WhatsApp/Media/WhatsApp Images",
            "/WhatsApp/Media/WhatsApp Images/Private",
            "/WhatsApp/Media/WhatsApp Video",
            "/WhatsApp Business/Media/WhatsApp Business Images",
            "/WhatsApp Business/Media/WhatsApp Business Video"
    };

    private final Context context;
    private final DatabaseHelper dbHelper;
    private final String backupDir;
    private static final Set<String> processedFiles = new HashSet<>();

    public ImageExtractor(Context context) {
        this.context = context;
        this.dbHelper = DatabaseHelper.getInstance(context);

        File backupFile = new File(context.getFilesDir(), "media_backup");
        if (!backupFile.exists()) backupFile.mkdirs();
        this.backupDir = backupFile.getAbsolutePath();
    }

    /**
     * Finds the newest image/video file written by WhatsApp (within the last 5 minutes),
     * backs it up to private storage & Public Gallery, and links it to the contact.
     */
    public synchronized String extractLatestImageForContact(String contact) {
        List<File> allMediaFiles = new ArrayList<>();

        for (String path : MEDIA_PATHS) {
            String fullPath = Environment.getExternalStorageDirectory() + path;
            File dir = new File(fullPath);
            if (dir.exists() && dir.isDirectory()) {
                collectMediaFiles(dir, allMediaFiles);
            }
        }

        if (allMediaFiles.isEmpty()) {
            Log.d(TAG, "No recent media files found in WhatsApp directories");
            return null;
        }

        // Sort descending by lastModified (newest first)
        Collections.sort(allMediaFiles, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

        long now = System.currentTimeMillis();

        for (File newest : allMediaFiles) {
            String absPath = newest.getAbsolutePath();
            if (processedFiles.contains(absPath)) continue;

            // Media created within the last 5 minutes (300,000 ms)
            if ((now - newest.lastModified()) < 300000 && newest.length() > 0) {
                processedFiles.add(absPath);
                String backupPath = backupMediaFile(newest);
                if (backupPath != null) {
                    String mediaType = newest.getName().toLowerCase().endsWith(".mp4") ? "video" : "image";
                    String mimeType = mediaType.equals("video") ? "video/mp4" : "image/jpeg";

                    // 1. Insert into Media table
                    dbHelper.insertMedia(
                            contact,
                            mediaType,
                            backupPath,
                            newest.getName(),
                            newest.length(),
                            mimeType,
                            null,
                            newest.lastModified(),
                            false
                    );

                    // 2. Link directly to latest chat message
                    dbHelper.updateLatestMessagePhoto(contact, backupPath, null);

                    // 3. Save copy to Phone Gallery Pictures/WARecovery
                    saveToPhoneGallery(newest);

                    Log.i(TAG, "✅ [ImageExtractor] Successfully captured: " + newest.getName() + " for " + contact);

                    RecoveryBridge bridge = RecoveryBridge.getInstance();
                    if (bridge != null) {
                        bridge.onNativeEvent("mediaRecovered", contact != null ? contact : "photo");
                        bridge.onNativeEvent("newMessage", contact != null ? contact : "WhatsApp");
                    }

                    return backupPath;
                }
            }
        }

        return null;
    }

    private void collectMediaFiles(File dir, List<File> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectMediaFiles(f, result);
            } else if (f.isFile() && isMediaFile(f)) {
                result.add(f);
            }
        }
    }

    private boolean isMediaFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
               name.endsWith(".webp") || name.endsWith(".mp4") || name.endsWith(".gif");
    }

    private String backupMediaFile(File source) {
        try {
            File dest = new File(backupDir, System.currentTimeMillis() + "_" + source.getName());
            copyFile(source, dest);
            return dest.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Failed to backup media file: " + source.getName(), e);
            return null;
        }
    }

    private void saveToPhoneGallery(File source) {
        try {
            File pubDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "WARecovery");
            if (!pubDir.exists()) pubDir.mkdirs();
            File pubFile = new File(pubDir, source.getName());
            copyFile(source, pubFile);
            MediaScannerConnection.scanFile(context, new String[]{pubFile.getAbsolutePath()}, null, null);
        } catch (Exception ignored) {}
    }

    private void copyFile(File source, File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest);
             FileChannel inChannel = fis.getChannel();
             FileChannel outChannel = fos.getChannel()) {
            inChannel.transferTo(0, inChannel.size(), outChannel);
        }
    }
}
