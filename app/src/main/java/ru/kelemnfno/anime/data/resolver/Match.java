package ru.kelemnfno.anime.data.resolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.kelemnfno.anime.data.model.Lookup;

/** Строгое сопоставление тайтлов между источниками: лучше ничего, чем чужое аниме. */
public final class Match {

    public static final double ACCEPT = 0.8;

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "tv", "тв", Cfg.s(481), "a", "an", "of", Cfg.s(465), "и", Cfg.s(475), Cfg.s(493), Cfg.s(474), Cfg.s(500), Cfg.s(467),
            Cfg.s(489), Cfg.s(466), Cfg.s(497), Cfg.s(491), Cfg.s(477), Cfg.s(496), Cfg.s(495), Cfg.s(471), Cfg.s(499),
            Cfg.s(473), Cfg.s(472), Cfg.s(478), Cfg.s(498), Cfg.s(270), Cfg.s(480), Cfg.s(492), Cfg.s(490)));

    private static final Map<String, Integer> ROMAN = new HashMap<>();

    static {
        ROMAN.put("i", 1);
        ROMAN.put("ii", 2);
        ROMAN.put(Cfg.s(469), 3);
        ROMAN.put("iv", 4);
        ROMAN.put("v", 5);
        ROMAN.put("vi", 6);
        ROMAN.put(Cfg.s(483), 7);
        ROMAN.put(Cfg.s(484), 8);
        ROMAN.put("ix", 9);
        ROMAN.put("x", 10);
    }

    private Match() {
    }

    /** Кандидат на совпадение: все известные названия + год. */
    public static class Candidate {
        public List<String> titles = new ArrayList<>();
        public int year;
        public int episodes;

        public Candidate(String... titles) {
            for (String t : titles) if (t != null && t.trim().length() > 1) this.titles.add(t);
        }

        public Candidate year(int year) {
            this.year = year;
            return this;
        }
    }

    /** Как описать строку источника. */
    public interface Describer<T> {
        Candidate describe(T row);
    }

    public static String normTitle(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase().replace("ё", "е").replaceAll(Cfg.s(173), " ").replaceAll(Cfg.s(187), " ").trim();
    }

    private static List<String> tokensOf(String raw) {
        List<String> out = new ArrayList<>();
        for (String t : normTitle(raw).split(" ")) {
            if (t.length() > 1 && !STOP.contains(t)) out.add(t);
        }
        return out;
    }

    /** Номер сезона: null — не указан явно. */
    public static Integer seasonOf(String raw) {
        String v = normTitle(raw);
        if (v.isEmpty()) return null;
        Matcher m = Pattern.compile(Cfg.s(462)).matcher(v);
        if (m.find()) return parseInt(m.group(1));
        m = Pattern.compile(Cfg.s(494)).matcher(v);
        if (m.find()) return parseInt(m.group(1));
        m = Pattern.compile(Cfg.s(476)).matcher(v);
        if (m.find()) return parseInt(m.group(1));
        m = Pattern.compile(Cfg.s(460)).matcher(v);
        if (m.find()) return parseInt(m.group(1));
        m = Pattern.compile(Cfg.s(459)).matcher(v);
        if (m.find()) return parseInt(m.group(1));
        m = Pattern.compile(Cfg.s(461)).matcher(v);
        if (m.find()) return parseInt(m.group(1));
        String[] words = v.split(" ");
        String last = words.length > 0 ? words[words.length - 1] : "";
        if (ROMAN.containsKey(last)) return ROMAN.get(last);
        if (last.matches("^[0-9]{1,2}$")) return parseInt(last);
        return null;
    }

    private static Integer parseInt(String v) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer seasonOfList(List<String> list) {
        Integer best = null;
        for (String t : list) {
            Integer s = seasonOf(t);
            if (s != null && (best == null || s > best)) best = s;
        }
        return best;
    }

    private static int levenshtein(String a, String b) {
        if (a.equals(b)) return 0;
        if (a.isEmpty()) return b.length();
        if (b.isEmpty()) return a.length();
        int[] prev = new int[b.length() + 1];
        for (int i = 0; i <= b.length(); i++) prev[i] = i;
        for (int i = 1; i <= a.length(); i++) {
            int[] row = new int[b.length() + 1];
            row[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                row[j] = Math.min(Math.min(prev[j] + 1, row[j - 1] + 1), prev[j - 1] + cost);
            }
            prev = row;
        }
        return prev[b.length()];
    }

    private static double ratio(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 0;
        return 1 - (double) levenshtein(a, b) / max;
    }

    /** Похожесть названий 0..1 — консервативная. */
    public static double similarity(String a, String b) {
        String na = normTitle(a);
        String nb = normTitle(b);
        if (na.isEmpty() || nb.isEmpty()) return 0;
        if (na.equals(nb)) return 1;
        Set<String> A = new LinkedHashSet<>(tokensOf(a));
        Set<String> B = new LinkedHashSet<>(tokensOf(b));
        if (A.isEmpty() || B.isEmpty()) return 0;
        int inter = 0;
        for (String t : A) if (B.contains(t)) inter++;
        Set<String> union = new LinkedHashSet<>(A);
        union.addAll(B);
        double jaccard = (double) inter / union.size();
        double contain = (double) inter / Math.min(A.size(), B.size());
        double tokenScore = jaccard * 0.5 + contain * 0.5;
        double ratioScore = inter >= 1 ? ratio(na, nb) : ratio(na, nb) * 0.35;
        return Math.max(tokenScore, ratioScore);
    }

    /** Оценка кандидата. 0 — точно не оно. */
    public static double matchScore(Candidate candidate, String title, String original, int year) {
        List<String> candTitles = new ArrayList<>();
        for (String t : candidate.titles) if (t != null && t.trim().length() > 1) candTitles.add(t);
        if (candTitles.isEmpty()) return 0;
        List<String> wanted = new ArrayList<>();
        if (title != null && title.trim().length() > 1) wanted.add(title);
        if (original != null && original.trim().length() > 1) wanted.add(original);
        if (wanted.isEmpty()) return 0;

        Integer candSeason = seasonOfList(candTitles);
        Integer wantSeason = seasonOfList(wanted);
        if (candSeason == null ? wantSeason != null : !candSeason.equals(wantSeason)) return 0;

        double best = 0;
        for (String c : candTitles) {
            for (String w : wanted) {
                double s = similarity(c, w);
                if (s > best) best = s;
            }
        }
        if (best <= 0) return 0;

        if (candidate.year > 0 && year > 0) {
            int diff = Math.abs(candidate.year - year);
            if (diff >= 3) return 0;
            if (diff == 2) best *= 0.7;
            else if (diff == 1) best *= 0.92;
        }
        return best;
    }

    public static double matchScore(Candidate candidate, Lookup l) {
        return matchScore(candidate, l == null ? "" : l.title, l == null ? null : l.original, l == null ? 0 : l.year);
    }

    /** Уверенный кандидат либо null. */
    public static <T> T pickBest(List<T> rows, Lookup target, Describer<T> describe) {
        T best = null;
        double bestScore = 0;
        if (rows == null) return null;
        for (T row : rows) {
            double score = matchScore(describe.describe(row), target);
            if (score > bestScore) {
                bestScore = score;
                best = row;
            }
        }
        return bestScore >= ACCEPT ? best : null;
    }
}
