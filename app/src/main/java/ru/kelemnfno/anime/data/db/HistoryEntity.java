package ru.kelemnfno.anime.data.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** История просмотра + позиция для продолжения. */
@Entity(tableName = "history")
public class HistoryEntity {
    @PrimaryKey
    @NonNull
    public String slug = "";
    @ColumnInfo(name = "anime_id")
    public int animeId;
    public String title = "";
    public String poster = "";
    /** Номер серии строкой — как отдаёт API. */
    public String episode = "";
    public String dubbing = "";
    public int total;
    @ColumnInfo(name = "position_ms")
    public long positionMs;
    @ColumnInfo(name = "duration_ms")
    public long durationMs;
    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
