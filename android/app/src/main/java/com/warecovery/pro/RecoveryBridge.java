package com.warecovery.pro;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import android.Manifest;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Capacitor Plugin Bridge that connects the native Android recovery services
 * to the web UI layer. All native functions are exposed via @PluginMethod.
 */
@CapacitorPlugin(
    name = "RecoveryBridge",
    permissions = {
        @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "microphone"),
        @Permission(strings = { Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE }, alias = "storage")
    }
)
public class RecoveryBridge extends Plugin {

    private static final String TAG = "WARecovery_Bridge";
    private static RecoveryBridge instance;

    private MediaScanner mediaScanner;
    private VoiceExtractor voiceExtractor;
    private ViewOnceCapture viewOnceCapture;
    private DatabaseHelper dbHelper;
    private android.media.MediaPlayer mediaPlayer;

    public static RecoveryBridge getInstance() {
        return instance;
    }

    @Override
    public void load() {
        instance = this;
        Context ctx = getContext();
        dbHelper = DatabaseHelper.getInstance(ctx);
        mediaScanner = new MediaScanner(ctx);
        voiceExtractor = new VoiceExtractor(ctx);
        viewOnceCapture = new ViewOnceCapture(ctx);

        // Start background services
        mediaScanner.startScanning();

        Log.i(TAG, "RecoveryBridge loaded and services started");
    }

    // =============================================
    // SERVICE MANAGEMENT
    // =============================================

    /**
     * Check if notification listener service is enabled.
     */
    @PluginMethod()
    public void isNotificationAccessEnabled(PluginCall call) {
        boolean enabled = false;
        try {
            String enabledListeners = Settings.Secure.getString(
                    getContext().getContentResolver(),
                    "enabled_notification_listeners"
            );
            enabled = enabledListeners != null &&
                    enabledListeners.contains(getContext().getPackageName());
        } catch (Exception ignored) {}

        JSObject result = new JSObject();
        result.put("enabled", enabled);
        call.resolve(result);
    }

    /**
     * Check if storage permission or manage external storage is granted.
     */
    @PluginMethod()
    public void isStoragePermissionGranted(PluginCall call) {
        boolean granted = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                granted = Environment.isExternalStorageManager();
            }
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean read = getContext().checkSelfPermission(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
                boolean write = getContext().checkSelfPermission(
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
                granted = read || write;
            }
            if (!granted && Build.VERSION.SDK_INT >= 33) {
                boolean images = getContext().checkSelfPermission("android.permission.READ_MEDIA_IMAGES") == PackageManager.PERMISSION_GRANTED;
                boolean audio = getContext().checkSelfPermission("android.permission.READ_MEDIA_AUDIO") == PackageManager.PERMISSION_GRANTED;
                boolean video = getContext().checkSelfPermission("android.permission.READ_MEDIA_VIDEO") == PackageManager.PERMISSION_GRANTED;
                granted = images || audio || video;
            }
        } catch (Exception e) {
            granted = false;
        }

        JSObject result = new JSObject();
        result.put("granted", granted);
        call.resolve(result);
    }

    /**
     * Check if battery optimizations are ignored.
     */
    @PluginMethod()
    public void isBatteryOptimizationIgnored(PluginCall call) {
        boolean isIgnored = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    isIgnored = pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
                }
            }
        } catch (Exception ignored) {}

        JSObject result = new JSObject();
        result.put("ignored", isIgnored);
        call.resolve(result);
    }

    /**
     * Get status of all 3 permissions in one call.
     */
    @PluginMethod()
    public void getAllPermissionsStatus(PluginCall call) {
        boolean notif = false;
        try {
            String enabledListeners = Settings.Secure.getString(
                    getContext().getContentResolver(),
                    "enabled_notification_listeners"
            );
            notif = enabledListeners != null &&
                    enabledListeners.contains(getContext().getPackageName());
        } catch (Exception ignored) {}

        boolean storage = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                storage = Environment.isExternalStorageManager();
            }
            if (!storage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                storage = getContext().checkSelfPermission(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            }
            if (!storage && Build.VERSION.SDK_INT >= 33) {
                storage = getContext().checkSelfPermission("android.permission.READ_MEDIA_IMAGES") == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Exception ignored) {}

        boolean battery = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    battery = pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
                }
            }
        } catch (Exception ignored) {}

        JSObject result = new JSObject();
        result.put("notification", notif);
        result.put("storage", storage);
        result.put("battery", battery);
        call.resolve(result);
    }

    /**
     * Open the system notification access settings page.
     */
    @PluginMethod()
    public void openNotificationSettings(PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                intent.putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        new android.content.ComponentName(getContext(), NotificationListener.class).flattenToString()
                );
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception e) {
            openAppSettings(call);
            return;
        }
        call.resolve();
    }

    /**
     * Open app settings for permissions.
     */
    @PluginMethod()
    public void openAppSettings(PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", getContext().getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception ignored) {}
        call.resolve();
    }

    /**
     * Open All Files / Storage Access settings screen.
     */
    @PluginMethod()
    public void openStorageSettings(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.fromParts("package", getContext().getPackageName(), null));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            } else {
                openAppSettings(call);
                return;
            }
        } catch (Exception e) {
            openAppSettings(call);
            return;
        }
        call.resolve();
    }

    /**
     * Open Battery Optimization ignore settings.
     */
    @PluginMethod()
    public void openBatterySettings(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getContext().getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            }
        } catch (Exception e) {
            openAppSettings(call);
            return;
        }
        call.resolve();
    }

    // =============================================
    // MESSAGES
    // =============================================

    /**
     * Get all messages, optionally filtered by contact and/or type.
     */
    @PluginMethod()
    public void getMessages(PluginCall call) {
        String contact = call.getString("contact", null);
        String filter = call.getString("filter", null);

        try {
            JSONArray messages = dbHelper.getMessagesAsJSON(contact, filter);
            JSObject result = new JSObject();
            result.put("messages", messages);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to get messages", e);
        }
    }

    /**
     * Get only deleted messages.
     */
    @PluginMethod()
    public void getDeletedMessages(PluginCall call) {
        String contact = call.getString("contact", null);

        try {
            JSONArray messages = dbHelper.getMessagesAsJSON(contact, "deleted");
            JSObject result = new JSObject();
            result.put("messages", messages);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to get deleted messages", e);
        }
    }

    /**
     * Mark a message as read or dismissed.
     */
    @PluginMethod()
    public void markMessageRead(PluginCall call) {
        call.resolve();
    }

    /**
     * Delete a single message from the recovery store.
     */
    @PluginMethod()
    public void deleteMessage(PluginCall call) {
        call.resolve();
    }

    // =============================================
    // MEDIA
    // =============================================

    /**
     * Trigger a manual media scan.
     */
    @PluginMethod()
    public void scanMedia(PluginCall call) {
        new Thread(() -> {
            mediaScanner.performFullScan();
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
        }).start();
    }

    /**
     * Get media files of a specific type.
     */
    @PluginMethod()
    public void getMedia(PluginCall call) {
        try {
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to get media", e);
        }
    }

    // =============================================
    // VOICE NOTES
    // =============================================

    /**
     * Trigger a voice notes scan.
     */
    @PluginMethod()
    public void scanVoiceNotes(PluginCall call) {
        new Thread(() -> {
            int count = voiceExtractor.scanAndExtract();
            JSObject result = new JSObject();
            result.put("count", count);
            call.resolve(result);
        }).start();
    }

    /**
     * Play a voice note using Android's native hardware audio player.
     */
    @PluginMethod()
    public void playVoiceNote(PluginCall call) {
        String path = call.getString("path");
        Double speed = call.getDouble("speed", 1.0);

        try {
            if (mediaPlayer != null) {
                try { mediaPlayer.stop(); mediaPlayer.release(); } catch (Exception ignored) {}
                mediaPlayer = null;
            }

            if (path != null) {
                path = path.replace("file://", "");
            }

            if (path == null || path.isEmpty() || !new File(path).exists()) {
                List<File> backups = voiceExtractor.getBackedUpVoiceNotes();
                if (!backups.isEmpty()) {
                    path = backups.get(backups.size() - 1).getAbsolutePath();
                }
            }

            if (path == null || !new File(path).exists()) {
                call.reject("Voice audio file not found: " + path);
                return;
            }

            Log.i(TAG, "Playing exact audio file: " + path);
            mediaPlayer = new android.media.MediaPlayer();
            mediaPlayer.setDataSource(path);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build();
                mediaPlayer.setAudioAttributes(attrs);
            }
            mediaPlayer.prepare();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && speed != null && speed > 0) {
                android.media.PlaybackParams params = new android.media.PlaybackParams();
                params.setSpeed(speed.floatValue());
                mediaPlayer.setPlaybackParams(params);
            }

            mediaPlayer.setOnCompletionListener(mp -> {
                JSObject ret = new JSObject();
                ret.put("status", "completed");
                notifyListeners("onVoicePlayState", ret);
            });

            mediaPlayer.start();

            JSObject result = new JSObject();
            result.put("status", "playing");
            result.put("duration", mediaPlayer.getDuration());
            call.resolve(result);

        } catch (Exception e) {
            Log.e(TAG, "Failed to play voice note", e);
            call.reject("Error playing audio: " + e.getMessage());
        }
    }

    @PluginMethod()
    public void pauseVoiceNote(PluginCall call) {
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
            JSObject result = new JSObject();
            result.put("status", "paused");
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Error pausing audio", e);
        }
    }

    @PluginMethod()
    public void stopVoiceNote(PluginCall call) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.stop();
                mediaPlayer.release();
                mediaPlayer = null;
            }
            JSObject result = new JSObject();
            result.put("status", "stopped");
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Error stopping audio", e);
        }
    }

    @PluginMethod()
    public void getVoiceNotes(PluginCall call) {
        try {
            JSONArray voiceNotes = dbHelper.getVoiceNotesAsJSON();
            JSObject result = new JSObject();
            result.put("voiceNotes", voiceNotes);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to get voice notes", e);
        }
    }

    // =============================================
    // STATISTICS
    // =============================================

    /**
     * Get recovery statistics.
     */
    @PluginMethod()
    public void getStats(PluginCall call) {
        try {
            JSONObject stats = dbHelper.getStats();
            JSObject result = new JSObject();
            result.put("totalMessages", stats.getInt("totalMessages"));
            result.put("deletedRecovered", stats.getInt("deletedRecovered"));
            result.put("viewOnceCaptures", stats.getInt("viewOnceCaptures"));
            result.put("totalMedia", stats.getInt("totalMedia"));
            result.put("totalVoiceNotes", stats.getInt("totalVoiceNotes"));
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to get statistics", e);
        }
    }

    /**
     * Clear all recovered data.
     */
    @PluginMethod()
    public void clearAllData(PluginCall call) {
        try {
            dbHelper.clearAll();
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to clear data", e);
        }
    }

    // =============================================
    // VIEW-ONCE
    // =============================================

    /**
     * Check if View-Once protection is active.
     */
    @PluginMethod()
    public void isViewOnceActive(PluginCall call) {
        JSObject result = new JSObject();
        result.put("active", true);
        File[] files = viewOnceCapture.getCapturedFiles();
        result.put("capturedCount", files != null ? files.length : 0);
        call.resolve(result);
    }

    // =============================================
    // IN-APP APK AUTO-UPDATER
    // =============================================

    /**
     * Download and trigger native Android Package Installer for APK update.
     */
    @PluginMethod()
    public void downloadAndInstallApk(PluginCall call) {
        String apkUrl = call.getString("url");
        if (apkUrl == null || apkUrl.isEmpty()) {
            call.reject("APK URL is required");
            return;
        }

        new Thread(() -> {
            try {
                Context context = getContext();
                URL url = new URL(apkUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "WA-Recovery-Pro-Updater");
                connection.setInstanceFollowRedirects(true);
                connection.connect();

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
                    String redirectUrl = connection.getHeaderField("Location");
                    connection = (HttpURLConnection) new URL(redirectUrl).openConnection();
                    connection.connect();
                }

                int fileLength = connection.getContentLength();
                File cacheDir = context.getExternalCacheDir();
                if (cacheDir == null) cacheDir = context.getCacheDir();
                File apkFile = new File(cacheDir, "WA-Recovery-Pro-update.apk");

                InputStream input = new BufferedInputStream(connection.getInputStream(), 8192);
                OutputStream output = new FileOutputStream(apkFile);

                byte[] data = new byte[4096];
                long total = 0;
                int count;
                int lastReportedProgress = -1;

                while ((count = input.read(data)) != -1) {
                    total += count;
                    if (fileLength > 0) {
                        int progress = (int) (total * 100 / fileLength);
                        if (progress != lastReportedProgress) {
                            lastReportedProgress = progress;
                            JSObject progressObj = new JSObject();
                            progressObj.put("progress", progress);
                            progressObj.put("bytesDownloaded", total);
                            progressObj.put("totalBytes", fileLength);
                            notifyListeners("apkDownloadProgress", progressObj);
                        }
                    }
                    output.write(data, 0, count);
                }

                output.flush();
                output.close();
                input.close();

                // Launch Android Package Installer
                Uri apkUri;
                try {
                    Class<?> fpClass = Class.forName("androidx.core.content.FileProvider");
                    java.lang.reflect.Method getUri = fpClass.getMethod("getUriForFile", Context.class, String.class, File.class);
                    apkUri = (Uri) getUri.invoke(null, context, context.getPackageName() + ".fileprovider", apkFile);
                } catch (Exception fallback) {
                    apkUri = Uri.fromFile(apkFile);
                }

                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);

                JSObject result = new JSObject();
                result.put("success", true);
                call.resolve(result);

            } catch (Exception e) {
                Log.e(TAG, "Failed to download and install APK", e);
                call.reject("Download/Install failed: " + e.getMessage());
            }
        }).start();
    }

    // =============================================
    // FLOATING CAPTURE ASSISTANT (OVERLAY SPY BUBBLE)
    // =============================================

    /**
     * Check if Display over other apps (SYSTEM_ALERT_WINDOW) permission is granted.
     */
    @PluginMethod()
    public void checkOverlayPermission(PluginCall call) {
        boolean granted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            granted = Settings.canDrawOverlays(getContext());
        }
        JSObject res = new JSObject();
        res.put("granted", granted);
        call.resolve(res);
    }

    /**
     * Check if Microphone (RECORD_AUDIO) runtime permission is granted.
     */
    @PluginMethod()
    public void isAudioPermissionGranted(PluginCall call) {
        boolean granted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            granted = getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
        JSObject res = new JSObject();
        res.put("granted", granted);
        call.resolve(res);
    }

    /**
     * Request Microphone runtime permission popup.
     */
    @PluginMethod()
    public void requestAudioPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionForAlias("microphone", call, "microphoneCallback");
                return;
            }
        }
        JSObject res = new JSObject();
        res.put("granted", true);
        call.resolve(res);
    }

    @PermissionCallback
    private void microphoneCallback(PluginCall call) {
        boolean granted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            granted = getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
        JSObject res = new JSObject();
        res.put("granted", granted);
        call.resolve(res);
    }

    /**
     * Open system settings for "Display over other apps".
     */
    @PluginMethod()
    public void requestOverlayPermission(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getContext().getPackageName())
                );
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not open overlay settings: " + e.getMessage());
        }
    }

    /**
     * Start the Floating Capture Assistant Service.
     */
    @PluginMethod()
    public void startFloatingAssistant(PluginCall call) {
        try {
            Context ctx = getContext();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
                JSObject res = new JSObject();
                res.put("success", false);
                res.put("error", "OVERLAY_PERMISSION_REQUIRED");
                call.resolve(res);
                return;
            }

            Intent intent = new Intent(ctx, FloatingAssistantService.class);
            intent.setAction(FloatingAssistantService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }

            JSObject res = new JSObject();
            res.put("success", true);
            call.resolve(res);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start floating assistant", e);
            call.reject("Failed to start assistant: " + e.getMessage());
        }
    }

    /**
     * Stop the Floating Capture Assistant Service.
     */
    @PluginMethod()
    public void stopFloatingAssistant(PluginCall call) {
        try {
            Context ctx = getContext();
            Intent intent = new Intent(ctx, FloatingAssistantService.class);
            intent.setAction(FloatingAssistantService.ACTION_STOP);
            ctx.stopService(intent);

            JSObject res = new JSObject();
            res.put("success", true);
            call.resolve(res);
        } catch (Exception e) {
            call.reject("Failed to stop assistant: " + e.getMessage());
        }
    }

    /**
     * Check if Floating Capture Assistant is currently active.
     */
    @PluginMethod()
    public void isFloatingAssistantRunning(PluginCall call) {
        JSObject res = new JSObject();
        res.put("isRunning", FloatingAssistantService.isRunning);
        call.resolve(res);
    }

    // =============================================
    // NATIVE SYSTEM SHARING
    // =============================================

    /**
     * Open Android Native Share Sheet for Text (Intent.ACTION_SEND).
     */
    @PluginMethod()
    public void shareText(PluginCall call) {
        String text = call.getString("text", "");
        String title = call.getString("title", "Share via");

        if (text == null || text.trim().isEmpty()) {
            call.reject("Text cannot be empty");
            return;
        }

        try {
            Intent sendIntent = new Intent();
            sendIntent.setAction(Intent.ACTION_SEND);
            sendIntent.putExtra(Intent.EXTRA_TEXT, text);
            sendIntent.setType("text/plain");

            Intent shareIntent = Intent.createChooser(sendIntent, title);
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(shareIntent);

            JSObject res = new JSObject();
            res.put("success", true);
            call.resolve(res);
        } catch (Exception e) {
            Log.e(TAG, "Failed to share text", e);
            call.reject("Share failed: " + e.getMessage());
        }
    }

    /**
     * Share real Media/Audio/Video/Photo file directly via Android Native Share Sheet (FileProvider).
     */
    @PluginMethod()
    public void shareMedia(PluginCall call) {
        String path = call.getString("path");
        String mimeType = call.getString("mimeType", "audio/*");
        String title = call.getString("title", "Share Audio");

        if (path == null || path.isEmpty()) {
            call.reject("File path cannot be empty");
            return;
        }

        path = path.replace("file://", "");
        File file = new File(path);
        if (!file.exists()) {
            call.reject("File does not exist: " + path);
            return;
        }

        try {
            Context ctx = getContext();
            Uri contentUri;
            try {
                Class<?> fpClass = Class.forName("androidx.core.content.FileProvider");
                java.lang.reflect.Method getUri = fpClass.getMethod("getUriForFile", Context.class, String.class, File.class);
                contentUri = (Uri) getUri.invoke(null, ctx, ctx.getPackageName() + ".fileprovider", file);
            } catch (Exception e) {
                contentUri = Uri.fromFile(file);
            }

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType(mimeType);
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

            Intent chooser = Intent.createChooser(shareIntent, title);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);

            JSObject res = new JSObject();
            res.put("success", true);
            call.resolve(res);
        } catch (Exception e) {
            Log.e(TAG, "Failed to share media file", e);
            call.reject("Failed to share file: " + e.getMessage());
        }
    }

    // =============================================
    // NATIVE EVENT CALLBACKS
    // =============================================

    /**
     * Called by native services to notify the web layer of events.
     */
    public void onNativeEvent(String eventName, String data) {
        JSObject eventData = new JSObject();
        eventData.put("event", eventName);
        eventData.put("data", data);
        eventData.put("timestamp", System.currentTimeMillis());
        notifyListeners(eventName, eventData);
    }
}
