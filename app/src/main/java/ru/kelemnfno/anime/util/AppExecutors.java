package ru.kelemnfno.anime.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Три пула: сеть/БД, тяжёлый подбор источников, UI-поток. */
public final class AppExecutors {

    private static final AppExecutors INSTANCE = new AppExecutors();

    private final ExecutorService io = Executors.newFixedThreadPool(6);
    private final ExecutorService heavy = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService scheduled = Executors.newScheduledThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    private AppExecutors() {
    }

    public static AppExecutors get() {
        return INSTANCE;
    }

    public ExecutorService io() {
        return io;
    }

    public ExecutorService heavy() {
        return heavy;
    }

    public ScheduledExecutorService scheduled() {
        return scheduled;
    }

    public void post(Runnable r) {
        main.post(r);
    }

    public void postDelayed(Runnable r, long delayMs) {
        main.postDelayed(r, delayMs);
    }

    /** Задача в фоне с колбэком на главном потоке. */
    public <T> void run(Task<T> task, Callback<T> callback) {
        io.execute(() -> {
            try {
                T value = task.call();
                main.post(() -> callback.onResult(value, null));
            } catch (Throwable t) {
                main.post(() -> callback.onResult(null, t));
            }
        });
    }

    public interface Task<T> {
        T call() throws Exception;
    }

    public interface Callback<T> {
        void onResult(T value, Throwable error);
    }
}
