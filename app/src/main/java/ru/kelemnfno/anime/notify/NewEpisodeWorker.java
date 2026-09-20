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
            int aired = 0;
            long nextDate = 0;
            String status = fav.status;
            if (item != null && item.episodes != null) {
                aired = item.episodes.safeAired();
                nextDate = item.episodes.nextDateMs();
                if (item.episodes.count > 0 && item.episodes.aired >= item.episodes.count) status = "released";
                else status = "ongoing";
            } else {
                // расписания нет — считаем по числу доступных серий в карточке
                try {
                    Set<String> numbers = new HashSet<>();
                    ru.kelemnfno.anime.data.model.AnimeFull full = AnimeRepository.get(context).anime(fav.slug);
                    if (full.videos != null) {
                        for (ru.kelemnfno.anime.data.model.VideoItem v : full.videos) {
                            if (v.number != null) numbers.add(v.number);
                        }
                    }
                    aired = numbers.size();
                    if (full.episodes != null) nextDate = full.episodes.nextDateMs();
                } catch (Exception ignored) {
                    continue;
                }
            }

            if (item != null || aired > 0) {
                db.favoriteDao().updateEpisodeState(fav.slug, Math.max(aired, fav.episodeCount), nextDate, status);
            }
            if (aired > fav.episodeCount && fav.episodeCount > 0) {
                // Скачок больше двух серий за проверку — похож на ошибку источника:
                // молча синхронизируем счётчик, без ложного «вышла серия».
                if (aired - fav.episodeCount <= 2) {
                    NotificationHelper.notifyEpisode(context, fav, fav.episodeCount + 1, aired);
                    notified++;
                    episodes += aired - fav.episodeCount;
                }
            }
        }
        if (notified > 1) NotificationHelper.notifySummary(context, notified, episodes);
        return notified;
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
