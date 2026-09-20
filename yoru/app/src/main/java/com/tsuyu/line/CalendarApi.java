package com.tsuyu.line;

import android.net.Uri;
import org.json.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class CalendarApi {
    private static final long DAY_MS = 24L * 60 * 60 * 1000L;
    private static final ZoneId MSK_ZONE = ZoneId.of("Europe/Moscow");
    private static final String SH_FIELDS = ShikimoriApi.SH_FIELDS;

    private CalendarApi() {}

    public static ArrayList<ApiRepository.AiringItem> airingSchedule(int days, List<Anime> focus, ApiRepository repo) throws Exception {
        int period = Math.max(7, Math.min(28, days));
        long now = System.currentTimeMillis();
        long start = startOfDay(now);
        long end = start + period * DAY_MS;

        CalendarFeed.Result<String, ApiRepository.AiringItem> map = CalendarFeed.collect(
                rows -> {
                    Exception failure = null;
                    ExecutorService pool = Executors.newFixedThreadPool(6);
                    try {
                        ArrayList<Future<List<ApiRepository.AiringItem>>> tasks = new ArrayList<>();
                        for (int page = 1; page <= 8; page++) {
                            final int p = page;
                            tasks.add(pool.submit(() -> {
                                TaskQueue.check();
                                return fetchAiringPage(repo, "ongoing", "popularity", p, now, start, end, false);
                            }));
                        }
                        for (int page = 1; page <= 3; page++) {
                            final int p = page;
                            tasks.add(pool.submit(() -> {
                                TaskQueue.check();
                                return fetchAiringPage(repo, "anons", "aired_on", p, now, start, end, true);
                            }));
                        }
                        for (Future<List<ApiRepository.AiringItem>> task : tasks) {
                            try {
                                List<ApiRepository.AiringItem> list = task.get(9, TimeUnit.SECONDS);
                                if (list != null) {
                                    for (ApiRepository.AiringItem it : list) {
                                        putAiringItem(rows, it);
                                    }
                                }
                            } catch (Exception e) {
                                TaskQueue.check();
                                failure = e;
                            }
                        }
                    } finally {
                        pool.shutdownNow();
                    }
                    if (rows.isEmpty() && failure != null) throw failure;
                },
                rows -> {
                    Exception failure = null;
                    try { airingRest(rows, repo, "ongoing", now, start, end, false); } catch (Exception e) { TaskQueue.check(); failure = e; }
                    try { airingRest(rows, repo, "anons", now, start, end, true); } catch (Exception e) { TaskQueue.check(); failure = e; }
                    if (rows.isEmpty() && failure != null) throw failure;
                },
                rows -> appendFavoriteAirings(rows, repo, focus, now, start, end)
        );

        ArrayList<ApiRepository.AiringItem> list = new ArrayList<>(map.values());
        if (!map.generalAvailable) {
            for (ApiRepository.AiringItem item : list) item.source = "personal-fallback";
        }
        list.sort((a, b) -> {
            int t = Long.compare(a.time, b.time);
            if (t != 0) return t;
            double bs = b.anime == null ? 0 : b.anime.score;
            double as = a.anime == null ? 0 : a.anime.score;
            return Double.compare(bs, as);
        });
        return list;
    }

    private static List<ApiRepository.AiringItem> fetchAiringPage(ApiRepository repo, String status, String order, int page, long now, long start, long end, boolean premiere) throws Exception {
        String fields = ShikimoriApi.SH_FIELDS + " nextdbhusoe";
        JSONArray rows = repo.shiki("{animes(limit:50,page:" + page + ",status:" + JSONObject.quote(status) + ",order:" + order + ",censored:false){" + fields + "}}").optJSONArray("animes");
        ArrayList<ApiRepository.AiringItem> out = new ArrayList<>();
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject raw = rows.optJSONObject(i);
                if (raw == null) continue;
                ApiRepository.AiringItem item = createAiringItem(repo, raw, now, start, end, premiere);
                if (item != null) out.add(item);
            }
        }
        return out;
    }

    private static void putAiringItem(LinkedHashMap<String, ApiRepository.AiringItem> map, ApiRepository.AiringItem item) {
        if (item == null || item.anime == null) return;
        String key = (item.anime.malId > 0 ? "mal:" + item.anime.malId : item.anime.key()) + "|" + item.episode + "|" + startOfDay(item.time);
        ApiRepository.AiringItem old = map.get(key);
        if (old == null || ("Точная дата".equals(item.precision) && !"Точная дата".equals(old.precision))) {
            map.put(key, item);
        }
    }

    private static ApiRepository.AiringItem createAiringItem(ApiRepository repo, JSONObject j, long now, long start, long end, boolean premiere) {
        Anime a = repo.remember(repo.shikiAnime(j));
        int aired = j.optInt("episodesAired", 0);
        int total = j.optInt("episodes", a.episodes);
        int next = Math.max(1, aired + 1);
        long at = premiere ? dateAt(j.optJSONObject("airedOn"), 20) : parseInstant(j.optString("nextEpisodeAt", ""));
        String precision = premiere ? "Премьера" : "Точная дата";
        if (!premiere && at <= 0) {
            at = estimateWeekly(j, now, end);
            precision = at > 0 ? "Расчёт" : "Точная дата";
        }
        if (total > 0 && next > total && !premiere) return null;
        if (at <= 0 || at < start - DAY_MS / 2 || at > end) return null;
        ApiRepository.AiringItem item = new ApiRepository.AiringItem();
        item.anime = a;
        item.time = at;
        item.episode = premiere ? 1 : next;
        item.kind = premiere ? "Премьера" : "Новая серия";
        item.precision = precision;
        item.source = "Yoru";
        return item;
    }

    private static void airingRest(LinkedHashMap<String, ApiRepository.AiringItem> map, ApiRepository repo, String status, long now, long start, long end, boolean premiere) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            int maxPages = premiere ? 3 : 8;
            ArrayList<Future<List<ApiRepository.AiringItem>>> tasks = new ArrayList<>();
            for (int page = 1; page <= maxPages; page++) {
                final int p = page;
                tasks.add(pool.submit(() -> {
                    TaskQueue.check();
                    JSONArray rows = new JSONArray(repo.shikiRest("/api/animes?limit=50&page=" + p + "&status=" + status + "&order=" + (premiere ? "aired_on" : "popularity") + "&censored=false"));
                    ArrayList<ApiRepository.AiringItem> out = new ArrayList<>();
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject raw = rows.optJSONObject(i);
                        if (raw == null) continue;
                        JSONObject row = new JSONObject(raw.toString());
                        row.put("episodesAired", raw.optInt("episodes_aired", 0));
                        row.put("nextEpisodeAt", raw.optString("next_episode_at", ""));
                        String date = raw.optString("aired_on", "");
                        JSONObject aired = new JSONObject();
                        if (!date.isEmpty() && !date.equals("null")) {
                            aired.put("date", date);
                            if (date.length() >= 4) aired.put("year", parseInt(date.substring(0, 4)));
                        }
                        row.put("airedOn", aired);
                        JSONObject image = raw.optJSONObject("image");
                        if (image != null) {
                            row.put("poster", new JSONObject().put("mainUrl", repo.shikiImage(image.optString("original", image.optString("preview", "")))));
                        }
                        ApiRepository.AiringItem it = createAiringItem(repo, row, now, start, end, premiere);
                        if (it != null) out.add(it);
                    }
                    return out;
                }));
            }
            for (Future<List<ApiRepository.AiringItem>> task : tasks) {
                try {
                    List<ApiRepository.AiringItem> list = task.get(8, TimeUnit.SECONDS);
                    if (list != null) {
                        for (ApiRepository.AiringItem it : list) putAiringItem(map, it);
                    }
                } catch (Exception ignored) {}
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void appendFavoriteAirings(LinkedHashMap<String, ApiRepository.AiringItem> map, ApiRepository repo, List<Anime> focus, long now, long start, long end) {
        if (focus == null || focus.isEmpty()) return;
        LinkedHashMap<Integer, Anime> ids = new LinkedHashMap<>();
        ArrayList<Anime> unresolved = new ArrayList<>();
        int seen = 0;
        for (Anime fav : focus) {
            if (!Anime.valid(fav)) continue;
            putFavoriteKnownDate(map, repo, fav, now, start, end);
            int id = scheduleId(fav);
            if (id > 0 && !ids.containsKey(id)) ids.put(id, fav);
            else if (unresolved.size() < 120) unresolved.add(Anime.from(fav.json()));
            if (++seen >= 120) break;
        }
        favoriteBatches(map, repo, ids, now, start, end);
        if (unresolved.isEmpty()) return;
        LinkedHashSet<Integer> found = new LinkedHashSet<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(6, unresolved.size())));
        CompletionService<Integer> done = new ExecutorCompletionService<>(pool);
        int jobs = 0;
        for (Anime fav : unresolved) {
            done.submit(() -> searchScheduleId(repo, fav));
            jobs++;
        }
        long deadline = System.currentTimeMillis() + 3500;
        try {
            for (int i = 0; i < jobs; i++) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                Future<Integer> f;
                try {
                    f = done.poll(left, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (f == null) break;
                try {
                    int id = f.get();
                    if (id > 0) found.add(id);
                } catch (Exception ignored) {}
            }
        } finally {
            pool.shutdownNow();
        }
        if (!found.isEmpty()) {
            LinkedHashMap<Integer, Anime> more = new LinkedHashMap<>();
            for (Integer id : found) more.put(id, new Anime());
            favoriteBatches(map, repo, more, now, start, end);
        }
    }

    private static void favoriteBatches(LinkedHashMap<String, ApiRepository.AiringItem> map, ApiRepository repo, LinkedHashMap<Integer, Anime> ids, long now, long start, long end) {
        if (ids == null || ids.isEmpty()) return;
        ArrayList<Integer> batch = new ArrayList<>();
        for (Integer id : ids.keySet()) {
            batch.add(id);
            if (batch.size() >= 40) {
                favoriteBatch(map, repo, batch, now, start, end);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) favoriteBatch(map, repo, batch, now, start, end);
    }

    private static int searchScheduleId(ApiRepository repo, Anime fav) {
        try {
            for (String q : repo.searchTerms(fav)) {
                Anime.Page page = repo.catalog("shikimori", q, 1, new ApiRepository.Filter());
                for (Anime candidate : page.items) {
                    if (repo.matchesAnime(candidate, fav)
                            || ApiRepository.plainName(candidate.title).equals(ApiRepository.plainName(fav.title))
                            || (!fav.original.isEmpty() && ApiRepository.plainName(candidate.original).equals(ApiRepository.plainName(fav.original)))) {
                        return scheduleId(candidate);
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static void favoriteBatch(LinkedHashMap<String, ApiRepository.AiringItem> map, ApiRepository repo, List<Integer> ids, long now, long start, long end) {
        StringBuilder joined = new StringBuilder();
        for (Integer id : ids) {
            if (id == null || id <= 0) continue;
            if (joined.length() > 0) joined.append(',');
            joined.append(id);
        }
        if (joined.length() == 0) return;
        try {
            JSONArray rows = repo.shiki("{animes(ids:" + JSONObject.quote(joined.toString()) + ",limit:" + Math.min(50, ids.size()) + "){" + SH_FIELDS + "}}").optJSONArray("animes");
            for (int i = 0; rows != null && i < rows.length(); i++) {
                ApiRepository.AiringItem item = createAiringItem(repo, rows.getJSONObject(i), now, start, end, false);
                if (item != null) putAiringItem(map, item);
            }
        } catch (Exception ignored) {}
    }

    public static int scheduleId(Anime a) {
        if (a == null) return 0;
        if (a.malId > 0) return a.malId;
        if ("shikimori".equals(a.source) || "yoru".equals(a.source)) return parseInt(a.id);
        return 0;
    }

    private static void putFavoriteKnownDate(LinkedHashMap<String, ApiRepository.AiringItem> map, ApiRepository repo, Anime fav, long now, long start, long end) {
        if (!Anime.valid(fav)) return;
        long at = parseInstant(fav.nextEpisodeAt);
        if (at <= 0 || at < start - DAY_MS / 2 || at > end) return;
        int aired = Math.max(0, fav.episodesAired);
        int total = fav.episodes;
        int next = Math.max(1, aired + 1);
        if (total > 0 && next > total) return;
        ApiRepository.AiringItem item = new ApiRepository.AiringItem();
        item.anime = repo.remember(fav);
        item.time = at;
        item.episode = next;
        item.kind = "Новая серия";
        item.precision = "Точная дата";
        item.source = "Yoru";
        String key = (fav.malId > 0 ? "mal:" + fav.malId : fav.key()) + "|" + item.episode + "|" + startOfDay(at);
        ApiRepository.AiringItem old = map.get(key);
        if (old == null || !"Точная дата".equals(old.precision)) map.put(key, item);
    }

    private static long estimateWeekly(JSONObject j, long now, long end) {
        long first = dateAt(j.optJSONObject("airedOn"), 20);
        if (first <= 0) return 0;
        int aired = Math.max(0, j.optInt("episodesAired", 0));
        long at = first + aired * 7L * DAY_MS;
        while (at < now - 6 * 60 * 60 * 1000L) at += 7L * DAY_MS;
        return at <= end ? at : 0;
    }

    private static long spreadCalendarSlot(Anime a, long start, long end, long now) {
        long days = Math.max(1, (end - start) / DAY_MS);
        long seed = Math.abs((long) (Anime.valid(a) ? a.key() : "yoru").hashCode());
        long at = start + (seed % days) * DAY_MS + 20 * 60 * 60 * 1000L;
        if (at < now - 2 * 60 * 60 * 1000L && days > 1) {
            at = start + ((seed + 1) % days) * DAY_MS + 20 * 60 * 60 * 1000L;
        }
        return at;
    }

    public static long parseInstant(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || v.equals("null")) return 0;
        try { return OffsetDateTime.parse(v).toInstant().toEpochMilli(); } catch (Exception ignored) {}
        try { return Instant.parse(v).toEpochMilli(); } catch (Exception ignored) {}
        try { return LocalDateTime.parse(v).atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli(); } catch (Exception ignored) {}
        return 0;
    }

    public static long dateAt(JSONObject value, int hour) {
        if (value == null) return 0;
        String d = value.optString("date", "");
        if (d.isEmpty() && value.optInt("year", 0) > 0) {
            d = String.format(Locale.US, "%04d-%02d-%02d", value.optInt("year"), Math.max(1, value.optInt("month", 1)), Math.max(1, value.optInt("day", 1)));
        }
        try {
            return LocalDate.parse(d).atTime(hour, 0).atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    public static long startOfDay(long time) {
        return Instant.ofEpochMilli(time).atZone(MSK_ZONE).toLocalDate().atStartOfDay(MSK_ZONE).toInstant().toEpochMilli();
    }

    public static int parseInt(String s) {
        try { return Integer.parseInt(s == null ? "0" : s.replaceAll("\\D+", "")); } catch (Exception e) { return 0; }
    }
}
