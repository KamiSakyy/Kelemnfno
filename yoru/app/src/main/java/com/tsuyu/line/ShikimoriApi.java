package com.tsuyu.line;

import android.net.Uri;
import org.json.*;
import java.io.IOException;
import java.util.*;

public final class ShikimoriApi {
    public static final String[] SHIKI_GRAPH = {
            "https://shikimori.one/api/graphql",
            "https://shikimori.io/api/graphql",
            "https://shikimori.me/api/graphql"
    };
    public static final String[] SHIKI_REST = {
            "https://shikimori.one",
            "https://shikimori.io",
            "https://shikimori.me"
    };
    public static final String SH_FIELDS = "id malId name russian english kind rating score status episodes episodesAired nextEpisodeAt airedOn{year date} poster{mainUrl originalUrl} genres{id russian name}";
    private static final long DAY_MS = 24L * 60 * 60 * 1000L;

    private ShikimoriApi() {}

    public static JSONObject shiki(String q, ApiRepository repo) throws Exception {
        Exception last = null;
        for (String url : SHIKI_GRAPH) {
            try {
                JSONObject j = new JSONObject(repo.request(url, "POST", new JSONObject().put("query", q).toString(), false));
                if (j.has("errors")) throw new IOException("Shikimori не ответил");
                return j.getJSONObject("data");
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new IOException("Shikimori не ответил");
    }

    public static String shikiRest(String path, ApiRepository repo) throws Exception {
        Exception last = null;
        for (String base : SHIKI_REST) {
            try {
                return repo.request(base + path, "GET", null, false);
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new IOException("Shikimori не ответил");
    }

    public static String shikiText(String path, ApiRepository repo) throws Exception {
        String first = null;
        try { first = shikiRest(path, repo); } catch (Exception ignored) {}
        if (first != null && first.trim().startsWith("[")) return first;
        String alt = path.endsWith(".json") ? path : path + ".json";
        String second = null;
        try { second = shikiRest(alt, repo); } catch (Exception ignored) {}
        if (second != null && second.trim().startsWith("[")) return second;
        if (second != null && !second.trim().isEmpty()) return second;
        return first;
    }

    public static JSONArray shikiArray(String path, ApiRepository repo) throws Exception {
        String text = shikiText(path, repo);
        if (text == null || text.trim().isEmpty()) return new JSONArray("[]");
        String t = text.trim();
        if (t.startsWith("[")) return new JSONArray(t);
        if (t.startsWith("{")) {
            JSONObject o = new JSONObject(t);
            String[] keys = {"screenshots", "videos", "episodes", "data", "items"};
            for (String key : keys) {
                JSONArray arr = o.optJSONArray(key);
                if (arr != null) return arr;
            }
        }
        return new JSONArray("[]");
    }

    public static Anime shikiAnime(JSONObject j) {
        Anime a = new Anime();
        a.source = "shikimori";
        a.id = j.optString("id");
        a.malId = j.optInt("malId", 0);
        if (a.malId == 0) try { a.malId = Integer.parseInt(a.id); } catch (Exception ignored) {}
        a.title = j.optString("russian", "");
        if (a.title.isEmpty()) a.title = j.optString("name", "Аниме");
        a.original = j.optString("name", "");
        a.type = ApiRepository.kind(j.optString("kind"));
        a.status = j.optString("status");
        a.episodes = j.optInt("episodes");
        a.episodesAired = j.optInt("episodesAired", 0);
        a.nextEpisodeAt = j.optString("nextEpisodeAt", "");
        a.score = j.optDouble("score", 0);
        JSONObject date = j.optJSONObject("airedOn"), poster = j.optJSONObject("poster");
        a.year = date == null ? 0 : date.optInt("year");
        a.airedDate = date == null ? "" : date.optString("date", "");
        a.poster = poster == null ? "" : ApiRepository.safeUrl(poster.optString("mainUrl", poster.optString("originalUrl")));
        a.age = j.optString("rating").equals("pg_13") ? "13+" : j.optString("rating").startsWith("r") ? "18+" : "";
        JSONArray studios = j.optJSONArray("studios");
        if (studios != null && studios.length() > 0) {
            a.studio = studios.optJSONObject(0) == null ? "" : studios.optJSONObject(0).optString("name", "");
        }
        JSONArray genres = j.optJSONArray("genres");
        for (int i = 0; genres != null && i < genres.length(); i++) {
            Object g = genres.opt(i);
            if (g instanceof JSONObject) {
                String val = ((JSONObject) g).optString("russian", ((JSONObject) g).optString("name", ""));
                if (!val.isEmpty() && !a.genres.contains(val)) a.genres.add(val);
            }
        }
        return a;
    }

    public static String shikiImage(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("//")) s = "https:" + s;
        if (s.startsWith("/")) s = "https://shikimori.io" + s;
        return ApiRepository.safeUrl(s);
    }

    public static Anime shikiQuick(int id, String source, ApiRepository repo) throws Exception {
        JSONArray rows = shiki("{animes(ids:" + JSONObject.quote(String.valueOf(id)) + ",limit:1){" + SH_FIELDS + " descriptionHtml}}", repo).optJSONArray("animes");
        if (rows == null || rows.length() == 0) throw new IOException("Аниме не найдено");
        JSONObject j = rows.getJSONObject(0);
        Anime a = shikiAnime(j);
        a.source = source == null || source.isEmpty() ? "shikimori" : source;
        if (a.malId <= 0) a.malId = id;
        if ("yoru".equals(a.source)) a.id = String.valueOf(id);
        a.description = ApiRepository.plain(j.optString("descriptionHtml"));
        YoruCache db = YoruApp.app() == null ? null : YoruApp.app().cache;
        if (db != null) db.detail(a);
        return repo.remember(a);
    }

    public static ArrayList<String> screenshotsOf(Anime a, ApiRepository repo) throws Exception {
        ArrayList<String> out = new ArrayList<>();
        if (a == null) return out;
        int mal = a.malId > 0 ? a.malId : ("shikimori".equals(a.source) ? CalendarApi.parseInt(a.id) : 0);
        if (mal <= 0) return out;
        YoruCache db = YoruApp.app() == null ? null : YoruApp.app().cache;
        if (db != null) {
            JSONArray cached = db.animeShots(mal, 21L * DAY_MS);
            for (int i = 0; i < cached.length() && out.size() < 12; i++) {
                String u = ApiRepository.safeUrl(cached.optString(i, ""));
                if (!u.isEmpty() && !out.contains(u)) out.add(u);
            }
            if (!out.isEmpty()) return out;
        }
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (attempt > 0) Thread.sleep(1200);
                JSONArray rows = shikiArray("/api/animes/" + mal + "/screenshots", repo);
                for (int i = 0; i < rows.length() && out.size() < 12; i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    String u = shikiImage(row.optString("original", row.optString("preview", "")));
                    if (!u.isEmpty() && !out.contains(u)) out.add(u);
                }
                if (!out.isEmpty()) {
                    if (db != null) {
                        JSONArray store = new JSONArray();
                        for (String u : out) store.put(u);
                        db.animeShots(mal, store);
                    }
                    return out;
                }
                last = null;
            } catch (Exception e) {
                last = e;
            }
        }
        if (last == null) return out;
        throw last;
    }

    public static void enrichSchedule(Anime a, ApiRepository repo) {
        if (a == null) return;
        int mal = a.malId > 0 ? a.malId : ("shikimori".equals(a.source) ? CalendarApi.parseInt(a.id) : 0);
        if (mal <= 0) return;
        try {
            JSONArray rows = shiki("{animes(ids:" + JSONObject.quote(String.valueOf(mal)) + ",limit:1){id status episodes episodesAired nextEpisodeAt airedOn{year date}}}", repo).optJSONArray("animes");
            JSONObject j = rows != null && rows.length() > 0 ? rows.optJSONObject(0) : null;
            if (j == null) return;
            int total = j.optInt("episodes", 0), aired = j.optInt("episodesAired", 0);
            if (total > a.episodes) a.episodes = total;
            if (aired > a.episodesAired) a.episodesAired = aired;
            String next = j.optString("nextEpisodeAt", "");
            if (!next.isEmpty() && !next.equals("null")) a.nextEpisodeAt = next;
            String st = j.optString("status", "");
            if (!st.isEmpty()) a.status = st;
        } catch (Exception ignored) {}
    }

    public static void enrichVisuals(Anime a, ApiRepository repo) {
        if (a == null) return;
        int mal = a.malId > 0 ? a.malId : ("shikimori".equals(a.source) ? CalendarApi.parseInt(a.id) : 0);
        if (mal <= 0) return;
        YoruCache db = YoruApp.app() == null ? null : YoruApp.app().cache;
        if (db != null && a.screenshots.isEmpty()) {
            JSONArray cached = db.animeShots(mal, 21L * DAY_MS);
            for (int i = 0; i < cached.length() && a.screenshots.size() < 12; i++) {
                String u = ApiRepository.safeUrl(cached.optString(i, ""));
                if (u.length() > 0) a.screenshots.add(u);
            }
        }
    }

    public static void enrichRelated(Anime a, ApiRepository repo) {
        if (a == null) return;
        LinkedHashMap<String, Anime> map = new LinkedHashMap<>();
        for (Anime row : a.related) {
            if (Anime.valid(row) && !row.key().equals(a.key())) map.put(SourceEngine.identity(row), row);
        }
        int mal = a.malId > 0 ? a.malId : ("shikimori".equals(a.source) ? CalendarApi.parseInt(a.id) : 0);
        if (mal <= 0) {
            try {
                for (String term : repo.searchTerms(a)) {
                    Anime.Page page = repo.catalog("shikimori", term, 1, new ApiRepository.Filter());
                    for (Anime item : page.items) {
                        if (repo.matchesAnime(item, a)) {
                            mal = item.malId > 0 ? item.malId : CalendarApi.parseInt(item.id);
                            break;
                        }
                    }
                    if (mal > 0) break;
                }
            } catch (Exception ignored) {}
        }
        YoruCache relatedDb = YoruApp.app() == null ? null : YoruApp.app().cache;
        if (mal > 0 && relatedDb != null) {
            JSONArray cached = relatedDb.franchise(mal, 3 * DAY_MS);
            for (int i = 0; i < cached.length(); i++) {
                Anime item = Anime.from(cached.optJSONObject(i));
                if (Anime.valid(item) && !item.key().equals(a.key())) map.put(SourceEngine.identity(item), item);
            }
        }
        if (mal > 0) {
            try {
                JSONArray rows = shiki("{animes(ids:" + JSONObject.quote(String.valueOf(mal)) + ",limit:1){related{relationKind anime{" + SH_FIELDS + "}}}}", repo).optJSONArray("animes");
                JSONObject first = rows != null && rows.length() > 0 ? rows.optJSONObject(0) : null;
                JSONArray rel = first == null ? null : first.optJSONArray("related");
                for (int i = 0; rel != null && i < rel.length(); i++) {
                    JSONObject r = rel.getJSONObject(i).optJSONObject("anime");
                    if (r == null) continue;
                    Anime item = repo.remember(shikiAnime(r));
                    if (Anime.valid(item) && !item.key().equals(a.key())) map.put(SourceEngine.identity(item), item);
                }
            } catch (Exception ignored) {}
        }
        if (mal > 0) {
            try {
                JSONObject data = AniListApi.query("{Media(mal_id:" + mal + "){relations{edges{relation node{id idMal title{romaji english native} bannerImage coverImage{extraLarge large medium} format status episodes duration genres averageScore startDate{year month day} season description(asHtml:false)}}}}}", repo);
                JSONObject media = data.optJSONObject("Media");
                JSONObject rels = media == null ? null : media.optJSONObject("relations");
                JSONArray edges = rels == null ? null : rels.optJSONArray("edges");
                for (int i = 0; edges != null && i < edges.length(); i++) {
                    JSONObject e = edges.optJSONObject(i);
                    if (e == null) continue;
                    Anime item = AniListApi.anilistAnime(e.optJSONObject("node"));
                    if (item == null || !Anime.valid(item) || item.key().equals(a.key())) continue;
                    boolean dup = false;
                    for (Anime h : map.values()) {
                        if (h.malId > 0 && item.malId > 0 && h.malId == item.malId) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) map.put(SourceEngine.identity(item), item);
                }
            } catch (Exception ignored) {}
        }
        if (mal > 0) {
            try {
                ArrayList<Integer> ids = franchiseIds(mal, map, a, repo);
                for (int from = 0; from < ids.size(); from += 45) {
                    List<Integer> part = ids.subList(from, Math.min(ids.size(), from + 45));
                    StringBuilder joined = new StringBuilder();
                    for (Integer id : part) {
                        if (id == null || id <= 0) continue;
                        if (joined.length() > 0) joined.append(',');
                        joined.append(id);
                    }
                    if (joined.length() == 0) continue;
                    JSONArray rows = shiki("{animes(ids:" + JSONObject.quote(joined.toString()) + ",limit:" + part.size() + "){" + SH_FIELDS + "}}", repo).optJSONArray("animes");
                    for (int i = 0; rows != null && i < rows.length(); i++) {
                        Anime item = repo.remember(shikiAnime(rows.getJSONObject(i)));
                        if (Anime.valid(item) && !item.key().equals(a.key())) map.put(SourceEngine.identity(item), item);
                    }
                }
            } catch (Exception ignored) {}
        }
        ArrayList<Anime> rows = new ArrayList<>(map.values());
        rows.sort((x, y) -> {
            int p = releaseOrder(x) - releaseOrder(y);
            if (p != 0) return p;
            p = franchiseKindRank(x) - franchiseKindRank(y);
            if (p != 0) return p;
            p = seasonNumber(x) - seasonNumber(y);
            if (p != 0) return p;
            return YoruBrain.title(x).compareToIgnoreCase(YoruBrain.title(y));
        });
        a.related.clear();
        JSONArray relCache = new JSONArray();
        for (Anime row : rows) {
            if (a.related.size() < 120) {
                a.related.add(row);
                relCache.put(row.json());
            }
        }
        if (mal > 0 && relatedDb != null) relatedDb.franchise(mal, relCache);
    }

    public static ArrayList<Integer> franchiseIds(int mal, LinkedHashMap<String, Anime> map, Anime self, ApiRepository repo) {
        ArrayList<Integer> ids = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(shikiRest("/api/animes/" + mal + "/franchise", repo));
            JSONArray nodes = root.optJSONArray("nodes");
            for (int i = 0; nodes != null && i < nodes.length(); i++) {
                JSONObject n = nodes.optJSONObject(i);
                if (n == null) continue;
                int id = n.optInt("id", 0);
                if (id <= 0) continue;
                Anime item = new Anime();
                item.source = "shikimori";
                item.id = String.valueOf(id);
                item.malId = id;
                item.title = n.optString("name", n.optString("russian", "Аниме"));
                item.original = n.optString("name", "");
                item.year = n.optInt("year", n.optInt("date", 0));
                item.type = ApiRepository.kind(n.optString("kind", ""));
                item.poster = shikiImage(n.optString("image_url", n.optString("poster", "")));
                if (Anime.valid(item) && !item.key().equals(self.key())) map.put(SourceEngine.identity(item), repo.remember(item));
                if (id != mal && !ids.contains(id)) ids.add(id);
            }
        } catch (Exception ignored) {}
        return ids;
    }

    private static int franchiseKindRank(Anime a) {
        String t = (a == null ? "" : a.type).toLowerCase(Locale.ROOT);
        if (t.contains("тв") || t.contains("tv")) return 10;
        if (t.contains("ona")) return 30;
        if (t.contains("фильм") || t.contains("movie")) return 50;
        if (t.contains("ova")) return 60;
        if (t.contains("спеш") || t.contains("special")) return 70;
        return 40;
    }

    private static int seasonNumber(Anime a) {
        String t = ((a == null ? "" : a.title) + " " + (a == null ? "" : a.original)).toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:season|сезон)\\s*(\\d+)|(\\d+)\\s*(?:st|nd|rd|th|й|я)?\\s*сезон").matcher(t);
        if (m.find()) {
            String n = m.group(1) != null ? m.group(1) : m.group(2);
            try { return Integer.parseInt(n); } catch (Exception ignored) {}
        }
        if (t.contains("ii")) return 2;
        if (t.contains("iii")) return 3;
        if (t.contains("iv")) return 4;
        return 1;
    }

    private static int releaseOrder(Anime a) {
        if (a == null) return 99999999;
        String d = a.airedDate == null ? "" : a.airedDate.replaceAll("[^0-9]", "");
        if (d.length() >= 8) try { return Integer.parseInt(d.substring(0, 8)); } catch (Exception ignored) {}
        return a.year > 0 ? a.year * 10000 + 101 : 99999999;
    }
}
