package com.cikelab.spelling;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 外观设置：主题（浅色 / 深色 / 跟随系统）、按键颜色与背景照片。
 * 与 shared/ui/look.js 的选项和色值保持一致；只存在本机，不进学习记录。
 */
final class Look {
    enum Theme {
        LIGHT("浅色"), DARK("深色"), SYSTEM("跟随系统");

        final String label;

        Theme(String label) {
            this.label = label;
        }
    }

    enum Accent {
        CLAY("陶土", 0xFFC2643F, 0xFF9E4C2D, 0xFFE08A64),
        INDIGO("黛蓝", 0xFF47699A, 0xFF34507A, 0xFF7D9FD0),
        PINE("松青", 0xFF3E8573, 0xFF2D6658, 0xFF6FB9A4),
        ROUGE("胭脂", 0xFFB04660, 0xFF8A3249, 0xFFDA7A91),
        WISTERIA("藤紫", 0xFF7B5EA7, 0xFF5F4686, 0xFFA98FD3);

        final String label;
        final int color;
        final int deep;
        final int light;

        Accent(String label, int color, int deep, int light) {
            this.label = label;
            this.color = color;
            this.deep = deep;
            this.light = light;
        }
    }

    /** 新词与查阅按钮共用的七个「奋斗」表情；STUDY_EMOJI 在选项表里代表这一整组。 */
    private static final String STUDY_EMOJI = "*study";
    private static final String[] STUDY = {"💪", "举臂发力", "🔥", "火苗跃动", "🚀", "火箭冲刺",
            "✊", "握拳打气", "🏃", "奔跑", "✍️", "奋笔疾书", "🌟", "闪亮星星"};

    /**
     * 其余外观选项：每项是「取值 id → 中文名」的有序表，与 shared/ui/look.js 的 options 一一对应。
     * 表情的 id 就是表情本身；「无」统一存为 none。
     */
    enum Setting {
        NEW_EMOJI("newEmoji", "🔥", STUDY_EMOJI, "none", "无"),
        SEARCH_EMOJI("searchEmoji", "🏃", STUDY_EMOJI, "🔍", "放大镜", "none", "无"),
        FOUND_EMOJI("foundEmoji", "🎯", "🔍", "扫视", "🎯", "正中靶心", "✨", "闪闪发光", "🎉", "撒花庆祝",
                "💡", "灵光一闪", "💎", "寻到宝石", "none", "无"),
        NUMERAL("numeral", "playfair", "playfair", "Playfair", "bodoni", "Bodoni", "cormorant", "Cormorant",
                "abril", "Abril", "cinzel", "Cinzel", "italiana", "Italiana", "fraunces", "Fraunces"),
        BADGE("badge", "laurel", "ring", "细环", "seal", "双环印", "progress", "进度环", "stamp", "朱砂印",
                "laurel", "月桂", "glass", "玻璃球", "orbit", "铭文环"),
        WATERMARK("watermark", "词", "词", "词", "学", "学", "勤", "勤", "书", "书", "none", "无"),
        FROST("frost", "medium", "light", "轻", "medium", "中", "heavy", "浓"),
        VEIL("veil", "medium", "light", "轻", "medium", "中", "heavy", "浓"),
        TEXT_SIZE("textSize", "normal", "normal", "标准", "large", "大", "xlarge", "特大"),
        WIGGLE("wiggle", "on", "on", "开", "off", "关"),
        ENTRANCE("entrance", "on", "on", "开", "off", "关");

        final String key;
        final String fallback;
        final String[] ids;
        final String[] labels;

        Setting(String key, String fallback, String... pairs) {
            this.key = key;
            this.fallback = fallback;
            java.util.List<String> flat = new java.util.ArrayList<>();
            for (String item : pairs) {
                if (item.equals(STUDY_EMOJI)) {
                    flat.addAll(java.util.Arrays.asList(STUDY));
                } else {
                    flat.add(item);
                }
            }
            ids = new String[flat.size() / 2];
            labels = new String[flat.size() / 2];
            for (int index = 0; index < ids.length; index++) {
                ids[index] = flat.get(index * 2);
                labels[index] = flat.get(index * 2 + 1);
            }
        }

        String label(String id) {
            for (int index = 0; index < ids.length; index++) {
                if (ids[index].equals(id)) return labels[index];
            }
            return id;
        }

        boolean accepts(String id) {
            return java.util.Arrays.asList(ids).contains(id);
        }
    }

    String get(Setting setting) {
        String id = preferences.getString(setting.key, setting.fallback);
        return setting.accepts(id) ? id : setting.fallback;
    }

    /** 取值；「无」返回空字符串。 */
    String value(Setting setting) {
        String id = get(setting);
        return "none".equals(id) ? "" : id;
    }

    void set(Setting setting, String id) {
        if (setting.accepts(id)) {
            preferences.edit().putString(setting.key, id).apply();
        }
    }

    boolean on(Setting setting) {
        return "on".equals(get(setting));
    }

    private static final String PREFERENCES = "look";
    private static final String PHOTO = "background.jpg";
    private static final int LONG_SIDE = 1600;
    private static Bitmap photoCache;
    private static long photoStamp = -1;

    private final Context context;
    private final SharedPreferences preferences;

    Look(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    Theme theme() {
        try {
            return Theme.valueOf(preferences.getString("theme", Theme.LIGHT.name()));
        } catch (IllegalArgumentException unknown) {
            return Theme.LIGHT;
        }
    }

    void theme(Theme theme) {
        preferences.edit().putString("theme", theme.name()).apply();
    }

    Accent accent() {
        try {
            return Accent.valueOf(preferences.getString("accent", Accent.CLAY.name()));
        } catch (IllegalArgumentException unknown) {
            return Accent.CLAY;
        }
    }

    void accent(Accent accent) {
        preferences.edit().putString("accent", accent.name()).apply();
    }

    /** 实际生效的是否为深色。 */
    boolean dark(Context current) {
        Theme theme = theme();
        if (theme == Theme.SYSTEM) {
            int night = current.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return night == Configuration.UI_MODE_NIGHT_YES;
        }
        return theme == Theme.DARK;
    }

    private File photoFile() {
        return new File(context.getFilesDir(), PHOTO);
    }

    boolean hasPhoto() {
        return photoFile().isFile();
    }

    /** 读出已保存的背景照片；同一文件只解码一次。 */
    Bitmap photo() {
        File file = photoFile();
        if (!file.isFile()) {
            photoCache = null;
            photoStamp = -1;
            return null;
        }
        if (photoCache == null || photoStamp != file.lastModified()) {
            photoCache = BitmapFactory.decodeFile(file.getAbsolutePath());
            photoStamp = file.lastModified();
        }
        return photoCache;
    }

    /** 从相册读入照片：按长边 1600 像素缩小、按 EXIF 摆正，再存成 JPEG。在后台线程调用。 */
    void savePhoto(Uri source) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(source)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("这张照片的格式暂不支持。");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= LONG_SIDE) {
            options.inSampleSize *= 2;
        }
        Bitmap decoded;
        try (InputStream in = open(source)) {
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        if (decoded == null) {
            throw new IOException("这张照片的格式暂不支持。");
        }
        int rotation = 0;
        try (InputStream in = open(source)) {
            int orientation = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
        } catch (IOException | RuntimeException noExif) {
            rotation = 0;
        }
        float scale = Math.min(1f, (float) LONG_SIDE / Math.max(decoded.getWidth(), decoded.getHeight()));
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        matrix.postRotate(rotation);
        Bitmap fitted = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
        File target = photoFile();
        File temporary = new File(target.getPath() + ".new");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            if (!fitted.compress(Bitmap.CompressFormat.JPEG, 86, out)) {
                throw new IOException("照片没能保存。");
            }
        }
        if (!temporary.renameTo(target)) {
            target.delete();
            if (!temporary.renameTo(target)) {
                throw new IOException("照片没能保存。");
            }
        }
        photoCache = fitted;
        photoStamp = target.lastModified();
    }

    void clearPhoto() {
        photoFile().delete();
        photoCache = null;
        photoStamp = -1;
    }

    private InputStream open(Uri source) throws IOException {
        InputStream in = context.getContentResolver().openInputStream(source);
        if (in == null) {
            throw new IOException("无法读取这张照片。");
        }
        return in;
    }
}
