package com.warecovery.pro;

import android.app.Notification;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Android NotificationListenerService that captures WhatsApp messages in real-time.
 * Automatically handles:
 * - Real sender deleted messages ("This message was deleted" / "تم حذف هذه الرسالة")
 * - Voice notes (.opus audio extraction and hardware playback pairing)
 * - View-Once photos and normal media
 */
@SuppressWarnings("deprecation")
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

            // 1. FILTER OUT CALL NOTIFICATIONS & SYSTEM NOTIFICATIONS
            if (isCallNotification(notification, contact, text)) {
                Log.d(TAG, "Ignoring WhatsApp call notification: " + text);
                return;
            }

            if (contact.equals("WhatsApp") || contact.equals("WhatsApp Business") ||
                contact.equalsIgnoreCase("Backup in progress") || contact.equalsIgnoreCase("جاري النسخ الاحتياطي")) {
                return;
            }

            // 2. PARSE MULTI-MESSAGE BUNDLES (MESSAGINGSTYLE)
            // When multiple messages arrive, WhatsApp bundles them into 'android.messages'
            android.os.Parcelable[] messagesArray = null;
            if (extras.containsKey("android.messages")) {
                messagesArray = extras.getParcelableArray("android.messages");
            }

            // Check for group message
            String groupName = null;
            CharSequence subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
            if (subText != null) {
                groupName = subText.toString();
            }

            if (messagesArray != null && messagesArray.length > 0) {
                for (android.os.Parcelable p : messagesArray) {
                    if (p instanceof Bundle) {
                        Bundle msgBundle = (Bundle) p;
                        CharSequence bTextCs = msgBundle.getCharSequence("text");
                        if (bTextCs == null) continue;
                        String bText = bTextCs.toString();
                        long bTime = msgBundle.getLong("time", timestamp);
                        String sender = contact;

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && msgBundle.containsKey("sender_person")) {
                            android.app.Person person = msgBundle.getParcelable("sender_person", android.app.Person.class);
                            if (person != null && person.getName() != null) {
                                sender = person.getName().toString();
                            }
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && msgBundle.containsKey("sender_person")) {
                            android.app.Person person = msgBundle.getParcelable("sender_person");
                            if (person != null && person.getName() != null) {
                                sender = person.getName().toString();
                            }
                        } else if (msgBundle.containsKey("sender")) {
                            CharSequence sCs = msgBundle.getCharSequence("sender");
                            if (sCs != null && sCs.length() > 0) {
                                sender = sCs.toString();
                            }
                        }

                        if (isDeletionNotification(bText)) {
                            dbHelper.markLatestMessageDeleted(sender);
                            notifyWebLayer("messageDeleted", sender);
                            continue;
                        }

                        if (isCallNotification(null, sender, bText)) continue;

                        String bType = detectMessageType(extras, bText);
                        dbHelper.insertMessage(
                                contact,
                                bText,
                                bType,
                                bTime,
                                "received",
                                groupName,
                                false,
                                false,
                                null,
                                null,
                                key + "_" + bTime
                        );
                    }
                }
                notifyWebLayer("newMessage", contact);
                return;
            }

            // Skip summary headers if there are no sub-messages
            if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0 && (text.isEmpty() || text.matches("\\d+\\s+new\\s+messages?"))) {
                return;
            }

            // 3. DELETION EVENT DETECTION
            // WhatsApp updates the notification to "This message was deleted" / "تم حذف هذه الرسالة"
            if (isDeletionNotification(text)) {
                Log.d(TAG, "⚠️ Deletion event detected from " + contact + "!");
                dbHelper.markLatestMessageDeleted(contact);
                notifyWebLayer("messageDeleted", contact);
                return;
            }

            // Detect message type
            String type = detectMessageType(extras, text);

            // Check for view-once
            boolean isViewOnce = isViewOnceMessage(extras, text);

            // Extract thumbnail / photo
            Bitmap picture = extractBitmap(notification, extras);
            String thumbnailBase64 = null;
            String savedPhotoPath = null;

            if (picture != null) {
                thumbnailBase64 = bitmapToBase64(picture);
                type = "image";

                try {
                    File dir = new File(getFilesDir(), "media_backup");
                    if (!dir.exists()) dir.mkdirs();
                    File photoFile = new File(dir, "wa_photo_" + timestamp + ".jpg");
                    FileOutputStream fos = new FileOutputStream(photoFile);
                    picture.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                    fos.flush();
                    fos.close();
                    savedPhotoPath = photoFile.getAbsolutePath();

                    // Save copy to Phone Gallery Pictures/WARecovery
                    File pubDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "WARecovery");
                    if (!pubDir.exists()) pubDir.mkdirs();
                    File pubFile = new File(pubDir, "wa_photo_" + timestamp + ".jpg");
                    FileOutputStream pubFos = new FileOutputStream(pubFile);
                    picture.compress(Bitmap.CompressFormat.JPEG, 90, pubFos);
                    pubFos.flush();
                    pubFos.close();
                    android.media.MediaScannerConnection.scanFile(this, new String[]{pubFile.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
                } catch (Exception e) {
                    Log.e(TAG, "Error saving notification photo to file", e);
                }
            }

            // If photo or view-once or thumbnail exists, register in media table
            if (thumbnailBase64 != null || "image".equals(type) || isViewOnce) {
                String mediaUrl = savedPhotoPath != null ? savedPhotoPath : thumbnailBase64;
                dbHelper.insertMedia(
                        contact,
                        "image",
                        mediaUrl,
                        "wa_photo_" + timestamp + ".jpg",
                        savedPhotoPath != null ? new File(savedPhotoPath).length() : 0,
                        "image/jpeg",
                        thumbnailBase64,
                        timestamp,
                        false
                );
                notifyWebLayer("mediaRecovered", contact);
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
                        String vPath = extractor.extractLatestVoiceForContact(voiceContact);
                        if (vPath == null) {
                            Thread.sleep(1200);
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

    /**
     * Identifies WhatsApp call notifications (Incoming, Missed, Ongoing, Ringing).
     */
    private boolean isCallNotification(Notification notification, String title, String text) {
        if (notification != null && Notification.CATEGORY_CALL.equals(notification.category)) {
            return true;
        }
        String combined = ((title != null ? title : "") + " " + (text != null ? text : "")).toLowerCase().trim();
        return combined.contains("calling") ||
               combined.contains("incoming voice call") ||
               combined.contains("incoming video call") ||
               combined.contains("missed voice call") ||
               combined.contains("missed video call") ||
               combined.contains("missed call") ||
               combined.contains("ongoing call") ||
               combined.contains("ringing") ||
               combined.contains("مكالمة واردة") ||
               combined.contains("مكالمة صوتية") ||
               combined.contains("مكالمة فيديو") ||
               combined.contains("مكالمة فائتة") ||
               combined.contains("جاري الاتصال") ||
               combined.contains("رنين") ||
               combined.contains("llamada entrante") ||
               combined.contains("appel entrant");
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        // Do NOT mark messages deleted on notification dismissal.
        // WhatsApp automatically dismisses notifications when the user opens the chat or reads messages.
        // True message deletion is handled in onNotificationPosted with "This message was deleted" / "تم حذف هذه الرسالة".
    }

    // ---- Helpers ----

    private boolean isWhatsAppNotification(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        return WHATSAPP_PACKAGE.equals(pkg) || WHATSAPP_BUSINESS_PACKAGE.equals(pkg);
    }

    private boolean isDeletionNotification(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase().trim().replaceAll("[.!?]+$", "").trim();
        return lower.equals("this message was deleted") ||
               lower.equals("message was deleted") ||
               lower.equals("you deleted this message") ||
               lower.equals("deleted this message") ||
               lower.equals("تم حذف هذه الرسالة") ||
               lower.equals("تم مسح هذه الرسالة") ||
               lower.equals("تم إلغاء إرسال هذه الرسالة") ||
               lower.equals("تم إلغاء إرسال") ||
               lower.equals("ce message a été supprimé") ||
               lower.equals("este mensaje fue eliminado") ||
               lower.equals("diese nachricht wurde gelöscht") ||
               lower.equals("bu mesaj silindi");
    }

    private String detectMessageType(Bundle extras, String text) {
        if (text == null) return "text";

        String lower = text.toLowerCase().trim();

        // 1. If it contains a link (YouTube, Web URL, etc.), it is text/link, NOT a video/media attachment!
        if (lower.contains("http://") || lower.contains("https://") || lower.contains("youtu.be") || lower.contains("youtube.com")) {
            return "text";
        }

        // Multilingual Voice Detection
        if (lower.equals("voice message") || lower.startsWith("🎤") ||
            lower.equals("رسالة صوتية") || lower.equals("مقطع صوتي") ||
            lower.contains("ptt") || lower.equals("nota de voz") || lower.equals("message vocal")) {
            return "voice";
        }
        // Multilingual Media Detection (exact keywords or standard notification prefixes)
        if (lower.equals("photo") || lower.equals("image") || lower.startsWith("📷") ||
            lower.equals("صورة") || lower.equals("foto")) {
            return "image";
        }
        if (lower.equals("video") || lower.startsWith("📹") || lower.equals("فيديو")) {
            return "video";
        }
        if (lower.equals("document") || lower.startsWith("📄") || lower.equals("مستند")) {
            return "document";
        }
        if (lower.equals("sticker") || lower.equals("ملصق")) {
            return "sticker";
        }
        if (lower.equals("gif")) {
            return "gif";
        }
        if (lower.equals("audio") || lower.startsWith("🎵") || lower.equals("صوت")) {
            return "audio";
        }
        if (lower.equals("location") || lower.startsWith("📍") || lower.equals("موقع") || lower.contains("live location")) {
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
               lower.equals("🔊 audio") || lower.equals("🎵 audio") ||
               lower.equals("صورة") || lower.equals("فيديو") ||
               lower.equals("رسالة صوتية") ||
               lower.contains("view once") || lower.contains("عرض لمرة واحدة") ||
               lower.contains("مرة واحدة");
    }

    /**
     * Aggressively extracts the highest-quality bitmap from every possible notification field.
     * Checks: EXTRA_PICTURE, EXTRA_PICTURE_ICON, EXTRA_LARGE_ICON, MessagingStyle images,
     * WearableExtender backgrounds, and all Bundle keys for any hidden Bitmap data.
     */
    private Bitmap extractBitmap(Notification notification, Bundle extras) {
        Bitmap best = null;

        try {
            if (extras == null) return null;

            // 1. EXTRA_PICTURE — BigPictureStyle (highest quality, usually the actual photo)
            Bitmap pic = extras.getParcelable(Notification.EXTRA_PICTURE);
            if (pic != null) {
                best = pickLarger(best, pic);
            }

            // 2. EXTRA_PICTURE_ICON (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    Object picIcon = extras.get(Notification.EXTRA_PICTURE_ICON);
                    if (picIcon instanceof android.graphics.drawable.Icon) {
                        android.graphics.drawable.Drawable drawable =
                                ((android.graphics.drawable.Icon) picIcon).loadDrawable(this);
                        if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
                            best = pickLarger(best,
                                    ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap());
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 3. EXTRA_LARGE_ICON (contact photo or media preview)
            Bitmap largeIcon = extras.getParcelable(Notification.EXTRA_LARGE_ICON);
            if (largeIcon != null) {
                best = pickLarger(best, largeIcon);
            }

            // 4. Scan ALL extras keys for any hidden Bitmap values
            for (String key : extras.keySet()) {
                try {
                    Object val = extras.get(key);
                    if (val instanceof Bitmap) {
                        best = pickLarger(best, (Bitmap) val);
                    }
                } catch (Exception ignored) {}
            }

            // 5. WearableExtender — may contain background bitmap
            try {
                Notification.WearableExtender wearable = new Notification.WearableExtender(notification);
                Bitmap bg = wearable.getBackground();
                if (bg != null) {
                    best = pickLarger(best, bg);
                }
            } catch (Exception ignored) {}

            // 6. MessagingStyle messages — may contain image URIs (Android 9+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    // Use reflection to handle API differences
                    java.lang.reflect.Method method = Notification.MessagingStyle.class
                            .getMethod("extractMessagingStyleFromNotification", Notification.class);
                    Object style = method.invoke(null, notification);
                    if (style instanceof Notification.MessagingStyle) {
                        for (Notification.MessagingStyle.Message msg :
                                ((Notification.MessagingStyle) style).getMessages()) {
                            if (msg.getDataUri() != null && msg.getDataMimeType() != null
                                    && msg.getDataMimeType().startsWith("image/")) {
                                try {
                                    java.io.InputStream is = getContentResolver().openInputStream(msg.getDataUri());
                                    if (is != null) {
                                        Bitmap msgBmp = android.graphics.BitmapFactory.decodeStream(is);
                                        is.close();
                                        if (msgBmp != null) {
                                            best = pickLarger(best, msgBmp);
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

        } catch (Exception e) {
            Log.w(TAG, "Error extracting bitmap from notification", e);
        }

        if (best != null) {
            Log.i(TAG, "📸 Extracted bitmap: " + best.getWidth() + "x" + best.getHeight());
        }
        return best;
    }

    /** Returns whichever bitmap has more pixels. */
    private Bitmap pickLarger(Bitmap a, Bitmap b) {
        if (a == null) return b;
        if (b == null) return a;
        return (b.getWidth() * b.getHeight() > a.getWidth() * a.getHeight()) ? b : a;
    }

    /** Maximum quality JPEG encoding for the best possible saved image. */
    private String bitmapToBase64(Bitmap bitmap) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
            byte[] byteArray = outputStream.toByteArray();
            return "data:image/png;base64," + Base64.encodeToString(byteArray, Base64.NO_WRAP);
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
