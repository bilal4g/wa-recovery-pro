package com.warecovery.pro;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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

import java.io.File;

/**
 * Floating Capture Assistant Overlay Service.
 * Draws a floating bubble over other apps (WhatsApp) allowing users to
 * record audio in real-time while playing View-Once voice notes or videos.
 */
@SuppressWarnings("deprecation")
public class FloatingAssistantService extends Service {

    private static final String TAG = "WARecovery_Floating";
    private static final String CHANNEL_ID = "wa_floating_assistant_channel";
    private static final int NOTIF_ID = 2001;

    public static final String ACTION_START = "START_FLOATING_ASSISTANT";
    public static final String ACTION_STOP = "STOP_FLOATING_ASSISTANT";

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
    private MediaRecorder mediaRecorder;
    private String currentAudioPath;
    private long recordStartTime = 0;
    private Handler timerHandler;
    private Runnable timerRunnable;

    private DatabaseHelper dbHelper;

    @Override
    public void onCreate() {
        super.onCreate();
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

        try {
            startForeground(NOTIF_ID, buildForegroundNotification());
        } catch (Exception e) {
            Log.e(TAG, "Failed to startForeground: " + e.getMessage());
        }

        try {
            showFloatingBubble();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show floating bubble: " + e.getMessage());
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Floating Capture Assistant",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Floating bubble to capture View-Once voice notes & media");
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating notification channel", e);
        }
    }

    private Notification buildForegroundNotification() {
        try {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }

            return builder
                    .setContentTitle("WA Recovery Pro — Floating Assistant")
                    .setContentText("Floating capture bubble is active over WhatsApp")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build();
        } catch (Exception e) {
            Log.e(TAG, "Error building notification", e);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return new Notification.Builder(this, CHANNEL_ID).build();
            }
            return new Notification();
        }
    }

    @SuppressLint({"ClickableViewAccessibility", "SetTextI18n"})
    private void showFloatingBubble() {
        if (floatingView != null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted, stopping service");
            stopSelf();
            return;
        }

        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
            );

            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 40;
            params.y = 300;

            // Build UI programmatically
            floatingView = buildOverlayLayout();
            windowManager.addView(floatingView, params);
            Log.i(TAG, "Floating bubble added to WindowManager");
        } catch (Exception e) {
            Log.e(TAG, "Error adding floating bubble to WindowManager", e);
            stopSelf();
        }
    }

    @SuppressLint({"ClickableViewAccessibility", "SetTextI18n"})
    private View buildOverlayLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.START);

        // 1. Floating Bubble Pill
        bubbleRoot = new LinearLayout(this);
        bubbleRoot.setOrientation(LinearLayout.HORIZONTAL);
        bubbleRoot.setGravity(Gravity.CENTER_VERTICAL);
        bubbleRoot.setPadding(24, 16, 24, 16);

        GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setColor(Color.parseColor("#1B2A38"));
        bubbleBg.setCornerRadius(60f);
        bubbleBg.setStroke(3, Color.parseColor("#00A884"));
        bubbleRoot.setBackground(bubbleBg);
        bubbleRoot.setElevation(16f);

        ImageView icon = new ImageView(this);
        icon.setImageResource(android.R.drawable.ic_btn_speak_now);
        icon.setColorFilter(Color.parseColor("#00A884"));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(48, 48);
        iconParams.setMarginEnd(12);
        bubbleRoot.addView(icon, iconParams);

        bubbleText = new TextView(this);
        bubbleText.setText("WA Capture");
        bubbleText.setTextColor(Color.WHITE);
        bubbleText.setTextSize(13f);
        bubbleText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bubbleRoot.addView(bubbleText);

        // 2. Expanded Menu Card (Hidden by default)
        menuCard = new LinearLayout(this);
        menuCard.setOrientation(LinearLayout.VERTICAL);
        menuCard.setPadding(24, 20, 24, 20);
        menuCard.setVisibility(View.GONE);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#111B21"));
        cardBg.setCornerRadius(24f);
        cardBg.setStroke(2, Color.parseColor("#2A3942"));
        menuCard.setBackground(cardBg);
        menuCard.setElevation(20f);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                dpToPx(240), LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 12, 0, 0);

        TextView menuTitle = new TextView(this);
        menuTitle.setText("🎙️ View-Once Capture");
        menuTitle.setTextColor(Color.WHITE);
        menuTitle.setTextSize(14f);
        menuTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        menuCard.addView(menuTitle);

        TextView menuDesc = new TextView(this);
        menuDesc.setText("Tap Record, then play the View-Once voice note on speaker.");
        menuDesc.setTextColor(Color.parseColor("#8696A0"));
        menuDesc.setTextSize(11f);
        menuDesc.setPadding(0, 4, 0, 16);
        menuCard.addView(menuDesc);

        // Action Button: Record Audio
        btnRecordAudio = new Button(this);
        btnRecordAudio.setText("🔴 Start Recording");
        btnRecordAudio.setTextColor(Color.WHITE);
        btnRecordAudio.setTextSize(12f);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#EF4444"));
        btnBg.setCornerRadius(16f);
        btnRecordAudio.setBackground(btnBg);
        btnRecordAudio.setPadding(16, 12, 16, 12);
        btnRecordAudio.setOnClickListener(v -> toggleRecording());
        menuCard.addView(btnRecordAudio);

        // Close Menu Button
        btnCloseMenu = new Button(this);
        btnCloseMenu.setText("✕ Close Assistant");
        btnCloseMenu.setTextColor(Color.parseColor("#8696A0"));
        btnCloseMenu.setTextSize(11f);
        btnCloseMenu.setBackgroundColor(Color.TRANSPARENT);
        btnCloseMenu.setOnClickListener(v -> stopSelf());
        menuCard.addView(btnCloseMenu);

        root.addView(bubbleRoot);
        root.addView(menuCard, cardParams);

        // Drag & Click Gesture Handling
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
                            windowManager.updateViewLayout(floatingView, params);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!isDrag) {
                            // Tap on bubble
                            if (isRecording) {
                                toggleRecording(); // Tap to stop recording instantly
                            } else {
                                toggleMenu();
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        return root;
    }

    private void toggleMenu() {
        if (menuCard == null) return;
        if (menuCard.getVisibility() == View.VISIBLE) {
            menuCard.setVisibility(View.GONE);
        } else {
            menuCard.setVisibility(View.VISIBLE);
        }
    }

    @SuppressLint("SetTextI18n")
    private void toggleRecording() {
        if (isRecording) {
            stopAudioRecording();
        } else {
            startAudioRecording();
        }
    }

    @SuppressLint("SetTextI18n")
    private void startAudioRecording() {
        try {
            File dir = new File(getFilesDir(), "recovered_voices");
            if (!dir.exists()) dir.mkdirs();

            long timestamp = System.currentTimeMillis();
            currentAudioPath = new File(dir, "captured_voice_" + timestamp + ".m4a").getAbsolutePath();

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setAudioEncodingBitRate(128000);
            mediaRecorder.setOutputFile(currentAudioPath);
            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecording = true;
            recordStartTime = System.currentTimeMillis();

            // Update UI to recording state
            GradientDrawable recBg = new GradientDrawable();
            recBg.setColor(Color.parseColor("#991B1B"));
            recBg.setCornerRadius(60f);
            recBg.setStroke(4, Color.parseColor("#EF4444"));
            bubbleRoot.setBackground(recBg);

            if (menuCard != null) menuCard.setVisibility(View.GONE);

            btnRecordAudio.setText("⏹️ Stop & Save Audio");
            GradientDrawable stopBg = new GradientDrawable();
            stopBg.setColor(Color.parseColor("#374151"));
            stopBg.setCornerRadius(16f);
            btnRecordAudio.setBackground(stopBg);

            // Timer Updater
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

            Toast.makeText(this, "🎙️ Recording View-Once Audio... Play voice note now!", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Started View-Once audio recording to: " + currentAudioPath);

        } catch (Exception e) {
            Log.e(TAG, "Failed to start audio recording", e);
            Toast.makeText(this, "Microphone permission required for audio recording", Toast.LENGTH_LONG).show();
            isRecording = false;
        }
    }

    @SuppressLint("SetTextI18n")
    private void stopAudioRecording() {
        if (!isRecording) return;
        isRecording = false;

        if (timerHandler != null && timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }

        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            }

            long durationSec = (System.currentTimeMillis() - recordStartTime) / 1000;
            if (durationSec < 1) durationSec = 1;

            // Register into Database as a Recovered Voice Message
            long timestamp = System.currentTimeMillis();
            String contactName = "View-Once Audio";

            dbHelper.insertMessage(
                    contactName,
                    "🎙️ Captured View-Once Audio (" + durationSec + "s)",
                    "voice",
                    timestamp,
                    "received",
                    null,
                    false,
                    true,
                    null,
                    currentAudioPath,
                    "voice_rec_" + timestamp
            );

            // Insert into Media Table
            dbHelper.insertMedia(
                    contactName,
                    "voice",
                    currentAudioPath,
                    "captured_voice_" + timestamp + ".m4a",
                    new File(currentAudioPath).length(),
                    "audio/mp4",
                    null,
                    timestamp,
                    true
            );

            // Notify Web Layer
            RecoveryBridge bridge = RecoveryBridge.getInstance();
            if (bridge != null) {
                bridge.onNativeEvent("newMessage", contactName);
            }

            Toast.makeText(this, "✅ Voice note captured & saved to WA Recovery Pro!", Toast.LENGTH_LONG).show();
            Log.i(TAG, "Successfully captured view-once voice note: " + currentAudioPath);

        } catch (Exception e) {
            Log.e(TAG, "Error stopping audio recording", e);
        }

        // Reset Bubble UI
        GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setColor(Color.parseColor("#1B2A38"));
        bubbleBg.setCornerRadius(60f);
        bubbleBg.setStroke(3, Color.parseColor("#00A884"));
        bubbleRoot.setBackground(bubbleBg);
        bubbleText.setText("WA Capture");

        btnRecordAudio.setText("🔴 Start Recording");
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#EF4444"));
        btnBg.setCornerRadius(16f);
        btnRecordAudio.setBackground(btnBg);
    }

    private String formatDuration(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (isRecording) {
            stopAudioRecording();
        }
        if (floatingView != null && windowManager != null) {
            windowManager.removeView(floatingView);
            floatingView = null;
        }
        Log.i(TAG, "Floating Assistant Service stopped");
    }
}
