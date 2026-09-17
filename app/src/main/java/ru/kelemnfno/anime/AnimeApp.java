package ru.kelemnfno.anime;

import android.app.Application;

import ru.kelemnfno.anime.data.db.AppDatabase;
import ru.kelemnfno.anime.data.prefs.Prefs;
import ru.kelemnfno.anime.notify.NewEpisodeWorker;
import ru.kelemnfno.anime.notify.NotificationHelper;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.CrashGuard;

/** Точка входа: каналы уведомлений, база, фоновые проверки новых серий. */
public class AnimeApp extends Application {

    private static AnimeApp instance;

    public static AnimeApp get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        CrashGuard.install(this);
        NotificationHelper.createChannels(this);
        AppExecutors.get().io().execute(() -> {
            AppDatabase.get(this).favoriteDao().all();
            if (Prefs.get(this).settings().notifyNewEpisodes) NewEpisodeWorker.schedule(this);
        });
    }
}
