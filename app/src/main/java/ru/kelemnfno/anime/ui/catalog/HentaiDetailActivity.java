package ru.kelemnfno.anime.ui.catalog;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.api.DirectHentai;
import ru.kelemnfno.anime.data.db.DownloadEntity;
import ru.kelemnfno.anime.data.model.Lookup;
import ru.kelemnfno.anime.data.model.Track;
import ru.kelemnfno.anime.data.resolver.Net;
import ru.kelemnfno.anime.data.resolver.SourceEngine;
import ru.kelemnfno.anime.download.DownloadService;
import ru.kelemnfno.anime.ui.player.PlayerActivity;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.Ui;

/**
 * Карточка хентай-тайтла на данных Shikimori: описание, жанры, рейтинг;
 * серии, просмотр (настоящие HLS AniLibria API v1) и скачивание.
 */
public class HentaiDetailActivity extends AppCompatActivity {

    private static final String[] SHIKI = {"https://shikimori.io/api", "https://shikimori.one/api"};
    private static final String ANILIB = "https://anilibria.top/api/v1";

    private int shikiId;
    private String title = "";
    private String original = "";
    private String poster = "";

    /** номер серии -> качество -> url (AniLibria HLS). */
    private final Map<Integer, Map<Integer, String>> epQ = new TreeMap<>();
    private Track directTrack;

    public static void start(Context c, int shikiId, String title, String original,
                             int year, String poster) {
        c.startActivity(new Intent(c, HentaiDetailActivity.class)
                .putExtra("shiki", shikiId)
                .putExtra("title", title)
                .putExtra("original", original)
                .putExtra("year", year)
                .putExtra("poster", poster));
    }

    private static volatile OkHttpClient client;

    private static OkHttpClient http() {
        if (client == null) {
            synchronized (HentaiDetailActivity.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(20, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
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

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String fixUrl(String v) {
        if (v == null || v.isEmpty()) return "";
        if (v.startsWith("http")) return v;
        if (v.startsWith("/")) return "https://anilibria.top" + v;
        if (v.startsWith("//")) return "https:" + v;
        return "";
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hentai_detail);
        findViewById(R.id.back).setOnClickListener(v -> finish());

        shikiId = getIntent().getIntExtra("shiki", 0);
        title = getIntent().getStringExtra("title");
        original = getIntent().getStringExtra("original");
        poster = getIntent().getStringExtra("poster");
        int year = getIntent().getIntExtra("year", 0);

        TextView t = findViewById(R.id.title);
        t.setText(title);
        TextView o = findViewById(R.id.original);
        o.setText(original);
        TextView m = findViewById(R.id.meta);
        m.setText(year > 0 ? year + " · 18+" : "18+");
        ImageView p = findViewById(R.id.poster);
        Glide.with(p).load(poster).placeholder(R.drawable.ph_poster).into(p);

        findViewById(R.id.fallback_watch).setOnClickListener(v -> fallback());
        load();
    }

    private void load() {
        AppExecutors.get().run(() -> {
            String[] detail = shikiDetail();
            fillAnilibria();
            return detail;
        }, (detail, error) -> {
            if (isFinishing()) return;
            if (detail != null) {
                if (!detail[0].isEmpty()) ((TextView) findViewById(R.id.meta)).setText(detail[0]);
                if (!detail[1].isEmpty()) ((TextView) findViewById(R.id.genres)).setText(detail[1]);
                if (!detail[2].isEmpty()) ((TextView) findViewById(R.id.description)).setText(detail[2]);
            }
            renderEpisodes();
        });
    }

    /** [meta, жанры, описание] из Shikimori. */
    private String[] shikiDetail() {
        String[] out = {"", "", ""};
        for (String host : SHIKI) {
            try {
                JsonObject a = JsonParser.parseString(get(host + "/animes/" + shikiId)).getAsJsonObject();
                StringBuilder meta = new StringBuilder();
                int year = getIntent().getIntExtra("year", 0);
                if (year > 0) meta.append(year);
                if (a.has("score") && a.get("score").isJsonPrimitive() && a.get("score").getAsDouble() > 0)
                    meta.append(meta.length() > 0 ? " · " : "").append("★ ")
                            .append(String.format(java.util.Locale.US, "%.2f", a.get("score").getAsDouble()));
                if (a.has("episodes") && a.get("episodes").isJsonPrimitive())
                    meta.append(meta.length() > 0 ? " · " : "").append(a.get("episodes").getAsInt()).append(" эп.");
                if (a.has("kind") && a.get("kind").isJsonPrimitive() && !a.get("kind").getAsString().isEmpty())
                    meta.append(meta.length() > 0 ? " · " : "").append(a.get("kind").getAsString().toUpperCase());
                meta.append(meta.length() > 0 ? " · " : "").append("18+");
                out[0] = meta.toString();
                if (a.has("genres") && a.get("genres").isJsonArray()) {
                    StringBuilder g = new StringBuilder();
                    for (JsonElement e : a.getAsJsonArray("genres")) {
                        if (!e.isJsonObject()) continue;
                        JsonObject ge = e.getAsJsonObject();
                        String name = ge.has("russian") && ge.get("russian").isJsonPrimitive()
                                ? ge.get("russian").getAsString()
                                : (ge.has("name") ? ge.get("name").getAsString() : "");
                        if (!name.isEmpty()) {
                            if (g.length() > 0) g.append(", ");
                            g.append(name);
                        }
                    }
                    out[1] = g.toString();
                }
                if (a.has("description") && a.get("description").isJsonPrimitive()) {
                    out[2] = a.get("description").getAsString()
                            .replaceAll("\\[[^\\]]*]", "").trim();
                }
                return out;
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Серии AniLibria: поиск релиза по имени, затем полный релиз с HLS. */
    private void fillAnilibria() {
        epQ.clear();
        for (String q : new String[]{original, title}) {
            if (q == null || q.isEmpty()) continue;
            try {
                JsonElement s = JsonParser.parseString(
                        get(ANILIB + "/app/search/releases?query=" + enc(q) + "&limit=6"));
                JsonArray arr = null;
                if (s.isJsonObject() && s.getAsJsonObject().has("data"))
                    arr = s.getAsJsonObject().getAsJsonArray("data");
                else if (s.isJsonArray()) arr = s.getAsJsonArray();
                if (arr == null) continue;
                for (JsonElement rel : arr) {
                    if (!rel.isJsonObject()) continue;
                    int id = rel.getAsJsonObject().has("id")
                            ? rel.getAsJsonObject().get("id").getAsInt() : 0;
                    if (id <= 0) continue;
                    JsonObject full = JsonParser.parseString(
                            get(ANILIB + "/anime/releases/" + id)).getAsJsonObject();
                    if (!full.has("episodes") || !full.get("episodes").isJsonArray()) continue;
                    for (JsonElement e : full.getAsJsonArray("episodes")) {
                        if (!e.isJsonObject()) continue;
                        JsonObject ep = e.getAsJsonObject();
                        int ord = ep.has("ordinal") ? (int) ep.get("ordinal").getAsDouble() : 0;
                        if (ord <= 0) continue;
                        Map<Integer, String> qmap = new LinkedHashMap<>();
                        String[][] keys = {{"480", "hls_480"}, {"720", "hls_720"},
                                {"1080", "hls_1080"}, {"1440", "hls_1440"}, {"2160", "hls_2160"}};
                        for (String[] kv : keys) {
                            String u = fixUrl(ep.has(kv[1]) && ep.get(kv[1]).isJsonPrimitive()
                                    ? ep.get(kv[1]).getAsString() : "");
                            if (!u.isEmpty()) qmap.put(Integer.parseInt(kv[0]), u);
                        }
                        if (!qmap.isEmpty()) epQ.put(ord, qmap);
                    }
                    if (!epQ.isEmpty()) return;
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void renderEpisodes() {
        LinearLayout box = findViewById(R.id.episodes);
        box.removeAllViews();
        TextView header = findViewById(R.id.episodes_header);
        View fallback = findViewById(R.id.fallback_watch);
        if (epQ.isEmpty()) {
            header.setVisibility(View.GONE);
            fallback.setVisibility(View.VISIBLE);
            return;
        }
        header.setText("Серии · " + epQ.size());
        header.setVisibility(View.VISIBLE);
        fallback.setVisibility(View.GONE);
        LayoutInflater li = LayoutInflater.from(this);
        for (Map.Entry<Integer, Map<Integer, String>> e : epQ.entrySet()) {
            final int ord = e.getKey();
            View row = li.inflate(R.layout.item_hentai_ep, box, false);
            ((TextView) row.findViewById(R.id.number)).setText("Серия " + ord);
            row.findViewById(R.id.watch).setOnClickListener(v -> play(ord));
            row.findViewById(R.id.download).setOnClickListener(v -> download(ord));
            box.addView(row);
        }
    }

    private Track track() {
        if (directTrack == null) directTrack = DirectHentai.publish(epQ);
        return directTrack;
    }

    private void play(int ord) {
        Track tr = track();
        List<Track> one = new ArrayList<>();
        one.add(tr);
        PlayerActivity.start(this, title, "hentai_" + shikiId, shikiId, poster,
                tr.id, ord, tr.voice, one);
    }

    private void download(int ord) {
        Map<Integer, String> qmap = epQ.get(ord);
        if (qmap == null || qmap.isEmpty()) {
            Ui.toast(this, "Поток недоступен");
            return;
        }
        int best = 0;
        for (int q : qmap.keySet()) best = Math.max(best, q);
        DownloadEntity e = new DownloadEntity();
        e.slug = "hentai_" + shikiId;
        e.animeId = shikiId;
        e.title = title;
        e.episode = String.valueOf(ord);
        e.voice = "AniLibria";
        e.quality = best;
        e.url = qmap.get(best);
        e.kind = "hls";
        e.poster = poster;
        e.fileName = "hentai_" + shikiId + "_" + ord + "_" + best + ".ts";
        DownloadService.add(this, e);
        Ui.toast(this, "Серия " + ord + " — в загрузках");
    }

    /** Запасной путь: общие источники приложения. */
    private void fallback() {
        Ui.toast(this, "Секунду…");
        AppExecutors.get().run(() -> {
            Lookup l = new Lookup();
            l.title = title;
            l.original = original.isEmpty() ? null : original;
            l.shikimoriId = shikiId;
            l.genres.add("хентай");
            return SourceEngine.tracks(l);
        }, (tracks, error) -> {
            if (isFinishing()) return;
            if (error != null || tracks == null || tracks.isEmpty()) {
                Ui.toast(this, "Источники не найдены");
                return;
            }
            Track tr = tracks.get(0);
            PlayerActivity.start(this, title, "hentai_" + shikiId, shikiId, poster,
                    tr.id, tr.firstEpisode(), tr.voice, tracks);
        });
    }
}
