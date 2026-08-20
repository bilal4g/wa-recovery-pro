package com.warecovery.pro;

import android.media.MediaMetadataRetriever;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Voice Note Extractor that scans and manages WhatsApp voice messages.
 * Extracts .opus voice files, reads duration metadata, and backs them up.
 */
public class VoiceExtractor {

    private static final String TAG = "WARecovery_Voice";

    private static final String[] VOICE_NOTE_PATHS = {
            "/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
            "/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes",
            "/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio",
            "/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Audio",
            "/WhatsApp/Media/WhatsApp Voice Notes",
            "/WhatsApp Business/Media/WhatsApp Business Voice Notes",
            "/WhatsApp/Media/WhatsApp Audio",
            "/WhatsApp Business/Media/WhatsApp Business Audio"
    };

    private final DatabaseHelper dbHelper;
    private final String backupDir;

    public VoiceExtractor(android.content.Context context) {
        this.dbHelper = DatabaseHelper.getInstance(context);

        File backupFile = new File(context.getFilesDir(), "voice_backup");
        if (!backupFile.exists()) backupFile.mkdirs();
        this.backupDir = backupFile.getAbsolutePath();
    }

    /**
     * Finds the newest .opus voice note on the device (created within last 5 minutes),
     * copies it into private app backup storage, and links it directly to the contact.
     */
    public String extractLatestVoiceForContact(String contact) {
        List<File> allVoiceFiles = new ArrayList<>();

        for (String path : VOICE_NOTE_PATHS) {
            String fullPath = Environment.getExternalStorageDirectory() + path;
            File dir = new File(fullPath);
            if (dir.exists() && dir.isDirectory()) {
                collectVoiceFiles(dir, allVoiceFiles);
            }
        }

        if (allVoiceFiles.isEmpty()) {
            Log.w(TAG, "No voice notes found in WhatsApp directories");
            return null;
        }

        // Sort descending by lastModified (newest first)
        Collections.sort(allVoiceFiles, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

        File newest = allVoiceFiles.get(0);
        long ageMs = System.currentTimeMillis() - newest.lastModified();

        // If file was modified in the last 5 minutes, it belongs to this incoming voice message!
        if (ageMs < 300000) {
            String backupPath = backupVoiceFile(newest);
            if (backupPath != null) {
                int duration = getAudioDuration(newest);
                dbHelper.insertVoiceNote(contact, backupPath, duration, System.currentTimeMillis(), false);
                dbHelper.updateLatestMessageVoiceAudio(contact, backupPath);
                Log.i(TAG, "✅ Successfully captured voice note from " + contact + ": " + backupPath);
                return backupPath;
            }
        }

        return null;
    }

    /**
     * Scan all WhatsApp voice note directories and back up any new files.
     */
    public int scanAndExtract() {
        int count = 0;
        List<File> allFiles = new ArrayList<>();
        for (String path : VOICE_NOTE_PATHS) {
            String fullPath = Environment.getExternalStorageDirectory() + path;
            File dir = new File(fullPath);
            if (dir.exists() && dir.isDirectory()) {
                collectVoiceFiles(dir, allFiles);
            }
        }

        for (File file : allFiles) {
            try {
                int duration = getAudioDuration(file);
                String backupPath = backupVoiceFile(file);
                if (backupPath != null) {
                    dbHelper.insertVoiceNote("Voice Note", backupPath, duration, file.lastModified(), false);
                    count++;
                }
            } catch (Exception ignored) {}
        }
        return count;
    }

    private void collectVoiceFiles(File dir, List<File> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectVoiceFiles(f, result);
            } else if (isVoiceFile(f)) {
                result.add(f);
            }
        }
    }

    /**
     * Get audio duration in seconds using MediaMetadataRetriever.
     */
    private int getAudioDuration(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                return Integer.parseInt(durationStr) / 1000; // Convert ms to seconds
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    retriever.close();
                } else {
                    retriever.release();
                }
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private String backupVoiceFile(File source) {
        try {
            File dest = new File(backupDir, System.currentTimeMillis() + "_" + source.getName());
            copyFile(source, dest);
            return dest.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Failed to backup voice file: " + source.getName(), e);
            return null;
        }
    }

    private void copyFile(File source, File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest);
             FileChannel inChannel = fis.getChannel();
             FileChannel outChannel = fos.getChannel()) {
            inChannel.transferTo(0, inChannel.size(), outChannel);
        }
    }

    private boolean isVoiceFile(File file) {
        if (!file.isFile()) return false;
        String name = file.getName().toLowerCase();
        return name.endsWith(".opus") || name.endsWith(".m4a") ||
               name.endsWith(".ogg") || name.endsWith(".mp3") ||
               name.endsWith(".aac") || name.endsWith(".3gp");
    }

    /**
     * Get all backed up voice files.
     */
    public List<File> getBackedUpVoiceNotes() {
        List<File> files = new ArrayList<>();
        File dir = new File(backupDir);
        if (dir.exists()) {
            File[] contents = dir.listFiles();
            if (contents != null) {
                for (File f : contents) {
                    if (f.isFile()) files.add(f);
                }
            }
        }
        return files;
    }

    /**
     * Get total storage used by backed up voice notes.
     */
    public long getStorageUsed() {
        long total = 0;
        for (File file : getBackedUpVoiceNotes()) {
            total += file.length();
        }
        return total;
    }
}
