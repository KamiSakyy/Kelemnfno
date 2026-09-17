package ru.kelemnfno.anime.data.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {FavoriteEntity.class, HistoryEntity.class, WatchedEntity.class,
                DownloadEntity.class, EpisodeStateEntity.class},
        version = 3,
        exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract FavoriteDao favoriteDao();

    public abstract HistoryDao historyDao();

    public abstract WatchedDao watchedDao();

    public abstract DownloadDao downloadDao();

    public abstract EpisodeStateDao episodeStateDao();

    public static AppDatabase get(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "kelemnfno.db")
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}
