package ru.kelemnfno.anime.data.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Кэш подобранного маршрута серии: не дёргаем источники повторно. */
@Entity(tableName = "episode_routes", indices = {@Index(value = {"slug", "episode", "voice"}, unique = true)})
public class EpisodeStateEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String slug = "";
    public String episode = "";
    public String voice = "";
    public int quality;
    public String url = "";
    public String referer = "";
    public String kind = "hls";
    @ColumnInfo(name = "saved_at")
    public long savedAt;
}
