package ru.kelemnfno.anime;

import android.app.Application;

import ru.kelemnfno.anime.data.db.AppDatabase;
import ru.kelemnfno.anime.data.prefs.Prefs;
import ru.kelemnfno.anime.notify.NewEpisodeWorker;
import ru.kelemnfno.anime.notify.NotificationHelper;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.CrashGuard;
import ru.kelemnfno.anime.util.Fmt;

/** Точка входа: каналы уведомлений, база, фоновые проверки новых серий. */
public class AnimeApp extends Application {

    private static AnimeApp instance;

    public static AnimeApp get() {
        return instance;
    }

    /** Мобильный интернет — картинки берём меньшего размера, экономим трафик. */
    private void watchNetwork() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            applyNetwork(cm);
            cm.registerDefaultNetworkCallback(
                    new android.net.ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onCapabilitiesChanged(android.net.Network network,
                                                          android.net.NetworkCapabilities caps) {
                            Fmt.setCellular(caps != null
                                    && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR));
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private void applyNetwork(android.net.ConnectivityManager cm) {
        android.net.NetworkCapabilities caps =
                cm.getNetworkCapabilities(cm.getActiveNetwork());
        Fmt.setCellular(caps != null
                && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        CrashGuard.install(this);
        NotificationHelper.createChannels(this);
        watchNetwork();
        AppExecutors.get().io().execute(() -> {
            AppDatabase.get(this).favoriteDao().all();
            if (Prefs.get(this).settings().notifyNewEpisodes) NewEpisodeWorker.schedule(this);
        });
    }
}
