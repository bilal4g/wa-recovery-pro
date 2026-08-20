package com.warecovery.pro;

import android.app.Notification;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Android NotificationListenerService that captures WhatsApp messages in real-time.
 * Automatically handles:
 * - Real sender deleted messages ("This message was deleted" / "تم حذف هذه الرسالة")
 * - Voice notes (.opus audio extraction and hardware playback pairing)
 * - View-Once photos and normal media
 */
public class NotificationListener extends NotificationListenerService {

    private static final String TAG = "WARecovery_NL";
    private static final String WHATSAPP_PACKAGE = "com.whatsapp";
    private static final String WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b";

    private DatabaseHelper dbHelper;
    private MediaScanner mediaScanner;
    private static NotificationListener instance;

    public static NotificationListener getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        dbHelper = DatabaseHelper.getInstance(this);
        mediaScanner = new MediaScanner(this);
        mediaScanner.startScanning();
        Log.i(TAG, "NotificationListenerService created");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaScanner != null) {
            mediaScanner.stopScanning();
        }
        instance = null;
        Log.i(TAG, "NotificationListenerService destroyed");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!isWhatsAppNotification(sbn)) return;

        // Trigger on-demand media scan immediately (0% battery drain while idle)
        if (mediaScanner != null) {
            mediaScanner.triggerOnDemandScan();
        }

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

            // Skip summary/group headers
            if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            if (contact.equals("WhatsApp") || contact.equals("WhatsApp Business")) return;

            // 1. DELETION EVENT DETECTION
            // WhatsApp updates the notification to "This message was deleted" / "تم حذف هذه الرسالة"
            if (isDeletionNotification(text)) {
                Log.d(TAG, "⚠️ Deletion event detected from " + contact + "!");
                dbHelper.markLatestMessageDeleted(contact);
                notifyWebLayer("messageDeleted", contact);
                return; // Do not insert the deletion string as a normal message
            }

            // Detect message type
            String type = detectMessageType(extras, text);

            // Check for group message
            String groupName = null;
            CharSequence subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
            if (subText != null) {
                groupName = subText.toString();
            }

            // Check for view-once
            boolean isViewOnce = isViewOnceMessage(extras, text);

            // Extract thumbnail / photo
            Bitmap picture = extractBitmap(notification, extras);
            String thumbnailBase64 = null;
            if (picture != null) {
                thumbnailBase64 = bitmapToBase64(picture);
            }

            // If photo or view-once, register in media table
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

            // Store message in database
            dbHelper.insertMessage(
                    contact,
                    text,
                    type,
                    timestamp,
                    "received",
                    groupName,
                    false,
                    isViewOnce,
                    thumbnailBase64,
                    thumbnailBase64,
                    key
            );

            // If voice note, extract incoming .opus audio file immediately
            if ("voice".equals(type) || "audio".equals(type)) {
                final String voiceContact = contact;
                new Thread(() -> {
                    try {
                        VoiceExtractor extractor = new VoiceExtractor(this);
                        // Attempt immediate scan
                        String vPath = extractor.extractLatestVoiceForContact(voiceContact);
                        if (vPath == null) {
                            Thread.sleep(1200); // Allow WhatsApp 1.2s to finish writing file
                            extractor.extractLatestVoiceForContact(voiceContact);
                        }
                        notifyWebLayer("newMessage", voiceContact);
                    } catch (Exception ignored) {}
                }).start();
            }

            Log.d(TAG, "Captured message from " + contact + " [" + type + "]: " + text);
            notifyWebLayer("newMessage", contact);

        } catch (Exception e) {
            Log.e(TAG, "Error processing notification", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        if (!isWhatsAppNotification(sbn)) return;

        if (reason == REASON_APP_CANCEL) {
            String key = sbn.getKey();
            dbHelper.markMessageDeleted(key);

            String contact = "Unknown";
            Bundle extras = sbn.getNotification().extras;
            if (extras != null) {
                contact = extras.getString(Notification.EXTRA_TITLE, "Unknown");
            }

            dbHelper.markLatestMessageDeleted(contact);
            Log.d(TAG, "Notification cancelled by app — marked deleted for: " + contact);
            notifyWebLayer("messageDeleted", contact);
        }
    }

    // ---- Helpers ----

    private boolean isWhatsAppNotification(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        return WHATSAPP_PACKAGE.equals(pkg) || WHATSAPP_BUSINESS_PACKAGE.equals(pkg);
    }

    private boolean isDeletionNotification(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase().trim();
        return lower.contains("this message was deleted") ||
               lower.contains("message was deleted") ||
               lower.contains("deleted this message") ||
               lower.contains("تم حذف هذه الرسالة") ||
               lower.contains("تم مسح هذه الرسالة") ||
               lower.contains("تم إلغاء إرسال") ||
               lower.contains("ce message a été supprimé") ||
               lower.contains("este mensaje fue eliminado") ||
               lower.contains("diese nachricht wurde gelöscht") ||
               lower.contains("bu mesaj silindi");
    }

    private String detectMessageType(Bundle extras, String text) {
        if (text == null) return "text";

        String lower = text.toLowerCase();
        // Multilingual Voice Detection
        if (lower.contains("voice message") || lower.contains("🎤") ||
            lower.contains("رسالة صوتية") || lower.contains("مقطع صوتي") ||
            lower.contains("صوتية") || lower.contains("ptt") ||
            lower.contains("nota de voz") || lower.contains("message vocal")) {
            return "voice";
        }
        // Multilingual Media Detection
        if (lower.contains("photo") || lower.contains("image") || lower.contains("📷") ||
            lower.contains("صورة") || lower.contains("foto")) {
            return "image";
        }
        if (lower.contains("video") || lower.contains("📹") || lower.contains("فيديو")) {
            return "video";
        }
        if (lower.contains("document") || lower.contains("📄") || lower.contains("مستند")) {
            return "document";
        }
        if (lower.contains("sticker") || lower.contains("ملصق")) {
            return "sticker";
        }
        if (lower.contains("gif")) {
            return "gif";
        }
        if (lower.contains("audio") || lower.contains("🎵") || lower.contains("صوت")) {
            return "audio";
        }
        if (lower.contains("location") || lower.contains("📍") || lower.contains("موقع")) {
            return "location";
        }

        if (extras != null && extras.containsKey(Notification.EXTRA_PICTURE)) {
            return "image";
        }

        return "text";
    }

    private boolean isViewOnceMessage(Bundle extras, String text) {
        if (text == null) return false;
        String lower = text.toLowerCase().trim();
        return lower.equals("photo") || lower.equals("video") ||
               lower.equals("📷 photo") || lower.equals("📹 video") ||
               lower.equals("صورة") || lower.equals("فيديو") ||
               lower.contains("view once") || lower.contains("عرض لمرة واحدة");
    }

    @SuppressWarnings("deprecation")
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

                Bitmap largeIcon = extras.getParcelable(Notification.EXTRA_LARGE_ICON);
                if (largeIcon != null) return largeIcon;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not extract bitmap from notification");
        }
        return null;
    }

    private String bitmapToBase64(Bitmap bitmap) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream);
            byte[] byteArray = outputStream.toByteArray();
            return "data:image/jpeg;base64," + Base64.encodeToString(byteArray, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Error converting bitmap to base64", e);
            return null;
        }
    }

    private void notifyWebLayer(String event, String data) {
        RecoveryBridge bridge = RecoveryBridge.getInstance();
        if (bridge != null) {
            bridge.onNativeEvent(event, data);
        }
    }
}
