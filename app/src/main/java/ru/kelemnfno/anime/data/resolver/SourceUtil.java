package ru.kelemnfno.anime.data.resolver;

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
public final class SourceUtil {

    private static final List<String> KODIK_HOSTS = Arrays.asList(Cfg.s(63), Cfg.s(68), Cfg.s(67), Cfg.s(82));

    private SourceUtil() {
    }

    public static String safeUrl(String value) {
        if (value == null) return "";
        String v = value.trim().replace("\\/", "/").replace(Cfg.s(95), "&").replace(" ", "%20");
        if (v.isEmpty()) return "";
        if (v.startsWith("//")) v = Cfg.s(297) + v;
        if (!v.toLowerCase().matches(Cfg.s(191))) return "";
        try {
            return new URL(v).toString();
        } catch (MalformedURLException e) {
            return "";
        }
    }

    public static String absolute(String base, String value) {
        if (value == null || value.isEmpty()) return "";
        String v = value.trim().replace("\\/", "/").replace(Cfg.s(95), "&").replace(" ", "%20");
        if (v.isEmpty()) return "";
        if (v.startsWith("//")) v = Cfg.s(297) + v;
        try {
            return new URL(new URL(base), v).toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Достаёт src из iframe и канонизирует Kodik-хосты. */
    public static String embed(String input) {
        if (input == null || input.isEmpty()) return "";
        String s = input.trim().replace(Cfg.s(95), "&");
        Matcher m = Pattern.compile(Cfg.s(385), Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) s = m.group(1);
        if (s.startsWith("//")) s = Cfg.s(297) + s;
        String safe = safeUrl(s);
        if (safe.isEmpty()) return "";
        try {
            URL u = new URL(safe);
            if (KODIK_HOSTS.contains(u.getHost())) {
                URL fixed = new URL(Cfg.s(296), Cfg.s(69), u.getPort(), u.getFile());
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
        return text.replaceAll(Cfg.s(111), " ")
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\[/?[^\\]]+]", "")
                .replace(Cfg.s(100), " ")
                .replace(Cfg.s(104), "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'")
                .replace(Cfg.s(97), "«")
                .replace(Cfg.s(105), "»")
                .replace(Cfg.s(99), "—")
                .replace(Cfg.s(101), "–")
                .replace(Cfg.s(95), "&")
                .replaceAll(Cfg.s(187), " ")
                .trim();
    }

    public static String plainName(String text) {
        if (text == null) return "";
        return text.toLowerCase().replaceAll(Cfg.s(173), " ").trim();
    }

    private static boolean routeLabel(String value) {
        String v = value.toLowerCase().replace("ё", "е");
        return v.contains(Cfg.s(433)) || v.contains(Cfg.s(434)) || v.contains(Cfg.s(431)) || v.contains(Cfg.s(310))
                || v.contains(Cfg.s(229)) || v.contains(Cfg.s(250)) || v.contains(Cfg.s(233)) || v.contains(Cfg.s(371))
                || v.contains(Cfg.s(83)) || v.equals(Cfg.s(353)) || v.equals(Cfg.s(302)) || v.equals(Cfg.s(450))
                || v.equals(Cfg.s(447)) || v.equals(Cfg.s(449));
    }

    /** Канонический ключ озвучки: «Анилибрия HD» → anilibria-hd. */
    public static String voiceKey(String value) {
        String v = (value == null ? "" : value).toLowerCase().replace("ё", "е");
        v = v.replace(Cfg.s(443), Cfg.s(210))
                .replace(Cfg.s(442), Cfg.s(209))
                .replace(Cfg.s(444), Cfg.s(214))
                .replace(Cfg.s(445), Cfg.s(227))
                .replace(Cfg.s(446), Cfg.s(220))
                .replace(Cfg.s(441), Cfg.s(207))
                .replace(Cfg.s(456), Cfg.s(393))
                .replace(Cfg.s(453), Cfg.s(393));
        if (v.contains(Cfg.s(210)) || v.contains(Cfg.s(209)) || v.contains(Cfg.s(249))) {
            if (v.contains(Cfg.s(251))) return Cfg.s(211);
            if (v.contains(Cfg.s(270))) return Cfg.s(212);
            if (v.contains("hd") || v.contains(Cfg.s(289))) return Cfg.s(213);
            return Cfg.s(210);
        }
        if (v.contains(Cfg.s(214)) || v.contains(Cfg.s(203))) return Cfg.s(214);
        if (v.contains(Cfg.s(230)) || v.contains(Cfg.s(206))) return Cfg.s(230);
        if (v.contains(Cfg.s(208)) || v.contains(Cfg.s(202))) return Cfg.s(208);
        if (v.contains(Cfg.s(228)) || v.contains(Cfg.s(205))) return Cfg.s(228);
        if (v.contains(Cfg.s(199)) && v.contains(Cfg.s(271))) return Cfg.s(200);
        if (v.contains(Cfg.s(227)) || v.contains(Cfg.s(215))) return Cfg.s(227);
        if (v.contains(Cfg.s(340)) || v.contains(Cfg.s(339))) return Cfg.s(340);
        if (v.contains(Cfg.s(345)) || v.contains(Cfg.s(344))) return Cfg.s(345);
        if (v.contains(Cfg.s(231)) && v.contains(Cfg.s(265))) return Cfg.s(232);
        if (v.contains(Cfg.s(244)) && v.contains(Cfg.s(388))) return Cfg.s(245);
        if (v.contains(Cfg.s(268)) && v.contains(Cfg.s(247))) return Cfg.s(269);
        if (v.contains(Cfg.s(207)) || v.contains(Cfg.s(201))) return Cfg.s(207);
        if (v.contains(Cfg.s(220)) || v.contains(Cfg.s(204))) return Cfg.s(220);
        if (v.contains(Cfg.s(391)) || v.contains(Cfg.s(389)) || v.contains(Cfg.s(454))) return Cfg.s(390);
        if (Pattern.compile(Cfg.s(178)).matcher(v).find()) return Cfg.s(308);
        if (v.contains(Cfg.s(309))) return Cfg.s(309);
        if (v.contains(Cfg.s(256))) return Cfg.s(256);
        if (v.contains(Cfg.s(429))) return Cfg.s(429);
        if (v.contains(Cfg.s(338))) return Cfg.s(338);
        if (v.contains(Cfg.s(375))) return Cfg.s(375);
        if (v.contains(Cfg.s(455)) || v.contains(Cfg.s(393)) || v.contains(Cfg.s(392))
                || Pattern.compile(Cfg.s(179)).matcher(v).find()) return Cfg.s(393);
        if (routeLabel(v)) return "";
        String p = plainName(v);
        if (p.isEmpty() || p.equals(Cfg.s(241)) || p.equals(Cfg.s(242)) || p.equals(Cfg.s(349)) || p.equals(Cfg.s(352))) return "";
        return p.replaceAll(Cfg.s(187), "");
    }

    private static final String[][] TITLES = {
            {Cfg.s(211), Cfg.s(135)},
            {Cfg.s(212), Cfg.s(136)},
            {Cfg.s(213), Cfg.s(137)},
            {Cfg.s(210), Cfg.s(81)},
            {Cfg.s(214), Cfg.s(138)},
            {Cfg.s(230), Cfg.s(141)},
            {Cfg.s(208), Cfg.s(134)},
            {Cfg.s(228), Cfg.s(140)},
            {Cfg.s(200), Cfg.s(132)},
            {Cfg.s(227), Cfg.s(143)},
            {Cfg.s(340), Cfg.s(151)},
            {Cfg.s(345), Cfg.s(152)},
            {Cfg.s(232), Cfg.s(142)},
            {Cfg.s(245), Cfg.s(144)},
            {Cfg.s(269), Cfg.s(147)},
            {Cfg.s(207), Cfg.s(133)},
            {Cfg.s(220), Cfg.s(139)},
            {Cfg.s(390), Cfg.s(158)},
            {Cfg.s(308), Cfg.s(148)},
            {Cfg.s(309), Cfg.s(149)},
            {Cfg.s(256), Cfg.s(146)},
            {Cfg.s(429), Cfg.s(161)},
            {Cfg.s(338), Cfg.s(150)},
            {Cfg.s(375), Cfg.s(157)},
            {Cfg.s(393), Cfg.s(440)},
    };

    public static String voiceTitle(String value) {
        String key = voiceKey(value);
        if (key.isEmpty()) return "";
        for (String[] row : TITLES) if (row[0].equals(key)) return row[1];
        String raw = (value == null ? "" : value).trim().replaceAll(Cfg.s(187), " ");
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
        return plain(v).replaceAll(Cfg.s(184), "").trim();
    }

    public static int qualityOf(String url) {
        String v = url == null ? "" : url.toLowerCase();
        Matcher m = Pattern.compile(Cfg.s(114)).matcher(v);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        if (v.contains("2160") || v.contains("4k") || v.contains(Cfg.s(410))) return 2160;
        if (v.contains("1440")) return 1440;
        if (v.contains("1080") || v.contains(Cfg.s(285)) || v.contains(Cfg.s(282))) return 1080;
        if (v.contains("720") || v.contains("hd")) return 720;
        if (v.contains("480") || v.contains("sd")) return 480;
        if (v.contains("360")) return 360;
        if (v.contains("240")) return 240;
        return 0;
    }

    public static String hostOf(String url) {
        try {
            return new URL(url.startsWith("//") ? Cfg.s(297) + url : url).getHost().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    public static String pathOf(String url) {
        try {
            return new URL(url.startsWith("//") ? Cfg.s(297) + url : url).getPath();
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
                    return java.net.URLDecoder.decode(part.substring(i + 1), Cfg.s(159));
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
        return t.replaceAll(Cfg.s(183), " / ");
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
                .replaceAll(Cfg.s(112), " ")
                .replaceAll(Cfg.s(185), " ")
                .replaceAll(Cfg.s(186), " ")
                .replaceAll(Cfg.s(187), " ")
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
        return s.replace(Cfg.s(104), "\"")
                .replace("&#34;", "\"")
                .replace(Cfg.s(95), "&")
                .replace("\\\"", "\"")
                .replace("\\/", "/");
    }

    public static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
