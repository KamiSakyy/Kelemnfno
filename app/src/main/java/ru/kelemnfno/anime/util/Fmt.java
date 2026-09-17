package ru.kelemnfno.anime.util;

import java.util.List;
import java.util.Locale;

import ru.kelemnfno.anime.data.model.AnimeItem;
import ru.kelemnfno.anime.data.model.Poster;
import ru.kelemnfno.anime.data.model.VideoItem;

/** Форматирование и нормализация данных (порт src/api/yummy.ts). */
public final class Fmt {

    public static final String[] SEASONS = {"", "Зима", "Весна", "Лето", "Осень"};

    private Fmt() {
    }

    public static String absUrl(String u) {
        if (u == null || u.isEmpty()) return "";
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("/")) return "https://static.yani.tv" + u;
        return u;
    }

    /** Постер нужного размера. Если его нет — перебираем все остальные, пустым не возвращаем. */
    public static String posterUrl(Poster p, String size) {
        if (p == null) return "";
        String v = null;
        switch (size) {
            case "fullsize": v = p.fullsize; break;
            case "big": v = p.big; break;
            case "small": v = p.small; break;
            case "medium": v = p.medium; break;
            case "huge": v = p.huge; break;
            case "mega": v = p.mega; break;
            default: v = p.medium; break;
        }
        String[] fallback = {p.medium, p.big, p.huge, p.fullsize, p.small, p.mega};
        for (int i = 0; i < fallback.length && isBlank(v); i++) v = fallback[i];
        return absUrl(v);
    }

    /** Готовый или относительный путь постера → полный URL. */
    public static String posterUrl(String path, String size) {
        return absUrl(path);
    }

    public static String posterUrl(AnimeItem a, String size) {
        return a == null ? "" : posterUrl(a.poster, size);
    }

    public static String playerName(VideoItem v) {
        String raw = v != null && v.data != null && v.data.player != null ? v.data.player : "";
        String cleaned = raw.replaceAll("(?i)^Плеер\\s+", "").trim();
        if (!cleaned.isEmpty()) return cleaned;
        String url = v != null && v.iframeUrl != null ? v.iframeUrl : "";
        if (url.contains("kodik")) return "Kodik";
        if (url.contains("alloha")) return "Alloha";
        if (url.contains("sibnet")) return "Sibnet";
        if (url.contains("CVH") || url.contains("cvh")) return "CVH";
        return "Плеер";
    }

    public static String dubbingName(VideoItem v) {
        String raw = v != null && v.data != null && v.data.dubbing != null ? v.data.dubbing : "";
        String cleaned = raw.replaceAll("(?i)^Озвучка\\s+", "").trim();
        return cleaned.isEmpty() ? "Без названия" : cleaned;
    }

    /** Убирает BB-код и упоминания из описания. */
    public static String cleanDescription(String d) {
        if (d == null) return "";
        String out = d.replaceAll("(?i)\\[/?[a-z]+]", "");
        out = out.replaceAll("(?i)@Shikimori", "");
        out = out.replaceAll("\n{3,}", "\n\n");
        return out.trim();
    }

    public static String formatViews(int n) {
        if (n <= 0) return "0";
        if (n >= 1_000_000) return trimZero(n / 1_000_000.0) + "M";
        if (n >= 1000) return trimZero(n / 1000.0) + "K";
        return String.valueOf(n);
    }

    private static String trimZero(double v) {
        String s = String.format(Locale.US, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    public static String formatDuration(double sec) {
        if (sec <= 0) return "";
        int m = (int) Math.round(sec / 60.0);
        if (m >= 60) return (m / 60) + " ч " + (m % 60) + " мин";
        return m + " мин";
    }

    /** Число серии из строки: "12", "12.5", "ONA 3". */
    public static int numberIn(String text, int fallback) {
        if (text == null) return fallback;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[0-9]+(?:\\.[0-9]+)?").matcher(text);
        if (!m.find()) return fallback;
        try {
            return (int) Math.round(Double.parseDouble(m.group()));
        } catch (Exception e) {
            return fallback;
        }
    }

    public static String episodeTag(String number) {
        String n = String.valueOf(number);
        if (n.length() < 2) n = "0" + n;
        return "E" + n.replaceAll("[^0-9A-Za-z._]", "_");
    }

    public static String sanitizeFilename(String name) {
        if (name == null) return "video";
        String out = name.replaceAll("[\\\\/:*?\"<>|]+", "").replaceAll("\\s+", " ").trim();
        if (out.length() > 120) out = out.substring(0, 120);
        return out.isEmpty() ? "video" : out;
    }

    public static String formatBytes(long n) {
        if (n >= 1_000_000_000L) return String.format(Locale.US, "%.2f ГБ", n / 1e9);
        if (n >= 1_000_000L) return String.format(Locale.US, "%.1f МБ", n / 1e6);
        return Math.round(n / 1000.0) + " КБ";
    }

    public static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%d:%02d", m, sec);
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (isBlank(p)) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }
}
