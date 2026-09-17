package ru.kelemnfno.anime.notify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import ru.kelemnfno.anime.data.prefs.Prefs;
import ru.kelemnfno.anime.download.DownloadService;

/** После перезагрузки/обновления восстанавливаем расписание проверок и загрузки. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (Prefs.get(context).settings().notifyNewEpisodes) NewEpisodeWorker.schedule(context);
        if (DownloadService.hasPending(context)) DownloadService.startAll(context);
    }
}
