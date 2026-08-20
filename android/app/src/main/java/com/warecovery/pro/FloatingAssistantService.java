package com.warecovery.pro;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Floating Capture Assistant Overlay Service.
 * Draws a floating bubble over other apps (WhatsApp) allowing users to:
 * 1. Record real audio while playing View-Once voice notes or videos (AAC 44.1kHz).
 * 2. Take REAL device pixel screenshots using Android MediaProjection.
 * 3. Record REAL full-screen MP4 videos using Android MediaProjection.
 */
@SuppressWarnings("deprecation")
public class FloatingAssistantService extends Service {

    private static final String TAG = "WARecovery_Floating";
    private static final String CHANNEL_ID = "wa_floating_assistant_channel";
    private static final int NOTIF_ID = 2001;

    private static FloatingAssistantService instance;
    public static boolean isRunning = false;
    public static final String ACTION_START = "START_FLOATING_ASSISTANT";
    public static final String ACTION_STOP = "STOP_FLOATING_ASSISTANT";
    public static final String ACTION_MEDIA_PROJECTION = "MEDIA_PROJECTION_GRANTED";

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;

    // Overlay UI Components
    private LinearLayout bubbleRoot;
    private TextView bubbleText;
    private LinearLayout menuCard;
    private Button btnRecordAudio;
    private Button btnCloseMenu;

    // Audio Recording State
    private boolean isRecording = false;
    private MediaRecorder audioRecorder;
    private String currentAudioPath;
    private long recordStartTime = 0;
    private Handler timerHandler;
    private Runnable timerRunnable;

    // Video Recording & Screen Capture State
    private boolean isRecordingVideo = false;
    private MediaRecorder videoRecorder;
    private VirtualDisplay videoVirtualDisplay;
    private long videoStartTime = 0;
    private String currentVideoPath;

    // MediaProjection
    private MediaProjection mediaProjection;
    private int projResultCode;
    private Intent projData;

    private DatabaseHelper dbHelper;

    public static FloatingAssistantService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        isRunning = true;
        dbHelper = DatabaseHelper.getInstance(this);
        timerHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_MEDIA_PROJECTION.equals(intent.getAction())) {
            int resCode = intent.getIntExtra("resultCode", -1);
            Intent data = intent.getParcelableExtra("data");
            String act = intent.getStringExtra("action");
            if (resCode == -1 && data != null) {
                onMediaProjectionGranted(resCode, data, act);
            }
            return START_STICKY;
        }

        try {
            startForeground(NOTIF_ID, buildForegroundNotification());
        } catch (Exception e) {
            Log.e(TAG, "Failed to startForeground: " + e.getMessage());
        }

        try {
            showFloatingBubble();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show floating bubble: " + e.getMessage());
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        instance = null;

        if (isRecording) {
            stopAudioRecording();
        }
        if (isRecordingVideo) {
            stopVideoRecording();
        }
        cleanupPersistentMirror();
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }

        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {}
            floatingView = null;
        }

        // Notify Web Layer that assistant stopped
        RecoveryBridge bridge = RecoveryBridge.getInstance();
        if (bridge != null) {
            bridge.onNativeEvent("assistantStateChanged", "stopped");
        }
    }

    // =============================================
    // FLOATING OVERLAY UI
    // =============================================

    @SuppressLint("ClickableViewAccessibility")
    private void showFloatingBubble() {
        if (floatingView != null) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 40;
        params.y = 250;

        floatingView = buildOverlayView();
        windowManager.addView(floatingView, params);
    }

    private View buildOverlayView() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);

        // 1. Floating Capsule Bubble
        bubbleRoot = new LinearLayout(this);
        bubbleRoot.setOrientation(LinearLayout.HORIZONTAL);
        bubbleRoot.setGravity(Gravity.CENTER_VERTICAL);
        bubbleRoot.setPadding(28, 20, 28, 20);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#111B21"));
        bg.setCornerRadius(60f);
        bg.setStroke(3, Color.parseColor("#00A884"));
        bubbleRoot.setBackground(bg);
        bubbleRoot.setElevation(16f);

        ImageView icon = new ImageView(this);
        icon.setImageResource(android.R.drawable.ic_btn_speak_now);
        icon.setColorFilter(Color.parseColor("#00A884"));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(48, 48);
        iconParams.setMarginEnd(16);
        bubbleRoot.addView(icon, iconParams);

        bubbleText = new TextView(this);
        bubbleText.setText("WA Assistant");
        bubbleText.setTextColor(Color.WHITE);
        bubbleText.setTextSize(13.5f);
        bubbleText.setTypeface(null, android.graphics.Typeface.BOLD);
        bubbleRoot.addView(bubbleText);

        // 2. Expandable Action Card
        menuCard = buildMenuCard();
        menuCard.setVisibility(View.GONE);

        // Touch listener for Drag & Drop + Tap toggle
        bubbleRoot.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isDrag = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDrag = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDrag = true;
                            params.x = initialX + dx;
                            params.y = initialY + dy;
                            try {
                                windowManager.updateViewLayout(floatingView, params);
                            } catch (Exception ignored) {}
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!isDrag) {
                            if (isRecording) {
                                stopAudioRecording();
                            } else if (isRecordingVideo) {
                                stopVideoRecording();
                            } else {
                                toggleMenu();
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        container.addView(bubbleRoot);
        container.addView(menuCard);
        return container;
    }

    private LinearLayout buildMenuCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(28, 24, 28, 24);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#1F2C34"));
        cardBg.setCornerRadius(24f);
        cardBg.setStroke(2, Color.parseColor("#2A3942"));
        card.setBackground(cardBg);
        card.setElevation(20f);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(540, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 16, 0, 0);
        card.setLayoutParams(cardParams);

        // Title
        TextView title = new TextView(this);
        title.setText("Spy Capture Menu");
        title.setTextColor(Color.parseColor("#E9EDEF"));
        title.setTextSize(14f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, 16);
        card.addView(title);

        // Button: 1-Tap Record Audio
        btnRecordAudio = createMenuButton("🎙️ Record Voice / Audio", "#00A884", v -> toggleAudioRecording());
        card.addView(btnRecordAudio);

        // Button: 1-Tap Instant Screenshot
        Button btnScreenshot = createMenuButton("📸 1-Tap Screenshot", "#3B82F6", v -> takeInstantScreenshot());
        card.addView(btnScreenshot);

        // Button: 1-Tap Record Screen Video
        Button btnScreenVideo = createMenuButton("🎥 Record Screen Video", "#8B5CF6", v -> toggleVideoRecording());
        card.addView(btnScreenVideo);

        // Button: Close Assistant
        btnCloseMenu = createMenuButton("❌ Close Assistant", "#EF4444", v -> stopSelf());
        card.addView(btnCloseMenu);

        return card;
    }

    private Button createMenuButton(String text, String colorHex, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(12.5f);
        btn.setAllCaps(false);
        btn.setOnClickListener(listener);

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor(colorHex));
        btnBg.setCornerRadius(16f);
        btn.setBackground(btnBg);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 90
        );
        p.setMargins(0, 8, 0, 8);
        btn.setLayoutParams(p);
        return btn;
    }

    private void toggleMenu() {
        if (menuCard == null) return;
        if (menuCard.getVisibility() == View.VISIBLE) {
            menuCard.setVisibility(View.GONE);
        } else {
            menuCard.setVisibility(View.VISIBLE);
        }
    }

    // Persistent Screen Mirror State (Eliminates repeated prompts)
    private ImageReader persistentImageReader;
    private VirtualDisplay persistentVirtualDisplay;
    private Bitmap latestScreenBitmap;
    private final Object bitmapLock = new Object();

    // =============================================
    // MEDIAPROJECTION CALLBACK & PERSISTENT MIRROR
    // =============================================

    public void onMediaProjectionGranted(int resultCode, Intent data, String action) {
        this.projResultCode = resultCode;
        this.projData = data;

        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr != null) {
            try {
                this.mediaProjection = mgr.getMediaProjection(resultCode, (Intent) data.clone());
                
                if (this.mediaProjection != null) {
                    this.mediaProjection.registerCallback(new MediaProjection.Callback() {
                        @Override
                        public void onStop() {
                            Log.i(TAG, "MediaProjection stopped");
                            cleanupPersistentMirror();
                            mediaProjection = null;
                        }
                    }, timerHandler);

                    initPersistentScreenMirror();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error obtaining MediaProjection", e);
            }
        }

        if (ScreenCaptureActivity.ACTION_VIDEO.equals(action)) {
            startRealVideoRecording();
        } else {
            // First instant capture right after grant
            timerHandler.postDelayed(this::captureRealPixelScreenshot, 350);
        }
    }

    private void initPersistentScreenMirror() {
        if (mediaProjection == null) return;
        try {
            cleanupPersistentMirror();

            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int w = metrics.widthPixels;
            int h = metrics.heightPixels;
            int density = metrics.densityDpi;

            persistentImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
            persistentVirtualDisplay = mediaProjection.createVirtualDisplay(
                    "WARecovery_PersistentMirror",
                    w, h, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    persistentImageReader.getSurface(), null, timerHandler
            );

            persistentImageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null) {
                        Image.Plane[] planes = image.getPlanes();
                        ByteBuffer buffer = planes[0].getBuffer();
                        int pixelStride = planes[0].getPixelStride();
                        int rowStride = planes[0].getRowStride();
                        int rowPadding = rowStride - pixelStride * w;

                        Bitmap bitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888);
                        bitmap.copyPixelsFromBuffer(buffer);

                        synchronized (bitmapLock) {
                            latestScreenBitmap = Bitmap.createBitmap(bitmap, 0, 0, w, h);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error updating screen frame", e);
                } finally {
                    if (image != null) image.close();
                }
            }, timerHandler);

            Log.i(TAG, "Persistent screen mirror initialized: 0-prompt instant capture active");
        } catch (Exception e) {
            Log.e(TAG, "Failed to init persistent screen mirror", e);
        }
    }

    private void cleanupPersistentMirror() {
        if (persistentVirtualDisplay != null) {
            try { persistentVirtualDisplay.release(); } catch (Exception ignored) {}
            persistentVirtualDisplay = null;
        }
        if (persistentImageReader != null) {
            try { persistentImageReader.close(); } catch (Exception ignored) {}
            persistentImageReader = null;
        }
    }

    // =============================================
    // REAL SCREENSHOT ENGINE
    // =============================================

    private void takeInstantScreenshot() {
        if (menuCard != null) menuCard.setVisibility(View.GONE);

        if (mediaProjection == null || persistentVirtualDisplay == null) {
            Intent intent = new Intent(this, ScreenCaptureActivity.class);
            intent.putExtra(ScreenCaptureActivity.EXTRA_ACTION, ScreenCaptureActivity.ACTION_SCREENSHOT);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            captureRealPixelScreenshot();
        }
    }

    private void captureRealPixelScreenshot() {
        if (floatingView != null) floatingView.setVisibility(View.INVISIBLE);

        timerHandler.postDelayed(() -> {
            try {
                Bitmap snap = null;
                synchronized (bitmapLock) {
                    if (latestScreenBitmap != null && !latestScreenBitmap.isRecycled()) {
                        snap = latestScreenBitmap.copy(latestScreenBitmap.getConfig(), false);
                    }
                }

                if (snap != null) {
                    saveAndPublishScreenshot(snap);
                } else {
                    // If mirror is fresh, wait 200ms and grab
                    timerHandler.postDelayed(() -> {
                        synchronized (bitmapLock) {
                            if (latestScreenBitmap != null && !latestScreenBitmap.isRecycled()) {
                                saveAndPublishScreenshot(latestScreenBitmap.copy(latestScreenBitmap.getConfig(), false));
                            }
                        }
                        if (floatingView != null) floatingView.setVisibility(View.VISIBLE);
                    }, 200);
                    return;
                }

            } catch (Exception e) {
                Log.e(TAG, "Failed real screenshot", e);
            } finally {
                if (floatingView != null) floatingView.setVisibility(View.VISIBLE);
            }
        }, 150);
    }

    private void saveAndPublishScreenshot(Bitmap bitmap) {
        try {
            File dir = new File(getFilesDir(), "media_backup");
            if (!dir.exists()) dir.mkdirs();

            long timestamp = System.currentTimeMillis();
            String fileName = "wa_screenshot_" + timestamp + ".png";
            File screenFile = new File(dir, fileName);

            FileOutputStream out = new FileOutputStream(screenFile);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            String base64 = "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

            // 1. Insert into Messages
            dbHelper.insertMessage(
                    "Screen Capture",
                    "📸 Captured Screenshot",
                    "image",
                    timestamp,
                    "received",
                    null,
                    false,
                    true,
                    base64,
                    screenFile.getAbsolutePath(),
                    "screen_" + timestamp
            );

            // 2. Insert into Media table
            dbHelper.insertMedia(
                    "Screen Capture",
                    "image",
                    screenFile.getAbsolutePath(),
                    fileName,
                    screenFile.length(),
                    "image/png",
                    base64,
                    timestamp,
                    true
            );

            // 3. Save copy to Phone Gallery (Pictures/WARecovery)
            try {
                File pubDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "WARecovery");
                if (!pubDir.exists()) pubDir.mkdirs();
                File pubFile = new File(pubDir, fileName);
                FileOutputStream pubOut = new FileOutputStream(pubFile);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, pubOut);
                pubOut.flush();
                pubOut.close();

                android.media.MediaScannerConnection.scanFile(
                        this,
                        new String[]{pubFile.getAbsolutePath()},
                        new String[]{"image/png"},
                        null
                );
            } catch (Exception ignored) {}

            // 4. Notify Web Layer
            RecoveryBridge bridge = RecoveryBridge.getInstance();
            if (bridge != null) {
                bridge.onNativeEvent("newMessage", "Screen Capture");
                bridge.onNativeEvent("mediaRecovered", "Screen Capture");
            }

            Toast.makeText(this, "📸 Real Screenshot captured & saved to Photos!", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Screenshot captured successfully: " + screenFile.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "Error saving screenshot", e);
        }
    }

    // =============================================
    // REAL SCREEN VIDEO ENGINE
    // =============================================

    private void toggleVideoRecording() {
        if (isRecordingVideo) {
            stopVideoRecording();
        } else {
            if (menuCard != null) menuCard.setVisibility(View.GONE);
            if (mediaProjection == null) {
                Intent intent = new Intent(this, ScreenCaptureActivity.class);
                intent.putExtra(ScreenCaptureActivity.EXTRA_ACTION, ScreenCaptureActivity.ACTION_VIDEO);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } else {
                startRealVideoRecording();
            }
        }
    }

    private void startRealVideoRecording() {
        try {
            if (mediaProjection == null && projData != null) {
                MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                if (mgr != null) mediaProjection = mgr.getMediaProjection(projResultCode, (Intent) projData.clone());
            }

            if (mediaProjection == null) {
                Toast.makeText(this, "Please grant screen recording permission", Toast.LENGTH_SHORT).show();
                return;
            }

            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int w = (metrics.widthPixels / 2) * 2;
            int h = (metrics.heightPixels / 2) * 2;
            int density = metrics.densityDpi;

            File dir = new File(getFilesDir(), "media_backup");
            if (!dir.exists()) dir.mkdirs();

            long timestamp = System.currentTimeMillis();
            currentVideoPath = new File(dir, "wa_video_" + timestamp + ".mp4").getAbsolutePath();

            videoRecorder = new MediaRecorder();
            videoRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            videoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            videoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            videoRecorder.setOutputFile(currentVideoPath);
            videoRecorder.setVideoSize(w, h);
            videoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            videoRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            videoRecorder.setVideoEncodingBitRate(5 * 1024 * 1024);
            videoRecorder.setVideoFrameRate(30);
            videoRecorder.prepare();

            videoVirtualDisplay = mediaProjection.createVirtualDisplay(
                    "WARecovery_Video",
                    w, h, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    videoRecorder.getSurface(), null, null
            );

            videoRecorder.start();
            isRecordingVideo = true;
            videoStartTime = System.currentTimeMillis();

            // Update UI to recording state
            GradientDrawable recBg = new GradientDrawable();
            recBg.setColor(Color.parseColor("#6D28D9"));
            recBg.setCornerRadius(60f);
            recBg.setStroke(4, Color.parseColor("#8B5CF6"));
            bubbleRoot.setBackground(recBg);

            timerRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isRecordingVideo) {
                        long elapsedSec = (System.currentTimeMillis() - videoStartTime) / 1000;
                        bubbleText.setText("🎥 " + formatDuration((int) elapsedSec) + " [Stop]");
                        timerHandler.postDelayed(this, 1000);
                    }
                }
            };
            timerHandler.post(timerRunnable);

            Toast.makeText(this, "🎥 Recording Screen Video... Tap bubble to finish!", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Started Screen Video recording: " + currentVideoPath);

        } catch (Exception e) {
            Log.e(TAG, "Error starting video recording", e);
            isRecordingVideo = false;
        }
    }

    private void stopVideoRecording() {
        if (!isRecordingVideo) return;
        isRecordingVideo = false;

        if (timerHandler != null && timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }

        try {
            if (videoRecorder != null) {
                try {
                    videoRecorder.stop();
                    videoRecorder.reset();
                    videoRecorder.release();
                } catch (Exception ignored) {}
                videoRecorder = null;
            }

            if (videoVirtualDisplay != null) {
                videoVirtualDisplay.release();
                videoVirtualDisplay = null;
            }

            long timestamp = System.currentTimeMillis();
            long durationSec = (System.currentTimeMillis() - videoStartTime) / 1000;
            if (durationSec < 1) durationSec = 1;

            if (currentVideoPath != null) {
                File vFile = new File(currentVideoPath);

                // 1. Insert into Messages table
                dbHelper.insertMessage(
                        "Screen Recording",
                        "🎥 Screen Video (" + durationSec + "s)",
                        "video",
                        timestamp,
                        "received",
                        null,
                        false,
                        true,
                        null,
                        currentVideoPath,
                        "vid_" + timestamp
                );

                // 2. Insert into Media table
                dbHelper.insertMedia(
                        "Screen Recording",
                        "video",
                        currentVideoPath,
                        "wa_video_" + timestamp + ".mp4",
                        vFile.length(),
                        "video/mp4",
                        null,
                        timestamp,
                        true
                );

                // 3. Save copy to Phone Gallery (Movies/WARecovery)
                try {
                    File pubDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "WARecovery");
                    if (!pubDir.exists()) pubDir.mkdirs();
                    File pubFile = new File(pubDir, "wa_video_" + timestamp + ".mp4");

                    InputStream in = new FileInputStream(vFile);
                    OutputStream out = new FileOutputStream(pubFile);
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    in.close();
                    out.close();

                    android.media.MediaScannerConnection.scanFile(
                            this,
                            new String[]{pubFile.getAbsolutePath()},
                            new String[]{"video/mp4"},
                            null
                    );
                } catch (Exception ignored) {}

                // 4. Notify Web Layer
                RecoveryBridge bridge = RecoveryBridge.getInstance();
                if (bridge != null) {
                    bridge.onNativeEvent("newMessage", "Screen Recording");
                    bridge.onNativeEvent("mediaRecovered", "Screen Recording");
                }

                Toast.makeText(this, "🎥 Screen Video saved to Gallery!", Toast.LENGTH_LONG).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error stopping video recording", e);
        } finally {
            resetBubbleUI();
        }
    }

    // =============================================
    // AUDIO RECORDING (AAC 44.1kHz)
    // =============================================

    private void toggleAudioRecording() {
        if (isRecording) {
            stopAudioRecording();
        } else {
            startAudioRecording();
        }
    }

    private void startAudioRecording() {
        try {
            File dir = new File(getFilesDir(), "voice_backup");
            if (!dir.exists()) dir.mkdirs();

            long timestamp = System.currentTimeMillis();
            currentAudioPath = new File(dir, "voice_spy_" + timestamp + ".m4a").getAbsolutePath();

            audioRecorder = new MediaRecorder();
            audioRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            audioRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            audioRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            audioRecorder.setAudioSamplingRate(44100);
            audioRecorder.setAudioEncodingBitRate(128000);
            audioRecorder.setOutputFile(currentAudioPath);
            audioRecorder.prepare();
            audioRecorder.start();

            isRecording = true;
            recordStartTime = System.currentTimeMillis();

            if (menuCard != null) menuCard.setVisibility(View.GONE);

            // Update UI to recording state
            GradientDrawable recBg = new GradientDrawable();
            recBg.setColor(Color.parseColor("#EF4444"));
            recBg.setCornerRadius(60f);
            recBg.setStroke(4, Color.parseColor("#DC2626"));
            bubbleRoot.setBackground(recBg);

            timerRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isRecording) {
                        long elapsedSec = (System.currentTimeMillis() - recordStartTime) / 1000;
                        bubbleText.setText("🔴 " + formatDuration((int) elapsedSec) + " [Stop]");
                        timerHandler.postDelayed(this, 1000);
                    }
                }
            };
            timerHandler.post(timerRunnable);

            Toast.makeText(this, "🔴 Recording Audio... Tap bubble to finish!", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Started spy audio recording: " + currentAudioPath);

        } catch (Exception e) {
            Log.e(TAG, "Error starting spy audio recording", e);
            Toast.makeText(this, "Could not start audio recorder", Toast.LENGTH_SHORT).show();
            isRecording = false;
        }
    }

    private void stopAudioRecording() {
        if (!isRecording) return;
        isRecording = false;

        if (timerHandler != null && timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }

        try {
            if (audioRecorder != null) {
                audioRecorder.stop();
                audioRecorder.reset();
                audioRecorder.release();
                audioRecorder = null;
            }

            long durationSec = (System.currentTimeMillis() - recordStartTime) / 1000;
            if (durationSec < 1) durationSec = 1;

            if (currentAudioPath != null && new File(currentAudioPath).exists()) {
                long timestamp = System.currentTimeMillis();

                // 1. Insert into Voice Notes Table
                dbHelper.insertVoiceNote("Captured Voice", currentAudioPath, (int) durationSec, timestamp, true);

                // 2. Insert into Messages Table
                dbHelper.insertMessage(
                        "Captured Voice",
                        "🎙️ View-Once Voice Note (" + durationSec + "s)",
                        "voice",
                        timestamp,
                        "received",
                        null,
                        false,
                        true,
                        null,
                        currentAudioPath,
                        "voice_" + timestamp
                );

                // 3. Notify Web Layer
                RecoveryBridge bridge = RecoveryBridge.getInstance();
                if (bridge != null) {
                    bridge.onNativeEvent("newMessage", "Captured Voice");
                    bridge.onNativeEvent("voiceRecovered", "Captured Voice");
                }

                Toast.makeText(this, "✅ Audio captured & saved!", Toast.LENGTH_SHORT).show();
                Log.i(TAG, "Successfully captured audio to: " + currentAudioPath);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error stopping audio recording", e);
        } finally {
            resetBubbleUI();
        }
    }

    private void resetBubbleUI() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#111B21"));
        bg.setCornerRadius(60f);
        bg.setStroke(3, Color.parseColor("#00A884"));
        bubbleRoot.setBackground(bg);
        bubbleText.setText("WA Assistant");
    }

    private String formatDuration(int totalSeconds) {
        int m = totalSeconds / 60;
        int s = totalSeconds % 60;
        return String.format("%02d:%02d", m, s);
    }

    // =============================================
    // NOTIFICATION
    // =============================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Floating Capture Assistant",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows floating capture bubble over WhatsApp");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildForegroundNotification() {
        Intent stopIntent = new Intent(this, FloatingAssistantService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("🛡️ WA Floating Assistant Active")
                .setContentText("Tap to capture View-Once voice notes, screenshots & videos")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close Bubble", pStop)
                .setOngoing(true)
                .build();
    }
}
