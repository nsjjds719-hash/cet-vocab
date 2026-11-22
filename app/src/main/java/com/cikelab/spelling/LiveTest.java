package com.cikelab.spelling;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 临考·生词本的实时测试：每轮从生词本随机抽词，不改动复习进度，可无限循环。 */
final class LiveTest {
    static final int ROUND_SIZE = 10;

    final WordBook book;
    final Practice.Mode mode;
    /** 当前一轮；复用默写会话的作答状态，但从不写入数据库。 */
    StudySession session;
    final List<Boolean> results = new ArrayList<>();
    private List<String> deck = new ArrayList<>();
    int round;

    LiveTest(WordBook book, Practice.Mode mode) {
        this.book = book;
        this.mode = mode;
    }

    /** 按“一副洗好的牌”出题：牌抽完再洗，保证各词轮流出现。返回 false 表示生词本已空。 */
    boolean nextRound(List<String> pool) {
        if (pool.isEmpty()) {
            return false;
        }
        List<String> words = deal(pool, deck, ROUND_SIZE);
        session = new StudySession(book, StudyPlanner.Kind.EXAM, 1, words, mode);
        results.clear();
        round++;
        return true;
    }

    static List<String> deal(List<String> pool, List<String> deck, int size) {
        Set<String> available = new HashSet<>(pool);
        deck.removeIf(word -> !available.contains(word));
        List<String> words = new ArrayList<>();
        while (words.size() < size && !deck.isEmpty()) {
            words.add(deck.remove(0));
        }
        if (words.size() < size) {
            deck.clear();
            for (String word : pool) {
                if (!words.contains(word)) {
                    deck.add(word);
                }
            }
            Collections.shuffle(deck);
            while (words.size() < size && !deck.isEmpty()) {
                words.add(deck.remove(0));
            }
        }
        return words;
    }

    boolean roundComplete() {
        return session.complete();
    }

    void answer(boolean correct) {
        results.add(correct);
        session.next(correct);
    }

    String encode() {
        try {
            return new JSONObject().put("book", book.name()).put("mode", mode.name())
                    .put("round", round).put("deck", new JSONArray(deck))
                    .put("results", new JSONArray(results))
                    .put("session", session.encode()).toString();
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    static LiveTest decode(String raw) {
        try {
            JSONObject json = new JSONObject(raw);
            LiveTest test = new LiveTest(WordBook.valueOf(json.getString("book")),
                    Practice.Mode.valueOf(json.getString("mode")));
            test.round = json.getInt("round");
            JSONArray deck = json.getJSONArray("deck");
            for (int i = 0; i < deck.length(); i++) {
                test.deck.add(deck.getString(i));
            }
            JSONArray results = json.getJSONArray("results");
            for (int i = 0; i < results.length(); i++) {
                test.results.add(results.getBoolean(i));
            }
            test.session = StudySession.decode(json.getString("session"));
            return test;
        } catch (JSONException error) {
            throw new IllegalStateException("Invalid live test state", error);
        }
    }
}
