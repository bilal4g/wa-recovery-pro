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
        String enabledListeners = Settings.Secure.getString(
                getContext().getContentResolver(),
                "enabled_notification_listeners"
        );
        boolean enabled = enabledListeners != null &&
                enabledListeners.contains(getContext().getPackageName());

        JSObject result = new JSObject();
        result.put("enabled", enabled);
        call.resolve(result);
    }

    /**
     * Open the system notification access settings page.
     */
    @PluginMethod()
    public void openNotificationSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    /**
     * Open app settings for permissions.
     */
    @PluginMethod()
    public void openAppSettings(PluginCall call) {
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(android.net.Uri.fromParts("package", getContext().getPackageName(), null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + getContext().getPackageName()));
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
