package ru.kelemnfno.anime.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY updated_at DESC LIMIT 60")
    LiveData<List<HistoryEntity>> observeAll();

    @Query("SELECT * FROM history ORDER BY updated_at DESC LIMIT 60")
    List<HistoryEntity> all();

    @Query("SELECT * FROM history WHERE slug = :slug LIMIT 1")
    HistoryEntity bySlug(String slug);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(HistoryEntity entity);

    @Query("DELETE FROM history WHERE slug = :slug")
    void deleteBySlug(String slug);

    @Query("DELETE FROM history")
    void clear();
}
