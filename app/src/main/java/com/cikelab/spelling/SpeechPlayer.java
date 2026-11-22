package com.cikelab.spelling;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class SpeechPlayer {
    interface Listener {
        void status(String message);
    }

    private static final String UNAVAILABLE = "英语语音暂不可用，请检查手机的文字转语音设置后重试。";
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech engine;
    private boolean initialized;
    private boolean starting;
    private boolean released;
    private int sequence;
    private String requestId;
    private String pendingText;
    private Listener listener;
    private final Runnable timeout = () -> fail(requestId);

    SpeechPlayer(Context context) {
        this.context = context.getApplicationContext();
    }

    void play(String word, Listener callback) {
        stop();
        requestId = "speech-" + ++sequence;
        pendingText = word;
        listener = callback;
        callback.status("正在准备发音…");
        main.postDelayed(timeout, 8000);
        if (initialized) {
            speak();
        } else if (!starting) {
            startEngine();
        }
    }

    private void startEngine() {
        starting = true;
        engine = new TextToSpeech(context, result -> main.post(() -> {
            if (released) {
                return;
            }
            starting = false;
            if (result != TextToSpeech.SUCCESS) {
                engine.shutdown();
                engine = null;
                fail(requestId);
                return;
            }
            initialized = true;
            engine.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    main.post(() -> report(id, "正在播放…", false));
                }

                @Override public void onDone(String id) {
                    main.post(() -> report(id, "播放完毕，可重听。", true));
                }

                @Override public void onError(String id) {
                    main.post(() -> fail(id));
                }

                @Override public void onError(String id, int code) {
                    main.post(() -> fail(id));
                }
            });
            if (requestId != null) {
                speak();
            }
        }));
    }

    private void speak() {
        Set<Voice> available = engine.getVoices();
        List<Voice> english = new ArrayList<>();
        if (available != null) {
            for (Voice voice : available) {
                Set<String> features = voice.getFeatures();
                if (Locale.ENGLISH.getLanguage().equals(voice.getLocale().getLanguage())
                        && (features == null || !features.contains(
                                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) {
                    english.add(voice);
                }
            }
        }
        english.sort(Comparator.comparing(Voice::isNetworkConnectionRequired)
                .thenComparing(voice -> !Locale.US.getCountry().equals(voice.getLocale().getCountry()))
                .thenComparing(Comparator.comparingInt(Voice::getQuality).reversed())
                .thenComparing(Voice::getName));
        for (Voice voice : english) {
            if (engine.setVoice(voice) == TextToSpeech.SUCCESS) {
                int result = engine.speak(pendingText, TextToSpeech.QUEUE_FLUSH, null, requestId);
                if (result == TextToSpeech.SUCCESS) {
                    return;
                }
            }
        }
        if (english.isEmpty()) {
            for (Locale locale : new Locale[]{Locale.US, Locale.UK, Locale.ENGLISH}) {
                if (engine.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE
                        && engine.speak(pendingText, TextToSpeech.QUEUE_FLUSH, null, requestId)
                        == TextToSpeech.SUCCESS) {
                    return;
                }
            }
        }
        fail(requestId);
    }

    private void fail(String id) {
        if (id != null && id.equals(requestId) && engine != null) {
            engine.stop();
        }
        report(id, UNAVAILABLE, true);
    }

    private void report(String id, String message, boolean terminal) {
        if (id == null || !id.equals(requestId)) {
            return;
        }
        Listener callback = listener;
        if (terminal) {
            clearRequest();
        }
        callback.status(message);
    }

    private void clearRequest() {
        requestId = null;
        pendingText = null;
        listener = null;
        main.removeCallbacks(timeout);
    }

    void stop() {
        clearRequest();
        if (initialized) {
            engine.stop();
        }
    }

    void release() {
        released = true;
        stop();
        if (engine != null) {
            engine.shutdown();
            engine = null;
        }
        initialized = false;
    }
}
