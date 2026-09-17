package ru.kelemnfno.anime.ui.detail;

import android.view.View;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.db.DownloadEntity;
import ru.kelemnfno.anime.data.model.AnimeFull;
import ru.kelemnfno.anime.data.model.StreamSource;
import ru.kelemnfno.anime.data.model.Track;
import ru.kelemnfno.anime.data.prefs.Prefs;
import ru.kelemnfno.anime.data.tsuyu.TsuyuEngine;
import ru.kelemnfno.anime.databinding.SheetDownloadBinding;
import ru.kelemnfno.anime.download.DownloadService;
import ru.kelemnfno.anime.ui.Chips;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.Fmt;
import ru.kelemnfno.anime.util.Ui;

/** Лист скачивания: подбор прямых потоков, выбор качества, старт загрузки серии. */
public final class DownloadSheet {

    private DownloadSheet() {
    }

    public static void show(final DetailActivity host, final AnimeFull anime, final Track track,
                            final int episode) {
        final SheetDownloadBinding b = SheetDownloadBinding.inflate(host.getLayoutInflater());
        final BottomSheetDialog dialog = new BottomSheetDialog(host, R.style.Theme_Kelemnfno_BottomSheet);
        dialog.setContentView(b.getRoot());

        final List<StreamSource> sources = new ArrayList<>();
        final int[] chosen = {Prefs.get(host).settings().downloadQuality};

        b.title.setText("Серия " + episode + " · " + anime.title);
        b.subtitle.setText(track.voice);
        b.close.setOnClickListener(v -> dialog.dismiss());
        b.stateResolving.setVisibility(View.VISIBLE);
        b.stateReady.setVisibility(View.GONE);
        b.stateError.setVisibility(View.GONE);

        b.start.setOnClickListener(v -> {
            StreamSource picked = pick(sources, chosen[0]);
            if (picked == null) {
                showError(b, "Нет потока в этом качестве");
                return;
            }
            DownloadEntity entity = entity(anime, track, episode, picked);
            DownloadService.add(host, entity);
            dialog.dismiss();
            Ui.toast(host, "Серия " + episode + " · " + picked.label + " — в загрузках");
        });
        b.retry.setOnClickListener(v -> resolve(host, b, track, episode, sources, chosen));

        resolve(host, b, track, episode, sources, chosen);
        dialog.show();
        Ui.fadeIn(b.getRoot(), 160);
    }

    /** Подбирает прямые потоки серии и показывает доступные качества. */
    private static void resolve(final DetailActivity host, final SheetDownloadBinding b, final Track track,
                                final int episode, final List<StreamSource> sources, final int[] chosen) {
        b.stateError.setVisibility(View.GONE);
        b.stateReady.setVisibility(View.GONE);
        b.stateResolving.setVisibility(View.VISIBLE);
        AppExecutors.get().heavy().execute(() -> {
            List<StreamSource> found;
            String error = null;
            try {
                found = TsuyuEngine.streams(track.id, episode);
            } catch (Throwable t) {
                found = new ArrayList<>();
                error = t.getMessage() == null ? "Источник недоступен" : t.getMessage();
            }
            final List<StreamSource> result = found;
            final String message = error;
            AppExecutors.get().post(() -> {
                if (host.isFinishing() || b.getRoot().getWindowToken() == null) return;
                sources.clear();
                sources.addAll(result);
                if (sources.isEmpty()) {
                    showError(b, message == null ? "Прямой поток не найден" : message);
                    return;
                }
                chosen[0] = clamp(chosen[0], availableQualities(sources));
                b.stateResolving.setVisibility(View.GONE);
                b.stateReady.setVisibility(View.VISIBLE);
                StreamSource first = sources.get(0);
                b.sourceInfo.setText(first.label + " · " + (first.kind == null ? "hls" : first.kind)
                        + " · " + track.voice);
                renderQualities(b, sources, chosen);
            });
        });
    }

    private static void renderQualities(final SheetDownloadBinding b, final List<StreamSource> sources,
                                        final int[] chosen) {
        b.qualities.removeAllViews();
        for (int quality : availableQualities(sources)) {
            Chips.add(b.qualities, quality + "p", quality == chosen[0], v -> {
                chosen[0] = quality;
                renderQualities(b, sources, chosen);
            });
        }
    }

    /** Доступные высоты потока, от больших к меньшим. */
    public static List<Integer> availableQualities(List<StreamSource> sources) {
        TreeSet<Integer> set = new TreeSet<>();
        for (StreamSource s : sources) {
            if (s.quality > 0) set.add(s.quality);
        }
        List<Integer> out = new ArrayList<>(set);
        java.util.Collections.sort(out, java.util.Collections.reverseOrder());
        return out;
    }

    private static int clamp(int wanted, List<Integer> available) {
        if (available.isEmpty()) return 720;
        for (int q : available) if (q == wanted) return q;
        for (int q : available) if (q <= wanted) return q;
        return available.get(available.size() - 1);
    }

    private static StreamSource pick(List<StreamSource> sources, int quality) {
        StreamSource fallback = null;
        for (StreamSource s : sources) {
            if (s.quality == quality) return s;
            if (fallback == null || Math.abs(s.quality - quality) < Math.abs(fallback.quality - quality)) {
                fallback = s;
            }
        }
        return fallback;
    }

    private static void showError(SheetDownloadBinding b, String message) {
        b.stateResolving.setVisibility(View.GONE);
        b.stateReady.setVisibility(View.GONE);
        b.stateError.setVisibility(View.VISIBLE);
        b.errorText.setText(message == null ? "Не удалось получить поток" : message);
    }

    /** Заполняет сущность загрузки из подобранного потока. */
    public static DownloadEntity entity(AnimeFull anime, Track track, int episode, StreamSource source) {
        DownloadEntity e = new DownloadEntity();
        e.slug = anime.animeUrl == null ? "" : anime.animeUrl;
        e.animeId = anime.animeId;
        e.title = Fmt.sanitizeFilename(anime.title);
        e.episode = String.valueOf(episode);
        e.voice = track.voice == null ? "" : track.voice;
        e.quality = source.quality;
        e.url = source.url;
        e.referer = source.referer == null ? "" : source.referer;
        e.kind = source.kind == null ? "hls" : source.kind;
        e.poster = Fmt.posterUrl(anime, "big");
        e.fileName = e.title + " " + Fmt.episodeTag(e.episode) + " "
                + (source.quality > 0 ? source.quality + "p" : "best")
                + ("mp4".equals(e.kind) ? ".mp4" : ".ts");
        e.status = DownloadEntity.QUEUED;
        e.createdAt = System.currentTimeMillis();
        e.updatedAt = e.createdAt;
        return e;
    }
}
