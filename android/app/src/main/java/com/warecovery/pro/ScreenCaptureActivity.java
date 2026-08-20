package com.warecovery.pro;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Transparent Activity that prompts the user for MediaProjection permission
 * and forwards the token to FloatingAssistantService for real pixel screenshots & screen videos.
 */
public class ScreenCaptureActivity extends Activity {

    private static final String TAG = "WARecovery_CaptureAct";
    private static final int REQUEST_CODE_CAPTURE = 9988;
    public static final String EXTRA_ACTION = "action_type";
    public static final String ACTION_SCREENSHOT = "screenshot";
    public static final String ACTION_VIDEO = "video";

    private String pendingAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pendingAction = getIntent().getStringExtra(EXTRA_ACTION);
        if (pendingAction == null) pendingAction = ACTION_SCREENSHOT;

        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr != null) {
            startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE_CAPTURE);
        } else {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_CAPTURE && resultCode == RESULT_OK && data != null) {
            FloatingAssistantService service = FloatingAssistantService.getInstance();
            if (service != null) {
                service.onMediaProjectionGranted(resultCode, data, pendingAction);
            }
        }
        finish();
    }
}
