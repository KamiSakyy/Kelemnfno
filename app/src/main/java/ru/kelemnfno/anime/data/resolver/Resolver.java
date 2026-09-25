package ru.kelemnfno.anime.data.resolver;

import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;

import static ru.kelemnfno.anime.data.resolver.SourceUtil.absolute;
import static ru.kelemnfno.anime.data.resolver.SourceUtil.find;
import static ru.kelemnfno.anime.data.resolver.SourceUtil.findAll;
import static ru.kelemnfno.anime.data.resolver.SourceUtil.hostMatches;
import static ru.kelemnfno.anime.data.resolver.SourceUtil.hostOf;
import static ru.kelemnfno.anime.data.resolver.SourceUtil.originOf;
import static ru.kelemnfno.anime.data.resolver.SourceUtil.param;
import static ru.kelemnfno.anime.data.resolver.SourceUtil.pathOf;
import static ru.kelemnfno.anime.data.resolver.SourceUtil.qualityOf;
import static ru.kelemnfno.anime.data.resolver.SourceUtil.safeUrl;

/**
 * Превращает ссылку плеера (Kodik, CDNVideoHub, Alloha, Aksor, Sibnet, Stormo, AniBoom,
 * AniLib, Animetka, AniDUB, VK, Rutube, ZedFilm, прямые m3u8/mpd/mp4) в прямые потоки.
 * Порт src/server/tsuyu/resolver.ts.
 */
public final class Resolver {

    private static final Pattern M3U8_RE = Pattern.compile(
            Cfg.s(299), Pattern.CASE_INSENSITIVE);
    private static final Pattern MP4_RE = Pattern.compile(
            Cfg.s(300), Pattern.CASE_INSENSITIVE);

    private Resolver() {
    }

    /** Результат подбора: качество → url + referer источника. */
    public static class Resolved {
        public Map<Integer, String> streams = new TreeMap<>(Collections.reverseOrder());
        public String referer = "";
    }

    public static String kindOf(String url) {
        if (url == null) return Cfg.s(291);
        if (Pattern.compile(Cfg.s(176), Pattern.CASE_INSENSITIVE).matcher(url).find()) return Cfg.s(324);
        if (url.toLowerCase().contains(Cfg.s(116))) return Cfg.s(261);
        return Cfg.s(291);
    }

    /* ---------------- Общие helpers ---------------- */

    private static Map<Integer, String> clean(Map<Integer, String> out) {
        Map<Integer, String> res = new TreeMap<>(Collections.reverseOrder());
        for (Map.Entry<Integer, String> e : out.entrySet()) {
            String safe = safeUrl(e.getValue());
            if (!safe.isEmpty()) res.put(e.getKey(), safe);
        }
        return res;
    }

    private static void put(Map<Integer, String> out, int quality, String url) {
        String safe = safeUrl(url);
        if (safe.isEmpty()) return;
        int q = quality > 0 ? quality : qualityOf(safe);
        if (q <= 0) q = 720;
        out.put(q, safe);
    }

    /** Мастер-плейлист → качество/ссылка. */
    static Map<Integer, String> parseHls(String manifest, String url) {
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        if (manifest == null || !manifest.trim().startsWith(Cfg.s(93))) return out;
        int height = 0;
        for (String raw : manifest.split(Cfg.s(182))) {
            String l = raw.trim();
            if (l.startsWith(Cfg.s(92))) {
                Matcher m = Pattern.compile(Cfg.s(154)).matcher(l);
                height = m.find() ? Integer.parseInt(m.group(1)) : 0;
            } else if (!l.isEmpty() && !l.startsWith("#") && height > 0) {
                out.put(height, absolute(url, l));
                height = 0;
            }
        }
        return clean(out);
    }

    /** Прямые m3u8/mpd/mp4 без обращения к плееру. */
    static Map<Integer, String> direct(String url, String referer) {
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        String lower = url.toLowerCase();
        String p = pathOf(url).toLowerCase();
        try {
            if (p.contains(Cfg.s(115)) || lower.contains(Cfg.s(115))) {
                try {
                    String manifest = Net.get(url, Net.baseHeaders(originOf(url), referer), 12_000);
                    out.putAll(parseHls(manifest, url));
                } catch (Exception ignored) {
                }
                if (out.isEmpty()) put(out, qualityOf(url), url);
            } else if (p.contains(Cfg.s(116)) || lower.contains(Cfg.s(116))) {
                out.put(0, url);
            } else if (Pattern.compile(Cfg.s(175)).matcher(p).find()
                    || Pattern.compile(Cfg.s(175)).matcher(lower).find()) {
                put(out, qualityOf(url), url);
            }
        } catch (Exception ignored) {
        }
        return clean(out);
    }

    /* ---------------- Kodik ---------------- */

    /** Путь эндпоинта из app.player_single: ищем atob('…') → Cfg.s(120). */
    private static String endpointPath(String script) {
        List<String> all = findAll(script, Cfg.s(240));
        for (String b64 : all) {
            String clean = b64.replaceAll(Cfg.s(172), "");
            try {
                String decoded = new String(Base64.decode(clean, Base64.DEFAULT), StandardCharsets.UTF_8);
                if (decoded.startsWith("/") && decoded.length() >= 2 && decoded.length() < 40) return decoded;
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    /** Шифр Kodik: сдвиг букв по алфавиту + base64. */
    private static String shift(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 'a' && c <= 'z') {
                sb.append((char) ((c - 'a' + n) % 26 + 'a'));
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) ((c - 'A' + n) % 26 + 'A'));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String b64(String value) {
        try {
            String normalized = value.replace('-', '+').replace('_', '/');
            return new String(Base64.decode(normalized, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static String kodikDecode(String raw) {
        int[] order = new int[31];
        order[0] = 18;
        order[1] = 13;
        order[2] = 21;
        order[3] = 8;
        order[4] = 3;
        for (int i = 0; i < 26; i++) order[5 + i] = i;
        for (int n : order) {
            String decoded = b64(shift(raw, n));
            if (decoded.matches(Cfg.s(188))) {
                return safeUrl(decoded.startsWith("//") ? Cfg.s(297) + decoded : decoded);
            }
        }
        String plain = b64(raw);
        if (plain.matches(Cfg.s(188))) return safeUrl(plain.startsWith("//") ? Cfg.s(297) + plain : plain);
        return safeUrl(raw);
    }

    private static Map<Integer, String> kodik(String url) throws IOException {
        String page = Net.get(url, Net.baseHeaders(originOf(url), Cfg.referer()), 15_000)
                .replaceAll(Cfg.s(171), "");

        Map<String, String> payload = new LinkedHashMap<>();
        String urlParams = find(page, Cfg.s(181));
        if (urlParams.isEmpty()) urlParams = find(page, Cfg.s(180));
        if (!urlParams.isEmpty()) {
            try {
                JsonObject p = Net.parse(SourceUtil.unescape(urlParams));
                for (String k : new String[]{"d", Cfg.s(260), "pd", Cfg.s(351), Cfg.s(359), Cfg.s(360)}) {
                    payload.put(k, J.str(p, k));
                }
            } catch (Exception ignored) {
            }
        }
        if (payload.get("d") == null || payload.get("d").isEmpty()) {
            payload.put("d", find(page, Cfg.s(413)));
            payload.put(Cfg.s(260), find(page, Cfg.s(412)));
            payload.put("pd", find(page, Cfg.s(414)));
            payload.put(Cfg.s(351), find(page, Cfg.s(415)));
            payload.put(Cfg.s(359), find(page, Cfg.s(416)));
            payload.put(Cfg.s(360), find(page, Cfg.s(417)));
        }
        String type = find(page, Cfg.s(109));
        if (type.isEmpty()) type = find(page, Cfg.s(169));
        String hash = find(page, Cfg.s(107));
        if (hash.isEmpty()) hash = find(page, Cfg.s(167));
        String id = find(page, Cfg.s(108));
        if (id.isEmpty()) id = find(page, Cfg.s(168));
        if (id.isEmpty()) id = find(page, Cfg.s(418));
        payload.put(Cfg.s(408), type);
        payload.put(Cfg.s(287), hash);
        payload.put("id", id);

        String endpoint = originOf(url) + Cfg.s(119);
        String script = find(page, Cfg.s(382));
        if (script.isEmpty()) script = find(page, Cfg.s(383));
        if (script.isEmpty()) script = find(page, Cfg.s(384));
        if (!script.isEmpty()) {
            try {
                String scriptUrl = absolute(url, script);
                String body = Net.get(scriptUrl, Net.baseHeaders(originOf(url), url), 12_000);
                String p = endpointPath(body);
                if (!p.isEmpty()) endpoint = originOf(scriptUrl) + p;
            } catch (Exception ignored) {
            }
        }

        for (String k : new String[]{"d", Cfg.s(260), "pd", Cfg.s(351), Cfg.s(408), Cfg.s(287), "id"}) {
            if (payload.get(k) == null || payload.get(k).isEmpty()) throw new IOException(Cfg.s(312));
        }

        String body = Net.formBodyRaw(
                "d", payload.get("d"),
                Cfg.s(260), payload.get(Cfg.s(260)),
                "pd", payload.get("pd"),
                Cfg.s(351), payload.get(Cfg.s(351)),
                Cfg.s(359), payload.get(Cfg.s(359)) == null ? "" : payload.get(Cfg.s(359)),
                Cfg.s(360), payload.get(Cfg.s(360)) == null ? "" : payload.get(Cfg.s(360)),
                Cfg.s(243), Cfg.s(407),
                Cfg.s(248), Cfg.s(407),
                Cfg.s(408), payload.get(Cfg.s(408)),
                Cfg.s(287), payload.get(Cfg.s(287)),
                "id", payload.get("id"),
                Cfg.s(306), "{}");

        List<String> bases = new ArrayList<>();
        bases.add(endpoint);
        bases.add(originOf(url) + Cfg.s(119));
        bases.add(Cfg.s(34));
        JsonObject links = null;
        for (String base : new LinkedHashSet<String>(bases)) {
            try {
                Map<String, String> h = Net.baseHeaders(originOf(url), url);
                h.put(Cfg.s(162), Cfg.s(163));
                h.put(Cfg.s(129), Cfg.s(237));
                JsonObject root = Net.parse(Net.postForm(base, body, h));
                JsonObject l = J.obj(root, Cfg.s(318));
                if (l.size() > 0) {
                    links = l;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (links == null) throw new IOException(Cfg.s(313));

        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        for (Map.Entry<String, JsonElement> entry : links.entrySet()) {
            int q = 0;
            String digits = entry.getKey().replaceAll(Cfg.s(177), "");
            if (!digits.isEmpty()) {
                try {
                    q = Integer.parseInt(digits);
                } catch (Exception ignored) {
                }
            }
            JsonElement value = entry.getValue();
            List<String> srcs = new ArrayList<>();
            if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                for (JsonElement el : array) {
                    srcs.add(el.isJsonPrimitive() ? el.getAsString() : J.str(J.obj(el), Cfg.s(380)));
                }
            } else {
                srcs.add(value.isJsonPrimitive() ? value.getAsString() : J.str(J.obj(value), Cfg.s(380)));
            }
            for (String src : srcs) {
                if (src == null || src.isEmpty()) continue;
                String decoded = kodikDecode(src);
                if (decoded.isEmpty()) decoded = safeUrl(src);
                if (!decoded.isEmpty()) put(out, q, decoded);
            }
        }
        if (out.isEmpty()) throw new IOException(Cfg.s(311));
        return clean(out);
    }

    /* ---------------- CDNVideoHub (Yummy) ---------------- */

    private static String normalizeCvh(String value, String failover) {
        String safe = safeUrl(value);
        if (safe.isEmpty() || failover == null || failover.isEmpty()) return safe;
        try {
            java.net.URL u = new java.net.URL(safe);
            if (!Cfg.s(296).equals(u.getProtocol()) || !u.getHost().matches(Cfg.s(190))) return safe;
            return new java.net.URL(Cfg.s(296), failover, u.getPort(), u.getFile()).toString();
        } catch (Exception e) {
            return safe;
        }
    }

    private static Map<Integer, String> cvh(String url) throws IOException {
        String animeId = param(url, Cfg.s(219));
        if (animeId.isEmpty()) animeId = param(url, "id");
        if (animeId.isEmpty()) throw new IOException(Cfg.s(257));
        int ep = 1;
        try {
            ep = Integer.parseInt(param(url, Cfg.s(277)));
        } catch (Exception ignored) {
        }
        if (ep <= 0) ep = 1;
        String dubbing = param(url, Cfg.s(272));

        Map<String, String> h = Net.baseHeaders(Cfg.s(41), Cfg.s(42));
        h.put(Cfg.s(129), Cfg.s(236));
        JsonObject playlist = Net.getJson(
                Cfg.s(39) + Net.enc(animeId) + Cfg.s(94), h);

        JsonObject chosen = null;
        for (JsonObject item : J.list(playlist, Cfg.s(307))) {
            if (J.intOf(item, Cfg.s(277)) != ep) continue;
            if (chosen == null) chosen = item;
            if (!dubbing.isEmpty() && J.str(item, Cfg.s(427)).equalsIgnoreCase(dubbing)) {
                chosen = item;
                break;
            }
        }
        String vkId = chosen == null ? "" : J.str(chosen, Cfg.s(426));
        if (vkId.isEmpty()) throw new IOException(Cfg.s(259));

        JsonObject video = Net.getJson(
                Cfg.s(40) + Net.enc(vkId), h);
        String failover = J.str(video, Cfg.s(281));
        JsonObject s = J.obj(video, Cfg.s(379));

        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        putCvh(out, 240, J.str(s, Cfg.s(330)), failover);
        putCvh(out, 360, J.str(s, Cfg.s(329)), failover);
        putCvh(out, 480, J.str(s, Cfg.s(331)), failover);
        putCvh(out, 720, J.str(s, Cfg.s(328)), failover);
        putCvh(out, 1080, J.str(s, Cfg.s(327)), failover);
        putCvh(out, 1440, first(J.str(s, Cfg.s(333)), J.str(s, Cfg.s(325))), failover);
        putCvh(out, 2160, first(J.str(s, Cfg.s(335)), J.str(s, Cfg.s(334)), J.str(s, Cfg.s(326)),
                J.str(s, Cfg.s(332))), failover);
        if (out.isEmpty()) throw new IOException(Cfg.s(258));
        return clean(out);
    }

    private static void putCvh(Map<Integer, String> out, int quality, String value, String failover) {
        String safe = normalizeCvh(value, failover);
        if (safe.isEmpty()) return;
        out.put(quality > 0 ? quality : Math.max(qualityOf(safe), 480), safe);
    }

    private static String first(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    /* ---------------- Alloha ---------------- */

    private static Map<Integer, String> alloha(String url) throws IOException {
        String page = Net.get(url, Net.baseHeaders(originOf(url), Cfg.referer()), 15_000);
        String id = find(page,
                Cfg.s(253));
        if (id.isEmpty()) id = find(page, Cfg.s(90));
        String token = find(page, Cfg.s(254));
        String user = find(page, Cfg.s(126));
        if (id.isEmpty() || token.isEmpty() || user.isEmpty()) throw new IOException(Cfg.s(195));

        Map<String, String> h = Net.baseHeaders(Cfg.alloha(), url);
        h.put(Cfg.s(131), user);
        String body = Cfg.s(402) + Net.enc(token) + Cfg.s(96);
        JsonObject root = Net.parse(Net.postForm(Cfg.alloha() + Cfg.s(80) + id, body, h));

        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        JsonArray hlsSource = J.arr(root, Cfg.s(292));
        if (hlsSource.size() > 0) {
            JsonObject q = J.obj(J.obj(hlsSource.get(0)), Cfg.s(357));
            for (Map.Entry<String, JsonElement> e : q.entrySet()) {
                if (e.getKey().equalsIgnoreCase(Cfg.s(342))) continue;
                String link = J.str(e.getValue());
                int cut = link.indexOf(Cfg.s(87));
                if (cut >= 0) link = link.substring(0, cut);
                String safe = safeUrl(link);
                if (safe.isEmpty()) continue;
                String digits = e.getKey().replaceAll(Cfg.s(177), "");
                int quality = digits.isEmpty() ? qualityOf(safe) : Integer.parseInt(digits);
                out.put(quality > 0 ? quality : 720, safe);
            }
        }
        if (out.isEmpty()) throw new IOException(Cfg.s(196));
        return clean(out);
    }

    /* ---------------- Aksor ---------------- */

    private static Map<Integer, String> aksor(String url) {
        String origin = originOf(url);
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        try {
            String[] parts = pathOf(url).split("/");
            String id = parts.length > 0 ? parts[parts.length - 1] : "";
            if (!id.isEmpty()) {
                JsonObject j = Net.getJson(origin + Cfg.s(79) + Net.enc(id), Net.baseHeaders(origin, url));
                for (Map.Entry<String, JsonElement> e : J.obj(j, Cfg.s(356)).entrySet()) {
                    String safe = safeUrl(J.str(e.getValue()));
                    if (safe.isEmpty()) continue;
                    String digits = e.getKey().replaceAll(Cfg.s(177), "");
                    out.put(digits.isEmpty() ? qualityOf(safe) : Integer.parseInt(digits), safe);
                }
                for (JsonObject row : J.list(j, Cfg.s(379))) {
                    String safe = safeUrl(first(J.str(row, Cfg.s(411)), J.str(row, Cfg.s(380))));
                    if (!safe.isEmpty()) out.put(J.intOf(row, Cfg.s(288)) > 0 ? J.intOf(row, Cfg.s(288)) : qualityOf(safe), safe);
                }
            }
        } catch (Exception ignored) {
        }
        if (!out.isEmpty()) return clean(out);
        try {
            String page = Net.get(url, Net.baseHeaders(origin, Cfg.referer()), 12_000);
            String link = absolute(url, find(page, Cfg.s(419)));
            if (!link.isEmpty()) put(out, qualityOf(link), link);
        } catch (Exception ignored) {
        }
        return clean(out);
    }

    /* ---------------- Sibnet / Stormo ---------------- */

    private static Map<Integer, String> sibnet(String url) throws IOException {
        String page = Net.get(url, Net.baseHeaders(originOf(url), url), 12_000);
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        for (String m : findAll(page, Cfg.s(166))) {
            put(out, 480, Cfg.s(51) + m);
        }
        for (String m : findAll(page, Cfg.s(381))) {
            put(out, 480, Cfg.s(52) + m.replace("\\/", "/"));
        }
        if (out.isEmpty()) throw new IOException(Cfg.s(376));
        return clean(out);
    }

    private static Map<Integer, String> stormo(String url) throws IOException {
        String page = Net.get(url, Net.baseHeaders(originOf(url), url), 12_000);
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        Matcher m = Pattern.compile(Cfg.s(85),
                Pattern.CASE_INSENSITIVE).matcher(page);
        while (m.find()) put(out, 480, m.group().replace("\\/", "/"));
        if (out.isEmpty()) return scan(url, null);
        return clean(out);
    }

    /* ---------------- AniBoom / AniLib ---------------- */

    private static Map<Integer, String> aniboom(String url) throws IOException {
        String page = Net.get(url, Net.baseHeaders(originOf(url), Cfg.s(12)), 12_000);
        String raw = find(page, Cfg.s(263));
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        if (!raw.isEmpty()) {
            try {
                JsonObject params = Net.parse(SourceUtil.unescape(raw));
                JsonObject hls = Net.parse(J.str(params, Cfg.s(291)));
                for (Map.Entry<String, JsonElement> e : hls.entrySet()) {
                    String safe = safeUrl(J.str(e.getValue()));
                    if (!safe.isEmpty()) out.putAll(direct(safe, url));
                }
            } catch (Exception ignored) {
            }
        }
        if (out.isEmpty()) return scan(url, Cfg.s(8));
        return clean(out);
    }

    private static Map<Integer, String> anilib(String url) throws IOException {
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        String safe = safeUrl(url);
        out.putAll(direct(safe, Cfg.s(9)));
        if (out.isEmpty() && !safe.isEmpty()) put(out, qualityOf(safe), safe);
        return clean(out);
    }

    /* ---------------- Animetka playlist ---------------- */

    private static Map<Integer, String> animetkaPlaylist(String url) throws IOException {
        Map<String, String> h = Net.baseHeaders(Cfg.s(13), Cfg.s(14));
        h.put(Cfg.s(129), Cfg.s(238));
        String text = Net.get(url, h, 12_000);
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        try {
            walkMedia(Net.parse(text), out);
        } catch (Exception ignored) {
        }
        if (out.isEmpty()) {
            Matcher m = M3U8_RE.matcher(text);
            while (m.find()) put(out, 720, m.group().replace("\\/", "/"));
        }
        if (out.isEmpty()) throw new IOException(Cfg.s(225));
        return clean(out);
    }

    private static void walkMedia(JsonElement el, Map<Integer, String> out) {
        if (el == null) return;
        if (el.isJsonPrimitive()) {
            String v = el.getAsString();
            if (v != null && Pattern.compile(Cfg.s(174), Pattern.CASE_INSENSITIVE).matcher(v).find()) {
                put(out, 720, v.startsWith("//") ? Cfg.s(297) + v : v);
            }
            return;
        }
        if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) walkMedia(child, out);
            return;
        }
        if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) walkMedia(e.getValue(), out);
        }
    }

    /* ---------------- VK / Rutube ---------------- */

    private static Map<Integer, String> vk(String url) throws IOException {
        String page = SourceUtil.unescape(Net.get(url, Net.baseHeaders(originOf(url), Cfg.referer()), 15_000));
        String ext = find(page, Cfg.s(106));
        if (ext.isEmpty()) ext = find(page, Cfg.s(84));
        if (!ext.isEmpty()) {
            try {
                page += "\n" + SourceUtil.unescape(Net.get(absolute(url, ext), Net.baseHeaders(Cfg.s(53), url), 12_000));
            } catch (Exception ignored) {
            }
        }
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        Matcher m = Pattern.compile(Cfg.s(165),
                Pattern.CASE_INSENSITIVE).matcher(page);
        while (m.find()) {
            String safe = safeUrl(m.group(2));
            if (!safe.isEmpty()) out.put(Integer.parseInt(m.group(1)), safe);
        }
        Matcher m2 = Pattern.compile(Cfg.s(164),
                Pattern.CASE_INSENSITIVE).matcher(page);
        while (m2.find()) {
            String safe = safeUrl(m2.group(1));
            if (!safe.isEmpty()) out.putAll(direct(safe, url));
        }
        if (out.isEmpty()) throw new IOException(Cfg.s(425));
        return clean(out);
    }

    private static String rutubeId(String value) {
        String id = find(value, Cfg.s(86));
        if (id.isEmpty()) id = find(value, Cfg.s(113));
        return id;
    }

    private static Map<Integer, String> rutube(String url) throws IOException {
        String id = rutubeId(url);
        if (id.isEmpty()) {
            try {
                id = rutubeId(Net.get(url, Net.baseHeaders(Cfg.s(43), Cfg.s(44)), 12_000));
            } catch (Exception ignored) {
            }
        }
        if (id.isEmpty()) throw new IOException(Cfg.s(369));
        Map<String, String> h = Net.baseHeaders(Cfg.s(43), url);
        h.put(Cfg.s(129), Cfg.s(239));
        JsonObject root = Net.getJson(Cfg.s(45) + Net.enc(id)
                + Cfg.s(117) + Net.enc(url) + Cfg.s(103), h);
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        for (Map.Entry<String, JsonElement> e : J.obj(root, Cfg.s(422)).entrySet()) {
            String safe = safeUrl(J.str(e.getValue()));
            if (!safe.isEmpty()) out.putAll(direct(safe, url));
        }
        if (out.isEmpty()) throw new IOException(Cfg.s(370));
        return clean(out);
    }

    /* ---------------- HLS-эндпоинт (AniDUB vid.php / ladony) ---------------- */

    private static Map<Integer, String> hlsEndpoint(String url, String referer) throws IOException {
        String body = Net.get(url, Net.baseHeaders(originOf(url), referer), 12_000);
        if (body.trim().startsWith(Cfg.s(93))) {
            Map<Integer, String> parsed = parseHls(body, url);
            if (!parsed.isEmpty()) return parsed;
            Map<Integer, String> single = new TreeMap<>(Collections.reverseOrder());
            put(single, qualityOf(url), url);
            return clean(single);
        }
        return scan(url, referer);
    }

    /* ---------------- Общий скан страницы ---------------- */

    static Map<Integer, String> scan(String url, String referer) throws IOException {
        String page = Net.get(url, Net.baseHeaders(originOf(url), referer == null ? url : referer), 15_000);
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        String body = SourceUtil.unescape(page);
        Matcher m = M3U8_RE.matcher(body);
        while (m.find()) put(out, 720, m.group().replace("\\/", "/"));
        if (out.isEmpty()) {
            Matcher m2 = MP4_RE.matcher(body);
            while (m2.find()) put(out, 480, m2.group().replace("\\/", "/"));
        }
        String file = find(body, Cfg.s(283));
        if (!file.isEmpty()) {
            String safe = absolute(url, file);
            if (!safe.isEmpty()) out.putAll(direct(safe, url));
        }
        if (out.isEmpty()) throw new IOException(Cfg.s(448));
        return clean(out);
    }

    /* ---------------- Точка входа ---------------- */

    public static Resolved resolveStreams(String input) throws IOException {
        String url = SourceUtil.embed(input);
        if (url.isEmpty()) url = safeUrl(input);
        if (url.isEmpty()) throw new IOException(Cfg.s(452));

        String host = hostOf(url);
        String path = pathOf(url).toLowerCase();
        String referer = originOf(url) + "/";

        Map<Integer, String> own = direct(url, referer);
        if (!own.isEmpty()) {
            Resolved r = new Resolved();
            r.streams = own;
            r.referer = referer;
            return r;
        }

        Map<Integer, String> streams;
        if ((hostMatches(host, Cfg.s(76), Cfg.bareHost()) && path.contains(Cfg.s(304)))
                || hostMatches(host, Cfg.s(64))) {
            streams = cvh(url);
        } else if (hostMatches(host, Cfg.s(62)) && path.startsWith(Cfg.s(78))) {
            streams = animetkaPlaylist(url);
        } else if (hostMatches(host, Cfg.s(69), Cfg.s(68), Cfg.s(67), Cfg.s(82), Cfg.s(63))) {
            streams = kodik(url);
        } else if (hostMatches(host, Cfg.s(194) + Cfg.bareHost(), Cfg.s(59))) {
            streams = alloha(url);
        } else if (hostMatches(host, Cfg.s(58), Cfg.s(192) + Cfg.bareHost(), Cfg.s(54))) {
            streams = aksor(url);
        } else if (hostMatches(host, Cfg.s(55), Cfg.s(71))) {
            streams = sibnet(url);
        } else if (hostMatches(host, Cfg.s(72))) {
            streams = stormo(url);
        } else if (host.contains(Cfg.s(315)) || path.contains(Cfg.s(420))) {
            streams = hlsEndpoint(url, Cfg.s(38));
        } else if (hostMatches(host, Cfg.s(73), Cfg.s(75), Cfg.s(74)) || path.contains(Cfg.s(305))) {
            streams = vk(url);
        } else if (hostMatches(host, Cfg.s(70))) {
            streams = rutube(url);
        } else if (hostMatches(host, Cfg.s(77), Cfg.s(65))) {
            streams = scan(url, null);
        } else if (hostMatches(host, Cfg.s(60))) {
            streams = aniboom(url);
        } else if (hostMatches(host, Cfg.s(56), Cfg.s(57), Cfg.s(61))) {
            streams = anilib(url);
        } else {
            streams = scan(url, null);
        }

        if (streams.isEmpty()) throw new IOException(Cfg.s(451));
        Resolved r = new Resolved();
        r.streams = streams;
        r.referer = referer;
        return r;
    }

    /* ---------------- Проверка пригодности потока ---------------- */

    /** HLS обязан начинаться с #EXTM3U, MP4 — содержать ftyp/moov. */
    public static boolean verify(String url, String referer, String kind) {
        try {
            Map<String, String> headers = Net.baseHeaders(originOf(url), referer);
            if (Cfg.s(324).equals(kind)) headers.put(Cfg.s(155), Cfg.s(246));
            Request request = new Request.Builder().url(url).headers(Net.Headers.of(headers)).get().build();
            try (Response response = Net.client().newCall(request).execute()) {
                int code = response.code();
                if (code != 200 && code != 206) return false;
                String type = response.header(Cfg.s(145));
                type = type == null ? "" : type.toLowerCase();
                byte[] bytes = response.body() == null ? new byte[0] : response.body().bytes();
                if (Cfg.s(291).equals(kind)) {
                    if (type.contains(Cfg.s(421)) || type.contains(Cfg.s(324))) return false;
                    String text = new String(bytes, StandardCharsets.UTF_8).trim();
                    return text.startsWith(Cfg.s(93));
                }
                if (type.contains(Cfg.s(336))) return false;
                if (type.contains(Cfg.s(421)) || type.contains(Cfg.s(343))) return true;
                int len = Math.min(bytes.length, 64);
                String head = new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
                return head.contains(Cfg.s(284)) || head.contains(Cfg.s(323));
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Оставляет только реально играбельные потоки (порядок качеств сохраняется). */
    public static Map<Integer, String> verifyStreams(Map<Integer, String> streams, String referer) {
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        for (Map.Entry<Integer, String> e : streams.entrySet()) {
            if (verify(e.getValue(), referer, kindOf(e.getValue()))) out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}
