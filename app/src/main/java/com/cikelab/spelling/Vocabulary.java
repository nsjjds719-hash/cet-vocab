package com.cikelab.spelling;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Vocabulary {
    private final Map<WordBook, Map<String, WordBook.Word>> entries = new EnumMap<>(WordBook.class);
    private final Map<WordBook, List<String>> books = new EnumMap<>(WordBook.class);
    private final Map<WordBook, List<String>> ranked = new EnumMap<>(WordBook.class);
    private final DictionaryLookup dictionary;
    private final JSONObject forms;

    Vocabulary(AssetManager assets) throws IOException, JSONException {
        JSONObject bundle = new JSONObject(read(assets, "catalog.json"));
        forms = bundle.getJSONObject("forms");
        JSONObject levels = bundle.getJSONObject("books");
        for (WordBook book : WordBook.values()) {
            JSONObject level = levels.getJSONObject(book.name().toLowerCase(Locale.ROOT));
            JSONObject rows = level.getJSONObject("entries");
            Map<String, WordBook.Word> catalog = new HashMap<>();
            for (Iterator<String> keys = rows.keys(); keys.hasNext();) {
                String key = keys.next();
                JSONArray row = rows.getJSONArray(key);
                String spelling = row.getString(0);
                List<String> senses = strings(row.getJSONArray(1));
                List<String> variants = strings(row.optJSONArray(3));
                catalog.put(key, new WordBook.Word(spelling, senses,
                        strings(row.getJSONArray(2)), variants));
            }
            entries.put(book, catalog);
            books.put(book, strings(level.getJSONArray("core")));
            ranked.put(book, strings(level.getJSONArray("ranking")));
        }
        dictionary = new DictionaryLookup(new JSONObject(read(assets, "dictionary.json")));
    }

    private List<String> strings(JSONArray source) throws JSONException {
        if (source == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(source.length());
        for (int i = 0; i < source.length(); i++) {
            result.add(source.getString(i));
        }
        return result;
    }

    List<String> words(WordBook book) {
        return books.get(book);
    }

    List<String> ranked(WordBook book) {
        return ranked.get(book);
    }

    WordBook.Word word(WordBook book, String key) {
        WordBook.Word word = entries.get(book).get(key);
        return word == null ? dictionary.reviewWord(key) : word;
    }

    DictionaryLookup dictionary() {
        return dictionary;
    }

    List<String> forms(String key) {
        JSONObject variations = forms.optJSONObject(key);
        List<String> result = new ArrayList<>();
        if (variations != null) {
            form(result, variations, "3", "第三人称单数");
            form(result, variations, "p", "过去式");
            form(result, variations, "d", "过去分词");
            form(result, variations, "i", "现在分词");
            form(result, variations, "s", "复数");
            form(result, variations, "r", "比较级");
            form(result, variations, "t", "最高级");
        }
        return result;
    }

    private void form(List<String> result, JSONObject forms, String code, String title) {
        String value = forms.optString(code);
        if (!value.isEmpty()) {
            result.add(title + "  " + value);
        }
    }

    private String read(AssetManager assets, String name) throws IOException {
        try (InputStream input = assets.open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
