package ru.kelemnfno.anime.data.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Загрузка серии: очередь, прогресс, путь к файлу. */
@Entity(tableName = "downloads")
public class DownloadEntity {
    public static final int QUEUED = 0;
    public static final int RUNNING = 1;
    public static final int PAUSED = 2;
    public static final int DONE = 3;
    public static final int ERROR = 4;
    public static final int CANCELLED = 5;

    @PrimaryKey(autoGenerate = true)
    public long id;
    public String slug = "";
    @ColumnInfo(name = "anime_id")
    public int animeId;
    public String title = "";
    public String episode = "";
    public String voice = "";
    public int quality;
    /** Прямой поток (m3u8/mp4). */
    public String url = "";
    public String kind = "hls";
    public String referer = "";
    public String poster = "";
    @ColumnInfo(name = "file_name")
    public String fileName = "";
    /** Абсолютный путь готового файла. */
    public String path = "";
    @ColumnInfo(name = "size_bytes")
    public long sizeBytes;
    public int status = QUEUED;
    public int progress;
    @ColumnInfo(name = "segments_done")
    public int segmentsDone;
    @ColumnInfo(name = "segments_total")
    public int segmentsTotal;
    public String error = "";
    @ColumnInfo(name = "created_at")
    public long createdAt;
    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
