package com.cikelab.spelling;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class StudyPlanner {
    static final int DEFAULT_NEW_WORDS = 35;
    static final int BATCH_SIZE = 35;
    static final int LAST_PASS = 5;
    static final long NEVER = Long.MIN_VALUE;

    enum Status {
        UNMARKED, MASTERED, REVIEW
    }

    enum Kind {
        NEW("新词🔥"), DUE("到期复习"), WEEKEND("周末复习"), PASS("本轮复习"), EXAM("临考生词");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    static final class Progress {
        Status status = Status.UNMARKED;
        long viewed = NEVER;
        long firstPass = NEVER;
        long due = NEVER;
        int unfamiliarPasses;
        int reviewedPasses;
    }

    static int bit(int pass) {
        return 1 << (pass - 1);
    }

    static boolean weekend(long day) {
        return LocalDate.ofEpochDay(day).getDayOfWeek().getValue() >= 6;
    }

    static boolean allowsNewWords(long day, boolean learnOnWeekends) {
        return learnOnWeekends || !weekend(day);
    }

    static long nextReviewDay(long day) {
        return day + 1;
    }

    static void mark(Progress entry, Status status, long day, int pass, boolean practiced) {
        if (status == Status.REVIEW) {
            entry.unfamiliarPasses |= bit(pass);
            if (entry.status != status || practiced) {
                entry.due = nextReviewDay(day);
            }
        } else {
            entry.due = NEVER;
        }
        entry.status = status;
    }

    static int nextPass(int pass) {
        return Math.min(LAST_PASS, pass + 1);
    }

    static int viewedToday(Map<String, Progress> entries, long day) {
        int viewed = 0;
        for (Progress entry : entries.values()) {
            if (entry.viewed == day) {
                viewed++;
            }
        }
        return viewed;
    }

    static int firstPassCount(List<String> catalog, Map<String, Progress> entries) {
        int finished = 0;
        for (String key : catalog) {
            Progress entry = entries.get(key);
            if (entry != null && entry.firstPass != NEVER) {
                finished++;
            }
        }
        return finished;
    }

    /** 首轮已读：读过学习卡（或已完成首轮默写）的词数。 */
    static int readCount(List<String> catalog, Map<String, Progress> entries) {
        int read = 0;
        for (String key : catalog) {
            Progress entry = entries.get(key);
            if (entry != null && (entry.viewed != NEVER || entry.firstPass != NEVER)) {
                read++;
            }
        }
        return read;
    }

    static List<String> newWords(List<String> catalog, Map<String, Progress> entries,
                                 long day, int quota, boolean learnOnWeekends) {
        List<String> result = new ArrayList<>();
        if (!allowsNewWords(day, learnOnWeekends)) {
            return result;
        }
        int left = Math.max(0, quota - viewedToday(entries, day));
        for (String key : catalog) {
            Progress entry = entries.get(key);
            if (entry != null && entry.viewed != NEVER && entry.firstPass == NEVER) {
                result.add(key);
            }
        }
        for (String key : catalog) {
            if (result.size() >= quota || left == 0) {
                break;
            }
            Progress entry = entries.get(key);
            if (entry == null || (entry.viewed == NEVER && entry.firstPass == NEVER)) {
                result.add(key);
                left--;
            }
        }
        return take(result, quota);
    }

    static List<String> review(Map<String, Progress> entries, long day, boolean includeFuture) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Progress> item : entries.entrySet()) {
            Progress entry = item.getValue();
            if (entry.status == Status.REVIEW && (includeFuture || entry.due <= day)) {
                result.add(item.getKey());
            }
        }
        result.sort(Comparator.<String>comparingLong(key -> entries.get(key).due)
                .thenComparing(Comparator.naturalOrder()));
        return result;
    }

    static List<String> reviewChoices(Map<String, Progress> entries, int pass, Kind kind, long day) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Progress> item : entries.entrySet()) {
            Progress entry = item.getValue();
            if (entry.status != Status.UNMARKED || entry.viewed != NEVER) {
                result.add(item.getKey());
            }
        }
        result.sort(Comparator.<String>comparingInt(key -> priority(entries.get(key), pass, kind))
                .thenComparingInt(key -> checkedInPass(entries.get(key), pass, kind) ? 1 : 0)
                .thenComparingLong(key -> {
                    Progress entry = entries.get(key);
                    return entry.status == Status.REVIEW ? entry.due : day;
                })
                .thenComparing(Comparator.naturalOrder()));
        return result;
    }

    static int priority(Progress entry, int pass, Kind kind) {
        if (entry.status == Status.REVIEW) {
            return 0;
        }
        if ((kind == Kind.PASS || kind == Kind.WEEKEND) && pass > 1
                && (entry.unfamiliarPasses & bit(pass - 1)) != 0) {
            return 1;
        }
        return 2;
    }

    static boolean checkedInPass(Progress entry, int pass, Kind kind) {
        return kind == Kind.PASS && pass > 1 && (entry.reviewedPasses & bit(pass)) != 0;
    }

    static int passReviewCount(Map<String, Progress> entries, int pass) {
        int count = 0;
        for (Progress entry : entries.values()) {
            if (checkedInPass(entry, pass, Kind.PASS)) {
                count++;
            }
        }
        return count;
    }

    static List<String> take(List<String> items, int size) {
        return new ArrayList<>(items.subList(0, Math.min(size, items.size())));
    }

    private StudyPlanner() {}
}
