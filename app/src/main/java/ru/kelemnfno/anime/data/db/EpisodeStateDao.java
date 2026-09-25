package ru.kelemnfno.anime.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface EpisodeStateDao {
    @Query("SELECT * FROM episode_routes WHERE slug = :slug AND episode = :episode AND voice = :voice ORDER BY saved_at DESC LIMIT 1")
    EpisodeStateEntity find(String slug, String episode, String voice);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(EpisodeStateEntity entity);

    @Query("DELETE FROM episode_routes WHERE slug = :slug")
    void clearSlug(String slug);

    @Query("DELETE FROM episode_routes WHERE saved_at < :before")
    void clearOlderThan(long before);
}
