package com.warecovery.pro;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

/**
 * Android Accessibility Service for instant, 0-prompt screen capture.
 * Provides direct hardware-buffer screenshots without per-session MediaProjection dialogs.
 */
public class WAAccessibilityService extends AccessibilityService {

    private static final String TAG = "WARecovery_A11y";
    private static WAAccessibilityService instance;

    public static WAAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static boolean isAccessibilityServiceEnabled(Context context) {
        if (instance != null) return true;
        try {
            int accessibilityEnabled = Settings.Secure.getInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0
            );
            if (accessibilityEnabled == 1) {
                String services = Settings.Secure.getString(
                        context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                );
                if (!TextUtils.isEmpty(services)) {
                    String myServiceName = context.getPackageName() + "/" + WAAccessibilityService.class.getName();
                    return services.contains(myServiceName) || services.contains(context.getPackageName());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking accessibility status", e);
        }
        return false;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "⚡ WA Accessibility 0-Prompt Screenshot Service Connected!");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Passive listener
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "WA Accessibility Service Interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Log.i(TAG, "WA Accessibility Service Destroyed");
    }

    public interface ScreenshotCallback {
        void onSuccess(Bitmap bitmap);
        void onFailure(String error);
    }

    /**
     * Take instant raw screen capture directly from the hardware buffer.
     * ZERO "Share Screen" dialogs, ZERO status bar recording indicators, ~15ms speed.
     */
    public void takeInstantScreenshot(ScreenshotCallback callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        try {
                            HardwareBuffer hwBuffer = result.getHardwareBuffer();
                            if (hwBuffer != null) {
                                Bitmap bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, result.getColorSpace());
                                Bitmap softwareBitmap = bitmap != null ? bitmap.copy(Bitmap.Config.ARGB_8888, false) : null;
                                hwBuffer.close();
                                if (softwareBitmap != null) {
                                    if (callback != null) callback.onSuccess(softwareBitmap);
                                    return;
                                }
                            }
                            if (callback != null) callback.onFailure("Empty hardware buffer");
                        } catch (Exception e) {
                            Log.e(TAG, "Error wrapping hardware buffer bitmap", e);
                            if (callback != null) callback.onFailure(e.getMessage());
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Log.e(TAG, "Accessibility takeScreenshot failed: " + errorCode);
                        if (callback != null) callback.onFailure("Failed with error code: " + errorCode);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Exception calling takeScreenshot", e);
                if (callback != null) callback.onFailure(e.getMessage());
            }
        } else {
            if (callback != null) callback.onFailure("Requires Android 11+ (API 30+)");
        }
    }
}
