package com.cikelab.spelling;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import static com.cikelab.spelling.StudyPlanner.Kind.*;
import static com.cikelab.spelling.StudyPlanner.Status.*;

public final class StudyRulesTest {
    private static final long FRIDAY = LocalDate.of(2026, 11, 20).toEpochDay();
    private static final long SATURDAY = FRIDAY + 1;
    private static final long SUNDAY = FRIDAY + 2;
    private static final long MONDAY = FRIDAY + 3;

    public static void main(String[] args) throws Exception {
        dailySchedule();
        reviewSelection();
        spellingAndResume();
        packagedBooks(args[0]);
        System.out.println("Daily schedule, review selection, spelling, resume and bundled data passed.");
    }

    private static void dailySchedule() {
        List<String> words = words(80);
        Map<String, StudyPlanner.Progress> progress = new HashMap<>();
        int quota = StudyPlanner.DEFAULT_NEW_WORDS;
        check(quota == 35, "Default weekday quota");
        check(StudyPlanner.newWords(words, progress, FRIDAY, quota, false).size() == 35, "Friday quota");
        check(StudyPlanner.newWords(words, progress, SATURDAY, quota, false).isEmpty(),
                "Saturday with weekend learning disabled");
        check(StudyPlanner.newWords(words, progress, SUNDAY, quota, false).isEmpty(),
                "Sunday with weekend learning disabled");
        check(StudyPlanner.newWords(words, progress, MONDAY, quota, false).size() == 35, "Monday quota");
        check(StudyPlanner.newWords(words, progress, SATURDAY, quota, true).size() == 35,
                "Saturday with weekend learning enabled");
        check(StudyPlanner.newWords(words, progress, SUNDAY, quota, true).size() == 35,
                "Sunday with weekend learning enabled");
        StudyPlanner.Progress seenOnSaturday = new StudyPlanner.Progress();
        seenOnSaturday.viewed = SATURDAY;
        seenOnSaturday.firstPass = SATURDAY;
        progress.put(words.get(1), seenOnSaturday);
        check(StudyPlanner.firstPassCount(words, progress) == 1,
                "Previously completed entries count towards expanded catalog progress");
        check(StudyPlanner.newWords(words, progress, SATURDAY, 1, true).isEmpty(),
                "Weekend uses the configured quota");
        check(StudyPlanner.newWords(words, progress, SUNDAY, 1, true).size() == 1,
                "Weekend quota resets at local midnight");
        StudyPlanner.Progress weak = new StudyPlanner.Progress();
        weak.viewed = FRIDAY;
        progress.put(words.get(0), weak);
        StudyPlanner.mark(weak, REVIEW, FRIDAY, 1, false);
        check(StudyPlanner.viewedToday(progress, FRIDAY) == 1, "New quota counts unique views");
        check(StudyPlanner.review(progress, FRIDAY, false).isEmpty(), "Due starts the next local date");
        check(StudyPlanner.review(progress, FRIDAY, true).size() == 1, "Exam list updates immediately");
        check(StudyPlanner.review(progress, SATURDAY, false).size() == 1, "Due on Saturday");
        StudyPlanner.mark(weak, REVIEW, SATURDAY, 1, false);
        check(StudyPlanner.review(progress, SATURDAY, false).size() == 1, "Repeated mark preserves due date");
        check(StudyPlanner.review(progress, MONDAY, false).size() == 1, "Overdue on Monday");
        StudyPlanner.mark(weak, REVIEW, MONDAY, 2, true);
        check(StudyPlanner.review(progress, MONDAY, false).isEmpty(), "Review schedules another day");
        StudyPlanner.mark(weak, MASTERED, MONDAY, 2, true);
        check(StudyPlanner.review(progress, MONDAY + 1, false).isEmpty(), "Mastered leaves review queue");
        check((weak.unfamiliarPasses & StudyPlanner.bit(1)) != 0, "Pass 1 history remains");
    }

    private static void reviewSelection() {
        Map<String, StudyPlanner.Progress> progress = new HashMap<>();
        for (String word : words(80)) {
            StudyPlanner.Progress entry = new StudyPlanner.Progress();
            StudyPlanner.mark(entry, MASTERED, FRIDAY, 1, false);
            progress.put(word, entry);
        }
        StudyPlanner.mark(progress.get("word079"), REVIEW, FRIDAY, 1, false);
        StudyPlanner.mark(progress.get("word078"), REVIEW, FRIDAY, 1, false);
        StudyPlanner.mark(progress.get("word078"), MASTERED, FRIDAY, 1, true);
        List<String> weekend = StudyPlanner.reviewChoices(progress, 1, WEEKEND, SATURDAY);
        check(weekend.size() == 80 && weekend.get(0).equals("word079"), "All old words, weak ones first");
        List<String> pass = StudyPlanner.reviewChoices(progress, 2, PASS, MONDAY);
        check(pass.subList(0, 2).equals(Arrays.asList("word079", "word078")), "Previous-pass priority");
        check(StudyPlanner.take(pass, 1).size() == 1, "One word is a valid selection");
        check(StudyPlanner.take(pass, 61).size() == 61, "No hidden cap on selected review words");
        progress.get("word079").reviewedPasses |= StudyPlanner.bit(2);
        List<String> more = StudyPlanner.reviewChoices(progress, 2, PASS, MONDAY);
        check(more.contains("word079") && more.size() == 80, "Further reviews remain available");
        check(StudyPlanner.passReviewCount(progress, 2) == 1, "Per-pass review counter");
        check(StudyPlanner.nextPass(2) == 3 && StudyPlanner.nextPass(4) == 5
                && StudyPlanner.nextPass(5) == 5, "Passes stop at five");
    }

    private static void spellingAndResume() {
        check(Practice.matches(" Ｄｅｃｉｄｅ  ", "decide"), "Input normalization");
        check(!Practice.matches("decid", "decide"), "Reject spelling mistakes");
        WordBook.Word color = new WordBook.Word("color", Arrays.asList("n. 颜色"),
                java.util.Collections.emptyList(), Arrays.asList("colour"));
        check(color.matches("colour") && !color.matches("colore"), "Official spelling variants");
        StudySession session = new StudySession(WordBook.CET6, NEW, 1,
                Arrays.asList("decide", "explore", "rely"), Practice.Mode.LISTEN);
        session.beginTest(2);
        check(session.words.size() == 2 && !session.words.contains("rely"), "Only viewed words enter test");
        session.next(true);
        session.input = "draft";
        session.hint = true;
        StudySession restored = StudySession.decode(session.encode());
        check(restored.book == WordBook.CET6 && restored.mode == Practice.Mode.LISTEN
                && restored.kind == NEW && restored.index == 1 && restored.correct == 1
                && restored.words.equals(session.words) && restored.hint
                && restored.input.equals("draft"), "Paused session round-trips");
    }

    private static void packagedBooks(String apkPath) throws Exception {
        try (ZipFile apk = new ZipFile(apkPath)) {
            JSONObject data = new JSONObject(asset(apk, "catalog.json"));
            JSONObject books = data.getJSONObject("books");
            DictionaryLookup dictionary = new DictionaryLookup(new JSONObject(asset(apk, "dictionary.json")));
            for (WordBook book : WordBook.values()) {
                JSONObject level = books.getJSONObject(book.name().toLowerCase(Locale.ROOT));
                JSONObject entries = level.getJSONObject("entries");
                JSONArray core = level.getJSONArray("core");
                JSONArray ranking = level.getJSONArray("ranking");
                Set<String> vocabulary = new HashSet<>();
                for (int i = 0; i < core.length(); i++) {
                    String key = core.getString(i);
                    JSONArray row = entries.getJSONArray(key);
                    check(row.getJSONArray(1).length() > 0, "Base vocabulary meaning");
                    check(vocabulary.add(key), "Unique stable keys");
                    check(dictionary.entry(key) != null, "Base word included in dictionary");
                }
                check(vocabulary.size() == (book == WordBook.CET4 ? 4951 : 6148),
                        "Expanded vocabulary included");
                if (book == WordBook.CET6) {
                    check(vocabulary.containsAll(Arrays.asList("auction", "audit", "augment",
                            "autobiography", "according to", "zoom")), "Verified outline examples included");
                }
                Set<String> ranked = new HashSet<>();
                check(ranking.length() == 1500, "Exactly 1500 frequency entries");
                for (int i = 0; i < ranking.length(); i++) {
                    String word = ranking.getString(i);
                    check(ranked.add(word), "Distinct ranked words");
                    check(dictionary.entry(word) != null, "Exam word included in dictionary");
                    check(entries.getJSONArray(word).getJSONArray(1).length() > 0,
                            "Exam words can be reviewed in Chinese mode");
                }
            }
            check(dictionary.search("auction").get(0).key.equals("auction"), "English definition lookup");
            check(dictionary.search("拍卖").stream().anyMatch(entry -> entry.key.equals("auction")),
                    "Chinese reverse lookup");
            check(dictionary.search("written").get(0).key.equals("written")
                    && dictionary.search("written").stream().anyMatch(entry -> entry.key.equals("write")),
                    "Inflected search matches its family");
            check(dictionary.search("decision").stream().anyMatch(entry -> entry.key.equals("decide")),
                    "Derived search matches its family");
            check(dictionary.families(dictionary.entry("saw")).size() >= 2
                    && dictionary.families(dictionary.entry("see")).stream()
                    .noneMatch(group -> group.contains(dictionary.entry("sawed"))),
                    "Homographic inflections do not merge independent families");
            check(!dictionary.reviewWord("written").meanings.isEmpty(),
                    "Dictionary-only term can enter review");
            check(dictionary.search("noofflinewordzzzz").isEmpty(), "Offline miss");
        }
    }

    private static String asset(ZipFile apk, String name) throws Exception {
        try (InputStream stream = apk.getInputStream(apk.getEntry("assets/" + name))) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = stream.read(chunk)) != -1) {
                output.write(chunk, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> words(int count) {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            words.add(String.format(Locale.ROOT, "word%03d", i));
        }
        return words;
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
