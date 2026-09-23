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
        ALL.add(simple(Cfg.s(434), false, Sources::primary));
        ALL.add(simple(Cfg.s(210), false, Sources::anilibria));
        ALL.add(simple(Cfg.s(235), false, Sources::anix));
        ALL.add(simple(Cfg.s(227), false, Sources::animevost));
        ALL.add(simple(Cfg.s(221), false, l -> animelibEpisodes(l, false)));
        ALL.add(simple(Cfg.s(222), false, l -> animelibEpisodes(l, true)));
        ALL.add(simple(Cfg.s(220), false, Sources::animedia));
        ALL.add(simple(Cfg.s(224), false, Sources::animetka));
        ALL.add(simple(Cfg.s(207), false, Sources::anidub));
        ALL.add(simple(Cfg.s(286), true, Sources::hanime));
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

    static SourceResult primary(Lookup l) throws Exception {
        TreeMap<Integer, EpisodeRow> map = map();
        String id = l.sourceId > 0 ? String.valueOf(l.sourceId) : "";
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
                JsonObject found = Net.getJson(Cfg.apiBase() + Cfg.s(217) + Net.enc(l.title) + Cfg.s(98), json);
                JsonObject best = choose(J.list(found, Cfg.s(365)), l, row -> {
                    Match.Candidate c = new Match.Candidate(str(row, Cfg.s(395)));
                    for (JsonElement t : J.arr(row, Cfg.s(348))) c.titles.add(J.str(t));
                    return c.year(num(row, Cfg.s(432)));
                });
                if (best != null) id = str(best, Cfg.s(219));
            } catch (Exception ignored) {
            }
        }
        if (id.isEmpty()) return done(Cfg.s(434), map);

        JsonObject detail = Net.getJson(Cfg.apiBase() + Cfg.s(216) + Net.enc(id) + Cfg.s(128), json);
        for (JsonObject v : J.list(J.obj(detail, Cfg.s(365)), Cfg.s(423))) {
            String iframe = embed(str(v, Cfg.s(303)));
            if (iframe.isEmpty()) continue;
            JsonObject data = J.obj(v, Cfg.s(262));
            String voice = voiceTitle(str(data, Cfg.s(271)));
            if (voice.isEmpty()) voice = Cfg.s(438);
            String name = str(v, Cfg.s(341)).isEmpty() ? null : Cfg.s(439) + str(v, Cfg.s(341));
            push(map, numberIn(str(v, Cfg.s(341)), 0), variant(voice, Cfg.s(434), iframe), name, J.num(v, Cfg.s(274)));
        }
        return done(Cfg.s(434), map);
    }

    /* ============ 2. AniLibria (anilibria.top) ============ */

    static SourceResult anilibria(Lookup l) throws Exception {
        TreeMap<Integer, EpisodeRow> map = map();
        Map<String, String> h = Net.baseHeaders(null, null);
        JsonObject release = null;

        if (l.extAlias != null && !l.extAlias.isEmpty()) {
            try {
                JsonObject byAlias = Net.getJson(
                        Cfg.s(11) + Net.enc(l.extAlias), h);
                JsonObject data = J.obj(byAlias, Cfg.s(262));
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
                    JsonObject hit = choose(J.list(data, Cfg.s(262)), l, row -> {
                        JsonObject name = J.obj(row, Cfg.s(337));
                        return new Match.Candidate(str(name, Cfg.s(320)), str(name, Cfg.s(276)), str(name, Cfg.s(198)))
                                .year(num(row, Cfg.s(432)));
                    });
                    if (hit != null) {
                        release = hit;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (release == null) return done(Cfg.s(210), map);

        String alias = str(release, Cfg.s(193));
        if (alias.isEmpty()) alias = str(release, "id");
        JsonObject full = Net.getJson(Cfg.s(11) + Net.enc(alias), h);
        for (JsonObject e : J.list(full, Cfg.s(278))) {
            Map<Integer, String> streams = new LinkedHashMap<>();
            int[][] pairs = {{480, 0}, {720, 1}, {1080, 2}, {1440, 3}, {2160, 4}};
            String[] keys = {Cfg.s(294), Cfg.s(295), Cfg.s(293), Cfg.s(502), Cfg.s(503)};
            for (int i = 0; i < pairs.length; i++) {
                String safe = safeUrl(str(e, keys[i]));
                if (!safe.isEmpty()) streams.put(pairs[i][0], safe);
            }
            if (streams.isEmpty()) continue;
            push(map, num(e, Cfg.s(346)), variant(Cfg.s(81), Cfg.s(210), streams), str(e, Cfg.s(337)), J.num(e, Cfg.s(274)));
        }
        return done(Cfg.s(210), map);
    }

    /* ============ 3. AnimeVost ============ */

    static SourceResult animevost(Lookup l) throws Exception {
        TreeMap<Integer, EpisodeRow> map = map();
        String id = "";
        for (String term : searchTerms(l.title, l.original)) {
            try {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put(Cfg.s(337), term);
                JsonObject data = Net.postFormJson(Cfg.s(21), fields, Net.baseHeaders(null, null));
                JsonObject hit = choose(J.list(data, Cfg.s(262)), l, row -> {
                    List<String> titles = new ArrayList<>();
                    titles.add(str(row, Cfg.s(395)));
                    titles.addAll(Arrays.asList(str(row, Cfg.s(395)).split("/")));
                    Match.Candidate c = new Match.Candidate();
                    c.titles = titles;
                    return c.year(num(row, Cfg.s(432)));
                });
                if (hit != null) {
                    id = str(hit, "id");
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (id.isEmpty()) return done(Cfg.s(227), map);

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
            push(map, numberIn(str(e, Cfg.s(337)), i), variant(Cfg.s(143), Cfg.s(227), streams), str(e, Cfg.s(337)), 0);
        }
        return done(Cfg.s(227), map);
    }

    /* ============ 4/5. AniLib + AniLib Ultra (api.cdnlibs.org) ============ */

    static SourceResult animelibEpisodes(final Lookup l, final boolean ultra) throws Exception {
        final String source = ultra ? Cfg.s(222) : Cfg.s(221);
        final TreeMap<Integer, EpisodeRow> map = map();
        String slug = "";
        Map<String, String> h = Net.baseHeaders(null, null);
        h.put(Cfg.s(129), Cfg.s(236));
        h.put("Origin", "https://animelib.org");
        h.put("Referer", "https://animelib.org/");

        for (String term : searchTerms(l.title, l.original)) {
            try {
                Map<String, String> p = new LinkedHashMap<>();
                p.put(Cfg.s(350), "1");
                p.put("q", term);
                JsonObject data = Net.getJson(Net.query(Cfg.s(23), p), h);
                List<JsonObject> rows = J.list(data, Cfg.s(262));
                if (l.shikimoriId > 0) {
                    for (JsonObject r : rows) {
                        if (shikiFromHref(str(r, Cfg.s(373))) == l.shikimoriId) {
                            slug = first(str(r, Cfg.s(378)), str(r, Cfg.s(377)), str(r, "id"));
                            break;
                        }
                    }
                    if (!slug.isEmpty()) break;
                }
                JsonObject hit = choose(rows, l, row -> new Match.Candidate(str(row, Cfg.s(368)), str(row, Cfg.s(337)),
                        str(row, Cfg.s(275))).year(yearOfRelease(str(row, Cfg.s(361)))));
                if (hit != null) {
                    slug = first(str(hit, Cfg.s(378)), str(hit, Cfg.s(377)), str(hit, "id"));
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (slug.isEmpty()) return done(source, map);

        List<JsonObject> rows;
        try {
            JsonObject eps = Net.getJson(Cfg.s(25) + Net.enc(slug), h);
            rows = J.list(eps, Cfg.s(262));
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
                    int n = (int) Math.round(J.num(e, Cfg.s(341)));
                    if (n < 0) return null;
                    EpisodeRow row = new EpisodeRow();
                    row.number = n;
                    row.name = str(e, Cfg.s(337));
                    try {
                        Map<String, String> hh = Net.baseHeaders(null, null);
                        hh.put(Cfg.s(129), Cfg.s(236));
                        hh.put("Origin", "https://animelib.org");
                        hh.put("Referer", "https://animelib.org/");
                        JsonObject detail = Net.getJson(
                                Cfg.s(24) + Net.enc(str(e, "id")), hh);
                        for (JsonObject p : J.list(J.obj(detail, Cfg.s(262)), Cfg.s(354))) {
                            String u = embed(str(p, Cfg.s(380)));
                            if (u.isEmpty()) continue;
                            String teamName = first(str(J.obj(p, Cfg.s(394)), Cfg.s(337)), str(J.obj(p, Cfg.s(394)), Cfg.s(395)));
                            String typeName = first(str(J.obj(p, Cfg.s(405)), Cfg.s(314)),
                                    str(J.obj(p, Cfg.s(405)), Cfg.s(395)), Cfg.s(437));
                            String label = cleanLabel((typeName + " " + teamName).trim());
                            String voice = voiceTitle(teamName);
                            if (voice.isEmpty()) voice = voiceTitle(label);
                            if (voice.isEmpty()) voice = teamName.isEmpty() ? Cfg.s(437) : cleanLabel(teamName);
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
                        row -> new Match.Candidate(str(row, Cfg.s(395))));
                if (hit != null) {
                    page = str(hit, Cfg.s(411));
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (page.isEmpty()) return done(Cfg.s(220), map);

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
            push(map, (int) Math.round(n), variant(Cfg.s(139), Cfg.s(220), vod));
        }
        return done(Cfg.s(220), map);
    }

    /* ============ 7. Animetka ============ */

    static SourceResult animetka(Lookup l) throws Exception {
        TreeMap<Integer, EpisodeRow> map = map();
        Map<String, String> headers = Net.baseHeaders(Cfg.s(13), Cfg.s(14));
        headers.put(Cfg.s(129), Cfg.s(238));
        JsonObject material = null;

        for (String term : searchTerms(l.title, l.original)) {
            try {
                Map<String, String> p = new LinkedHashMap<>();
                p.put(Cfg.s(358), term);
                p.put("q", term);
                p.put(Cfg.s(316), "12");
                JsonObject data = Net.getJson(Net.query(Cfg.s(17), p), headers);
                List<JsonObject> rows = J.list(data, Cfg.s(262));
                if (rows.isEmpty()) rows = J.list(data, Cfg.s(366));
                if (rows.isEmpty()) rows = J.list(data, Cfg.s(307));
                JsonObject hit = choose(rows, l, row -> new Match.Candidate(str(row, Cfg.s(395)), str(row, Cfg.s(337)),
                        str(row, Cfg.s(398)), str(row, Cfg.s(347))).year(num(row, Cfg.s(432))));
                if (hit != null) {
                    material = hit;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (material == null) return done(Cfg.s(224), map);

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
        if (total == 0) total = num(material, Cfg.s(278));
        total = Math.max(1, Math.min(200, total == 0 ? 12 : total));

        int materialId = (int) Math.round(J.num(detail, Cfg.s(322)));
        if (materialId == 0) materialId = num(material, Cfg.s(322));
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
            String raw = first(str(t, Cfg.s(395)), str(t, Cfg.s(337)));
            String voice = voiceTitle(raw);
            if (voice.isEmpty()) voice = cleanLabel(raw);
            if (voice.isEmpty()) voice = Cfg.s(437);
            for (int ep = 1; ep <= total; ep++) {
                Map<String, String> p = new LinkedHashMap<>();
                p.put(Cfg.s(321), String.valueOf(materialId));
                p.put(Cfg.s(403), String.valueOf(tid));
                p.put(Cfg.s(277), String.valueOf(ep));
                String url = Net.query(Cfg.s(16), p);
                push(map, ep, variant(voice, Cfg.s(224), url));
            }
        }
        return done(Cfg.s(224), map);
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
                String html = Net.postForm(base + Cfg.s(121), body, Net.baseHeaders(base, base + "/"));
                JsonObject hit = choose(searchLinks(html, base), l, row -> new Match.Candidate(str(row, Cfg.s(395))));
                if (hit != null) {
                    page = str(hit, Cfg.s(411));
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (page.isEmpty()) return done(Cfg.s(207), map);

        String html = Net.get(page, Net.baseHeaders(base, base + "/"));
        String embedUrl = SourceUtil.find(html, Cfg.s(298))
                .replace(Cfg.s(95), "&").replaceAll(Cfg.s(110), "");
        if (embedUrl.isEmpty()) return done(Cfg.s(207), map);

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
            push(map, n, variant(Cfg.s(133), Cfg.s(207), origin + Cfg.s(123) + hash), label, 0);
        }
        if (!found) {
            String single = SourceUtil.find(embedUrl, Cfg.s(170));
            if (!single.isEmpty()) push(map, 1, variant(Cfg.s(133), Cfg.s(207), origin + Cfg.s(123) + single));
        }
        return done(Cfg.s(207), map);
    }

    /* ============ 9. AnixSekai ============ */

    private static final String[] ANIX_BASES = {Cfg.s(18), Cfg.s(22)};

    private static Map<String, String> anixHeaders(String base) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put(Cfg.s(129), Cfg.s(236));
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
                if (J.has(root, Cfg.s(252)) && J.intOf(root, Cfg.s(252)) != 0) throw new Exception(Cfg.s(234));
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
                if (J.has(root, Cfg.s(252)) && J.intOf(root, Cfg.s(252)) != 0) throw new Exception(Cfg.s(234));
                return root;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new Exception(Cfg.s(233)) : last;
    }

    /** Ищет первый массив в ответе по списку ключей (структура API плавающая). */
    private static JsonArray firstArray(JsonObject root, String... keys) {
        if (root == null) return new JsonArray();
        for (String k : keys) {
            JsonElement v = root.get(k);
            if (v != null && v.isJsonArray()) return v.getAsJsonArray();
            if (v != null && v.isJsonObject()) {
                JsonArray nested = firstArray(v.getAsJsonObject(), Cfg.s(363), Cfg.s(255), Cfg.s(262), Cfg.s(319), Cfg.s(307),
                        Cfg.s(409), Cfg.s(379), Cfg.s(278));
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
                List<JsonObject> rows = J.list(firstArray(root, Cfg.s(363), Cfg.s(255), Cfg.s(262), Cfg.s(319), Cfg.s(307)));
                JsonObject hit = choose(rows, l, row -> new Match.Candidate(str(row, Cfg.s(400)), str(row, Cfg.s(395)),
                        str(row, Cfg.s(399)), str(row, Cfg.s(397))).year(num(row, Cfg.s(432))));
                if (hit != null) {
                    release = hit;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (release == null) return done(Cfg.s(235), map);
        String id = first(str(release, "id"), str(release, Cfg.s(362)));
        if (id.isEmpty()) return done(Cfg.s(235), map);

        List<JsonObject> types = J.list(firstArray(anixGetSafe(Cfg.s(118) + Net.enc(id)), Cfg.s(409), Cfg.s(262), Cfg.s(307)));
        List<JsonObject> filtered = new ArrayList<>();
        for (JsonObject t : types) {
            if (!voiceTitle(first(str(t, Cfg.s(337)), str(t, Cfg.s(395)))).isEmpty()) filtered.add(t);
        }
        filtered.sort((a, b) -> num(b, Cfg.s(279)) - num(a, Cfg.s(279)));
        if (filtered.size() > 8) filtered = filtered.subList(0, 8);

        for (JsonObject type : filtered) {
            String voice = voiceTitle(first(str(type, Cfg.s(337)), str(type, Cfg.s(395))));
            int typeId = num(type, "id");
            if (typeId == 0) continue;
            List<JsonObject> sources = J.list(firstArray(
                    anixGetSafe(Cfg.s(118) + Net.enc(id) + "/" + typeId), Cfg.s(379), Cfg.s(262), Cfg.s(307)));
            if (sources.isEmpty()) continue;
            JsonObject chosen = null;
            for (JsonObject s : sources) {
                boolean kodik = num(s, "id") == 12
                        || (str(s, Cfg.s(337)) + str(s, Cfg.s(395))).toLowerCase().contains(Cfg.s(310));
                if (!kodik) {
                    chosen = s;
                    break;
                }
            }
            if (chosen == null) chosen = sources.get(0);
            int sourceId = num(chosen, "id");
            if (sourceId == 0) continue;
            List<JsonObject> eps = J.list(firstArray(
                    anixGetSafe(Cfg.s(118) + Net.enc(id) + "/" + typeId + "/" + sourceId), Cfg.s(278), Cfg.s(262), Cfg.s(307)));
            int i = 0;
            for (JsonObject e : eps) {
                i++;
                String u = embed(first(str(e, Cfg.s(411)), str(e, Cfg.s(317)), str(e, Cfg.s(303))));
                if (u.isEmpty()) continue;
                int n = J.firstNum(e, Cfg.s(355), Cfg.s(277), Cfg.s(341));
                if (n <= 0) n = i;
                push(map, n, variant(voice, Cfg.s(235), u), first(str(e, Cfg.s(395)), str(e, Cfg.s(337))), J.num(e, Cfg.s(274)));
            }
        }
        return done(Cfg.s(235), map);
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
        h.put(Cfg.s(129), Cfg.s(236));
        String root = null;
        try {
            root = Net.postJson(Cfg.s(46), body, h);
        } catch (Exception ignored) {
        }
        List<JsonObject> hits = new ArrayList<>();
        if (root != null) {
            try {
                JsonObject parsed = Net.parse(root);
                String hitsRaw = J.str(parsed, Cfg.s(290));
                if (!hitsRaw.isEmpty()) {
                    hits = J.list(parseArray(hitsRaw));
                }
            } catch (Exception ignored) {
            }
        }
        String slug = "";
        if (!hits.isEmpty()) {
            JsonObject best = choose(hits, l, row -> new Match.Candidate(str(row, Cfg.s(337)), str(row, Cfg.s(401))));
            if (best != null) slug = str(best, Cfg.s(377));
        }
        java.util.List<String> slugs = new ArrayList<>();
        if (!slug.isEmpty()) slugs.add(slug);
        for (String name : new String[]{l.original, l.title}) {
            String base = name == null ? "" : name.toLowerCase(java.util.Locale.US)
                    .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
            if (base.isEmpty()) continue;
            String[] parts = base.split("-");
            slugs.add(base);
            if (parts.length > 3) slugs.add(parts[0] + "-" + parts[1] + "-" + parts[2]);
            if (parts.length > 2) slugs.add(parts[0] + "-" + parts[1]);
        }
        for (String cand : slugs) {
            JsonObject detail;
            try {
                detail = Net.getJson(Cfg.s(33) + Net.enc(cand), h);
            } catch (Exception e) {
                continue;
            }
            Map<Integer, String> streams = new LinkedHashMap<>();
            for (JsonObject srv : J.list(J.obj(detail, Cfg.s(424)), Cfg.s(372))) {
                for (JsonObject st : J.list(srv, Cfg.s(387))) {
                    String u = safeUrl(str(st, Cfg.s(411)));
                    if (u.isEmpty()) continue;
                    int height = J.firstNum(st, Cfg.s(288), Cfg.s(430));
                    streams.put(height > 0 ? height : 720, u);
                }
            }
            if (!streams.isEmpty()) {
                push(map, 1, variant(Cfg.s(438), Cfg.s(286), streams));
                return done(Cfg.s(286), map);
            }
        }
        return done(Cfg.s(286), map);
    }
}
