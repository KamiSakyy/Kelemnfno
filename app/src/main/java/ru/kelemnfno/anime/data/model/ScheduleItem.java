package ru.kelemnfno.anime.data.model;

import com.google.gson.annotations.SerializedName;

/** Строка расписания выхода серий. */
public class ScheduleItem {
    public String description;
    public Poster poster;
    public String title;
    @SerializedName("anime_url")
    public String animeUrl;
    @SerializedName("anime_id")
    public int animeId;
    public Episodes episodes;
}
