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
 * AniLibria API (v3) — используется ТОЛЬКО для каталога жанра «хентай».
 * Основные источники приложения не затрагиваются.
 */
public final class AniLibriaApi {

    private static final String[] HOSTS = {"https://api.anilibria.tv", "https://api.anilibria.app"};

    public static final class Title {
        public String name = "";
        public String poster = "";
    }

    private static volatile OkHttpClient client;

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

    /** Страница каталога хентая (page с единицы). */
    public static List<Title> hentai(int page) throws IOException {
        IOException last = null;
        for (String host : HOSTS) {
            String url = host + "/v3/title/search?genres=%D1%85%D0%B5%D0%BD%D1%82%D0%B0%D0%B9"
                    + "&items_per_page=30&page=" + page
                    + "&filter=id,names,posters";
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", Net.CHROME)
                    .build();
            try (Response res = http().newCall(req).execute()) {
                if (!res.isSuccessful() || res.body() == null) {
                    last = new IOException("HTTP " + res.code());
                    continue;
                }
                return parse(res.body().string(), host);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("AniLibria недоступна");
    }

    private static List<Title> parse(String body, String host) {
        List<Title> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(body);
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
                if (url.startsWith("/")) url = host + url;
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
