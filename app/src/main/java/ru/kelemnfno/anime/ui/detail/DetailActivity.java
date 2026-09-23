package ru.kelemnfno.anime.ui.detail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import ru.kelemnfno.anime.data.model.StreamSource;
import ru.kelemnfno.anime.data.resolver.Net;
import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.db.AppDatabase;
import ru.kelemnfno.anime.data.db.DownloadEntity;
import ru.kelemnfno.anime.data.db.FavoriteEntity;
import ru.kelemnfno.anime.data.db.HistoryEntity;
import ru.kelemnfno.anime.data.model.AnimeFull;
import ru.kelemnfno.anime.data.model.Lookup;
import ru.kelemnfno.anime.data.model.PersonRef;
import ru.kelemnfno.anime.data.model.ScheduleItem;
import ru.kelemnfno.anime.data.model.Track;
import ru.kelemnfno.anime.data.model.ViewingOrderItem;
import ru.kelemnfno.anime.data.prefs.Prefs;
import ru.kelemnfno.anime.data.repo.AnimeRepository;
import ru.kelemnfno.anime.data.shots.ScreenshotFetcher;
import ru.kelemnfno.anime.data.resolver.SourceEngine;
import ru.kelemnfno.anime.databinding.ActivityDetailBinding;
import ru.kelemnfno.anime.databinding.ItemCardBinding;
import ru.kelemnfno.anime.databinding.ItemEpisodeBinding;
import ru.kelemnfno.anime.databinding.ItemScreenshotBinding;
import ru.kelemnfno.anime.ui.AnimeCardAdapter;
import ru.kelemnfno.anime.ui.CardModel;
import ru.kelemnfno.anime.ui.Chips;
import ru.kelemnfno.anime.ui.player.PlayerActivity;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.Countdown;
import ru.kelemnfno.anime.util.Fmt;
import ru.kelemnfno.anime.util.Ui;

/** Карточка аниме: описание, озвучки, серии, скачивание, избранное. */
public class DetailActivity extends AppCompatActivity {

    public static final String EXTRA_SLUG = "slug";
    public static final String EXTRA_EPISODE = "episode";
    public static final String EXTRA_DUBBING = "dubbing";

    private ActivityDetailBinding b;
    private String slug = "";
    private AnimeFull anime;
    private List<Track> tracks = new ArrayList<>();
    private Track currentTrack;
    private final List<Integer> watched = new ArrayList<>();
    private final List<String> downloadedEpisodes = new ArrayList<>();
    private EpisodeAdapter episodeAdapter;
    private long nextEpisodeTs;
    private int nextEpisodeNumber;

    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_POSTER = "poster";
    private static final String EXTRA_SHIKI = "shiki_id";

    public static void open(Context context, String slug) {
        context.startActivity(new Intent(context, DetailActivity.class).putExtra(EXTRA_SLUG, slug));
    }

    /** То же, но с уже известными названием и постером — шапка рисуется мгновенно, без ожидания сети. */
    public static void openWith(Context context, String slug, String title, String poster) {
        context.startActivity(new Intent(context, DetailActivity.class)
                .putExtra(EXTRA_SLUG, slug)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_POSTER, poster));
    }

    /** Карточка AniLibria: всё (описание, обложка, серии) — из API v1 anilibria.top. */
    public static void openAnilib(Context context, int anilibId, String title, String en, int year, String poster) {
        context.startActivity(new Intent(context, DetailActivity.class)
                .putExtra(EXTRA_ANILIB, anilibId)
                .putExtra("anilib_title", title)
                .putExtra("anilib_en", en)
                .putExtra("anilib_year", year)
                .putExtra("anilib_poster", poster));
    }

    /** Карточка для хентай-раздела: данные Shikimori, серии и видео — AniLibria API v1. */
    public static void openShiki(Context context, int shikiId, String ru, String en, int year, String poster) {
        context.startActivity(new Intent(context, DetailActivity.class)
                .putExtra(EXTRA_SHIKI, shikiId)
                .putExtra("shiki_ru", ru)
                .putExtra("shiki_en", en)
                .putExtra("shiki_year", year)
                .putExtra("shiki_poster", poster));
    }

    public static void open(Context context, String slug, String episode, String dubbing) {
        context.startActivity(new Intent(context, DetailActivity.class)
                .putExtra(EXTRA_SLUG, slug)
                .putExtra(EXTRA_EPISODE, episode)
                .putExtra(EXTRA_DUBBING, dubbing));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityDetailBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        slug = getIntent().getStringExtra(EXTRA_SLUG);
        shikiMode = getIntent().getIntExtra(EXTRA_SHIKI, 0) > 0;
        anilibMode = getIntent().getIntExtra(EXTRA_ANILIB, 0) > 0;
        if (anilibMode) {
            String at = getIntent().getStringExtra("anilib_title");
            if (at != null && !at.isEmpty()) b.title.setText(at);
            String ap = getIntent().getStringExtra("anilib_poster");
            if (ap != null && !ap.isEmpty()) Ui.poster(b.poster, ap, 12);
        }
        if (shikiMode) {
            String st = getIntent().getStringExtra("shiki_ru");
            if (st != null && !st.isEmpty()) b.title.setText(st);
            String sp = getIntent().getStringExtra("shiki_poster");
            if (sp != null && !sp.isEmpty()) Ui.poster(b.poster, sp, 12);
        }

        // Пока грузится полная карточка, показываем то, что уже известно из каталога.
        String knownTitle = getIntent().getStringExtra(EXTRA_TITLE);
        String knownPoster = getIntent().getStringExtra(EXTRA_POSTER);
        if (knownTitle != null && !knownTitle.isEmpty()) {
            b.title.setText(knownTitle);
            Ui.poster(b.poster, knownPoster, 12);
        }

        b.back.setOnClickListener(v -> finish());
        b.fav.setOnClickListener(v -> toggleFavorite());
        b.favButton.setOnClickListener(v -> toggleFavorite());

        episodeAdapter = new EpisodeAdapter();
        b.episodes.setLayoutManager(new GridLayoutManager(this, 5));
        Ui.tuneList(b.episodes, false);
        b.episodes.setAdapter(episodeAdapter);
        b.resetProgress.setOnClickListener(v -> {
            AppExecutors.get().io().execute(() -> AppDatabase.get(this).watchedDao().resetSlug(slug));
            Ui.toast(this, "Прогресс сброшен");
        });
        b.descriptionMore.setOnClickListener(v -> {
            boolean expanded = b.description.getMaxLines() != 4;
            b.description.setMaxLines(expanded ? 4 : 200);
            b.descriptionMore.setText(expanded ? R.string.read_more : R.string.collapse);
        });
        b.description.setMaxLines(4);

        load();
        observeWatched();
        observeDownloads();
    }

    private boolean shikiMode;
    private boolean anilibMode;
    private static final String EXTRA_ANILIB = "anilib_id";
    private final java.util.Map<Integer, java.util.Map<Integer, String>> shikiEpQ = new java.util.TreeMap<>();
    private boolean sideLoadsStarted;

    private static boolean changed(ru.kelemnfno.anime.data.model.AnimeFull a,
                                   ru.kelemnfno.anime.data.model.AnimeFull b) {
        int av = a.videos == null ? 0 : a.videos.size();
        int bv = b.videos == null ? 0 : b.videos.size();
        return av != bv || !String.valueOf(a.title).equals(String.valueOf(b.title));
    }

    private void load() {
        if (anilibMode) {
            b.loading.setVisibility(View.VISIBLE);
            AppExecutors.get().run(this::buildAnilibFull, (value, error) -> {
                b.loading.setVisibility(View.GONE);
                if (value != null) show(value);
                else if (anime == null) b.title.setText("Не удалось загрузить");
            });
            return;
        }
        if (shikiMode) {
            b.loading.setVisibility(View.VISIBLE);
            AppExecutors.get().run(this::buildShikiFull, (value, error) -> {
                b.loading.setVisibility(View.GONE);
                if (value != null) show(value);
                else if (anime == null) b.title.setText("Не удалось загрузить");
            });
            return;
        }
        // Карточка из кэша показывается сразу — без ожидания сети.
        ru.kelemnfno.anime.data.model.AnimeFull cached =
                AnimeRepository.get(this).animeCached(slug);
        if (cached != null) show(cached);
        else b.loading.setVisibility(View.VISIBLE);

        AppExecutors.get().run(() -> AnimeRepository.get(this).anime(slug), (value, error) -> {
            b.loading.setVisibility(View.GONE);
            if (error != null || value == null) {
                if (anime == null) {
                    b.title.setText("Не удалось загрузить");
                    Ui.toast(this, error == null ? "Ошибка" : error.getMessage());
                }
                return;
            }
            show(value);
        });
    }

    private static volatile okhttp3.OkHttpClient shikiClient;

    private static okhttp3.OkHttpClient shikiHttp() {
        if (shikiClient == null) {
            synchronized (DetailActivity.class) {
                if (shikiClient == null) {
                    shikiClient = new okhttp3.OkHttpClient.Builder()
                            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .build();
                }
            }
        }
        return shikiClient;
    }

    private static String shikiGet(String url) throws java.io.IOException {
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", ru.kelemnfno.anime.data.resolver.Net.CHROME)
                .build();
        try (okhttp3.Response res = shikiHttp().newCall(req).execute()) {
            if (!res.isSuccessful() || res.body() == null)
                throw new java.io.IOException("HTTP " + res.code());
            return res.body().string();
        }
    }

    /** Полная карточка из Shikimori + серии/видео из AniLibria API v1. */
    /** Полная карточка AniLibria: описание, обложка, серии и HLS — только anilibria.top. */
    private ru.kelemnfno.anime.data.model.AnimeFull buildAnilibFull() {
        int id = getIntent().getIntExtra(EXTRA_ANILIB, 0);
        String title = getIntent().getStringExtra("anilib_title");
        String en = getIntent().getStringExtra("anilib_en");
        int year = getIntent().getIntExtra("anilib_year", 0);
        String posterUrl = getIntent().getStringExtra("anilib_poster");
        ru.kelemnfno.anime.data.model.AnimeFull a = new ru.kelemnfno.anime.data.model.AnimeFull();
        a.animeId = id;
        a.animeUrl = "anilib:" + id;
        a.title = title == null ? "" : title;
        a.original = en;
        a.otherTitles = new java.util.ArrayList<>();
        if (en != null && !en.isEmpty()) a.otherTitles.add(en);
        a.year = year;
        a.poster = new ru.kelemnfno.anime.data.model.Poster();
        a.poster.fullsize = posterUrl;
        a.poster.huge = posterUrl;
        a.poster.mega = posterUrl;
        a.poster.big = posterUrl;
        a.poster.medium = posterUrl;
        a.poster.small = posterUrl;
        a.remoteIds = new ru.kelemnfno.anime.data.model.RemoteIds();
        a.minAge = new ru.kelemnfno.anime.data.model.MinAge();
        a.minAge.value = 18;
        a.minAge.title = "18+";
        a.genres = new java.util.ArrayList<>();
        shikiEpQ.clear();
        try {
            JsonObject o = JsonParser.parseString(shikiGet("https://anilibria.top/api/v1/anime/releases/" + id)).getAsJsonObject();
            if (o.has("data") && o.get("data").isJsonObject()) o = o.getAsJsonObject("data");
            if (o.has("name") && o.get("name").isJsonObject()) {
                JsonObject n = o.getAsJsonObject("name");
                if (a.title.isEmpty() && n.has("main") && n.get("main").isJsonPrimitive())
                    a.title = n.get("main").getAsString();
                if ((en == null || en.isEmpty()) && n.has("english") && n.get("english").isJsonPrimitive()) {
                    a.original = n.get("english").getAsString();
                    if (!a.original.isEmpty()) a.otherTitles.add(a.original);
                }
            }
            if (a.year <= 0 && o.has("year") && o.get("year").isJsonPrimitive()) a.year = o.get("year").getAsInt();
            if (o.has("description") && o.get("description").isJsonPrimitive())
                a.description = o.get("description").getAsString();
            if (o.has("type") && o.get("type").isJsonObject()) {
                JsonObject t = o.getAsJsonObject("type");
                a.type = new ru.kelemnfno.anime.data.model.AnimeType();
                a.type.name = t.has("description") && t.get("description").isJsonPrimitive()
                        ? t.get("description").getAsString() : "";
            }
            if (o.has("genres") && o.get("genres").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("genres")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject g = e.getAsJsonObject();
                    if (g.has("name") && g.get("name").isJsonPrimitive()) {
                        ru.kelemnfno.anime.data.model.GenreShort gs = new ru.kelemnfno.anime.data.model.GenreShort();
                        gs.title = g.get("name").getAsString();
                        a.genres.add(gs);
                    }
                }
            }
            if (o.has("shikimori") && o.get("shikimori").isJsonObject()) {
                JsonObject sh = o.getAsJsonObject("shikimori");
                if (sh.has("rating") && sh.get("rating").isJsonPrimitive() && !sh.get("rating").isJsonNull()
                        && sh.get("rating").getAsDouble() > 0) {
                    a.rating = new ru.kelemnfno.anime.data.model.Rating();
                    a.rating.shikimoriRating = sh.get("rating").getAsDouble();
                    a.rating.average = sh.get("rating").getAsDouble();
                }
                if (sh.has("id") && sh.get("id").isJsonPrimitive() && !sh.get("id").isJsonNull())
                    a.remoteIds.shikimoriId = sh.get("id").getAsInt();
            }
            if ((posterUrl == null || posterUrl.isEmpty()) && o.has("poster") && o.get("poster").isJsonObject()) {
                JsonObject ps = o.getAsJsonObject("poster");
                String src = ps.has("src") && ps.get("src").isJsonPrimitive() ? ps.get("src").getAsString() : "";
                if (!src.isEmpty()) {
                    if (!src.startsWith("http")) src = "https://anilibria.top" + src;
                    a.poster.fullsize = src;
                    a.poster.huge = src;
                    a.poster.mega = src;
                    a.poster.big = src;
                    a.poster.medium = src;
                    a.poster.small = src;
                }
            }
            if (o.has("episodes") && o.get("episodes").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("episodes")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject ep = e.getAsJsonObject();
                    int ord = ep.has("ordinal") && ep.get("ordinal").isJsonPrimitive()
                            ? (int) ep.get("ordinal").getAsDouble() : 0;
                    if (ord <= 0) continue;
                    java.util.Map<Integer, String> qmap = new java.util.LinkedHashMap<>();
                    String[][] keys = {{"480", "hls_480"}, {"720", "hls_720"}, {"1080", "hls_1080"},
                            {"1440", "hls_1440"}, {"2160", "hls_2160"}};
                    for (String[] kv : keys) {
                        String u = ep.has(kv[1]) && ep.get(kv[1]).isJsonPrimitive()
                                ? ep.get(kv[1]).getAsString() : "";
                        if (u.isEmpty()) continue;
                        if (!u.startsWith("http")) {
                            // Видео AniLibria живёт на cache.libria.fun — не на anilibria.top!
                            u = u.startsWith("/videos/") ? "https://cache.libria.fun" + u
                                    : (u.startsWith("/") ? "https://anilibria.top" + u : "");
                        }
                        if (!u.isEmpty()) qmap.put(Integer.parseInt(kv[0]), u);
                    }
                    if (!qmap.isEmpty()) shikiEpQ.put(ord, qmap);
                }
            }
        } catch (Exception ignored) {
        }
        a.videos = new java.util.ArrayList<>();
        for (int ord : shikiEpQ.keySet()) {
            ru.kelemnfno.anime.data.model.VideoItem v = new ru.kelemnfno.anime.data.model.VideoItem();
            v.number = String.valueOf(ord);
            a.videos.add(v);
        }
        return a;
    }

    private ru.kelemnfno.anime.data.model.AnimeFull buildShikiFull() {
        int id = getIntent().getIntExtra(EXTRA_SHIKI, 0);
        String ru = getIntent().getStringExtra("shiki_ru");
        String en = getIntent().getStringExtra("shiki_en");
        int year = getIntent().getIntExtra("shiki_year", 0);
        String posterUrl = getIntent().getStringExtra("shiki_poster");
        ru.kelemnfno.anime.data.model.AnimeFull a = new ru.kelemnfno.anime.data.model.AnimeFull();
        a.animeId = id;
        a.animeUrl = "shiki:" + id;
        a.title = ru == null || ru.isEmpty() ? en : ru;
        a.original = en;
        a.otherTitles = new java.util.ArrayList<>();
        if (en != null && !en.isEmpty()) a.otherTitles.add(en);
        a.year = year;
        a.poster = new ru.kelemnfno.anime.data.model.Poster();
        a.poster.fullsize = posterUrl;
        a.poster.huge = posterUrl;
        a.poster.mega = posterUrl;
        a.poster.big = posterUrl;
        a.poster.medium = posterUrl;
        a.poster.small = posterUrl;
        a.remoteIds = new ru.kelemnfno.anime.data.model.RemoteIds();
        a.remoteIds.shikimoriId = id;
        a.minAge = new ru.kelemnfno.anime.data.model.MinAge();
        a.minAge.value = 18;
        a.minAge.title = "18+";
        a.genres = new java.util.ArrayList<>();
        try {
            JsonObject o = JsonParser.parseString(shikiGet("https://shikimori.io/api/animes/" + id)).getAsJsonObject();
            if (o.has("score") && o.get("score").isJsonPrimitive() && o.get("score").getAsDouble() > 0) {
                a.rating = new ru.kelemnfno.anime.data.model.Rating();
                a.rating.shikimoriRating = o.get("score").getAsDouble();
                a.rating.average = o.get("score").getAsDouble();
            }
            if (o.has("description") && o.get("description").isJsonPrimitive())
                a.description = o.get("description").getAsString();
            if (o.has("kind") && o.get("kind").isJsonPrimitive()) {
                a.type = new ru.kelemnfno.anime.data.model.AnimeType();
                a.type.name = o.get("kind").getAsString();
            }
            if (o.has("status") && o.get("status").isJsonPrimitive()) {
                a.animeStatus = new ru.kelemnfno.anime.data.model.AnimeStatus();
                a.animeStatus.title = o.get("status").getAsString();
            }
            if (year <= 0 && o.has("aired_on") && o.get("aired_on").isJsonPrimitive()) {
                String iso = o.get("aired_on").getAsString();
                if (iso.length() >= 4) try { a.year = Integer.parseInt(iso.substring(0, 4)); } catch (Exception ignored) { }
            }
            if (o.has("genres") && o.get("genres").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("genres")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject g = e.getAsJsonObject();
                    String name = g.has("russian") && g.get("russian").isJsonPrimitive()
                            ? g.get("russian").getAsString()
                            : (g.has("name") && g.get("name").isJsonPrimitive() ? g.get("name").getAsString() : "");
                    if (!name.isEmpty()) {
                        ru.kelemnfno.anime.data.model.GenreShort gs = new ru.kelemnfno.anime.data.model.GenreShort();
                        gs.title = name;
                        a.genres.add(gs);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // Серии и потоки — AniLibria API v1.
        shikiEpQ.clear();
        for (String q : new String[]{en, ru}) {
            if (q == null || q.isEmpty()) continue;
            try {
                JsonElement se = JsonParser.parseString(shikiGet(
                        "https://anilibria.top/api/v1/app/search/releases?query="
                        + Net.enc(q) + "&limit=6"));
                JsonArray arr = null;
                if (se.isJsonArray()) arr = se.getAsJsonArray();
                else if (se.isJsonObject() && se.getAsJsonObject().has("data"))
                    arr = se.getAsJsonObject().getAsJsonArray("data");
                if (arr == null) continue;
                JsonObject bestRel = null;
                String ql = q == null ? "" : q.toLowerCase();
                for (JsonElement rel : arr) {
                    if (!rel.isJsonObject()) continue;
                    JsonObject ro = rel.getAsJsonObject();
                    if (!ro.has("id") || ro.get("id").getAsInt() <= 0) continue;
                    if (bestRel == null) bestRel = ro;
                    if (!ql.isEmpty() && ro.has("name") && ro.get("name").isJsonObject()) {
                        JsonObject nm = ro.getAsJsonObject("name");
                        String main = nm.has("main") && nm.get("main").isJsonPrimitive()
                                ? nm.get("main").getAsString().toLowerCase() : "";
                        String eng = nm.has("english") && nm.get("english").isJsonPrimitive()
                                ? nm.get("english").getAsString().toLowerCase() : "";
                        if ((!main.isEmpty() && (main.contains(ql) || ql.contains(main)))
                                || (!eng.isEmpty() && (eng.contains(ql) || ql.contains(eng)))) {
                            bestRel = ro;
                            break;
                        }
                    }
                }
                if (bestRel == null) continue;
                for (int attempt = 0; attempt < arr.size(); attempt++) {
                    JsonObject cand = attempt == 0 ? bestRel
                            : (arr.get(attempt).isJsonObject() ? arr.get(attempt).getAsJsonObject() : null);
                    if (cand == null || !cand.has("id") || cand.get("id").getAsInt() <= 0) continue;
                    int rid = cand.get("id").getAsInt();
                    JsonObject full = JsonParser.parseString(
                            shikiGet("https://anilibria.top/api/v1/anime/releases/" + rid)).getAsJsonObject();
                    if (!full.has("episodes") || !full.get("episodes").isJsonArray()) continue;
                    for (JsonElement e : full.getAsJsonArray("episodes")) {
                        if (!e.isJsonObject()) continue;
                        JsonObject ep = e.getAsJsonObject();
                        int ord = ep.has("ordinal") ? (int) ep.get("ordinal").getAsDouble() : 0;
                        if (ord <= 0) continue;
                        java.util.Map<Integer, String> qmap = new java.util.LinkedHashMap<>();
                        String[][] keys = {{"480", "hls_480"}, {"720", "hls_720"}, {"1080", "hls_1080"},
                                {"1440", "hls_1440"}, {"2160", "hls_2160"}};
                        for (String[] kv : keys) {
                            String u = ep.has(kv[1]) && ep.get(kv[1]).isJsonPrimitive()
                                    ? ep.get(kv[1]).getAsString() : "";
                            if (!u.isEmpty()) {
                                if (!u.startsWith("http")) u = u.startsWith("/videos/") ? "https://cache.libria.fun" + u
                                        : (u.startsWith("/") ? "https://anilibria.top" + u : "");
                            }
                            if (!u.isEmpty()) qmap.put(Integer.parseInt(kv[0]), u);
                        }
                        if (!qmap.isEmpty()) shikiEpQ.put(ord, qmap);
                    }
                    if (!shikiEpQ.isEmpty()) break;
                }
            } catch (Exception ignored) {
            }
            if (!shikiEpQ.isEmpty()) break;
        }
        a.videos = new java.util.ArrayList<>();
        for (int ord : shikiEpQ.keySet()) {
            ru.kelemnfno.anime.data.model.VideoItem v = new ru.kelemnfno.anime.data.model.VideoItem();
            v.number = String.valueOf(ord);
            a.videos.add(v);
        }
        return a;
    }

    /** Рисует карточку; тяжёлые запросы запускаются один раз. */
    private void show(ru.kelemnfno.anime.data.model.AnimeFull value) {
        ru.kelemnfno.anime.data.model.AnimeFull previous = anime;
        anime = value;
        // Перерисовываем только если данные правда изменились — иначе экран мигает.
        if (previous == null || changed(previous, value)) render();
        if (sideLoadsStarted) return;
        sideLoadsStarted = true;
        loadTracks();
        loadNextEpisode();
        loadScreenshots();
    }

    private void render() {
        Ui.image(b.backdrop, Fmt.posterUrl(anime, "fullsize"));
        Ui.poster(b.poster, Fmt.posterUrl(anime, "huge"), 12);
        b.title.setText(anime.title);

        List<String> meta = new ArrayList<>();
        if (anime.year > 0) {
            meta.add(anime.year + (anime.season > 0 && anime.season < Fmt.SEASONS.length
                    ? ", " + Fmt.SEASONS[anime.season] : ""));
        }
        if (anime.type != null && anime.type.name != null) meta.add(anime.type.name);
        if (anime.minAge != null && anime.minAge.title != null && !"Unknown".equals(anime.minAge.title)) {
            meta.add(anime.minAge.title);
        }
        int quickCount = quickEpisodes().size();
        if (quickCount > 0) meta.add(quickCount + " сер.");
        b.meta.setText(Fmt.join(meta, " · "));

        double rating = anime.rating == null ? 0 : anime.rating.average;
        b.ratingValue.setText(rating > 0 ? String.format(Locale.US, "%.2f", rating) : "—");
        b.ratingCounters.setText((anime.rating == null ? 0 : anime.rating.counters) + " оценок");
        List<String> external = new ArrayList<>();
        if (anime.rating != null) {
            if (anime.rating.shikimoriRating > 0) {
                external.add("Shikimori " + String.format(Locale.US, "%.2f", anime.rating.shikimoriRating));
            }
            if (anime.rating.malRating > 0) {
                external.add("MAL " + String.format(Locale.US, "%.2f", anime.rating.malRating));
            }
            if (anime.rating.kpRating > 0) {
                external.add("КП " + String.format(Locale.US, "%.1f", anime.rating.kpRating));
            }
        }
        b.ratingExternal.setText(Fmt.join(external, "\n"));

        String desc = Fmt.cleanDescription(anime.description);
        b.description.setText(desc.isEmpty() ? "Описание отсутствует." : desc);
        b.descriptionMore.setVisibility(desc.length() > 320 ? View.VISIBLE : View.GONE);

        if (anime.otherTitles != null && anime.otherTitles.size() > 1) {
            b.otherTitles.setVisibility(View.VISIBLE);
            b.otherTitles.setText("Другие названия: " + Fmt.join(anime.otherTitles, " · "));
        } else {
            b.otherTitles.setVisibility(View.GONE);
        }

        renderGenres();
        renderQuickEpisodes();
        setupInlinePlayer();
        renderInfo();
        renderFavoriteState();
        renderViewingOrder();

        b.watch.setOnClickListener(v -> {
            if (currentTrack == null || currentTrack.episodes.isEmpty()) {
                // без лишних надписей
                return;
            }
            // чтение истории — вне главного потока
            AppExecutors.get().run(this::resumeEpisode, (ep, error) -> {
                if (b == null || isFinishing() || currentTrack == null) return;
                play(currentTrack, ep == null ? currentTrack.firstEpisode() : ep);
            });
        });
    }

    private int resumeEpisode() {
        HistoryEntity h = AppDatabase.get(this).historyDao().bySlug(slug);
        if (h != null && h.episode != null && !h.episode.isEmpty()) {
            int ep = Fmt.numberIn(h.episode, 1);
            if (currentTrack.hasEpisode(ep)) return ep;
        }
        return currentTrack.episodes.isEmpty() ? 1 : currentTrack.episodes.get(0);
    }

    private void renderGenres() {
        b.genres.removeAllViews();
        if (anime.genres == null) return;
        for (ru.kelemnfno.anime.data.model.GenreShort g : anime.genres) {
            Chips.add(b.genres, g.title, false, v -> Ui.toast(this, g.title));
        }
    }

    private void renderInfo() {
        b.info.removeAllViews();
        row("Тип", anime.type == null ? "" : anime.type.name);
        row("Статус", anime.animeStatus == null ? "" : anime.animeStatus.title);
        row("Год", anime.year > 0 ? String.valueOf(anime.year) : "");
        row("Серий", anime.videos == null || anime.videos.isEmpty() ? "" : String.valueOf(anime.videos.size()));
        row("Длительность", Fmt.formatDuration(anime.duration));
        row("Возраст", anime.minAge == null ? "" : anime.minAge.title);
        row("Первоисточник", anime.original);
        row("Студия", names(anime.studios));
        row("Режиссёр", names(anime.creators));
        row("Просмотров", Fmt.formatViews(anime.views));
        if (anime.top != null && anime.top.global > 0) row("Место в топе", "#" + anime.top.global);
    }

    private String names(List<PersonRef> list) {
        if (list == null || list.isEmpty()) return "";
        List<String> out = new ArrayList<>();
        for (PersonRef p : list) out.add(p.title);
        return Fmt.join(out, ", ");
    }

    private void row(String label, String value) {
        if (value == null || value.isEmpty()) return;
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(getColor(R.color.text_mute));
        l.setTextSize(13);
        r.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(getColor(R.color.text));
        v.setTextSize(13);
        v.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_END);
        r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));
        b.info.addView(r);
    }

    /* ---------------- Озвучки и серии ---------------- */

    private void loadTracks() {
        b.episodesHint.setText("");
        final Lookup lookup = lookupOf(anime);
        AppExecutors.get().heavy().execute(() -> {
            List<Track> result;
            try {
                result = SourceEngine.tracks(lookup);
            } catch (Throwable t) {
                result = new ArrayList<>();
            }
            if ((shikiMode || anilibMode) && !shikiEpQ.isEmpty()) {
                try {
                    result.add(0, ru.kelemnfno.anime.data.api.DirectHentai.publish(shikiEpQ));
                } catch (Throwable ignored) {
                }
            }
            final List<Track> tracks = result;
            AppExecutors.get().post(() -> {
                if (b == null || isFinishing()) return;
                this.tracks = tracks;
                renderVoices();
            });
        });
    }

    /** Выбор озвучки и качества для конкретной серии. */
    private void openDownloadSheet(int episode) {
        if (currentTrack == null || tracks.isEmpty()) {
            Ui.toast(this, "Озвучки ещё подбираются");
            return;
        }
        DownloadSheet.show(this, anime, tracks, currentTrack, episode);
    }

    private Lookup lookupOf(AnimeFull a) {
        Lookup l = new Lookup();
        l.title = a.title;
        l.original = a.otherTitles != null && !a.otherTitles.isEmpty() ? a.otherTitles.get(0) : a.original;
        l.year = a.year;
        l.sourceId = a.animeId;
        if (a.remoteIds != null) {
            l.shikimoriId = a.remoteIds.shikimoriId;
            l.malId = a.remoteIds.malId;
            l.kpId = a.remoteIds.kpId;
            l.extAlias = a.remoteIds.extAlias;
        }
        if (a.genres != null) {
            for (ru.kelemnfno.anime.data.model.GenreShort g : a.genres) l.genres.add(g.title);
        }
        return l;
    }

    /**
     * Список серий из ответа API — виден сразу, без ожидания подбора источников.
     * Когда озвучки подобраны, renderVoices() уточнит список по выбранной дорожке.
     */
    /** Уникальные номера серий из видео-списка источника (без дублей по озвучкам). */
    private List<Integer> quickEpisodes() {
        List<Integer> eps = new ArrayList<>();
        if (anime == null || anime.videos == null) return eps;
        for (ru.kelemnfno.anime.data.model.VideoItem v : anime.videos) {
            int n = Fmt.numberIn(v.number, 0);
            if (n > 0 && !eps.contains(n)) eps.add(n);
        }
        java.util.Collections.sort(eps);
        return eps;
    }

    private void renderQuickEpisodes() {
        List<Integer> eps = quickEpisodes();
        if (eps.isEmpty()) return;
        b.episodesBlock.setVisibility(View.VISIBLE);
        b.episodesHint.setText(String.valueOf(eps.size()));
        episodeAdapter.submit(eps);
    }

    private void renderVoices() {
        b.voices.removeAllViews();
        if (tracks.isEmpty()) {
            b.episodesHint.setText(R.string.no_sources);
            episodeAdapter.submit(new ArrayList<>());
            return;
        }
        String preferred = Prefs.get(this).settings().preferredDub;
        String savedDub = getIntent().getStringExtra(EXTRA_DUBBING);
        Track chosen = null;
        for (Track t : tracks) {
            if (savedDub != null && t.voice.equalsIgnoreCase(savedDub)) chosen = t;
            else if (chosen == null && !preferred.isEmpty() && t.voice.equalsIgnoreCase(preferred)) chosen = t;
        }
        if (chosen == null) chosen = tracks.get(0);

        for (Track t : tracks) {
            final Track track = t;
            Chips.add(b.voices, t.voice + " · " + t.episodes.size(), t == chosen, v -> {
                currentTrack = track;
                renderVoices();
                if (inlineStarted) playInline(inlineEpisode);
            });
        }
        currentTrack = chosen;
        List<Integer> episodes = new ArrayList<>(chosen.episodes);
        java.util.Collections.sort(episodes);
        b.episodesHint.setText(chosen.voice + " · " + episodes.size() + " серий"
                + (chosen.maxQuality > 0 ? " · до " + chosen.maxQuality + "p" : ""));
        episodeAdapter.submit(episodes);
    }

    private void play(Track track, int episode) {
        saveHistory(String.valueOf(episode));
        PlayerActivity.start(this, anime.title, slug, anime.animeId, Fmt.posterUrl(anime, "big"),
                track.id, episode, track.voice, tracks);
    }

    private void saveHistory(final String episode) {
        if (anime == null) return;
        final HistoryEntity entity = new HistoryEntity();
        entity.slug = slug;
        entity.animeId = anime.animeId;
        entity.title = anime.title;
        entity.poster = Fmt.posterUrl(anime, "big");
        entity.episode = episode;
        entity.dubbing = currentTrack == null ? "" : currentTrack.voice;
        entity.total = currentTrack == null ? 0 : currentTrack.episodes.size();
        entity.updatedAt = System.currentTimeMillis();
        AppExecutors.get().io().execute(() -> {
            HistoryEntity h = AppDatabase.get(this).historyDao().bySlug(slug);
            entity.positionMs = h == null ? 0 : h.positionMs;
            entity.durationMs = h == null ? 0 : h.durationMs;
            AppDatabase.get(this).historyDao().upsert(entity);
        });
    }

    /* ---------------- Избранное ---------------- */

    private void renderFavoriteState() {
        AppExecutors.get().run(() -> AppDatabase.get(this).favoriteDao().contains(slug), (value, error) -> {
            if (b == null || isFinishing()) return;
            boolean fav = value != null && value;
            b.fav.setImageResource(fav ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
            b.fav.setImageTintList(android.content.res.ColorStateList.valueOf(
                    getColor(fav ? R.color.rose : R.color.text)));
            b.favButton.setText(fav ? R.string.in_favorites : R.string.to_favorites);
            // Кнопку целиком не закрашиваем — цвет меняет только значок.
            b.favButton.setBackgroundResource(R.drawable.bg_btn_secondary);
            b.favButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    fav ? R.drawable.ic_heart_filled : R.drawable.ic_heart, 0, 0, 0);
            b.favButton.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(
                    getColor(fav ? R.color.rose : R.color.text_mute)));
            Ui.pop(b.fav);
        });
    }

    private void toggleFavorite() {
        if (anime == null) return;
        final FavoriteEntity draft = new FavoriteEntity();
        draft.slug = slug != null ? slug : (anime.animeUrl == null ? "" : anime.animeUrl);
        draft.animeId = anime.animeId;
        draft.title = anime.title;
        draft.poster = Fmt.posterUrl(anime, "big");
        draft.year = anime.year;
        draft.type = (anime.type == null || anime.type.shortname == null) ? "" : anime.type.shortname;
        draft.addedAt = System.currentTimeMillis();
        draft.episodeCount = quickEpisodes().size();
        draft.dubbing = currentTrack == null ? "" : currentTrack.voice;
        draft.status = (anime.animeStatus == null || anime.animeStatus.alias == null) ? "" : anime.animeStatus.alias;
        draft.nextDate = nextEpisodeTs;
        AppExecutors.get().run(() -> AppDatabase.get(this).favoriteDao().bySlug(slug), (existing, error) -> {
            if (b == null || isFinishing()) return;
            if (existing != null) {
                AppExecutors.get().io().execute(() -> AppDatabase.get(this).favoriteDao().deleteBySlug(slug));
                Ui.toast(this, "Удалено из избранного");
            } else {
                AppExecutors.get().io().execute(() -> AppDatabase.get(this).favoriteDao().upsert(draft));
                Ui.toast(this, "Добавлено — пришлём уведомление о новой серии");
            }
            renderFavoriteState();
        });
    }

    /* ---------------- Следующая серия ---------------- */

    private void loadNextEpisode() {
        if (anime.animeStatus == null || !anime.animeStatus.isOngoing()) {
            b.countdown.setVisibility(View.GONE);
            return;
        }
        AppExecutors.get().run(() -> {
            ScheduleItem s0 = null;
            for (ScheduleItem s : AnimeRepository.get(this).schedule()) {
                if (s.animeId == anime.animeId && s.episodes != null) {
                    s0 = s;
                    break;
                }
            }
            int shiki = anime.remoteIds == null ? 0 : anime.remoteIds.shikimoriId;
            int[] st = ru.kelemnfno.anime.data.shots.ScreenshotFetcher.airedStatus(shiki);
            return new Object[]{s0, st};
        }, (value, error) -> {
            if (b == null || value == null) {
                b.countdown.setVisibility(View.GONE);
                return;
            }
            ScheduleItem s0 = (ScheduleItem) value[0];
            int[] st = (int[]) value[1];
            long nextTs = s0 != null ? s0.episodes.nextDateMs() : 0;
            int real = quickEpisodes().size();
            int aired;
            int total;
            if (st != null && st[0] > 0) {
                // Shikimori — источник правды: сколько серий реально вышло.
                aired = st[0];
                total = Math.max(st[1], Math.max(st[0], real));
            } else {
                aired = real > 0 ? real : (s0 != null ? s0.episodes.safeAired() : 0);
                total = Math.max(s0 != null ? s0.episodes.count : 0, real);
            }
            if (aired <= 0) {
                b.countdown.setVisibility(View.GONE);
                return;
            }
            b.countdown.setVisibility(View.VISIBLE);
            if (total > 0 && aired >= total) {
                // Сезон завершён: не обещаем новую серию.
                nextEpisodeTs = 0;
                b.countdown.setText("Вышли все " + total + " сер.");
                return;
            }
            nextEpisodeTs = nextTs;
            nextEpisodeNumber = aired + 1;
            String when = nextTs > System.currentTimeMillis()
                    ? Countdown.format(nextTs) + " · " + Countdown.dateTime(nextTs)
                    : "дата уточняется";
            b.countdown.setText("Серия " + nextEpisodeNumber
                    + (total > aired ? " из " + total : "")
                    + " — " + when);
        });
    }

    /* ---------------- Скриншоты и порядок просмотра ---------------- */

    private void loadScreenshots() {
        int shiki = anime.remoteIds == null ? 0 : anime.remoteIds.shikimoriId;
        int mal = anime.remoteIds == null ? 0 : anime.remoteIds.malId;
        String name = anime.title;
        AppExecutors.get().run(() -> ScreenshotFetcher.fetch(shiki, mal, name), (value, error) -> {
            if (b == null || value == null || value.isEmpty()) return;
            b.screenshotsBlock.setVisibility(View.VISIBLE);
            b.screenshots.setLayoutManager(
                    new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            b.screenshots.addItemDecoration(new ru.kelemnfno.anime.ui.CardSpacing(this, 0));
            List<String> six = value.size() > 6 ? new ArrayList<>(value.subList(0, 6)) : value;
            b.screenshots.setAdapter(new ScreenshotAdapter(six));
        });
    }

    private void renderViewingOrder() {
        List<ViewingOrderItem> order = anime.viewingOrder;
        if (order == null || order.size() < 2) return;
        b.viewingOrder.getRoot().setVisibility(View.VISIBLE);
        b.viewingOrder.sectionTitle.setText(R.string.viewing_order);
        b.viewingOrder.sectionSubtitle.setText("Связанные тайтлы франшизы");
        b.viewingOrder.sectionMore.setVisibility(View.GONE);
        List<CardModel> models = new ArrayList<>();
        for (ViewingOrderItem v : order) {
            CardModel m = new CardModel(v.animeUrl, v.title, Fmt.posterUrl(v.poster, "big"));
            m.animeId = v.animeId;
            m.rating = v.rating;
            m.subtitle = (v.year > 0 ? v.year + " · " : "") + (v.data == null ? "" : v.data.text);
            m.badge = v.animeId == anime.animeId ? "сейчас" : null;
            m.ongoing = v.animeStatus != null && v.animeStatus.isOngoing();
            models.add(m);
        }
        AnimeCardAdapter adapter = new AnimeCardAdapter(118);
        adapter.setListener(new AnimeCardAdapter.OnCardClick() {
            @Override
            public void onClick(CardModel model) {
                open(DetailActivity.this, model.slug);
            }

            @Override
            public void onLongClick(CardModel model) {
            }
        });
        adapter.submit(models);
        b.viewingOrder.sectionList.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        b.viewingOrder.sectionList.addItemDecoration(new ru.kelemnfno.anime.ui.CardSpacing(this, 0));
        b.viewingOrder.sectionList.setAdapter(adapter);
    }

    /* ---------------- Наблюдение за БД ---------------- */

    private void observeWatched() {
        LiveData<List<String>> live = AppDatabase.get(this).watchedDao().observeBySlug(slug);
        live.observe(this, list -> {
            watched.clear();
            if (list != null) {
                for (String s : list) watched.add(Fmt.numberIn(s, -1));
            }
            if (episodeAdapter != null) episodeAdapter.notifyDataSetChanged();
        });
    }

    private void observeDownloads() {
        AppDatabase.get(this).downloadDao().observeAll().observe(this, rows -> {
            downloadedEpisodes.clear();
            if (rows != null) {
                String eff = slug != null ? slug : (anime != null ? anime.animeUrl : null);
                for (DownloadEntity d : rows) {
                    if (d.status == DownloadEntity.DONE && eff != null && eff.equals(d.slug))
                        downloadedEpisodes.add(d.episode);
                }
            }
            if (episodeAdapter != null) episodeAdapter.notifyDataSetChanged();
        });
    }

    /* ---------------- Адаптеры ---------------- */

    private class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.Holder> {
        private final List<Integer> episodes = new ArrayList<>();

        void submit(List<Integer> values) {
            episodes.clear();
            episodes.addAll(values);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemEpisodeBinding.inflate(getLayoutInflater(), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(episodes.get(position));
        }

        @Override
        public int getItemCount() {
            return episodes.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            private final ItemEpisodeBinding b;

            Holder(ItemEpisodeBinding binding) {
                super(binding.getRoot());
                this.b = binding;
            }

            void bind(int episode) {
                b.number.setText(String.valueOf(episode));
                boolean isWatched = watched.contains(episode);
                b.watched.setVisibility(isWatched ? View.VISIBLE : View.GONE);
                b.state.setText(isWatched ? "просмотрено"
                        : downloadedEpisodes.contains(String.valueOf(episode)) ? "скачано" : "");
                boolean saved = downloadedEpisodes.contains(String.valueOf(episode));
                b.downloaded.setVisibility(View.VISIBLE);
                b.downloaded.setImageTintList(android.content.res.ColorStateList.valueOf(
                        getColor(saved ? R.color.emerald : R.color.text_mute)));
                b.downloaded.setAlpha(saved ? 1f : 0.7f);
                b.downloaded.setOnClickListener(v -> openDownloadSheet(episode));
                boolean playing = inlineStarted && episode == inlineEpisode;
                b.getRoot().setAlpha(!playing && isWatched ? 0.55f : 1f);
                b.number.setTextColor(getColor(playing ? R.color.accent : R.color.text));
                b.getRoot().setOnClickListener(v -> playInline(episode));
                b.getRoot().setOnLongClickListener(v -> {
                    if (currentTrack != null) {
                        DownloadSheet.show(DetailActivity.this, anime, tracks, currentTrack, episode);
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    /* ---------------- Встроенный плеер (как на сайте, прямо в карточке тайтла) ---------------- */

    private ExoPlayer inlinePlayer;
    private String inlineReferer = "";
    private int inlineEpisode = 1;
    private boolean inlineStarted;

    private void setupInlinePlayer() {
        b.playerBlock.setVisibility(View.VISIBLE);
        Ui.image(b.inlinePoster, Fmt.posterUrl(anime, "fullsize"));
        b.inlinePlay.setOnClickListener(v -> playInline(defaultEpisode()));
        b.inlinePrev.setOnClickListener(v -> stepEpisode(-1));
        b.inlineNext.setOnClickListener(v -> stepEpisode(1));
        b.inlineEpLabel.setText(episodeLabel(defaultEpisode()));
        b.inlineFullscreen.setOnClickListener(v -> {
            if (currentTrack == null) {
                // тихо
                return;
            }
            play(currentTrack, inlineStarted ? inlineEpisode : defaultEpisode());
        });
    }

    /** Предыдущая/следующая серия в пределах выбранной озвучки. */
    private void stepEpisode(int delta) {
        if (currentTrack == null || currentTrack.episodes.isEmpty()) {
            // тихо
            return;
        }
        List<Integer> eps = new ArrayList<>(currentTrack.episodes);
        java.util.Collections.sort(eps);
        int i = eps.indexOf(inlineEpisode);
        if (i < 0) i = 0;
        else i = Math.max(0, Math.min(eps.size() - 1, i + delta));
        playInline(eps.get(i));
    }

    private String episodeLabel(int episode) {
        return "Серия " + episode
                + (currentTrack == null || currentTrack.voice == null || currentTrack.voice.isEmpty()
                ? "" : " · " + currentTrack.voice);
    }

    private int defaultEpisode() {
        if (currentTrack == null || currentTrack.episodes.isEmpty()) return 1;
        List<Integer> eps = new ArrayList<>(currentTrack.episodes);
        java.util.Collections.sort(eps);
        return eps.get(0);
    }

    /**
     * Воспроизведение всегда в полноэкранном плеере — он один на всё
     * приложение, с жестами, сменой озвучки и серий.
     */
    private void playInline(final int episode) {
        if (currentTrack == null) {
            // тихо
            return;
        }
        ru.kelemnfno.anime.ui.player.PlayerActivity.start(this, anime.title, slug, anime.animeId,
                Fmt.posterUrl(anime, "big"), currentTrack.id, episode, currentTrack.voice, tracks);
    }

    private String originOf(String referer) {
        int i = referer.indexOf('/', 8);
        return i > 8 ? referer.substring(0, i) : referer;
    }

    private void releaseInline() {
        if (inlinePlayer != null) {
            inlinePlayer.release();
            inlinePlayer = null;
        }
        inlineReferer = "";
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (inlinePlayer != null) inlinePlayer.pause();
    }

    @Override
    protected void onDestroy() {
        releaseInline();
        super.onDestroy();
    }

    private class ScreenshotAdapter extends RecyclerView.Adapter<ScreenshotAdapter.Holder> {
        private final List<String> urls;

        ScreenshotAdapter(List<String> urls) {
            this.urls = urls;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemScreenshotBinding.inflate(getLayoutInflater(), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            String url = urls.get(position);
            Ui.image(holder.b.image, url);
            holder.b.getRoot().setOnClickListener(v -> showShot(url));
        }

        @Override
        public int getItemCount() {
            return urls.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            private final ItemScreenshotBinding b;

            Holder(ItemScreenshotBinding binding) {
                super(binding.getRoot());
                this.b = binding;
            }
        }
    }

    /** Кадр на весь экран — тап по картинке или по фону закрывает. */
    private void showShot(String url) {
        ImageView full = new ImageView(this);
        full.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        full.setScaleType(ImageView.ScaleType.FIT_CENTER);
        full.setBackgroundColor(0xFF000000);
        Ui.image(full, url);
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(full);
        full.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (anime != null) renderFavoriteState();
    }
}
