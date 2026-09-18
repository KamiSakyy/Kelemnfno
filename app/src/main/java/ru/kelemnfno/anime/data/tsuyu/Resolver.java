package ru.kelemnfno.anime.data.tsuyu;

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

import static ru.kelemnfno.anime.data.tsuyu.TsuyuUtil.absolute;
import static ru.kelemnfno.anime.data.tsuyu.TsuyuUtil.find;
import static ru.kelemnfno.anime.data.tsuyu.TsuyuUtil.findAll;
import static ru.kelemnfno.anime.data.tsuyu.TsuyuUtil.hostMatches;
import static ru.kelemnfno.anime.data.tsuyu.TsuyuUtil.hostOf;
import static ru.kelemnfno.anime.data.tsuyu.TsuyuUtil.originOf;
import static ru.kelemnfno.anime.data.tsuyu.TsuyuUtil.param;
import static ru.kelemnfno.anime.data.tsuyu.TsuyuUtil.pathOf;
import static ru.kelemnfno.anime.data.tsuyu.TsuyuUtil.qualityOf;
import static ru.kelemnfno.anime.data.tsuyu.TsuyuUtil.safeUrl;

/**
 * Превращает ссылку плеера (Kodik, CDNVideoHub, Alloha, Aksor, Sibnet, Stormo, AniBoom,
 * AniLib, Animetka, AniDUB, VK, Rutube, ZedFilm, прямые m3u8/mpd/mp4) в прямые потоки.
 * Порт src/server/tsuyu/resolver.ts.
 */
public final class Resolver {

    private static final Pattern M3U8_RE = Pattern.compile(
            "https?:\\\\?/\\\\?/[^\"'\\s\\\\<>]+?\\.m3u8[^\"'\\s\\\\<>]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern MP4_RE = Pattern.compile(
            "https?:\\\\?/\\\\?/[^\"'\\s\\\\<>]+?\\.mp4[^\"'\\s\\\\<>]*", Pattern.CASE_INSENSITIVE);

    private Resolver() {
    }

    /** Результат подбора: качество → url + referer источника. */
    public static class Resolved {
        public Map<Integer, String> streams = new TreeMap<>(Collections.reverseOrder());
        public String referer = "";
    }

    public static String kindOf(String url) {
        if (url == null) return "hls";
        if (Pattern.compile("\\.mp4(\\?|$)", Pattern.CASE_INSENSITIVE).matcher(url).find()) return "mp4";
        if (url.toLowerCase().contains(".mpd")) return "dash";
        return "hls";
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
        if (manifest == null || !manifest.trim().startsWith("#EXTM3U")) return out;
        int height = 0;
        for (String raw : manifest.split("\\r?\\n")) {
            String l = raw.trim();
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                Matcher m = Pattern.compile("RESOLUTION=\\d+x(\\d+)").matcher(l);
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
            if (p.contains(".m3u8") || lower.contains(".m3u8")) {
                try {
                    String manifest = Net.get(url, Net.baseHeaders(originOf(url), referer), 12_000);
                    out.putAll(parseHls(manifest, url));
                } catch (Exception ignored) {
                }
                if (out.isEmpty()) put(out, qualityOf(url), url);
            } else if (p.contains(".mpd") || lower.contains(".mpd")) {
                out.put(0, url);
            } else if (Pattern.compile("\\.(mp4|mkv|webm)").matcher(p).find()
                    || Pattern.compile("\\.(mp4|mkv|webm)").matcher(lower).find()) {
                put(out, qualityOf(url), url);
            }
        } catch (Exception ignored) {
        }
        return clean(out);
    }

    /* ---------------- Kodik ---------------- */

    /** Путь эндпоинта из app.player_single: ищем atob('…') → "/get-player". */
    private static String endpointPath(String script) {
        List<String> all = findAll(script, "atob\\(\\s*[\"']([A-Za-z0-9+/=]+)[\"']");
        for (String b64 : all) {
            String clean = b64.replaceAll("[^A-Za-z0-9+/=]", "");
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
            if (decoded.matches("^(https?:)?//.*")) {
                return safeUrl(decoded.startsWith("//") ? "https:" + decoded : decoded);
            }
        }
        String plain = b64(raw);
        if (plain.matches("^(https?:)?//.*")) return safeUrl(plain.startsWith("//") ? "https:" + plain : plain);
        return safeUrl(raw);
    }

    private static Map<Integer, String> kodik(String url) throws IOException {
        String page = Net.get(url, Net.baseHeaders(originOf(url), Secrets.referer()), 15_000)
                .replaceAll("[\\n\\r]", "");

        Map<String, String> payload = new LinkedHashMap<>();
        String urlParams = find(page, "\\burlParams\\s*=\\s*'([^']+)'");
        if (urlParams.isEmpty()) urlParams = find(page, "\\burlParams\\s*=\\s*\"([^\"]+)\"");
        if (!urlParams.isEmpty()) {
            try {
                JsonObject p = Net.parse(TsuyuUtil.unescape(urlParams));
                for (String k : new String[]{"d", "d_sign", "pd", "pd_sign", "ref", "ref_sign"}) {
                    payload.put(k, J.str(p, k));
                }
            } catch (Exception ignored) {
            }
        }
        if (payload.get("d") == null || payload.get("d").isEmpty()) {
            payload.put("d", find(page, "var\\s+domain\\s*=\\s*[\"'](.+?)[\"']"));
            payload.put("d_sign", find(page, "var\\s+d_sign\\s*=\\s*[\"'](.+?)[\"']"));
            payload.put("pd", find(page, "var\\s+pd\\s*=\\s*[\"'](.+?)[\"']"));
            payload.put("pd_sign", find(page, "var\\s+pd_sign\\s*=\\s*[\"'](.+?)[\"']"));
            payload.put("ref", find(page, "var\\s+ref\\s*=\\s*[\"'](.+?)[\"']"));
            payload.put("ref_sign", find(page, "var\\s+ref_sign\\s*=\\s*[\"'](.+?)[\"']"));
        }
        String type = find(page, "(?:videoInfo|vInfo)\\.type\\s*\\+?=\\s*[\"'](.+?)[\"']");
        if (type.isEmpty()) type = find(page, "[\"']type[\"']\\s*:\\s*[\"'](.+?)[\"']");
        String hash = find(page, "(?:videoInfo|vInfo)\\.hash\\s*\\+?=\\s*[\"'](.+?)[\"']");
        if (hash.isEmpty()) hash = find(page, "[\"']hash[\"']\\s*:\\s*[\"'](.+?)[\"']");
        String id = find(page, "(?:videoInfo|vInfo)\\.id\\s*\\+?=\\s*[\"'](.+?)[\"']");
        if (id.isEmpty()) id = find(page, "[\"']id[\"']\\s*:\\s*[\"'](.+?)[\"']");
        if (id.isEmpty()) id = find(page, "var\\s+videoId\\s*=\\s*[\"'](\\d+)[\"']");
        payload.put("type", type);
        payload.put("hash", hash);
        payload.put("id", id);

        String endpoint = originOf(url) + "/ftor";
        String script = find(page, "src=[\"']((?:\\/\\/[^\"']+)?\\/assets\\/js\\/app\\.player_single[^\"']+)[\"']");
        if (script.isEmpty()) script = find(page, "src=[\"']([^\"']*app\\.player_single[^\"']+)[\"']");
        if (script.isEmpty()) script = find(page, "src=[\"']([^\"']*assets\\/js[^\"']+)[\"']");
        if (!script.isEmpty()) {
            try {
                String scriptUrl = absolute(url, script);
                String body = Net.get(scriptUrl, Net.baseHeaders(originOf(url), url), 12_000);
                String p = endpointPath(body);
                if (!p.isEmpty()) endpoint = originOf(scriptUrl) + p;
            } catch (Exception ignored) {
            }
        }

        for (String k : new String[]{"d", "d_sign", "pd", "pd_sign", "type", "hash", "id"}) {
            if (payload.get(k) == null || payload.get(k).isEmpty()) throw new IOException("kodik: нет параметров");
        }

        String body = Net.formBodyRaw(
                "d", payload.get("d"),
                "d_sign", payload.get("d_sign"),
                "pd", payload.get("pd"),
                "pd_sign", payload.get("pd_sign"),
                "ref", payload.get("ref") == null ? "" : payload.get("ref"),
                "ref_sign", payload.get("ref_sign") == null ? "" : payload.get("ref_sign"),
                "bad_user", "true",
                "cdn_is_working", "true",
                "type", payload.get("type"),
                "hash", payload.get("hash"),
                "id", payload.get("id"),
                "info", "{}");

        List<String> bases = new ArrayList<>();
        bases.add(endpoint);
        bases.add(originOf(url) + "/ftor");
        bases.add(Secrets.s(34));
        JsonObject links = null;
        for (String base : new LinkedHashSet<String>(bases)) {
            try {
                Map<String, String> h = Net.baseHeaders(originOf(url), url);
                h.put("X-Requested-With", "XMLHttpRequest");
                h.put("Accept", "application/json, text/javascript, */*; q=0.01");
                JsonObject root = Net.parse(Net.postForm(base, body, h));
                JsonObject l = J.obj(root, "links");
                if (l.size() > 0) {
                    links = l;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (links == null) throw new IOException("kodik: нет ссылок");

        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        for (Map.Entry<String, JsonElement> entry : links.entrySet()) {
            int q = 0;
            String digits = entry.getKey().replaceAll("\\D+", "");
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
                    srcs.add(el.isJsonPrimitive() ? el.getAsString() : J.str(J.obj(el), "src"));
                }
            } else {
                srcs.add(value.isJsonPrimitive() ? value.getAsString() : J.str(J.obj(value), "src"));
            }
            for (String src : srcs) {
                if (src == null || src.isEmpty()) continue;
                String decoded = kodikDecode(src);
                if (decoded.isEmpty()) decoded = safeUrl(src);
                if (!decoded.isEmpty()) put(out, q, decoded);
            }
        }
        if (out.isEmpty()) throw new IOException("kodik: не удалось декодировать");
        return clean(out);
    }

    /* ---------------- CDNVideoHub (Yummy) ---------------- */

    private static String normalizeCvh(String value, String failover) {
        String safe = safeUrl(value);
        if (safe.isEmpty() || failover == null || failover.isEmpty()) return safe;
        try {
            java.net.URL u = new java.net.URL(safe);
            if (!"https".equals(u.getProtocol()) || !u.getHost().matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) return safe;
            return new java.net.URL("https", failover, u.getPort(), u.getFile()).toString();
        } catch (Exception e) {
            return safe;
        }
    }

    private static Map<Integer, String> cvh(String url) throws IOException {
        String animeId = param(url, "anime_id");
        if (animeId.isEmpty()) animeId = param(url, "id");
        if (animeId.isEmpty()) throw new IOException("cvh: нет id");
        int ep = 1;
        try {
            ep = Integer.parseInt(param(url, "episode"));
        } catch (Exception ignored) {
        }
        if (ep <= 0) ep = 1;
        String dubbing = param(url, "dubbing_code");

        Map<String, String> h = Net.baseHeaders(Secrets.s(41), Secrets.s(42));
        h.put("Accept", "application/json");
        JsonObject playlist = Net.getJson(
                Secrets.s(39) + Net.enc(animeId) + "&aggr=mali", h);

        JsonObject chosen = null;
        for (JsonObject item : J.list(playlist, "items")) {
            if (J.intOf(item, "episode") != ep) continue;
            if (chosen == null) chosen = item;
            if (!dubbing.isEmpty() && J.str(item, "voiceStudio").equalsIgnoreCase(dubbing)) {
                chosen = item;
                break;
            }
        }
        String vkId = chosen == null ? "" : J.str(chosen, "vkId");
        if (vkId.isEmpty()) throw new IOException("cvh: серия не найдена");

        JsonObject video = Net.getJson(
                Secrets.s(40) + Net.enc(vkId), h);
        String failover = J.str(video, "failoverHost");
        JsonObject s = J.obj(video, "sources");

        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        putCvh(out, 240, J.str(s, "mpegLowestUrl"), failover);
        putCvh(out, 360, J.str(s, "mpegLowUrl"), failover);
        putCvh(out, 480, J.str(s, "mpegMediumUrl"), failover);
        putCvh(out, 720, J.str(s, "mpegHighUrl"), failover);
        putCvh(out, 1080, J.str(s, "mpegFullHdUrl"), failover);
        putCvh(out, 1440, first(J.str(s, "mpegQuadHdUrl"), J.str(s, "mpeg2kUrl")), failover);
        putCvh(out, 2160, first(J.str(s, "mpegUltraHdUrl"), J.str(s, "mpegUhdUrl"), J.str(s, "mpeg4kUrl"),
                J.str(s, "mpegOriginalUrl")), failover);
        if (out.isEmpty()) throw new IOException("cvh: нет вариантов");
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
        String page = Net.get(url, Net.baseHeaders(originOf(url), Secrets.referer()), 15_000);
        String id = find(page,
                "const\\s+fileList\\s*=\\s*JSON\\.parse\\('\\{\"type\":\\s*\"serial\",\\s*\"active\":\\s*\\{\"id\":\\s*(\\d+)");
        if (id.isEmpty()) id = find(page, "\"active\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*(\\d+)");
        String token = find(page, "const\\s+userParam\\s*=\\s*\\{[\\s\\S]*?token:\\s*[\"'](.+?)[\"']");
        String user = find(page, "<meta\\s+name\\s*=\\s*[\"']user[\"']\\s+content\\s*=\\s*[\"'](.+?)[\"']");
        if (id.isEmpty() || token.isEmpty() || user.isEmpty()) throw new IOException("alloha: нет параметров");

        Map<String, String> h = Net.baseHeaders(Secrets.alloha(), url);
        h.put("Accepts-Controls", user);
        String body = "token=" + Net.enc(token) + "&av1=true&autoplay=0&audio=&subtitle=";
        JsonObject root = Net.parse(Net.postForm(Secrets.alloha() + "/movie/" + id, body, h));

        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        JsonArray hlsSource = J.arr(root, "hlsSource");
        if (hlsSource.size() > 0) {
            JsonObject q = J.obj(J.obj(hlsSource.get(0)), "quality");
            for (Map.Entry<String, JsonElement> e : q.entrySet()) {
                if (e.getKey().equalsIgnoreCase("object")) continue;
                String link = J.str(e.getValue());
                int cut = link.indexOf(" or ");
                if (cut >= 0) link = link.substring(0, cut);
                String safe = safeUrl(link);
                if (safe.isEmpty()) continue;
                String digits = e.getKey().replaceAll("\\D+", "");
                int quality = digits.isEmpty() ? qualityOf(safe) : Integer.parseInt(digits);
                out.put(quality > 0 ? quality : 720, safe);
            }
        }
        if (out.isEmpty()) throw new IOException("alloha: пусто");
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
                JsonObject j = Net.getJson(origin + "/api/video/" + Net.enc(id), Net.baseHeaders(origin, url));
                for (Map.Entry<String, JsonElement> e : J.obj(j, "qualities").entrySet()) {
                    String safe = safeUrl(J.str(e.getValue()));
                    if (safe.isEmpty()) continue;
                    String digits = e.getKey().replaceAll("\\D+", "");
                    out.put(digits.isEmpty() ? qualityOf(safe) : Integer.parseInt(digits), safe);
                }
                for (JsonObject row : J.list(j, "sources")) {
                    String safe = safeUrl(first(J.str(row, "url"), J.str(row, "src")));
                    if (!safe.isEmpty()) out.put(J.intOf(row, "height") > 0 ? J.intOf(row, "height") : qualityOf(safe), safe);
                }
            }
        } catch (Exception ignored) {
        }
        if (!out.isEmpty()) return clean(out);
        try {
            String page = Net.get(url, Net.baseHeaders(origin, Secrets.referer()), 12_000);
            String link = absolute(url, find(page, "var\\s+videoUrl\\s*=\\s*[\"'](.+?)[\"']"));
            if (!link.isEmpty()) put(out, qualityOf(link), link);
        } catch (Exception ignored) {
        }
        return clean(out);
    }

    /* ---------------- Sibnet / Stormo ---------------- */

    private static Map<Integer, String> sibnet(String url) throws IOException {
        String page = Net.get(url, Net.baseHeaders(originOf(url), url), 12_000);
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        for (String m : findAll(page, "[\"'](\\/v\\/[a-z0-9]+\\/[^\"']+?\\.m3u8)[\"']")) {
            put(out, 480, Secrets.s(51) + m);
        }
        for (String m : findAll(page, "src:\\s*[\"']\\/(.+?\\.mp4[^\"']*)[\"']")) {
            put(out, 480, Secrets.s(52) + m.replace("\\/", "/"));
        }
        if (out.isEmpty()) throw new IOException("sibnet: пусто");
        return clean(out);
    }

    private static Map<Integer, String> stormo(String url) throws IOException {
        String page = Net.get(url, Net.baseHeaders(originOf(url), url), 12_000);
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        Matcher m = Pattern.compile("https?:\\\\?/\\\\?/www\\.stormo\\.tv/get_file/[^\"'<>\\s]+?\\.mp4/?",
                Pattern.CASE_INSENSITIVE).matcher(page);
        while (m.find()) put(out, 480, m.group().replace("\\/", "/"));
        if (out.isEmpty()) return scan(url, null);
        return clean(out);
    }

    /* ---------------- AniBoom / AniLib ---------------- */

    private static Map<Integer, String> aniboom(String url) throws IOException {
        String page = Net.get(url, Net.baseHeaders(originOf(url), Secrets.s(12)), 12_000);
        String raw = find(page, "data-parameters\\s*=\\s*\"([^\"]+)\"");
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        if (!raw.isEmpty()) {
            try {
                JsonObject params = Net.parse(TsuyuUtil.unescape(raw));
                JsonObject hls = Net.parse(J.str(params, "hls"));
                for (Map.Entry<String, JsonElement> e : hls.entrySet()) {
                    String safe = safeUrl(J.str(e.getValue()));
                    if (!safe.isEmpty()) out.putAll(direct(safe, url));
                }
            } catch (Exception ignored) {
            }
        }
        if (out.isEmpty()) return scan(url, Secrets.s(8));
        return clean(out);
    }

    private static Map<Integer, String> anilib(String url) throws IOException {
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        String safe = safeUrl(url);
        out.putAll(direct(safe, Secrets.s(9)));
        if (out.isEmpty() && !safe.isEmpty()) put(out, qualityOf(safe), safe);
        return clean(out);
    }

    /* ---------------- Animetka playlist ---------------- */

    private static Map<Integer, String> animetkaPlaylist(String url) throws IOException {
        Map<String, String> h = Net.baseHeaders(Secrets.s(13), Secrets.s(14));
        h.put("Accept", "application/json, text/plain, */*");
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
        if (out.isEmpty()) throw new IOException("animetka: пусто");
        return clean(out);
    }

    private static void walkMedia(JsonElement el, Map<Integer, String> out) {
        if (el == null) return;
        if (el.isJsonPrimitive()) {
            String v = el.getAsString();
            if (v != null && Pattern.compile("\\.(m3u8|mp4)", Pattern.CASE_INSENSITIVE).matcher(v).find()) {
                put(out, 720, v.startsWith("//") ? "https:" + v : v);
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
        String page = TsuyuUtil.unescape(Net.get(url, Net.baseHeaders(originOf(url), Secrets.referer()), 15_000));
        String ext = find(page, "(?:src|href)=[\"']([^\"']*video_ext\\.php[^\"']+)[\"']");
        if (ext.isEmpty()) ext = find(page, "(https?:\\\\?/\\\\?/vk\\.com/video_ext\\.php[^\"'<>\\s]+)");
        if (!ext.isEmpty()) {
            try {
                page += "\n" + TsuyuUtil.unescape(Net.get(absolute(url, ext), Net.baseHeaders(Secrets.s(53), url), 12_000));
            } catch (Exception ignored) {
            }
        }
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        Matcher m = Pattern.compile("[\"'](?:url|mp4_)(\\d{3,4})[\"']\\s*[:=]\\s*[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(page);
        while (m.find()) {
            String safe = safeUrl(m.group(2));
            if (!safe.isEmpty()) out.put(Integer.parseInt(m.group(1)), safe);
        }
        Matcher m2 = Pattern.compile("[\"'](?:hls_fmp4|hls|dash_sep)[\"']\\s*[:=]\\s*[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(page);
        while (m2.find()) {
            String safe = safeUrl(m2.group(1));
            if (!safe.isEmpty()) out.putAll(direct(safe, url));
        }
        if (out.isEmpty()) throw new IOException("vk: пусто");
        return clean(out);
    }

    private static String rutubeId(String value) {
        String id = find(value, "rutube\\.ru/(?:play/embed|video)/([0-9a-f]{32})");
        if (id.isEmpty()) id = find(value, "([0-9a-f]{32})");
        return id;
    }

    private static Map<Integer, String> rutube(String url) throws IOException {
        String id = rutubeId(url);
        if (id.isEmpty()) {
            try {
                id = rutubeId(Net.get(url, Net.baseHeaders(Secrets.s(43), Secrets.s(44)), 12_000));
            } catch (Exception ignored) {
            }
        }
        if (id.isEmpty()) throw new IOException("rutube: нет id");
        Map<String, String> h = Net.baseHeaders(Secrets.s(43), url);
        h.put("Accept", "application/json,*/*");
        JsonObject root = Net.getJson(Secrets.s(45) + Net.enc(id)
                + "/?no_404=true&referer=" + Net.enc(url) + "&pver=v2", h);
        Map<Integer, String> out = new TreeMap<>(Collections.reverseOrder());
        for (Map.Entry<String, JsonElement> e : J.obj(root, "video_balancer").entrySet()) {
            String safe = safeUrl(J.str(e.getValue()));
            if (!safe.isEmpty()) out.putAll(direct(safe, url));
        }
        if (out.isEmpty()) throw new IOException("rutube: пусто");
        return clean(out);
    }

    /* ---------------- HLS-эндпоинт (AniDUB vid.php / ladony) ---------------- */

    private static Map<Integer, String> hlsEndpoint(String url, String referer) throws IOException {
        String body = Net.get(url, Net.baseHeaders(originOf(url), referer), 12_000);
        if (body.trim().startsWith("#EXTM3U")) {
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
        String body = TsuyuUtil.unescape(page);
        Matcher m = M3U8_RE.matcher(body);
        while (m.find()) put(out, 720, m.group().replace("\\/", "/"));
        if (out.isEmpty()) {
            Matcher m2 = MP4_RE.matcher(body);
            while (m2.find()) put(out, 480, m2.group().replace("\\/", "/"));
        }
        String file = find(body, "file\\s*:\\s*[\"']([^\"']+)[\"']");
        if (!file.isEmpty()) {
            String safe = absolute(url, file);
            if (!safe.isEmpty()) out.putAll(direct(safe, url));
        }
        if (out.isEmpty()) throw new IOException("не удалось найти поток");
        return clean(out);
    }

    /* ---------------- Точка входа ---------------- */

    public static Resolved resolveStreams(String input) throws IOException {
        String url = TsuyuUtil.embed(input);
        if (url.isEmpty()) url = safeUrl(input);
        if (url.isEmpty()) throw new IOException("пустая ссылка");

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
        if ((hostMatches(host, Secrets.s(76), Secrets.bareHost()) && path.contains("iframecvh"))
                || hostMatches(host, Secrets.s(64))) {
            streams = cvh(url);
        } else if (hostMatches(host, Secrets.s(62)) && path.startsWith("/api/anime/playlist")) {
            streams = animetkaPlaylist(url);
        } else if (hostMatches(host, Secrets.s(69), Secrets.s(68), Secrets.s(67), "kodik.biz", Secrets.s(63))) {
            streams = kodik(url);
        } else if (hostMatches(host, "alloha." + Secrets.bareHost(), Secrets.s(59))) {
            streams = alloha(url);
        } else if (hostMatches(host, Secrets.s(58), "aksor." + Secrets.bareHost(), Secrets.s(54))) {
            streams = aksor(url);
        } else if (hostMatches(host, Secrets.s(55), Secrets.s(71))) {
            streams = sibnet(url);
        } else if (hostMatches(host, Secrets.s(72))) {
            streams = stormo(url);
        } else if (host.contains("ladony") || path.contains("vid.php")) {
            streams = hlsEndpoint(url, Secrets.s(38));
        } else if (hostMatches(host, Secrets.s(73), Secrets.s(75), Secrets.s(74)) || path.contains("iframevk")) {
            streams = vk(url);
        } else if (hostMatches(host, Secrets.s(70))) {
            streams = rutube(url);
        } else if (hostMatches(host, Secrets.s(77), Secrets.s(65))) {
            streams = scan(url, null);
        } else if (hostMatches(host, Secrets.s(60))) {
            streams = aniboom(url);
        } else if (hostMatches(host, Secrets.s(56), Secrets.s(57), Secrets.s(61))) {
            streams = anilib(url);
        } else {
            streams = scan(url, null);
        }

        if (streams.isEmpty()) throw new IOException("поток не найден");
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
            if ("mp4".equals(kind)) headers.put("Range", "bytes=0-2048");
            Request request = new Request.Builder().url(url).headers(Net.Headers.of(headers)).get().build();
            try (Response response = Net.client().newCall(request).execute()) {
                int code = response.code();
                if (code != 200 && code != 206) return false;
                String type = response.header("Content-Type");
                type = type == null ? "" : type.toLowerCase();
                byte[] bytes = response.body() == null ? new byte[0] : response.body().bytes();
                if ("hls".equals(kind)) {
                    if (type.contains("video/") || type.contains("mp4")) return false;
                    String text = new String(bytes, StandardCharsets.UTF_8).trim();
                    return text.startsWith("#EXTM3U");
                }
                if (type.contains("mpegurl")) return false;
                if (type.contains("video/") || type.contains("octet-stream")) return true;
                int len = Math.min(bytes.length, 64);
                String head = new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
                return head.contains("ftyp") || head.contains("moov");
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
