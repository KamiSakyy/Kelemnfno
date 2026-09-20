package ru.kelemnfno.anime.data.shots;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 */
public final class ScreenshotFetcher {

    private static final MemCache CACHE = new MemCache();
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
        List<String> shots = shikimori(Cfg.s(49) + shikimoriId + ".json?lang=ru", shikimoriId);
        if (shots.isEmpty()) shots = shikimori(Cfg.s(47) + shikimoriId + ".json?lang=ru", shikimoriId);
        if (shots.isEmpty()) shots = shikimori(Cfg.s(50) + shikimoriId + "?lang=ru", shikimoriId);
        if (shots.isEmpty()) shots = shikimori(Cfg.s(28) + shikimoriId + "?lang=ru", shikimoriId);
        if (shots.isEmpty()) shots = shikimori(Cfg.s(19) + encoded, shikimoriId);
        if (shots.isEmpty()) shots = shikimori(Cfg.s(29) + encoded, shikimoriId);

        if (shots.isEmpty()) CACHE.put("empty_" + key, shots);
        else CACHE.put(key, shots);
        return shots;
    }

    /**
     * Shikimori: в ответе поле screenshots — массив строк вида
     * //shikimori.me/system/screenshots/…
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
            for (JsonElement el : J.arr(json, "screenshots")) {
                add(out, J.str(el));
            }
        } catch (Exception ignored) {
        }
        return out;
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
