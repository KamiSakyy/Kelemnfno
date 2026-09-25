package ru.kelemnfno.anime.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

@Dao
public interface WatchedDao {
    @Query("SELECT episode FROM watched WHERE slug = :slug")
    LiveData<List<String>> observeBySlug(String slug);

    @Query("SELECT episode FROM watched WHERE slug = :slug")
    List<String> bySlug(String slug);

    @Query("SELECT COUNT(*) FROM watched WHERE slug = :slug")
    int countFor(String slug);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(WatchedEntity entity);

    /** Отметить серию просмотренной (дубликаты игнорируются). */
    @Transaction
    default void mark(String slug, String episode) {
        WatchedEntity entity = new WatchedEntity();
        entity.slug = slug;
        entity.episode = episode;
        entity.markedAt = System.currentTimeMillis();
        insert(entity);
    }

    @Query("DELETE FROM watched WHERE slug = :slug AND episode = :episode")
    void delete(String slug, String episode);

    @Query("DELETE FROM watched WHERE slug = :slug")
    void resetSlug(String slug);
}
