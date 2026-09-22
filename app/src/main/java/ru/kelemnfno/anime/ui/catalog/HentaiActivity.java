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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ru.kelemnfno.anime.R;
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
 * полноразмерные обложки. Просмотр — через общие источники приложения,
 * AniLibria (API v1) подключается как дополнительный источник с настоящими
 * видео, как у обычных тайтлов.
 */
public class HentaiActivity extends AppCompatActivity {

    private static final String[] SHIKI = {"https://shikimori.io/api", "https://shikimori.one/api"};

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
                    JsonObject o = e.getAsJsonObject();
                    Row r = new Row();
                    r.shikiId = o.has("id") ? o.get("id").getAsInt() : 0;
                    String ru = o.has("russian") && o.get("russian").isJsonPrimitive()
                            ? o.get("russian").getAsString() : "";
                    String en = o.has("name") && o.get("name").isJsonPrimitive()
                            ? o.get("name").getAsString() : "";
                    r.title = ru.isEmpty() ? en : ru;
                    r.original = en;
                    r.year = o.has("released_on") && o.get("released_on").isJsonPrimitive()
                            ? yearOf(o.get("released_on").getAsString())
                            : (o.has("aired_on") && o.get("aired_on").isJsonPrimitive()
                            ? yearOf(o.get("aired_on").getAsString()) : 0);
                    if (o.has("image") && o.get("image").isJsonObject()) {
                        JsonObject img = o.getAsJsonObject("image");
                        String p = img.has("original") && img.get("original").isJsonPrimitive()
                                ? img.get("original").getAsString() : "";
                        if (p.isEmpty() && img.has("preview") && img.get("preview").isJsonPrimitive())
                            p = img.get("preview").getAsString();
                        if (!p.isEmpty())
                            r.poster = p.startsWith("http") ? p : "https://shikimori.io" + p;
                    }
                    if (!r.title.isEmpty()) out.add(r);
                }
                if (!out.isEmpty()) return out;
                throw new IOException("пусто");
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("Shikimori недоступна");
    }

    private static int yearOf(String iso) {
        try {
            return iso != null && iso.length() >= 4 ? Integer.parseInt(iso.substring(0, 4)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
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
        AppExecutors.get().run(() -> hentai(want), (rows, error) -> {
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

    /** Тап: подбор источников (включая AniLibria v1 с настоящими видео) и плеер. */
    private void open(Row r) {
        AppExecutors.get().run(() -> {
            Lookup l = new Lookup();
            l.title = r.title;
            l.original = r.original.isEmpty() ? null : r.original;
            l.year = r.year;
            l.shikimoriId = r.shikiId;
            l.genres.add("хентай");
            List<Track> tracks = SourceEngine.tracks(l);
            if (tracks != null && !tracks.isEmpty()) {
                Track t = tracks.get(0);
                return new Object[]{tracks, t, null};
            }
            java.util.Map<String, String> p = new java.util.LinkedHashMap<>();
            p.put("search", r.title);
            p.put("limit", "1");
            List<AnimeItem> found = AnimeRepository.get(this).list(p);
            return new Object[]{null, null, found.isEmpty() ? null : found.get(0)};
        }, (res, error) -> {
            if (isFinishing()) return;
            if (error != null || res == null) {
                Ui.toast(this, "Тайтл не найден в каталоге");
                return;
            }
            @SuppressWarnings("unchecked")
            List<Track> tracks = (List<Track>) res[0];
            Track t = (Track) res[1];
            AnimeItem item = (AnimeItem) res[2];
            if (tracks != null && t != null) {
                PlayerActivity.start(this, r.title, "hentai_" + r.shikiId, r.shikiId, r.poster,
                        t.id, t.firstEpisode(), t.voice, tracks);
            } else if (item != null) {
                DetailActivity.open(this, item.animeUrl);
            } else {
                Ui.toast(this, "Тайтл не найден в каталоге");
            }
        });
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
