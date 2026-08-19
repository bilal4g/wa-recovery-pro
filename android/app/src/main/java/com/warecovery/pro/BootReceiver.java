package com.warecovery.pro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Boot receiver to restart the recovery services after device reboot.
 * Ensures continuous message capture without user intervention.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "WARecovery_Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {

            Log.i(TAG, "Device booted / app updated — restarting recovery services");

            // The NotificationListenerService is automatically restarted by the system
            // if it was enabled. We just need to restart our media scanner.

            try {
                MediaScanner scanner = new MediaScanner(context);
                scanner.startScanning();
                Log.i(TAG, "Media scanner restarted after boot");
            } catch (Exception e) {
                Log.e(TAG, "Failed to restart media scanner", e);
            }
        }
    }
}
