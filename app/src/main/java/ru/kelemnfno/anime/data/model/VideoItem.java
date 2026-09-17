package ru.kelemnfno.anime.data.model;

import com.google.gson.annotations.SerializedName;

/** Один вариант серии: плеер + озвучка + iframe. */
public class VideoItem {
    @SerializedName("video_id")
    public long videoId;
    public VideoData data;
    /** Номер серии строкой: "1", "2.5", "ONA 1". */
    public String number;
    public long date;
    @SerializedName("iframe_url")
    public String iframeUrl;
    public int index;
    public Skips skips;
    public int views;
    public double duration;
}
