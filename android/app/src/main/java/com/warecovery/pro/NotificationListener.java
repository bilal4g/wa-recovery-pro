package com.warecovery.pro;

import android.app.Notification;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Notification Listener Service that captures all WhatsApp notifications.
 * This is the core of the message recovery system.
 * 
 * When WhatsApp sends a notification (new message), we capture it and store it.
 * When a notification is removed (message deleted by sender), we mark it as deleted.
 */
public class NotificationListener extends NotificationListenerService {

    private static final String TAG = "WARecovery_NLS";
    private static final String WHATSAPP_PACKAGE = "com.whatsapp";
    private static final String WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b";

    private DatabaseHelper dbHelper;
    private static NotificationListener instance;

    public static NotificationListener getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        dbHelper = DatabaseHelper.getInstance(this);
        Log.i(TAG, "NotificationListenerService created");

        // Start watching WhatsApp media directories for view-once captures
        try {
            ViewOnceWatcher.getInstance(this).startWatching();
            Log.i(TAG, "ViewOnceWatcher started");
        } catch (Exception e) {
            Log.w(TAG, "Could not start ViewOnceWatcher", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Log.i(TAG, "NotificationListenerService destroyed");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Only process WhatsApp notifications
        if (!isWhatsAppNotification(sbn)) return;

        try {
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;

            if (extras == null) return;

            // Extract message data
            String contact = extras.getString(Notification.EXTRA_TITLE, "Unknown");
            CharSequence textCs = extras.getCharSequence(Notification.EXTRA_TEXT);
            String text = textCs != null ? textCs.toString() : "";
            long timestamp = sbn.getPostTime();
            String key = sbn.getKey();

            // Skip summary/group notifications
            if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

            // Detect message type
            String type = detectMessageType(extras, text);

            // Check for group message
            String groupName = null;
            CharSequence subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
            if (subText != null) {
                groupName = subText.toString();
            }

            // Check for view-once (WhatsApp marks these differently in notification)
            boolean isViewOnce = isViewOnceMessage(extras, text);

            // Extract thumbnail/photo if available from all Android notification channels
            Bitmap picture = extractBitmap(notification, extras);
            String thumbnailBase64 = null;
            if (picture != null) {
                thumbnailBase64 = bitmapToBase64(picture);
            }

            // If it's a photo or view-once with image data, also register in Media gallery
            if (thumbnailBase64 != null && ("image".equals(type) || isViewOnce)) {
                dbHelper.insertMedia(
                        contact,
                        "image",
                        thumbnailBase64,
                        "wa_photo_" + timestamp + ".jpg",
                        0,
                        "image/jpeg",
                        thumbnailBase64,
                        timestamp,
                        false
                );
            }

            // Store in database
            dbHelper.insertMessage(
                    contact,
                    text,
                    type,
                    timestamp,
                    "received",
                    groupName,
                    false,  // not deleted yet
                    isViewOnce,
                    thumbnailBase64,   // mediaUrl
                    thumbnailBase64,
                    key     // notification key for tracking deletions
            );

            Log.d(TAG, "Captured message from " + contact + ": " + text.substring(0, Math.min(30, text.length())));

            // Notify the web layer (if bridge is registered)
            notifyWebLayer("newMessage", contact);

        } catch (Exception e) {
            Log.e(TAG, "Error processing notification", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        // Only process WhatsApp notifications
        if (!isWhatsAppNotification(sbn)) return;

        // reason == REASON_APP_CANCEL typically means the sender deleted the message
        // reason == REASON_CANCEL means user dismissed it
        if (reason == REASON_APP_CANCEL) {
            String key = sbn.getKey();
            dbHelper.markMessageDeleted(key);

            String contact = "Unknown";
            Bundle extras = sbn.getNotification().extras;
            if (extras != null) {
                contact = extras.getString(Notification.EXTRA_TITLE, "Unknown");
            }

            Log.d(TAG, "Message deleted by sender — recovered! Contact: " + contact);
            notifyWebLayer("messageDeleted", contact);
        }
    }

    // ---- Helpers ----

    private boolean isWhatsAppNotification(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        return WHATSAPP_PACKAGE.equals(pkg) || WHATSAPP_BUSINESS_PACKAGE.equals(pkg);
    }

    private String detectMessageType(Bundle extras, String text) {
        if (text == null) return "text";

        // WhatsApp notification text patterns
        String lower = text.toLowerCase();
        if (lower.contains("photo") || lower.contains("image") || lower.equals("📷 photo")) return "image";
        if (lower.contains("video") || lower.equals("📹 video")) return "video";
        if (lower.contains("voice message") || lower.contains("🎤")) return "voice";
        if (lower.contains("document") || lower.contains("📄")) return "document";
        if (lower.contains("sticker")) return "sticker";
        if (lower.contains("gif") || lower.equals("gif")) return "gif";
        if (lower.contains("audio") || lower.equals("🎵 audio")) return "audio";
        if (lower.contains("contact card")) return "contact";
        if (lower.contains("location") || lower.contains("📍")) return "location";

        // Check for media in extras
        if (extras.containsKey(Notification.EXTRA_PICTURE)) return "image";

        return "text";
    }

    private boolean isViewOnceMessage(Bundle extras, String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        // WhatsApp view-once messages show as "Photo" or "Video" with a specific icon
        // and the notification typically just says "photo" or "video" without preview
        return lower.equals("photo") || lower.equals("video") ||
               lower.contains("view once") || lower.contains("📷") && lower.length() < 10;
    }

    private Bitmap extractBitmap(Notification notification, Bundle extras) {
        try {
            if (extras != null) {
                Bitmap pic = extras.getParcelable(Notification.EXTRA_PICTURE);
                if (pic != null) return pic;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Object picIcon = extras.get(Notification.EXTRA_PICTURE_ICON);
                    if (picIcon instanceof android.graphics.drawable.Icon) {
                        android.graphics.drawable.Drawable drawable = ((android.graphics.drawable.Icon) picIcon).loadDrawable(this);
                        if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
                            return ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
                        }
                    }
                }

                Bitmap large = extras.getParcelable(Notification.EXTRA_LARGE_ICON);
                if (large != null) return large;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notification.getLargeIcon() != null) {
                android.graphics.drawable.Drawable drawable = notification.getLargeIcon().loadDrawable(this);
                if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
                    return ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting bitmap", e);
        }
        return null;
    }

    private String bitmapToBase64(Bitmap bitmap) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
            byte[] bytes = baos.toByteArray();
            return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Error converting bitmap to base64", e);
            return null;
        }
    }

    private void notifyWebLayer(String event, String data) {
        // This will be connected to the Capacitor bridge
        try {
            RecoveryBridge bridge = RecoveryBridge.getInstance();
            if (bridge != null) {
                bridge.onNativeEvent(event, data);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not notify web layer", e);
        }
    }
}
