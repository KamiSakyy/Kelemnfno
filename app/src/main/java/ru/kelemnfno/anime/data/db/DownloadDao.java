package ru.kelemnfno.anime.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY created_at DESC")
    LiveData<List<DownloadEntity>> observeAll();

    @Query("SELECT * FROM downloads ORDER BY created_at DESC")
    List<DownloadEntity> all();

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    DownloadEntity byId(long id);

    @Query("SELECT * FROM downloads WHERE status IN (0, 1, 2) ORDER BY created_at ASC")
    List<DownloadEntity> pending();

    @Query("SELECT * FROM downloads WHERE status = 3 ORDER BY created_at DESC")
    LiveData<List<DownloadEntity>> observeFinished();

    @Insert
    long insert(DownloadEntity entity);

    @Update
    void update(DownloadEntity entity);

    @Query("DELETE FROM downloads WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM downloads WHERE status IN (3, 4, 5)")
    void clearFinished();

    @Query("SELECT * FROM downloads WHERE slug = :slug AND episode = :episode AND voice = :voice LIMIT 1")
    DownloadEntity existing(String slug, String episode, String voice);
}
