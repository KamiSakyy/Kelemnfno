package ru.kelemnfno.anime.download;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.kelemnfno.anime.data.db.AppDatabase;
import ru.kelemnfno.anime.data.db.DownloadEntity;
import ru.kelemnfno.anime.data.resolver.Net;
import ru.kelemnfno.anime.notify.NotificationHelper;

/**
 * Настоящее скачивание серий в foreground-сервисе: очередь, пауза/продолжение,
 * отмена, прогресс в уведомлении и докачка после обрыва связи.
 */
public class DownloadService extends Service {

    public static final String ACTION_ENQUEUE = "ru.kelemnfno.anime.ENQUEUE";
    public static final String ACTION_PAUSE = "ru.kelemnfno.anime.PAUSE";
    public static final String ACTION_RESUME = "ru.kelemnfno.anime.RESUME";
    public static final String ACTION_CANCEL = "ru.kelemnfno.anime.CANCEL";
    public static final String ACTION_START_ALL = "ru.kelemnfno.anime.START_ALL";
    public static final String EXTRA_ID = "id";

    private static final int MAX_PARALLEL = 2;
    private static final int NOTIF_ID = 4242;

    private final Set<Long> running = new HashSet<>();
    private final Set<Long> paused = new HashSet<>();
    private final List<HlsDownloader.Cancel> cancels = new ArrayList<>();
    private ExecutorService pool;

    public static void enqueue(Context context, long id) {
        context.startService(new Intent(context, DownloadService.class)
                .setAction(ACTION_ENQUEUE).putExtra(EXTRA_ID, id));
    }

    public static void startAll(Context context) {
        context.startService(new Intent(context, DownloadService.class).setAction(ACTION_START_ALL));
    }

    public static void pause(Context context, long id) {
        context.startService(new Intent(context, DownloadService.class)
                .setAction(ACTION_PAUSE).putExtra(EXTRA_ID, id));
    }

    public static void resume(Context context, long id) {
        context.startService(new Intent(context, DownloadService.class)
                .setAction(ACTION_RESUME).putExtra(EXTRA_ID, id));
    }

    public static void cancel(Context context, long id) {
        context.startService(new Intent(context, DownloadService.class)
                .setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        pool = Executors.newFixedThreadPool(MAX_PARALLEL);
        NotificationHelper.createChannels(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null) {
            stopIfIdle();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        long id = intent.getLongExtra(EXTRA_ID, 0);
        if (ACTION_PAUSE.equals(action)) {
            markPause(id, true);
        } else if (ACTION_RESUME.equals(action)) {
            markPause(id, false);
            schedule(id);
        } else if (ACTION_CANCEL.equals(action)) {
            cancelTask(id);
        } else if (ACTION_START_ALL.equals(action)) {
            for (DownloadEntity d : db().downloadDao().pending()) schedule(d.id);
        } else {
            schedule(id);
        }
        stopIfIdle();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private AppDatabase db() {
        return AppDatabase.get(this);
    }

    private void markPause(long id, boolean pause) {
        DownloadEntity d = db().downloadDao().byId(id);
        if (d == null) return;
        if (pause) {
            paused.add(id);
            d.status = DownloadEntity.PAUSED;
        } else {
            paused.remove(id);
            d.status = DownloadEntity.QUEUED;
        }
        d.updatedAt = System.currentTimeMillis();
        db().downloadDao().update(d);
        cancelTask(id);
        refresh();
    }

    private void cancelTask(long id) {
        synchronized (cancels) {
            for (HlsDownloader.Cancel c : cancels) c.cancel();
            cancels.clear();
        }
        running.remove(id);
        paused.remove(id);
        DownloadEntity d = db().downloadDao().byId(id);
        if (d != null) {
            d.status = DownloadEntity.CANCELLED;
            d.updatedAt = System.currentTimeMillis();
            db().downloadDao().update(d);
            if (d.path != null && !d.path.isEmpty()) DownloadStore.delete(new File(d.path));
        }
        NotificationHelper.cancelDownload(this, id);
        refresh();
    }

    /** Ставит загрузку в очередь, если ещё не идёт. */
    private void schedule(final long id) {
        if (id <= 0) return;
        synchronized (running) {
            if (running.contains(id)) return;
            if (running.size() >= MAX_PARALLEL) return;
            running.add(id);
        }
        startForegroundIfNeeded();
        pool.execute(() -> runDownload(id));
    }

    private void startForegroundIfNeeded() {
        DownloadEntity first = null;
        for (DownloadEntity d : db().downloadDao().pending()) {
            first = d;
            break;
        }
        if (first == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIF_ID,
                        NotificationHelper.downloadNotification(this, first, true),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                ServiceCompat.startForeground(this, NOTIF_ID,
                        NotificationHelper.downloadNotification(this, first, true), 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void runDownload(final long id) {
        final DownloadEntity d = db().downloadDao().byId(id);
        if (d == null) {
            running.remove(id);
            stopIfIdle();
            return;
        }
        if (paused.contains(id)) {
            running.remove(id);
            stopIfIdle();
            return;
        }

        d.status = DownloadEntity.RUNNING;
        d.error = "";
        d.updatedAt = System.currentTimeMillis();
        db().downloadDao().update(d);
        refresh();

        final HlsDownloader.Cancel cancel = new HlsDownloader.Cancel();
        synchronized (cancels) {
            cancels.add(cancel);
        }

        try {
            if (d.url == null || d.url.isEmpty()) throw new java.io.IOException("Нет прямой ссылки на поток");
            File target = d.path != null && !d.path.isEmpty()
                    ? new File(d.path)
                    : DownloadStore.fileFor(this, d.title, d.episode, d.quality, d.kind);
            if (target.getParentFile() != null && !target.getParentFile().exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.getParentFile().mkdirs();
            }

            HlsDownloader.Result result = HlsDownloader.download(Net.client(), d.url, d.referer, d.quality, target,
                    cancel, (percent, loadedBytes, segmentsDone, segmentsTotal) -> {
                        d.progress = percent;
                        d.sizeBytes = loadedBytes;
                        d.segmentsDone = segmentsDone;
                        d.segmentsTotal = segmentsTotal;
                        d.updatedAt = System.currentTimeMillis();
                        db().downloadDao().update(d);
                        NotificationHelper.notifyDownload(DownloadService.this, d, true);
                        DownloadBus.event(d);
                    });

            d.status = DownloadEntity.DONE;
            d.progress = 100;
            d.path = result.file.getAbsolutePath();
            d.sizeBytes = result.bytes;
            d.segmentsDone = result.segments;
            d.segmentsTotal = result.segments;
            d.error = "";
            d.updatedAt = System.currentTimeMillis();
            db().downloadDao().update(d);
            NotificationHelper.notifyDownload(DownloadService.this, d, false);
        } catch (Throwable t) {
            String message = t.getMessage() == null ? "Ошибка скачивания" : t.getMessage();
            if ("cancelled".equals(message)) {
                d.status = DownloadEntity.CANCELLED;
            } else {
                d.status = DownloadEntity.ERROR;
                d.error = message.length() > 140 ? message.substring(0, 140) : message;
                NotificationHelper.notifyDownload(this, d, false);
            }
            d.updatedAt = System.currentTimeMillis();
            db().downloadDao().update(d);
        } finally {
            synchronized (cancels) {
                cancels.remove(cancel);
            }
            running.remove(id);
            refresh();
            stopIfIdle();
            // подхватываем следующую очередь
            for (DownloadEntity next : db().downloadDao().pending()) {
                if (next.status == DownloadEntity.QUEUED && !paused.contains(next.id)) {
                    schedule(next.id);
                    break;
                }
            }
        }
    }

    /** Обновляет сводное уведомление и шину для UI. */
    private void refresh() {
        List<DownloadEntity> all = db().downloadDao().all();
        DownloadBus.publish(all);
        DownloadEntity active = null;
        for (DownloadEntity d : all) {
            if (d.status == DownloadEntity.RUNNING || d.status == DownloadEntity.QUEUED) {
                active = d;
                break;
            }
        }
        if (active != null) {
            NotificationHelper.notifyDownload(this, active, true);
        }
    }

    private void stopIfIdle() {
        if (!running.isEmpty()) return;
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        synchronized (cancels) {
            for (HlsDownloader.Cancel c : cancels) c.cancel();
            cancels.clear();
        }
        if (pool != null) pool.shutdownNow();
        super.onDestroy();
    }

    /** Поставить серию в очередь: используется экранами. */
    public static long add(Context context, DownloadEntity entity) {
        long id = AppDatabase.get(context).downloadDao().insert(entity);
        enqueue(context, id);
        return id;
    }

    /** Есть ли незавершённые загрузки. */
    public static boolean hasPending(Context context) {
        return !new ArrayList<>(AppDatabase.get(context).downloadDao().pending()).isEmpty();
    }
}
