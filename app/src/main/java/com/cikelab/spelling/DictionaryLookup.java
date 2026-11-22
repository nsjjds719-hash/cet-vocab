package com.cikelab.spelling;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DictionaryLookup {
    private static final int NO_MATCH = Integer.MAX_VALUE;
    private static final Pattern HAN = Pattern.compile("\\p{sc=Han}+");
    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<Entry, List<List<Entry>>> families = new LinkedHashMap<>();
    private final Map<Entry, List<Form>> inflections = new LinkedHashMap<>();

    static final class Entry {
        final String key;
        final String spelling;
        final Set<String> meanings = new LinkedHashSet<>();
        final Set<String> variants = new LinkedHashSet<>();
        private final String normalizedSpelling;
        private final Set<String> aliases = new LinkedHashSet<>();
        private final Set<String> chinesePhrases = new LinkedHashSet<>();

        private Entry(String key, String spelling) {
            this.key = key;
            this.spelling = spelling;
            normalizedSpelling = searchKey(spelling);
        }

        private void add(String spelling, List<String> senses, List<String> alternate) {
            for (String sense : senses) {
                if (meanings.add(sense)) {
                    Matcher phrases = HAN.matcher(sense);
                    while (phrases.find()) {
                        chinesePhrases.add(phrases.group());
                    }
                }
            }
            for (String form : alternate) {
                if (!Practice.matches(form, this.spelling)) {
                    variants.add(form);
                    aliases.add(searchKey(form));
                }
            }
            aliases.add(searchKey(spelling));
        }

        private int score(String query, boolean chinese) {
            if (chinese) {
                int best = NO_MATCH;
                for (String phrase : chinesePhrases) {
                    if (phrase.equals(query)) {
                        return 0;
                    }
                    if (phrase.startsWith(query)) {
                        best = 1;
                    } else if (phrase.contains(query)) {
                        best = Math.min(best, 2);
                    }
                }
                return best;
            }
            if (normalizedSpelling.equals(query)) {
                return 0;
            }
            if (aliases.contains(query)) {
                return 1;
            }
            int best = NO_MATCH;
            for (String alias : aliases) {
                if (alias.startsWith(query)) {
                    return 3;
                }
                if (alias.contains(query)) {
                    best = 4;
                }
            }
            return best;
        }

    }

    private static final class Match {
        final Entry entry;
        final int score;

        Match(Entry entry, int score) {
            this.entry = entry;
            this.score = score;
        }
    }

    static final class Form {
        final Entry base;
        final String description;

        Form(Entry base, String code) {
            this.base = base;
            switch (code) {
                case "3": description = "第三人称单数"; break;
                case "p": description = "过去式"; break;
                case "d": description = "过去分词"; break;
                case "i": description = "现在分词"; break;
                case "s": description = "复数"; break;
                case "r": description = "比较级"; break;
                case "t": description = "最高级"; break;
                default: throw new IllegalArgumentException("Unknown word form code");
            }
        }

        String label() {
            return base.spelling + " 的" + description;
        }
    }

    DictionaryLookup(JSONObject data) throws JSONException {
        JSONObject terms = data.getJSONObject("terms");
        for (Iterator<String> keys = terms.keys(); keys.hasNext();) {
            String key = keys.next();
            JSONArray row = terms.getJSONArray(key);
            String spelling = row.getString(0);
            Entry entry = new Entry(key, spelling);
            entry.add(spelling, strings(row.getJSONArray(1)), strings(row.getJSONArray(2)));
            entries.put(key, entry);
        }
        JSONObject groups = data.getJSONObject("families");
        for (Iterator<String> keys = groups.keys(); keys.hasNext();) {
            List<Entry> group = new ArrayList<>();
            for (String member : strings(groups.getJSONArray(keys.next()))) {
                group.add(entries.get(member));
            }
            for (Entry member : group) {
                families.computeIfAbsent(member, ignored -> new ArrayList<>()).add(group);
            }
        }
        JSONObject changes = data.getJSONObject("inflections");
        for (Iterator<String> keys = changes.keys(); keys.hasNext();) {
            String key = keys.next();
            JSONArray sources = changes.getJSONArray(key);
            List<Form> forms = new ArrayList<>();
            for (int i = 0; i < sources.length(); i++) {
                JSONArray form = sources.getJSONArray(i);
                forms.add(new Form(entries.get(form.getString(0)), form.getString(1)));
            }
            inflections.put(entries.get(key), forms);
        }
    }

    private List<String> strings(JSONArray array) throws JSONException {
        List<String> list = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            list.add(array.getString(i));
        }
        return list;
    }

    Entry entry(String key) {
        return entries.get(key);
    }

    List<List<Entry>> families(Entry entry) {
        return families.getOrDefault(entry, Collections.emptyList());
    }

    List<Form> forms(Entry entry) {
        return inflections.getOrDefault(entry, Collections.emptyList());
    }

    List<String> meanings(Entry entry) {
        if (!entry.meanings.isEmpty()) {
            return new ArrayList<>(entry.meanings);
        }
        List<String> result = new ArrayList<>();
        for (Form source : forms(entry)) {
            result.add(source.label());
            for (String sense : source.base.meanings) {
                if (!result.contains(sense)) {
                    result.add(sense);
                }
            }
        }
        if (result.isEmpty()) {
            for (List<Entry> family : families(entry)) {
                for (Entry other : family) {
                    if (!other.meanings.isEmpty()) {
                        result.add("同源词  " + other.spelling);
                        result.addAll(other.meanings);
                        return result;
                    }
                }
            }
        }
        return result;
    }

    WordBook.Word reviewWord(String key) {
        Entry entry = entries.get(key);
        return entry == null ? null : new WordBook.Word(entry.spelling, meanings(entry),
                Collections.emptyList(), new ArrayList<>(entry.variants));
    }

    List<Entry> search(String input) {
        String normalized = searchKey(input);
        boolean chinese = HAN.matcher(normalized).find();
        String query = chinese ? SPACES.matcher(normalized).replaceAll("") : normalized;
        List<Entry> result = new ArrayList<>();
        if (query.isEmpty()) {
            return result;
        }
        Map<Entry, Integer> scores = new LinkedHashMap<>();
        List<Entry> exact = new ArrayList<>();
        for (Entry entry : entries.values()) {
            int score = entry.score(query, chinese);
            if (score != NO_MATCH) {
                scores.put(entry, score);
                if (!chinese && score <= 1) {
                    exact.add(entry);
                }
            }
        }
        for (Entry entry : exact) {
            for (List<Entry> family : families(entry)) {
                for (Entry member : family) {
                    scores.merge(member, 2, Math::min);
                }
            }
        }
        List<Match> matches = new ArrayList<>(scores.size());
        scores.forEach((entry, score) -> matches.add(new Match(entry, score)));
        matches.sort(Comparator.comparingInt((Match match) -> match.score)
                .thenComparingInt(match -> match.entry.normalizedSpelling.length())
                .thenComparing(match -> match.entry.normalizedSpelling));
        for (Match match : matches) {
            result.add(match.entry);
        }
        return result;
    }

    Entry suggestedTerm(String query, List<Entry> results) {
        if (results.size() == 1) {
            return results.get(0);
        }
        if (HAN.matcher(query).find()) {
            return null;
        }
        Entry exact = null;
        String normalized = searchKey(query);
        for (Entry entry : results) {
            if (entry.aliases.contains(normalized)) {
                if (exact != null) {
                    return null;
                }
                exact = entry;
            }
        }
        return exact;
    }

    private static String searchKey(String value) {
        return ACCENTS.matcher(Normalizer.normalize(Practice.normalize(value),
                Normalizer.Form.NFD)).replaceAll("");
    }
}
