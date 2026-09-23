package ru.kelemnfno.anime.ui.catalog;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.api.DirectHentai;
import ru.kelemnfno.anime.data.model.AnimeItem;
import ru.kelemnfno.anime.data.model.Lookup;
import ru.kelemnfno.anime.data.model.Track;
import ru.kelemnfno.anime.data.repo.AnimeRepository;
import ru.kelemnfno.anime.data.resolver.Net;
import ru.kelemnfno.anime.data.resolver.SourceEngine;
import ru.kelemnfno.anime.ui.detail.DetailActivity;
import ru.kelemnfno.anime.ui.player.PlayerActivity;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.Ui;

/**
 * Раздел 18+: настоящий хентай-каталог Shikimori (жанр 12, без цензуры),
 * полноразмерные обложки. Тап — сразу просмотр: настоящие HLS-видео
 * AniLibria (API v1), запасные пути — общие источники и основной каталог.
 */
public class HentaiActivity extends AppCompatActivity {

    private static final String[] SHIKI = {"https://shikimori.io/api", "https://shikimori.tv/api", "https://shikimori.one/api"};
    private static final String ANILIB = "https://anilibria.top/api/v1";

    /** Тайтлы, которые обязаны быть в разделе (со скриншота пользователя). */
    private static final String[] PINNED = {
            "Issho ni Ecchi",
            "Incha Couple",
            "Usamimi Bouken-tan"
    };

    public static void start(android.content.Context context) {
        context.startActivity(new android.content.Intent(context, HentaiActivity.class));
    }

    private static final class Row {
        int shikiId;
        String title = "";
        String original = "";
        int year;
        String poster = "";
    }

    private static volatile OkHttpClient client;

    private final List<Row> items = new ArrayList<>();
    private Adapter adapter;
    private int page = 1;
    private boolean loading;
    private boolean hasMore = true;

    private static OkHttpClient http() {
        if (client == null) {
            synchronized (HentaiActivity.class) {
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

    private static Row parseShiki(JsonObject o) {
        Row r = new Row();
        r.shikiId = o.has("id") ? o.get("id").getAsInt() : 0;
        String ru = o.has("russian") && o.get("russian").isJsonPrimitive()
                ? o.get("russian").getAsString() : "";
        String en = o.has("name") && o.get("name").isJsonPrimitive()
                ? o.get("name").getAsString() : "";
        r.title = ru.isEmpty() ? en : ru;
        r.original = en;
        String iso = o.has("released_on") && o.get("released_on").isJsonPrimitive()
                ? o.get("released_on").getAsString()
                : (o.has("aired_on") && o.get("aired_on").isJsonPrimitive()
                ? o.get("aired_on").getAsString() : "");
        try {
            r.year = iso.length() >= 4 ? Integer.parseInt(iso.substring(0, 4)) : 0;
        } catch (NumberFormatException ignored) {
        }
        if (o.has("image") && o.get("image").isJsonObject()) {
            JsonObject img = o.getAsJsonObject("image");
            String p = img.has("original") && img.get("original").isJsonPrimitive()
                    ? img.get("original").getAsString() : "";
            if (p.isEmpty() && img.has("preview") && img.get("preview").isJsonPrimitive())
                p = img.get("preview").getAsString();
            if (!p.isEmpty()) r.poster = p.startsWith("http") ? p : "https://shikimori.io" + p;
        }
        return r.title.isEmpty() ? null : r;
    }

    /** Закреплённые тайтлы со скриншота — поиск по имени в Shikimori. */
    private static List<Row> pinned() {
        List<Row> out = new ArrayList<>();
        for (String name : PINNED) {
            for (String host : SHIKI) {
                try {
                    JsonElement root = JsonParser.parseString(
                            get(host + "/animes?search=" + enc(name) + "&limit=1"));
                    if (root.isJsonArray() && root.getAsJsonArray().size() > 0) {
                        Row r = parseShiki(root.getAsJsonArray().get(0).getAsJsonObject());
                        if (r != null) out.add(r);
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    /** Страница настоящего хентай-каталога Shikimori (жанр 12). */
    private static List<Row> hentai(int page) throws IOException {
        IOException last = null;
        for (String host : SHIKI) {
            try {
                String url = host + "/animes?genre=12&is_censored=false&order=popularity"
                        + "&limit=30&page=" + page;
                JsonElement root = JsonParser.parseString(get(url));
                if (!root.isJsonArray()) throw new IOException("не массив");
                List<Row> out = new ArrayList<>();
                for (JsonElement e : root.getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    Row r = parseShiki(e.getAsJsonObject());
                    if (r != null) out.add(r);
                }
                if (!out.isEmpty()) return out;
                throw new IOException("пусто");
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("Shikimori недоступна");
    }

    private static String fixUrl(String v) {
        if (v == null || v.isEmpty()) return "";
        if (v.startsWith("http")) return v;
        if (v.startsWith("/")) return "https://anilibria.top" + v;
        if (v.startsWith("//")) return "https:" + v;
        return "";
    }

    /** Прямой просмотр: AniLibria API v1 — поиск релиза и HLS-серии. */
    private static Object[] anilibriaDirect(Row r) {
        for (String q : new String[]{r.original, r.title}) {
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
                    Map<Integer, Map<Integer, String>> epQ = new LinkedHashMap<>();
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
                    if (!epQ.isEmpty()) {
                        Track t = DirectHentai.publish(epQ);
                        return new Object[]{"direct", t, null};
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hentai);
        findViewById(R.id.back).setOnClickListener(v -> finish());

        RecyclerView list = findViewById(R.id.list);
        GridLayoutManager lm = new GridLayoutManager(this, 3);
        list.setLayoutManager(lm);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                int last = lm.findLastVisibleItemPosition();
                if (last >= items.size() - 6) loadMore();
            }
        });
        loadMore();
    }

    private void loadMore() {
        if (loading || !hasMore) return;
        loading = true;
        final int want = page;
        AppExecutors.get().run(() -> {
            if (want != 1) return hentai(want);
            List<Row> merged = new ArrayList<>(pinned());
            try {
                for (Row r : hentai(1)) {
                    boolean dup = false;
                    for (Row p : merged) if (p.shikiId == r.shikiId) { dup = true; break; }
                    if (!dup) merged.add(r);
                }
            } catch (IOException ignored) {
                if (merged.isEmpty()) throw ignored;
            }
            return merged;
        }, (rows, error) -> {
            loading = false;
            if (isFinishing()) return;
            if (error != null || rows == null || rows.isEmpty()) {
                hasMore = false;
                if (items.isEmpty()) Ui.toast(this, "Каталог сейчас недоступен");
                return;
            }
            page = want + 1;
            items.addAll(rows);
            adapter.notifyDataSetChanged();
        });
    }

    /** Тап: полная карточка на данных Shikimori (описание, серии, просмотр, скачивание). */
    private void open(Row r) {
        DetailActivity.openShiki(this, r.shikiId, r.title, r.original, r.year, r.poster);
    }

    private class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_hentai, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Row r = items.get(position);
            h.title.setText(r.title);
            Glide.with(h.poster).load(r.poster).placeholder(R.drawable.ph_poster).into(h.poster);
            h.itemView.setOnClickListener(v -> open(r));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static class Holder extends RecyclerView.ViewHolder {
        final ImageView poster;
        final TextView title;

        Holder(@NonNull View v) {
            super(v);
            poster = v.findViewById(R.id.poster);
            title = v.findViewById(R.id.title);
        }
    }
}
