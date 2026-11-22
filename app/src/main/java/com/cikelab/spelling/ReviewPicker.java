package com.cikelab.spelling;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static com.cikelab.spelling.StudyView.*;

final class ReviewPicker {
    private final Activity activity;
    private final StudyView view;
    private final List<String> ordered;
    private final Map<String, StudyPlanner.Progress> progress;
    private final Set<String> selected;
    private final Vocabulary vocabulary;
    private final WordBook book;
    private final int pass;
    private final StudyPlanner.Kind kind;
    private final Button start;
    private final Button clear;
    private final Button amount;
    private final ChoiceAdapter adapter;
    private List<String> visible;

    ReviewPicker(Activity activity, StudyView view, List<String> ordered,
                 Map<String, StudyPlanner.Progress> progress, Set<String> selected,
                 Vocabulary vocabulary, WordBook book, int pass, StudyPlanner.Kind kind,
                 Consumer<List<String>> begin) {
        this.activity = activity;
        this.view = view;
        this.ordered = ordered;
        this.visible = ordered;
        this.progress = progress;
        this.selected = selected;
        this.vocabulary = vocabulary;
        this.book = book;
        this.pass = pass;
        this.kind = kind;
        selected.retainAll(ordered);

        view.add(view.body, view.text("勾选单词，或按列表顺序选取指定数量。", 14, MUTED, false), 5);
        EditText search = new EditText(activity);
        search.setSingleLine();
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setHint("搜索英文单词");
        search.setTextSize(16);
        search.setTextColor(INK);
        search.setHintTextColor(MUTED);
        search.setMinHeight(view.dp(48));
        search.setPadding(view.dp(12), view.dp(7), view.dp(12), view.dp(7));
        search.setBackground(view.rounded(CARD, 12, BORDER));
        view.add(view.body, search, 12);

        adapter = new ChoiceAdapter();
        amount = view.button("按数量选词", Style.SECONDARY, this::pickAmount);
        clear = view.button("清空选择", Style.TEXT, () -> {
            selected.clear();
            updateActions();
            adapter.notifyDataSetChanged();
        });
        view.pair(view.body, amount, clear, 6);
        ListView list = new ListView(activity);
        list.setDividerHeight(0);
        list.setAdapter(adapter);
        TextView empty = view.text("没有符合条件的旧词。", 15, MUTED, false);
        view.add(view.body, empty, 10);
        list.setEmptyView(empty);
        view.body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        start = view.button("", Style.PRIMARY, () -> {
            List<String> words = new ArrayList<>();
            for (String word : ordered) {
                if (selected.contains(word)) {
                    words.add(word);
                }
            }
            if (!words.isEmpty()) {
                begin.accept(words);
            }
        });
        view.add(view.body, start, 10);
        updateActions();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                String query = Practice.normalize(text.toString());
                List<String> matches = new ArrayList<>();
                for (String word : ordered) {
                    if (word.contains(query)) {
                        matches.add(word);
                    }
                }
                visible = matches;
                updateActions();
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
    }

    private void pickAmount() {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setHint("复习数量");
        LinearLayout box = view.column();
        box.setPadding(view.dp(24), view.dp(4), view.dp(24), 0);
        view.add(box, input, 0);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("选取复习数量")
                .setMessage("将按当前列表顺序选取，最多 " + visible.size() + " 个。")
                .setView(box).setNegativeButton("取消", null).setPositiveButton("选取", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    int count = positiveInt(input.getText().toString());
                    if (count < 1 || count > visible.size()) {
                        input.setError("请输入 1–" + visible.size());
                        return;
                    }
                    selected.clear();
                    selected.addAll(StudyPlanner.take(visible, count));
                    updateActions();
                    adapter.notifyDataSetChanged();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private int positiveInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private void updateActions() {
        start.setText(selected.isEmpty() ? "开始复习" : "开始复习 · " + selected.size() + " 个词");
        start.setEnabled(!selected.isEmpty());
        clear.setEnabled(!selected.isEmpty());
        amount.setEnabled(!visible.isEmpty());
    }

    private final class ChoiceAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public String getItem(int index) { return visible.get(index); }
        @Override public long getItemId(int index) { return index; }

        @Override public View getView(int index, View recycled, ViewGroup parent) {
            String key = getItem(index);
            StudyPlanner.Progress entry = progress.get(key);
            String spelling = vocabulary.word(book, key).spelling;
            CheckBox box = new CheckBox(activity);
            box.setText(spelling);
            box.setTextColor(INK);
            box.setTextSize(17);
            box.setMinHeight(view.dp(52));
            box.setPadding(view.dp(6), 0, view.dp(8), 0);
            box.setButtonTintList(ColorStateList.valueOf(GREEN));
            box.setChecked(selected.contains(key));
            box.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    selected.add(key);
                } else {
                    selected.remove(key);
                }
                updateActions();
            });
            int priority = StudyPlanner.priority(entry, pass, kind);
            String detail = priority == 0 ? "待复习" : priority == 1 ? "上一周目不熟"
                    : entry.status == StudyPlanner.Status.MASTERED ? "已学会" : "已看过";
            if (StudyPlanner.checkedInPass(entry, pass, kind)) {
                detail += " · 已练";
            }
            TextView status = view.text(detail, 12, priority == 0 ? WARNING : MUTED, false);
            LinearLayout row = view.row();
            row.setPadding(view.dp(6), 0, view.dp(10), 0);
            row.setBackground(view.rounded(index % 2 == 0 ? CARD : PAPER, 10, 0));
            row.addView(box, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(status);
            return row;
        }
    }
}
