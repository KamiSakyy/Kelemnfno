package ru.kelemnfno.anime.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.db.DownloadEntity;
import ru.kelemnfno.anime.data.db.FavoriteEntity;
import ru.kelemnfno.anime.download.DownloadService;
import ru.kelemnfno.anime.ui.detail.DetailActivity;
import ru.kelemnfno.anime.util.Fmt;

/** Все системные уведомления приложения: каналы, прогресс закачки, новые серии. */
public final class NotificationHelper {

    public static final String CHANNEL_DOWNLOADS = "downloads";
    public static final String CHANNEL_EPISODES = "new_episodes";
    public static final String CHANNEL_PLAYER = "player";

    public static final int ID_DOWNLOAD_SUMMARY = 1000;
    public static final int ID_EPISODE_SUMMARY = 2000;

    private NotificationHelper() {
    }

    public static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel downloads = new NotificationChannel(CHANNEL_DOWNLOADS,
                "Скачивание серий", NotificationManager.IMPORTANCE_LOW);
        downloads.setDescription("Прогресс загрузки серий на устройство");
        downloads.setShowBadge(false);
        nm.createNotificationChannel(downloads);

        NotificationChannel episodes = new NotificationChannel(CHANNEL_EPISODES,
                "Новые серии", NotificationManager.IMPORTANCE_HIGH);
        episodes.setDescription("Уведомления о выходе новых серий избранного аниме");
        episodes.enableVibration(true);
        nm.createNotificationChannel(episodes);

        NotificationChannel player = new NotificationChannel(CHANNEL_PLAYER,
                "Воспроизведение", NotificationManager.IMPORTANCE_LOW);
        player.setDescription("Управление плеером и фоновое аудио");
        player.setShowBadge(false);
        nm.createNotificationChannel(player);
    }

    public static boolean canNotify(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    /* ---------------- Скачивание ---------------- */

    public static Notification downloadNotification(Context context, DownloadEntity d, boolean ongoing) {
        return downloadNotification(context, d, ongoing, 1);
    }

    public static Notification downloadNotification(Context context, DownloadEntity d,
                                                    boolean ongoing, int total) {
        Intent pause = new Intent(context, DownloadService.class)
                .setAction(d.status == DownloadEntity.RUNNING ? DownloadService.ACTION_PAUSE
                        : DownloadService.ACTION_RESUME)
                .putExtra(DownloadService.EXTRA_ID, d.id);
        Intent cancel = new Intent(context, DownloadService.class)
                .setAction(DownloadService.ACTION_CANCEL)
                .putExtra(DownloadService.EXTRA_ID, d.id);

        String title = d.title + " · серия " + d.episode;
        String text;
        switch (d.status) {
            case DownloadEntity.RUNNING:
                text = d.progress + "% · " + Fmt.formatBytes(d.sizeBytes)
                        + (d.segmentsTotal > 0 ? " · сегмент " + d.segmentsDone + "/" + d.segmentsTotal : "");
                break;
            case DownloadEntity.PAUSED:
                text = "Пауза · " + d.progress + "%";
                break;
            case DownloadEntity.ERROR:
                text = "Ошибка: " + (d.error == null || d.error.isEmpty() ? "не удалось скачать" : d.error);
                break;
            case DownloadEntity.DONE:
                text = "Готово · " + Fmt.formatBytes(d.sizeBytes);
                break;
            default:
                text = "В очереди";
                break;
        }

        if (total > 1) text = text + " · ещё " + (total - 1) + " в очереди";

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (d.status == DownloadEntity.RUNNING || d.status == DownloadEntity.QUEUED || d.status == DownloadEntity.PAUSED) {
            b.setProgress(100, d.progress, d.progress <= 0);
            b.addAction(R.drawable.ic_pause, d.status == DownloadEntity.RUNNING ? "Пауза" : "Продолжить",
                    servicePending(context, pause, 1));
            b.addAction(R.drawable.ic_close, "Отменить", servicePending(context, cancel, 2));
        }
        return b.build();
    }

    private static PendingIntent servicePending(Context context, Intent intent, int code) {
        return PendingIntent.getService(context, code + (int) (intent.getLongExtra(DownloadService.EXTRA_ID, 0) * 10),
                intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void notifyDownload(Context context, DownloadEntity d, boolean ongoing) {
        if (!canNotify(context)) return;
        try {
            NotificationManagerCompat.from(context).notify((int) (100 + d.id), downloadNotification(context, d, ongoing));
        } catch (SecurityException ignored) {
        }
    }

    /**
     * Одно уведомление на все загрузки. Раньше фоновое уведомление сервиса
     * и уведомление по конкретной серии имели разные id, поэтому загрузка
     * показывалась дважды.
     */
    public static void notifyDownloads(Context context, DownloadEntity active, int total) {
        if (active == null || !canNotify(context)) return;
        try {
            NotificationManagerCompat.from(context).notify(
                    ID_DOWNLOAD_SUMMARY, downloadNotification(context, active, true, total));
        } catch (SecurityException ignored) {
        }
    }

    public static void cancelDownloads(Context context) {
        NotificationManagerCompat.from(context).cancel(ID_DOWNLOAD_SUMMARY);
    }

    public static void cancelDownload(Context context, long id) {
        NotificationManagerCompat.from(context).cancel((int) (100 + id));
    }

    /* ---------------- Новые серии ---------------- */

    /** Уведомление «вышла новая серия»: постер, номер серии и озвучки. */
    public static Notification episodeNotification(Context context, FavoriteEntity fav,
                                                   int fromEpisode, int toEpisode, String dubs) {
        Intent open = new Intent(context, DetailActivity.class)
                .putExtra(DetailActivity.EXTRA_SLUG, fav.slug)
                .putExtra(DetailActivity.EXTRA_EPISODE, String.valueOf(toEpisode))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, (int) (fav.animeId * 7L + toEpisode),
                open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int count = Math.max(1, toEpisode - fromEpisode + 1);
        String head = count > 1
                ? "Вышли серии " + fromEpisode + "–" + toEpisode
                : "Вышла серия " + toEpisode;
        String dubPart = dubs == null || dubs.isEmpty() ? "" : " · " + dubs;
        String text = head + dubPart;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_EPISODES)
                .setSmallIcon(R.drawable.ic_bell)
                .setContentTitle(fav.title)
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setColor(context.getColor(R.color.accent))
                .setContentIntent(pi);

        android.graphics.Bitmap poster = loadBitmap(fav.poster);
        if (poster != null) {
            builder.setLargeIcon(poster);
            builder.setStyle(new NotificationCompat.BigPictureStyle()
                    .bigPicture(poster)
                    .setBigContentTitle(fav.title)
                    .setSummaryText(text));
        } else {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(text));
        }
        return builder.build();
    }

    /** Постер для уведомления; без сети или при ошибке — null (уведомление останется текстовым). */
    private static android.graphics.Bitmap loadBitmap(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            okhttp3.Request req = new okhttp3.Request.Builder().url(url).build();
            try (okhttp3.Response r = ru.kelemnfno.anime.data.resolver.Net.client().newCall(req).execute()) {
                if (!r.isSuccessful() || r.body() == null) return null;
                return android.graphics.BitmapFactory.decodeStream(r.body().byteStream());
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public static void notifyEpisode(Context context, FavoriteEntity fav, int fromEpisode, int toEpisode, String dubs) {
        if (!canNotify(context)) return;
        try {
            NotificationManagerCompat.from(context)
                    .notify((int) (ID_EPISODE_SUMMARY + fav.animeId),
                            episodeNotification(context, fav, fromEpisode, toEpisode, dubs));
        } catch (SecurityException ignored) {
        }
    }

    /** Сводка, если новых серий сразу несколько. */
    public static void notifySummary(Context context, int titles, int episodes) {
        if (!canNotify(context) || titles <= 0) return;
        Intent open = new Intent(context, DetailActivity.class).setAction(Intent.ACTION_MAIN);
        try {
            NotificationManagerCompat.from(context).notify(ID_EPISODE_SUMMARY,
                    new NotificationCompat.Builder(context, CHANNEL_EPISODES)
                            .setSmallIcon(R.drawable.ic_bell)
                            .setContentTitle("Новые серии")
                            .setContentText(episodes + " сер. в " + titles + " тайтлах из избранного")
                            .setAutoCancel(true)
                            .setContentIntent(PendingIntent.getActivity(context, 0, open,
                                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                            .build());
        } catch (SecurityException ignored) {
        }
    }
}
