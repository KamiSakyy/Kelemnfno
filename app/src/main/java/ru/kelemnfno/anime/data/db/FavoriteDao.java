package ru.kelemnfno.anime.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY added_at DESC")
    LiveData<List<FavoriteEntity>> observeAll();

    @Query("SELECT * FROM favorites ORDER BY added_at DESC")
    List<FavoriteEntity> all();

    @Query("SELECT * FROM favorites WHERE slug = :slug LIMIT 1")
    FavoriteEntity bySlug(String slug);

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE slug = :slug)")
    boolean contains(String slug);

    @Query("SELECT * FROM favorites WHERE notify = 1")
    List<FavoriteEntity> notifyable();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(FavoriteEntity entity);

    @Delete
    void delete(FavoriteEntity entity);

    @Query("DELETE FROM favorites WHERE slug = :slug")
    void deleteBySlug(String slug);

    @Query("UPDATE favorites SET episode_count = :count, next_date = :nextDate, status = :status WHERE slug = :slug")
    void updateEpisodeState(String slug, int count, long nextDate, String status);
}
