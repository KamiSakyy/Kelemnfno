package ru.kelemnfno.anime.ui.feed;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import ru.kelemnfno.anime.data.model.AnimeItem;
import ru.kelemnfno.anime.data.model.Lookup;
import ru.kelemnfno.anime.data.model.StreamSource;
import ru.kelemnfno.anime.data.model.Track;
import ru.kelemnfno.anime.data.repo.AnimeRepository;
import ru.kelemnfno.anime.data.resolver.SourceEngine;
import ru.kelemnfno.anime.databinding.FragmentFeedBinding;
import ru.kelemnfno.anime.ui.detail.DetailActivity;
import ru.kelemnfno.anime.player.PlaybackService;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.Fmt;
import ru.kelemnfno.anime.util.Ui;

/**
 * Бесконечная вертикальная лента: случайные серии кусками по 15–30 секунд.
 * Плеер один на всю ленту — страницы только подставляют ему свой экран.
 */
@OptIn(markerClass = UnstableApi.class)
public class FeedFragment extends Fragment {

    private static final int BATCH = 3;

    private FragmentFeedBinding b;
    private final List<FeedClip> clips = new ArrayList<>();
    private final Random random = new Random();
    private FeedAdapter adapter;
    private ExoPlayer player;
    private FeedAdapter.Holder attached;
    private int current = -1;
    private int cursor;
    private boolean loadingBatch;
    private boolean muted;
    private boolean wantNext;
    private int failStreak;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentFeedBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adapter = new FeedAdapter(clips, new FeedAdapter.Actions() {
            @Override
            public void onOpen(FeedClip clip) {
                if (getContext() != null) DetailActivity.open(getContext(), clip.slug);
            }

            @Override
            public void onMute(FeedAdapter.Holder holder) {
                toggleMute(holder);
            }
        });
        b.pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        b.pager.setAdapter(adapter);
        b.pager.setOffscreenPageLimit(1);
        // Лёгкий наплыв при перелистывании — лента листается плавнее.
        b.pager.setPageTransformer((page, position) -> {
            float shift = Math.abs(position);
            page.setAlpha(shift < 1f ? 1f - shift * 0.35f : 0.65f);
            page.setScaleX(shift < 1f ? 1f - shift * 0.06f : 0.94f);
            page.setScaleY(shift < 1f ? 1f - shift * 0.06f : 0.94f);
        });
        b.pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                playAt(position);
            }
        });

        // Та же фабрика загрузки, что и у основного плеера: иначе Referer/Origin
        // из applyHeaders() не попадают в запросы и источники отдают 403.
        // Плюс локальный кэш: первый просмотр качает фрагмент по сети,
        // цикл и повторные просмотры читают из кэша — без трафика.
        androidx.media3.datasource.cache.CacheDataSource.Factory dataSources =
                new androidx.media3.datasource.cache.CacheDataSource.Factory()
                        .setCache(feedCache(requireContext()))
                        .setUpstreamDataSourceFactory(
                                new androidx.media3.datasource.DefaultDataSource.Factory(
                                        requireContext(), PlaybackService.HTTP));
        // Экономия трафика: самый дешёвый битрейт, а буфер равен клипу —
        // качается ровно показываемый кусок 15–30 с и играет без заиканий.
        androidx.media3.exoplayer.trackselection.DefaultTrackSelector trackSelector =
                new androidx.media3.exoplayer.trackselection.DefaultTrackSelector(requireContext());
        trackSelector.setParameters(new androidx.media3.exoplayer.trackselection
                .DefaultTrackSelector.Parameters.Builder(requireContext())
                .setForceLowestBitrate(true)
                .build());
        androidx.media3.exoplayer.DefaultLoadControl loadControl =
                new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(5000, 30000, 1500, 3000)
                        .build();
        player = new ExoPlayer.Builder(requireContext())
                .setMediaSourceFactory(new androidx.media3.exoplayer.source
                        .DefaultMediaSourceFactory(dataSources))
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .build();
        // Как в тик-токе: клип крутится циклом, но из кэша — трафик не тратится.
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && attached != null) {
                    attached.b.feedLoading.setVisibility(View.GONE);
                    failStreak = 0;
                }

            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                if (attached != null) attached.b.feedLoading.setVisibility(View.GONE);
                failStreak++;
                // Битый фрагмент не должен вешать ленту: пропускаем его сам.
                if (failStreak <= 3) advance();
            }
        });
        loadBatch();
    }

    private static androidx.media3.datasource.cache.SimpleCache sCache;

    /** Локальный кэш клипов (150 МБ, старое вытесняется): цикл и повторы — без сети. */
    private static synchronized androidx.media3.datasource.cache.SimpleCache feedCache(
            android.content.Context ctx) {
        if (sCache == null) {
            sCache = new androidx.media3.datasource.cache.SimpleCache(
                    new java.io.File(ctx.getCacheDir(), "feed"),
                    new androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(
                            150L * 1024 * 1024));
        }
        return sCache;
    }

    /** Готовит порцию клипов в фоне и добавляет её в ленту. */
    private void loadBatch() {
        if (loadingBatch || b == null) return;
        loadingBatch = true;
        b.feedProgress.setVisibility(View.VISIBLE);
        AppExecutors.get().run(() -> buildBatch(BATCH),
                (value, error) -> {
                    loadingBatch = false;
                    if (b == null) return;
                    b.feedProgress.setVisibility(View.GONE);
                    if (value == null || value.isEmpty()) {
                        if (clips.isEmpty()) {
                            b.feedHint.setText(error == null
                                    ? "Не удалось подобрать источники. Попробуйте ещё раз."
                                    : error.getMessage());
                        }
                        return;
                    }
                    int first = clips.size();
                    clips.addAll(value);
                    adapter.notifyItemRangeInserted(first, value.size());
                    b.feedHint.setVisibility(View.GONE);
                    if (current < 0) playAt(0);
                    else if (wantNext) advance();
                });
    }

    private List<FeedClip> buildBatch(int wanted) throws Exception {
        AnimeRepository repo = AnimeRepository.get(requireContext());
        Map<String, String> params = new LinkedHashMap<>();
        String[] sorts = {"top", "views", "rating"};
        params.put("sort", sorts[random.nextInt(sorts.length)]);
        params.put("limit", "24");
        params.put("offset", String.valueOf(cursor));
        cursor += 24;

        List<AnimeItem> items = repo.list(params);
        Collections.shuffle(items, random);
        if (items.size() > 8) items = items.subList(0, 8);

        // Подбор озвучки — дело небыстрое, поэтому кандидатов разбираем
        // одновременно и забираем первые подошедшие, а не ждём всех.
        java.util.concurrent.ExecutorService pool = AppExecutors.get().heavy();
        List<java.util.concurrent.Future<FeedClip>> futures = new ArrayList<>();
        for (AnimeItem item : items) {
            futures.add(pool.submit(() -> clipFor(item)));
        }
        List<FeedClip> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 12_000L;
        for (java.util.concurrent.Future<FeedClip> future : futures) {
            if (out.size() >= wanted) break;
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            try {
                FeedClip clip = future.get(left, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (clip != null) out.add(clip);
            } catch (Throwable ignored) {
            }
        }
        for (java.util.concurrent.Future<FeedClip> future : futures) future.cancel(true);
        return out;
    }

    /** Один клип: первая подходящая озвучка, случайная серия, случайный кусок. */
    private FeedClip clipFor(AnimeItem item) {
        try {
            Lookup lookup = new Lookup();
            lookup.title = item.title;
            lookup.year = item.year;
            lookup.sourceId = item.animeId;
            List<Track> tracks = SourceEngine.tracks(lookup);
            if (tracks == null || tracks.isEmpty()) return null;
            Track track = tracks.get(0);
            if (track.episodes == null || track.episodes.isEmpty()) return null;
            int episode = track.episodes.get(random.nextInt(track.episodes.size()));

            List<StreamSource> sources = null;
            for (Track t : tracks) {
                if (t.episodes == null || !t.episodes.contains(episode)) continue;
                try {
                    List<StreamSource> found = SourceEngine.streams(t.id, episode);
                    if (found != null && !found.isEmpty()) {
                        sources = found;
                        track = t;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (sources == null || sources.isEmpty()) return null;
            // Предпочитаем HLS с минимальным качеством: он качается сегментами,
            // то есть ровно показанным куском, и трафик минимален.
            StreamSource chosen = null;
            for (StreamSource s : sources) {
                if (!s.isHls()) continue;
                if (chosen == null
                        || (s.quality > 0 && (chosen.quality == 0 || s.quality < chosen.quality))) {
                    chosen = s;
                }
            }
            if (chosen == null) chosen = sources.get(0);

            FeedClip clip = new FeedClip();
            clip.title = item.title;
            clip.slug = item.animeUrl;
            clip.episode = episode;
            clip.url = chosen.url;
            clip.referer = chosen.referer;
            clip.voice = chosen.voice;
            clip.poster = Fmt.posterUrl(item, "medium");
            clip.startMs = (60 + random.nextInt(420)) * 1000L;
            clip.clipMs = 15_000L + random.nextInt(16_000);
            return clip;
        } catch (Throwable t) {
            return null;
        }
    }

    private void playAt(int position) {
        if (b == null || player == null) return;
        detach();
        current = position;
        if (position < 0 || position >= clips.size()) return;
        FeedClip clip = clips.get(position);
        // У ViewPager2 метод поиска страницы есть только у внутреннего RecyclerView.
        View pages = b.pager.getChildAt(0);
        RecyclerView.ViewHolder holder = pages instanceof RecyclerView
                ? ((RecyclerView) pages).findViewHolderForAdapterPosition(position)
                : null;
        if (!(holder instanceof FeedAdapter.Holder)) {
            // Страница ещё не разложена — без ретрая плеер остался бы без экрана,
            // и лента выглядела бы «мёртвой».
            final int pos = position;
            b.pager.post(() -> {
                if (b != null && current == pos) playAt(pos);
            });
            return;
        }
        attached = (FeedAdapter.Holder) holder;
        attached.b.feedLoading.setVisibility(View.VISIBLE);
        attached.b.feedPlayer.setPlayer(player);
        PlaybackService.applyHeaders(clip.referer, null);
        // В плеер отдаётся ТОЛЬКО окно 15–30 с: остальная серия не скачивается,
        // мобильный трафик тратится ровно на показанный фрагмент.
        MediaItem item = new MediaItem.Builder()
                .setUri(clip.url)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startMs)
                        .setEndPositionMs(clip.startMs + clip.clipMs)
                        .build())
                .build();
        player.setMediaItem(item);
        player.prepare();
        player.setVolume(muted ? 0f : 1f);
        player.setPlayWhenReady(true);
        wantNext = false;
        if (position >= clips.size() - 4) loadBatch();
    }

    private void advance() {
        if (b == null) return;
        if (current + 1 < clips.size()) {
            b.pager.setCurrentItem(current + 1, true);
        } else {
            wantNext = true;
            loadBatch();
        }
    }

    private void toggleMute(FeedAdapter.Holder holder) {
        muted = !muted;
        if (player != null) player.setVolume(muted ? 0f : 1f);
        if (holder != null) holder.b.feedMute.setAlpha(muted ? 0.35f : 1f);
        if (getContext() != null) Ui.toast(getContext(), muted ? "Без звука" : "Со звуком");
    }

    private void detach() {
        if (attached != null) {
            attached.b.feedPlayer.setPlayer(null);
            attached.b.feedLoading.setVisibility(View.VISIBLE);
            attached = null;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (player != null && current >= 0) player.play();
    }

    @Override
    public void onDestroyView() {
        detach();
        if (player != null) {
            player.release();
            player = null;
        }
        b = null;
        super.onDestroyView();
    }
}
