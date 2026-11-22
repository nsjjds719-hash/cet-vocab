package com.cikelab.spelling;

import java.text.Normalizer;
import java.util.Locale;

final class Practice {
    enum Mode {
        CHINESE("依义默写"),
        LISTEN("闻声默写");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'').replace('\u2018', '\'')
                .replace('\u2010', '-').replace('\u2011', '-')
                .replaceAll("\\s+", " ");
    }

    static boolean matches(String input, String answer) {
        String normalized = normalize(input);
        return !normalized.isEmpty() && normalized.equals(normalize(answer));
    }
}

