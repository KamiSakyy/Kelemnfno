package ru.kelemnfno.anime.data.shots;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import ru.kelemnfno.anime.data.repo.MemCache;
import ru.kelemnfno.anime.data.resolver.J;
import ru.kelemnfno.anime.data.resolver.Net;
import ru.kelemnfno.anime.data.resolver.Cfg;

/**
 * Кадры из серий под описанием.
 *
 * Единственный источник — Shikimori, и только по shikimori_id тайтла.
 * Никаких поисков по названию и никаких сторонних баз: любой такой
 * запасной вариант при неточном совпадении показывает кадры чужого
 * аниме. Если Shikimori не ответил — блока кадров просто нет.
 *
 * Важно про формат: в ответе Shikimori поле screenshots — это массив
 * объектов {"original": "/system/screenshots/…", "preview": "…"}, а не
 * массив строк. Пути относительные, хост подставляем сами.
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

    /**
     * @param shikimoriId id тайтла в Shikimori; без него кадров нет вообще
     * @param malId       не используется, оставлен ради совместимости вызовов
     * @param title       не используется: поиск по названию даёт чужие тайтлы
     */
    public static List<String> fetch(int shikimoriId, int malId, String title) {
        if (shikimoriId <= 0) return new ArrayList<>();

        String key = "shiki_" + shikimoriId;
        List<String> hit = CACHE.get(key, GOOD_TTL);
        if (hit != null && !hit.isEmpty()) return hit;
        // Пустой ответ держим всего минуту: если Shikimori отвиснет, кадры появятся сами.
        if (CACHE.get("empty_" + key, EMPTY_TTL) != null) return new ArrayList<>();

        String encoded = Cfg.s(66) + shikimoriId + ".json%3Flang%3Dru";
        // Канонический API первым: один запрос и полный ответ.
        List<String> shots = shikimori(Cfg.s(50) + shikimoriId + ".json?lang=ru", shikimoriId);
        if (shots.isEmpty()) shots = shikimoriGraph(shikimoriId);
        if (shots.isEmpty()) shots = shikimori(Cfg.s(49) + shikimoriId + ".json?lang=ru", shikimoriId);
        if (shots.isEmpty()) shots = shikimori(Cfg.s(47) + shikimoriId + ".json?lang=ru", shikimoriId);
        if (shots.isEmpty()) shots = shikimori(Cfg.s(28) + shikimoriId + "?lang=ru", shikimoriId);
        if (shots.isEmpty()) shots = shikimori(Cfg.s(19) + encoded, shikimoriId);
        if (shots.isEmpty()) shots = shikimori(Cfg.s(29) + encoded, shikimoriId);

        if (shots.isEmpty()) CACHE.put("empty_" + key, shots);
        else CACHE.put(key, shots);
        return shots;
    }

    /**
     * Shikimori REST: /api/animes/{id}.json и его зеркала.
     *
     * Если прокси отдал страницу другого тайтла (id в ответе не наш) —
     * ответ отбрасываем, чужие кадры не показываем.
     */
    private static List<String> shikimori(String url, int expectedId) {
        List<String> out = new ArrayList<>();
        try {
            Map<String, String> headers = Net.baseHeaders(Cfg.s(2), Cfg.s(48));
            headers.put("Accept", "application/json");
            JsonObject json = Net.getJson(url, headers);
            if (json == null) return out;
            int id = J.intOf(json, "id");
            if (id > 0 && id != expectedId) return out;
            collect(out, json.getAsJsonArray("screenshots"));
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * Shikimori GraphQL: те же кадры, но сразу абсолютными ссылками.
     * Нужен как запасной ход, если REST закрыт от мобильных клиентов.
     */
    private static List<String> shikimoriGraph(int shikimoriId) {
        List<String> out = new ArrayList<>();
        try {
            String body = "{\"query\":\"{ animes(ids: \\\"" + shikimoriId
                    + "\\\") { id screenshots { originalUrl x332Url x166Url } } }\"}";
            Request request = new Request.Builder()
                    .url(Cfg.s(501))
                    .header("User-Agent", Net.CHROME)
                    .header("Origin", Cfg.s(2))
                    .post(RequestBody.create(body, JSON))
                    .build();
            try (Response response = Net.client().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return out;
                JsonObject data = J.obj(Net.parse(response.body().string()), "data");
                if (data == null) return out;
                JsonElement listEl = data.get("animes");
                if (listEl == null || !listEl.isJsonArray()) return out;
                JsonArray list = listEl.getAsJsonArray();
                if (list.size() == 0) return out;
                JsonElement firstEl = list.get(0);
                if (firstEl == null || !firstEl.isJsonObject()) return out;
                JsonObject first = firstEl.getAsJsonObject();
                int id = J.intOf(first, "id");
                if (id > 0 && id != shikimoriId) return out;
                collectGraph(out, first.getAsJsonArray("screenshots"));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Кадры REST-ответа: объекты с original / preview и относительными путями. */
    private static void collect(List<String> out, JsonArray arr) {
        if (arr == null) return;
        for (JsonElement el : arr) {
            if (el == null || el.isJsonNull()) continue;
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                String v = J.str(o, "original");
                if (v.isEmpty()) v = J.str(o, "preview");
                add(out, v);
            } else if (el.isJsonPrimitive()) {
                // старый формат — просто строка
                add(out, J.str(el));
            }
        }
    }

    /** Кадры GraphQL-ответа: absoluteUrl-поля, берём самое крупное из доступных. */
    private static void collectGraph(List<String> out, JsonArray arr) {
        if (arr == null) return;
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String v = J.str(o, "originalUrl");
            if (v.isEmpty()) v = J.str(o, "x332Url");
            if (v.isEmpty()) v = J.str(o, "x166Url");
            add(out, v);
        }
    }

    private static void add(List<String> out, String v) {
        if (out.size() >= LIMIT || v == null) return;
        v = v.trim();
        if (v.isEmpty()) return;
        if (v.startsWith("//")) {
            v = "https:" + v;
        } else if (v.startsWith("/")) {
            // Shikimori отдаёт кадры относительными путями: /system/screenshots/…
            v = Cfg.shikimori() + v;
        }
        if (!v.startsWith("http://") && !v.startsWith("https://")) return;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).equals(v)) return;
        }
        out.add(v);
    }
}
