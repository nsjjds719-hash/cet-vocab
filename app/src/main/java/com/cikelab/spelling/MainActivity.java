package com.cikelab.spelling;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static com.cikelab.spelling.StudyPlanner.NEVER;
import static com.cikelab.spelling.StudyPlanner.Status.MASTERED;
import static com.cikelab.spelling.StudyPlanner.Status.REVIEW;
import static com.cikelab.spelling.StudyPlanner.Status.UNMARKED;
import static com.cikelab.spelling.StudyView.*;
import static com.cikelab.spelling.StudyView.Style.*;

public final class MainActivity extends Activity {
    private enum Screen {
        HOME, DICTIONARY, DICTIONARY_DETAIL, EXAM_RANK, EXAM_REVIEW, MASTERED_WORDS,
        REVIEW_WORDS, PICKER, SESSION, LIVE, WORD, SETTINGS, SETTINGS_LOOK, SETTINGS_HOME, SETTINGS_EMOJI,
        SETTINGS_READING
    }

    private static final int PICK_PHOTO = 41;
    private Look look;
    private boolean photoBusy;

    private static final int DICTIONARY_PAGE_SIZE = 8;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private StudyStore store;
    private SpeechPlayer speech;
    private Vocabulary vocabulary;
    private WordBook book;
    private Screen screen = Screen.HOME;
    private StudyView view;
    private StudySession active;
    private LiveTest live;
    /** 词目学习页：打开的词、来源页，以及回去时要恢复的筛选词和列表位置。 */
    private String wordKey;
    private Screen wordReturn = Screen.HOME;
    private String listQuery = "";
    private int listPosition;
    private int listOffset;
    private static final int LIST_PAGE_SIZE = 50;
    private boolean restoringList;
    private ListView currentList;
    private StudyPlanner.Kind pickerKind = StudyPlanner.Kind.WEEKEND;
    private final Set<String> selectedReviewWords = new LinkedHashSet<>();
    private String dictionaryQuery = "";
    private int dictionaryOffset;
    private String dictionarySelection;
    private String cachedDictionaryQuery;
    private List<DictionaryLookup.Entry> matches = Collections.emptyList();
    private boolean resumeSession;
    private boolean resumeExam;
    private boolean receiverRegistered;
    private long shownDay = NEVER;
    private String lastEntrance = "";
    private LinearLayout panel;
    private static final String[] NUMERALS = {"", "一", "二", "三", "四", "五", "六"};
    private final BroadcastReceiver calendar = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshDate();
        }
    };

    @Override public void onCreate(Bundle state) {
        look = new Look(this);
        boolean dark = look.dark(this);
        setTheme(dark ? R.style.AppThemeDark : R.style.AppTheme);
        StudyView.palette(dark, look);
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        paintSystemBars();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        store = new StudyStore(this);
        speech = new SpeechPlayer(this);
        book = store.selectedBook();
        if (state != null) {
            Screen previous = Screen.valueOf(state.getString("screen", Screen.HOME.name()));
            resumeSession = previous == Screen.SESSION;
            resumeExam = state.getBoolean("examSession");
            screen = resumeSession ? Screen.HOME : previous;
            pickerKind = StudyPlanner.Kind.valueOf(state.getString("pickerKind",
                    StudyPlanner.Kind.WEEKEND.name()));
            ArrayList<String> selected = state.getStringArrayList("selectedReviewWords");
            if (selected != null) {
                selectedReviewWords.addAll(selected);
            }
            dictionaryQuery = state.getString("dictionaryQuery", "");
            dictionaryOffset = state.getInt("dictionaryOffset");
            dictionarySelection = state.getString("dictionarySelection");
            String liveState = state.getString("liveTest");
            if (liveState != null) {
                live = LiveTest.decode(liveState);
            }
            if (screen == Screen.WORD) {
                wordKey = state.getString("wordKey");
                wordReturn = Screen.valueOf(state.getString("wordReturn", Screen.HOME.name()));
                listQuery = state.getString("listQuery", "");
                listPosition = state.getInt("listPosition");
                listOffset = state.getInt("listOffset");
                if (wordKey == null || (wordReturn == Screen.LIVE && live == null)) {
                    screen = Screen.HOME;
                }
            }
            if (screen == Screen.LIVE && live == null) {
                screen = Screen.EXAM_REVIEW;
            }
        }
        loadWords();
    }

    private void loadWords() {
        view = install(true);
        view.add(view.body, view.heading("词间", 34), 10);
        view.add(view.body, view.text("正在载入词书…", 16, MUTED, false), 16);
        loader.execute(() -> {
            try {
                Vocabulary loaded = new Vocabulary(getAssets());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    vocabulary = loaded;
                    if (resumeSession) {
                        active = store.session(book, resumeExam);
                        resumeSession = false;
                        if (active != null) {
                            showSession();
                            return;
                        }
                    }
                    redraw();
                });
            } catch (Exception error) {
                Log.e("Cijian", "Unable to read the bundled wordbooks", error);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        view = install(true);
                        view.add(view.body, view.heading("词书加载失败", 27), 16);
                        view.add(view.body, view.button("重试", PRIMARY, this::loadWords), 16);
                    }
                });
            }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter changes = new IntentFilter(Intent.ACTION_DATE_CHANGED);
        changes.addAction(Intent.ACTION_TIME_CHANGED);
        changes.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(calendar, changes, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(calendar, changes);
        }
        receiverRegistered = true;
    }

    @Override protected void onResume() {
        super.onResume();
        refreshDate();
    }

    @Override protected void onPause() {
        super.onPause();
        if (active != null) {
            store.save(active);
        }
        speech.stop();
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(calendar);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("screen", screen.name());
        state.putBoolean("examSession", active != null && active.kind == StudyPlanner.Kind.EXAM);
        if (screen == Screen.PICKER) {
            state.putString("pickerKind", pickerKind.name());
            state.putStringArrayList("selectedReviewWords", new ArrayList<>(selectedReviewWords));
        }
        if ((screen == Screen.LIVE || screen == Screen.WORD) && live != null) {
            state.putString("liveTest", live.encode());
        }
        if (screen == Screen.WORD) {
            state.putString("wordKey", wordKey);
            state.putString("wordReturn", wordReturn.name());
            state.putString("listQuery", listQuery);
            state.putInt("listPosition", listPosition);
            state.putInt("listOffset", listOffset);
        }
        if (screen == Screen.DICTIONARY || screen == Screen.DICTIONARY_DETAIL) {
            state.putString("dictionaryQuery", dictionaryQuery);
            state.putInt("dictionaryOffset", dictionaryOffset);
            state.putString("dictionarySelection", dictionarySelection);
        }
    }

    @Override protected void onDestroy() {
        loader.shutdownNow();
        speech.release();
        store.close();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (screen == Screen.SESSION) {
            leaveSession();
        } else if (screen == Screen.LIVE) {
            exitLive();
        } else if (screen == Screen.WORD) {
            closeWord();
        } else if (screen == Screen.DICTIONARY_DETAIL) {
            dictionary();
        } else if (screen == Screen.DICTIONARY) {
            exitDictionary();
        } else if (isSettings(screen) && screen != Screen.SETTINGS) {
            settings();
        } else if (screen != Screen.HOME) {
            home();
        } else {
            super.onBackPressed();
        }
    }

    private StudyView install(boolean scroll) {
        StudyView page = new StudyView(this, scroll);
        page.root.setFocusableInTouchMode(true);
        setContentView(page.root);
        page.root.requestFocus();
        page.root.requestApplyInsets();
        shownDay = today();
        page.root.post(() -> {
            String key = entranceKey();
            if (!key.equals(lastEntrance)) {
                lastEntrance = key;
                if (!CALM) {
                    page.enter();
                }
            }
        });
        return page;
    }

    /** 同一页内的局部刷新（标记、换页码）不重播入场动画。 */
    private String entranceKey() {
        String key = screen.name() + "|" + book;
        if (screen == Screen.SESSION && active != null) {
            key += "|" + active.index + "|" + active.learning + "|" + active.revealed;
        } else if (screen == Screen.LIVE && live != null) {
            key += "|" + live.round + "|" + live.session.index + "|" + live.session.revealed;
        } else if (screen == Screen.WORD) {
            key += "|" + wordKey;
        } else if (screen == Screen.DICTIONARY) {
            key += "|" + dictionaryQuery + "|" + dictionaryOffset;
        } else if (screen == Screen.DICTIONARY_DETAIL) {
            key += "|" + dictionarySelection;
        }
        return key;
    }

    private long today() {
        return LocalDate.now().toEpochDay();
    }

    private void refreshDate() {
        if (vocabulary != null && shownDay != today()) {
            if (screen == Screen.SESSION && active != null && active.learning) {
                showSession();
            } else if (screen == Screen.HOME) {
                home();
            }
        }
    }

    private void redraw() {
        switch (screen) {
            case DICTIONARY:
                dictionary();
                break;
            case DICTIONARY_DETAIL:
                dictionaryDetail(dictionarySelection);
                break;
            case EXAM_RANK:
            case EXAM_REVIEW:
                exam(screen == Screen.EXAM_REVIEW);
                break;
            case MASTERED_WORDS:
            case REVIEW_WORDS:
                records(screen == Screen.MASTERED_WORDS);
                break;
            case PICKER:
                reviewPicker(pickerKind);
                break;
            case LIVE:
                showLive();
                break;
            case WORD:
                wordPage();
                break;
            case SETTINGS:
                settings();
                break;
            case SETTINGS_LOOK:
            case SETTINGS_HOME:
            case SETTINGS_EMOJI:
            case SETTINGS_READING:
                settingsGroup(screen);
                break;
            default:
                home();
        }
    }

    private StudyStore.Stage updateStage(Map<String, StudyPlanner.Progress> progress) {
        StudyStore.Stage stage = store.stage(book);
        List<String> words = vocabulary.words(book);
        if (stage.pass == 1 && StudyPlanner.firstPassCount(words, progress) == words.size()) {
            stage = new StudyStore.Stage(2, false);
            store.stage(book, stage);
        }
        return stage;
    }

    private void home() {
        screen = Screen.HOME;
        view = install(true);
        if (vocabulary == null) {
            view.add(view.body, view.heading("正在载入词书…", 22), 12);
            return;
        }
        long day = today();
        Map<String, StudyPlanner.Progress> progress = store.progress(book);
        StudyStore.Stage stage = updateStage(progress);
        int quota = store.quota();
        List<String> due = StudyPlanner.review(progress, day, false);
        List<String> review = StudyPlanner.review(progress, day, true);
        List<String> mastered = withStatus(progress, MASTERED);
        boolean weekend = StudyPlanner.weekend(day);
        boolean learnOnWeekends = store.learnOnWeekends();
        List<String> words = vocabulary.words(book);
        int complete = StudyPlanner.firstPassCount(words, progress);
        boolean hasNewWords = complete < words.size();
        boolean learningDay = hasNewWords && StudyPlanner.allowsNewWords(day, learnOnWeekends);
        String fire = look.value(Look.Setting.NEW_EMOJI);
        String mood = weekend ? (learningDay ? "周末" + orWords(fire, "新词") : "周末复习") : fire;
        String date = LocalDate.ofEpochDay(day).getMonthValue() + "月"
                + LocalDate.ofEpochDay(day).getDayOfMonth() + "日" + (mood.isEmpty() ? "" : " · " + mood);

        Button seal = view.button("考", SEAL, () -> exam(false));
        seal.setContentDescription("临考 · 考前复习");
        LinearLayout.LayoutParams sealSize = new LinearLayout.LayoutParams(view.dp(48), view.dp(48));
        sealSize.rightMargin = view.dp(4);
        LinearLayout.LayoutParams gearSize = new LinearLayout.LayoutParams(view.dp(46), view.dp(46));
        gearSize.rightMargin = view.dp(10);
        LinearLayout top = view.row();
        top.setClipChildren(false);
        top.addView(view.heading("词间", 34), new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(view.gear(this::settings), gearSize);
        top.addView(seal, sealSize);
        view.add(view.body, top, 0);
        TextView dateLine = view.text(date, 13, MUTED, false);
        dateLine.setLetterSpacing(.12f);
        view.add(view.body, dateLine, 4);
        dictionaryField("", 16);
        if (!store.dictionaryHistory().isEmpty()) {
            LinearLayout history = view.row();
            history.addView(view.button("查阅记录", TEXT, this::dictionaryHistory),
                    new LinearLayout.LayoutParams(-2, -2));
            view.add(view.body, history, 2);
        }
        bookSwitch(view.body, this::home);

        LinearLayout hero = view.ink(book == WordBook.CET4 ? "IV" : "VI");
        view.add(hero, view.tag(book == WordBook.CET4 ? "CET-4" : "CET-6", GOLD), 0);
        String caption;
        TextView figure;
        if (stage.pass == 1) {
            caption = "首轮已读";
            figure = view.figure(String.valueOf(complete), " / " + words.size(), 40);
            figure.setContentDescription("首轮已读 " + complete + " 个，共 " + words.size() + " 个");
        } else {
            caption = stage.finished ? "五轮圆满 · 已复习" : "本轮已复习";
            figure = view.figure(String.valueOf(StudyPlanner.passReviewCount(progress, stage.pass)), " 词", 40);
        }
        LinearLayout passRow = view.row();
        passRow.addView(view.badge(stage.pass, stage.finished,
                        stage.finished ? "五轮圆满" : "第" + NUMERALS[stage.pass] + "轮",
                        book == WordBook.CET4 ? "CET-4" : "CET-6"),
                new LinearLayout.LayoutParams(view.dp(68), view.dp(68)));
        LinearLayout figures = view.column();
        TextView captionLine = view.text(caption, 13, CREAM_MUTED, false);
        captionLine.setLetterSpacing(.14f);
        figures.addView(captionLine);
        LinearLayout.LayoutParams figureSpace = new LinearLayout.LayoutParams(-2, -2);
        figureSpace.topMargin = view.dp(3);
        figures.addView(figure, figureSpace);
        LinearLayout.LayoutParams figuresSpace = new LinearLayout.LayoutParams(0, -2, 1);
        figuresSpace.leftMargin = view.dp(16);
        passRow.addView(figures, figuresSpace);
        view.add(hero, passRow, 12);
        if (stage.pass == 1) {
            ProgressBar meter = view.meter(words.size(), complete);
            meter.setContentDescription("首轮已读 " + complete + " 个，共 " + words.size() + " 个");
            LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, view.dp(4));
            bar.topMargin = view.dp(14);
            hero.addView(meter, bar);
        } else {
            if (hasNewWords) {
                view.add(hero, view.text("尚余 " + (words.size() - complete) + " 词待补读",
                        14, CREAM_MUTED, false), 5);
            }
        }
        view.add(view.body, hero, 16);

        panel = view.glass();
        view.titleAndAction(panel, view.heading("今日", 21),
                hasNewWords ? view.button("每日词量", TEXT, this::chooseQuota) : null);
        String daily = "到期复习 " + due.size() + " 词";
        if (learningDay) {
            daily = "新词 " + StudyPlanner.viewedToday(progress, day) + " / " + quota
                    + "   ·   " + daily;
        }
        view.add(panel, view.text(daily, 13, MUTED, false), 2);
        showDailyActions(stage, progress, due, day, quota, learnOnWeekends, learningDay);
        if (hasNewWords) {
            showWeekendSetting(learnOnWeekends);
        }
        view.add(view.body, panel, 14);

        view.pair(view.body,
                view.button("已熟记 · " + mastered.size(), SECONDARY, () -> records(true)),
                view.button("待复习 · " + review.size(), SECONDARY, () -> records(false)), 16);
    }

    private void dictionaryField(String text, int margin) {
        EditText field = input("中英互查词典");
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setText(text);
        Runnable search = () -> {
            String query = field.getText().toString().trim();
            if (query.isEmpty()) {
                field.setError("先写下要查的词");
                field.requestFocus();
                return;
            }
            hideKeyboard();
            searchDictionary(query);
        };
        field.setOnEditorActionListener((input, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                search.run();
                return true;
            }
            return false;
        });
        LinearLayout row = view.row();
        LinearLayout.LayoutParams fieldWidth = new LinearLayout.LayoutParams(0, -2, 1);
        fieldWidth.rightMargin = view.dp(8);
        row.addView(field, fieldWidth);
        row.setClipChildren(false);
        String runner = look.value(Look.Setting.SEARCH_EMOJI);
        Button run = view.button(runner.isEmpty() ? "查阅" : runner, INK_FILL, search);
        run.setTextSize(21);
        run.setContentDescription("查阅");
        run.setMinWidth(view.dp(60));
        run.setMinimumWidth(view.dp(60));
        row.addView(run, new LinearLayout.LayoutParams(-2, -2));
        view.add(view.body, row, margin);
    }

    private void searchDictionary(String query) {
        store.recordDictionaryQuery(query);
        dictionaryQuery = query;
        dictionaryOffset = 0;
        dictionarySelection = null;
        dictionary();
    }

    private void dictionaryHistory() {
        dictionaryQuery = "";
        dictionaryOffset = 0;
        dictionarySelection = null;
        dictionary();
    }

    private List<DictionaryLookup.Entry> dictionaryMatches() {
        if (!dictionaryQuery.equals(cachedDictionaryQuery)) {
            matches = vocabulary.dictionary().search(dictionaryQuery);
            cachedDictionaryQuery = dictionaryQuery;
        }
        return matches;
    }

    private void dictionary() {
        screen = Screen.DICTIONARY;
        view = install(true);
        view.titleAndAction(view.body, view.heading("词典", 28),
                view.button("合上词典", TEXT, this::exitDictionary));
        dictionaryField(dictionaryQuery, 12);
        if (dictionaryQuery.isEmpty()) {
            showDictionaryHistory();
            return;
        }
        List<DictionaryLookup.Entry> matches = dictionaryMatches();
        if (matches.isEmpty()) {
            view.add(view.body, view.text("无结果", 16, MUTED, false), 18);
            return;
        }
        int end = Math.min(dictionaryOffset + DICTIONARY_PAGE_SIZE, matches.size());
        String found = look.value(Look.Setting.FOUND_EMOJI);
        view.add(view.body, view.text((found.isEmpty() ? "" : found + " ") + matches.size() + " 条",
                13, MUTED, false), 14);
        for (DictionaryLookup.Entry entry : matches.subList(dictionaryOffset, end)) {
            LinearLayout card = view.glass();
            TextView spelling = view.word(entry.spelling, 25, INK);
            view.add(card, spelling, 0);
            card.setClickable(true);
            card.setOnClickListener(ignored -> dictionaryDetail(entry.key));
            card.setContentDescription(entry.spelling);
            view.spring(card);
            List<String> meanings = vocabulary.dictionary().meanings(entry);
            view.add(card, view.text(String.join("\n", StudyPlanner.take(meanings, 2)),
                    15, INK, false), 7);
            view.add(view.body, card, 10);
        }
        if (matches.size() > DICTIONARY_PAGE_SIZE) {
            Button previous = view.button("上一页", SECONDARY, () -> {
                dictionaryOffset -= DICTIONARY_PAGE_SIZE;
                dictionary();
            });
            Button next = view.button("下一页", SECONDARY, () -> {
                dictionaryOffset += DICTIONARY_PAGE_SIZE;
                dictionary();
            });
            previous.setEnabled(dictionaryOffset > 0);
            next.setEnabled(end < matches.size());
            view.pair(view.body, previous, next, 12);
        }
    }

    private void showDictionaryHistory() {
        List<String> history = store.dictionaryHistory();
        if (history.isEmpty()) {
            view.add(view.body, view.text("还没有查阅记录", 15, MUTED, false), 18);
            return;
        }
        view.add(view.body, view.heading("最近查阅", 18), 18);
        for (String query : history) {
            view.add(view.body, view.button(query, SECONDARY, () -> searchDictionary(query)), 6);
        }
    }

    private void dictionaryDetail(String key) {
        DictionaryLookup lookup = vocabulary.dictionary();
        DictionaryLookup.Entry entry = lookup.entry(key);
        if (entry == null) {
            dictionary();
            return;
        }
        dictionarySelection = key;
        screen = Screen.DICTIONARY_DETAIL;
        view = install(true);
        view.titleAndAction(view.body, view.heading("词条", 28),
                view.button("返回结果", TEXT, this::dictionary));
        LinearLayout close = view.row();
        close.addView(view.button("合上词典", TEXT, this::exitDictionary), new LinearLayout.LayoutParams(-2, -2));
        view.add(view.body, close, 2);
        LinearLayout head = view.glass();
        head.setPadding(view.dp(20), view.dp(30), view.dp(20), view.dp(24));
        view.watermark(head, entry.spelling);
        english(head, entry.spelling, INK, 40);
        TextView meaning = view.text(String.join("\n", lookup.meanings(entry)), 16, INK_SOFT, false);
        meaning.setGravity(Gravity.CENTER_HORIZONTAL);
        meaning.setTextIsSelectable(true);
        view.add(head, meaning, 8);
        if (!entry.variants.isEmpty()) {
            view.add(head, view.text("其他写法  " + String.join(" / ", entry.variants),
                    14, MUTED, false), 6);
        }
        view.add(view.body, head, 15);
        for (DictionaryLookup.Form form : lookup.forms(entry)) {
            view.add(view.body, view.button(form.label(), SECONDARY,
                    () -> dictionaryDetail(form.base.key)), 6);
        }
        List<String> changes = vocabulary.forms(key);
        if (!changes.isEmpty()) {
            LinearLayout forms = view.glass();
            view.add(forms, view.heading("词形变化", 18), 0);
            view.add(forms, view.text(String.join("\n", changes), 15, INK, false), 5);
            view.add(view.body, forms, 10);
        }
        List<List<DictionaryLookup.Entry>> families = lookup.families(entry);
        if (!families.isEmpty()) {
            view.add(view.body, view.heading("同源词与词形", 18), 15);
            for (List<DictionaryLookup.Entry> family : families) {
                LinearLayout group = view.glass();
                group.setPadding(view.dp(8), view.dp(8), view.dp(8), view.dp(8));
                for (DictionaryLookup.Entry related : family) {
                    if (related != entry) {
                        Button link = view.button(related.spelling, TEXT,
                                () -> dictionaryDetail(related.key));
                        link.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
                        link.setTextSize(18);
                        link.setLetterSpacing(.01f);
                        link.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                        view.add(group, link, 0);
                    }
                }
                view.add(view.body, group, 8);
            }
        }
    }

    private void exitDictionary() {
        List<DictionaryLookup.Entry> results = dictionaryMatches();
        if (results.isEmpty()) {
            home();
            return;
        }
        DictionaryLookup lookup = vocabulary.dictionary();
        DictionaryLookup.Entry selected = dictionarySelection == null
                ? lookup.suggestedTerm(dictionaryQuery, results)
                : lookup.entry(dictionarySelection);
        if (selected != null) {
            tint(new AlertDialog.Builder(this).setTitle("收入复习？")
                    .setMessage("把 " + selected.spelling + " 收入" + book.label + "的待复习吗？")
                    .setNegativeButton("不必", (dialog, which) -> home())
                    .setPositiveButton("收入复习", (dialog, which) -> markDictionaryTerm(selected))
                    .show());
            return;
        }
        List<DictionaryLookup.Entry> shown = results.subList(dictionaryOffset,
                Math.min(dictionaryOffset + DICTIONARY_PAGE_SIZE, results.size()));
        String[] words = shown.stream().map(entry -> entry.spelling).toArray(String[]::new);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("选一个词收入复习？")
                .setSingleChoiceItems(words, -1, (prompt, which) ->
                        ((AlertDialog) prompt).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true))
                .setNegativeButton("不必", (prompt, which) -> home())
                .setNeutralButton("再查查", null)
                .setPositiveButton("收入复习", (prompt, which) -> {
                    int index = ((AlertDialog) prompt).getListView().getCheckedItemPosition();
                    if (index >= 0) {
                        markDictionaryTerm(shown.get(index));
                    }
                }).create();
        dialog.setOnShowListener(prompt -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false));
        dialog.show();
        tint(dialog);
    }

    private void markDictionaryTerm(DictionaryLookup.Entry entry) {
        store.mark(book, entry.key, REVIEW, today(), store.stage(book).pass);
        home();
    }

    private void showDailyActions(StudyStore.Stage stage, Map<String, StudyPlanner.Progress> progress,
                                  List<String> due, long day, int quota, boolean learnOnWeekends,
                                  boolean learningDay) {
        StudySession saved = store.session(book, false);
        if (saved != null) {
            if (saved.complete()) {
                store.clear(saved);
            } else {
                view.pair(panel, view.button("接续" + kindLabel(saved.kind), PRIMARY,
                        () -> resume(saved)), view.button("收起本组", TEXT,
                        () -> endSession(saved)), 14);
                return;
            }
        }
        if (StudyPlanner.weekend(day)) {
            List<String> choices = StudyPlanner.reviewChoices(progress, stage.pass,
                    StudyPlanner.Kind.WEEKEND, day);
            if (!choices.isEmpty()) {
                view.add(panel, view.button("择词复习", PRIMARY,
                        () -> openPicker(StudyPlanner.Kind.WEEKEND)), 14);
            } else if (!learningDay) {
                view.add(panel, view.text("今日无旧词需复习。", 14, MUTED, false), 14);
            }
            if (learningDay) {
                showNewWordAction(progress, day, quota, learnOnWeekends, !choices.isEmpty(), stage.pass);
            }
            addEndPass(stage);
            return;
        }
        if (!due.isEmpty()) {
            view.add(panel, view.button("复习到期词", PRIMARY,
                    () -> openPicker(StudyPlanner.Kind.DUE)), 14);
        }
        if (learningDay) {
            showNewWordAction(progress, day, quota, learnOnWeekends, !due.isEmpty(), stage.pass);
        }
        if (stage.pass > 1 && !stage.finished) {
            view.add(panel, view.button("本轮择词复习",
                    due.isEmpty() && !learningDay ? PRIMARY : SECONDARY,
                    () -> openPicker(StudyPlanner.Kind.PASS)), 10);
            addEndPass(stage);
        } else if (due.isEmpty() && !learningDay) {
            view.add(panel, view.text("今日无到期复习。", 14, MUTED, false), 14);
        }
    }

    private void showNewWordAction(Map<String, StudyPlanner.Progress> progress, long day,
                                   int quota, boolean learnOnWeekends, boolean hasReviewAction,
                                   int pass) {
        if (StudyPlanner.newWords(vocabulary.words(book), progress, day, quota, learnOnWeekends)
                .isEmpty()) {
            view.add(panel, view.text("今日新词已读完。", 14, MUTED, false), 14);
        } else {
            view.add(panel, view.button(pass == 1 ? "开始今日新词" : "补读新词",
                    hasReviewAction ? SECONDARY : PRIMARY,
                    () -> chooseMode(StudyPlanner.Kind.NEW)), 10);
        }
    }

    /** 玻璃风格的开关：按键色轨道、米白圆钮。 */
    private Switch themedSwitch(String label, boolean checked) {
        Switch setting = new Switch(this);
        setting.setText(label);
        setting.setTextColor(INK);
        setting.setTextSize(15 * TEXT_SCALE);
        setting.setMinHeight(view.dp(48));
        setting.setPadding(view.dp(2), 0, view.dp(2), 0);
        int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
        setting.setThumbTintList(new android.content.res.ColorStateList(states,
                new int[]{0xFFFFFDF8, 0xFFFFFDF8}));
        setting.setTrackTintList(new android.content.res.ColorStateList(states,
                new int[]{CLAY, TRACK_OFF}));
        setting.setTrackTintMode(android.graphics.PorterDuff.Mode.SRC);
        setting.setChecked(checked);
        return setting;
    }

    private void showWeekendSetting(boolean enabled) {
        Switch setting = themedSwitch("周末也读新词", enabled);
        setting.setOnCheckedChangeListener((button, checked) -> {
            store.learnOnWeekends(checked);
            home();
        });
        view.add(panel, setting, 8);
    }

    /** 状态栏与导航栏跟随主题；有背景照片时（Android 11 起）让照片铺到栏下。 */
    private void paintSystemBars() {
        android.view.Window window = getWindow();
        // 先取一次 DecorView 让窗口装好装饰层；在 onCreate 里直接调 getInsetsController()
        // 时装饰层可能还没建，部分系统（如 Android 16）会因此空指针闪退。
        View decor = window.getDecorView();
        boolean edge = Build.VERSION.SDK_INT >= 30 && look.hasPhoto();
        window.setStatusBarColor(edge ? android.graphics.Color.TRANSPARENT : PAPER);
        window.setNavigationBarColor(edge ? android.graphics.Color.TRANSPARENT : PAPER_DEEP);
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController bars = window.getInsetsController();
            if (bars != null) {
                int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                bars.setSystemBarsAppearance(DARK ? 0 : light, light);
            }
        } else {
            int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            int flags = decor.getSystemUiVisibility() & ~light;
            decor.setSystemUiVisibility(DARK ? flags : flags | light);
        }
    }

    /** 设置首页：四个分组，点进去是子页。外观只存本机（Look），与学习记录分开。 */
    private void settings() {
        screen = Screen.SETTINGS;
        view = install(true);
        view.titleAndAction(view.body, view.heading("设置", 26), view.button("返回", TEXT, this::home));
        view.add(view.body, view.text("外观只保存在这台设备上，不影响学习记录。", 13, MUTED, false), 4);
        String reading = "字号" + label(Look.Setting.TEXT_SIZE) + " · 跳动" + label(Look.Setting.WIGGLE)
                + " · 浮现" + label(Look.Setting.ENTRANCE);
        String[][] groups = {
                {"色", "外观", look.theme().label + " · " + look.accent().label + " · "
                        + (look.hasPhoto() ? "自定义照片" : "暖纸水印")},
                {"数", "首页样式", label(Look.Setting.NUMERAL) + " · " + label(Look.Setting.BADGE)},
                {"趣", "动态表情", emojiOrNone(Look.Setting.NEW_EMOJI) + "　" + emojiOrNone(Look.Setting.SEARCH_EMOJI)
                        + "　" + emojiOrNone(Look.Setting.FOUND_EMOJI)},
                {"读", "阅读与动效", reading}};
        Screen[] targets = {Screen.SETTINGS_LOOK, Screen.SETTINGS_HOME, Screen.SETTINGS_EMOJI, Screen.SETTINGS_READING};
        for (int index = 0; index < groups.length; index++) {
            Screen target = targets[index];
            view.add(view.body, settingGroup(groups[index], () -> settingsGroup(target)), index == 0 ? 16 : 12);
        }
        view.add(view.body, view.button("完成", PRIMARY, this::home), 18);
    }

    private String label(Look.Setting setting) {
        return setting.label(look.get(setting));
    }

    private String emojiOrNone(Look.Setting setting) {
        String value = look.value(setting);
        return value.isEmpty() ? "无" : value;
    }

    /** 分组卡：左边一枚印章小字，中间名称与当前取值，右边箭头。 */
    private View settingGroup(String[] group, Runnable open) {
        LinearLayout card = view.glass();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        TextView glyph = view.heading(group[0], 19);
        glyph.setLetterSpacing(0);
        glyph.setTextColor(CLAY_DEEP);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(view.shape(0, 8, view.dp(1), (CLAY & 0x00FFFFFF) | 0x8C000000));
        card.addView(glyph, new LinearLayout.LayoutParams(view.dp(42), view.dp(42)));
        LinearLayout words = view.column();
        words.addView(view.heading(group[1], 18));
        TextView summary = view.text(group[2], 13, MUTED, false);
        summary.setSingleLine(true);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(-2, -2);
        under.topMargin = view.dp(2);
        words.addView(summary, under);
        LinearLayout.LayoutParams middle = new LinearLayout.LayoutParams(0, -2, 1);
        middle.leftMargin = view.dp(16);
        middle.rightMargin = view.dp(8);
        card.addView(words, middle);
        TextView chevron = view.text("›", 24, MUTED, false);
        chevron.setTypeface(android.graphics.Typeface.SERIF);
        card.addView(chevron, new LinearLayout.LayoutParams(-2, -2));
        card.setContentDescription(group[1] + "：" + group[2]);
        card.setClickable(true);
        card.setFocusable(true);
        view.spring(card);
        card.setOnClickListener(ignored -> open.run());
        return card;
    }

    /** 设置子页。group 为 SETTINGS_LOOK / SETTINGS_HOME / SETTINGS_EMOJI / SETTINGS_READING。 */
    private void settingsGroup(Screen group) {
        screen = group;
        view = install(true);
        String title = group == Screen.SETTINGS_LOOK ? "外观" : group == Screen.SETTINGS_HOME ? "首页样式"
                : group == Screen.SETTINGS_EMOJI ? "动态表情" : "阅读与动效";
        view.titleAndAction(view.body, view.heading(title, 26), view.button("返回", TEXT, this::settings));
        if (group == Screen.SETTINGS_LOOK) {
            lookSettings();
        } else if (group == Screen.SETTINGS_HOME) {
            homeSettings();
        } else if (group == Screen.SETTINGS_EMOJI) {
            emojiSettings();
        } else {
            readingSettings();
        }
        view.add(view.body, view.button("完成", PRIMARY, this::home), 18);
    }

    /** 一张设置卡：标题、内容、说明。 */
    private LinearLayout settingCard(String heading, View content, String note, int top) {
        LinearLayout card = view.glass();
        view.add(card, view.heading(heading, 19), 0);
        view.add(card, content, 12);
        if (note != null) {
            view.add(card, view.text(note, 13, MUTED, false), 10);
        }
        view.add(view.body, card, top);
        return card;
    }

    /** 两三个取值的设置用分段按钮。 */
    private View segmentOf(Look.Setting setting) {
        String current = look.get(setting);
        Button[] buttons = new Button[setting.ids.length];
        for (int index = 0; index < buttons.length; index++) {
            String id = setting.ids[index];
            buttons[index] = view.button(setting.labels[index], id.equals(current) ? INK_FILL : SEGMENT,
                    () -> chooseSetting(setting, id));
        }
        return view.segment(buttons);
    }

    /** 多个取值的设置用选项格，每行 4 个。 */
    private View choices(Look.Setting setting, java.util.function.Function<String, View> sample, boolean onInk) {
        String current = look.get(setting);
        List<View> cells = new ArrayList<>();
        for (int index = 0; index < setting.ids.length; index++) {
            String id = setting.ids[index];
            cells.add(view.choice(sample.apply(id), setting.labels[index], id.equals(current), onInk,
                    () -> chooseSetting(setting, id)));
        }
        return view.grid(cells, 4);
    }

    private void chooseSetting(Look.Setting setting, String id) {
        look.set(setting, id);
        applyLook();
    }

    private void lookSettings() {
        view.add(view.body, view.text("外观只保存在这台设备上，不影响学习记录。", 13, MUTED, false), 4);
        Look.Theme current = look.theme();
        Look.Theme[] options = Look.Theme.values();
        Button[] themeButtons = new Button[options.length];
        for (int index = 0; index < options.length; index++) {
            Look.Theme option = options[index];
            themeButtons[index] = view.button(option.label, option == current ? INK_FILL : SEGMENT,
                    () -> chooseTheme(option));
        }
        settingCard("主题", view.segment(themeButtons), "跟随系统时，会随手机的深浅色设置自动切换。", 16);

        LinearLayout swatches = view.row();
        swatches.setClipChildren(false);
        Look.Accent chosen = look.accent();
        for (Look.Accent accent : Look.Accent.values()) {
            swatches.addView(view.swatch(accent, accent == chosen, () -> chooseAccent(accent)),
                    new LinearLayout.LayoutParams(0, -2, 1));
        }
        settingCard("按键颜色", swatches, "主按键、开关、进度条与朱印都会换成这个颜色。", 14);

        android.graphics.Bitmap photo = look.photo();
        View preview;
        if (photo != null) {
            android.widget.ImageView image = new android.widget.ImageView(this);
            image.setImageBitmap(photo);
            image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            image.setContentDescription("当前背景照片");
            preview = image;
        } else {
            TextView placeholder = view.heading(look.value(Look.Setting.WATERMARK), 46);
            placeholder.setTextColor(MUTED);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setContentDescription("默认背景：暖纸水印");
            placeholder.setBackground(view.shape(PAPER_DEEP, 18, 1, (INK & 0x00FFFFFF) | 0x1A000000));
            preview = placeholder;
        }
        preview.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View target, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, target.getWidth(), target.getHeight(), view.dp(18));
            }
        });
        preview.setClipToOutline(true);
        preview.setMinimumHeight(view.dp(150));
        LinearLayout photoCard = settingCard("背景照片", preview, null, 14);
        preview.getLayoutParams().height = view.dp(150);
        Button pick = view.button(photoBusy ? "正在处理…" : photo != null ? "更换照片" : "选择照片",
                SECONDARY, this::pickPhoto);
        pick.setEnabled(!photoBusy);
        Button reset = view.button("恢复默认", TEXT, this::clearPhoto);
        reset.setEnabled(photo != null && !photoBusy);
        view.pair(photoCard, pick, reset, 12);
        view.add(photoCard, view.text("照片会自动压缩后存在本机，卡片会加一层磨砂以便看清文字。",
                13, MUTED, false), 10);
        if (photo != null) {
            TextView veil = view.heading("薄纱浓度", 16);
            veil.setTextColor(CLAY_DEEP);
            view.add(photoCard, veil, 16);
            view.add(photoCard, segmentOf(Look.Setting.VEIL), 10);
            view.add(photoCard, view.text("照片上那层薄纱，越浓照片越淡、字越清楚。", 13, MUTED, false), 10);
        }

        settingCard("背景水印字", choices(Look.Setting.WATERMARK, id -> {
            TextView sample = view.heading("none".equals(id) ? "—" : id, 26);
            sample.setLetterSpacing(0);
            return sample;
        }, false), "右下角那枚大号宋体字；用了背景照片时不显示。", 14);
        settingCard("磨砂浓度", segmentOf(Look.Setting.FROST), "卡片和按键玻璃的透明程度，越浓越不透。", 14);
    }

    private void homeSettings() {
        LinearLayout hero = view.ink(book == WordBook.CET4 ? "IV" : "VI");
        view.add(hero, view.tag("预览", GOLD), 0);
        LinearLayout passRow = view.row();
        passRow.addView(view.badge(3, false, "第三轮", book == WordBook.CET4 ? "CET-4" : "CET-6"),
                new LinearLayout.LayoutParams(view.dp(68), view.dp(68)));
        LinearLayout figures = view.column();
        figures.addView(view.text("首轮已读", 13, CREAM_MUTED, false));
        figures.addView(view.figure("1268", " / 4544", 40));
        LinearLayout.LayoutParams figuresSpace = new LinearLayout.LayoutParams(0, -2, 1);
        figuresSpace.leftMargin = view.dp(16);
        passRow.addView(figures, figuresSpace);
        view.add(hero, passRow, 12);
        view.add(view.body, hero, 16);

        settingCard("数字字体", choices(Look.Setting.NUMERAL, id -> {
            TextView sample = view.text("128", 26, CREAM, false);
            sample.setTypeface(view.numeral(id));
            sample.setIncludeFontPadding(false);
            return sample;
        }, true), "首页统计数字和轮次徽章里的数字。", 14);
        String tag = book == WordBook.CET4 ? "CET-4" : "CET-6";
        settingCard("轮次徽章", choices(Look.Setting.BADGE, id -> {
            View badge = view.badge(3, false, Look.Setting.BADGE.label(id), tag, id);
            badge.setLayoutParams(new LinearLayout.LayoutParams(view.dp(48), view.dp(48)));
            badge.setMinimumWidth(view.dp(48));
            badge.setMinimumHeight(view.dp(48));
            return badge;
        }, true), "首页统计卡左侧的圆形标记，圈内是第几轮。", 14);
    }

    private void emojiSettings() {
        java.util.function.Function<String, View> sample = id -> {
            TextView emoji = view.text("none".equals(id) ? "—" : id, 26, INK, false);
            emoji.setGravity(Gravity.CENTER);
            return emoji;
        };
        settingCard("新词", choices(Look.Setting.NEW_EMOJI, sample, false),
                "「新词」按钮、学习页标题和首页日期行。", 16);
        settingCard("查阅按钮", choices(Look.Setting.SEARCH_EMOJI, sample, false),
                "查词框右边的按钮；选「无」时显示「查阅」二字。", 14);
        settingCard("查词结果", choices(Look.Setting.FOUND_EMOJI, sample, false),
                "结果列表上方「N 条」前面的表情。", 14);
    }

    private void readingSettings() {
        settingCard("字号", segmentOf(Look.Setting.TEXT_SIZE), "释义、按钮和单词卡一起放大。", 16);
        LinearLayout motion = view.glass();
        view.add(motion, view.heading("动效", 19), 0);
        lookSwitch(motion, Look.Setting.WIGGLE, "表情跳动", "关掉后表情保持静止。");
        lookSwitch(motion, Look.Setting.ENTRANCE, "换页浮现", "关掉后换页时内容直接出现，不再逐块浮上来。");
        view.add(view.body, motion, 14);
    }

    private void lookSwitch(LinearLayout card, Look.Setting setting, String label, String note) {
        Switch toggle = themedSwitch(label, look.on(setting));
        toggle.setOnCheckedChangeListener((button, checked) -> chooseSetting(setting, checked ? "on" : "off"));
        view.add(card, toggle, 8);
        view.add(card, view.text(note, 13, MUTED, false), 2);
    }

    private void chooseTheme(Look.Theme theme) {
        if (theme == look.theme()) {
            return;
        }
        look.theme(theme);
        if (look.dark(this) == DARK) {
            settingsGroup(Screen.SETTINGS_LOOK);
        } else {
            recreate();
        }
    }

    private void chooseAccent(Look.Accent accent) {
        look.accent(accent);
        applyLook();
    }

    private void pickPhoto() {
        Intent pick;
        if (Build.VERSION.SDK_INT >= 33) {
            pick = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
        } else {
            pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("image/*");
        }
        try {
            startActivityForResult(pick, PICK_PHOTO);
        } catch (android.content.ActivityNotFoundException missing) {
            android.widget.Toast.makeText(this, "这台手机没有可用的相册。", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_PHOTO || result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        android.net.Uri source = data.getData();
        photoBusy = true;
        if (screen == Screen.SETTINGS_LOOK) {
            settingsGroup(Screen.SETTINGS_LOOK);
        }
        loader.execute(() -> {
            String failure = null;
            try {
                look.savePhoto(source);
            } catch (Exception error) {
                Log.w("Cijian", "Unable to set background photo", error);
                failure = error instanceof java.io.IOException && error.getMessage() != null
                        ? error.getMessage() : "照片没能设置成功，请换一张试试。";
            }
            String message = failure;
            runOnUiThread(() -> {
                photoBusy = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (message != null) {
                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
                }
                applyLook();
            });
        });
    }

    private void clearPhoto() {
        tint(new AlertDialog.Builder(this).setTitle("恢复默认背景？")
                .setMessage("照片会从本机删除，背景回到暖纸水印。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复默认", (dialog, which) -> {
                    look.clearPhoto();
                    applyLook();
                }).show());
    }

    /** 外观改动后重新套用调色板并重画当前页；不重播入场动画。 */
    private void applyLook() {
        StudyView.palette(DARK, look);
        paintSystemBars();
        if (isSettings(screen)) {
            int scroll = currentScroll();
            if (screen == Screen.SETTINGS) {
                settings();
            } else {
                settingsGroup(screen);
            }
            restoreScroll(scroll);
        } else if (vocabulary != null) {
            redraw();
        }
    }

    private static boolean isSettings(Screen screen) {
        return screen == Screen.SETTINGS || screen == Screen.SETTINGS_LOOK || screen == Screen.SETTINGS_HOME
                || screen == Screen.SETTINGS_EMOJI || screen == Screen.SETTINGS_READING;
    }

    /** 设置子页里改了选项要原地重画，保持滚动位置。 */
    private int currentScroll() {
        View parent = view == null ? null : (View) view.body.getParent();
        return parent instanceof android.widget.ScrollView ? parent.getScrollY() : 0;
    }

    private void restoreScroll(int y) {
        View parent = (View) view.body.getParent();
        if (parent instanceof android.widget.ScrollView) {
            parent.post(() -> parent.scrollTo(0, y));
        }
    }

    /** 「新词🔥」等标签里的表情换成设置所选；选「无」时去掉。 */
    private String kindLabel(StudyPlanner.Kind kind) {
        return kind.label.replace("🔥", look.value(Look.Setting.NEW_EMOJI));
    }

    /** 表情为空（选了「无」）时用后备文字。 */
    private static String orWords(String emoji, String fallback) {
        return emoji.trim().isEmpty() ? fallback : emoji;
    }

    private void endSession(StudySession saved) {
        tint(new AlertDialog.Builder(this).setTitle("收起本组？")
                .setMessage("已读的词和已核对的结果都会保留。")
                .setNegativeButton("先留着", null)
                .setPositiveButton("收起本组", (dialog, which) -> {
                    store.clear(saved);
                    home();
                }).show());
    }

    private void addEndPass(StudyStore.Stage stage) {
        if (stage.pass > 1 && !stage.finished) {
            view.add(panel, view.button("完结本轮", TEXT, () -> endPass(stage)), 6);
        }
    }

    private void endPass(StudyStore.Stage stage) {
        int next = StudyPlanner.nextPass(stage.pass);
        boolean finalPass = stage.pass == StudyPlanner.LAST_PASS;
        String summary = finalPass ? "五轮至此圆满，之后仍可日常复习与临考。"
                : "将开启第" + NUMERALS[next] + "轮，待复习的词会继续保留。";
        tint(new AlertDialog.Builder(this).setTitle("完结第" + NUMERALS[stage.pass] + "轮？")
                .setMessage(summary).setNegativeButton("取消", null)
                .setPositiveButton("完结本轮", (dialog, which) -> {
                    StudyStore.Stage current = store.stage(book);
                    if (current.pass == stage.pass && !current.finished) {
                        store.stage(book, new StudyStore.Stage(next, finalPass));
                        home();
                    }
                }).show());
    }

    private void openPicker(StudyPlanner.Kind kind) {
        selectedReviewWords.clear();
        reviewPicker(kind);
    }

    private void reviewPicker(StudyPlanner.Kind kind) {
        screen = Screen.PICKER;
        pickerKind = kind;
        view = install(false);
        view.titleAndAction(view.body, view.heading(kindLabel(kind), 26),
                view.button("返回", TEXT, this::home));
        Map<String, StudyPlanner.Progress> progress = store.progress(book);
        StudyStore.Stage stage = updateStage(progress);
        int priorityPass = stage.finished ? 1 : stage.pass;
        List<String> words = kind == StudyPlanner.Kind.DUE
                ? StudyPlanner.review(progress, today(), false)
                : StudyPlanner.reviewChoices(progress, priorityPass, kind, today());
        new ReviewPicker(this, view, words, progress, selectedReviewWords, vocabulary,
                book, priorityPass, kind, picked -> {
                    hideKeyboard();
                    pickMode("如何默写", mode ->
                            resume(new StudySession(book, kind, stage.pass, picked, mode)));
                });
    }

    private void bookSwitch(LinearLayout destination, Runnable render) {
        view.add(destination, view.segment(
                view.button("英语四级", book == WordBook.CET4 ? INK_FILL : SEGMENT,
                        () -> changeBook(WordBook.CET4, render)),
                view.button("英语六级", book == WordBook.CET6 ? INK_FILL : SEGMENT,
                        () -> changeBook(WordBook.CET6, render))), 14);
    }

    private void changeBook(WordBook selection, Runnable render) {
        if (book != selection) {
            store.selectedBook(selection);
            book = selection;
            render.run();
        }
    }

    private void chooseQuota() {
        EditText number = input("每日词量");
        number.setInputType(InputType.TYPE_CLASS_NUMBER);
        number.setText(Integer.toString(store.quota()));
        number.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
        number.selectAll();
        LinearLayout box = view.column();
        box.setPadding(view.dp(24), view.dp(8), view.dp(24), 0);
        view.add(box, number, 0);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("每日词量")
                .setMessage("每天想读几个新词？（1–200）")
                .setView(box).setNegativeButton("取消", null).setPositiveButton("就这样", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    String entered = number.getText().toString();
                    int amount = entered.isEmpty() ? 0 : Integer.parseInt(entered);
                    if (amount < 1 || amount > 200) {
                        number.setError("请输入 1–200");
                        return;
                    }
                    store.quota(amount);
                    dialog.dismiss();
                    home();
                }));
        dialog.show();
        tint(dialog);
    }

    private void chooseMode(StudyPlanner.Kind kind) {
        if (kind == StudyPlanner.Kind.NEW) {
            start(kind, Practice.Mode.CHINESE);
            return;
        }
        pickMode("如何默写", mode -> start(kind, mode));
    }

    private void pickMode(String title, Consumer<Practice.Mode> choice) {
        tint(new AlertDialog.Builder(this).setTitle(title)
                .setItems(new String[]{Practice.Mode.CHINESE.label, Practice.Mode.LISTEN.label},
                        (dialog, index) -> choice.accept(Practice.Mode.values()[index]))
                .setNegativeButton("取消", null).show());
    }

    private void start(StudyPlanner.Kind kind, Practice.Mode mode) {
        long day = today();
        Map<String, StudyPlanner.Progress> progress = store.progress(book);
        StudyStore.Stage stage = updateStage(progress);
        List<String> words = new ArrayList<>();
        switch (kind) {
            case NEW:
                words = StudyPlanner.newWords(vocabulary.words(book), progress, day, store.quota(),
                        store.learnOnWeekends());
                break;
            case EXAM:
                words = StudyPlanner.take(StudyPlanner.review(progress, day, true),
                        StudyPlanner.BATCH_SIZE);
                break;
        }
        if (words.isEmpty()) {
            tint(new AlertDialog.Builder(this).setMessage("当前没有适合这项练习的词。")
                    .setPositiveButton("知道了", null).show());
            return;
        }
        resume(new StudySession(book, kind, stage.pass, words, mode));
    }

    private void resume(StudySession session) {
        active = session;
        store.save(session);
        showSession();
    }

    private void showSession() {
        screen = Screen.SESSION;
        speech.stop();
        if (active.complete()) {
            result();
            return;
        }
        if (active.learning && !canShowNewCard()) {
            if (active.index == 0) {
                store.clear(active);
                active = null;
                home();
                return;
            }
            view = install(true);
            view.add(view.body, view.heading("默写这一组", 28), 18);
            view.add(view.body, view.text("已读 " + active.index + " 词，来默写吧。",
                    15, MUTED, false), 10);
            view.add(view.body, view.button("开始默写", PRIMARY,
                    () -> chooseNewTest(active.index)), 16);
            view.add(view.body, view.button("稍后再续", TEXT, this::leaveSession), 8);
            return;
        }
        view = install(true);
        view.titleAndAction(view.body, view.heading(kindLabel(active.kind), 26),
                view.button("稍后再续", TEXT, this::leaveSession));
        String phase = active.learning ? "研读" : active.mode.label;
        TextView progressLine = view.text(phase + "   ·   " + (active.index + 1)
                + " / " + active.words.size(), 13, MUTED, false);
        progressLine.setLetterSpacing(.12f);
        view.add(view.body, progressLine, 4);
        WordBook.Word word = vocabulary.word(book, active.current());
        if (active.learning) {
            store.viewed(book, active.current(), today());
            studyCard(word);
        } else if (active.revealed) {
            answer(word);
        } else {
            question(word, active);
        }
    }

    private boolean canShowNewCard() {
        Map<String, StudyPlanner.Progress> progress = store.progress(book);
        StudyPlanner.Progress entry = progress.get(active.current());
        long day = today();
        return (entry != null && entry.viewed != NEVER)
                || (StudyPlanner.allowsNewWords(day, store.learnOnWeekends())
                && StudyPlanner.viewedToday(progress, day) < store.quota());
    }

    private void studyCard(WordBook.Word word) {
        wordCard(word, active.current());
        StudyPlanner.Progress saved = store.progress(book).get(active.current());
        StudyPlanner.Status current = saved == null ? UNMARKED : saved.status;
        view.pair(view.body, view.button(current == MASTERED ? "✓ 已熟记" : "熟记",
                        current == MASTERED ? INK_FILL : SECONDARY, () -> markLearning(MASTERED)),
                view.button(current == REVIEW ? "✓ 待复习" : "待复习",
                        current == REVIEW ? INK_FILL : SECONDARY, () -> markLearning(REVIEW)), 14);
        boolean last = active.index + 1 == active.words.size();
        view.add(view.body, view.button(last ? "开始默写" : "下一词", PRIMARY,
                this::advanceLearning), 10);
    }

    /** 学习卡：单词、释义、发音，以及词形变化和同根词。研读和词目学习页共用。 */
    private void wordCard(WordBook.Word word, String key) {
        LinearLayout card = view.glass();
        card.setPadding(view.dp(20), view.dp(34), view.dp(20), view.dp(24));
        view.watermark(card, word.spelling);
        english(card, word.spelling, INK, 44);
        TextView meanings = view.text(word.meanings, 16, INK_SOFT, false);
        meanings.setGravity(Gravity.CENTER_HORIZONTAL);
        view.add(card, meanings, 6);
        showVariants(card, word, MUTED);
        audio(card, word.spelling, false);
        view.add(view.body, card, 16);

        List<String> forms = vocabulary.forms(key);
        if (!forms.isEmpty() || !word.relatives.isEmpty()) {
            LinearLayout morphology = view.glass();
            if (!forms.isEmpty()) {
                view.add(morphology, clayHeading("词形变化"), 0);
                view.add(morphology, view.text(String.join("\n", forms), 15, INK, false), 5);
            }
            if (!word.relatives.isEmpty()) {
                view.add(morphology, clayHeading("同根词"), forms.isEmpty() ? 0 : 16);
                view.add(morphology, view.text(String.join("\n", word.relatives), 15, INK, false), 5);
            }
            view.add(view.body, morphology, 12);
        }
    }

    /** 从词目（生词本、高频榜、已熟记、待复习、实时测试结果）点开单词。 */
    private void openWord(String key) {
        wordReturn = screen;
        if (currentList != null && screen != Screen.LIVE) {
            listPosition = currentList.getFirstVisiblePosition();
            listOffset = ((WordAdapter) currentList.getAdapter()).offset;
        } else {
            listPosition = 0;
            listOffset = 0;
        }
        wordKey = key;
        wordPage();
    }

    private void wordPage() {
        screen = Screen.WORD;
        speech.stop();
        hideKeyboard();
        view = install(true);
        view.titleAndAction(view.body, view.heading("学习", 26),
                view.button("返回", TEXT, this::closeWord));
        view.add(view.body, view.text(book.label, 13, MUTED, false), 4);
        WordBook.Word word = vocabulary.word(book, wordKey);
        wordCard(word, wordKey);
        StudyPlanner.Progress saved = store.progress(book).get(wordKey);
        StudyPlanner.Status current = saved == null ? UNMARKED : saved.status;
        int pass = store.stage(book).pass;
        view.pair(view.body, view.button(current == MASTERED ? "✓ 已熟记" : "熟记",
                        current == MASTERED ? INK_FILL : SECONDARY, () -> {
                            store.mark(book, wordKey, MASTERED, today(), pass);
                            wordPage();
                        }),
                view.button(current == REVIEW ? "✓ 待复习" : "待复习",
                        current == REVIEW ? INK_FILL : SECONDARY, () -> {
                            store.mark(book, wordKey, REVIEW, today(), pass);
                            wordPage();
                        }), 14);
        if (current == REVIEW) {
            LinearLayout line = view.row();
            line.addView(view.text("生词本星标", 13, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(starButton(wordKey, word.spelling, store.starred(book).contains(wordKey)),
                    new LinearLayout.LayoutParams(-2, -2));
            view.add(view.body, line, 10);
        }
    }

    private void closeWord() {
        speech.stop();
        Screen back = wordReturn;
        wordKey = null;
        restoringList = back != Screen.LIVE;
        switch (back) {
            case LIVE:
                showLive();
                return;
            case EXAM_RANK:
            case EXAM_REVIEW:
                exam(back == Screen.EXAM_REVIEW);
                break;
            case MASTERED_WORDS:
            case REVIEW_WORDS:
                records(back == Screen.MASTERED_WORDS);
                break;
            default:
                home();
        }
        restoringList = false;
    }

    /** 词目行可点：整行带水波纹，点开学习页；行内按钮（星标、生疏）照常各管各的。 */
    private void linkRow(LinearLayout row, String key, String spelling) {
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setForeground(getDrawable(ripple.resourceId));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(spelling + "，查看学习页");
        row.setOnClickListener(clicked -> openWord(key));
    }

    private void markLearning(StudyPlanner.Status status) {
        store.mark(book, active.current(), status, today(), active.pass);
        showSession();
    }

    private void advanceLearning() {
        if (active.index + 1 == active.words.size()) {
            chooseNewTest(active.words.size());
        } else {
            active.index++;
            store.save(active);
            showSession();
        }
    }

    private void chooseNewTest(int viewedCount) {
        pickMode("选择默写方式", mode -> {
            active.mode = mode;
            active.beginTest(viewedCount);
            store.save(active);
            showSession();
        });
    }

    private void question(WordBook.Word word, StudySession active) {
        LinearLayout card = view.glass();
        if (active.mode == Practice.Mode.CHINESE) {
            view.add(card, view.heading("观其义，书其形", 19), 0);
            String clue = word.clue.isEmpty() ? "这条释义暂缺，请听发音作答。" : word.clue;
            view.add(card, view.text(clue, 18, INK, false), 12);
            if (word.clue.isEmpty()) {
                audio(card, word.spelling, false);
            }
        } else {
            view.add(card, view.heading("闻其声，书其形", 19), 0);
            audio(card, word.spelling, false);
            if (!word.clue.isEmpty()) {
                TextView clue = view.text(word.clue, 16, INK, false);
                clue.setVisibility(active.hint ? View.VISIBLE : View.GONE);
                Button hint = view.button("释义提示", TEXT, () -> {
                    active.hint = !active.hint;
                    clue.setVisibility(active.hint ? View.VISIBLE : View.GONE);
                });
                view.add(card, hint, 4);
                view.add(card, clue, 3);
            }
        }
        view.add(view.body, card, 18);

        EditText spelling = input("在此拼写");
        spelling.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        spelling.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        spelling.setText(active.input);
        spelling.setSelection(spelling.length());
        spelling.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        spelling.setTextSize(25);
        spelling.setLetterSpacing(.04f);
        spelling.setGravity(Gravity.CENTER);
        spelling.setMinHeight(view.dp(66));
        spelling.setBackground(view.field(22));
        spelling.setTextLocale(java.util.Locale.US);
        view.add(view.body, spelling, 16);
        Button submit = view.button("核对", PRIMARY, () -> reveal(false));
        submit.setEnabled(!Practice.normalize(active.input).isEmpty());
        watch(spelling, value -> {
            active.input = value;
            submit.setEnabled(!Practice.normalize(value).isEmpty());
        });
        spelling.setOnEditorActionListener((field, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE && submit.isEnabled()) {
                reveal(false);
                return true;
            }
            return false;
        });
        view.pair(view.body, view.button("揭晓答案", SECONDARY, () -> reveal(true)), submit, 12);
    }

    private void reveal(boolean skip) {
        StudySession current = screen == Screen.LIVE ? (live == null ? null : live.session) : active;
        if (current == null || current.revealed
                || (!skip && Practice.normalize(current.input).isEmpty())) {
            return;
        }
        hideKeyboard();
        current.revealed = true;
        current.skipped = skip;
        if (screen == Screen.LIVE) {
            showLive();
            return;
        }
        store.save(current);
        showSession();
    }

    private void answer(WordBook.Word word) {
        boolean correct = !active.skipped && word.matches(active.input);
        LinearLayout card = view.tinted(correct ? SUCCESS : RETRY);
        card.setPadding(view.dp(20), view.dp(24), view.dp(20), view.dp(22));
        TextView verdict = view.heading(correct ? "拼写无误" : active.skipped ? "答案在此" : "差之毫厘", 15);
        verdict.setLetterSpacing(.3f);
        verdict.setTextColor(correct ? SUCCESS_TEXT : WARNING);
        verdict.setGravity(Gravity.CENTER_HORIZONTAL);
        view.add(card, verdict, 0);
        english(card, word.spelling, INK, 40);
        showVariants(card, word, MUTED);
        if (!active.skipped && !correct) {
            TextView mine = view.text("你写的是  " + active.input, 15, WARNING, false);
            mine.setGravity(Gravity.CENTER_HORIZONTAL);
            view.add(card, mine, 6);
        }
        if (!word.meanings.isEmpty()) {
            TextView meanings = view.text(word.meanings, 16, INK_SOFT, false);
            meanings.setGravity(Gravity.CENTER_HORIZONTAL);
            view.add(card, meanings, 8);
        }
        audio(card, word.spelling, false);
        view.add(view.body, card, 18);
        view.add(view.body, view.text("这个词，记住了吗？", 14, MUTED, false), 18);
        view.pair(view.body,
                view.button("熟记", correct ? PRIMARY : SECONDARY,
                        () -> saveAnswer(MASTERED, correct)),
                view.button("待复习", correct ? SECONDARY : PRIMARY,
                        () -> saveAnswer(REVIEW, correct)), 8);
    }

    private void saveAnswer(StudyPlanner.Status status, boolean correct) {
        if (active == null || !active.revealed) {
            return;
        }
        String key = active.current();
        active.next(correct);
        store.finishQuestion(active, key, status, today());
        showSession();
    }

    private void result() {
        if (active.kind != StudyPlanner.Kind.EXAM) {
            updateStage(store.progress(book));
        }
        view = install(true);
        view.add(view.body, view.heading("本组圆满", 32), 26);
        LinearLayout card = view.ink("fin.");
        view.add(card, view.tag(kindLabel(active.kind), GOLD), 0);
        view.add(card, view.word(active.correct + " / " + active.words.size(), 44, CREAM), 10);
        view.add(card, view.text("拼写无误", 14, CREAM_MUTED, false), 2);
        view.add(view.body, card, 18);
        view.add(view.body, view.button("收卷", PRIMARY, this::leaveSession), 18);
    }

    private void leaveSession() {
        speech.stop();
        hideKeyboard();
        StudyPlanner.Kind kind = active.kind;
        boolean finished = active.complete();
        if (finished) {
            store.clear(active);
        } else {
            store.save(active);
        }
        active = null;
        if (kind == StudyPlanner.Kind.EXAM) {
            exam(true);
        } else if (finished && (kind == StudyPlanner.Kind.WEEKEND || kind == StudyPlanner.Kind.PASS
                || kind == StudyPlanner.Kind.DUE)) {
            openPicker(kind);
        } else {
            home();
        }
    }

    private void exam(boolean review) {
        screen = review ? Screen.EXAM_REVIEW : Screen.EXAM_RANK;
        view = install(false);
        view.titleAndAction(view.body, view.heading("临考", 30),
                view.button("返回", TEXT, this::home));
        bookSwitch(view.body, () -> exam(review));
        view.add(view.body, view.segment(
                view.button("真题高频", review ? SEGMENT : INK_FILL, () -> exam(false)),
                view.button("生词本", review ? INK_FILL : SEGMENT, () -> exam(true))), 10);
        Map<String, StudyPlanner.Progress> progress = store.progress(book);
        List<String> words = review ? StudyPlanner.review(progress, today(), true)
                : vocabulary.ranked(book);
        StudySession paused = review ? store.session(book, true) : null;
        if (paused != null && paused.complete()) {
            store.clear(paused);
            paused = null;
        }
        if (review && (paused != null || !words.isEmpty())) {
            StudySession saved = paused;
            view.add(view.body, view.button(paused == null ? "生词默写" : "接续生词默写",
                    PRIMARY, () -> {
                        if (saved == null) {
                            chooseMode(StudyPlanner.Kind.EXAM);
                        } else {
                            resume(saved);
                        }
                    }), 9);
        }
        if (review && !words.isEmpty()) {
            view.add(view.body, view.button("实时测试", SECONDARY,
                    () -> pickMode("实时测试 · 如何默写", this::startLive)), 9);
            int stars = 0;
            Set<String> starred = store.starred(book);
            for (String word : words) {
                if (starred.contains(word)) {
                    stars++;
                }
            }
            view.add(view.body, view.text("从生词本随机抽词，每轮 "
                    + Math.min(LiveTest.ROUND_SIZE, words.size())
                    + " 词，可无限循环；不影响复习进度。" + (stars > 0 ? "  已加星 " + stars + " 词" : ""),
                    13, MUTED, false), 6);
        }
        wordList(words, progress, review ? null : REVIEW,
                review ? "生词本还是空的。" : "暂无高频词条。");
    }

    private List<String> livePool() {
        return StudyPlanner.review(store.progress(book), today(), true);
    }

    private void startLive(Practice.Mode mode) {
        live = new LiveTest(book, mode);
        nextLiveRound();
    }

    private void nextLiveRound() {
        if (!live.nextRound(livePool())) {
            exitLive();
            return;
        }
        showLive();
    }

    private void exitLive() {
        speech.stop();
        hideKeyboard();
        live = null;
        exam(true);
    }

    private void showLive() {
        screen = Screen.LIVE;
        speech.stop();
        if (live == null) {
            exam(true);
            return;
        }
        if (live.roundComplete()) {
            liveSummary();
            return;
        }
        StudySession round = live.session;
        view = install(true);
        view.titleAndAction(view.body, view.heading("实时测试", 26),
                view.button("结束测试", TEXT, this::exitLive));
        TextView progressLine = view.text(round.mode.label + "   ·   第 " + live.round + " 轮   ·   "
                + (round.index + 1) + " / " + round.words.size(), 13, MUTED, false);
        progressLine.setLetterSpacing(.12f);
        view.add(view.body, progressLine, 4);
        WordBook.Word word = vocabulary.word(book, round.current());
        if (!round.revealed) {
            question(word, round);
            return;
        }
        boolean correct = !round.skipped && word.matches(round.input);
        LinearLayout card = view.tinted(correct ? SUCCESS : RETRY);
        card.setPadding(view.dp(20), view.dp(24), view.dp(20), view.dp(22));
        TextView verdict = view.heading(correct ? "拼写无误" : round.skipped ? "答案在此" : "差之毫厘", 15);
        verdict.setLetterSpacing(.3f);
        verdict.setTextColor(correct ? SUCCESS_TEXT : WARNING);
        verdict.setGravity(Gravity.CENTER_HORIZONTAL);
        view.add(card, verdict, 0);
        english(card, word.spelling, INK, 40);
        showVariants(card, word, MUTED);
        if (!round.skipped && !correct) {
            TextView mine = view.text("你写的是  " + round.input, 15, WARNING, false);
            mine.setGravity(Gravity.CENTER_HORIZONTAL);
            view.add(card, mine, 6);
        }
        if (!word.meanings.isEmpty()) {
            TextView meanings = view.text(word.meanings, 16, INK_SOFT, false);
            meanings.setGravity(Gravity.CENTER_HORIZONTAL);
            view.add(card, meanings, 8);
        }
        audio(card, word.spelling, false);
        view.add(view.body, card, 18);
        boolean last = round.index + 1 == round.words.size();
        view.add(view.body, view.button(last ? "查看本轮结果" : "下一词", PRIMARY, () -> {
            if (live != null && live.session.revealed) {
                live.answer(correct);
                showLive();
            }
        }), 18);
    }

    private void liveSummary() {
        StudySession round = live.session;
        view = install(true);
        view.titleAndAction(view.body, view.heading("实时测试", 26),
                view.button("结束测试", TEXT, this::exitLive));
        view.add(view.body, view.text("第 " + live.round + " 轮完成", 13, MUTED, false), 4);
        LinearLayout card = view.ink("live");
        view.add(card, view.tag("实时测试", GOLD), 0);
        view.add(card, view.word(round.correct + " / " + round.words.size(), 44, CREAM), 10);
        view.add(card, view.text("拼写无误", 14, CREAM_MUTED, false), 2);
        view.add(view.body, card, 16);
        view.add(view.body, view.text("给不会的词加星，星标会显示在生词本里，可随时去除。",
                13, MUTED, false), 14);
        Set<String> starred = store.starred(book);
        List<Button> stars = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        for (int i = 0; i < round.words.size(); i++) {
            String key = round.words.get(i);
            boolean known = i < live.results.size() && live.results.get(i);
            if (!known) {
                missed.add(key);
            }
            WordBook.Word word = vocabulary.word(book, key);
            LinearLayout row = view.row();
            row.setPadding(view.dp(16), view.dp(6), view.dp(6), view.dp(6));
            row.setMinimumHeight(view.dp(58));
            row.setBackground(view.rounded(i % 2 == 0 ? ROW : 0x00000000, 16, 0));
            LinearLayout labels = view.column();
            TextView label = view.text(word.spelling, 18, INK, false);
            label.setTypeface(Typeface.SERIF);
            labels.addView(label);
            String gloss = word.meanings.split("\n", 2)[0];
            TextView detail = view.text((known ? "会" : "不会") + "  " + gloss, 13,
                    known ? MUTED : WARNING, false);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(detail);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            linkRow(row, key, word.spelling);
            Button star = starButton(key, word.spelling, starred.contains(key));
            stars.add(star);
            row.addView(star, new LinearLayout.LayoutParams(-2, -2));
            view.add(view.body, row, 4);
        }
        missed.removeIf(starred::contains);
        if (!missed.isEmpty()) {
            Button[] bulk = new Button[1];
            bulk[0] = view.button("不会的词全部加星 · " + missed.size(), SECONDARY, () -> {
                for (String key : missed) {
                    store.star(book, key, true);
                }
                for (int i = 0; i < stars.size(); i++) {
                    if (missed.contains(round.words.get(i))) {
                        paintStar(stars.get(i), vocabulary.word(book, round.words.get(i)).spelling, true);
                    }
                }
                bulk[0].setVisibility(View.GONE);
            });
            view.add(view.body, bulk[0], 14);
        }
        view.add(view.body, view.button("再来一轮", PRIMARY, this::nextLiveRound), 12);
    }

    /** 星标开关：点一下加星，再点去除；直接写入生词本。 */
    private Button starButton(String key, String spelling, boolean on) {
        Button[] holder = new Button[1];
        holder[0] = view.button("", TEXT, () -> {
            boolean next = !store.starred(book).contains(key);
            store.star(book, key, next);
            paintStar(holder[0], spelling, next);
        });
        holder[0].setTextSize(22);
        holder[0].setPadding(view.dp(12), 0, view.dp(12), 0);
        paintStar(holder[0], spelling, on);
        return holder[0];
    }

    private void paintStar(Button star, String spelling, boolean on) {
        star.setText(on ? "★" : "☆");
        star.setTextColor(on ? GOLD : MUTED);
        star.setContentDescription((on ? "去除星标 " : "加星标 ") + spelling);
    }

    private void records(boolean mastered) {
        screen = mastered ? Screen.MASTERED_WORDS : Screen.REVIEW_WORDS;
        view = install(false);
        view.titleAndAction(view.body, view.heading(mastered ? "已熟记" : "待复习", 28),
                view.button("返回", TEXT, this::home));
        Map<String, StudyPlanner.Progress> progress = store.progress(book);
        List<String> words = mastered ? withStatus(progress, MASTERED)
                : StudyPlanner.review(progress, today(), true);
        view.add(view.body, view.text(book.label + "   ·   " + words.size() + " 词",
                14, MUTED, false), 5);
        wordList(words, progress, mastered ? REVIEW : MASTERED, "这里还没有记录。");
    }

    private List<String> withStatus(Map<String, StudyPlanner.Progress> progress,
                                    StudyPlanner.Status status) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, StudyPlanner.Progress> item : progress.entrySet()) {
            if (item.getValue().status == status) {
                result.add(item.getKey());
            }
        }
        Collections.sort(result);
        return result;
    }

    private void wordList(List<String> words, Map<String, StudyPlanner.Progress> progress,
                          StudyPlanner.Status action, String emptyMessage) {
        if (words.isEmpty()) {
            view.add(view.body, view.text(emptyMessage, 15, MUTED, false), 20);
            return;
        }
        currentList = null;
        EditText search = input("筛选单词");
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        view.add(view.body, search, 12);
        ListView list = new ListView(this);
        currentList = list;
        list.setDividerHeight(0);
        list.setClipToPadding(false);
        list.setPadding(0, view.dp(8), 0, 0);
        list.setContentDescription("单词列表");
        WordAdapter adapter = new WordAdapter(words, progress, action);
        list.setAdapter(adapter);
        LinearLayout pager = view.row();
        pager.setGravity(Gravity.CENTER_VERTICAL);
        Button previous = view.button("上一页", SECONDARY, () -> {
            adapter.turn(-1);
            list.setSelection(0);
        });
        Button next = view.button("下一页", SECONDARY, () -> {
            adapter.turn(1);
            list.setSelection(0);
        });
        TextView pageLabel = view.text("", 13, MUTED, false);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        pager.addView(previous, new LinearLayout.LayoutParams(-2, -2));
        pager.addView(pageLabel, new LinearLayout.LayoutParams(0, -2, 1));
        pager.addView(next, new LinearLayout.LayoutParams(-2, -2));
        adapter.onPage = () -> {
            int total = adapter.matching();
            pager.setVisibility(total > LIST_PAGE_SIZE ? View.VISIBLE : View.GONE);
            int end = Math.min(adapter.offset + LIST_PAGE_SIZE, total);
            pageLabel.setText("第 " + (adapter.offset / LIST_PAGE_SIZE + 1) + " / "
                    + ((total + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE) + " 页\n"
                    + (total == 0 ? 0 : adapter.offset + 1) + "–" + end + " / " + total);
            previous.setEnabled(adapter.offset > 0);
            next.setEnabled(end < total);
        };
        TextView empty = view.text("没有匹配的单词", 15, MUTED, false);
        empty.setVisibility(View.GONE);
        view.add(view.body, empty, 12);
        list.setEmptyView(empty);
        view.body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setClipChildren(true);
        list.setClipToOutline(true);
        view.add(view.body, pager, 12);
        String restoredQuery = restoringList ? listQuery : "";
        int restoredPosition = restoringList ? listPosition : 0;
        int restoredOffset = restoringList ? listOffset : 0;
        restoringList = false;
        listQuery = "";
        if (!restoredQuery.isEmpty()) {
            search.setText(restoredQuery);
            adapter.filter(restoredQuery);
            listQuery = restoredQuery;
        }
        adapter.page(restoredOffset);
        if (restoredPosition > 0) {
            list.setSelection(Math.min(restoredPosition, Math.max(0, adapter.getCount() - 1)));
        }
        watch(search, value -> {
            listQuery = value;
            adapter.filter(value);
        });
    }

    private final class WordAdapter extends BaseAdapter {
        private final List<String> source;
        private final Map<String, StudyPlanner.Progress> progress;
        private final StudyPlanner.Status action;
        private final int pass = store.stage(book).pass;
        private final Set<String> starred = screen == Screen.EXAM_REVIEW
                ? store.starred(book) : null;
        private String query = "";
        private List<String> visible;
        /** 当前页第一词在筛选结果里的位置；每页 LIST_PAGE_SIZE 词。 */
        int offset;
        Runnable onPage;

        WordAdapter(List<String> words, Map<String, StudyPlanner.Progress> progress,
                    StudyPlanner.Status action) {
            this.source = new ArrayList<>(words);
            this.visible = this.source;
            this.progress = progress;
            this.action = action;
        }

        void filter(String current) {
            String normalized = Practice.normalize(current);
            if (!normalized.equals(query)) {
                offset = 0;
            }
            query = normalized;
            visible = new ArrayList<>();
            for (String word : source) {
                if (word.contains(query)) {
                    visible.add(word);
                }
            }
            page(offset);
        }

        int matching() {
            return visible.size();
        }

        void turn(int direction) {
            page(offset + direction * LIST_PAGE_SIZE);
        }

        /** 翻到含 start 的那一页；删词后页尾变空时自动退回上一页。 */
        void page(int start) {
            int last = visible.isEmpty() ? 0 : (visible.size() - 1) / LIST_PAGE_SIZE * LIST_PAGE_SIZE;
            offset = Math.max(0, Math.min(start / LIST_PAGE_SIZE * LIST_PAGE_SIZE, last));
            notifyDataSetChanged();
            if (onPage != null) {
                onPage.run();
            }
        }

        @Override public int getCount() {
            return Math.max(0, Math.min(LIST_PAGE_SIZE, visible.size() - offset));
        }
        @Override public String getItem(int index) { return visible.get(offset + index); }
        @Override public long getItemId(int index) { return index; }

        @Override public View getView(int index, View recycled, ViewGroup parent) {
            String key = getItem(index);
            WordBook.Word word = vocabulary.word(book, key);
            LinearLayout row = view.row();
            row.setPadding(view.dp(16), view.dp(3), view.dp(6), view.dp(3));
            row.setMinimumHeight(view.dp(54));
            row.setBackground(view.rounded(index % 2 == 0 ? ROW : 0x00000000, 16, 0));
            TextView label = view.text(word.spelling, 18, INK, false);
            label.setTypeface(Typeface.SERIF);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            linkRow(row, key, word.spelling);
            if (starred != null) {
                Button star = starButton(key, word.spelling, starred.contains(key));
                star.setOnClickListener(clicked -> {
                    boolean on = !starred.contains(key);
                    store.star(book, key, on);
                    if (on) {
                        starred.add(key);
                    } else {
                        starred.remove(key);
                    }
                    paintStar(star, word.spelling, on);
                });
                row.addView(star, new LinearLayout.LayoutParams(-2, -2));
            }
            if (action != null) {
                String actionLabel = action == MASTERED ? "熟记" : "生疏";
                String checkedLabel = action == MASTERED ? "✓ 已熟记" : "✓ 已收录";
                boolean marked = progress.containsKey(key) && progress.get(key).status == action;
                Button button = view.button(marked ? checkedLabel : actionLabel, TEXT, () -> {
                    store.mark(book, key, action, today(), pass);
                    StudyPlanner.Progress status = new StudyPlanner.Progress();
                    status.status = action;
                    progress.put(key, status);
                    if (screen == Screen.MASTERED_WORDS || screen == Screen.REVIEW_WORDS) {
                        source.remove(key);
                        filter(query);
                    } else {
                        notifyDataSetChanged();
                    }
                });
                button.setEnabled(!marked);
                button.setContentDescription(word.spelling + " " + (marked ? checkedLabel : actionLabel));
                row.addView(button, new LinearLayout.LayoutParams(-2, -2));
            }
            return row;
        }
    }

    private void audio(LinearLayout destination, String spelling, boolean dark) {
        TextView status = view.text("", 13, MUTED, false);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Button listen = view.button("聆听", SECONDARY,
                () -> speech.play(spelling, message -> {
                    if (isFinishing() || !status.isAttachedToWindow()) {
                        return;
                    }
                    status.setText(message);
                    status.setVisibility(View.VISIBLE);
                }));
        LinearLayout line = view.row();
        line.setGravity(Gravity.CENTER_HORIZONTAL);
        line.setClipChildren(false);
        line.addView(listen, new LinearLayout.LayoutParams(-2, -2));
        view.add(destination, line, 14);
        view.add(destination, status, 5);
    }

    private void english(LinearLayout destination, String spelling, int color, int size) {
        TextView title = view.word(spelling, size, color);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        view.add(destination, title, 2);
        View rule = new View(this);
        rule.setBackgroundColor((CLAY & 0x00FFFFFF) | 0x99000000);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(view.dp(36), 1);
        line.gravity = Gravity.CENTER_HORIZONTAL;
        line.topMargin = view.dp(12);
        line.bottomMargin = view.dp(4);
        destination.addView(rule, line);
    }

    private TextView clayHeading(String label) {
        TextView heading = view.heading(label, 16);
        heading.setTextColor(CLAY_DEEP);
        return heading;
    }

    private void showVariants(LinearLayout destination, WordBook.Word word, int color) {
        if (!word.variants.isEmpty()) {
            TextView variants = view.text("其他写法  " + String.join(" / ", word.variants),
                    13, color, false);
            variants.setGravity(Gravity.CENTER_HORIZONTAL);
            view.add(destination, variants, 5);
        }
    }

    private EditText input(String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setTextColor(INK);
        field.setHintTextColor(HINT);
        field.setTextSize(16);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setMinHeight(view.dp(52));
        field.setPadding(view.dp(20), view.dp(8), view.dp(20), view.dp(8));
        field.setBackground(view.field(26));
        field.setElevation(view.dp(2));
        return field;
    }

    private void watch(EditText input, Consumer<String> onChange) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                onChange.accept(text.toString());
            }
            @Override public void afterTextChanged(Editable text) {}
        });
    }

    private void hideKeyboard() {
        if (view != null) {
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            keyboard.hideSoftInputFromWindow(view.root.getWindowToken(), 0);
        }
    }
}
