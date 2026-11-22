package com.cikelab.spelling;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class StudyView {
    static final int PAPER = 0xFFF8F6EF;
    static final int CARD = 0xFFFFFEFA;
    static final int INK = 0xFF182E26;
    static final int GREEN = 0xFF264D3F;
    static final int SOFT_GREEN = 0xFFE6EEE5;
    static final int MUTED = 0xFF53635A;
    static final int BORDER = 0xFFD9DED4;
    static final int GOLD = 0xFFF6CE87;
    static final int WARNING = 0xFF885020;
    static final int LIGHT_GOLD = 0xFFF7EAD5;
    static final int WHITE = Color.WHITE;

    enum Style { PRIMARY, SECONDARY, TEXT, ON_GREEN }

    final LinearLayout root;
    final LinearLayout body;
    private final Context context;

    StudyView(Context context, boolean scroll) {
        this.context = context;
        root = column();
        root.setBackgroundColor(PAPER);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        body = column();
        body.setPadding(dp(22), dp(14), dp(22), dp(24));
        if (scroll) {
            ScrollView scroller = new ScrollView(context);
            scroller.setFillViewport(true);
            scroller.setClipToPadding(false);
            scroller.addView(body, new ScrollView.LayoutParams(-1, -2));
            root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        }
    }

    int dp(float value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    TextView text(String value, float size, int color, boolean bold) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        text.setTextColor(color);
        text.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        text.setLineSpacing(dp(2), 1);
        return text;
    }

    TextView heading(String value, float size) {
        TextView heading = text(value, size, INK, true);
        if (Build.VERSION.SDK_INT >= 28) {
            heading.setAccessibilityHeading(true);
        }
        return heading;
    }

    Button button(String value, Style style, Runnable action) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(value);
        button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(13), dp(8), dp(13), dp(8));
        button.setStateListAnimator(null);
        int background = CARD;
        int foreground = GREEN;
        int border = BORDER;
        if (style == Style.PRIMARY) {
            background = GREEN;
            foreground = WHITE;
            border = 0;
        } else if (style == Style.TEXT) {
            background = Color.TRANSPARENT;
            border = 0;
        } else if (style == Style.ON_GREEN) {
            background = GOLD;
            foreground = INK;
            border = 0;
        }
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{style == Style.PRIMARY ? SOFT_GREEN : MUTED, foreground}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33728D80),
                rounded(background, 14, border), rounded(WHITE, 14, 0)));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    LinearLayout card(int background, int border) {
        LinearLayout card = column();
        card.setBackground(rounded(background, 22, border));
        card.setPadding(dp(18), dp(17), dp(18), dp(17));
        return card;
    }

    GradientDrawable rounded(int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (border != 0) {
            drawable.setStroke(dp(1), border);
        }
        return drawable;
    }

    void add(LinearLayout container, View view, int top) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(top);
        container.addView(view, layout);
    }

    void pair(LinearLayout container, View first, View second, int top) {
        LinearLayout line = row();
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
        left.rightMargin = dp(8);
        line.addView(first, left);
        line.addView(second, new LinearLayout.LayoutParams(0, -2, 1));
        add(container, line, top);
    }

    void titleAndAction(LinearLayout container, View title, View action) {
        LinearLayout line = row();
        line.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(action, new LinearLayout.LayoutParams(-2, -2));
        add(container, line, 0);
    }
}
