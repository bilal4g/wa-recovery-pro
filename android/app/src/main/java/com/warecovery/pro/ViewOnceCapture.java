package com.warecovery.pro;

import android.app.Notification;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * View-Once Message Capture handler.
 * Captures thumbnails and previews from WhatsApp view-once messages
 * via notification data before they're opened and disappear.
 */
public class ViewOnceCapture {

    private static final String TAG = "WARecovery_ViewOnce";

    private final android.content.Context context;
    private final DatabaseHelper dbHelper;
    private final String captureDir;

    public ViewOnceCapture(android.content.Context context) {
        this.context = context;
        this.dbHelper = DatabaseHelper.getInstance(context);

        File captureFile = new File(context.getFilesDir(), "viewonce_captures");
        if (!captureFile.exists()) captureFile.mkdirs();
        this.captureDir = captureFile.getAbsolutePath();
    }

    /**
     * Process a potential view-once notification.
     * Called from NotificationListener when a view-once message is detected.
     */
    public void processViewOnceNotification(String contact, Notification notification, long timestamp) {
        try {
            Bundle extras = notification.extras;
            if (extras == null) return;

            // Determine media type
            String type = "image";
            CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
            if (text != null) {
                String lower = text.toString().toLowerCase();
                if (lower.contains("video")) type = "video";
            }

            // Extract thumbnail/picture from notification
            String thumbnailBase64 = null;
            String filePath = null;

            // Try to get the large icon (contact photo or media preview)
            Bitmap picture = extras.getParcelable(Notification.EXTRA_PICTURE);
            if (picture != null) {
                // Save the bitmap
                filePath = saveBitmap(picture, contact, timestamp);
                thumbnailBase64 = bitmapToBase64(picture);
                Log.d(TAG, "Captured view-once picture from notification");
            }

            // Try notification large icon as fallback
            if (thumbnailBase64 == null) {
                Bitmap largeIcon = extras.getParcelable(Notification.EXTRA_LARGE_ICON);
                if (largeIcon != null) {
                    filePath = saveBitmap(largeIcon, contact, timestamp);
                    thumbnailBase64 = bitmapToBase64(largeIcon);
                    Log.d(TAG, "Captured view-once from large icon");
                }
            }

            // Store in database
            dbHelper.insertViewOnce(
                    contact,
                    type,
                    thumbnailBase64,
                    filePath,
                    timestamp
            );

            Log.i(TAG, "View-once message captured from " + contact + " (" + type + ")");

        } catch (Exception e) {
            Log.e(TAG, "Error capturing view-once message", e);
        }
    }

    /**
     * Save a bitmap to the capture directory.
     */
    private String saveBitmap(Bitmap bitmap, String contact, long timestamp) {
        try {
            String filename = "viewonce_" + timestamp + "_" + sanitizeFilename(contact) + ".jpg";
            File file = new File(captureDir, filename);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.flush();
            }

            return file.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save bitmap", e);
            return null;
        }
    }

    private String bitmapToBase64(Bitmap bitmap) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            byte[] bytes = baos.toByteArray();
            return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Get all view-once captures.
     */
    public File[] getCapturedFiles() {
        File dir = new File(captureDir);
        if (dir.exists()) {
            return dir.listFiles();
        }
        return new File[0];
    }

    /**
     * Get storage used by captures.
     */
    public long getStorageUsed() {
        long total = 0;
        File[] files = getCapturedFiles();
        if (files != null) {
            for (File f : files) {
                total += f.length();
            }
        }
        return total;
    }
}
