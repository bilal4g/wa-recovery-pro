package com.warecovery.pro;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Capacitor Plugin Bridge that connects the native Android recovery services
 * to the web UI layer. All native functions are exposed via @PluginMethod.
 */
@CapacitorPlugin(name = "RecoveryBridge")
public class RecoveryBridge extends Plugin {

    private static final String TAG = "WARecovery_Bridge";
    private static RecoveryBridge instance;

    private MediaScanner mediaScanner;
    private VoiceExtractor voiceExtractor;
    private ViewOnceCapture viewOnceCapture;
    private DatabaseHelper dbHelper;

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
            java.util.Set<String> packages = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(getContext());
            enabled = packages.contains(getContext().getPackageName());
        } catch (Exception e) {
            try {
                String enabledListeners = Settings.Secure.getString(
                        getContext().getContentResolver(),
                        "enabled_notification_listeners"
                );
                enabled = enabledListeners != null &&
                        enabledListeners.contains(getContext().getPackageName());
            } catch (Exception ignored) {}
        }

        JSObject result = new JSObject();
        result.put("enabled", enabled);
        call.resolve(result);
    }

    /**
     * Open the system notification access settings page.
     */
    @PluginMethod()
    public void openNotificationSettings(PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                intent.putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        new android.content.ComponentName(getContext(), NotificationListener.class).flattenToString()
                );
            }
            if (getActivity() != null) {
                getActivity().startActivity(intent);
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            }
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
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.fromParts("package", getContext().getPackageName(), null));
            if (getActivity() != null) {
                getActivity().startActivity(intent);
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            }
        } catch (Exception ignored) {}
        call.resolve();
    }

    /**
     * Open All Files / Storage Access settings screen.
     */
    @PluginMethod()
    public void openStorageSettings(PluginCall call) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(android.net.Uri.fromParts("package", getContext().getPackageName(), null));
                if (getActivity() != null) {
                    getActivity().startActivity(intent);
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(intent);
                }
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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + getContext().getPackageName()));
                if (getActivity() != null) {
                    getActivity().startActivity(intent);
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(intent);
                }
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
        try {
            String contact = call.getString("contact");
            String filter = call.getString("filter");

            JSONArray messages = dbHelper.getMessagesAsJSON(contact, filter);

            JSObject result = new JSObject();
            result.put("messages", messages.toString());
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to get messages", e);
        }
    }

    /**
     * Get aggregated contact list with message counts.
     */
    @PluginMethod()
    public void getContacts(PluginCall call) {
        try {
            JSONArray messages = dbHelper.getMessagesAsJSON(null, null);
            // The web layer handles contact aggregation from messages
            JSObject result = new JSObject();
            result.put("messages", messages.toString());
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to get contacts", e);
        }
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
            // Media data is stored in the database
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
            result.put("totalMessages", stats.optInt("totalMessages", 0));
            result.put("deletedRecovered", stats.optInt("deletedRecovered", 0));
            result.put("viewOnceCaptures", stats.optInt("viewOnceCaptures", 0));
            result.put("totalMedia", stats.optInt("totalMedia", 0));
            result.put("totalVoiceNotes", stats.optInt("totalVoiceNotes", 0));
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to get stats", e);
        }
    }

    // =============================================
    // DATA MANAGEMENT
    // =============================================

    /**
     * Export all data as JSON.
     */
    @PluginMethod()
    public void exportData(PluginCall call) {
        try {
            JSONArray messages = dbHelper.getMessagesAsJSON(null, null);
            JSObject result = new JSObject();
            result.put("data", messages.toString());
            result.put("exportDate", System.currentTimeMillis());
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to export data", e);
        }
    }

    /**
     * Clear all recovered data.
     */
    @PluginMethod()
    public void clearAll(PluginCall call) {
        dbHelper.clearAll();
        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
    }

    // =============================================
    // IN-APP APK AUTO-INSTALLER
    // =============================================

    /**
     * Downloads an APK from a URL and launches the Android Package Installer.
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
                java.net.URL url = new java.net.URL(apkUrl);
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent", "WA-Recovery-Pro-Updater");
                connection.setInstanceFollowRedirects(true);
                connection.connect();

                int responseCode = connection.getResponseCode();
                if (responseCode == java.net.HttpURLConnection.HTTP_MOVED_PERM || 
                    responseCode == java.net.HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == 307 || responseCode == 308) {
                    String newUrl = connection.getHeaderField("Location");
                    url = new java.net.URL(newUrl);
                    connection = (java.net.HttpURLConnection) url.openConnection();
                    connection.setRequestProperty("User-Agent", "WA-Recovery-Pro-Updater");
                    connection.connect();
                }

                int fileLength = connection.getContentLength();
                java.io.File cacheDir = context.getExternalCacheDir();
                if (cacheDir == null) cacheDir = context.getCacheDir();
                java.io.File apkFile = new java.io.File(cacheDir, "WA-Recovery-Pro-Update.apk");
                if (apkFile.exists()) {
                    apkFile.delete();
                }

                java.io.InputStream input = new java.io.BufferedInputStream(connection.getInputStream(), 8192);
                java.io.OutputStream output = new java.io.FileOutputStream(apkFile);

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
                android.net.Uri apkUri;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    apkUri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            context.getPackageName() + ".fileprovider",
                            apkFile
                    );
                } else {
                    apkUri = android.net.Uri.fromFile(apkFile);
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
