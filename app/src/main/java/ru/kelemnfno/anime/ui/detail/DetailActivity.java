package ru.kelemnfno.anime.ui.detail;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
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
import ru.kelemnfno.anime.data.tsuyu.TsuyuEngine;
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

    public static void open(Context context, String slug) {
        context.startActivity(new Intent(context, DetailActivity.class).putExtra(EXTRA_SLUG, slug));
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

        b.back.setOnClickListener(v -> finish());
        b.share.setOnClickListener(v -> share());
        b.openSite.setOnClickListener(v -> Ui.openUrl(this, "https://yani.tv/anime/" + slug));
        b.fav.setOnClickListener(v -> toggleFavorite());
        b.favButton.setOnClickListener(v -> toggleFavorite());

        episodeAdapter = new EpisodeAdapter();
        b.episodes.setLayoutManager(new GridLayoutManager(this, 6));
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

    private void load() {
        b.loading.setVisibility(View.VISIBLE);
        AppExecutors.get().run(() -> AnimeRepository.get(this).anime(slug), (value, error) -> {
            b.loading.setVisibility(View.GONE);
            if (error != null || value == null) {
                b.title.setText("Не удалось загрузить");
                Ui.toast(this, error == null ? "Ошибка" : error.getMessage());
                return;
            }
            anime = value;
            render();
            loadTracks();
            loadNextEpisode();
            loadScreenshots();
        });
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
        if (anime.videos != null && !anime.videos.isEmpty()) meta.add(anime.videos.size() + " сер.");
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
        renderInfo();
        renderFavoriteState();
        renderViewingOrder();

        b.watch.setOnClickListener(v -> {
            if (currentTrack == null || currentTrack.episodes.isEmpty()) {
                Ui.toast(this, getString(R.string.resolving));
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
        b.episodesHint.setText(R.string.resolving);
        final Lookup lookup = lookupOf(anime);
        AppExecutors.get().heavy().execute(() -> {
            List<Track> result;
            try {
                result = TsuyuEngine.tracks(lookup);
            } catch (Throwable t) {
                result = new ArrayList<>();
            }
            final List<Track> tracks = result;
            AppExecutors.get().post(() -> {
                if (b == null || isFinishing()) return;
                this.tracks = tracks;
                renderVoices();
            });
        });
    }

    private Lookup lookupOf(AnimeFull a) {
        Lookup l = new Lookup();
        l.title = a.title;
        l.original = a.otherTitles != null && !a.otherTitles.isEmpty() ? a.otherTitles.get(0) : a.original;
        l.year = a.year;
        l.yummyId = a.animeId;
        if (a.remoteIds != null) {
            l.shikimoriId = a.remoteIds.shikimoriId;
            l.malId = a.remoteIds.malId;
            l.kpId = a.remoteIds.kpId;
            l.anilibriaAlias = a.remoteIds.anilibriaAlias;
        }
        if (a.genres != null) {
            for (ru.kelemnfno.anime.data.model.GenreShort g : a.genres) l.genres.add(g.title);
        }
        return l;
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
            b.favButton.setBackgroundResource(fav ? R.drawable.bg_chip_accent : R.drawable.bg_chip);
            Ui.pop(b.fav);
        });
    }

    private void toggleFavorite() {
        if (anime == null) return;
        final FavoriteEntity draft = new FavoriteEntity();
        draft.slug = slug;
        draft.animeId = anime.animeId;
        draft.title = anime.title;
        draft.poster = Fmt.posterUrl(anime, "big");
        draft.year = anime.year;
        draft.type = anime.type == null ? "" : anime.type.shortname;
        draft.addedAt = System.currentTimeMillis();
        draft.episodeCount = anime.videos == null ? 0 : anime.videos.size();
        draft.dubbing = currentTrack == null ? "" : currentTrack.voice;
        draft.status = anime.animeStatus == null ? "" : anime.animeStatus.alias;
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

    private void share() {
        if (anime == null) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, anime.title);
        intent.putExtra(Intent.EXTRA_TEXT, anime.title + " — смотреть в Kelemnfno");
        startActivity(Intent.createChooser(intent, "Поделиться"));
    }

    /* ---------------- Следующая серия ---------------- */

    private void loadNextEpisode() {
        if (anime.animeStatus == null || !anime.animeStatus.isOngoing()) {
            b.countdown.setVisibility(View.GONE);
            return;
        }
        AppExecutors.get().run(() -> {
            for (ScheduleItem s : AnimeRepository.get(this).schedule()) {
                if (s.animeId == anime.animeId && s.episodes != null) return s;
            }
            return null;
        }, (value, error) -> {
            if (b == null || value == null || value.episodes == null || value.episodes.nextDate <= 0) {
                b.countdown.setVisibility(View.GONE);
                return;
            }
            nextEpisodeTs = value.episodes.nextDateMs();
            nextEpisodeNumber = value.episodes.aired + 1;
            b.countdown.setVisibility(View.VISIBLE);
            b.countdown.setText("Серия " + nextEpisodeNumber
                    + (value.episodes.count > 0 ? " из " + value.episodes.count : "")
                    + " — " + Countdown.format(nextEpisodeTs) + " · " + Countdown.dateTime(nextEpisodeTs));
        });
    }

    /* ---------------- Скриншоты и порядок просмотра ---------------- */

    private void loadScreenshots() {
        int shiki = anime.remoteIds == null ? 0 : anime.remoteIds.shikimoriId;
        int mal = anime.remoteIds == null ? 0 : anime.remoteIds.malId;
        if (shiki <= 0 && mal <= 0) return;
        AppExecutors.get().run(() -> ScreenshotFetcher.fetch(shiki, mal), (value, error) -> {
            if (b == null || value == null || value.isEmpty()) return;
            b.screenshotsBlock.setVisibility(View.VISIBLE);
            b.screenshots.setLayoutManager(
                    new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            b.screenshots.setAdapter(new ScreenshotAdapter(value));
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
                for (DownloadEntity d : rows) {
                    if (d.status == DownloadEntity.DONE && slug.equals(d.slug)) downloadedEpisodes.add(d.episode);
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
                b.downloaded.setVisibility(downloadedEpisodes.contains(String.valueOf(episode))
                        ? View.VISIBLE : View.GONE);
                b.getRoot().setAlpha(isWatched ? 0.55f : 1f);
                b.getRoot().setOnClickListener(v -> {
                    if (currentTrack != null) play(currentTrack, episode);
                });
                b.getRoot().setOnLongClickListener(v -> {
                    if (currentTrack != null) {
                        DownloadSheet.show(DetailActivity.this, anime, currentTrack, episode);
                        return true;
                    }
                    return false;
                });
            }
        }
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
            holder.b.image.setOnClickListener(v -> Ui.openUrl(DetailActivity.this, url));
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

    @Override
    protected void onResume() {
        super.onResume();
        if (anime != null) renderFavoriteState();
    }
}
