package com.cikelab.spelling;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.cikelab.spelling.StudyPlanner.NEVER;

final class StudyStore extends SQLiteOpenHelper {
    private static final String[] FIELDS = {"word", "status", "viewed", "first_pass",
            "due", "unfamiliar", "reviewed"};

    StudyStore(Context context) {
        super(context.getApplicationContext(), "study-v1.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE progress (book TEXT NOT NULL, word TEXT NOT NULL, "
                + "status INTEGER NOT NULL, viewed INTEGER NOT NULL, first_pass INTEGER NOT NULL, "
                + "due INTEGER NOT NULL, "
                + "unfamiliar INTEGER NOT NULL, reviewed INTEGER NOT NULL, PRIMARY KEY(book, word))");
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported database upgrade");
    }

    Map<String, StudyPlanner.Progress> progress(WordBook book) {
        Map<String, StudyPlanner.Progress> result = new HashMap<>();
        try (Cursor rows = getReadableDatabase().query("progress", FIELDS, "book=?",
                new String[]{book.name()}, null, null, null)) {
            while (rows.moveToNext()) {
                result.put(rows.getString(0), read(rows));
            }
        }
        return result;
    }

    private StudyPlanner.Progress entry(WordBook book, String word) {
        try (Cursor rows = getReadableDatabase().query("progress", FIELDS, "book=? AND word=?",
                new String[]{book.name(), word}, null, null, null)) {
            return rows.moveToFirst() ? read(rows) : new StudyPlanner.Progress();
        }
    }

    private StudyPlanner.Progress read(Cursor row) {
        StudyPlanner.Progress result = new StudyPlanner.Progress();
        result.status = StudyPlanner.Status.values()[row.getInt(1)];
        result.viewed = row.getLong(2);
        result.firstPass = row.getLong(3);
        result.due = row.getLong(4);
        result.unfamiliarPasses = row.getInt(5);
        result.reviewedPasses = row.getInt(6);
        return result;
    }

    private void write(WordBook book, String word, StudyPlanner.Progress entry) {
        ContentValues values = new ContentValues();
        values.put("book", book.name());
        values.put("word", word);
        values.put("status", entry.status.ordinal());
        values.put("viewed", entry.viewed);
        values.put("first_pass", entry.firstPass);
        values.put("due", entry.due);
        values.put("unfamiliar", entry.unfamiliarPasses);
        values.put("reviewed", entry.reviewedPasses);
        getWritableDatabase().replaceOrThrow("progress", null, values);
    }

    void viewed(WordBook book, String word, long day) {
        StudyPlanner.Progress entry = entry(book, word);
        if (entry.viewed == NEVER) {
            entry.viewed = day;
            write(book, word, entry);
        }
    }

    void mark(WordBook book, String word, StudyPlanner.Status status, long day, int pass) {
        StudyPlanner.Progress entry = entry(book, word);
        StudyPlanner.mark(entry, status, day, pass, false);
        write(book, word, entry);
    }

    /** 第二至第五轮里在学习页或已熟记/待复习列表标记，也算本轮已复习。 */
    void review(WordBook book, String word, StudyPlanner.Status status, long day) {
        Stage stage = stage(book);
        StudyPlanner.Progress entry = entry(book, word);
        StudyPlanner.mark(entry, status, day, stage.pass, false);
        if (stage.pass > 1 && !stage.finished) {
            entry.reviewedPasses |= StudyPlanner.bit(stage.pass);
        }
        write(book, word, entry);
    }

    void finishQuestion(StudySession session, String word, StudyPlanner.Status status, long day) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            StudyPlanner.Progress entry = entry(session.book, word);
            StudyPlanner.mark(entry, status, day, session.pass, true);
            if (session.kind == StudyPlanner.Kind.NEW && entry.firstPass == NEVER) {
                entry.firstPass = day;
            }
            if (session.kind != StudyPlanner.Kind.EXAM && session.pass > 1
                    && !stage(session.book).finished) {
                entry.reviewedPasses |= StudyPlanner.bit(session.pass);
            }
            write(session.book, word, entry);
            save(session);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    int quota() {
        return Integer.parseInt(value("quota", Integer.toString(StudyPlanner.DEFAULT_NEW_WORDS)));
    }

    void quota(int count) {
        put("quota", Integer.toString(count));
    }

    boolean learnOnWeekends() {
        return Boolean.parseBoolean(value("learn_on_weekends", "true"));
    }

    void learnOnWeekends(boolean enabled) {
        put("learn_on_weekends", Boolean.toString(enabled));
    }

    List<String> dictionaryHistory() {
        List<String> result = new ArrayList<>();
        try {
            JSONArray history = new JSONArray(value("dictionary_history", "[]"));
            for (int i = 0; i < history.length(); i++) {
                result.add(history.getString(i));
            }
        } catch (JSONException invalidHistory) {
            return new ArrayList<>();
        }
        return result;
    }

    void recordDictionaryQuery(String query) {
        List<String> history = dictionaryHistory();
        history.removeIf(previous -> Practice.matches(previous, query));
        history.add(0, query);
        put("dictionary_history", new JSONArray(StudyPlanner.take(history, 20)).toString());
    }

    /** 生词本星标：实时测试后标记不会的词，可随时去除。存于 settings，免去升级数据表。 */
    Set<String> starred(WordBook book) {
        Set<String> result = new LinkedHashSet<>();
        try {
            JSONArray stars = new JSONArray(value("starred." + book.name(), "[]"));
            for (int i = 0; i < stars.length(); i++) {
                result.add(stars.getString(i));
            }
        } catch (JSONException invalidStars) {
            return new LinkedHashSet<>();
        }
        return result;
    }

    void star(WordBook book, String word, boolean on) {
        Set<String> stars = starred(book);
        if (on) {
            stars.add(word);
        } else {
            stars.remove(word);
        }
        put("starred." + book.name(), new JSONArray(stars).toString());
    }

    WordBook selectedBook() {
        return WordBook.valueOf(value("selected_book", WordBook.CET6.name()));
    }

    void selectedBook(WordBook book) {
        put("selected_book", book.name());
    }

    static final class Stage {
        final int pass;
        final boolean finished;

        Stage(int pass, boolean finished) {
            this.pass = pass;
            this.finished = finished;
        }
    }

    Stage stage(WordBook book) {
        try {
            JSONObject json = new JSONObject(value("stage." + book.name(), "{}"));
            return new Stage(json.optInt("pass", 1), json.optBoolean("finished"));
        } catch (JSONException error) {
            throw new IllegalStateException("Invalid stored study stage", error);
        }
    }

    void stage(WordBook book, Stage stage) {
        try {
            JSONObject json = new JSONObject();
            json.put("pass", stage.pass);
            json.put("finished", stage.finished);
            put("stage." + book.name(), json.toString());
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    StudySession session(WordBook book, boolean exam) {
        String raw = value(sessionKey(book, exam), null);
        return raw == null ? null : StudySession.decode(raw);
    }

    void save(StudySession session) {
        put(sessionKey(session.book, session.kind == StudyPlanner.Kind.EXAM), session.encode());
    }

    void clear(StudySession session) {
        getWritableDatabase().delete("settings", "key=?", new String[]{
                sessionKey(session.book, session.kind == StudyPlanner.Kind.EXAM)});
    }

    private String sessionKey(WordBook book, boolean exam) {
        return "session." + book.name() + (exam ? ".exam" : ".daily");
    }

    private String value(String key, String fallback) {
        try (Cursor row = getReadableDatabase().query("settings", new String[]{"value"}, "key=?",
                new String[]{key}, null, null, null)) {
            return row.moveToFirst() ? row.getString(0) : fallback;
        }
    }

    private void put(String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", value);
        getWritableDatabase().replaceOrThrow("settings", null, values);
    }
}
