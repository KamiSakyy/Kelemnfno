package ru.kelemnfno.anime.notify;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import ru.kelemnfno.anime.data.db.AppDatabase;
import ru.kelemnfno.anime.data.db.FavoriteEntity;
import ru.kelemnfno.anime.data.model.ScheduleItem;
import ru.kelemnfno.anime.data.prefs.Prefs;
import ru.kelemnfno.anime.data.repo.AnimeRepository;

/**
 * Фоновая проверка: если у тайтла из избранного вышла новая серия — шлём уведомление.
 * Работает по расписанию и при старте приложения.
 */
public class NewEpisodeWorker extends Worker {

    public static final String UNIQUE = "new_episodes_check";

    public NewEpisodeWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        try {
            int checked = check(context);
            return Result.success();
        } catch (Throwable t) {
            // сеть может быть недоступна — попробуем в следующий раз
            return Result.retry();
        }
    }

    /** Возвращает число уведомлённых тайтлов. */
    public static int check(Context context) throws Exception {
        AppDatabase db = AppDatabase.get(context);
        List<FavoriteEntity> favorites = db.favoriteDao().notifyable();
        if (favorites.isEmpty()) return 0;

        List<ScheduleItem> schedule = new ArrayList<>();
        try {
            schedule = AnimeRepository.get(context).schedule();
        } catch (Exception ignored) {
        }

        int notified = 0;
        int episodes = 0;
        for (FavoriteEntity fav : favorites) {
            ScheduleItem item = null;
            for (ScheduleItem s : schedule) {
                if (s.animeId == fav.animeId) {
                    item = s;
                    break;
                }
            }
            long nextDate = 0;
            String status = fav.status;
            if (item != null && item.episodes != null) {
                nextDate = item.episodes.nextDateMs();
                if (item.episodes.count > 0 && item.episodes.safeAired() >= item.episodes.count) status = "released";
                else status = "ongoing";
            }

            // Правда — фактически доступные серии в карточке тайтла:
            // счётчик расписания умеет врать («вышло» раньше времени),
            // поэтому расписание используем только для даты и статуса.
            int aired = 0;
            int maxN = 0;
            List<String> dubs = new ArrayList<>();
            try {
                Set<Integer> numbers = new HashSet<>();
                ru.kelemnfno.anime.data.model.AnimeFull full = AnimeRepository.get(context).anime(fav.slug);
                if (full.videos != null) {
                    for (ru.kelemnfno.anime.data.model.VideoItem v : full.videos) {
                        int n = ru.kelemnfno.anime.util.Fmt.numberIn(v.number, 0);
                        if (n > 0) {
                            numbers.add(n);
                            if (n > maxN) maxN = n;
                        }
                    }
                    for (ru.kelemnfno.anime.data.model.VideoItem v : full.videos) {
                        if (ru.kelemnfno.anime.util.Fmt.numberIn(v.number, 0) != maxN) continue;
                        String d = v.data == null ? "" : v.data.dubbing;
                        if (d != null && !d.isEmpty() && !dubs.contains(d)) dubs.add(d);
                    }
                }
                aired = numbers.size();
                // Shikimori — источник правды: берём его счётчик вышедших, если ответил.
                int shiki = full.remoteIds == null ? 0 : full.remoteIds.shikimoriId;
                int[] st = ru.kelemnfno.anime.data.shots.ScreenshotFetcher.airedStatus(shiki);
                if (st != null && st[0] > 0) aired = st[0];
                if (full.episodes != null && full.episodes.nextDateMs() > 0) nextDate = full.episodes.nextDateMs();
            } catch (Exception ignored) {
                // карточка не открылась — откат к счётчику расписания
                if (item != null && item.episodes != null) aired = item.episodes.safeAired();
                else continue;
            }
            if (aired <= 0) continue;

            db.favoriteDao().updateEpisodeState(fav.slug, Math.max(aired, fav.episodeCount), nextDate, status);
            if (aired > fav.episodeCount && fav.episodeCount > 0 && aired - fav.episodeCount <= 2) {
                NotificationHelper.notifyEpisode(context, fav, fav.episodeCount + 1, aired, dubLine(fav, dubs));
                notified++;
                episodes += aired - fav.episodeCount;
            }
        }
        if (notified > 1) NotificationHelper.notifySummary(context, notified, episodes);
        return notified;
    }

    /** Строка озвучек для уведомления: сначала озвучка пользователя, если она среди вышедших. */
    private static String dubLine(FavoriteEntity fav, List<String> dubs) {
        if (dubs == null || dubs.isEmpty()) return fav.dubbing == null ? "" : fav.dubbing;
        if (fav.dubbing != null && !fav.dubbing.isEmpty()) {
            for (String d : dubs) if (d.equalsIgnoreCase(fav.dubbing)) return d;
        }
        StringBuilder sb = new StringBuilder(dubs.get(0));
        if (dubs.size() > 1) {
            sb.append(dubs.size() == 2 ? " и " + dubs.get(1) : " и ещё " + (dubs.size() - 1));
        }
        return sb.toString();
    }

    /** Периодическая проверка: минимум раз в час, по умолчанию — по настройке. */
    public static void schedule(Context context) {
        Prefs prefs = Prefs.get(context);
        int hours = Math.max(1, prefs.settings().checkHours);
        Constraints.Builder cb = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED);
        if (prefs.settings().notifyWifiOnly) cb.setRequiredNetworkType(NetworkType.UNMETERED);
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(NewEpisodeWorker.class, hours, TimeUnit.HOURS)
                .setConstraints(cb.build())
                .setInitialDelay(1, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE,
                ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE);
    }

    /** Проверить прямо сейчас (кнопка в настройках / старт приложения). */
    public static void checkNow(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(NewEpisodeWorker.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE + "_now", ExistingWorkPolicy.REPLACE, request);
    }
}
