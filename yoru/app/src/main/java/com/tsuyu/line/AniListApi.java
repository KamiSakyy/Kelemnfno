package com.tsuyu.line;

import android.net.Uri;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public final class AniListApi {
    private static final String GRAPHQL_URL = "https://graphql.anilist.co";
    public static final String ANI_FIELDS = "id idMal title{romaji english native} bannerImage coverImage{extraLarge large medium} format status episodes duration genres averageScore startDate{year month day} season description(asHtml:false)";

    private static final ConcurrentHashMap<String, String> POSTER_FIX = new ConcurrentHashMap<>();
    private static final Set<String> POSTER_BUSY = ConcurrentHashMap.newKeySet();
    private static final ExecutorService POSTER_POOL = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "yoru-poster-fix");
        t.setDaemon(true);
        return t;
    });

    private AniListApi() {}

    public static JSONObject query(String q, ApiRepository repo) throws Exception {
        JSONObject body = new JSONObject().put("query", q);
        HashMap<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        return new JSONObject(repo.request(GRAPHQL_URL, "POST", body.toString(), false, h)).getJSONObject("data");
    }

    public static Anime anilistAnime(JSONObject j) {
        if (j == null) return null;
        Anime a = new Anime();
        a.source = "anilist";
        a.anilistId = j.optInt("id", 0);
        a.malId = j.optInt("idMal", j.optInt("malId", 0));
        int use = a.malId > 0 ? a.malId : a.anilistId;
        if (use <= 0) return null;
        a.id = String.valueOf(use);
        JSONObject t = j.optJSONObject("title");
        String ru = t == null ? "" : t.optString("russian", "");
        String en = t == null ? "" : t.optString("english", "");
        String romaji = t == null ? "" : t.optString("romaji", "");
        a.title = ru;
        if (a.title.isEmpty()) a.title = en;
        if (a.title.isEmpty()) a.title = romaji;
        if (a.title.isEmpty()) a.title = "Аниме";
        a.original = romaji;
        if (a.original.isEmpty()) a.original = en;
        a.alias = en;
        a.type = ApiRepository.kind(j.optString("format", ""));
        String st = j.optString("status", "");
        a.status = "FINISHED".equals(st) || "CANCELLED".equals(st) || "HIATUS".equals(st) ? "finished" : "NOT_YET_RELEASED".equals(st) ? "anons" : "ongoing";
        a.episodes = j.optInt("episodes", 0);
        a.episodesAired = 0;
        JSONObject d = j.optJSONObject("startDate");
        if (d != null && d.optInt("year", 0) > 0) {
            a.year = d.optInt("year");
            a.airedDate = String.format(Locale.ROOT, "%04d-%02d-%02d", a.year, d.optInt("month", 1), d.optInt("day", 1));
        }
        JSONObject c = j.optJSONObject("coverImage");
        a.poster = c == null ? "" : ApiRepository.safeUrl(c.optString("large", c.optString("medium", "")));
        a.score = j.optDouble("averageScore", 0) / 10.0;
        a.description = ApiRepository.plain(j.optString("description", ""));
        JSONArray genres = j.optJSONArray("genres");
        for (int i = 0; genres != null && i < genres.length(); i++) {
            String g = genres.optString(i, "");
            if (!g.isEmpty() && !a.genres.contains(g)) a.genres.add(g);
        }
        return a;
    }

    public static Anime.Page anilistCatalog(String search, int page, ApiRepository repo) throws Exception {
        Anime.Page out = new Anime.Page();
        out.page = page;
        String q = search == null ? "" : search.trim();
        if (q.isEmpty()) return out;
        String query = "query($q:String,$p:Int){Page(page:$p,perPage:24){pageInfo{hasNextPage} media(search:$q,type:ANIME){" + ANI_FIELDS + "}}}";
        JSONObject vars = new JSONObject().put("q", q).put("p", page);
        JSONObject root = query(new JSONObject().put("query", query).put("variables", vars).toString(), repo);
        JSONObject p = root.optJSONObject("Page");
        JSONArray rows = p == null ? null : p.optJSONArray("media");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            Anime a = anilistAnime(row);
            if (Anime.valid(a)) out.items.add(repo.remember(a));
        }
        out.more = p != null && p.optJSONObject("pageInfo") != null && p.optJSONObject("pageInfo").optBoolean("hasNextPage", false);
        return out;
    }

    public static HashMap<Long, String> episodeThumbnails(int malId, int anilistId, ApiRepository repo) {
        HashMap<Long, String> map = new HashMap<>();
        if (malId <= 0 && anilistId <= 0) return map;
        try {
            String q = malId > 0
                    ? "query($id:Int){Media(idMal:$id,type:ANIME){streamingEpisodes{title thumbnail}}}"
                    : "query($id:Int){Media(id:$id,type:ANIME){streamingEpisodes{title thumbnail}}}";
            JSONObject vars = new JSONObject().put("id", malId > 0 ? malId : anilistId);
            JSONObject root = query(new JSONObject().put("query", q).put("variables", vars).toString(), repo);
            JSONObject media = root == null ? null : root.optJSONObject("Media");
            JSONArray list = media == null ? null : media.optJSONArray("streamingEpisodes");
            if (list != null) {
                Pattern numPattern = Pattern.compile("(?:Episode|Ep\\.|Серия|#)?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
                for (int i = 0; i < list.length(); i++) {
                    JSONObject row = list.optJSONObject(i);
                    if (row == null) continue;
                    String thumb = ApiRepository.safeUrl(row.optString("thumbnail", ""));
                    if (thumb.isEmpty()) continue;
                    String title = row.optString("title", "");
                    Matcher m = numPattern.matcher(title);
                    long num = (m.find()) ? Long.parseLong(m.group(1)) : (i + 1);
                    if (num > 0 && !map.containsKey(num)) {
                        map.put(num, thumb);
                    }
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    public static void posterFix(Anime a, Runnable done) {
        if (a == null || a.malId <= 0) return;
        String key = "mal:" + a.malId;
        String hit = POSTER_FIX.get(key);
        if (hit != null) {
            if (!hit.isEmpty() && (a.poster == null || a.poster.isEmpty())) {
                a.poster = hit;
                if (done != null) try { done.run(); } catch (Exception ignored) {}
            }
            return;
        }
        if (!POSTER_BUSY.add(key)) return;
        POSTER_POOL.execute(() -> {
            String url = "";
            try { url = anilistCover(a.malId); } catch (Exception ignored) {}
            POSTER_BUSY.remove(key);
            POSTER_FIX.put(key, url);
            if (!url.isEmpty() && (a.poster == null || a.poster.isEmpty())) a.poster = url;
            if (done != null && !url.isEmpty()) postPosterDone(done);
        });
    }

    public static void posterFixReplace(Anime a, Runnable done) {
        if (a == null || a.malId <= 0) return;
        String key = "mal:" + a.malId;
        String hit = POSTER_FIX.get(key);
        if (hit != null) {
            if (!hit.isEmpty()) {
                a.poster = hit;
                if (done != null) try { done.run(); } catch (Exception ignored) {}
            }
            return;
        }
        if (!POSTER_BUSY.add(key)) return;
        POSTER_POOL.execute(() -> {
            String url = "";
            try { url = anilistCover(a.malId); } catch (Exception ignored) {}
            POSTER_BUSY.remove(key);
            POSTER_FIX.put(key, url);
            if (!url.isEmpty()) a.poster = url;
            if (done != null && !url.isEmpty()) postPosterDone(done);
        });
    }

    private static void postPosterDone(final Runnable done) {
        try {
            final YoruApp app = YoruApp.app();
            if (app != null && app.main != null) {
                app.main.post(() -> {
                    try { done.run(); } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }

    public static String anilistCover(int mal) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(GRAPHQL_URL);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            JSONObject vars = new JSONObject().put("id", mal);
            String payload = new JSONObject().put("query", "query($id:Int){Media(idMal:$id,type:ANIME){coverImage{extraLarge large}}}").put("variables", vars).toString();
            byte[] out = payload.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(out.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(out);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            if (in == null) return "";
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) {
                buf.write(b, 0, n);
                if (buf.size() > 65536) break;
            }
            JSONObject data = new JSONObject(buf.toString("UTF-8")).optJSONObject("data");
            JSONObject media = data == null ? null : data.optJSONObject("Media");
            JSONObject img = media == null ? null : media.optJSONObject("coverImage");
            String url = img == null ? "" : img.optString("extraLarge", img.optString("large", ""));
            return ApiRepository.safeUrl(url);
        } catch (Exception e) {
            return "";
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }
}
