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
        REVIEW_WORDS, PICKER, SESSION
    }

    private static final int DICTIONARY_PAGE_SIZE = 8;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private StudyStore store;
    private SpeechPlayer speech;
    private Vocabulary vocabulary;
    private WordBook book;
    private Screen screen = Screen.HOME;
    private StudyView view;
    private StudySession active;
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
    private final BroadcastReceiver calendar = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshDate();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
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
        } else if (screen == Screen.DICTIONARY_DETAIL) {
            dictionary();
        } else if (screen == Screen.DICTIONARY) {
            exitDictionary();
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
        return page;
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
        String date = LocalDate.ofEpochDay(day).getMonthValue() + "月"
                + LocalDate.ofEpochDay(day).getDayOfMonth() + "日 · "
                + (weekend ? (learningDay ? "周末学习日" : "周末复习日") : "学习日");

        view.titleAndAction(view.body, view.heading("词间", 34),
                view.button("考前复习", SECONDARY, () -> exam(false)));
        view.add(view.body, view.text(date, 14, MUTED, false), 5);
        dictionaryField("", 12);
        if (!store.dictionaryHistory().isEmpty()) {
            view.add(view.body, view.button("搜索记录", TEXT, this::dictionaryHistory), 3);
        }
        bookSwitch(view.body, this::home);

        LinearLayout hero = view.card(GREEN, 0);
        TextView level = view.text(book == WordBook.CET4 ? "CET-4" : "CET-6", 13, GOLD, true);
        level.setLetterSpacing(.15f);
        view.add(hero, level, 0);
        view.add(hero, view.text(stage.finished ? "已完成五周目" : "第 " + stage.pass + " 周目",
                29, WHITE, true), 5);
        if (stage.pass == 1) {
            view.add(hero, view.text("词书首轮  " + complete + " / " + words.size(),
                    14, SOFT_GREEN, false), 5);
            ProgressBar meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            meter.setMax(words.size());
            meter.setProgress(complete);
            meter.setProgressTintList(android.content.res.ColorStateList.valueOf(GOLD));
            meter.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF517062));
            meter.setContentDescription("本周目完成 " + complete + " 个，共 " + words.size() + " 个");
            view.add(hero, meter, 10);
        } else {
            int reviewed = StudyPlanner.passReviewCount(progress, stage.pass);
            view.add(hero, view.text((stage.finished ? "第五周目复习 " : "本周目已复习 ")
                    + reviewed + " 个词", 14, SOFT_GREEN, false), 5);
            if (hasNewWords) {
                view.add(hero, view.text("待补学 " + (words.size() - complete) + " 个词",
                        14, SOFT_GREEN, false), 5);
            }
        }
        view.add(view.body, hero, 16);

        view.titleAndAction(view.body, view.heading("今日任务", 22),
                view.button("设置新词量", TEXT, this::chooseQuota));
        String daily = "到期复习 " + due.size() + " 个";
        if (learningDay) {
            daily = "今日新词 " + StudyPlanner.viewedToday(progress, day) + " / " + quota
                    + "   ·   " + daily;
        }
        view.add(view.body, view.text(daily, 14, MUTED, false), 0);
        showDailyActions(stage, progress, due, day, quota, learnOnWeekends, learningDay);
        if (hasNewWords) {
            showWeekendSetting(learnOnWeekends);
        }

        view.pair(view.body,
                view.button("已学会 " + mastered.size(), SECONDARY, () -> records(true)),
                view.button("待复习 " + review.size(), SECONDARY, () -> records(false)), 20);
    }

    private void dictionaryField(String text, int margin) {
        EditText field = input("搜索英文单词或中文释义");
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setText(text);
        Runnable search = () -> {
            String query = field.getText().toString().trim();
            if (query.isEmpty()) {
                field.setError("请输入英文单词或中文释义");
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
        row.addView(view.button("搜索", PRIMARY, search), new LinearLayout.LayoutParams(-2, -2));
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
        view.titleAndAction(view.body, view.heading("词典", 27),
                view.button("返回首页", TEXT, this::exitDictionary));
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
        view.add(view.body, view.text("共 " + matches.size() + " 条结果", 14, MUTED, false), 14);
        for (DictionaryLookup.Entry entry : matches.subList(dictionaryOffset, end)) {
            LinearLayout card = view.card(CARD, BORDER);
            Button spelling = view.button(entry.spelling, SECONDARY, () -> dictionaryDetail(entry.key));
            spelling.setTextLocale(java.util.Locale.US);
            view.add(card, spelling, 0);
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
            view.add(view.body, view.text("暂无搜索记录", 15, MUTED, false), 18);
            return;
        }
        view.add(view.body, view.heading("最近搜索", 18), 18);
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
        view.titleAndAction(view.body, view.heading("词条详情", 24),
                view.button("返回搜索结果", TEXT, this::dictionary));
        view.add(view.body, view.button("结束查词", TEXT, this::exitDictionary), 4);
        LinearLayout head = view.card(CARD, BORDER);
        english(head, entry.spelling, INK, 34);
        TextView meaning = view.text(String.join("\n", lookup.meanings(entry)), 17, INK, false);
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
            LinearLayout forms = view.card(CARD, BORDER);
            view.add(forms, view.heading("词形变化", 18), 0);
            view.add(forms, view.text(String.join("\n", changes), 15, INK, false), 5);
            view.add(view.body, forms, 10);
        }
        List<List<DictionaryLookup.Entry>> families = lookup.families(entry);
        if (!families.isEmpty()) {
            view.add(view.body, view.heading("同源词与词形", 18), 15);
            for (List<DictionaryLookup.Entry> family : families) {
                LinearLayout group = view.card(CARD, BORDER);
                for (DictionaryLookup.Entry related : family) {
                    if (related != entry) {
                        view.add(group, view.button(related.spelling, TEXT,
                                () -> dictionaryDetail(related.key)), 0);
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
            new AlertDialog.Builder(this).setTitle("加入待复习？")
                    .setMessage("把 " + selected.spelling + " 加入" + book.label + "的待复习吗？")
                    .setNegativeButton("不加入", (dialog, which) -> home())
                    .setPositiveButton("加入复习", (dialog, which) -> markDictionaryTerm(selected))
                    .show();
            return;
        }
        List<DictionaryLookup.Entry> shown = results.subList(dictionaryOffset,
                Math.min(dictionaryOffset + DICTIONARY_PAGE_SIZE, results.size()));
        String[] words = shown.stream().map(entry -> entry.spelling).toArray(String[]::new);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("选一个词加入待复习？")
                .setSingleChoiceItems(words, -1, (prompt, which) ->
                        ((AlertDialog) prompt).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true))
                .setNegativeButton("不加入", (prompt, which) -> home())
                .setNeutralButton("继续查词", null)
                .setPositiveButton("加入复习", (prompt, which) -> {
                    int index = ((AlertDialog) prompt).getListView().getCheckedItemPosition();
                    if (index >= 0) {
                        markDictionaryTerm(shown.get(index));
                    }
                }).create();
        dialog.setOnShowListener(prompt -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false));
        dialog.show();
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
                view.pair(view.body, view.button("继续" + saved.kind.label, PRIMARY,
                        () -> resume(saved)), view.button("结束这组", TEXT,
                        () -> endSession(saved)), 12);
                return;
            }
        }
        if (StudyPlanner.weekend(day)) {
            List<String> choices = StudyPlanner.reviewChoices(progress, stage.pass,
                    StudyPlanner.Kind.WEEKEND, day);
            if (!choices.isEmpty()) {
                view.add(view.body, view.button("自选旧词复习", PRIMARY,
                        () -> openPicker(StudyPlanner.Kind.WEEKEND)), 12);
            } else if (!learningDay) {
                view.add(view.body, view.text("今天暂无旧词可复习。", 15, MUTED, false), 12);
            }
            if (learningDay) {
                showNewWordAction(progress, day, quota, learnOnWeekends, !choices.isEmpty(), stage.pass);
            }
            addEndPass(stage);
            return;
        }
        if (!due.isEmpty()) {
            view.add(view.body, view.button("复习到期词", PRIMARY,
                    () -> openPicker(StudyPlanner.Kind.DUE)), 12);
        }
        if (learningDay) {
            showNewWordAction(progress, day, quota, learnOnWeekends, !due.isEmpty(), stage.pass);
        }
        if (stage.pass > 1 && !stage.finished) {
            view.add(view.body, view.button("自选本周目复习",
                    due.isEmpty() && !learningDay ? PRIMARY : SECONDARY,
                    () -> openPicker(StudyPlanner.Kind.PASS)), 10);
            addEndPass(stage);
        } else if (due.isEmpty() && !learningDay) {
            view.add(view.body, view.text("今天暂无到期复习。", 15, MUTED, false), 12);
        }
    }

    private void showNewWordAction(Map<String, StudyPlanner.Progress> progress, long day,
                                   int quota, boolean learnOnWeekends, boolean hasReviewAction,
                                   int pass) {
        if (StudyPlanner.newWords(vocabulary.words(book), progress, day, quota, learnOnWeekends)
                .isEmpty()) {
            view.add(view.body, view.text("今天的新词任务已完成。", 15, MUTED, false), 12);
        } else {
            view.add(view.body, view.button(pass == 1 ? "开始学新词" : "补学新词",
                    hasReviewAction ? SECONDARY : PRIMARY,
                    () -> chooseMode(StudyPlanner.Kind.NEW)), 10);
        }
    }

    private void showWeekendSetting(boolean enabled) {
        Switch setting = new Switch(this);
        setting.setText("周末学新词");
        setting.setTextColor(INK);
        setting.setTextSize(15);
        setting.setMinHeight(view.dp(48));
        setting.setPadding(view.dp(14), 0, view.dp(14), 0);
        setting.setBackground(view.rounded(CARD, 14, BORDER));
        setting.setChecked(enabled);
        setting.setOnCheckedChangeListener((button, checked) -> {
            store.learnOnWeekends(checked);
            home();
        });
        view.add(view.body, setting, 12);
    }

    private void endSession(StudySession saved) {
        new AlertDialog.Builder(this).setTitle("结束这组练习？")
                .setMessage("已学习的词和已提交的结果会保留。")
                .setNegativeButton("继续保留", null)
                .setPositiveButton("结束这组", (dialog, which) -> {
                    store.clear(saved);
                    home();
                }).show();
    }

    private void addEndPass(StudyStore.Stage stage) {
        if (stage.pass > 1 && !stage.finished) {
            view.add(view.body, view.button("结束本周目", TEXT, () -> endPass(stage)), 6);
        }
    }

    private void endPass(StudyStore.Stage stage) {
        int next = StudyPlanner.nextPass(stage.pass);
        boolean finalPass = stage.pass == StudyPlanner.LAST_PASS;
        String summary = finalPass ? "标记第五周目已完成，之后还可以继续日常和考前复习。"
                : "进入第 " + next + " 周目，已保存的待复习词会继续保留。";
        new AlertDialog.Builder(this).setTitle("结束第 " + stage.pass + " 周目？")
                .setMessage(summary).setNegativeButton("取消", null)
                .setPositiveButton("结束本周目", (dialog, which) -> {
                    StudyStore.Stage current = store.stage(book);
                    if (current.pass == stage.pass && !current.finished) {
                        store.stage(book, new StudyStore.Stage(next, finalPass));
                        home();
                    }
                }).show();
    }

    private void openPicker(StudyPlanner.Kind kind) {
        selectedReviewWords.clear();
        reviewPicker(kind);
    }

    private void reviewPicker(StudyPlanner.Kind kind) {
        screen = Screen.PICKER;
        pickerKind = kind;
        view = install(false);
        view.titleAndAction(view.body, view.heading(kind.label, 26),
                view.button("返回首页", TEXT, this::home));
        Map<String, StudyPlanner.Progress> progress = store.progress(book);
        StudyStore.Stage stage = updateStage(progress);
        int priorityPass = stage.finished ? 1 : stage.pass;
        List<String> words = kind == StudyPlanner.Kind.DUE
                ? StudyPlanner.review(progress, today(), false)
                : StudyPlanner.reviewChoices(progress, priorityPass, kind, today());
        new ReviewPicker(this, view, words, progress, selectedReviewWords, vocabulary,
                book, priorityPass, kind, picked -> {
                    hideKeyboard();
                    pickMode("选择默写方式", mode ->
                            resume(new StudySession(book, kind, stage.pass, picked, mode)));
                });
    }

    private void bookSwitch(LinearLayout destination, Runnable render) {
        view.pair(destination,
                view.button("英语四级", book == WordBook.CET4 ? PRIMARY : SECONDARY,
                        () -> changeBook(WordBook.CET4, render)),
                view.button("英语六级", book == WordBook.CET6 ? PRIMARY : SECONDARY,
                        () -> changeBook(WordBook.CET6, render)), 14);
    }

    private void changeBook(WordBook selection, Runnable render) {
        if (book != selection) {
            store.selectedBook(selection);
            book = selection;
            render.run();
        }
    }

    private void chooseQuota() {
        EditText number = input("每日新词量");
        number.setInputType(InputType.TYPE_CLASS_NUMBER);
        number.setText(Integer.toString(store.quota()));
        number.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
        number.selectAll();
        LinearLayout box = view.column();
        box.setPadding(view.dp(24), view.dp(8), view.dp(24), 0);
        view.add(box, number, 0);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("每日新词量")
                .setMessage("输入每天要学的新词数（1–200）。")
                .setView(box).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
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
    }

    private void chooseMode(StudyPlanner.Kind kind) {
        if (kind == StudyPlanner.Kind.NEW) {
            start(kind, Practice.Mode.CHINESE);
            return;
        }
        pickMode("选择默写方式", mode -> start(kind, mode));
    }

    private void pickMode(String title, Consumer<Practice.Mode> choice) {
        new AlertDialog.Builder(this).setTitle(title)
                .setItems(new String[]{Practice.Mode.CHINESE.label, Practice.Mode.LISTEN.label},
                        (dialog, index) -> choice.accept(Practice.Mode.values()[index]))
                .setNegativeButton("取消", null).show();
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
            new AlertDialog.Builder(this).setMessage("当前没有适合这项练习的词。")
                    .setPositiveButton("知道了", null).show();
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
            view.add(view.body, view.heading("接着默写这一组", 27), 18);
            view.add(view.body, view.text("已经学过 " + active.index + " 个词，可以继续默写。",
                    15, MUTED, false), 10);
            view.add(view.body, view.button("选择默写方式", PRIMARY,
                    () -> chooseNewTest(active.index)), 12);
            view.add(view.body, view.button("稍后继续", TEXT, this::leaveSession), 8);
            return;
        }
        view = install(true);
        view.titleAndAction(view.body, view.heading(active.kind.label, 22),
                view.button("稍后继续", TEXT, this::leaveSession));
        String phase = active.learning ? "先学" : active.mode.label;
        view.add(view.body, view.text(phase + "   ·   " + (active.index + 1)
                + " / " + active.words.size(), 14, MUTED, true), 4);
        WordBook.Word word = vocabulary.word(book, active.current());
        if (active.learning) {
            store.viewed(book, active.current(), today());
            studyCard(word);
        } else if (active.revealed) {
            answer(word);
        } else {
            question(word);
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
        LinearLayout card = view.card(GREEN, 0);
        english(card, word.spelling, WHITE, 38);
        view.add(card, view.text(word.meanings, 17, SOFT_GREEN, false), 8);
        showVariants(card, word, SOFT_GREEN);
        audio(card, word.spelling, true);
        view.add(view.body, card, 17);

        List<String> forms = vocabulary.forms(active.current());
        if (!forms.isEmpty() || !word.relatives.isEmpty()) {
            LinearLayout morphology = view.card(CARD, BORDER);
            if (!forms.isEmpty()) {
                view.add(morphology, view.heading("语法变形", 17), 0);
                view.add(morphology, view.text(String.join("\n", forms), 15, INK, false), 5);
            }
            if (!word.relatives.isEmpty()) {
                view.add(morphology, view.heading("同根词", 17), forms.isEmpty() ? 0 : 16);
                view.add(morphology, view.text(String.join("\n", word.relatives), 15, INK, false), 5);
            }
            view.add(view.body, morphology, 12);
        }
        StudyPlanner.Progress saved = store.progress(book).get(active.current());
        StudyPlanner.Status current = saved == null ? UNMARKED : saved.status;
        view.pair(view.body, view.button(current == MASTERED ? "✓ 已学会" : "已学会",
                        current == MASTERED ? PRIMARY : SECONDARY, () -> markLearning(MASTERED)),
                view.button(current == REVIEW ? "✓ 待复习" : "加入复习",
                        current == REVIEW ? PRIMARY : SECONDARY, () -> markLearning(REVIEW)), 14);
        boolean last = active.index + 1 == active.words.size();
        view.add(view.body, view.button(last ? "选择默写方式" : "下一个单词", PRIMARY,
                this::advanceLearning), 10);
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

    private void question(WordBook.Word word) {
        LinearLayout card = view.card(CARD, BORDER);
        if (active.mode == Practice.Mode.CHINESE) {
            view.add(card, view.heading("根据释义写出英文", 19), 0);
            String clue = word.clue.isEmpty() ? "这条释义暂缺，请听发音作答。" : word.clue;
            view.add(card, view.text(clue, 18, INK, false), 8);
            if (word.clue.isEmpty()) {
                audio(card, word.spelling, false);
            }
        } else {
            view.add(card, view.heading("听发音写出英文", 19), 0);
            audio(card, word.spelling, false);
            if (!word.clue.isEmpty()) {
                TextView clue = view.text(word.clue, 16, INK, false);
                clue.setVisibility(active.hint ? View.VISIBLE : View.GONE);
                Button hint = view.button("中文提示", TEXT, () -> {
                    active.hint = !active.hint;
                    clue.setVisibility(active.hint ? View.VISIBLE : View.GONE);
                });
                view.add(card, hint, 4);
                view.add(card, clue, 3);
            }
        }
        view.add(view.body, card, 18);

        EditText spelling = input("输入英文拼写");
        spelling.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        spelling.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        spelling.setText(active.input);
        spelling.setSelection(spelling.length());
        view.add(view.body, spelling, 16);
        Button submit = view.button("提交", PRIMARY, () -> reveal(false));
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
        view.pair(view.body, view.button("跳过并看答案", SECONDARY, () -> reveal(true)), submit, 10);
    }

    private void reveal(boolean skip) {
        if (active == null || active.revealed || (!skip && Practice.normalize(active.input).isEmpty())) {
            return;
        }
        hideKeyboard();
        active.revealed = true;
        active.skipped = skip;
        store.save(active);
        showSession();
    }

    private void answer(WordBook.Word word) {
        boolean correct = !active.skipped && word.matches(active.input);
        LinearLayout card = view.card(correct ? SOFT_GREEN : LIGHT_GOLD, 0);
        view.add(card, view.text(correct ? "拼写正确" : active.skipped ? "已跳过" : "再记一次",
                15, correct ? GREEN : WARNING, true), 0);
        english(card, word.spelling, INK, 34);
        showVariants(card, word, MUTED);
        if (!active.skipped && !correct) {
            view.add(card, view.text("你的拼写  " + active.input, 15, WARNING, false), 6);
        }
        if (!word.meanings.isEmpty()) {
            view.add(card, view.text(word.meanings, 16, INK, false), 6);
        }
        audio(card, word.spelling, false);
        view.add(view.body, card, 18);
        view.add(view.body, view.text("给这个词标记掌握情况", 15, MUTED, false), 16);
        view.pair(view.body,
                view.button("已学会", correct ? PRIMARY : SECONDARY,
                        () -> saveAnswer(MASTERED, correct)),
                view.button("加入复习", correct ? SECONDARY : PRIMARY,
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
        view.add(view.body, view.heading("本组已完成", 32), 26);
        LinearLayout card = view.card(GREEN, 0);
        view.add(card, view.text(active.kind.label, 16, SOFT_GREEN, true), 0);
        view.add(card, view.text(active.correct + " / " + active.words.size(), 39, GOLD, true), 6);
        view.add(card, view.text("拼写正确", 15, WHITE, false), 0);
        view.add(view.body, card, 16);
        view.add(view.body, view.button("完成", PRIMARY, this::leaveSession), 18);
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
        view.titleAndAction(view.body, view.heading("考前复习", 26),
                view.button("返回首页", TEXT, this::home));
        bookSwitch(view.body, () -> exam(review));
        view.pair(view.body,
                view.button("考试高频词", review ? SECONDARY : PRIMARY, () -> exam(false)),
                view.button("不会的单词", review ? PRIMARY : SECONDARY, () -> exam(true)), 10);
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
            view.add(view.body, view.button(paused == null ? "开始重点复习" : "继续重点复习",
                    PRIMARY, () -> {
                        if (saved == null) {
                            chooseMode(StudyPlanner.Kind.EXAM);
                        } else {
                            resume(saved);
                        }
                    }), 9);
        }
        wordList(words, progress, review ? null : REVIEW,
                review ? "还没有标记待复习的词。" : "暂无高频词条。");
    }

    private void records(boolean mastered) {
        screen = mastered ? Screen.MASTERED_WORDS : Screen.REVIEW_WORDS;
        view = install(false);
        view.titleAndAction(view.body, view.heading(mastered ? "已学会" : "待复习", 26),
                view.button("返回首页", TEXT, this::home));
        Map<String, StudyPlanner.Progress> progress = store.progress(book);
        List<String> words = mastered ? withStatus(progress, MASTERED)
                : StudyPlanner.review(progress, today(), true);
        view.add(view.body, view.text(book.label + "   ·   " + words.size() + " 个词",
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
        EditText search = input("搜索英文单词");
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        view.add(view.body, search, 12);
        ListView list = new ListView(this);
        list.setDividerHeight(0);
        list.setClipToPadding(false);
        list.setPadding(0, view.dp(8), 0, 0);
        list.setContentDescription("单词列表");
        WordAdapter adapter = new WordAdapter(words, progress, action);
        list.setAdapter(adapter);
        TextView empty = view.text("没有匹配的单词", 15, MUTED, false);
        empty.setVisibility(View.GONE);
        view.add(view.body, empty, 12);
        list.setEmptyView(empty);
        view.body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        watch(search, adapter::filter);
    }

    private final class WordAdapter extends BaseAdapter {
        private final List<String> source;
        private final Map<String, StudyPlanner.Progress> progress;
        private final StudyPlanner.Status action;
        private final int pass = store.stage(book).pass;
        private String query = "";
        private List<String> visible;

        WordAdapter(List<String> words, Map<String, StudyPlanner.Progress> progress,
                    StudyPlanner.Status action) {
            this.source = new ArrayList<>(words);
            this.visible = this.source;
            this.progress = progress;
            this.action = action;
        }

        void filter(String current) {
            query = Practice.normalize(current);
            visible = new ArrayList<>();
            for (String word : source) {
                if (word.contains(query)) {
                    visible.add(word);
                }
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return visible.size(); }
        @Override public String getItem(int index) { return visible.get(index); }
        @Override public long getItemId(int index) { return index; }

        @Override public View getView(int index, View recycled, ViewGroup parent) {
            String key = getItem(index);
            WordBook.Word word = vocabulary.word(book, key);
            LinearLayout row = view.row();
            row.setPadding(view.dp(13), view.dp(3), view.dp(6), view.dp(3));
            row.setMinimumHeight(view.dp(52));
            row.setBackground(view.rounded(index % 2 == 0 ? CARD : PAPER, 10, 0));
            TextView label = view.text(word.spelling, 18, INK, true);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            if (action != null) {
                String actionLabel = action == MASTERED ? "已学会" : "不熟";
                String checkedLabel = action == MASTERED ? "✓ 已学会" : "✓ 已加入";
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
        TextView status = view.text("", 13, dark ? SOFT_GREEN : MUTED, false);
        status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        view.add(destination, view.button("▶  播放发音", dark ? ON_GREEN : SECONDARY,
                () -> speech.play(spelling, message -> {
                    if (isFinishing() || !status.isAttachedToWindow()) {
                        return;
                    }
                    status.setText(message);
                    status.setVisibility(View.VISIBLE);
                })), 12);
        view.add(destination, status, 5);
    }

    private void english(LinearLayout destination, String spelling, int color, int size) {
        TextView title = view.text(spelling, size, color, true);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        view.add(destination, title, 2);
    }

    private void showVariants(LinearLayout destination, WordBook.Word word, int color) {
        if (!word.variants.isEmpty()) {
            view.add(destination, view.text("其他写法  " + String.join(" / ", word.variants),
                    14, color, false), 5);
        }
    }

    private EditText input(String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        field.setTextSize(17);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setMinHeight(view.dp(52));
        field.setPadding(view.dp(14), view.dp(8), view.dp(14), view.dp(8));
        field.setBackground(view.rounded(CARD, 13, BORDER));
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
