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
        SOURCE_BASE.put(Cfg.s(434), 100);
        SOURCE_BASE.put(Cfg.s(210), 94);
        SOURCE_BASE.put(Cfg.s(235), 90);
        SOURCE_BASE.put(Cfg.s(227), 88);
        SOURCE_BASE.put(Cfg.s(222), 84);
        SOURCE_BASE.put(Cfg.s(221), 80);
        SOURCE_BASE.put(Cfg.s(220), 72);
        SOURCE_BASE.put(Cfg.s(224), 66);
        SOURCE_BASE.put(Cfg.s(207), 64);
        SOURCE_BASE.put(Cfg.s(286), 40);
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
                Cfg.s(125),
                Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            String url = SourceUtil.absolute(base, m.group(1));
            if (url.isEmpty() || seen.contains(url)) continue;
            String inner = plain(m.group(2));
            String attr = SourceUtil.find(m.group(0), Cfg.s(396));
            if (attr.isEmpty()) attr = SourceUtil.find(m.group(0), Cfg.s(197));
            String title = (inner.isEmpty() ? plain(attr) : inner).replaceAll(Cfg.s(189), "").trim();
            if (title.length() < 2) continue;
            seen.add(url);
            JsonObject row = new JsonObject();
            row.addProperty(Cfg.s(411), url);
            row.addProperty(Cfg.s(395), title);
            out.add(row);
        }
        return out;
    }

    private static int shikiFromHref(String href) {
        String v = SourceUtil.find(href == null ? "" : href, Cfg.s(223));
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
                JsonObject found = Net.getJson(Cfg.apiBase() + Cfg.s(218) + shiki + Cfg.s(98), json);
                for (JsonObject r : J.list(found, Cfg.s(365))) {
                    if (num(J.obj(r, Cfg.s(364)), Cfg.s(374)) == shiki) {
                        id = str(r, Cfg.s(219));
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (id.isEmpty()) {
            try {
                JsonObject found = Net.getJson(Cfg.apiBase() + Cfg.s(217) + Net.enc(l.title) + "&limit=20", json);
                JsonObject best = choose(J.list(found, "response"), l, row -> {
                    Match.Candidate c = new Match.Candidate(str(row, "title"));
                    for (JsonElement t : J.arr(row, Cfg.s(348))) c.titles.add(J.str(t));
                    return c.year(num(row, Cfg.s(432)));
                });
                if (best != null) id = str(best, "anime_id");
            } catch (Exception ignored) {
            }
        }
        if (id.isEmpty()) return done("yummy", map);

        JsonObject detail = Net.getJson(Cfg.apiBase() + Cfg.s(216) + Net.enc(id) + Cfg.s(128), json);
        for (JsonObject v : J.list(J.obj(detail, "response"), Cfg.s(423))) {
            String iframe = embed(str(v, Cfg.s(303)));
            if (iframe.isEmpty()) continue;
            JsonObject data = J.obj(v, Cfg.s(262));
            String voice = voiceTitle(str(data, Cfg.s(271)));
            if (voice.isEmpty()) voice = Cfg.s(438);
            String name = str(v, Cfg.s(341)).isEmpty() ? null : Cfg.s(439) + str(v, "number");
            push(map, numberIn(str(v, "number"), 0), variant(voice, "yummy", iframe), name, J.num(v, Cfg.s(274)));
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
                    p.put(Cfg.s(316), "12");
                    p.put(Cfg.s(350), "1");
                    p.put(Cfg.s(280), term);
                    JsonObject data = Net.getJson(Net.query(Cfg.s(10), p), h);
                    JsonObject hit = choose(J.list(data, "data"), l, row -> {
                        JsonObject name = J.obj(row, Cfg.s(337));
                        return new Match.Candidate(str(name, Cfg.s(320)), str(name, Cfg.s(276)), str(name, Cfg.s(198)))
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

        String alias = str(release, Cfg.s(193));
        if (alias.isEmpty()) alias = str(release, "id");
        JsonObject full = Net.getJson(Cfg.s(11) + Net.enc(alias), h);
        for (JsonObject e : J.list(full, Cfg.s(278))) {
            Map<Integer, String> streams = new LinkedHashMap<>();
            int[][] pairs = {{480, 0}, {720, 1}, {1080, 2}};
            String[] keys = {Cfg.s(294), Cfg.s(295), Cfg.s(293)};
            for (int i = 0; i < pairs.length; i++) {
                String safe = safeUrl(str(e, keys[i]));
                if (!safe.isEmpty()) streams.put(pairs[i][0], safe);
            }
            if (streams.isEmpty()) continue;
            push(map, num(e, Cfg.s(346)), variant(Cfg.s(81), "anilibria", streams), str(e, "name"), J.num(e, "duration"));
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

        String text = Net.postForm(Cfg.s(20), Cfg.s(301) + Net.enc(id),
                Net.baseHeaders(null, null));
        JsonArray list = parseArray(text);
        int i = 0;
        for (JsonElement el : list) {
            i++;
            JsonObject e = J.obj(el);
            Map<Integer, String> streams = new LinkedHashMap<>();
            String std = safeUrl(str(e, Cfg.s(386)));
            String hd = safeUrl(str(e, "hd"));
            if (!std.isEmpty()) streams.put(480, std);
            if (!hd.isEmpty()) streams.put(720, hd);
            if (streams.isEmpty()) continue;
            push(map, numberIn(str(e, "name"), i), variant(Cfg.s(143), "animevost", streams), str(e, "name"), 0);
        }
        return done("animevost", map);
    }

    /* ============ 4/5. AniLib + AniLib Ultra (api.cdnlibs.org) ============ */

    static SourceResult animelibEpisodes(final Lookup l, final boolean ultra) throws Exception {
        final String source = ultra ? "animelib4k" : "animelib";
        final TreeMap<Integer, EpisodeRow> map = map();
        String slug = "";
        Map<String, String> h = Net.baseHeaders(null, null);
        h.put(Cfg.s(129), Cfg.s(236));

        for (String term : searchTerms(l.title, l.original)) {
            try {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("page", "1");
                p.put("q", term);
                JsonObject data = Net.getJson(Net.query(Cfg.s(23), p), h);
                List<JsonObject> rows = J.list(data, "data");
                if (l.shikimoriId > 0) {
                    for (JsonObject r : rows) {
                        if (shikiFromHref(str(r, Cfg.s(373))) == l.shikimoriId) {
                            slug = first(str(r, Cfg.s(378)), str(r, Cfg.s(377)), str(r, "id"));
                            break;
                        }
                    }
                    if (!slug.isEmpty()) break;
                }
                JsonObject hit = choose(rows, l, row -> new Match.Candidate(str(row, Cfg.s(368)), str(row, "name"),
                        str(row, Cfg.s(275))).year(yearOfRelease(str(row, Cfg.s(361)))));
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
                        for (JsonObject p : J.list(J.obj(detail, "data"), Cfg.s(354))) {
                            String u = embed(str(p, Cfg.s(380)));
                            if (u.isEmpty()) continue;
                            String teamName = first(str(J.obj(p, Cfg.s(394)), "name"), str(J.obj(p, "team"), "title"));
                            String typeName = first(str(J.obj(p, Cfg.s(405)), Cfg.s(314)),
                                    str(J.obj(p, "translation_type"), "title"), Cfg.s(437));
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
                String body = Cfg.s(266)
                        + Net.enc(term);
                String html = Net.postForm(base + Cfg.s(121), body, Net.baseHeaders(base, base + "/"));
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
        Matcher m = Pattern.compile(Cfg.s(264), Pattern.CASE_INSENSITIVE)
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
            if (n < 1 || !vod.contains(Cfg.s(124))) continue;
            push(map, (int) Math.round(n), variant(Cfg.s(139), "animedia", vod));
        }
        return done("animedia", map);
    }

    /* ============ 7. Animetka ============ */

    static SourceResult animetka(Lookup l) throws Exception {
        TreeMap<Integer, EpisodeRow> map = map();
        Map<String, String> headers = Net.baseHeaders(Cfg.s(13), Cfg.s(14));
        headers.put("Accept", Cfg.s(238));
        JsonObject material = null;

        for (String term : searchTerms(l.title, l.original)) {
            try {
                Map<String, String> p = new LinkedHashMap<>();
                p.put(Cfg.s(358), term);
                p.put("q", term);
                p.put("limit", "12");
                JsonObject data = Net.getJson(Net.query(Cfg.s(17), p), headers);
                List<JsonObject> rows = J.list(data, "data");
                if (rows.isEmpty()) rows = J.list(data, Cfg.s(366));
                if (rows.isEmpty()) rows = J.list(data, Cfg.s(307));
                JsonObject hit = choose(rows, l, row -> new Match.Candidate(str(row, "title"), str(row, "name"),
                        str(row, Cfg.s(398)), str(row, Cfg.s(347))).year(num(row, "year")));
                if (hit != null) {
                    material = hit;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (material == null) return done("animetka", map);

        String id = first(str(material, Cfg.s(226)), str(material, "id"));
        JsonObject detail = new JsonObject();
        try {
            detail = Net.getJson(Cfg.s(15) + Net.enc(id), headers);
        } catch (Exception ignored) {
        }

        List<JsonObject> translations = J.list(detail, Cfg.s(406));
        if (translations.isEmpty()) translations = J.list(detail, Cfg.s(428));
        if (translations.isEmpty()) translations = J.list(detail, Cfg.s(273));

        int total = (int) Math.round(J.num(detail, Cfg.s(279)));
        if (total == 0) total = num(material, "episodes");
        total = Math.max(1, Math.min(200, total == 0 ? 12 : total));

        int materialId = (int) Math.round(J.num(detail, Cfg.s(322)));
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
            if (tid == 0) tid = num(t, Cfg.s(404));
            if (tid == 0) continue;
            String raw = first(str(t, "title"), str(t, "name"));
            String voice = voiceTitle(raw);
            if (voice.isEmpty()) voice = cleanLabel(raw);
            if (voice.isEmpty()) voice = "Озвучка";
            for (int ep = 1; ep <= total; ep++) {
                Map<String, String> p = new LinkedHashMap<>();
                p.put(Cfg.s(321), String.valueOf(materialId));
                p.put(Cfg.s(403), String.valueOf(tid));
                p.put(Cfg.s(277), String.valueOf(ep));
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
                String body = Cfg.s(267)
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
        String embedUrl = SourceUtil.find(html, Cfg.s(298))
                .replace(Cfg.s(95), "&").replaceAll(Cfg.s(110), "");
        if (embedUrl.isEmpty()) return done("anidub", map);

        String origin = SourceUtil.originOf(embedUrl);
        String playlist;
        try {
            playlist = Net.get(embedUrl + Cfg.s(102), Net.baseHeaders(origin, base + "/"));
        } catch (Exception e) {
            playlist = "";
        }
        Matcher spans = Pattern.compile(Cfg.s(127),
                Pattern.CASE_INSENSITIVE).matcher(playlist);
        int i = 0;
        boolean found = false;
        while (spans.find()) {
            found = true;
            i++;
            String hash = spans.group(1).replaceAll("^/+", "");
            String label = plain(spans.group(2));
            int n = numberIn(label, i);
            push(map, n, variant(Cfg.s(133), "anidub", origin + Cfg.s(123) + hash), label, 0);
        }
        if (!found) {
            String single = SourceUtil.find(embedUrl, Cfg.s(170));
            if (!single.isEmpty()) push(map, 1, variant("AniDUB", "anidub", origin + "/vid.php?v=/" + single));
        }
        return done("anidub", map);
    }

    /* ============ 9. AnixSekai ============ */

    private static final String[] ANIX_BASES = {Cfg.s(18), Cfg.s(22)};

    private static Map<String, String> anixHeaders(String base) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put(Cfg.s(160), ANIX_UA);
        h.put(Cfg.s(153), base);
        h.put(Cfg.s(156), base + "/");
        h.put(Cfg.s(130), Cfg.s(367));
        return h;
    }

    private static JsonObject anixGet(String path) throws Exception {
        Exception last = null;
        for (String base : ANIX_BASES) {
            try {
                JsonObject root = Net.parse(Net.get(base + path, anixHeaders(base)));
                if (J.has(root, Cfg.s(252)) && J.intOf(root, "code") != 0) throw new Exception(Cfg.s(234));
                return root;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new Exception(Cfg.s(233)) : last;
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
                JsonArray nested = firstArray(v.getAsJsonObject(), Cfg.s(363), Cfg.s(255), "data", Cfg.s(319), "items",
                        Cfg.s(409), Cfg.s(379), "episodes");
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
                JsonObject root = anixPost(Cfg.s(122),
                        Cfg.s(435) + escapeJson(term) + Cfg.s(88));
                List<JsonObject> rows = J.list(firstArray(root, "releases", "content", "data", "list", "items"));
                JsonObject hit = choose(rows, l, row -> new Match.Candidate(str(row, Cfg.s(400)), str(row, "title"),
                        str(row, Cfg.s(399)), str(row, Cfg.s(397))).year(num(row, "year")));
                if (hit != null) {
                    release = hit;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (release == null) return done("anixsekai", map);
        String id = first(str(release, "id"), str(release, Cfg.s(362)));
        if (id.isEmpty()) return done("anixsekai", map);

        List<JsonObject> types = J.list(firstArray(anixGetSafe(Cfg.s(118) + Net.enc(id)), "types", "data", "items"));
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
                        || (str(s, "name") + str(s, "title")).toLowerCase().contains(Cfg.s(310));
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
                String u = embed(first(str(e, "url"), str(e, Cfg.s(317)), str(e, "iframe_url")));
                if (u.isEmpty()) continue;
                int n = J.firstNum(e, Cfg.s(355), "episode", "number");
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
        String body = Cfg.s(436) + escapeJson(l.title) + Cfg.s(89)
                + Cfg.s(91);
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
            String hitsRaw = J.str(parsed, Cfg.s(290));
            if (!hitsRaw.isEmpty()) {
                hits = J.list(parseArray(hitsRaw));
            }
        } catch (Exception e) {
            return done("hanime", map);
        }
        JsonObject best = choose(hits, l, row -> new Match.Candidate(str(row, "name"), str(row, Cfg.s(401))));
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
        for (JsonObject s : J.list(J.obj(detail, Cfg.s(424)), Cfg.s(372))) {
            for (JsonObject st : J.list(s, Cfg.s(387))) {
                String u = safeUrl(str(st, "url"));
                if (u.isEmpty()) continue;
                int height = J.firstNum(st, Cfg.s(288), Cfg.s(430));
                streams.put(height > 0 ? height : 720, u);
            }
        }
        if (!streams.isEmpty()) push(map, 1, variant("Оригинал", "hanime", streams));
        return done("hanime", map);
    }
}
