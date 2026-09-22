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
import ru.kelemnfno.anime.data.resolver.Net;
import ru.kelemnfno.anime.data.resolver.SourceEngine;
import ru.kelemnfno.anime.databinding.SheetDownloadBinding;
import ru.kelemnfno.anime.download.DownloadService;
import ru.kelemnfno.anime.ui.Chips;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.Fmt;
import ru.kelemnfno.anime.util.Ui;

/**
 * Лист скачивания: выбор озвучки и качества.
 * Потоки берутся перехватом (SourceEngine.streams), файл пишется в папку приложения.
 */
public final class DownloadSheet {

    private DownloadSheet() {
    }

    public static void show(DetailActivity host, AnimeFull anime, Track track, int episode) {
        List<Track> one = new ArrayList<>();
        one.add(track);
        show(host, anime, one, track, episode);
    }

    public static void show(final DetailActivity host, final AnimeFull anime, final List<Track> allTracks,
                            Track initial, final int episode) {
        final SheetDownloadBinding b = SheetDownloadBinding.inflate(host.getLayoutInflater());
        final BottomSheetDialog dialog = new BottomSheetDialog(host, R.style.Theme_Kelemnfno_BottomSheet);
        dialog.setContentView(b.getRoot());

        final List<StreamSource> sources = new ArrayList<>();
        final int[] chosen = {Prefs.get(host).settings().downloadQuality};
        // Адрес конкретного варианта, если качество выбрано из мастер-плейлиста.
        final String[] chosenUrl = {null};
        final Track[] current = {initial};

        b.title.setText("Серия " + episode + " · " + anime.title);
        b.close.setOnClickListener(v -> dialog.dismiss());

        b.start.setOnClickListener(v -> {
            StreamSource picked = pick(sources, chosen[0]);
            if (picked == null) {
                showError(b, "Нет потока в этом качестве");
                return;
            }
            if (chosenUrl[0] != null && !chosenUrl[0].isEmpty()) {
                picked = copyOf(picked, chosen[0], chosenUrl[0]);
            }
            DownloadService.add(host, entity(anime, current[0], episode, picked));
            dialog.dismiss();
            Ui.toast(host, "Серия " + episode + " · " + picked.quality + "p — в загрузках");
        });
        b.retry.setOnClickListener(v ->
                resolve(host, b, current[0], episode, sources, chosen, chosenUrl));

        renderVoices(b, allTracks, current, host, anime, episode, sources, chosen, chosenUrl);
        expand(dialog);
        dialog.show();
        Ui.fadeIn(b.getRoot(), 160);
        resolve(host, b, current[0], episode, sources, chosen, chosenUrl);
    }

    private static void renderVoices(final SheetDownloadBinding b, final List<Track> allTracks,
                                     final Track[] current, final DetailActivity host,
                                     final AnimeFull anime, final int episode,
                                     final List<StreamSource> sources, final int[] chosen,
                                     final String[] chosenUrl) {
        b.voices.removeAllViews();
        for (Track t : allTracks) {
            final Track track = t;
            Chips.add(b.voices, track.voice, track.id.equals(current[0].id), v -> {
                current[0] = track;
                renderVoices(b, allTracks, current, host, anime, episode, sources, chosen, chosenUrl);
                resolve(host, b, track, episode, sources, chosen, chosenUrl);
            });
        }
        if (allTracks.size() < 2) b.voices.setVisibility(View.GONE);
    }

    /** Подбирает прямые потоки серии и показывает доступные качества. */
    private static void resolve(final DetailActivity host, final SheetDownloadBinding b, final Track track,
                                final int episode, final List<StreamSource> sources, final int[] chosen,
                                final String[] chosenUrl) {
        b.subtitle.setText(track.voice);
        b.stateError.setVisibility(View.GONE);
        b.stateReady.setVisibility(View.GONE);
        b.stateResolving.setVisibility(View.VISIBLE);
        AppExecutors.get().heavy().execute(() -> {
            List<StreamSource> found;
            String error = null;
            try {
                found = ru.kelemnfno.anime.data.api.DirectHentai.streams(track.id, episode);
                if (found == null) found = SourceEngine.streams(track.id, episode);
            } catch (Throwable t) {
                found = new ArrayList<>();
                error = t.getMessage() == null ? "Источник недоступен" : t.getMessage();
            }
            final List<StreamSource> result = found;
            final String message = error;
            AppExecutors.get().post(() -> {
                if (host.isFinishing()) return;
                sources.clear();
                sources.addAll(result);
                if (sources.isEmpty()) {
                    showError(b, message == null ? "Прямой поток не найден" : message);
                    return;
                }
                int wantedBefore = chosen[0];
                chosen[0] = clamp(chosen[0], availableQualities(sources));
                if (chosen[0] != wantedBefore) {
                    b.subtitle.setText(track.voice + " · " + wantedBefore
                            + "p у источника нет, ближайшее " + chosen[0] + "p");
                }
                chosenUrl[0] = null;
                b.stateResolving.setVisibility(View.GONE);
                b.stateReady.setVisibility(View.VISIBLE);
                StreamSource first = pick(sources, chosen[0]);
                b.sourceInfo.setText((first == null ? "" : first.label) + " · " + track.voice);
                renderQualities(b, sources, chosen, chosenUrl, new ArrayList<String[]>());
                loadVariants(host, b, first, sources, chosen, chosenUrl, track);
            });
        });
    }

    private static void renderQualities(final SheetDownloadBinding b, final List<StreamSource> sources,
                                        final int[] chosen, final String[] chosenUrl,
                                        final List<String[]> variants) {
        b.qualities.removeAllViews();
        if (variants != null && !variants.isEmpty()) {
            for (final String[] variant : variants) {
                final int height = parseInt(variant[0]);
                if (height <= 0) continue;
                Chips.add(b.qualities, height + "p", height == chosen[0], v -> {
                    chosen[0] = height;
                    chosenUrl[0] = variant[1];
                    renderQualities(b, sources, chosen, chosenUrl, variants);
                });
            }
            return;
        }
        chosenUrl[0] = null;
        for (final int quality : availableQualities(sources)) {
            Chips.add(b.qualities, quality + "p", quality == chosen[0], v -> {
                chosen[0] = quality;
                renderQualities(b, sources, chosen, chosenUrl, variants);
            });
        }
    }

    /**
     * Честный список качеств: берём варианты прямо из мастер-плейлиста,
     * поэтому в списке есть и 360p, и всё, что реально отдаёт источник.
     */
    private static void loadVariants(final DetailActivity host, final SheetDownloadBinding b,
                                     final StreamSource source, final List<StreamSource> sources,
                                     final int[] chosen, final String[] chosenUrl, final Track track) {
        if (source == null || !source.isHls()) return;
        AppExecutors.get().run(() -> {
            String text = Net.get(source.url,
                    ru.kelemnfno.anime.download.HlsDownloader.headersFor(source.referer), 8000);
            return ru.kelemnfno.anime.download.HlsDownloader.variants(text, source.url);
        }, (value, error) -> {
            if (host.isFinishing() || value == null || value.size() < 2) return;
            List<Integer> heights = new ArrayList<>();
            for (String[] v : value) {
                int h = parseInt(v[0]);
                if (h > 0 && !heights.contains(h)) heights.add(h);
            }
            if (heights.isEmpty()) return;
            java.util.Collections.sort(heights, java.util.Collections.reverseOrder());
            chosen[0] = clamp(chosen[0], heights);
            for (String[] v : value) {
                if (parseInt(v[0]) == chosen[0]) {
                    chosenUrl[0] = v[1];
                    break;
                }
            }
            b.sourceInfo.setText((source.label == null ? "" : source.label)
                    + " · " + track.voice + " · " + chosen[0] + "p");
            renderQualities(b, sources, chosen, chosenUrl, value);
        });
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Поток с подменёнными качеством и адресом — для выбранного варианта. */
    private static StreamSource copyOf(StreamSource source, int quality, String url) {
        StreamSource copy = new StreamSource();
        copy.quality = quality;
        copy.url = url;
        copy.kind = source.kind;
        copy.referer = source.referer;
        copy.voice = source.voice;
        copy.label = source.label;
        return copy;
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

    /** Лист раскрываем, чтобы озвучки и качества помещались на экран. */
    private static void expand(BottomSheetDialog dialog) {
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;
        com.google.android.material.bottomsheet.BottomSheetBehavior<View> behavior =
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet);
        behavior.setSkipCollapsed(true);
        behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }
}
