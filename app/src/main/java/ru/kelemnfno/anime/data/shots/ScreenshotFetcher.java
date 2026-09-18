package ru.kelemnfno.anime.data.shots;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import ru.kelemnfno.anime.data.repo.MemCache;
import ru.kelemnfno.anime.data.tsuyu.J;
import ru.kelemnfno.anime.data.tsuyu.Net;

/**
 * Кадры из серий под описанием.
 *
 * Настоящие скриншоты серий хранит Shikimori (по shikimori_id) и MyAnimeList (по mal_id).
 * Оба закрыты защитой от ботов, поэтому источников несколько и они перебираются по очереди:
 * страница-JSON Shikimori → зеркало → API Shikimori → прокси → Jikan → страница MAL → AniList.
 */
public final class ScreenshotFetcher {

    private static final MemCache CACHE = new MemCache();
    private static final MediaType JSON = MediaType.get("application/json; charset=UTF-8");
    private static final int LIMIT = 8;
    private static final long GOOD_TTL = 12 * 60 * 60_000L;
    private static final long EMPTY_TTL = 60_000L;

    private ScreenshotFetcher() {
    }

    public static List<String> fetch(int shikimoriId, int malId) {
        return fetch(shikimoriId, malId, null);
    }

    public static List<String> fetch(int shikimoriId, int malId, String title) {
        String key = shikimoriId + "_" + malId + "_" + (title == null ? "" : title.hashCode());
        List<String> hit = CACHE.get(key, GOOD_TTL);
        if (hit != null && !hit.isEmpty()) return hit;
        // Пустой ответ держим всего минуту: если источник отвиснет, кадры появятся сами.
        if (CACHE.get("empty_" + key, EMPTY_TTL) != null) return new ArrayList<>();

        List<String> shots = new ArrayList<>();

        String encoded = "https%3A%2F%2Fshikimori.one%2Fanimes%2F" + shikimoriId + ".json%3Flang%3Dru";
        if (shikimoriId > 0) {
            shots = shikimori(Secrets.s(49) + shikimoriId + ".json?lang=ru");
            if (shots.isEmpty()) {
                shots = shikimori(Secrets.s(47) + shikimoriId + ".json?lang=ru");
            }
            if (shots.isEmpty()) {
                shots = shikimori(Secrets.s(50) + shikimoriId + "?lang=ru");
            }
            if (shots.isEmpty()) {
                shots = shikimori(Secrets.s(28) + shikimoriId + "?lang=ru");
            }
            if (shots.isEmpty()) {
                shots = shikimori(Secrets.s(19) + encoded);
            }
            if (shots.isEmpty()) {
                shots = shikimori(Secrets.s(29) + encoded);
            }
        }

        int mal = malId;
        if (mal <= 0) mal = malIdByTitle(title);
        if (shots.isEmpty() && mal > 0) shots = jikan(mal);
        if (shots.isEmpty() && mal > 0) shots = malPictures(mal);
        if (shots.isEmpty() && mal > 0) shots = anilist(mal);

        if (shots.isEmpty()) CACHE.put("empty_" + key, shots);
        else CACHE.put(key, shots);
        return shots;
    }

    /** Shikimori: в ответе поле screenshots — массив строк вида //shikimori.me/system/... */
    private static List<String> shikimori(String url) {
        List<String> out = new ArrayList<>();
        try {
            Map<String, String> headers = Net.baseHeaders(Secrets.s(2), Secrets.s(48));
            headers.put("Accept", "application/json");
            JsonObject json = Net.getJson(url, headers);
            for (JsonElement el : J.arr(json, "screenshots")) {
                add(out, J.str(el));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Jikan v4: у каждой картинки объект images.jpg.large, а не поле large. */
    private static List<String> jikan(int malId) {
        List<String> out = new ArrayList<>();
        try {
            JsonObject root = Net.getJson(Secrets.s(26) + malId + "/pictures",
                    Net.baseHeaders(null, null));
            for (JsonObject item : J.list(root, "data")) {
                JsonObject jpg = J.obj(J.obj(item, "images"), "jpg");
                String v = J.str(jpg, "large");
                if (v.isEmpty()) v = J.str(jpg, "image_url");
                if (v.isEmpty()) v = J.str(J.obj(J.obj(item, "images"), "webp"), "large");
                add(out, v);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Страница картинок MyAnimeList: картинки лежат в data-src. */
    private static List<String> malPictures(int malId) {
        List<String> out = new ArrayList<>();
        try {
            String html = Net.get(Secrets.s(36) + malId + "/pictures",
                    Net.baseHeaders(Secrets.s(35), Secrets.s(36) + malId));
            Document doc = Jsoup.parse(html);
            for (Element img : doc.select("img[data-src], img[src]")) {
                String src = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
                if (src.contains(Secrets.s(6))) add(out, src);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** AniList: кадров серий там нет, зато есть широкий баннер — лучше, чем пустой блок. */
    private static List<String> anilist(int malId) {
        List<String> out = new ArrayList<>();
        try {
            String body = "{\"query\":\"{ Media(idMAL: " + malId
                    + ", type: ANIME) { bannerImage coverImage { extraLarge large } } }\"}";
            Request request = new Request.Builder()
                    .url(Secrets.s(30))
                    .header("User-Agent", Net.CHROME)
                    .post(RequestBody.create(body, JSON))
                    .build();
            try (Response response = Net.client().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return out;
                JsonObject root = J.obj(Net.parse(response.body().string()), "data");
                JsonObject media = J.obj(root, "Media");
                // Прокси-обёртки кладут ответ на уровень глубже — как в коде сайта.
                if (media.size() == 0) media = J.obj(J.obj(root, "data"), "Media");
                add(out, J.str(media, "bannerImage"));
                JsonObject cover = J.obj(media, "coverImage");
                add(out, J.str(cover, "extraLarge"));
                add(out, J.str(cover, "large"));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Если mal_id в ответе нет — ищем тайтл по названию. */
    private static int malIdByTitle(String title) {
        if (title == null || title.trim().isEmpty()) return 0;
        try {
            String q = URLEncoder.encode(title.trim(), "UTF-8");
            JsonObject root = Net.getJson(
                    Secrets.s(27) + q + "&limit=1&type=anime",
                    Net.baseHeaders(null, null));
            List<JsonObject> items = J.list(root, "data");
            if (items.isEmpty()) return 0;
            return J.intOf(items.get(0), "mal_id");
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static void add(List<String> out, String v) {
        if (out.size() >= LIMIT || v == null) return;
        v = v.trim();
        if (v.isEmpty()) return;
        if (v.startsWith("//")) {
            v = "https:" + v;
        } else if (v.startsWith("/")) {
            // Shikimori отдаёт кадры относительными путями: /system/screenshots/…
            v = ru.kelemnfno.anime.data.tsuyu.Secrets.shikimori() + v;
        }
        if (!v.startsWith("http://") && !v.startsWith("https://")) return;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).equals(v)) return;
        }
        out.add(v);
    }
}
