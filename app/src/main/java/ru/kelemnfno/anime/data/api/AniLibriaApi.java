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

    
    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
