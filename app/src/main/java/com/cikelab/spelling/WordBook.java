package com.cikelab.spelling;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

enum WordBook {
    CET4("英语四级"),
    CET6("英语六级");

    final String label;

    WordBook(String label) {
        this.label = label;
    }

    static final class Word {
        final String spelling;
        final String meanings;
        final String clue;
        final List<String> relatives;
        final List<String> variants;

        Word(String spelling, List<String> senses, List<String> relatives, List<String> variants) {
            this.spelling = spelling;
            this.meanings = String.join("\n", senses);
            String masked = String.join("\n", senses.subList(0, Math.min(3, senses.size())));
            List<String> answers = new ArrayList<>(variants);
            answers.add(spelling);
            for (String answer : answers) {
                Pattern pattern = Pattern.compile("(?<![A-Za-z])" + Pattern.quote(answer)
                        + "(?![A-Za-z])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                masked = pattern.matcher(masked).replaceAll("____");
            }
            this.clue = masked;
            this.relatives = relatives;
            this.variants = variants;
        }

        boolean matches(String answer) {
            return Practice.matches(answer, spelling)
                    || variants.stream().anyMatch(variant -> Practice.matches(answer, variant));
        }
    }
}

