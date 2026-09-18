package ru.kelemnfno.anime.data.tsuyu;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Нормализация ссылок и распознавание озвучек (порт src/server/tsuyu/util.ts). */
public final class TsuyuUtil {

    private static final List<String> KODIK_HOSTS = Arrays.asList(Secrets.s(63), Secrets.s(68), Secrets.s(67), "kodik.biz");

    private TsuyuUtil() {
    }

    public static String safeUrl(String value) {
        if (value == null) return "";
        String v = value.trim().replace("\\/", "/").replace("&amp;", "&").replace(" ", "%20");
        if (v.isEmpty()) return "";
        if (v.startsWith("//")) v = "https:" + v;
        if (!v.toLowerCase().matches("^https?://.*")) return "";
        try {
            return new URL(v).toString();
        } catch (MalformedURLException e) {
            return "";
        }
    }

    public static String absolute(String base, String value) {
        if (value == null || value.isEmpty()) return "";
        String v = value.trim().replace("\\/", "/").replace("&amp;", "&").replace(" ", "%20");
        if (v.isEmpty()) return "";
        if (v.startsWith("//")) v = "https:" + v;
        try {
            return new URL(new URL(base), v).toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Достаёт src из iframe и канонизирует Kodik-хосты. */
    public static String embed(String input) {
        if (input == null || input.isEmpty()) return "";
        String s = input.trim().replace("&amp;", "&");
        Matcher m = Pattern.compile("src=[\"']([^\"']+)", Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) s = m.group(1);
        if (s.startsWith("//")) s = "https:" + s;
        String safe = safeUrl(s);
        if (safe.isEmpty()) return "";
        try {
            URL u = new URL(safe);
            if (KODIK_HOSTS.contains(u.getHost())) {
                URL fixed = new URL("https", Secrets.s(69), u.getPort(), u.getFile());
                return fixed.toString();
            }
            return u.toString();
        } catch (Exception e) {
            return safe;
        }
    }

    /** Текст из HTML/BB-кода. */
    public static String plain(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\[/?[^\\]]+]", "")
                .replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'")
                .replace("&laquo;", "«")
                .replace("&raquo;", "»")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static String plainName(String text) {
        if (text == null) return "";
        return text.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static boolean routeLabel(String value) {
        String v = value.toLowerCase().replace("ё", "е");
        return v.contains("yoru") || v.contains("yummy") || v.contains("yani") || v.contains("kodik")
                || v.contains("aniqit") || v.contains("cdnvideohub") || v.contains("anix") || v.contains("sekai")
                || v.contains("tsuyu") || v.equals("player") || v.equals("iframe") || v.equals("плеер")
                || v.equals("вариант") || v.equals("оригинал");
    }

    /** Канонический ключ озвучки: «Анилибрия HD» → anilibria-hd. */
    public static String voiceKey(String value) {
        String v = (value == null ? "" : value).toLowerCase().replace("ё", "е");
        v = v.replace("анилибрия", "anilibria")
                .replace("анилиберт", "aniliberty")
                .replace("анимаунт", "animaunt")
                .replace("анимевост", "animevost")
                .replace("анимедиа", "animedia")
                .replace("анидаб", "anidub")
                .replace("субтитры", "subtitles")
                .replace("сабы", "subtitles");
        if (v.contains("anilibria") || v.contains("aniliberty") || v.contains("cdnlibs")) {
            if (v.contains("classic")) return "anilibria-classic";
            if (v.contains("dub")) return "anilibria-dub";
            if (v.contains("hd") || v.contains("hiq")) return "anilibria-hd";
            return "anilibria";
        }
        if (v.contains("animaunt") || v.contains("ani maunt")) return "animaunt";
        if (v.contains("anirise") || v.contains("ani rise")) return "anirise";
        if (v.contains("anifilm") || v.contains("ani film")) return "anifilm";
        if (v.contains("aniplay") || v.contains("ani play")) return "aniplay";
        if (v.contains("amazing") && v.contains("dubbing")) return "amazing-dubbing";
        if (v.contains("animevost") || v.contains("anime vost")) return "animevost";
        if (v.contains("newstation") || v.contains("new station")) return "newstation";
        if (v.contains("onwave") || v.contains("on wave")) return "onwave";
        if (v.contains("anistar") && v.contains("deep")) return "anistar-deep";
        if (v.contains("beyond") && v.contains("studio")) return "beyond-studio";
        if (v.contains("dream") && v.contains("cast")) return "dreamcast";
        if (v.contains("anidub") || v.contains("ani dub")) return "anidub";
        if (v.contains("animedia") || v.contains("ani media")) return "animedia";
        if (v.contains("studioband") || v.contains("studio band") || v.contains("студийная банда")) return "studio-band";
        if (Pattern.compile("\\bjam\\b").matcher(v).find()) return "jam";
        if (v.contains("kansai")) return "kansai";
        if (v.contains("crunchyroll")) return "crunchyroll";
        if (v.contains("wakanim")) return "wakanim";
        if (v.contains("netflix")) return "netflix";
        if (v.contains("shiza")) return "shiza";
        if (v.contains("субтитр") || v.contains("subtitles") || v.contains("subtitle")
                || Pattern.compile("\\bsub\\b").matcher(v).find()) return "subtitles";
        if (routeLabel(v)) return "";
        String p = plainName(v);
        if (p.isEmpty() || p.equals("auto") || p.equals("avto") || p.equals("ozvuchka") || p.equals("perevod")) return "";
        return p.replaceAll("\\s+", "");
    }

    private static final String[][] TITLES = {
            {"anilibria-classic", "AniLibria Classic"},
            {"anilibria-dub", "AniLibria Dub"},
            {"anilibria-hd", "AniLibria HD"},
            {"anilibria", "AniLibria.TV"},
            {"animaunt", "AniMaunt"},
            {"anirise", "AniRise"},
            {"anifilm", "AniFilm"},
            {"aniplay", "AniPlay"},
            {"amazing-dubbing", "Amazing Dubbing"},
            {"animevost", "AnimeVost"},
            {"newstation", "NewStation"},
            {"onwave", "OnWave"},
            {"anistar-deep", "AniStar & DEEP"},
            {"beyond-studio", "Beyond:Studio"},
            {"dreamcast", "Dream Cast"},
            {"anidub", "AniDUB"},
            {"animedia", "AniMedia"},
            {"studio-band", "StudioBand"},
            {"jam", "JAM"},
            {"kansai", "Kansai"},
            {"crunchyroll", "Crunchyroll"},
            {"wakanim", "Wakanim"},
            {"netflix", "Netflix"},
            {"shiza", "SHIZA Project"},
            {"subtitles", "Субтитры"},
    };

    public static String voiceTitle(String value) {
        String key = voiceKey(value);
        if (key.isEmpty()) return "";
        for (String[] row : TITLES) if (row[0].equals(key)) return row[1];
        String raw = (value == null ? "" : value).trim().replaceAll("\\s+", " ");
        if (raw.length() <= 1) return "";
        return raw.length() > 48 ? raw.substring(0, 48) : raw;
    }

    public static boolean voiceMatches(String wanted, String actual) {
        String w = voiceKey(wanted);
        if (w.isEmpty()) return true;
        String a = voiceKey(actual);
        if (a.isEmpty()) return false;
        return w.equals(a) || a.contains(w) || w.contains(a);
    }

    public static String cleanLabel(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.isEmpty()) return "";
        return plain(v).replaceAll("\\s*[·|]\\s*$", "").trim();
    }

    public static int qualityOf(String url) {
        String v = url == null ? "" : url.toLowerCase();
        Matcher m = Pattern.compile("(\\d{3,4})p").matcher(v);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        if (v.contains("2160") || v.contains("4k") || v.contains("uhd")) return 2160;
        if (v.contains("1440")) return 1440;
        if (v.contains("1080") || v.contains("fullhd") || v.contains("fhd")) return 1080;
        if (v.contains("720") || v.contains("hd")) return 720;
        if (v.contains("480") || v.contains("sd")) return 480;
        if (v.contains("360")) return 360;
        if (v.contains("240")) return 240;
        return 0;
    }

    public static String hostOf(String url) {
        try {
            return new URL(url.startsWith("//") ? "https:" + url : url).getHost().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    public static String pathOf(String url) {
        try {
            return new URL(url.startsWith("//") ? "https:" + url : url).getPath();
        } catch (Exception e) {
            return "";
        }
    }

    public static String originOf(String url) {
        try {
            URL u = new URL(url);
            int port = u.getPort();
            String p = port > 0 && port != u.getDefaultPort() ? ":" + port : "";
            return u.getProtocol() + "://" + u.getHost() + p;
        } catch (Exception e) {
            return "";
        }
    }

    public static String param(String url, String name) {
        try {
            String q = new URL(url).getQuery();
            if (q == null) return "";
            for (String part : q.split("&")) {
                int i = part.indexOf('=');
                if (i < 0) continue;
                if (part.substring(0, i).equals(name)) {
                    return java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8");
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public static boolean hostMatches(String host, String... names) {
        for (String n : names) {
            if (host.equals(n) || host.endsWith("." + n)) return true;
        }
        return false;
    }

    public static int numberIn(String text, int fallback) {
        Matcher m = Pattern.compile("[0-9]+(?:\\.[0-9]+)?").matcher(text == null ? "" : text);
        if (!m.find()) return fallback;
        try {
            return (int) Math.round(Double.parseDouble(m.group()));
        } catch (Exception e) {
            return fallback;
        }
    }

    public static String cleanTitle(String raw) {
        if (raw == null) return "";
        String t = plain(raw).replace("\u00a0", " ").trim();
        return t.replaceAll("\\s*/\\s*", " / ");
    }

    /** Варианты поискового запроса: основной, оригинал, части после «/», короткое имя. */
    public static List<String> searchTerms(String title, String original) {
        Set<String> out = new LinkedHashSet<>();
        addTerm(out, title);
        addTerm(out, original);
        for (String raw : new String[]{title, original}) {
            if (raw == null) continue;
            for (String part : raw.split("/")) addTerm(out, part);
        }
        String shortName = cleanTitle(title).split("[:—–-]")[0].trim();
        if (shortName.length() >= 3) out.add(shortName);
        List<String> list = new ArrayList<>(out);
        return list.size() > 5 ? new ArrayList<>(list.subList(0, 5)) : list;
    }

    private static void addTerm(Set<String> out, String value) {
        if (value == null) return;
        String v = cleanTitle(value)
                .replaceAll("(?iu)(смотреть|онлайн|аниме|сериал)", " ")
                .replaceAll("\\s*\\([^)]*\\)\\s*", " ")
                .replaceAll("\\s*\\[[^\\]]*]\\s*", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (v.length() >= 2) out.add(v);
    }

    /** Все совпадения регулярки (первая группа либо всё совпадение). */
    public static List<String> findAll(String text, String regex) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find()) {
            out.add(m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group());
        }
        return out;
    }

    /** Первое совпадение регулярки: группа 1 либо всё совпадение. */
    public static String find(String text, String regex) {
        if (text == null) return "";
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        if (!m.find()) return "";
        return m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group();
    }

    public static String unescape(String s) {
        if (s == null) return "";
        return s.replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&amp;", "&")
                .replace("\\\"", "\"")
                .replace("\\/", "/");
    }

    public static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
