package ru.kelemnfno.anime.data.resolver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.kelemnfno.anime.data.model.EpisodeRow;
import ru.kelemnfno.anime.data.model.Lookup;
import ru.kelemnfno.anime.data.model.SourceResult;
import ru.kelemnfno.anime.data.model.VariantRow;

import static ru.kelemnfno.anime.data.resolver.SourceUtil.cleanLabel;
import static ru.kelemnfno.anime.data.resolver.SourceUtil.embed;
import static ru.kelemnfno.anime.data.resolver.SourceUtil.numberIn;
import static ru.kelemnfno.anime.data.resolver.SourceUtil.plain;
import static ru.kelemnfno.anime.data.resolver.SourceUtil.searchTerms;
import static ru.kelemnfno.anime.data.resolver.SourceUtil.safeUrl;
import static ru.kelemnfno.anime.data.resolver.SourceUtil.voiceTitle;

/**
 * Все источники подбора озвучек. Названия источников никогда не показываются
 * пользователю — наружу уходит только имя озвучки.
 * Порт src/server/tsuyu/sources.ts.
 */
public final class Sources {

    private static final String ANIX_UA = Net.ANIX_UA;

    private Sources() {
    }

    /** Один источник. */
    public interface Source {
        String id();

        boolean adult();

        SourceResult run(Lookup lookup);
    }

    /** Базовые веса для умного объединения (SourceEngine.BASE). */
    public static final Map<String, Integer> SOURCE_BASE = new LinkedHashMap<>();

    static {
        SOURCE_BASE.put("yummy", 100);
        SOURCE_BASE.put("anilibria", 94);
        SOURCE_BASE.put("anixsekai", 90);
        SOURCE_BASE.put("animevost", 88);
        SOURCE_BASE.put("animelib4k", 84);
        SOURCE_BASE.put("animelib", 80);
        SOURCE_BASE.put("animedia", 72);
        SOURCE_BASE.put("animetka", 66);
        SOURCE_BASE.put("anidub", 64);
        SOURCE_BASE.put("hanime", 40);
    }

    /* ============ Реестр ============ */

    public static final List<Source> ALL = new ArrayList<>();

    static {
        ALL.add(simple("yummy", false, Sources::yummy));
        ALL.add(simple("anilibria", false, Sources::anilibria));
        ALL.add(simple("anixsekai", false, Sources::anix));
        ALL.add(simple("animevost", false, Sources::animevost));
        ALL.add(simple("animelib", false, l -> animelibEpisodes(l, false)));
        ALL.add(simple("animelib4k", false, l -> animelibEpisodes(l, true)));
        ALL.add(simple("animedia", false, Sources::animedia));
        ALL.add(simple("animetka", false, Sources::animetka));
        ALL.add(simple("anidub", false, Sources::anidub));
        ALL.add(simple("hanime", true, Sources::hanime));
    }

    private interface Runner {
        SourceResult run(Lookup l) throws Exception;
    }

    private static Source simple(final String id, final boolean adult, final Runner runner) {
        return new Source() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean adult() {
                return adult;
            }

            @Override
            public SourceResult run(Lookup lookup) {
                try {
                    return runner.run(lookup);
                } catch (Throwable t) {
                    return SourceResult.of(id, new ArrayList<>());
                }
            }
        };
    }

    /* ============ Общие helpers ============ */

    private static void push(TreeMap<Integer, EpisodeRow> map, int number, VariantRow variant) {
        push(map, number, variant, null, 0);
    }

    private static void push(TreeMap<Integer, EpisodeRow> map, int number, VariantRow variant, String name, double duration) {
        if (number <= 0) return;
        boolean hasRoute = (variant.embed != null && !variant.embed.isEmpty())
                || (variant.streams != null && !variant.streams.isEmpty());
        if (!hasRoute) return;
        EpisodeRow row = map.get(number);
        if (row == null) {
            row = new EpisodeRow();
            row.number = number;
            map.put(number, row);
        }
        if (name != null && !name.isEmpty() && (row.name == null || row.name.isEmpty())) row.name = name;
        if (duration > 0 && row.duration <= 0) row.duration = duration;
        String key = variant.routeKey();
        for (VariantRow v : row.variants) {
            if (key.equals(v.routeKey())) return;
        }
        row.variants.add(variant);
    }

    private static VariantRow variant(String voice, String source, String embedUrl) {
        VariantRow v = new VariantRow();
        v.voice = voice;
        v.source = source;
        v.embed = embedUrl;
        return v;
    }

    private static VariantRow variant(String voice, String source, Map<Integer, String> streams) {
        VariantRow v = new VariantRow();
        v.voice = voice;
        v.source = source;
        v.streams = streams == null ? new LinkedHashMap<>() : streams;
        int max = 0;
        for (int q : v.streams.keySet()) max = Math.max(max, q);
        v.quality = max;
        return v;
    }

    private static SourceResult done(String source, TreeMap<Integer, EpisodeRow> map) {
        return SourceResult.of(source, new ArrayList<>(map.values()));
    }

    private static TreeMap<Integer, EpisodeRow> map() {
        return new TreeMap<>();
    }

    private static String str(JsonObject o, String key) {
        return J.str(o, key);
    }

    private static int num(JsonObject o, String key) {
        return J.intOf(o, key);
    }

    /** Ссылки-результаты поиска DLE-сайтов. */
    private static List<JsonObject> searchLinks(String html, String base) {
        List<JsonObject> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        Matcher m = Pattern.compile(
                "<a[^>]+href=\"((?:https?://[^\"]+)?/[^\"]*?\\d+[^\"/]*\\.html)\"[^>]*>([\\s\\S]{0,600}?)</a>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            String url = SourceUtil.absolute(base, m.group(1));
            if (url.isEmpty() || seen.contains(url)) continue;
            String inner = plain(m.group(2));
            String attr = SourceUtil.find(m.group(0), "title=\"([^\"]+)\"");
            if (attr.isEmpty()) attr = SourceUtil.find(m.group(0), "alt=\"([^\"]+)\"");
            String title = (inner.isEmpty() ? plain(attr) : inner).replaceAll("^[\\d.,\\s+]*", "").trim();
            if (title.length() < 2) continue;
            seen.add(url);
            JsonObject row = new JsonObject();
            row.addProperty("url", url);
            row.addProperty("title", title);
            out.add(row);
        }
        return out;
    }

    private static int shikiFromHref(String href) {
        String v = SourceUtil.find(href == null ? "" : href, "animes\\/(\\d+)");
        return v.isEmpty() ? 0 : Integer.parseInt(v);
    }

    /** Строгий выбор кандидата: нет уверенного совпадения — источник пропускается. */
    private static <T> T choose(List<T> rows, Lookup l, Match.Describer<T> describer) {
        return Match.pickBest(rows, l, describer);
    }

    /* ============ 1. Yummy (api.yani.tv) ============ */

    static SourceResult yummy(Lookup l) throws Exception {
        TreeMap<Integer, EpisodeRow> map = map();
        String id = l.yummyId > 0 ? String.valueOf(l.yummyId) : "";
        int shiki = l.shikimoriId > 0 ? l.shikimoriId : l.malId;
        Map<String, String> json = Net.baseHeaders(null, null);

        if (id.isEmpty() && shiki > 0) {
            try {
                JsonObject found = Net.getJson(Cfg.apiBase() + "anime?shikimori_ids=" + shiki + "&limit=20", json);
                for (JsonObject r : J.list(found, "response")) {
                    if (num(J.obj(r, "remote_ids"), "shikimori_id") == shiki) {
                        id = str(r, "anime_id");
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (id.isEmpty()) {
            try {
                JsonObject found = Net.getJson(Cfg.apiBase() + "anime?q=" + Net.enc(l.title) + "&limit=20", json);
                JsonObject best = choose(J.list(found, "response"), l, row -> {
                    Match.Candidate c = new Match.Candidate(str(row, "title"));
                    for (JsonElement t : J.arr(row, "other_titles")) c.titles.add(J.str(t));
                    return c.year(num(row, "year"));
                });
                if (best != null) id = str(best, "anime_id");
            } catch (Exception ignored) {
            }
        }
        if (id.isEmpty()) return done("yummy", map);

        JsonObject detail = Net.getJson(Cfg.apiBase() + "anime/" + Net.enc(id) + "?need_videos=true", json);
        for (JsonObject v : J.list(J.obj(detail, "response"), "videos")) {
            String iframe = embed(str(v, "iframe_url"));
            if (iframe.isEmpty()) continue;
            JsonObject data = J.obj(v, "data");
            String voice = voiceTitle(str(data, "dubbing"));
            if (voice.isEmpty()) voice = "Оригинал";
            String name = str(v, "number").isEmpty() ? null : "Серия " + str(v, "number");
            push(map, numberIn(str(v, "number"), 0), variant(voice, "yummy", iframe), name, J.num(v, "duration"));
        }
        return done("yummy", map);
    }

    /* ============ 2. AniLibria (anilibria.top) ============ */

    static SourceResult anilibria(Lookup l) throws Exception {
        TreeMap<Integer, EpisodeRow> map = map();
        Map<String, String> h = Net.baseHeaders(null, null);
        JsonObject release = null;

        if (l.anilibriaAlias != null && !l.anilibriaAlias.isEmpty()) {
            try {
                JsonObject byAlias = Net.getJson(
                        Cfg.s(11) + Net.enc(l.anilibriaAlias), h);
                JsonObject data = J.obj(byAlias, "data");
                if (data.size() > 0) release = data;
            } catch (Exception ignored) {
            }
        }
        if (release == null) {
            for (String term : searchTerms(l.title, l.original)) {
                try {
                    Map<String, String> p = new LinkedHashMap<>();
                    p.put("limit", "12");
                    p.put("page", "1");
                    p.put("f[search]", term);
                    JsonObject data = Net.getJson(Net.query(Cfg.s(10), p), h);
                    JsonObject hit = choose(J.list(data, "data"), l, row -> {
                        JsonObject name = J.obj(row, "name");
                        return new Match.Candidate(str(name, "main"), str(name, "english"), str(name, "alternative"))
                                .year(num(row, "year"));
                    });
                    if (hit != null) {
                        release = hit;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (release == null) return done("anilibria", map);

        String alias = str(release, "alias");
        if (alias.isEmpty()) alias = str(release, "id");
        JsonObject full = Net.getJson(Cfg.s(11) + Net.enc(alias), h);
        for (JsonObject e : J.list(full, "episodes")) {
            Map<Integer, String> streams = new LinkedHashMap<>();
            int[][] pairs = {{480, 0}, {720, 1}, {1080, 2}};
            String[] keys = {"hls_480", "hls_720", "hls_1080"};
            for (int i = 0; i < pairs.length; i++) {
                String safe = safeUrl(str(e, keys[i]));
                if (!safe.isEmpty()) streams.put(pairs[i][0], safe);
            }
            if (streams.isEmpty()) continue;
            push(map, num(e, "ordinal"), variant(Cfg.s(81), "anilibria", streams), str(e, "name"), J.num(e, "duration"));
        }
        return done("anilibria", map);
    }

    /* ============ 3. AnimeVost ============ */

    static SourceResult animevost(Lookup l) throws Exception {
        TreeMap<Integer, EpisodeRow> map = map();
        String id = "";
        for (String term : searchTerms(l.title, l.original)) {
            try {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("name", term);
                JsonObject data = Net.postFormJson(Cfg.s(21), fields, Net.baseHeaders(null, null));
                JsonObject hit = choose(J.list(data, "data"), l, row -> {
                    List<String> titles = new ArrayList<>();
                    titles.add(str(row, "title"));
                    titles.addAll(Arrays.asList(str(row, "title").split("/")));
                    Match.Candidate c = new Match.Candidate();
                    c.titles = titles;
                    return c.year(num(row, "year"));
                });
                if (hit != null) {
                    id = str(hit, "id");
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (id.isEmpty()) return done("animevost", map);

        String text = Net.postForm(Cfg.s(20), "id=" + Net.enc(id),
                Net.baseHeaders(null, null));
        JsonArray list = parseArray(text);
        int i = 0;
        for (JsonElement el : list) {
            i++;
            JsonObject e = J.obj(el);
            Map<Integer, String> streams = new LinkedHashMap<>();
            String std = safeUrl(str(e, "std"));
            String hd = safeUrl(str(e, "hd"));
            if (!std.isEmpty()) streams.put(480, std);
            if (!hd.isEmpty()) streams.put(720, hd);
            if (streams.isEmpty()) continue;
            push(map, numberIn(str(e, "name"), i), variant("AnimeVost", "animevost", streams), str(e, "name"), 0);
        }
        return done("animevost", map);
    }

    /* ============ 4/5. AniLib + AniLib Ultra (api.cdnlibs.org) ============ */

    static SourceResult animelibEpisodes(final Lookup l, final boolean ultra) throws Exception {
        final String source = ultra ? "animelib4k" : "animelib";
        final TreeMap<Integer, EpisodeRow> map = map();
        String slug = "";
        Map<String, String> h = Net.baseHeaders(null, null);
        h.put("Accept", "application/json");

        for (String term : searchTerms(l.title, l.original)) {
            try {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("page", "1");
                p.put("q", term);
                JsonObject data = Net.getJson(Net.query(Cfg.s(23), p), h);
                List<JsonObject> rows = J.list(data, "data");
                if (l.shikimoriId > 0) {
                    for (JsonObject r : rows) {
                        if (shikiFromHref(str(r, "shikimori_href")) == l.shikimoriId) {
                            slug = first(str(r, "slug_url"), str(r, "slug"), str(r, "id"));
                            break;
                        }
                    }
                    if (!slug.isEmpty()) break;
                }
                JsonObject hit = choose(rows, l, row -> new Match.Candidate(str(row, "rus_name"), str(row, "name"),
                        str(row, "eng_name")).year(yearOfRelease(str(row, "releaseDate"))));
                if (hit != null) {
                    slug = first(str(hit, "slug_url"), str(hit, "slug"), str(hit, "id"));
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (slug.isEmpty()) return done(source, map);

        List<JsonObject> rows;
        try {
            JsonObject eps = Net.getJson(Cfg.s(25) + Net.enc(slug), h);
            rows = J.list(eps, "data");
        } catch (Exception e) {
            rows = new ArrayList<>();
        }
        int limit = ultra ? 60 : 40;
        final List<JsonObject> slice = rows.size() > limit ? rows.subList(0, limit) : rows;

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, Math.max(1, slice.size())));
        List<Future<EpisodeRow>> futures = new ArrayList<>();
        for (final JsonObject e : slice) {
            futures.add(pool.submit(new Callable<EpisodeRow>() {
                @Override
                public EpisodeRow call() {
                    int n = (int) Math.round(J.num(e, "number"));
                    if (n < 0) return null;
                    EpisodeRow row = new EpisodeRow();
                    row.number = n;
                    row.name = str(e, "name");
                    try {
                        Map<String, String> hh = Net.baseHeaders(null, null);
                        hh.put("Accept", "application/json");
                        JsonObject detail = Net.getJson(
                                Cfg.s(24) + Net.enc(str(e, "id")), hh);
                        for (JsonObject p : J.list(J.obj(detail, "data"), "players")) {
                            String u = embed(str(p, "src"));
                            if (u.isEmpty()) continue;
                            String teamName = first(str(J.obj(p, "team"), "name"), str(J.obj(p, "team"), "title"));
                            String typeName = first(str(J.obj(p, "translation_type"), "label"),
                                    str(J.obj(p, "translation_type"), "title"), "Озвучка");
                            String label = cleanLabel((typeName + " " + teamName).trim());
                            String voice = voiceTitle(teamName);
                            if (voice.isEmpty()) voice = voiceTitle(label);
                            if (voice.isEmpty()) voice = teamName.isEmpty() ? "Озвучка" : cleanLabel(teamName);
                            row.variants.add(variant(voice, source, u));
                        }
                    } catch (Exception ignored) {
                    }
                    return row;
                }
            }));
        }
        for (Future<EpisodeRow> f : futures) {
            try {
                EpisodeRow row = f.get(25, TimeUnit.SECONDS);
                if (row == null) continue;
                for (VariantRow v : row.variants) push(map, row.number, v, row.name, 0);
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
        return done(source, map);
    }

    private static int yearOfRelease(String value) {
        if (value == null || value.length() < 4) return 0;
        try {
            return Integer.parseInt(value.substring(0, 4));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String first(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    /* ============ 6. AniMedia (amd.online) ============ */

    static SourceResult animedia(Lookup l) throws Exception {
        TreeMap<Integer, EpisodeRow> map = map();
        String base = Cfg.s(7);
        String page = "";
        for (String term : searchTerms(l.title, l.original)) {
            try {
                String body = "do=search&subaction=search&full_search=0&result_from=1&search_start=1&story="
                        + Net.enc(term);
                String html = Net.postForm(base + "/index.php?do=search", body, Net.baseHeaders(base, base + "/"));
                JsonObject hit = choose(searchLinks(html, base), l,
                        row -> new Match.Candidate(str(row, "title")));
                if (hit != null) {
                    page = str(hit, "url");
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (page.isEmpty()) return done("animedia", map);

        String html = Net.get(page, Net.baseHeaders(base, base + "/"));
        Matcher m = Pattern.compile("data-vid=\"([^\"]+)\"[^>]*data-vlnk=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
                .matcher(html);
        int count = 0;
        while (m.find() && count < 120) {
            count++;
            double n = 0;
            try {
                n = Double.parseDouble(m.group(1));
            } catch (Exception ignored) {
            }
            String vod = SourceUtil.absolute(base, m.group(2));
            if (n < 1 || !vod.contains("/vod/")) continue;
            push(map, (int) Math.round(n), variant("AniMedia", "animedia", vod));
        }
        return done("animedia", map);
    }

    /* ============ 7. Animetka ============ */

    static SourceResult animetka(Lookup l) throws Exception {
        TreeMap<Integer, EpisodeRow> map = map();
        Map<String, String> headers = Net.baseHeaders(Cfg.s(13), Cfg.s(14));
        headers.put("Accept", "application/json, text/plain, */*");
        JsonObject material = null;

        for (String term : searchTerms(l.title, l.original)) {
            try {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("query", term);
                p.put("q", term);
                p.put("limit", "12");
                JsonObject data = Net.getJson(Net.query(Cfg.s(17), p), headers);
                List<JsonObject> rows = J.list(data, "data");
                if (rows.isEmpty()) rows = J.list(data, "results");
                if (rows.isEmpty()) rows = J.list(data, "items");
                JsonObject hit = choose(rows, l, row -> new Match.Candidate(str(row, "title"), str(row, "name"),
                        str(row, "title_orig"), str(row, "original")).year(num(row, "year")));
                if (hit != null) {
                    material = hit;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (material == null) return done("animetka", map);

        String id = first(str(material, "animetka_id"), str(material, "id"));
        JsonObject detail = new JsonObject();
        try {
            detail = Net.getJson(Cfg.s(15) + Net.enc(id), headers);
        } catch (Exception ignored) {
        }

        List<JsonObject> translations = J.list(detail, "translations");
        if (translations.isEmpty()) translations = J.list(detail, "voices");
        if (translations.isEmpty()) translations = J.list(detail, "dubs");

        int total = (int) Math.round(J.num(detail, "episodes_count"));
        if (total == 0) total = num(material, "episodes");
        total = Math.max(1, Math.min(200, total == 0 ? 12 : total));

        int materialId = (int) Math.round(J.num(detail, "material_id"));
        if (materialId == 0) materialId = num(material, "material_id");
        if (materialId == 0) {
            try {
                materialId = Integer.parseInt(id);
            } catch (Exception ignored) {
            }
        }

        int index = 0;
        for (JsonObject t : translations) {
            index++;
            if (index > 8) break;
            int tid = (int) Math.round(J.num(t, "id"));
            if (tid == 0) tid = num(t, "translation_id");
            if (tid == 0) continue;
            String raw = first(str(t, "title"), str(t, "name"));
            String voice = voiceTitle(raw);
            if (voice.isEmpty()) voice = cleanLabel(raw);
            if (voice.isEmpty()) voice = "Озвучка";
            for (int ep = 1; ep <= total; ep++) {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("material", String.valueOf(materialId));
                p.put("translation", String.valueOf(tid));
                p.put("episode", String.valueOf(ep));
                String url = Net.query(Cfg.s(16), p);
                push(map, ep, variant(voice, "animetka", url));
            }
        }
        return done("animetka", map);
    }

    /* ============ 8. AniDUB (online.anidub.com) ============ */

    static SourceResult anidub(Lookup l) throws Exception {
        TreeMap<Integer, EpisodeRow> map = map();
        String base = Cfg.s(37);
        String page = "";
        for (String term : searchTerms(l.title, l.original)) {
            try {
                String body = "do=search&subaction=search&search_start=1&full_search=0&result_from=1&story="
                        + Net.enc(term);
                String html = Net.postForm(base + "/index.php?do=search", body, Net.baseHeaders(base, base + "/"));
                JsonObject hit = choose(searchLinks(html, base), l, row -> new Match.Candidate(str(row, "title")));
                if (hit != null) {
                    page = str(hit, "url");
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (page.isEmpty()) return done("anidub", map);

        String html = Net.get(page, Net.baseHeaders(base, base + "/"));
        String embedUrl = SourceUtil.find(html, "https?://[^\"'<>\\s]*?/index\\.php\\?v=[^\"'<>\\s]+")
                .replace("&amp;", "&").replaceAll("(?i)&playlist.*$", "");
        if (embedUrl.isEmpty()) return done("anidub", map);

        String origin = SourceUtil.originOf(embedUrl);
        String playlist;
        try {
            playlist = Net.get(embedUrl + "&playlist", Net.baseHeaders(origin, base + "/"));
        } catch (Exception e) {
            playlist = "";
        }
        Matcher spans = Pattern.compile("<span[^>]+data=\"([^\"]+)\"[^>]*>([\\s\\S]{0,60}?)</span>",
                Pattern.CASE_INSENSITIVE).matcher(playlist);
        int i = 0;
        boolean found = false;
        while (spans.find()) {
            found = true;
            i++;
            String hash = spans.group(1).replaceAll("^/+", "");
            String label = plain(spans.group(2));
            int n = numberIn(label, i);
            push(map, n, variant("AniDUB", "anidub", origin + "/vid.php?v=/" + hash), label, 0);
        }
        if (!found) {
            String single = SourceUtil.find(embedUrl, "[?&]v=\\/?([^&]+)");
            if (!single.isEmpty()) push(map, 1, variant("AniDUB", "anidub", origin + "/vid.php?v=/" + single));
        }
        return done("anidub", map);
    }

    /* ============ 9. AnixSekai ============ */

    private static final String[] ANIX_BASES = {Cfg.s(18), Cfg.s(22)};

    private static Map<String, String> anixHeaders(String base) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("User-Agent", ANIX_UA);
        h.put("Origin", base);
        h.put("Referer", base + "/");
        h.put("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.5");
        return h;
    }

    private static JsonObject anixGet(String path) throws Exception {
        Exception last = null;
        for (String base : ANIX_BASES) {
            try {
                JsonObject root = Net.parse(Net.get(base + path, anixHeaders(base)));
                if (J.has(root, "code") && J.intOf(root, "code") != 0) throw new Exception("anix code");
                return root;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new Exception("anix") : last;
    }

    private static JsonObject anixPost(String path, String body) throws Exception {
        Exception last = null;
        for (String base : ANIX_BASES) {
            try {
                JsonObject root = Net.parse(Net.postJson(base + path, body, anixHeaders(base)));
                if (J.has(root, "code") && J.intOf(root, "code") != 0) throw new Exception("anix code");
                return root;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new Exception("anix") : last;
    }

    /** Ищет первый массив в ответе по списку ключей (структура API плавающая). */
    private static JsonArray firstArray(JsonObject root, String... keys) {
        if (root == null) return new JsonArray();
        for (String k : keys) {
            JsonElement v = root.get(k);
            if (v != null && v.isJsonArray()) return v.getAsJsonArray();
            if (v != null && v.isJsonObject()) {
                JsonArray nested = firstArray(v.getAsJsonObject(), "releases", "content", "data", "list", "items",
                        "types", "sources", "episodes");
                if (nested.size() > 0) return nested;
            }
        }
        return new JsonArray();
    }

    static SourceResult anix(Lookup l) throws Exception {
        TreeMap<Integer, EpisodeRow> map = map();
        JsonObject release = null;
        for (String term : searchTerms(l.title, l.original)) {
            try {
                JsonObject root = anixPost("/search/releases/0",
                        "{\"query\":\"" + escapeJson(term) + "\",\"searchBy\":0}");
                List<JsonObject> rows = J.list(firstArray(root, "releases", "content", "data", "list", "items"));
                JsonObject hit = choose(rows, l, row -> new Match.Candidate(str(row, "title_ru"), str(row, "title"),
                        str(row, "title_original"), str(row, "title_en")).year(num(row, "year")));
                if (hit != null) {
                    release = hit;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (release == null) return done("anixsekai", map);
        String id = first(str(release, "id"), str(release, "releaseId"));
        if (id.isEmpty()) return done("anixsekai", map);

        List<JsonObject> types = J.list(firstArray(anixGetSafe("/episode/" + Net.enc(id)), "types", "data", "items"));
        List<JsonObject> filtered = new ArrayList<>();
        for (JsonObject t : types) {
            if (!voiceTitle(first(str(t, "name"), str(t, "title"))).isEmpty()) filtered.add(t);
        }
        filtered.sort((a, b) -> num(b, "episodes_count") - num(a, "episodes_count"));
        if (filtered.size() > 8) filtered = filtered.subList(0, 8);

        for (JsonObject type : filtered) {
            String voice = voiceTitle(first(str(type, "name"), str(type, "title")));
            int typeId = num(type, "id");
            if (typeId == 0) continue;
            List<JsonObject> sources = J.list(firstArray(
                    anixGetSafe("/episode/" + Net.enc(id) + "/" + typeId), "sources", "data", "items"));
            if (sources.isEmpty()) continue;
            JsonObject chosen = null;
            for (JsonObject s : sources) {
                boolean kodik = num(s, "id") == 12
                        || (str(s, "name") + str(s, "title")).toLowerCase().contains("kodik");
                if (!kodik) {
                    chosen = s;
                    break;
                }
            }
            if (chosen == null) chosen = sources.get(0);
            int sourceId = num(chosen, "id");
            if (sourceId == 0) continue;
            List<JsonObject> eps = J.list(firstArray(
                    anixGetSafe("/episode/" + Net.enc(id) + "/" + typeId + "/" + sourceId), "episodes", "data", "items"));
            int i = 0;
            for (JsonObject e : eps) {
                i++;
                String u = embed(first(str(e, "url"), str(e, "link"), str(e, "iframe_url")));
                if (u.isEmpty()) continue;
                int n = J.firstNum(e, "position", "episode", "number");
                if (n <= 0) n = i;
                push(map, n, variant(voice, "anixsekai", u), first(str(e, "title"), str(e, "name")), J.num(e, "duration"));
            }
        }
        return done("anixsekai", map);
    }

    private static JsonObject anixGetSafe(String path) {
        try {
            return anixGet(path);
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Массив JSON из текста; пусто, если формат другой. */
    static JsonArray parseArray(String text) {
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(text);
            return el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
        } catch (Exception e) {
            return new JsonArray();
        }
    }

    /* ============ 10. Hanime (18+, только для хентая) ============ */

    static SourceResult hanime(Lookup l) throws Exception {
        TreeMap<Integer, EpisodeRow> map = map();
        String body = "{\"search_text\":\"" + escapeJson(l.title) + "\",\"tags\":[],\"tags_mode\":\"AND\","
                + "\"brands\":[],\"blacklist\":[],\"order_by\":\"created_at_unix\",\"ordering\":\"desc\",\"page\":0}";
        Map<String, String> h = Net.baseHeaders(Cfg.s(31), Cfg.s(32));
        h.put("Accept", "application/json");
        String root;
        try {
            root = Net.postJson(Cfg.s(46), body, h);
        } catch (Exception e) {
            return done("hanime", map);
        }
        List<JsonObject> hits = new ArrayList<>();
        try {
            JsonObject parsed = Net.parse(root);
            String hitsRaw = J.str(parsed, "hits");
            if (!hitsRaw.isEmpty()) {
                hits = J.list(parseArray(hitsRaw));
            }
        } catch (Exception e) {
            return done("hanime", map);
        }
        JsonObject best = choose(hits, l, row -> new Match.Candidate(str(row, "name"), str(row, "titles")));
        if (best == null) return done("hanime", map);
        String slug = str(best, "slug");
        if (slug.isEmpty()) return done("hanime", map);

        JsonObject detail;
        try {
            detail = Net.getJson(Cfg.s(33) + Net.enc(slug), h);
        } catch (Exception e) {
            return done("hanime", map);
        }
        Map<Integer, String> streams = new LinkedHashMap<>();
        for (JsonObject s : J.list(J.obj(detail, "videos_manifest"), "servers")) {
            for (JsonObject st : J.list(s, "streams")) {
                String u = safeUrl(str(st, "url"));
                if (u.isEmpty()) continue;
                int height = J.firstNum(st, "height", "width");
                streams.put(height > 0 ? height : 720, u);
            }
        }
        if (!streams.isEmpty()) push(map, 1, variant("Оригинал", "hanime", streams));
        return done("hanime", map);
    }
}
