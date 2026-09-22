package ru.kelemnfno.anime.data.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ru.kelemnfno.anime.data.resolver.Net;

/**
 * AniLibria (AniLiberty) API v1 — самое новое API 2026 года
 * (aniliberty.top / api.anilibria.app). Используется ТОЛЬКО для
 * каталога жанра «хентай»; основные источники не затрагиваются.
 */
public final class AniLibriaApi {

    private static final String[] HOSTS = {
            "https://aniliberty.top/api/v1",
            "https://api.anilibria.app/api/v1",
            "https://api.anilibria.tv/v3" // старый запасной
    };

    public static final class Title {
        public String name = "";
        public String poster = "";
    }

    private static volatile OkHttpClient client;
    private static volatile String hentaiGenreId = null;

    private AniLibriaApi() {
    }

    private static OkHttpClient http() {
        if (client == null) {
            synchronized (AniLibriaApi.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(20, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return client;
    }

    private static String get(String url) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", Net.CHROME)
                .build();
        try (Response res = http().newCall(req).execute()) {
            if (!res.isSuccessful() || res.body() == null) throw new IOException("HTTP " + res.code());
            return res.body().string();
        }
    }

    /** Страница каталога хентая (page с единицы). */
    public static List<Title> hentai(int page) throws IOException {
        IOException last = null;
        for (String host : HOSTS) {
            try {
                if (host.endsWith("/v3")) {
                    List<Title> v3 = hentaiV3(host, page);
                    if (!v3.isEmpty()) return v3;
                    continue;
                }
                String gid = hentaiGenreId;
                if (gid == null) {
                    gid = findHentaiGenre(host);
                    if (gid == null) throw new IOException("жанр не найден");
                    hentaiGenreId = gid;
                }
                String url = host + "/anime/catalog/releases?f%5Bgenres%5D=" + gid
                        + "&page=" + page + "&limit=30";
                List<Title> rows = parseCatalog(get(url), host);
                if (!rows.isEmpty()) return rows;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("AniLibria недоступна");
    }

    /** Идентификатор жанра «хентай» из справочника v1. */
    private static String findHentaiGenre(String host) throws IOException {
        JsonElement root = JsonParser.parseString(get(host + "/anime/catalog/references/genres"));
        JsonArray arr = null;
        if (root.isJsonObject() && root.getAsJsonObject().has("data"))
            arr = root.getAsJsonObject().getAsJsonArray("data");
        else if (root.isJsonArray()) arr = root.getAsJsonArray();
        if (arr == null) return null;
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String label = (o.has("value") ? o.get("value").getAsString() : "")
                    + " " + (o.has("description") ? o.get("description").getAsString() : "")
                    + " " + (o.has("name") ? o.get("name").getAsString() : "");
            if (label.toLowerCase().contains("хентай")) {
                return o.has("id") ? String.valueOf(o.get("id").getAsInt()) : null;
            }
        }
        return null;
    }

    /** Ответ каталога v1: { data: [ { name:{ru,en}, poster/posters:{small:{url}} } ] }. */
    private static List<Title> parseCatalog(String body, String host) {
        List<Title> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(body);
        JsonArray arr = null;
        if (root.isJsonObject() && root.getAsJsonObject().has("data"))
            arr = root.getAsJsonObject().getAsJsonArray("data");
        else if (root.isJsonArray()) arr = root.getAsJsonArray();
        if (arr == null) return out;
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            Title t = new Title();
            if (o.has("name") && o.get("name").isJsonObject()) {
                JsonObject n = o.getAsJsonObject("name");
                t.name = str(n, "ru");
                if (t.name.isEmpty()) t.name = str(n, "en");
                if (t.name.isEmpty()) t.name = str(n, "alternative");
            } else if (o.has("name") && o.get("name").isJsonPrimitive()) {
                t.name = o.get("name").getAsString();
            }
            JsonObject p = null;
            if (o.has("posters") && o.get("posters").isJsonObject())
                p = o.getAsJsonObject("posters");
            else if (o.has("poster") && o.get("poster").isJsonObject())
                p = o.getAsJsonObject("poster");
            if (p != null) {
                for (String key : new String[]{"small", "medium", "original"}) {
                    if (p.has(key) && p.get(key).isJsonObject()) {
                        String u = str(p.getAsJsonObject(key), "url");
                        if (!u.isEmpty()) {
                            t.poster = u.startsWith("http") ? u
                                    : host.replace("/api/v1", "") + (u.startsWith("/") ? u : "/" + u);
                            break;
                        }
                    }
                }
            }
            if (!t.name.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Запасной путь через старый v3. */
    private static List<Title> hentaiV3(String host, int page) throws IOException {
        List<Title> out = new ArrayList<>();
        String url = host + "/title/search?genres=%D1%85%D0%B5%D0%BD%D1%82%D0%B0%D0%B9"
                + "&items_per_page=30&page=" + page
                + "&filter=id,names,posters";
        JsonElement root = JsonParser.parseString(get(url));
        JsonArray list = null;
        if (root.isJsonArray()) list = root.getAsJsonArray();
        else if (root.isJsonObject() && root.getAsJsonObject().has("list"))
            list = root.getAsJsonObject().getAsJsonArray("list");
        if (list == null) return out;
        for (JsonElement e : list) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            Title t = new Title();
            if (o.has("names") && o.get("names").isJsonObject()) {
                JsonObject n = o.getAsJsonObject("names");
                t.name = str(n, "ru");
                if (t.name.isEmpty()) t.name = str(n, "en");
            }
            if (o.has("posters") && o.get("posters").isJsonObject()) {
                JsonObject p = o.getAsJsonObject("posters");
                String url = "";
                if (p.has("small") && p.get("small").isJsonObject())
                    url = str(p.getAsJsonObject("small"), "url");
                if (url.isEmpty() && p.has("original") && p.get("original").isJsonObject())
                    url = str(p.getAsJsonObject("original"), "url");
                if (url.startsWith("/")) url = host.replace("/v3", "") + url;
                t.poster = url;
            }
            if (!t.name.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
