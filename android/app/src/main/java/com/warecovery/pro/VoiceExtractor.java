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
import java.util.List;

/**
 * Voice Note Extractor that scans and manages WhatsApp voice messages.
 * Extracts .opus voice files, reads duration metadata, and backs them up.
 */
public class VoiceExtractor {

    private static final String TAG = "WARecovery_Voice";

    private static final String[] VOICE_NOTE_PATHS = {
            "/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
            "/WhatsApp/Media/WhatsApp Voice Notes",
            "/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio",
            "/WhatsApp/Media/WhatsApp Audio"
    };

    private final android.content.Context context;
    private final DatabaseHelper dbHelper;
    private final String backupDir;

    public VoiceExtractor(android.content.Context context) {
        this.context = context;
        this.dbHelper = DatabaseHelper.getInstance(context);

        File backupFile = new File(context.getFilesDir(), "voice_backup");
        if (!backupFile.exists()) backupFile.mkdirs();
        this.backupDir = backupFile.getAbsolutePath();
    }

    /**
     * Scan all WhatsApp voice note directories and back up any new files.
     */
    public int scanAndExtract() {
        int count = 0;

        for (String path : VOICE_NOTE_PATHS) {
            String fullPath = Environment.getExternalStorageDirectory() + path;
            File dir = new File(fullPath);

            if (dir.exists() && dir.isDirectory()) {
                count += scanVoiceDirectory(dir);
            }
        }

        Log.i(TAG, "Voice scan complete: " + count + " voice notes found");
        return count;
    }

    private int scanVoiceDirectory(File dir) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;

        for (File file : files) {
            if (file.isDirectory()) {
                count += scanVoiceDirectory(file);
            } else if (isVoiceFile(file)) {
                processVoiceFile(file);
                count++;
            }
        }
        return count;
    }

    private void processVoiceFile(File file) {
        try {
            // Get duration using MediaMetadataRetriever
            int duration = getAudioDuration(file);

            // Back up the file
            String backupPath = backupVoiceFile(file);
            if (backupPath == null) return;

            // Try to determine contact from parent directory structure
            String contact = extractContactFromPath(file);

            // Store in database
            dbHelper.insertVoiceNote(
                    contact,
                    backupPath,
                    duration,
                    file.lastModified(),
                    false
            );

            Log.d(TAG, "Processed voice note: " + file.getName() + " (duration: " + duration + "s)");

        } catch (Exception e) {
            Log.e(TAG, "Error processing voice file: " + file.getName(), e);
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
        } catch (Exception e) {
            Log.w(TAG, "Could not extract duration for: " + file.getName());
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
        return 0;
    }

    /**
     * Extract a contact name from the file path structure.
     * WhatsApp voice notes are organized in timestamped folders, not by contact.
     */
    private String extractContactFromPath(File file) {
        // WhatsApp stores voice notes in numbered directories (e.g., /Voice Notes/202308/)
        // We can't determine the contact from the path alone.
        // The contact association happens via the NotificationListener when the voice is received.
        return null;
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
