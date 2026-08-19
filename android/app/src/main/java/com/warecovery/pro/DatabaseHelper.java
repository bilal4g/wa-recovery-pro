package com.warecovery.pro;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite database helper for storing recovered messages, media, voice notes, and view-once captures.
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "wa_recovery.db";
    private static final int DATABASE_VERSION = 1;

    // Table names
    public static final String TABLE_MESSAGES = "messages";
    public static final String TABLE_MEDIA = "media_files";
    public static final String TABLE_VOICE_NOTES = "voice_notes";
    public static final String TABLE_VIEW_ONCE = "view_once";

    // Common columns
    public static final String COL_ID = "_id";
    public static final String COL_CONTACT = "contact";
    public static final String COL_TIMESTAMP = "timestamp";
    public static final String COL_IS_DELETED = "is_deleted";

    // Messages columns
    public static final String COL_TEXT = "text";
    public static final String COL_TYPE = "type";
    public static final String COL_DIRECTION = "direction";
    public static final String COL_GROUP_NAME = "group_name";
    public static final String COL_IS_VIEW_ONCE = "is_view_once";
    public static final String COL_MEDIA_URL = "media_url";
    public static final String COL_THUMBNAIL = "thumbnail";
    public static final String COL_NOTIFICATION_KEY = "notification_key";
    public static final String COL_DELETED_AT = "deleted_at";

    // Media columns
    public static final String COL_MEDIA_TYPE = "media_type";
    public static final String COL_FILE_PATH = "file_path";
    public static final String COL_FILE_NAME = "file_name";
    public static final String COL_FILE_SIZE = "file_size";
    public static final String COL_MIME_TYPE = "mime_type";

    // Voice note columns
    public static final String COL_DURATION = "duration";
    public static final String COL_VOICE_PATH = "voice_path";

    private static DatabaseHelper instance;

    public static synchronized DatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Messages table
        db.execSQL("CREATE TABLE " + TABLE_MESSAGES + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_CONTACT + " TEXT NOT NULL, "
                + COL_TEXT + " TEXT, "
                + COL_TYPE + " TEXT DEFAULT 'text', "
                + COL_TIMESTAMP + " INTEGER NOT NULL, "
                + COL_DIRECTION + " TEXT DEFAULT 'received', "
                + COL_GROUP_NAME + " TEXT, "
                + COL_IS_DELETED + " INTEGER DEFAULT 0, "
                + COL_IS_VIEW_ONCE + " INTEGER DEFAULT 0, "
                + COL_MEDIA_URL + " TEXT, "
                + COL_THUMBNAIL + " TEXT, "
                + COL_NOTIFICATION_KEY + " TEXT, "
                + COL_DELETED_AT + " INTEGER"
                + ")");

        // Media files table
        db.execSQL("CREATE TABLE " + TABLE_MEDIA + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_CONTACT + " TEXT, "
                + COL_MEDIA_TYPE + " TEXT NOT NULL, "
                + COL_FILE_PATH + " TEXT NOT NULL, "
                + COL_FILE_NAME + " TEXT, "
                + COL_FILE_SIZE + " INTEGER DEFAULT 0, "
                + COL_MIME_TYPE + " TEXT, "
                + COL_THUMBNAIL + " TEXT, "
                + COL_TIMESTAMP + " INTEGER NOT NULL, "
                + COL_IS_DELETED + " INTEGER DEFAULT 0"
                + ")");

        // Voice notes table
        db.execSQL("CREATE TABLE " + TABLE_VOICE_NOTES + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_CONTACT + " TEXT, "
                + COL_VOICE_PATH + " TEXT NOT NULL, "
                + COL_DURATION + " INTEGER DEFAULT 0, "
                + COL_TIMESTAMP + " INTEGER NOT NULL, "
                + COL_IS_DELETED + " INTEGER DEFAULT 0"
                + ")");

        // View-once captures table
        db.execSQL("CREATE TABLE " + TABLE_VIEW_ONCE + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_CONTACT + " TEXT NOT NULL, "
                + COL_TYPE + " TEXT DEFAULT 'image', "
                + COL_THUMBNAIL + " TEXT, "
                + COL_FILE_PATH + " TEXT, "
                + COL_TIMESTAMP + " INTEGER NOT NULL"
                + ")");

        // Indexes for performance
        db.execSQL("CREATE INDEX idx_messages_contact ON " + TABLE_MESSAGES + " (" + COL_CONTACT + ")");
        db.execSQL("CREATE INDEX idx_messages_timestamp ON " + TABLE_MESSAGES + " (" + COL_TIMESTAMP + ")");
        db.execSQL("CREATE INDEX idx_messages_deleted ON " + TABLE_MESSAGES + " (" + COL_IS_DELETED + ")");
        db.execSQL("CREATE INDEX idx_media_type ON " + TABLE_MEDIA + " (" + COL_MEDIA_TYPE + ")");
        db.execSQL("CREATE INDEX idx_voice_timestamp ON " + TABLE_VOICE_NOTES + " (" + COL_TIMESTAMP + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future migration logic here
    }

    // ---- Message CRUD ----

    public long insertMessage(String contact, String text, String type, long timestamp,
                              String direction, String groupName, boolean isDeleted,
                              boolean isViewOnce, String mediaUrl, String thumbnail,
                              String notificationKey) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_CONTACT, contact);
        values.put(COL_TEXT, text);
        values.put(COL_TYPE, type);
        values.put(COL_TIMESTAMP, timestamp);
        values.put(COL_DIRECTION, direction);
        values.put(COL_GROUP_NAME, groupName);
        values.put(COL_IS_DELETED, isDeleted ? 1 : 0);
        values.put(COL_IS_VIEW_ONCE, isViewOnce ? 1 : 0);
        values.put(COL_MEDIA_URL, mediaUrl);
        values.put(COL_THUMBNAIL, thumbnail);
        values.put(COL_NOTIFICATION_KEY, notificationKey);
        return db.insert(TABLE_MESSAGES, null, values);
    }

    public void markMessageDeleted(String notificationKey) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_IS_DELETED, 1);
        values.put(COL_DELETED_AT, System.currentTimeMillis());
        db.update(TABLE_MESSAGES, values,
                COL_NOTIFICATION_KEY + " = ?", new String[]{notificationKey});
    }

    public JSONArray getMessagesAsJSON(String contact, String filter) throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        String selection = null;
        String[] selectionArgs = null;

        if (contact != null && filter != null) {
            selection = COL_CONTACT + " = ? AND " + COL_IS_DELETED + " = ?";
            selectionArgs = new String[]{contact, filter.equals("deleted") ? "1" : "0"};
        } else if (contact != null) {
            selection = COL_CONTACT + " = ?";
            selectionArgs = new String[]{contact};
        } else if (filter != null && filter.equals("deleted")) {
            selection = COL_IS_DELETED + " = 1";
        }

        Cursor cursor = db.query(TABLE_MESSAGES, null, selection, selectionArgs,
                null, null, COL_TIMESTAMP + " DESC", "500");

        JSONArray result = new JSONArray();
        while (cursor.moveToNext()) {
            JSONObject msg = new JSONObject();
            msg.put("id", cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)));
            msg.put("contact", cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTACT)));
            msg.put("text", cursor.getString(cursor.getColumnIndexOrThrow(COL_TEXT)));
            msg.put("type", cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE)));
            msg.put("timestamp", cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)));
            msg.put("direction", cursor.getString(cursor.getColumnIndexOrThrow(COL_DIRECTION)));
            msg.put("isDeleted", cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_DELETED)) == 1);
            msg.put("isViewOnce", cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_VIEW_ONCE)) == 1);
            msg.put("mediaUrl", cursor.getString(cursor.getColumnIndexOrThrow(COL_MEDIA_URL)));
            msg.put("thumbnail", cursor.getString(cursor.getColumnIndexOrThrow(COL_THUMBNAIL)));
            result.put(msg);
        }
        cursor.close();
        return result;
    }

    // ---- Media CRUD ----

    public long insertMedia(String contact, String mediaType, String filePath,
                            String fileName, long fileSize, String mimeType,
                            String thumbnail, long timestamp, boolean isDeleted) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_CONTACT, contact);
        values.put(COL_MEDIA_TYPE, mediaType);
        values.put(COL_FILE_PATH, filePath);
        values.put(COL_FILE_NAME, fileName);
        values.put(COL_FILE_SIZE, fileSize);
        values.put(COL_MIME_TYPE, mimeType);
        values.put(COL_THUMBNAIL, thumbnail);
        values.put(COL_TIMESTAMP, timestamp);
        values.put(COL_IS_DELETED, isDeleted ? 1 : 0);
        return db.insert(TABLE_MEDIA, null, values);
    }

    // ---- Voice Notes CRUD ----

    public long insertVoiceNote(String contact, String voicePath, int duration,
                                long timestamp, boolean isDeleted) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_CONTACT, contact);
        values.put(COL_VOICE_PATH, voicePath);
        values.put(COL_DURATION, duration);
        values.put(COL_TIMESTAMP, timestamp);
        values.put(COL_IS_DELETED, isDeleted ? 1 : 0);
        return db.insert(TABLE_VOICE_NOTES, null, values);
    }

    // ---- View-Once CRUD ----

    public long insertViewOnce(String contact, String type, String thumbnail,
                               String filePath, long timestamp) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_CONTACT, contact);
        values.put(COL_TYPE, type);
        values.put(COL_THUMBNAIL, thumbnail);
        values.put(COL_FILE_PATH, filePath);
        values.put(COL_TIMESTAMP, timestamp);
        return db.insert(TABLE_VIEW_ONCE, null, values);
    }

    // ---- Statistics ----

    public JSONObject getStats() throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        JSONObject stats = new JSONObject();

        stats.put("totalMessages", getCount(db, TABLE_MESSAGES, null));
        stats.put("deletedRecovered", getCount(db, TABLE_MESSAGES, COL_IS_DELETED + " = 1"));
        stats.put("viewOnceCaptures", getCount(db, TABLE_MESSAGES, COL_IS_VIEW_ONCE + " = 1"));
        stats.put("totalMedia", getCount(db, TABLE_MEDIA, null));
        stats.put("totalVoiceNotes", getCount(db, TABLE_VOICE_NOTES, null));

        return stats;
    }

    private int getCount(SQLiteDatabase db, String table, String where) {
        String query = "SELECT COUNT(*) FROM " + table;
        if (where != null) query += " WHERE " + where;
        Cursor cursor = db.rawQuery(query, null);
        cursor.moveToFirst();
        int count = cursor.getInt(0);
        cursor.close();
        return count;
    }

    // ---- Clear All ----

    public void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_MESSAGES, null, null);
        db.delete(TABLE_MEDIA, null, null);
        db.delete(TABLE_VOICE_NOTES, null, null);
        db.delete(TABLE_VIEW_ONCE, null, null);
    }
}
