package ru.kelemnfno.anime.data.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Избранное. Хранит счётчик серий — по нему ловим новые серии. */
@Entity(tableName = "favorites")
public class FavoriteEntity {
    @PrimaryKey
    @NonNull
    public String slug = "";
    @ColumnInfo(name = "anime_id")
    public int animeId;
    public String title = "";
    public String poster = "";
    public int year;
    public String type = "";
    @ColumnInfo(name = "added_at")
    public long addedAt;
    /** Сколько серий было известно на момент последней проверки. */
    @ColumnInfo(name = "episode_count")
    public int episodeCount;
    /** Озвучка, на которой смотрит пользователь. */
    public String dubbing = "";
    /** Включены ли уведомления по этому тайтлу. */
    public boolean notify = true;
    /** Статус тайтла (ongoing/released/announcement). */
    public String status = "";
    /** Дата выхода следующей серии (мс). */
    @ColumnInfo(name = "next_date")
    public long nextDate;
}
