package ru.kelemnfno.anime.data.shots;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import ru.kelemnfno.anime.data.repo.MemCache;
import ru.kelemnfno.anime.data.tsuyu.J;
import ru.kelemnfno.anime.data.tsuyu.Net;

/** Кадры из серий: Shikimori → AniList → Jikan (MAL). */
public final class ScreenshotFetcher {

    private static final MemCache CACHE = new MemCache();
    private static final MediaType JSON = MediaType.get("application/json; charset=UTF-8");

    private ScreenshotFetcher() {
    }

    public static List<String> fetch(int shikimoriId, int malId) {
        String key = shikimoriId + "_" + malId;
        List<String> hit = CACHE.get(key, 60 * 60_000L);
        if (hit != null) return hit;

        List<String> shots = new ArrayList<>();
        if (shikimoriId > 0) shots.addAll(shikimori(shikimoriId));
        if (shots.isEmpty() && malId > 0) shots.addAll(anilist(malId));
        if (shots.isEmpty() && malId > 0) shots.addAll(jikan(malId));

        CACHE.put(key, shots);
        return shots;
    }

    private static List<String> shikimori(int id) {
        List<String> out = new ArrayList<>();
        try {
            JsonObject json = Net.getJson("https://shikimori.one/animes/" + id + ".json?lang=ru",
                    Net.baseHeaders("https://shikimori.one", "https://shikimori.one/"));
            for (JsonElement el : J.arr(json, "screenshots")) {
                String v = J.str(el);
                if (v.isEmpty()) continue;
                if (v.startsWith("//")) v = "https:" + v;
                out.add(v);
                if (out.size() >= 8) break;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static List<String> anilist(int malId) {
        List<String> out = new ArrayList<>();
        try {
            String body = "{\"query\":\"{ Media(idMAL: " + malId
                    + ", type: ANIME) { screenshots { location } } }\"}";
            Request request = new Request.Builder()
                    .url("https://graphql.anilist.co/")
                    .post(RequestBody.create(body, JSON))
                    .build();
            try (Response response = Net.client().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return out;
                JsonObject root = Net.parse(response.body().string());
                JsonObject dataObj = J.obj(root, "data");
                JsonObject media = J.obj(dataObj, "Media");
                if (media.size() == 0) media = J.obj(J.obj(dataObj, "data"), "Media");
                for (JsonObject s : J.list(media, "screenshots")) {
                    String v = J.str(s, "location");
                    if (!v.isEmpty()) out.add(v);
                    if (out.size() >= 8) break;
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static List<String> jikan(int malId) {
        List<String> out = new ArrayList<>();
        try {
            JsonObject root = Net.getJson("https://api.jikan.moe/v4/anime/" + malId + "/pictures",
                    Net.baseHeaders(null, null));
            JsonArray data = J.arr(root, "data");
            for (JsonElement el : data) {
                String v = J.str(J.obj(el), "large");
                if (!v.isEmpty()) out.add(v);
                if (out.size() >= 8) break;
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
