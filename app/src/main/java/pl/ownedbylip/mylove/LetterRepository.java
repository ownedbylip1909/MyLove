package pl.ownedbylip.mylove;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

final class LetterRepository extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "letters.db";
    private static final int DATABASE_VERSION = 5;

    LetterRepository(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE letters (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "remote_id TEXT UNIQUE," +
                "title TEXT NOT NULL," +
                "preview TEXT NOT NULL," +
                "body TEXT NOT NULL," +
                "date_label TEXT NOT NULL," +
                "published_at TEXT NOT NULL," +
                "is_archived INTEGER NOT NULL DEFAULT 0," +
                "is_unread INTEGER NOT NULL DEFAULT 1)");
        createAttachmentsTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE letters ADD COLUMN remote_id TEXT");
            db.execSQL("CREATE UNIQUE INDEX letters_remote_id_idx ON letters(remote_id)");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE letters ADD COLUMN published_at TEXT");
            db.execSQL("ALTER TABLE letters ADD COLUMN is_archived INTEGER NOT NULL DEFAULT 0");
            db.execSQL("UPDATE letters SET published_at = " +
                    "printf('2000-01-01T00:00:%02dZ', id) WHERE published_at IS NULL");
        }
        if (oldVersion < 4) {
            createAttachmentsTable(db);
        }
        if (oldVersion < 5) {
            db.delete("letters", "remote_id IS NULL", null);
        }
    }

    private void createAttachmentsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS letter_attachments (" +
                "storage_path TEXT PRIMARY KEY," +
                "letter_remote_id TEXT NOT NULL," +
                "mime_type TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS attachment_letter_idx " +
                "ON letter_attachments(letter_remote_id)");
    }

    List<Letter> getLetters() {
        List<Letter> letters = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "letters", null, "is_archived = 0", null, null, null,
                "published_at DESC, id DESC")) {
            while (cursor.moveToNext()) {
                String remoteId = cursor.getString(cursor.getColumnIndexOrThrow("remote_id"));
                letters.add(new Letter(
                        cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        remoteId,
                        cursor.getString(cursor.getColumnIndexOrThrow("title")),
                        cursor.getString(cursor.getColumnIndexOrThrow("preview")),
                        cursor.getString(cursor.getColumnIndexOrThrow("body")),
                        cursor.getString(cursor.getColumnIndexOrThrow("date_label")),
                        cursor.getString(cursor.getColumnIndexOrThrow("published_at")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("is_unread")) == 1,
                        getAttachments(remoteId)
                ));
            }
        }
        return letters;
    }

    int upsertRemoteLetters(List<SupabaseClient.RemoteLetter> remoteLetters) {
        SQLiteDatabase db = getWritableDatabase();
        int changed = 0;
        db.beginTransaction();
        try {
            for (SupabaseClient.RemoteLetter letter : remoteLetters) {
                ContentValues values = new ContentValues();
                values.put("remote_id", letter.id);
                values.put("title", letter.title);
                values.put("preview", letter.preview);
                values.put("body", letter.body);
                values.put("date_label", letter.dateLabel);
                values.put("published_at", letter.publishedAt);
                values.put("is_unread", letter.read ? 0 : 1);
                long result = db.insertWithOnConflict(
                        "letters", null, values, SQLiteDatabase.CONFLICT_IGNORE);
                if (result != -1) {
                    changed++;
                } else {
                    ContentValues updates = new ContentValues();
                    updates.put("title", letter.title);
                    updates.put("preview", letter.preview);
                    updates.put("body", letter.body);
                    updates.put("date_label", letter.dateLabel);
                    updates.put("published_at", letter.publishedAt);
                    if (letter.read) {
                        updates.put("is_unread", 0);
                    }
                    db.update("letters", updates, "remote_id = ?", new String[]{letter.id});
                }
                db.delete("letter_attachments", "letter_remote_id = ?",
                        new String[]{letter.id});
                for (SupabaseClient.RemoteAttachment attachment : letter.attachments) {
                    ContentValues attachmentValues = new ContentValues();
                    attachmentValues.put("storage_path", attachment.storagePath);
                    attachmentValues.put("letter_remote_id", letter.id);
                    attachmentValues.put("mime_type", attachment.mimeType);
                    db.insertWithOnConflict("letter_attachments", null,
                            attachmentValues, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            reconcileRemoteLetters(db, remoteLetters);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return changed;
    }

    private List<Letter.Attachment> getAttachments(String remoteId) {
        List<Letter.Attachment> attachments = new ArrayList<>();
        if (remoteId == null) {
            return attachments;
        }
        try (Cursor cursor = getReadableDatabase().query(
                "letter_attachments",
                new String[]{"storage_path", "mime_type"},
                "letter_remote_id = ?", new String[]{remoteId},
                null, null, "storage_path")) {
            while (cursor.moveToNext()) {
                attachments.add(new Letter.Attachment(
                        cursor.getString(0), cursor.getString(1)));
            }
        }
        return attachments;
    }

    private void reconcileRemoteLetters(
            SQLiteDatabase db, List<SupabaseClient.RemoteLetter> remoteLetters) {
        if (remoteLetters.isEmpty()) {
            db.delete("letter_attachments", null, null);
            db.delete("letters", "remote_id IS NOT NULL", null);
            return;
        }
        StringBuilder placeholders = new StringBuilder();
        String[] ids = new String[remoteLetters.size()];
        for (int i = 0; i < remoteLetters.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
            ids[i] = remoteLetters.get(i).id;
        }
        String missing = " NOT IN (" + placeholders + ")";
        db.delete("letter_attachments", "letter_remote_id" + missing, ids);
        db.delete("letters", "remote_id IS NOT NULL AND remote_id" + missing, ids);
    }

    void markRead(long id) {
        ContentValues values = new ContentValues();
        values.put("is_unread", 0);
        getWritableDatabase().update("letters", values, "id = ?", new String[]{String.valueOf(id)});
    }

    void archive(long id) {
        ContentValues values = new ContentValues();
        values.put("is_archived", 1);
        getWritableDatabase().update(
                "letters", values, "id = ?", new String[]{String.valueOf(id)});
    }

    void delete(long id) {
        getWritableDatabase().delete(
                "letters", "id = ?", new String[]{String.valueOf(id)});
    }
}
