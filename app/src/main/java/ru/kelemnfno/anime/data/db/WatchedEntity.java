package ru.kelemnfno.anime.data.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Отмеченные просмотренными серии. */
@Entity(tableName = "watched", indices = {@Index(value = {"slug", "episode"}, unique = true)})
public class WatchedEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String slug = "";
    public String episode = "";
    @ColumnInfo(name = "marked_at")
    public long markedAt;
}
