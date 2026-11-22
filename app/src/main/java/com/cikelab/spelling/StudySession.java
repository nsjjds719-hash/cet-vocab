package com.cikelab.spelling;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class StudySession {
    final WordBook book;
    final StudyPlanner.Kind kind;
    final int pass;
    final List<String> words;
    Practice.Mode mode;
    boolean learning;
    int index;
    int correct;
    boolean revealed;
    boolean skipped;
    boolean hint;
    String input = "";

    StudySession(WordBook book, StudyPlanner.Kind kind, int pass, List<String> words,
                 Practice.Mode mode) {
        this.book = book;
        this.kind = kind;
        this.pass = pass;
        this.words = new ArrayList<>(words);
        this.mode = mode;
        learning = kind == StudyPlanner.Kind.NEW;
    }

    void beginTest(int viewedCount) {
        words.subList(viewedCount, words.size()).clear();
        Collections.shuffle(words);
        index = 0;
        learning = false;
    }

    boolean complete() {
        return !learning && index >= words.size();
    }

    String current() {
        return words.get(index);
    }

    void next(boolean correctAnswer) {
        if (correctAnswer) {
            correct++;
        }
        index++;
        revealed = false;
        skipped = false;
        hint = false;
        input = "";
    }

    String encode() {
        try {
            return new JSONObject()
                    .put("book", book.name()).put("kind", kind.name()).put("pass", pass)
                    .put("words", new JSONArray(words)).put("mode", mode.name())
                    .put("learning", learning).put("index", index).put("correct", correct)
                    .put("revealed", revealed).put("skipped", skipped).put("hint", hint)
                    .put("input", input).toString();
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    static StudySession decode(String raw) {
        try {
            JSONObject json = new JSONObject(raw);
            List<String> words = new ArrayList<>();
            JSONArray array = json.getJSONArray("words");
            for (int i = 0; i < array.length(); i++) {
                words.add(array.getString(i));
            }
            StudySession session = new StudySession(WordBook.valueOf(json.getString("book")),
                    StudyPlanner.Kind.valueOf(json.getString("kind")), json.getInt("pass"), words,
                    Practice.Mode.valueOf(json.getString("mode")));
            session.learning = json.getBoolean("learning");
            session.index = json.getInt("index");
            session.correct = json.getInt("correct");
            session.revealed = json.getBoolean("revealed");
            session.skipped = json.getBoolean("skipped");
            session.hint = json.getBoolean("hint");
            session.input = json.getString("input");
            return session;
        } catch (JSONException error) {
            throw new IllegalStateException("Invalid stored study session", error);
        }
    }
}
