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
    private static final int DATABASE_VERSION = 2;

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
                "is_unread INTEGER NOT NULL DEFAULT 1)");
        insert(db, "Für heute",
                "Nur eine kleine Erinnerung …",
                "Du musst heute nichts lösen und keine Antwort finden. Ich wollte dir nur sagen, dass du mir wichtig bist – auch an den schwierigen Tagen.",
                "HEUTE");
        insert(db, "Was ich an dir sehe",
                "Die kleinen Dinge sind oft die größten.",
                "Ich sehe deine Art, wie du dich um andere kümmerst. Dein Lachen, deine Stärke und auch die leisen Seiten an dir. Vieles davon sage ich vermutlich zu selten.",
                "EIN ERSTER BRIEF");
        insert(db, "Kein perfekter Moment",
                "Aber ein ehrlicher.",
                "Zwischen uns war es nicht immer leicht. Diese App soll nichts ungeschehen machen. Sie soll dir nur zeigen, dass ich zuhöre, nachdenke und dass mir etwas an uns liegt.",
                "VON HERZEN");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE letters ADD COLUMN remote_id TEXT");
            db.execSQL("CREATE UNIQUE INDEX letters_remote_id_idx ON letters(remote_id)");
        }
    }

    private void insert(SQLiteDatabase db, String title, String preview, String body, String date) {
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("preview", preview);
        values.put("body", body);
        values.put("date_label", date);
        db.insertOrThrow("letters", null, values);
    }

    List<Letter> getLetters() {
        List<Letter> letters = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "letters", null, null, null, null, null, "id DESC")) {
            while (cursor.moveToNext()) {
                letters.add(new Letter(
                        cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("remote_id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("title")),
                        cursor.getString(cursor.getColumnIndexOrThrow("preview")),
                        cursor.getString(cursor.getColumnIndexOrThrow("body")),
                        cursor.getString(cursor.getColumnIndexOrThrow("date_label")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("is_unread")) == 1
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
                values.put("is_unread", 1);
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
                    db.update("letters", updates, "remote_id = ?", new String[]{letter.id});
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return changed;
    }

    void markRead(long id) {
        ContentValues values = new ContentValues();
        values.put("is_unread", 0);
        getWritableDatabase().update("letters", values, "id = ?", new String[]{String.valueOf(id)});
    }
}
