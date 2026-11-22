package com.cikelab.spelling;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** 词间的界面基件：暖纸水印底、液态玻璃卡片与按钮、弹性按压。 */
final class StudyView {
    // 调色板：随主题与按键颜色由 palette() 改写，色值与 shared/ui/styles.css 对应。
    static int PAPER;
    static int PAPER_DEEP;
    static int INK;
    static int INK_SOFT;
    static int MUTED;
    static int CLAY;
    static int CLAY_DEEP;
    static final int GOLD = 0xFFC9A463;
    static final int CREAM = 0xFFF6EFE3;
    static final int CREAM_MUTED = 0xFFC9BCA8;
    static int SUCCESS;
    static int SUCCESS_TEXT;
    static int RETRY;
    static int WARNING;
    static int HINT;
    static int ROW;
    static int TRACK_OFF;
    static final int WHITE = Color.WHITE;
    static boolean DARK;
    private static int glassTop;
    private static int glassBottom;
    private static int glassEdge;
    private static int glassSheen;
    private static int groove;
    private static int grooveEdge;
    private static int sealFill;
    private static int scrim;
    private static int[] glows;
    private static Bitmap photo;
    // 设置里的其余外观（见 Look.Setting）。
    static float TEXT_SCALE = 1f;
    static boolean STILL;
    static boolean CALM;
    private static String mark = "词";
    private static String numeralId = "playfair";
    private static String badgeStyle = "laurel";

    static {
        colors(false, Look.Accent.CLAY, null, 1f, 1f);
    }

    /** 套用全部外观设置；须在建页面之前调用。 */
    static void palette(boolean dark, Look look) {
        float frost = "light".equals(look.get(Look.Setting.FROST)) ? .68f
                : "heavy".equals(look.get(Look.Setting.FROST)) ? 1.32f : 1f;
        float veil = "light".equals(look.get(Look.Setting.VEIL)) ? .45f
                : "heavy".equals(look.get(Look.Setting.VEIL)) ? 1.75f : 1f;
        colors(dark, look.accent(), look.photo(), frost, veil);
        String size = look.get(Look.Setting.TEXT_SIZE);
        TEXT_SCALE = "xlarge".equals(size) ? 1.25f : "large".equals(size) ? 1.125f : 1f;
        STILL = !look.on(Look.Setting.WIGGLE);
        CALM = !look.on(Look.Setting.ENTRANCE);
        mark = look.value(Look.Setting.WATERMARK);
        numeralId = look.get(Look.Setting.NUMERAL);
        badgeStyle = look.get(Look.Setting.BADGE);
    }

    private static void colors(boolean dark, Look.Accent accent, Bitmap background, float frost, float veil) {
        DARK = dark;
        photo = background;
        CLAY = accent.color;
        CLAY_DEEP = dark ? accent.light : accent.deep;
        if (dark) {
            PAPER = 0xFF1B1815;
            PAPER_DEEP = 0xFF110F0D;
            INK = 0xFFEDE5D8;
            INK_SOFT = 0xFFCFC4B4;
            MUTED = 0xFF9B907F;
            SUCCESS = 0xFF3A4E3E;
            SUCCESS_TEXT = 0xFF9CC7A6;
            RETRY = 0xFF5C3828;
            WARNING = 0xFFE8956F;
            HINT = 0xFF7A7062;
            ROW = 0x0CFFF0DC;
            TRACK_OFF = 0x40FFF0DC;
            glassTop = 0xDE342D27;
            glassBottom = 0xC4241F1B;
            glassEdge = 0x24FFF0DC;
            glassSheen = 0x14FFFFFF;
            groove = 0x47000000;
            grooveEdge = 0x10FFF0DC;
            sealFill = 0x731E1A16;
            scrim = 0x78110F0D;
            glows = new int[]{withAlpha(accent.color, 0x30), 0x2E5A7864, 0x28967846};
        } else {
            PAPER = 0xFFF3EFE6;
            PAPER_DEEP = 0xFFE9E2D4;
            INK = 0xFF24201B;
            INK_SOFT = 0xFF4A433A;
            MUTED = 0xFF7D7366;
            SUCCESS = 0xFFE3EBE0;
            SUCCESS_TEXT = 0xFF4F6B57;
            RETRY = 0xFFF6E3D6;
            WARNING = 0xFF9A4A26;
            HINT = 0xFFA39885;
            ROW = 0x61FFFCF6;
            TRACK_OFF = 0x55786450;
            glassTop = 0xD9FFFDF8;
            glassBottom = 0xA6FBF6EC;
            glassEdge = 0xC8FFFFFF;
            glassSheen = 0x66FFFFFF;
            groove = 0x1A786450;
            grooveEdge = 0x66FFFFFF;
            sealFill = 0x73FFF8F0;
            scrim = 0x4DF3EFE6;
            glows = new int[]{withAlpha(accent.color, 0x40), 0x388BA891, 0x3ED6B67A};
        }
        if (background != null) {
            glassTop = withAlpha(glassTop, 0xE6);
            glassBottom = withAlpha(glassBottom, dark ? 0xD2 : 0xC4);
        }
        glassTop = scaleAlpha(glassTop, frost, 0xF8);
        glassBottom = scaleAlpha(glassBottom, frost, 0xF4);
        scrim = scaleAlpha(scrim, veil, 0xE6);
    }

    private static int scaleAlpha(int color, float factor, int cap) {
        return withAlpha(color, Math.min(cap, Math.round((color >>> 24) * factor)));
    }

    /** 把颜色往白（amount>0）或黑（amount<0）调。 */
    static int shade(int color, float amount) {
        int target = amount > 0 ? 255 : 0;
        float t = Math.abs(amount);
        int r = Math.round(((color >> 16) & 0xFF) * (1 - t) + target * t);
        int g = Math.round(((color >> 8) & 0xFF) * (1 - t) + target * t);
        int b = Math.round((color & 0xFF) * (1 - t) + target * t);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    enum Style { PRIMARY, SECONDARY, TEXT, INK_FILL, SEGMENT, SEAL }

    final LinearLayout root;
    final LinearLayout body;
    private final Context context;
    private final Typeface serif;
    private final Typeface serifItalic;

    StudyView(Context context, boolean scroll) {
        this.context = context;
        serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL);
        serifItalic = Typeface.create(Typeface.SERIF, Typeface.ITALIC);
        root = column();
        root.setBackground(new PaperDrawable());
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        body = column();
        body.setPadding(dp(20), dp(18), dp(20), dp(28));
        body.setClipChildren(false);
        body.setClipToPadding(false);
        if (scroll) {
            ScrollView scroller = new ScrollView(context);
            scroller.setFillViewport(true);
            scroller.setClipToPadding(false);
            scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroller.setVerticalScrollBarEnabled(false);
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
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * TEXT_SCALE);
        text.setTextColor(color);
        text.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        text.setLineSpacing(dp(3), 1);
        flames(text);
        return text;
    }

    /**
     * 动态表情的关键帧，节奏与 shared/ui 的同名动画相同。
     * 每帧为 [进度, 横向缩放, 纵向缩放, 角度, 横移(字号倍数), 纵移(字号倍数)]。
     */
    private enum Lively {
        FIRE("🔥", 900, .15f, new float[][]{
                {0f, 1f, 1f, 0f, 0f, 0f}, {.25f, .94f, 1.12f, -4f, 0f, 0f}, {.5f, 1.06f, .95f, 3f, 0f, 0f},
                {.75f, .97f, 1.08f, -2f, 0f, 0f}, {1f, 1f, 1f, 0f, 0f, 0f}}),
        RUN("🏃", 1000, 0f, new float[][]{
                {0f, 1f, 1f, -4f, -.12f, 0f}, {.5f, 1f, 1f, 4f, .12f, -.18f}, {1f, 1f, 1f, -4f, -.12f, 0f}}),
        TARGET("🎯", 1200, 0f, new float[][]{
                {0f, 1f, 1f, 0f, 0f, 0f}, {.2f, 1.22f, 1.22f, 0f, 0f, 0f}, {.4f, .94f, .94f, 0f, 0f, 0f},
                {.6f, 1f, 1f, 0f, 0f, 0f}, {1f, 1f, 1f, 0f, 0f, 0f}}),
        FLEX("💪", 1100, 0f, new float[][]{
                {0f, 1f, 1f, 0f, 0f, 0f}, {.3f, 1.15f, 1.15f, -14f, 0f, 0f}, {.55f, 1.05f, 1.05f, 6f, 0f, 0f},
                {1f, 1f, 1f, 0f, 0f, 0f}}),
        ROCKET("🚀", 1400, 0f, new float[][]{
                {0f, 1f, 1f, 0f, 0f, 0f}, {.15f, 1f, 1f, 0f, -.06f, .06f}, {.3f, 1f, 1f, 0f, .06f, -.06f},
                {.6f, 1f, 1f, 0f, .3f, -.36f}, {.75f, 1f, 1f, 0f, 0f, 0f}, {1f, 1f, 1f, 0f, 0f, 0f}}),
        PUNCH("✊", 1000, 0f, new float[][]{
                {0f, 1f, 1f, 0f, 0f, 0f}, {.4f, 1.18f, 1.18f, 0f, 0f, -.36f}, {.6f, .96f, .96f, 0f, 0f, 0f},
                {1f, 1f, 1f, 0f, 0f, 0f}}),
        WRITE("✍️", 600, 0f, new float[][]{
                {0f, 1f, 1f, 0f, 0f, 0f}, {.25f, 1f, 1f, -8f, -.12f, 0f}, {.75f, 1f, 1f, 6f, .12f, 0f},
                {1f, 1f, 1f, 0f, 0f, 0f}}),
        SHINE("🌟", 1600, 0f, new float[][]{
                {0f, 1f, 1f, 0f, 0f, 0f}, {.5f, 1.2f, 1.2f, 18f, 0f, 0f}, {1f, 1f, 1f, 0f, 0f, 0f}}),
        SCAN("🔍", 1600, 0f, new float[][]{
                {0f, 1f, 1f, 0f, 0f, 0f}, {.25f, 1f, 1f, -10f, -.12f, -.05f}, {.75f, 1f, 1f, 10f, .12f, .05f},
                {1f, 1f, 1f, 0f, 0f, 0f}}),
        TWINKLE("✨", 1400, 0f, new float[][]{
                {0f, .9f, .9f, 0f, 0f, 0f}, {.5f, 1.15f, 1.15f, 15f, 0f, 0f}, {1f, .9f, .9f, 0f, 0f, 0f}}),
        PARTY("🎉", 1300, 0f, new float[][]{
                {0f, 1f, 1f, 0f, 0f, 0f}, {.15f, 1.15f, 1.15f, -14f, 0f, 0f}, {.3f, 1.05f, 1.05f, 8f, 0f, 0f},
                {.6f, 1f, 1f, 0f, 0f, 0f}, {1f, 1f, 1f, 0f, 0f, 0f}}),
        BULB("💡", 1500, 0f, new float[][]{
                {0f, 1f, 1f, 0f, 0f, 0f}, {.5f, 1f, 1f, 0f, 0f, -.1f}, {1f, 1f, 1f, 0f, 0f, 0f}}),
        GEM("💎", 1800, 0f, new float[][]{
                {0f, 1f, 1f, -6f, 0f, 0f}, {.5f, 1.12f, 1.12f, 6f, 0f, 0f}, {1f, 1f, 1f, -6f, 0f, 0f}});

        final String emoji;
        final long period;
        final float gap;
        final float[][] frames;

        Lively(String emoji, long period, float gap, float[][] frames) {
            this.emoji = emoji;
            this.period = period;
            this.gap = gap;
            this.frames = frames;
        }
    }

    /** 让文字里的 🔥、🏃、🎯 动起来；系统关闭动画时保持静止。 */
    static void flames(TextView view) {
        if (STILL) {
            return;
        }
        String plain = view.getText().toString();
        android.text.SpannableString spanned = null;
        java.util.List<android.animation.ValueAnimator> tickers = new java.util.ArrayList<>();
        for (Lively kind : Lively.values()) {
            int at = plain.indexOf(kind.emoji);
            if (at < 0) {
                continue;
            }
            if (spanned == null) {
                spanned = new android.text.SpannableString(view.getText());
            }
            android.animation.ValueAnimator ticker = android.animation.ValueAnimator.ofFloat(0f, 1f);
            ticker.setDuration(kind.period);
            ticker.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            ticker.setInterpolator(null);
            ticker.addUpdateListener(animation -> view.invalidate());
            tickers.add(ticker);
            while (at >= 0) {
                spanned.setSpan(new FlameSpan(kind, ticker), at, at + kind.emoji.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                at = plain.indexOf(kind.emoji, at + kind.emoji.length());
            }
        }
        if (spanned == null) {
            return;
        }
        view.setText(spanned);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View attached) {
                for (android.animation.ValueAnimator ticker : tickers) ticker.start();
            }
            @Override public void onViewDetachedFromWindow(View detached) {
                for (android.animation.ValueAnimator ticker : tickers) ticker.cancel();
            }
        });
        if (view.isAttachedToWindow()) {
            for (android.animation.ValueAnimator ticker : tickers) ticker.start();
        }
    }

    /** 动态表情字形：以底部为轴，按关键帧伸缩、摇摆、位移。 */
    private static final class FlameSpan extends android.text.style.ReplacementSpan {
        private final Lively kind;
        private final android.animation.ValueAnimator ticker;

        FlameSpan(Lively kind, android.animation.ValueAnimator ticker) {
            this.kind = kind;
            this.ticker = ticker;
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end,
                                     Paint.FontMetricsInt metrics) {
            if (metrics != null) {
                paint.getFontMetricsInt(metrics);
            }
            return Math.round(paint.measureText(text, start, end) + paint.getTextSize() * kind.gap);
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                                   int top, int y, int bottom, Paint paint) {
            float[][] frames = kind.frames;
            float phase = ticker.isRunning() ? (Float) ticker.getAnimatedValue() : 0f;
            int index = 0;
            while (index < frames.length - 2 && phase > frames[index + 1][0]) {
                index++;
            }
            float[] from = frames[index];
            float[] to = frames[index + 1];
            float t = (phase - from[0]) / (to[0] - from[0]);
            t = t * t * (3 - 2 * t);
            float size = paint.getTextSize();
            float left = x + size * kind.gap;
            float pivotX = left + paint.measureText(text, start, end) / 2;
            canvas.save();
            canvas.translate((from[4] + (to[4] - from[4]) * t) * size, (from[5] + (to[5] - from[5]) * t) * size);
            canvas.translate(pivotX, y);
            canvas.rotate(from[3] + (to[3] - from[3]) * t);
            canvas.scale(from[1] + (to[1] - from[1]) * t, from[2] + (to[2] - from[2]) * t);
            canvas.translate(-pivotX, -y);
            canvas.drawText(text, start, end, left, y, paint);
            canvas.restore();
        }
    }

    /** 宋体类衬线标题，字距舒展。 */
    TextView heading(String value, float size) {
        TextView heading = text(value, size, INK, false);
        heading.setTypeface(serif);
        heading.setLetterSpacing(size >= 26 ? .22f : .1f);
        if (Build.VERSION.SDK_INT >= 28) {
            heading.setAccessibilityHeading(true);
        }
        return heading;
    }

    /** 英文单词：衬线斜体。 */
    TextView word(String value, float size, int color) {
        TextView word = text(value, size, color, false);
        word.setTypeface(serifItalic);
        word.setTextLocale(java.util.Locale.US);
        return word;
    }

    /** 首页统计数字的艺术字（Playfair Display Italic，SIL OFL 1.1）；缺失时退回系统衬线斜体。 */
    private static final java.util.Map<String, Typeface> numeralFaces = new java.util.HashMap<>();

    /** 设置里选的数字字体；其余六种是只含 0–9 与「/」的子集（见 shared/ui/fonts/README.txt）。 */
    Typeface numeral() {
        return numeral(numeralId);
    }

    Typeface numeral(String id) {
        Typeface face = numeralFaces.get(id);
        if (face == null) {
            String asset = "playfair".equals(id) ? "PlayfairDisplay-Italic.ttf" : "fonts/" + id + ".ttf";
            try {
                face = Typeface.createFromAsset(context.getAssets(), asset);
            } catch (RuntimeException missing) {
                android.util.Log.w("Cijian", "Unable to load numeral font " + asset, missing);
                face = "playfair".equals(id) ? serifItalic : numeral("playfair");
            }
            numeralFaces.put(id, face);
        }
        return face;
    }

    /** 艺术字数字，后缀（如“ / 4544”“ 词”）缩小并转淡。 */
    TextView figure(String number, String suffix, float size) {
        android.text.SpannableString value = new android.text.SpannableString(number + suffix);
        int end = value.length();
        value.setSpan(new android.text.style.RelativeSizeSpan(.5f), number.length(), end, 0);
        value.setSpan(new android.text.style.ForegroundColorSpan(CREAM_MUTED), number.length(), end, 0);
        TextView figure = text("", size, CREAM, false);
        figure.setText(value);
        figure.setTypeface(numeral());
        figure.setIncludeFontPadding(false);
        figure.setSingleLine(true);
        return figure;
    }

    /** 轮次徽章：两枝金色月桂围住艺术字数字。 */
    View badge(int pass, boolean finished, String label, String tag) {
        return badge(pass, finished, label, tag, badgeStyle);
    }

    View badge(int pass, boolean finished, String label, String tag, String style) {
        View badge = new PassBadge(pass, finished, style, tag);
        badge.setContentDescription(label);
        return badge;
    }

    /** 小号大写字距标签，例如 CET-4。 */
    TextView tag(String value, int color) {
        TextView tag = text(value, 11, color, true);
        tag.setLetterSpacing(.32f);
        return tag;
    }

    Button button(String value, Style style, Runnable action) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(value);
        flames(button);
        button.setTextSize(15 * TEXT_SCALE);
        button.setLetterSpacing(.06f);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(18), dp(8), dp(18), dp(8));
        button.setStateListAnimator(null);
        int pill = 26;
        Drawable surface;
        int foreground;
        int disabled = MUTED;
        switch (style) {
            case PRIMARY:
                surface = clayPill(pill);
                foreground = 0xFFFFFAF3;
                disabled = 0xCCFFFAF3;
                button.setElevation(dp(4));
                shadow(button, CLAY_DEEP);
                break;
            case INK_FILL:
                surface = DARK ? layered(new int[]{0xF5F4ECE0, 0xF5D6CBBC}, pill, 0x80FFFAF0) : inkSurface(pill);
                foreground = DARK ? 0xFF24201B : CREAM;
                disabled = CREAM_MUTED;
                button.setElevation(dp(3));
                shadow(button, 0xFF2A1F14);
                break;
            case TEXT:
                surface = shape(Color.TRANSPARENT, pill, 0, 0);
                foreground = INK_SOFT;
                break;
            case SEGMENT:
                surface = shape(Color.TRANSPARENT, pill, 0, 0);
                foreground = MUTED;
                button.setMinHeight(dp(42));
                button.setMinimumHeight(dp(42));
                break;
            case SEAL:
                return seal(button, value, action);
            default:
                surface = glassSurface(pill);
                foreground = INK;
                button.setElevation(dp(2));
                shadow(button, 0xFF8A6A48);
        }
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{disabled, foreground}));
        int ripple = style == Style.PRIMARY || style == Style.INK_FILL ? 0x40FFF0E0 : withAlpha(CLAY, 0x26);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), surface,
                shape(WHITE, pill, 0, 0)));
        spring(button);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    /** 朱印「考」：方框细边、内衬一道细线，按下时盖印变实。 */
    private Button seal(Button button, String value, Runnable action) {
        button.setText(value);
        button.setTextSize(21);
        button.setLetterSpacing(0);
        button.setTypeface(serif);
        button.setPadding(0, 0, 0, 0);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        LayerDrawable idle = new LayerDrawable(new Drawable[]{
                shape(sealFill, 6, dp(1.5f), CLAY), shape(Color.TRANSPARENT, 3, 1, withAlpha(CLAY, 0x73))});
        idle.setLayerInset(1, dp(4), dp(4), dp(4), dp(4));
        LayerDrawable pressed = new LayerDrawable(new Drawable[]{
                shape(CLAY, 6, dp(1.5f), CLAY), shape(Color.TRANSPARENT, 3, 1, 0x80FFF0E6)});
        pressed.setLayerInset(1, dp(4), dp(4), dp(4), dp(4));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{}, idle);
        states.setExitFadeDuration(220);
        button.setBackground(states);
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_pressed}, new int[]{}},
                new int[]{0xFFFFFAF3, CLAY}));
        button.setRotation(-3);
        button.setElevation(dp(3));
        shadow(button, CLAY_DEEP);
        spring(button);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    /** 液态玻璃的手感：按下缩小，松手带回弹。 */
    @SuppressLint("ClickableViewAccessibility")
    void spring(View target) {
        target.setOnTouchListener((view, event) -> {
            if (!view.isEnabled()) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(.95f).scaleY(.95f).setDuration(110)
                            .setInterpolator(new DecelerateInterpolator()).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(420)
                            .setInterpolator(new OvershootInterpolator(3.2f)).start();
                    break;
            }
            return false;
        });
    }

    /** 浅色玻璃卡。 */
    LinearLayout glass() {
        LinearLayout card = column();
        card.setBackground(glassSurface(24));
        card.setPadding(dp(20), dp(19), dp(20), dp(19));
        card.setElevation(dp(5));
        shadow(card, 0xFF8A6A48);
        return card;
    }

    /** 带淡色的玻璃卡（答对 / 答错）。 */
    LinearLayout tinted(int color) {
        LinearLayout card = glass();
        card.setBackground(layered(new int[]{withAlpha(color, 0xE6), withAlpha(color, 0xC8)}, 24,
                DARK ? 0x24FFF0DC : 0xC0FFFFFF));
        return card;
    }

    /** 墨色玻璃卡，右下角压一枚大号水印。 */
    LinearLayout ink(String mark) {
        LinearLayout card = column();
        card.setBackground(inkSurface(26));
        card.setPadding(dp(22), dp(22), dp(22), dp(22));
        card.setElevation(dp(10));
        shadow(card, 0xFF2A1F14);
        if (mark != null) {
            card.setForeground(new WatermarkDrawable(mark, 0x12FFFFFF, Gravity.END | Gravity.BOTTOM, 104));
        }
        return card;
    }

    /** 以单词本身作背景水印。 */
    void watermark(View card, String mark) {
        card.setForeground(new WatermarkDrawable(mark, withAlpha(CLAY, 0x12), Gravity.CENTER, 118));
    }

    /** 分段选择槽。 */
    LinearLayout segment(Button... options) {
        LinearLayout track = row();
        track.setPadding(dp(4), dp(4), dp(4), dp(4));
        track.setBackground(shape(groove, 28, 1, grooveEdge));
        for (int index = 0; index < options.length; index++) {
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
            if (index < options.length - 1) {
                cell.rightMargin = dp(4);
            }
            if (options.length > 2) {
                options[index].setPadding(dp(6), dp(8), dp(6), dp(8));
            }
            track.addView(options[index], cell);
        }
        return track;
    }

    /** 按键颜色色板：圆形色点，选中时外加一圈并打勾。 */
    View swatch(Look.Accent accent, boolean chosen, Runnable action) {
        LinearLayout cell = column();
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClipChildren(false);
        View dot = new View(context);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{shade(accent.color, .2f), accent.color});
        fill.setShape(GradientDrawable.OVAL);
        if (chosen) {
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setStroke(dp(2), accent.color);
            LayerDrawable layers = new LayerDrawable(new Drawable[]{ring, fill, new CheckDrawable()});
            layers.setLayerInset(1, dp(5), dp(5), dp(5), dp(5));
            layers.setLayerInset(2, dp(14), dp(14), dp(14), dp(14));
            dot.setBackground(layers);
        } else {
            LayerDrawable layers = new LayerDrawable(new Drawable[]{fill});
            layers.setLayerInset(0, dp(5), dp(5), dp(5), dp(5));
            dot.setBackground(layers);
        }
        dot.setElevation(dp(3));
        cell.addView(dot, new LinearLayout.LayoutParams(dp(50), dp(50)));
        TextView label = text(accent.label, 12, chosen ? INK : MUTED, false);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(-2, -2);
        under.topMargin = dp(2);
        cell.addView(label, under);
        cell.setContentDescription("按键颜色 " + accent.label + (chosen ? "，已选" : ""));
        cell.setClickable(true);
        cell.setFocusable(true);
        spring(cell);
        cell.setOnClickListener(view -> action.run());
        return cell;
    }

    /** 设置里的选项格：上面是样例（表情、数字、徽章），下面是名字；选中时描一圈按键色。 */
    LinearLayout choice(View sample, String label, boolean chosen, boolean onInk, Runnable action) {
        LinearLayout cell = column();
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(4), dp(10), dp(4), dp(8));
        cell.setMinimumHeight(dp(88));
        Drawable surface = onInk ? inkSurface(18) : glassSurface(18);
        if (chosen) {
            LayerDrawable ringed = new LayerDrawable(new Drawable[]{surface,
                    shape(Color.TRANSPARENT, 18, dp(2), DARK && !onInk ? CLAY_DEEP : CLAY)});
            cell.setBackground(ringed);
        } else {
            cell.setBackground(surface);
        }
        cell.setElevation(dp(2));
        shadow(cell, onInk ? 0xFF2A1F14 : 0xFF8A6A48);
        cell.addView(sample, new LinearLayout.LayoutParams(-2, -2));
        int muted = onInk ? CREAM_MUTED : MUTED;
        int strong = onInk ? CREAM : INK;
        TextView name = text(label, 12, chosen ? strong : muted, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(-2, -2);
        under.topMargin = dp(4);
        cell.addView(name, under);
        cell.setContentDescription(label + (chosen ? "，已选" : ""));
        cell.setClickable(true);
        cell.setFocusable(true);
        spring(cell);
        cell.setOnClickListener(view -> action.run());
        return cell;
    }

    /** 把选项格排成每行 columns 个的网格，末行不足时留空。 */
    LinearLayout grid(java.util.List<View> cells, int columns) {
        LinearLayout grid = column();
        grid.setClipChildren(false);
        for (int start = 0; start < cells.size(); start += columns) {
            LinearLayout line = row();
            line.setClipChildren(false);
            line.setGravity(Gravity.TOP);
            for (int index = start; index < start + columns; index++) {
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
                if (index < start + columns - 1) {
                    cell.rightMargin = dp(8);
                }
                if (index < cells.size()) {
                    line.addView(cells.get(index), cell);
                } else {
                    line.addView(new View(context), cell);
                }
            }
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
            gap.topMargin = start == 0 ? 0 : dp(8);
            grid.addView(line, gap);
        }
        return grid;
    }

    /** 白色对勾。 */
    private final class CheckDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();

        CheckDrawable() {
            paint.setColor(WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(dp(2));
        }

        @Override public void draw(Canvas canvas) {
            Rect box = getBounds();
            float w = box.width();
            float h = box.height();
            path.reset();
            path.moveTo(box.left + w * .12f, box.top + h * .52f);
            path.lineTo(box.left + w * .4f, box.top + h * .8f);
            path.lineTo(box.left + w * .9f, box.top + h * .24f);
            canvas.drawPath(path, paint);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** 设置钮：三道横线各带一个圆钮（滑杆图形），与 shared/ui 的 SVG 一致。 */
    Button gear(Runnable action) {
        Button button = button("", Style.SECONDARY, action);
        button.setPadding(0, 0, 0, 0);
        button.setContentDescription("设置");
        button.setForeground(new SlidersDrawable());
        return button;
    }

    private final class SlidersDrawable extends Drawable {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);

        SlidersDrawable() {
            line.setColor(INK_SOFT);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeWidth(dp(1.6f));
            knob.setColor(glassTop | 0xFF000000);
        }

        @Override public void draw(Canvas canvas) {
            Rect box = getBounds();
            float unit = dp(20) / 24f;
            float left = box.exactCenterX() - 12 * unit;
            float top = box.exactCenterY() - 12 * unit;
            float[][] rows = {{7, 9}, {12, 15.5f}, {17, 7.5f}};
            for (float[] row : rows) {
                canvas.drawLine(left + 4 * unit, top + row[0] * unit, left + 20 * unit, top + row[0] * unit, line);
            }
            for (float[] row : rows) {
                canvas.drawCircle(left + row[1] * unit, top + row[0] * unit, 2.2f * unit, knob);
                canvas.drawCircle(left + row[1] * unit, top + row[0] * unit, 2.2f * unit, line);
            }
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** 对话框按钮跟随按键颜色（系统对话框主题里的强调色是写死的）。 */
    static android.app.AlertDialog tint(android.app.AlertDialog dialog) {
        for (int which : new int[]{android.app.AlertDialog.BUTTON_POSITIVE,
                android.app.AlertDialog.BUTTON_NEGATIVE, android.app.AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button != null) {
                button.setTextColor(new ColorStateList(
                        new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                        new int[]{withAlpha(MUTED, 0x99), DARK ? CLAY_DEEP : CLAY}));
            }
        }
        return dialog;
    }

    ProgressBar meter(int max, int value) {
        ProgressBar meter = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{CLAY, GOLD});
        fill.setCornerRadius(dp(3));
        Drawable track = shape(0x24FFF0DC, 3, 0, 0);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{track,
                new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        meter.setProgressDrawable(layers);
        meter.setMax(Math.max(1, max));
        meter.setProgress(value);
        meter.setMinimumHeight(dp(4));
        return meter;
    }

    Drawable field(int radius) {
        return glassSurface(radius);
    }

    GradientDrawable rounded(int color, int radius, int border) {
        return shape(color, radius, border == 0 ? 0 : dp(1), border);
    }

    GradientDrawable shape(int color, float radius, int stroke, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke > 0) {
            drawable.setStroke(stroke, strokeColor);
        }
        return drawable;
    }

    private Drawable glassSurface(int radius) {
        return layered(new int[]{glassTop, glassBottom}, radius, glassEdge);
    }

    private Drawable clayPill(int radius) {
        return layered(new int[]{shade(CLAY, .16f), shade(CLAY, -.1f)}, radius, 0x66FFE6D8);
    }

    private Drawable inkSurface(int radius) {
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                DARK ? new int[]{0xF0453C32, 0xF5272219} : new int[]{0xF03E362D, 0xF51E1A15});
        fill.setCornerRadius(dp(radius));
        fill.setStroke(1, 0x30FFF0DC);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x1FFFE6C8, 0x00FFE6C8});
        sheen.setCornerRadius(dp(radius));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{fill, sheen});
        layers.setLayerInset(1, 1, 1, 1, dp(radius) * 2);
        return layers;
    }

    /** 竖向渐变填充 + 白色描边 + 顶部一道高光，模拟玻璃折射。 */
    private Drawable layered(int[] colors, int radius, int edge) {
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
        fill.setCornerRadius(dp(radius));
        fill.setStroke(1, edge);
        GradientDrawable highlight = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{glassSheen, 0x00FFFFFF});
        highlight.setCornerRadius(dp(radius));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{fill, highlight});
        layers.setLayerInset(1, 1, 1, 1, 0);
        layers.setLayerHeight(1, dp(Math.min(radius, 22)));
        return layers;
    }

    private void shadow(View view, int color) {
        if (Build.VERSION.SDK_INT >= 28) {
            view.setOutlineAmbientShadowColor(color);
            view.setOutlineSpotShadowColor(color);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    void add(LinearLayout container, View view, int top) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(top);
        container.addView(view, layout);
    }

    void pair(LinearLayout container, View first, View second, int top) {
        LinearLayout line = row();
        line.setClipChildren(false);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
        left.rightMargin = dp(10);
        line.addView(first, left);
        line.addView(second, new LinearLayout.LayoutParams(0, -2, 1));
        add(container, line, top);
    }

    void titleAndAction(LinearLayout container, View title, View action) {
        LinearLayout line = row();
        line.setClipChildren(false);
        line.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            line.addView(action, new LinearLayout.LayoutParams(-2, -2));
        }
        add(container, line, 0);
    }

    /** 换页时各块依次浮现。 */
    void enter() {
        for (int index = 0; index < body.getChildCount(); index++) {
            View child = body.getChildAt(index);
            child.setAlpha(0f);
            child.setTranslationY(dp(14));
            child.animate().alpha(1f).translationY(0).setStartDelay(Math.min(index, 6) * 40L)
                    .setDuration(520).setInterpolator(new DecelerateInterpolator(2.2f)).start();
        }
    }

    /** 底纸：暖色渐变、三团柔光，以及右下角的宋体「词」水印。 */
    private final class PaperDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);

        PaperDrawable() {
            glyph.setTypeface(serif);
            glyph.setColor(withAlpha(INK, 0x0C));
            glyph.setTextAlign(Paint.Align.RIGHT);
        }

        @Override public void draw(Canvas canvas) {
            Rect box = getBounds();
            float width = box.width();
            float height = box.height();
            paint.setShader(new LinearGradient(0, 0, 0, height, PAPER, PAPER_DEEP, Shader.TileMode.CLAMP));
            canvas.drawRect(box, paint);
            paint.setShader(null);
            if (photo != null) {
                // 自定义背景：居中裁切铺满，再压一层主题色薄纱；不画水印。
                float scale = Math.max(width / photo.getWidth(), height / photo.getHeight());
                float left = box.left + (width - photo.getWidth() * scale) / 2;
                float top = box.top + (height - photo.getHeight() * scale) / 2;
                android.graphics.RectF target = new android.graphics.RectF(left, top,
                        left + photo.getWidth() * scale, top + photo.getHeight() * scale);
                paint.setFilterBitmap(true);
                canvas.save();
                canvas.clipRect(box);
                canvas.drawBitmap(photo, null, target, paint);
                canvas.restore();
                paint.setColor(scrim);
                canvas.drawRect(box, paint);
                return;
            }
            glow(canvas, width * .15f, height * .16f, width * .75f, glows[0]);
            glow(canvas, width * .9f, height * .36f, width * .8f, glows[1]);
            glow(canvas, width * .55f, height * .92f, width * .75f, glows[2]);
            paint.setShader(null);
            glyph.setTextSize(Math.min(width, height) * .9f);
            if (!mark.isEmpty()) {
                canvas.drawText(mark, width + width * .08f, height + glyph.getTextSize() * .06f, glyph);
            }
        }

        private void glow(Canvas canvas, float x, float y, float radius, int color) {
            paint.setShader(new RadialGradient(x, y, radius, color, color & 0x00FFFFFF,
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, radius, paint);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /**
     * 轮次徽章，七种样式（设置 → 首页样式），都画在 66×66 的画布上，
     * 与 shared/ui/app.js 的 badgeShapes 一致。徽章总是放在墨色底上。
     */
    private final class PassBadge extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint digit = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF ring = new android.graphics.RectF(3, 3, 63, 63);
        private final android.graphics.RectF arc = new android.graphics.RectF(3, 3, 63, 63);
        private final android.graphics.RectF oval = new android.graphics.RectF(-2.4f, -5.6f, 2.4f, 5.6f);
        private final String number;
        private final int lit;
        private final String style;
        private final String tag;

        PassBadge(int pass, boolean finished, String style, String tag) {
            super(context);
            this.number = String.valueOf(pass);
            this.lit = finished ? 5 : pass;
            this.style = style;
            this.tag = tag;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1);
            line.setColor(GOLD);
            fill.setColor(GOLD);
            digit.setColor(GOLD);
            digit.setTypeface(numeral());
            digit.setTextAlign(Paint.Align.CENTER);
            digit.setTextSize("orbit".equals(style) ? 25 : 33);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override protected void onDraw(Canvas canvas) {
            float scale = Math.min(getWidth(), getHeight()) / 66f;
            canvas.save();
            canvas.translate((getWidth() - 66 * scale) / 2, (getHeight() - 66 * scale) / 2);
            canvas.scale(scale, scale);
            int seal = shade(CLAY, .3f);
            switch (style) {
                case "ring":
                    line.setStrokeWidth(1.4f);
                    canvas.drawCircle(33, 33, 31, line);
                    break;
                case "seal":
                    line.setColor(seal);
                    line.setStrokeWidth(2);
                    canvas.drawCircle(33, 33, 31, line);
                    line.setStrokeWidth(1);
                    line.setPathEffect(new android.graphics.DashPathEffect(new float[]{2, 3}, 0));
                    canvas.drawCircle(33, 33, 26, line);
                    line.setPathEffect(null);
                    digit.setColor(seal);
                    break;
                case "progress":
                    line.setStrokeWidth(3);
                    line.setStrokeCap(Paint.Cap.ROUND);
                    arc.set(3, 3, 63, 63);
                    for (int i = 0; i < 5; i++) {
                        boolean on = i < lit;
                        line.setColor(on ? GOLD : 0x2EFFF0DC);
                        line.setShadowLayer(on ? 3 : 0, 0, 0, 0xB3E2AA6E);
                        canvas.drawArc(arc, -90 + i * 72 + 5, 62, false, line);
                    }
                    line.clearShadowLayer();
                    break;
                case "stamp":
                    canvas.save();
                    canvas.rotate(-8, 33, 33);
                    fill.setColor(CLAY);
                    canvas.drawCircle(33, 33, 31, fill);
                    line.setColor(0x8CF6EFE3);
                    canvas.drawCircle(33, 33, 27, line);
                    canvas.restore();
                    digit.setColor(CREAM);
                    break;
                case "glass":
                    fill.setShader(new RadialGradient(23, 18, 53,
                            new int[]{0x73FFFFFF, 0x38E8C993, 0x59C2643F}, new float[]{0f, .45f, 1f},
                            Shader.TileMode.CLAMP));
                    canvas.drawCircle(33, 33, 31, fill);
                    fill.setShader(null);
                    line.setColor(0x73FFFFFF);
                    canvas.drawCircle(33, 33, 31, line);
                    fill.setColor(0x59FFFFFF);
                    canvas.save();
                    canvas.rotate(-25, 24, 18);
                    canvas.drawOval(new android.graphics.RectF(13, 13, 35, 23), fill);
                    canvas.restore();
                    digit.setColor(0xFFFFF7EA);
                    digit.setShadowLayer(6, 0, 1, 0x59000000);
                    break;
                case "orbit":
                    canvas.drawCircle(33, 33, 20, line);
                    android.graphics.Path path = new android.graphics.Path();
                    path.addCircle(33, 33, 27, android.graphics.Path.Direction.CW);
                    fill.setTextSize(6.4f);
                    fill.setLetterSpacing(.34f);
                    fill.setTypeface(Typeface.SERIF);
                    canvas.drawTextOnPath("THE · PASS · " + tag + " · THE · PASS · " + tag + " ·",
                            path, 0, 0, fill);
                    break;
                default:
                    // 月桂：两道弧枝，叶片沿半径 30 的圆弧自下而上排开，越往上越淡。
                    canvas.drawArc(ring, 129.8f, 116.2f, false, line);
                    canvas.drawArc(ring, 50.2f, -116.2f, false, line);
                    for (int side = -1; side <= 1; side += 2) {
                        for (int i = 0; i < 6; i++) {
                            double angle = Math.toRadians(155 - i * 22);
                            float x = (float) (33 + side * 30 * Math.sin(angle));
                            float y = (float) (33 - 30 * Math.cos(angle));
                            fill.setAlpha(Math.round(255 * (1 - i * .08f)));
                            canvas.save();
                            canvas.translate(x, y);
                            canvas.rotate(side * (155 - i * 22 - 145));
                            canvas.drawOval(oval, fill);
                            canvas.restore();
                        }
                    }
            }
            Paint.FontMetrics metrics = digit.getFontMetrics();
            canvas.drawText(number, 33, 33 - (metrics.ascent + metrics.descent) / 2, digit);
            canvas.restore();
        }
    }

    /** 衬线斜体水印。 */
    private final class WatermarkDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String mark;
        private final int gravity;
        private final float size;

        WatermarkDrawable(String mark, int color, int gravity, float size) {
            this.mark = mark;
            this.gravity = gravity;
            this.size = size;
            paint.setTypeface(serifItalic);
            paint.setColor(color);
        }

        @Override public void draw(Canvas canvas) {
            Rect box = getBounds();
            paint.setTextSize(dp(size));
            float textWidth = paint.measureText(mark);
            canvas.save();
            canvas.clipRect(box);
            if (gravity == Gravity.CENTER) {
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(mark, box.exactCenterX(),
                        box.exactCenterY() + paint.getTextSize() * .32f, paint);
            } else {
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(mark, box.right - textWidth + dp(6), box.bottom + dp(size) * .18f, paint);
            }
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
