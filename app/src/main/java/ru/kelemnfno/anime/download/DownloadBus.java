package ru.kelemnfno.anime.download;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;

import ru.kelemnfno.anime.data.db.DownloadEntity;

/** Мост сервис → UI: сервис и экраны живут в одном процессе. */
public final class DownloadBus {

    private static final MutableLiveData<List<DownloadEntity>> STATE =
            new MutableLiveData<>(new ArrayList<>());
    private static final MutableLiveData<DownloadEntity> EVENT = new MutableLiveData<>();

    private DownloadBus() {
    }

    public static LiveData<List<DownloadEntity>> state() {
        return STATE;
    }

    public static LiveData<DownloadEntity> events() {
        return EVENT;
    }

    public static void publish(List<DownloadEntity> rows) {
        STATE.postValue(rows == null ? new ArrayList<>() : new ArrayList<>(rows));
    }

    public static void event(DownloadEntity row) {
        EVENT.postValue(row);
    }
}
